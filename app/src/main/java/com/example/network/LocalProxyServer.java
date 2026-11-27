package com.example.network;

import com.example.model.ProxyConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Pure Java multi-threaded local HTTP/HTTPS CONNECT proxy relay listening on 127.0.0.1.
 * Forwards requests to the currently active upstream proxy, injects auth when configured,
 * and emits detailed DNS/send/tunnel/transfer lifecycle telemetry.
 */
public class LocalProxyServer {

    private static final Pattern IPV4_LITERAL = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$");

    public enum PolicyResult {
        ALLOW,
        BLOCK_FIREWALL,
        BLOCK_PRIVATE_ROUTING_DISABLED
    }

    public interface PolicyChecker {
        PolicyResult checkRequest(Socket clientSocket, String targetHost, int targetPort);
    }

    public interface EventListener {
        void onUpstreamSuccess(long proxyId);
        void onUpstreamFailure(long proxyId, String reason);
    }

    public interface TrafficListener {
        String resolveCallerPackage(Socket clientSocket);
        void onDnsDomainRequested(String callerPackage, String domain);
        void onSendStarted(String callerPackage, String methodTag, String target, ProxyConfig proxy);
        void onTunnelEstablished(String callerPackage, String methodTag, String target, long handshakeMs);
        void onTransferCompleted(String callerPackage, String target, long sentBytes, long recvBytes, long durationMs);
        void onSendFailed(String callerPackage, String methodTag, String target, String reason);
        void onRequestBlocked(String callerPackage, PolicyResult reason, String target);
    }

    private static final int UPSTREAM_CONNECT_TIMEOUT_MS = 8000;
    private static final int BUFFER_SIZE = 16384;

    private final AtomicReference<ProxyConfig> activeProxyRef = new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong bytesIn = new AtomicLong(0L);
    private final AtomicLong bytesOut = new AtomicLong(0L);
    private final AtomicInteger activeConnections = new AtomicInteger(0);

    private ServerSocket serverSocket;
    private ExecutorService clientPool;
    private Thread acceptThread;
    private EventListener eventListener;
    private PolicyChecker policyChecker;
    private TrafficListener trafficListener;
    private int localPort = 0;

    public void setEventListener(EventListener listener) {
        this.eventListener = listener;
    }

    public void setPolicyChecker(PolicyChecker checker) {
        this.policyChecker = checker;
    }

    public void setTrafficListener(TrafficListener listener) {
        this.trafficListener = listener;
    }

    public void setActiveProxy(ProxyConfig proxy) {
        this.activeProxyRef.set(proxy);
    }

    public ProxyConfig getActiveProxy() {
        return activeProxyRef.get();
    }

    public int getLocalPort() {
        return localPort;
    }

    public long getBytesIn() {
        return bytesIn.get();
    }

    public long getBytesOut() {
        return bytesOut.get();
    }

    public int getActiveConnections() {
        return activeConnections.get();
    }

    public synchronized int start(ProxyConfig initialProxy) throws IOException {
        if (running.get() && serverSocket != null && !serverSocket.isClosed()) {
            setActiveProxy(initialProxy);
            return localPort;
        }
        setActiveProxy(initialProxy);
        serverSocket = new ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"));
        localPort = serverSocket.getLocalPort();
        clientPool = Executors.newCachedThreadPool();
        running.set(true);

        acceptThread = new Thread(() -> {
            while (running.get() && serverSocket != null && !serverSocket.isClosed()) {
                try {
                    Socket client = serverSocket.accept();
                    clientPool.execute(() -> handleClient(client));
                } catch (IOException e) {
                    if (running.get()) {
                        // Continue unless stopped
                    }
                }
            }
        }, "ProxyFlow-LocalAccept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        return localPort;
    }

    public synchronized void stop() {
        running.set(false);
        localPort = 0;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            serverSocket = null;
        }
        if (clientPool != null) {
            clientPool.shutdownNow();
            clientPool = null;
        }
    }

