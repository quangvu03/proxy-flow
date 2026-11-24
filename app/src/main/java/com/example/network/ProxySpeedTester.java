package com.example.network;

import com.example.model.ProxyConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Pure Java diagnostic and network speed tester that measures real-world proxy
 * viability: TCP reachability, User/Pass authentication, HTTPS CONNECT tunneling,
 * average ping, download speed (Mbps), and upload speed (Mbps) through the upstream proxy.
 */
public final class ProxySpeedTester {

    private static final int CONNECT_TIMEOUT_MS = 7000;
    private static final int IO_TIMEOUT_MS = 12000;
    private static final int DOWNLOAD_TARGET_BYTES = 524288; // 512 KB
    private static final int UPLOAD_TARGET_BYTES = 131072;   // 128 KB

    private ProxySpeedTester() {}

    public enum AuthState {
        NOT_REQUIRED,
        VALID,
        FAILED_407,
        UNKNOWN
    }

    public static class DiagnosticResult {
        private final boolean tcpReachable;
        private final long tcpLatencyMs;
        private final AuthState authState;
        private final boolean httpsTunnelOk;
        private final boolean usable;
        private final String errorMessage;

        public DiagnosticResult(boolean tcpReachable, long tcpLatencyMs, AuthState authState,
                                boolean httpsTunnelOk, boolean usable, String errorMessage) {
            this.tcpReachable = tcpReachable;
            this.tcpLatencyMs = tcpLatencyMs;
            this.authState = authState != null ? authState : AuthState.UNKNOWN;
            this.httpsTunnelOk = httpsTunnelOk;
            this.usable = usable;
            this.errorMessage = errorMessage != null ? errorMessage : "";
        }

        public boolean isTcpReachable() { return tcpReachable; }
        public long getTcpLatencyMs() { return tcpLatencyMs; }
        public AuthState getAuthState() { return authState; }
        public boolean isHttpsTunnelOk() { return httpsTunnelOk; }
        public boolean isUsable() { return usable; }
        public String getErrorMessage() { return errorMessage; }
    }

    public static class SpeedResult {
        private final boolean success;
        private final long avgPingMs;
        private final long jitterMs;
        private final double downloadMbps;
        private final double uploadMbps;
        private final long bytesDownloaded;
        private final long bytesUploaded;
        private final String errorMessage;

        public SpeedResult(boolean success, long avgPingMs, double downloadMbps, double uploadMbps,
                           long bytesDownloaded, long bytesUploaded, String errorMessage) {
            this(success, avgPingMs, 0L, downloadMbps, uploadMbps, bytesDownloaded, bytesUploaded, errorMessage);
        }

        public SpeedResult(boolean success, long avgPingMs, long jitterMs, double downloadMbps, double uploadMbps,
                           long bytesDownloaded, long bytesUploaded, String errorMessage) {
            this.success = success;
            this.avgPingMs = avgPingMs;
            this.jitterMs = Math.max(0L, jitterMs);
            this.downloadMbps = downloadMbps;
            this.uploadMbps = uploadMbps;
            this.bytesDownloaded = bytesDownloaded;
            this.bytesUploaded = bytesUploaded;
            this.errorMessage = errorMessage != null ? errorMessage : "";
        }

        public boolean isSuccess() { return success; }
        public long getAvgPingMs() { return avgPingMs; }
        public long getJitterMs() { return jitterMs; }
        public double getDownloadMbps() { return downloadMbps; }
        public double getUploadMbps() { return uploadMbps; }
        public long getBytesDownloaded() { return bytesDownloaded; }
        public long getBytesUploaded() { return bytesUploaded; }
        public String getErrorMessage() { return errorMessage; }
    }

