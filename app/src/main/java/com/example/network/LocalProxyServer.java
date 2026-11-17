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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pure Java multi-threaded local HTTP/HTTPS CONNECT proxy relay listening on 127.0.0.1.
 * Forwards requests to the currently active upstream HTTP proxy and transparently injects
 * Basic Proxy-Authorization headers, enabling instant hot-swapping between proxies.
 */
public class LocalProxyServer {

    public interface EventListener {
        void onUpstreamSuccess(long proxyId);
        void onUpstreamFailure(long proxyId, String reason);
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
    private int localPort = 0;

    public void setEventListener(EventListener listener) {
        this.eventListener = listener;
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

            upstream = new Socket();
            upstream.connect(new InetSocketAddress(proxy.getHost(), proxy.getPort()), UPSTREAM_CONNECT_TIMEOUT_MS);
            upstream.setSoTimeout(30000);

            String requestLine = lines[0];
            boolean isConnect = requestLine.toUpperCase(Locale.ROOT).startsWith("CONNECT ");

            StringBuilder rebuilt = new StringBuilder();
            rebuilt.append(requestLine).append("\r\n");
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                if (line.isEmpty()) continue;
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.startsWith("proxy-authorization:") || lower.startsWith("proxy-connection:")) {
                    continue;
                }
                rebuilt.append(line).append("\r\n");
            }
            if (proxy.hasAuth()) {
                rebuilt.append("Proxy-Authorization: Basic ")
                        .append(proxy.getBasicAuthToken())
                        .append("\r\n");
            }
            rebuilt.append("Proxy-Connection: Keep-Alive\r\n");
            rebuilt.append("\r\n");

            byte[] outHeader = rebuilt.toString().getBytes(StandardCharsets.ISO_8859_1);
            OutputStream upstreamOut = upstream.getOutputStream();
            InputStream upstreamIn = upstream.getInputStream();

            upstreamOut.write(outHeader);
            upstreamOut.flush();
            bytesOut.addAndGet(outHeader.length);

            if (isConnect) {
                byte[] upstreamRespHeaders = readHttpHeaderBlock(upstreamIn);
                if (upstreamRespHeaders == null || upstreamRespHeaders.length == 0) {
                    notifyFailure(proxy.getId(), "Upstream đóng kết nối khi CONNECT");
                    sendBadGateway(clientOut, "Upstream closed connection");
                    return;
                }
                String respStr = new String(upstreamRespHeaders, StandardCharsets.ISO_8859_1);
                String statusLine = respStr.split("\r\n", 2)[0];
                int statusCode = ProxyTester.parseHttpStatusCode(statusLine);
                clientOut.write(upstreamRespHeaders);
                clientOut.flush();
                bytesIn.addAndGet(upstreamRespHeaders.length);

                if (statusCode >= 200 && statusCode < 300) {
                    notifySuccess(proxy.getId());
                    relayBidirectional(client, upstream, clientIn, clientOut, upstreamIn, upstreamOut);
                } else if (statusCode == 407) {
                    notifyFailure(proxy.getId(), "Lỗi xác thực Proxy (407)");
                }
            } else {
                notifySuccess(proxy.getId());
                relayBidirectional(client, upstream, clientIn, clientOut, upstreamIn, upstreamOut);
            }
        } catch (IOException e) {
            ProxyConfig current = activeProxyRef.get();
            if (current != null) {
                notifyFailure(current.getId(), e.getMessage() != null ? e.getMessage() : "Lỗi kết nối Upstream");
            }
        } finally {
            activeConnections.decrementAndGet();
            closeQuietly(upstream);
            closeQuietly(client);
        }
    }

    private void relayBidirectional(Socket client, Socket upstream,
                                    InputStream clientIn, OutputStream clientOut,
                                    InputStream upstreamIn, OutputStream upstreamOut) {
        Thread uploadThread = new Thread(() -> {
            byte[] buf = new byte[BUFFER_SIZE];
            try {
                int n;
                while ((n = clientIn.read(buf)) != -1) {
                    upstreamOut.write(buf, 0, n);
                    upstreamOut.flush();
                    bytesOut.addAndGet(n);
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

    private void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