    private void handleClient(Socket client) {
        activeConnections.incrementAndGet();
        Socket upstream = null;
        String callerPkg = "";
        String methodTag = "HTTP";
        String targetDisplay = "";
        long startNs = System.nanoTime();
        try {
            client.setSoTimeout(30000);
            InputStream clientIn = client.getInputStream();
            OutputStream clientOut = client.getOutputStream();

            byte[] headerBytes = readHttpHeaderBlock(clientIn);
            if (headerBytes == null || headerBytes.length == 0) {
                return;
            }

            String headerText = new String(headerBytes, StandardCharsets.ISO_8859_1);
            String[] lines = headerText.split("\r\n");
            if (lines.length == 0) {
                return;
            }

            ProxyConfig proxy = activeProxyRef.get();
            if (proxy == null) {
                sendBadGateway(clientOut, "No active proxy selected");
                return;
            }

            TrafficListener tListener = this.trafficListener;
            if (tListener != null) {
                callerPkg = tListener.resolveCallerPackage(client);
            }

            String requestLine = lines[0];
            boolean isConnect = requestLine.toUpperCase(Locale.ROOT).startsWith("CONNECT ");
            String httpMethod = extractMethod(requestLine);
            methodTag = isConnect ? "HTTPS" : httpMethod;
            TargetEndpoint endpoint = parseTargetEndpoint(requestLine, lines, isConnect);
            targetDisplay = (endpoint != null && !endpoint.host.isEmpty())
                    ? (endpoint.host + ":" + endpoint.port)
                    : "";

            if (tListener != null && endpoint != null && !endpoint.host.isEmpty()
                    && !isIpAddressLiteral(endpoint.host)) {
                tListener.onDnsDomainRequested(callerPkg, endpoint.host);
            }

            PolicyChecker checker = this.policyChecker;
            if (checker != null && endpoint != null && !endpoint.host.isEmpty()) {
                PolicyResult policyResult = checker.checkRequest(client, endpoint.host, endpoint.port);
                if (policyResult == PolicyResult.BLOCK_FIREWALL) {
                    if (tListener != null) {
                        tListener.onRequestBlocked(callerPkg, policyResult, targetDisplay);
                    }
                    sendForbidden(clientOut, "Blocked by TopProxy Firewall");
                    return;
                } else if (policyResult == PolicyResult.BLOCK_PRIVATE_ROUTING_DISABLED) {
                    if (tListener != null) {
                        tListener.onRequestBlocked(callerPkg, policyResult, targetDisplay);
                    }
                    sendForbidden(clientOut, "Private network routing via Proxy is disabled");
                    return;
                }
            }

            if (tListener != null && !targetDisplay.isEmpty()) {
                tListener.onSendStarted(callerPkg, methodTag, targetDisplay, proxy);
            }

            if (proxy.isSocks5Protocol()) {
                if (endpoint == null || endpoint.host.isEmpty()) {
                    sendBadGateway(clientOut, "Target host missing for SOCKS5 tunnel");
                    return;
                }
                ProxyConfig effectiveSocksProxy = proxy;
                if (!proxy.hasAuth()) {
                    ProxyConfig clientCreds = applyClientBasicAuthIfPresent(proxy, lines);
                    if (clientCreds != null) {
                        effectiveSocksProxy = clientCreds;
                    }
                }
                try {
                    upstream = UpstreamProxyConnector.openTunnel(
                            effectiveSocksProxy, endpoint.host, endpoint.port, UPSTREAM_CONNECT_TIMEOUT_MS);
                } catch (UpstreamProxyConnector.ProxyAuthException authEx) {
                    if (!proxy.hasAuth()) {
                        sendProxyAuthRequired(clientOut);
                        return;
                    }
                    throw authEx;
                }
                upstream.setSoTimeout(30000);
                OutputStream upstreamOut = upstream.getOutputStream();
                InputStream upstreamIn = upstream.getInputStream();

                notifySuccess(proxy.getId());
                if (tListener != null && !targetDisplay.isEmpty()) {
                    long handshakeMs = Math.max(1L, (System.nanoTime() - startNs) / 1_000_000L);
                    tListener.onTunnelEstablished(callerPkg, methodTag, targetDisplay, handshakeMs);
                }

                long initialSent = 0L;
                long initialRecv = 0L;
                if (isConnect) {
                    byte[] established = "HTTP/1.1 200 Connection Established\r\n\r\n"
                            .getBytes(StandardCharsets.ISO_8859_1);
                    clientOut.write(established);
                    clientOut.flush();
                    bytesIn.addAndGet(established.length);
                    initialRecv = established.length;
                } else {
                    byte[] directHttpHeaders = rebuildDirectHttpHeadersForTunnel(requestLine, lines);
                    upstreamOut.write(directHttpHeaders);
                    upstreamOut.flush();
                    bytesOut.addAndGet(directHttpHeaders.length);
                    initialSent = directHttpHeaders.length;
                }
                long[] counts = relayBidirectional(client, upstream, clientIn, clientOut, upstreamIn, upstreamOut,
                        initialSent, initialRecv);
                if (tListener != null && !targetDisplay.isEmpty()) {
                    long durationMs = Math.max(1L, (System.nanoTime() - startNs) / 1_000_000L);
                    tListener.onTransferCompleted(callerPkg, targetDisplay, counts[0], counts[1], durationMs);
                }
            } else {
                String requestTarget = extractRequestTarget(requestLine);
                String initialAuth = proxy.hasAuth()
                        ? ProxyAuthHelper.buildInitialProxyAuthHeaderValue(proxy)
                        : null;
                byte[] outHeader = buildUpstreamHttpHeaders(requestLine, lines, proxy.hasAuth(), initialAuth);

                long reqContentLength = parseHeaderContentLength(lines);
                boolean reqChunked = isChunkedTransfer(lines);
                boolean canIntercept407 = isConnect || (proxy.hasAuth() && !reqChunked && reqContentLength <= 65536L);
                byte[] reqBody = (!isConnect && canIntercept407 && reqContentLength > 0L)
                        ? readExactBytes(clientIn, (int) reqContentLength)
                        : new byte[0];

                upstream = UpstreamProxyConnector.openRawUpstreamSocket(proxy, UPSTREAM_CONNECT_TIMEOUT_MS);
                upstream.setSoTimeout(30000);
                OutputStream upstreamOut = upstream.getOutputStream();
                InputStream upstreamIn = upstream.getInputStream();

                upstreamOut.write(outHeader);
                if (reqBody.length > 0) {
                    upstreamOut.write(reqBody);
                }
                upstreamOut.flush();
                long totalSent = outHeader.length + reqBody.length;
                bytesOut.addAndGet(totalSent);

                if (canIntercept407) {
                    ProxyAuthHelper.HttpResponseHead head = ProxyAuthHelper.readHttpResponseHead(upstreamIn);
                    if (head == null) {
                        notifyFailure(proxy.getId(), "Upstream đóng kết nối");
                        if (tListener != null && !targetDisplay.isEmpty()) {
                            tListener.onSendFailed(callerPkg, methodTag, targetDisplay, "Upstream closed connection");
                        }
                        sendBadGateway(clientOut, "Upstream closed connection");
                        return;
                    }

                    if (head.getStatusCode() == 407 && proxy.hasAuth()) {
                        String challengeMethod = isConnect ? "CONNECT" : httpMethod;
                        String retryAuth = ProxyAuthHelper.buildChallengeResponseHeaderValue(
                                proxy, head.getProxyAuthenticateHeaders(), challengeMethod, requestTarget, initialAuth);
                        if (retryAuth != null) {
                            boolean canReuse = ProxyAuthHelper.drainResponseBodyForSocketReuse(upstreamIn, head);
                            if (!canReuse) {
                                closeQuietly(upstream);
                                upstream = UpstreamProxyConnector.openRawUpstreamSocket(proxy, UPSTREAM_CONNECT_TIMEOUT_MS);
                                upstream.setSoTimeout(30000);
                                upstreamOut = upstream.getOutputStream();
                                upstreamIn = upstream.getInputStream();
                            }
                            byte[] retryHeader = buildUpstreamHttpHeaders(requestLine, lines, true, retryAuth);
                            ProxyAuthHelper.HttpResponseHead retryHead = null;
                            try {
                                upstreamOut.write(retryHeader);
                                if (reqBody.length > 0) {
                                    upstreamOut.write(reqBody);
                                }
                                upstreamOut.flush();
                                long retryBytes = retryHeader.length + reqBody.length;
                                totalSent += retryBytes;
                                bytesOut.addAndGet(retryBytes);
                                retryHead = ProxyAuthHelper.readHttpResponseHead(upstreamIn);
                            } catch (IOException ioEx) {
                                if (!canReuse) throw ioEx;
                            }
                            if (retryHead == null && canReuse) {
                                closeQuietly(upstream);
                                upstream = UpstreamProxyConnector.openRawUpstreamSocket(proxy, UPSTREAM_CONNECT_TIMEOUT_MS);
                                upstream.setSoTimeout(30000);
                                upstreamOut = upstream.getOutputStream();
                                upstreamIn = upstream.getInputStream();
                                upstreamOut.write(retryHeader);
                                if (reqBody.length > 0) {
                                    upstreamOut.write(reqBody);
                                }
                                upstreamOut.flush();
                                long retryBytes = retryHeader.length + reqBody.length;
                                totalSent += retryBytes;
                                bytesOut.addAndGet(retryBytes);
                                retryHead = ProxyAuthHelper.readHttpResponseHead(upstreamIn);
                            }
                            if (retryHead == null) {
                                notifyFailure(proxy.getId(), "Upstream đóng kết nối khi xác thực");
                                if (tListener != null && !targetDisplay.isEmpty()) {
                                    tListener.onSendFailed(callerPkg, methodTag, targetDisplay, "Upstream closed connection");
                                }
                                sendBadGateway(clientOut, "Upstream closed connection");
                                return;
                            }
                            head = retryHead;
                        }
                    }

                    byte[] upstreamRespHeaders = head.getRawBytes();
                    int statusCode = head.getStatusCode();

                    if (statusCode == 407) {
                        if (proxy.hasAuth()) {
                            notifyFailure(proxy.getId(),
                                    ProxyAuthHelper.describe407Failure(proxy, head.getProxyAuthenticateHeaders()));
                        }
                        if (tListener != null && !targetDisplay.isEmpty()) {
                            tListener.onSendFailed(callerPkg, methodTag, targetDisplay, "HTTP 407");
                        }
                        clientOut.write(upstreamRespHeaders);
                        clientOut.flush();
                        bytesIn.addAndGet(upstreamRespHeaders.length);
                        drainResponseTailToClient(upstreamIn, clientOut, head);
                    } else if (isConnect && (statusCode < 200 || statusCode >= 300)) {
                        if (statusCode >= 500 && statusCode < 600) {
                            notifyFailure(proxy.getId(), "Lỗi máy chủ Proxy (HTTP " + statusCode + ")");
                        }
                        if (tListener != null && !targetDisplay.isEmpty()) {
                            tListener.onSendFailed(callerPkg, methodTag, targetDisplay, "HTTP " + statusCode);
                        }
                        clientOut.write(upstreamRespHeaders);
                        clientOut.flush();
                        bytesIn.addAndGet(upstreamRespHeaders.length);
                    } else {
                        if (statusCode >= 500 && statusCode < 600) {
                            notifyFailure(proxy.getId(), "Lỗi máy chủ Proxy (HTTP " + statusCode + ")");
                        } else {
                            notifySuccess(proxy.getId());
                        }
                        if (tListener != null && !targetDisplay.isEmpty()) {
                            long handshakeMs = Math.max(1L, (System.nanoTime() - startNs) / 1_000_000L);
                            tListener.onTunnelEstablished(callerPkg, methodTag, targetDisplay, handshakeMs);
                        }
                        clientOut.write(upstreamRespHeaders);
                        clientOut.flush();
                        bytesIn.addAndGet(upstreamRespHeaders.length);
                        long[] counts = relayBidirectional(client, upstream, clientIn, clientOut, upstreamIn, upstreamOut,
                                totalSent, upstreamRespHeaders.length);
                        if (tListener != null && !targetDisplay.isEmpty()) {
                            long durationMs = Math.max(1L, (System.nanoTime() - startNs) / 1_000_000L);
                            tListener.onTransferCompleted(callerPkg, targetDisplay, counts[0], counts[1], durationMs);
                        }
                    }
                } else {
                    notifySuccess(proxy.getId());
                    if (tListener != null && !targetDisplay.isEmpty()) {
                        long handshakeMs = Math.max(1L, (System.nanoTime() - startNs) / 1_000_000L);
                        tListener.onTunnelEstablished(callerPkg, methodTag, targetDisplay, handshakeMs);
                    }
                    long[] counts = relayBidirectional(client, upstream, clientIn, clientOut, upstreamIn, upstreamOut,
                            totalSent, 0L);
                    if (tListener != null && !targetDisplay.isEmpty()) {
                        long durationMs = Math.max(1L, (System.nanoTime() - startNs) / 1_000_000L);
                        tListener.onTransferCompleted(callerPkg, targetDisplay, counts[0], counts[1], durationMs);
                    }
                }
            }
        } catch (IOException e) {
            String reason = e.getMessage() != null ? e.getMessage() : "Lỗi kết nối Upstream";
            ProxyConfig current = activeProxyRef.get();
            if (current != null) {
                notifyFailure(current.getId(), reason);
            }
            TrafficListener tListener = this.trafficListener;
            if (tListener != null && !targetDisplay.isEmpty()) {
                tListener.onSendFailed(callerPkg, methodTag, targetDisplay, reason);
            }
        } finally {
            activeConnections.decrementAndGet();
            closeQuietly(upstream);
            closeQuietly(client);
        }
    }