    public static DiagnosticResult runDiagnostics(ProxyConfig proxy) {
        if (proxy == null || proxy.getHost().isEmpty() || proxy.getPort() <= 0) {
            return new DiagnosticResult(false, -1, AuthState.UNKNOWN, false, false,
                    "Thông số Proxy không hợp lệ");
        }

        long tcpStart = System.nanoTime();
        long tcpMs;
        try (Socket tcpProbe = new Socket()) {
            tcpProbe.connect(new InetSocketAddress(proxy.getHost(), proxy.getPort()), CONNECT_TIMEOUT_MS);
            tcpMs = Math.max(1L, (System.nanoTime() - tcpStart) / 1_000_000L);
        } catch (IOException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Không thể kết nối TCP đến Proxy";
            return new DiagnosticResult(false, -1, AuthState.UNKNOWN, false, false, msg);
        }

        if (proxy.isSocks5Protocol()) {
            try (Socket ignored = UpstreamProxyConnector.openTunnel(
                    proxy, "speed.cloudflare.com", 443, CONNECT_TIMEOUT_MS)) {
                AuthState auth = proxy.hasAuth() ? AuthState.VALID : AuthState.NOT_REQUIRED;
                return new DiagnosticResult(true, tcpMs, auth, true, true, "");
            } catch (UpstreamProxyConnector.ProxyAuthException authErr) {
                if (!proxy.hasAuth()) {
                    return new DiagnosticResult(true, tcpMs, AuthState.NOT_REQUIRED, false, true, "");
                }
                return new DiagnosticResult(true, tcpMs, AuthState.FAILED_407, false, false,
                        authErr.getMessage() != null ? authErr.getMessage() : "Lỗi xác thực SOCKS5");
            } catch (IOException e) {
                String msg = e.getMessage() != null ? e.getMessage() : "Lỗi bắt tay SOCKS5";
                return new DiagnosticResult(true, tcpMs, AuthState.UNKNOWN, false, false, msg);
            }
        }

        try (Socket socket = UpstreamProxyConnector.openRawUpstreamSocket(proxy, CONNECT_TIMEOUT_MS)) {
            socket.setSoTimeout(CONNECT_TIMEOUT_MS);

            // Probe HTTPS CONNECT tunnel + Proxy-Authorization
            StringBuilder req = new StringBuilder();
            req.append("CONNECT speed.cloudflare.com:443 HTTP/1.1\r\n");
            req.append("Host: speed.cloudflare.com:443\r\n");
            if (proxy.hasAuth()) {
                req.append("Proxy-Authorization: Basic ").append(proxy.getBasicAuthToken()).append("\r\n");
            }
            req.append("\r\n");

            OutputStream out = socket.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            String statusLine = reader.readLine();
            if (statusLine == null) {
                return new DiagnosticResult(true, tcpMs, AuthState.UNKNOWN, false, false,
                        "Proxy đóng kết nối sau khi bắt tay TCP");
            }

            int code = ProxyTester.parseHttpStatusCode(statusLine);
            if (code == 407) {
                if (!proxy.hasAuth()) {
                    return new DiagnosticResult(true, tcpMs, AuthState.NOT_REQUIRED, true, true, "");
                }
                return new DiagnosticResult(true, tcpMs, AuthState.FAILED_407, false, false,
                        "Lỗi 407: Sai tài khoản hoặc mật khẩu Proxy");
            }
            if (code >= 200 && code < 300) {
                AuthState auth = proxy.hasAuth() ? AuthState.VALID : AuthState.NOT_REQUIRED;
                return new DiagnosticResult(true, tcpMs, auth, true, true, "");
            }

            // Fallback HTTP GET check if CONNECT is restricted by proxy policy
            ProxyTester.TestResult httpFallback = ProxyTester.testProxy(proxy, CONNECT_TIMEOUT_MS);
            if (httpFallback.isSuccess()) {
                AuthState auth = proxy.hasAuth() ? AuthState.VALID : AuthState.NOT_REQUIRED;
                return new DiagnosticResult(true, tcpMs, auth, false, true,
                        "Hỗ trợ HTTP (hạn chế HTTPS CONNECT)");
            }
            return new DiagnosticResult(true, tcpMs, AuthState.UNKNOWN, false, false,
                    "Proxy trả về mã lỗi HTTP " + code);
        } catch (IOException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Không thể kết nối đến Proxy";
            return new DiagnosticResult(true, tcpMs, AuthState.UNKNOWN, false, false, msg);
        }
    }

    public static SpeedResult measureCurrentNetworkSpeed(ProxyConfig activeProxyIfVpnRunning) {
        if (activeProxyIfVpnRunning != null) {
            return measureSpeed(activeProxyIfVpnRunning);
        }
        return measureDirectSpeed();
    }

