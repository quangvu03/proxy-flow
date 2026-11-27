package com.example.network;

import com.example.model.ProxyConfig;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Pure Java upstream proxy connector supporting HTTP, HTTP + TLS,
 * SOCKS5, SOCKS5 + UDP, and SOCKS5 + TLS with optional authentication.
 */
public final class UpstreamProxyConnector {

    public static class ProxyAuthException extends IOException {
        public ProxyAuthException(String message) {
            super(message);
        }
    }

    public static class ProxyAuthRequiredException extends ProxyAuthException {
        public ProxyAuthRequiredException(String message) {
            super(message);
        }
    }

    private UpstreamProxyConnector() {}

    public static Socket openRawUpstreamSocket(ProxyConfig proxy, int timeoutMs) throws IOException {
        if (proxy == null || proxy.getHost().isEmpty() || proxy.getPort() <= 0) {
            throw new IOException("Invalid proxy configuration");
        }
        Socket tcp = new Socket();
        try {
            try {
                tcp.connect(new InetSocketAddress(proxy.getHost(), proxy.getPort()), timeoutMs);
            } catch (SocketTimeoutException ste) {
                throw new SocketTimeoutException(
                        "Quá thời gian kết nối tới máy chủ " + proxy.getHost() + ":" + proxy.getPort());
            }
            tcp.setSoTimeout(timeoutMs);
            if (proxy.isTlsProtocol()) {
                SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                SSLSocket ssl = (SSLSocket) factory.createSocket(tcp, proxy.getHost(), proxy.getPort(), true);
                ssl.setSoTimeout(timeoutMs);
                ssl.startHandshake();
                return ssl;
            }
            return tcp;
        } catch (IOException e) {
            closeQuietly(tcp);
            throw e;
        }
    }

    public static Socket openTunnel(ProxyConfig proxy, String targetHost, int targetPort, int timeoutMs)
            throws IOException {
        if (proxy != null && proxy.isSocks5Protocol()) {
            Socket upstream = openRawUpstreamSocket(proxy, timeoutMs);
            try {
                performSocks5Connect(upstream, proxy, targetHost, targetPort);
                return upstream;
            } catch (IOException e) {
                closeQuietly(upstream);
                throw e;
            }
        }
        if (proxy == null) {
            throw new IOException("Invalid proxy configuration");
        }
        return performHttpConnectWithAuthRetry(proxy, targetHost, targetPort, timeoutMs);
    }

    private static Socket performHttpConnectWithAuthRetry(
            ProxyConfig proxy, String targetHost, int targetPort, int timeoutMs) throws IOException {
        ProxyAuthHelper.TunnelAttemptResult attempt = ProxyAuthHelper.sendHttpConnectWithAuthRetry(
                proxy, targetHost, targetPort, timeoutMs);
        int code = attempt.responseHead.getStatusCode();
        if (code < 200 || code >= 300) {
            closeQuietly(attempt.socket);
            throw new IOException("Proxy CONNECT returned HTTP " + code);
        }
        return attempt.socket;
    }

    private static void performSocks5Connect(Socket upstream, ProxyConfig proxy, String targetHost, int targetPort)
            throws IOException {
        OutputStream out = upstream.getOutputStream();
        DataInputStream in = new DataInputStream(upstream.getInputStream());

        boolean offerUserPass = proxy.hasAuth();
        if (offerUserPass) {
            out.write(new byte[]{0x05, 0x02, 0x00, 0x02});
        } else {
            out.write(new byte[]{0x05, 0x01, 0x00});
        }
        out.flush();

        int ver = in.readUnsignedByte();
        int method = in.readUnsignedByte();
        if (ver != 0x05) {
            throw new IOException("Invalid SOCKS5 version: " + ver);
        }
        if (method == 0xFF) {
            if (!proxy.hasAuth()) {
                throw new ProxyAuthRequiredException("Proxy SOCKS5 yêu cầu xác thực (vui lòng nhập tài khoản / mật khẩu)");
            }
            throw new ProxyAuthException("Lỗi xác thực SOCKS5: Máy chủ từ chối phương thức đăng nhập");
        }
        if (method == 0x02) {
            String rawUser = "";
            String rawPass = "";
            if (proxy.hasAuth()) {
                if (proxy.getAuthMethod() == ProxyConfig.AuthMethod.BEARER_TOKEN) {
                    rawUser = proxy.getUsername();
                    rawPass = proxy.getAuthToken();
                } else {
                    rawUser = proxy.getUsername();
                    rawPass = proxy.getPassword();
                }
            }
            byte[] userBytes = rawUser != null ? rawUser.getBytes(StandardCharsets.UTF_8) : new byte[0];
            byte[] passBytes = rawPass != null ? rawPass.getBytes(StandardCharsets.UTF_8) : new byte[0];
            if (userBytes.length > 255 || passBytes.length > 255) {
                throw new ProxyAuthException("SOCKS5 credentials exceed 255 bytes");
            }
            ByteArrayOutputStream authReq = new ByteArrayOutputStream();
            authReq.write(0x01);
            authReq.write(userBytes.length);
            authReq.write(userBytes);
            authReq.write(passBytes.length);
            authReq.write(passBytes);
            out.write(authReq.toByteArray());
            out.flush();

            int authVer = in.readUnsignedByte();
            int authStatus = in.readUnsignedByte();
            if (authVer != 0x01 || authStatus != 0x00) {
                if (!proxy.hasAuth()) {
                    throw new ProxyAuthRequiredException("Proxy SOCKS5 yêu cầu xác thực (vui lòng nhập tài khoản / mật khẩu)");
                }
                throw new ProxyAuthException("Lỗi xác thực SOCKS5: Sai tài khoản hoặc mật khẩu");
            }
        } else if (method != 0x00) {
            throw new IOException("Unsupported SOCKS5 auth method: " + method);
        }

        byte[] hostBytes = targetHost.getBytes(StandardCharsets.UTF_8);
        if (hostBytes.length > 255) {
            throw new IOException("Target host exceeds 255 bytes");
        }
        ByteArrayOutputStream req = new ByteArrayOutputStream();
        req.write(0x05); // VER
        req.write(0x01); // CMD = CONNECT
        req.write(0x00); // RSV
        req.write(0x03); // ATYP = DOMAINNAME
        req.write(hostBytes.length);
        req.write(hostBytes);
        req.write((targetPort >> 8) & 0xFF);
        req.write(targetPort & 0xFF);
        out.write(req.toByteArray());
        out.flush();

        int replyVer = in.readUnsignedByte();
        int rep = in.readUnsignedByte();
        in.readUnsignedByte(); // RSV
        int atyp = in.readUnsignedByte();
        if (replyVer != 0x05) {
            throw new IOException("Invalid SOCKS5 connect reply version");
        }
        if (atyp == 0x01) {
            in.readFully(new byte[4]);
        } else if (atyp == 0x03) {
            int len = in.readUnsignedByte();
            in.readFully(new byte[len]);
        } else if (atyp == 0x04) {
            in.readFully(new byte[16]);
        }
        in.readFully(new byte[2]); // BND.PORT

        if (rep != 0x00) {
            throw new IOException("SOCKS5 CONNECT failed with code " + rep);
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