    private static byte[] buildUpstreamHttpHeaders(String requestLine, String[] lines,
                                                   boolean stripIncomingAuth, String proxyAuthHeaderValue) {
        StringBuilder rebuilt = new StringBuilder();
        rebuilt.append(requestLine).append("\r\n");
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) continue;
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("proxy-connection:")
                    || (stripIncomingAuth && lower.startsWith("proxy-authorization:"))) {
                continue;
            }
            rebuilt.append(line).append("\r\n");
        }
        if (proxyAuthHeaderValue != null && !proxyAuthHeaderValue.isEmpty()) {
            rebuilt.append("Proxy-Authorization: ").append(proxyAuthHeaderValue).append("\r\n");
        }
        rebuilt.append("Proxy-Connection: Keep-Alive\r\n");
        rebuilt.append("\r\n");
        return rebuilt.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String extractRequestTarget(String requestLine) {
        if (requestLine == null) return "/";
        String[] parts = requestLine.trim().split("\\s+");
        return parts.length >= 2 ? parts[1] : "/";
    }

    private static long parseHeaderContentLength(String[] lines) {
        if (lines == null) return -1L;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line == null) continue;
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("content-length:")) {
                try {
                    return Long.parseLong(line.substring("content-length:".length()).trim());
                } catch (NumberFormatException ignored) {
                    return -1L;
                }
            }
        }
        return -1L;
    }

    private static boolean isChunkedTransfer(String[] lines) {
        if (lines == null) return false;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line == null) continue;
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) {
                return true;
            }
        }
        return false;
    }

    private static byte[] readExactBytes(InputStream in, int length) throws IOException {
        if (length <= 0) return new byte[0];
        byte[] buf = new byte[length];
        int off = 0;
        while (off < length) {
            int read = in.read(buf, off, length - off);
            if (read == -1) break;
            off += read;
        }
        if (off == length) {
            return buf;
        }
        byte[] partial = new byte[off];
        System.arraycopy(buf, 0, partial, 0, off);
        return partial;
    }

    private void drainResponseTailToClient(InputStream upstreamIn, OutputStream clientOut,
                                           ProxyAuthHelper.HttpResponseHead head) {
        long len = head.getContentLength();
        if (len <= 0L && !head.isChunked()) {
            return;
        }
        try {
            byte[] buf = new byte[4096];
            long remaining = len > 0L ? len : 65536L;
            while (remaining > 0L) {
                int toRead = (int) Math.min(buf.length, remaining);
                int read = upstreamIn.read(buf, 0, toRead);
                if (read == -1) break;
                clientOut.write(buf, 0, read);
                bytesIn.addAndGet(read);
                remaining -= read;
            }
            clientOut.flush();
        } catch (IOException ignored) {
        }
    }

    private static byte[] rebuildDirectHttpHeadersForTunnel(String requestLine, String[] lines) {
        String[] parts = requestLine.split(" ", 3);
        String rebuiltFirstLine = requestLine;
        if (parts.length == 3) {
            String target = parts[1];
            if (target.startsWith("http://") || target.startsWith("https://")) {
                String noScheme = target.substring(target.indexOf("://") + 3);
                int slashIdx = noScheme.indexOf('/');
                String path = slashIdx >= 0 ? noScheme.substring(slashIdx) : "/";
                rebuiltFirstLine = parts[0] + " " + path + " " + parts[2];
            }
        }
        StringBuilder rebuilt = new StringBuilder();
        rebuilt.append(rebuiltFirstLine).append("\r\n");
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) continue;
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("proxy-authorization:") || lower.startsWith("proxy-connection:")) {
                continue;
            }
            rebuilt.append(line).append("\r\n");
        }
        rebuilt.append("Connection: keep-alive\r\n\r\n");
        return rebuilt.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private long[] relayBidirectional(Socket client, Socket upstream,
                                      InputStream clientIn, OutputStream clientOut,
                                      InputStream upstreamIn, OutputStream upstreamOut,
                                      long initialSent, long initialRecv) {
        final AtomicLong sessionOut = new AtomicLong(initialSent);
        final AtomicLong sessionIn = new AtomicLong(initialRecv);
        Thread uploadThread = new Thread(() -> {
            byte[] buf = new byte[BUFFER_SIZE];
            try {
                int n;
                while ((n = clientIn.read(buf)) != -1) {
                    upstreamOut.write(buf, 0, n);
                    upstreamOut.flush();
                    bytesOut.addAndGet(n);
                    sessionOut.addAndGet(n);
                }
            } catch (IOException ignored) {
            } finally {
                try {
                    upstream.shutdownOutput();
                } catch (IOException ignored) {
                }
            }
        }, "ProxyFlow-Up");
        uploadThread.setDaemon(true);
        uploadThread.start();

        byte[] buf = new byte[BUFFER_SIZE];
        try {
            int n;
            while ((n = upstreamIn.read(buf)) != -1) {
                clientOut.write(buf, 0, n);
                clientOut.flush();
                bytesIn.addAndGet(n);
                sessionIn.addAndGet(n);
            }
        } catch (IOException ignored) {
        } finally {
            try {
                client.shutdownOutput();
            } catch (IOException ignored) {
            }
        }

        try {
            uploadThread.join(1500);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        return new long[]{sessionOut.get(), sessionIn.get()};
    }

    private static String extractMethod(String requestLine) {
        if (requestLine == null) return "HTTP";
        int sp = requestLine.indexOf(' ');
        if (sp <= 0) return "HTTP";
        String m = requestLine.substring(0, sp).trim().toUpperCase(Locale.ROOT);
        return m.isEmpty() ? "HTTP" : m;
    }

    private static boolean isIpAddressLiteral(String host) {
        if (host == null || host.isEmpty()) return true;
        return IPV4_LITERAL.matcher(host).matches() || host.contains(":");
    }

    private byte[] readHttpHeaderBlock(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
        int state = 0;
        int b;
        int total = 0;
        while (total < 32768 && (b = in.read()) != -1) {
            baos.write(b);
            total++;
            if (state == 0 && b == '\r') state = 1;
            else if (state == 1 && b == '\n') state = 2;
            else if (state == 2 && b == '\r') state = 3;
            else if (state == 3 && b == '\n') break;
            else state = (b == '\r') ? 1 : 0;
        }
        return baos.toByteArray();
    }

    private void sendBadGateway(OutputStream out, String message) {
        try {
            String resp = "HTTP/1.1 502 Bad Gateway\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\n" + message;
            out.write(resp.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException ignored) {
        }
    }

    private void sendForbidden(OutputStream out, String message) {
        try {
            String resp = "HTTP/1.1 403 Forbidden\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\n" + message;
            out.write(resp.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException ignored) {
        }
    }

    private void sendProxyAuthRequired(OutputStream out) {
        try {
            String resp = "HTTP/1.1 407 Proxy Authentication Required\r\n"
                    + "Proxy-Authenticate: Basic realm=\"ProxyFlow\"\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(resp.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        } catch (IOException ignored) {
        }
    }

    private static TargetEndpoint parseTargetEndpoint(String requestLine, String[] lines, boolean isConnect) {
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            return null;
        }
        String target = parts[1].trim();
        if (isConnect) {
            return splitHostPort(target, 443);
        }
        if (target.startsWith("http://") || target.startsWith("https://")) {
            boolean https = target.startsWith("https://");
            String noScheme = target.substring(target.indexOf("://") + 3);
            int slash = noScheme.indexOf('/');
            String authority = slash >= 0 ? noScheme.substring(0, slash) : noScheme;
            return splitHostPort(authority, https ? 443 : 80);
        }
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.toLowerCase(Locale.ROOT).startsWith("host:")) {
                String hostHeader = line.substring(5).trim();
                return splitHostPort(hostHeader, 80);
            }
        }
        return null;
    }

    private static TargetEndpoint splitHostPort(String authority, int defaultPort) {
        if (authority == null || authority.isEmpty()) return null;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close > 0) {
                String host = authority.substring(1, close);
                int port = defaultPort;
                if (close + 1 < authority.length() && authority.charAt(close + 1) == ':') {
                    try {
                        port = Integer.parseInt(authority.substring(close + 2));
                    } catch (NumberFormatException ignored) {
                    }
                }
                return new TargetEndpoint(host, port);
            }
        }
        int colon = authority.lastIndexOf(':');
        if (colon > 0 && colon < authority.length() - 1) {
            try {
                int port = Integer.parseInt(authority.substring(colon + 1));
                return new TargetEndpoint(authority.substring(0, colon), port);
            } catch (NumberFormatException ignored) {
            }
        }
        return new TargetEndpoint(authority, defaultPort);
    }

    private static final class TargetEndpoint {
        final String host;
        final int port;

        TargetEndpoint(String host, int port) {
            this.host = host != null ? host : "";
            this.port = port;
        }
    }

    private void notifySuccess(long proxyId) {
        if (eventListener != null) {
            eventListener.onUpstreamSuccess(proxyId);
        }
    }

    private void notifyFailure(long proxyId, String reason) {
        if (eventListener != null) {
            eventListener.onUpstreamFailure(proxyId, reason);
        }
    }

    private static ProxyConfig applyClientBasicAuthIfPresent(ProxyConfig base, String[] lines) {
        if (base == null || lines == null) return null;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line == null) continue;
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("proxy-authorization:")) {
                String value = line.substring("proxy-authorization:".length()).trim();
                if (value.toLowerCase(Locale.ROOT).startsWith("basic ")) {
                    String b64 = value.substring(6).trim();
                    byte[] decoded = decodeBase64(b64);
                    if (decoded != null) {
                        String rawCreds = new String(decoded, StandardCharsets.UTF_8);
                        int colonIdx = rawCreds.indexOf(':');
                        String user = colonIdx >= 0 ? rawCreds.substring(0, colonIdx) : rawCreds;
                        String pass = colonIdx >= 0 ? rawCreds.substring(colonIdx + 1) : "";
                        if (!user.isEmpty()) {
                            ProxyConfig copy = base.copy();
                            copy.setAuthMethod(ProxyConfig.AuthMethod.USER_PASS);
                            copy.setUsername(user);
                            copy.setPassword(pass);
                            return copy;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static byte[] decodeBase64(String input) {
        if (input == null) return null;
        String clean = input.trim();
        if (clean.isEmpty() || (clean.length() % 4) != 0) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream((clean.length() * 3) / 4);
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (c == '=') break;
            int val;
            if (c >= 'A' && c <= 'Z') val = c - 'A';
            else if (c >= 'a' && c <= 'z') val = c - 'a' + 26;
            else if (c >= '0' && c <= '9') val = c - '0' + 52;
            else if (c == '+') val = 62;
            else if (c == '/') val = 63;
            else return null;
            buffer = (buffer << 6) | val;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((buffer >> bits) & 0xFF);
            }
        }
        return out.toByteArray();
    }

    private void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