    public static SpeedResult measureDirectSpeed() {
        long pingSum = 0;
        long minPing = Long.MAX_VALUE;
        long maxPing = 0;
        int pingCount = 0;
        for (int i = 0; i < 3; i++) {
            long start = System.nanoTime();
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("1.1.1.1", 443), CONNECT_TIMEOUT_MS);
                long rtt = Math.max(1L, (System.nanoTime() - start) / 1_000_000L);
                pingSum += rtt;
                minPing = Math.min(minPing, rtt);
                maxPing = Math.max(maxPing, rtt);
                pingCount++;
            } catch (IOException ignored) {
            }
        }

        long avgPingMs = pingCount > 0 ? (pingSum / pingCount) : -1L;
        long jitterMs = pingCount > 1 ? Math.max(0L, maxPing - minPing) : 0L;

        long dlBytes = 0;
        double dlMbps = 0.0;
        HttpsURLConnection dlConn = null;
        try {
            URL url = new URL("https://speed.cloudflare.com/__down?bytes=" + DOWNLOAD_TARGET_BYTES);
            dlConn = (HttpsURLConnection) url.openConnection(Proxy.NO_PROXY);
            dlConn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            dlConn.setReadTimeout(IO_TIMEOUT_MS);
            dlConn.setRequestProperty("User-Agent", "ProxyFlow/1.1");
            if (dlConn.getResponseCode() >= 200 && dlConn.getResponseCode() < 300) {
                try (InputStream in = dlConn.getInputStream()) {
                    byte[] buf = new byte[16384];
                    long startNs = System.nanoTime();
                    int read;
                    while ((read = in.read(buf)) != -1) {
                        dlBytes += read;
                    }
                    long elapsedNs = Math.max(1_000_000L, System.nanoTime() - startNs);
                    dlMbps = calculateMbps(dlBytes, elapsedNs);
                }
            }
        } catch (IOException ignored) {
        } finally {
            if (dlConn != null) dlConn.disconnect();
        }

        long ulBytes = 0;
        double ulMbps = 0.0;
        HttpsURLConnection ulConn = null;
        try {
            URL url = new URL("https://speed.cloudflare.com/__up");
            ulConn = (HttpsURLConnection) url.openConnection(Proxy.NO_PROXY);
            ulConn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            ulConn.setReadTimeout(IO_TIMEOUT_MS);
            ulConn.setRequestMethod("POST");
            ulConn.setDoOutput(true);
            ulConn.setFixedLengthStreamingMode(UPLOAD_TARGET_BYTES);
            ulConn.setRequestProperty("User-Agent", "ProxyFlow/1.1");
            ulConn.setRequestProperty("Content-Type", "application/octet-stream");

            byte[] chunk = new byte[16384];
            for (int i = 0; i < chunk.length; i++) {
                chunk[i] = (byte) (i & 0xFF);
            }

            long startNs = System.nanoTime();
            try (OutputStream out = ulConn.getOutputStream()) {
                int remaining = UPLOAD_TARGET_BYTES;
                while (remaining > 0) {
                    int toWrite = Math.min(chunk.length, remaining);
                    out.write(chunk, 0, toWrite);
                    ulBytes += toWrite;
                    remaining -= toWrite;
                }
                out.flush();
            }
            int code = ulConn.getResponseCode();
            if (code >= 200 && code < 400) {
                long elapsedNs = Math.max(1_000_000L, System.nanoTime() - startNs);
                ulMbps = calculateMbps(ulBytes, elapsedNs);
            } else {
                ulBytes = 0;
            }
        } catch (IOException ignored) {
            ulBytes = 0;
        } finally {
            if (ulConn != null) ulConn.disconnect();
        }

        boolean ok = pingCount > 0 || dlBytes > 0 || ulBytes > 0;
        return new SpeedResult(ok, avgPingMs, jitterMs, dlMbps, ulMbps, dlBytes, ulBytes,
                ok ? "" : "Không thể kết nối máy chủ đo tốc độ");
    }

    public static SpeedResult measureSpeed(ProxyConfig proxy) {
        if (proxy == null || proxy.getHost().isEmpty() || proxy.getPort() <= 0) {
            return new SpeedResult(false, -1, 0L, 0, 0, 0, 0, "Cấu hình Proxy không hợp lệ");
        }

        // 1. Measure average Ping (3 probes)
        long pingSum = 0;
        long minPing = Long.MAX_VALUE;
        long maxPing = 0;
        int pingCount = 0;
        for (int i = 0; i < 3; i++) {
            long start = System.nanoTime();
            try (Socket ignored = UpstreamProxyConnector.openTunnel(proxy, "1.1.1.1", 443, CONNECT_TIMEOUT_MS)) {
                long rtt = Math.max(1L, (System.nanoTime() - start) / 1_000_000L);
                pingSum += rtt;
                minPing = Math.min(minPing, rtt);
                maxPing = Math.max(maxPing, rtt);
                pingCount++;
            } catch (UpstreamProxyConnector.ProxyAuthException authErr) {
                return new SpeedResult(false, -1, 0L, 0, 0, 0, 0,
                        authErr.getMessage() != null ? authErr.getMessage() : "Lỗi xác thực Proxy");
            } catch (IOException ignored) {
            }
        }

        if (pingCount == 0) {
            return new SpeedResult(false, -1, 0L, 0, 0, 0, 0, "Không thể thiết lập phiên đo tốc độ qua Proxy");
        }
        long avgPingMs = pingSum / pingCount;
        long jitterMs = pingCount > 1 ? Math.max(0L, maxPing - minPing) : 0L;

        // 2. Measure Download Speed via Proxy tunnel (speed.cloudflare.com)
        long dlBytes = 0;
        double dlMbps = 0.0;
        try (Socket tunnel = UpstreamProxyConnector.openTunnel(proxy, "speed.cloudflare.com", 443, CONNECT_TIMEOUT_MS)) {
            tunnel.setSoTimeout(IO_TIMEOUT_MS);
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            try (SSLSocket ssl = (SSLSocket) factory.createSocket(tunnel, "speed.cloudflare.com", 443, true)) {
                ssl.setSoTimeout(IO_TIMEOUT_MS);
                ssl.startHandshake();

                String req = "GET /__down?bytes=" + DOWNLOAD_TARGET_BYTES + " HTTP/1.0\r\n"
                        + "Host: speed.cloudflare.com\r\n"
                        + "User-Agent: ProxyFlow/1.0\r\n"
                        + "Connection: close\r\n\r\n";
                OutputStream out = ssl.getOutputStream();
                out.write(req.getBytes(StandardCharsets.UTF_8));
                out.flush();

                InputStream in = ssl.getInputStream();
                skipHttpHeaders(in);

                byte[] buf = new byte[16384];
                long startNs = System.nanoTime();
                int read;
                while ((read = in.read(buf)) != -1) {
                    dlBytes += read;
                }
                long elapsedNs = Math.max(1_000_000L, System.nanoTime() - startNs);
                dlMbps = calculateMbps(dlBytes, elapsedNs);
            }
        } catch (IOException ignored) {
        }

        // 3. Measure Upload Speed via Proxy tunnel (speed.cloudflare.com)
        long ulBytes = 0;
        double ulMbps = 0.0;
        try (Socket tunnel = UpstreamProxyConnector.openTunnel(proxy, "speed.cloudflare.com", 443, CONNECT_TIMEOUT_MS)) {
            tunnel.setSoTimeout(IO_TIMEOUT_MS);
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            try (SSLSocket ssl = (SSLSocket) factory.createSocket(tunnel, "speed.cloudflare.com", 443, true)) {
                ssl.setSoTimeout(IO_TIMEOUT_MS);
                ssl.startHandshake();

                String headers = "POST /__up HTTP/1.0\r\n"
                        + "Host: speed.cloudflare.com\r\n"
                        + "User-Agent: ProxyFlow/1.0\r\n"
                        + "Content-Type: application/octet-stream\r\n"
                        + "Content-Length: " + UPLOAD_TARGET_BYTES + "\r\n"
                        + "Connection: close\r\n\r\n";
                OutputStream out = ssl.getOutputStream();
                out.write(headers.getBytes(StandardCharsets.UTF_8));

                byte[] chunk = new byte[16384];
                for (int i = 0; i < chunk.length; i++) {
                    chunk[i] = (byte) (i & 0xFF);
                }

                long startNs = System.nanoTime();
                int remaining = UPLOAD_TARGET_BYTES;
                while (remaining > 0) {
                    int toWrite = Math.min(chunk.length, remaining);
                    out.write(chunk, 0, toWrite);
                    ulBytes += toWrite;
                    remaining -= toWrite;
                }
                out.flush();

                // Read response status line to ensure upload was received by server
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(ssl.getInputStream(), StandardCharsets.ISO_8859_1));
                reader.readLine();
                long elapsedNs = Math.max(1_000_000L, System.nanoTime() - startNs);
                ulMbps = calculateMbps(ulBytes, elapsedNs);
            }
        } catch (IOException ignored) {
        }

        boolean ok = dlBytes > 0 || ulBytes > 0 || avgPingMs > 0;
        return new SpeedResult(ok, avgPingMs, jitterMs, dlMbps, ulMbps, dlBytes, ulBytes,
                ok ? "" : "Không thể truyền dữ liệu đo tốc độ");
    }

    public static double calculateMbps(long bytes, long elapsedNs) {
        if (bytes <= 0 || elapsedNs <= 0) {
            return 0.0;
        }
        double seconds = elapsedNs / 1_000_000_000.0;
        return (bytes * 8.0) / (seconds * 1_000_000.0);
    }

    private static void skipHttpHeaders(InputStream in) throws IOException {
        int state = 0;
        int b;
        int count = 0;
        while (count < 16384 && (b = in.read()) != -1) {
            count++;
            if (state == 0 && b == '\r') state = 1;
            else if (state == 1 && b == '\n') state = 2;
            else if (state == 2 && b == '\r') state = 3;
            else if (state == 3 && b == '\n') break;
            else state = (b == '\r') ? 1 : 0;
        }
    }
}
