package com.example.network;

import com.example.model.ProxyConfig;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
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

    private UpstreamProxyConnector() {}

    public static Socket openRawUpstreamSocket(ProxyConfig proxy, int timeoutMs) throws IOException {
        if (proxy == null || proxy.getHost().isEmpty() || proxy.getPort() <= 0) {
            throw new IOException("Invalid proxy configuration");
        }
        Socket tcp = new Socket();
        try {
            tcp.connect(new InetSocketAddress(proxy.getHost(), proxy.getPort()), timeoutMs);
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
        Socket upstream = openRawUpstreamSocket(proxy, timeoutMs);
        try {
            if (proxy.isSocks5Protocol()) {
                performSocks5Connect(upstream, proxy, targetHost, targetPort);
            } else {
                performHttpConnect(upstream, proxy, targetHost, targetPort);
            }
            return upstream;
        } catch (IOException e) {
            closeQuietly(upstream);
            throw e;
        }
    }

    private static void performHttpConnect(Socket upstream, ProxyConfig proxy, String targetHost, int targetPort)
            throws IOException {
        StringBuilder req = new StringBuilder();
        req.append("CONNECT ").append(targetHost).append(":").append(targetPort).append(" HTTP/1.1\r\n");
        req.append("Host: ").append(targetHost).append(":").append(targetPort).append("\r\n");
        if (proxy.hasAuth()) {
            req.append("Proxy-Authorization: Basic ").append(proxy.getBasicAuthToken()).append("\r\n");
        }
        req.append("\r\n");

        OutputStream out = upstream.getOutputStream();
        out.write(req.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();

        InputStream in = upstream.getInputStream();
        String statusLine = readAsciiLine(in);
        if (statusLine == null) {
            throw new IOException("Proxy closed connection during CONNECT");
        }
        int code = ProxyTester.parseHttpStatusCode(statusLine);
        if (code == 407) {
            throw new ProxyAuthException("Lỗi 407: Sai tài khoản hoặc mật khẩu Proxy");
        }
        if (code < 200 || code >= 300) {
            throw new IOException("Proxy CONNECT returned HTTP " + code);
        }
        String line;
        while ((line = readAsciiLine(in)) != null && !line.isEmpty()) {
            // Drain CONNECT response headers
        }
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
            throw new ProxyAuthException("SOCKS5 server rejected authentication methods");
        }
        if (method == 0x02) {
            if (!proxy.hasAuth()) {
                throw new ProxyAuthException("SOCKS5 requires username/password");
            }
            byte[] userBytes = proxy.getUsername().getBytes(StandardCharsets.UTF_8);
            byte[] passBytes = proxy.getPassword().getBytes(StandardCharsets.UTF_8);
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

    private static String readAsciiLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') sb.append((char) b);
            if (sb.length() > 4096) break;
        }
        return (b == -1 && sb.length() == 0) ? null : sb.toString();
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
