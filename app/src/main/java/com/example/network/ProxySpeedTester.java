package com.example.network;

import androidx.annotation.Nullable;

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
        REQUIRED,
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
        @Nullable
        private final ProxyConfig.Protocol detectedProtocol;
        private final ProxyTester.FailureReason failureReason;
        private final int httpStatusCode;

        public DiagnosticResult(boolean tcpReachable, long tcpLatencyMs, AuthState authState,
                                boolean httpsTunnelOk, boolean usable, String errorMessage) {
            this(tcpReachable, tcpLatencyMs, authState, httpsTunnelOk, usable, errorMessage, null);
        }

        public DiagnosticResult(boolean tcpReachable, long tcpLatencyMs, AuthState authState,
                                boolean httpsTunnelOk, boolean usable, String errorMessage,
                                @Nullable ProxyConfig.Protocol detectedProtocol) {
            this(tcpReachable, tcpLatencyMs, authState, httpsTunnelOk, usable, errorMessage,
                    detectedProtocol,
                    usable ? ProxyTester.FailureReason.NONE
                            : ProxyTester.inferFailureReasonFromMessage(errorMessage),
                    -1);
        }

        public DiagnosticResult(boolean tcpReachable, long tcpLatencyMs, AuthState authState,
                                boolean httpsTunnelOk, boolean usable, String errorMessage,
                                @Nullable ProxyConfig.Protocol detectedProtocol,
                                @Nullable ProxyTester.FailureReason failureReason,
                                int httpStatusCode) {
            this.tcpReachable = tcpReachable;
            this.tcpLatencyMs = tcpLatencyMs;
            this.authState = authState != null ? authState : AuthState.UNKNOWN;
            this.httpsTunnelOk = httpsTunnelOk;
            this.usable = usable;
            this.errorMessage = errorMessage != null ? errorMessage : "";
            this.detectedProtocol = detectedProtocol;
            this.failureReason = failureReason != null
                    ? failureReason
                    : (usable ? ProxyTester.FailureReason.NONE : ProxyTester.FailureReason.PROTOCOL_OR_RESPONSE_ERROR);
            this.httpStatusCode = httpStatusCode;
        }

        public boolean isTcpReachable() { return tcpReachable; }
        public long getTcpLatencyMs() { return tcpLatencyMs; }
        public AuthState getAuthState() { return authState; }
        public boolean isHttpsTunnelOk() { return httpsTunnelOk; }
        public boolean isUsable() { return usable; }
        public String getErrorMessage() { return errorMessage; }
        @Nullable
        public ProxyConfig.Protocol getDetectedProtocol() { return detectedProtocol; }
        public ProxyTester.FailureReason getFailureReason() { return failureReason; }
        public int getHttpStatusCode() { return httpStatusCode; }
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
                    "Thông số Proxy không hợp lệ", null,
                    ProxyTester.FailureReason.INVALID_CONFIG, -1);
        }

        long tcpStart = System.nanoTime();
        long tcpMs;
        try (Socket tcpProbe = new Socket()) {
            tcpProbe.connect(new InetSocketAddress(proxy.getHost(), proxy.getPort()), CONNECT_TIMEOUT_MS);
            tcpMs = Math.max(1L, (System.nanoTime() - tcpStart) / 1_000_000L);
        } catch (IOException e) {
            ProxyTester.TestResult classified = ProxyTester.classifyExceptionFailure(e, proxy);
            return new DiagnosticResult(false, -1, AuthState.UNKNOWN, false, false,
                    classified.getErrorMessage(), null,
                    classified.getFailureReason(), classified.getHttpStatusCode());
        }

        if (proxy.getProtocol().isAutoDetect()) {
            ProxyTester.TestResult detected = ProxyTester.testProxy(proxy, CONNECT_TIMEOUT_MS);
            if (!detected.isSuccess() || detected.getDetectedProtocol() == null) {
                AuthState state = classifyAuthStateFromReason(proxy, detected.getFailureReason(), detected.getErrorMessage());
                return new DiagnosticResult(true, tcpMs, state, false, false,
                        detected.getErrorMessage(), null,
                        detected.getFailureReason(), detected.getHttpStatusCode());
            }
            ProxyConfig resolved = proxy.copy();
            resolved.setProtocol(detected.getDetectedProtocol());
            DiagnosticResult inner = runDiagnostics(resolved);
            return new DiagnosticResult(inner.isTcpReachable(), inner.getTcpLatencyMs(),
                    inner.getAuthState(), inner.isHttpsTunnelOk(), inner.isUsable(),
                    inner.getErrorMessage(), detected.getDetectedProtocol(),
                    inner.getFailureReason(), inner.getHttpStatusCode());
        }

        if (proxy.isSocks5Protocol()) {
            try (Socket ignored = UpstreamProxyConnector.openTunnel(
                    proxy, "speed.cloudflare.com", 443, CONNECT_TIMEOUT_MS)) {
                AuthState auth = proxy.hasAuth() ? AuthState.VALID : AuthState.NOT_REQUIRED;
                return new DiagnosticResult(true, tcpMs, auth, true, true, "",
                        proxy.getProtocol(), ProxyTester.FailureReason.NONE, 200);
            } catch (UpstreamProxyConnector.ProxyAuthRequiredException reqErr) {
                return new DiagnosticResult(true, tcpMs, AuthState.REQUIRED, false, false,
                        reqErr.getMessage() != null ? reqErr.getMessage() : "Proxy SOCKS5 yêu cầu xác thực",
                        null, ProxyTester.FailureReason.AUTH_REQUIRED, -1);
            } catch (UpstreamProxyConnector.ProxyAuthException authErr) {
                ProxyTester.TestResult classified = ProxyTester.classifyExceptionFailure(authErr, proxy);
                return new DiagnosticResult(true, tcpMs, AuthState.FAILED_407, false, false,
                        classified.getErrorMessage(), null,
                        classified.getFailureReason(), classified.getHttpStatusCode());
            } catch (IOException e) {
                ProxyTester.TestResult classified = ProxyTester.classifyExceptionFailure(e, proxy);
                return new DiagnosticResult(true, tcpMs, AuthState.UNKNOWN, false, false,
                        classified.getErrorMessage(), null,
                        classified.getFailureReason(), classified.getHttpStatusCode());
            }
        }

        try {
            ProxyAuthHelper.TunnelAttemptResult attempt = ProxyAuthHelper.sendHttpConnectWithAuthRetry(
                    proxy, "speed.cloudflare.com", 443, CONNECT_TIMEOUT_MS);
            try (Socket ignored = attempt.socket) {
                int code = attempt.responseHead.getStatusCode();
                if (code >= 200 && code < 300) {
                    AuthState auth = proxy.hasAuth() ? AuthState.VALID : AuthState.NOT_REQUIRED;
                    return new DiagnosticResult(true, tcpMs, auth, true, true, "",
                            proxy.getProtocol(), ProxyTester.FailureReason.NONE, code);
                }

                // Fallback HTTP GET check if CONNECT is restricted by proxy policy
                ProxyTester.TestResult httpFallback = ProxyTester.testProxy(proxy, CONNECT_TIMEOUT_MS);
                if (httpFallback.isSuccess()) {
                    AuthState auth = proxy.hasAuth() ? AuthState.VALID : AuthState.NOT_REQUIRED;
                    return new DiagnosticResult(true, tcpMs, auth, false, true,
                            "Hỗ trợ HTTP (hạn chế HTTPS CONNECT)",
                            proxy.getProtocol(), ProxyTester.FailureReason.NONE, 200);
                }
                if (httpFallback.getFailureReason() == ProxyTester.FailureReason.AUTH_REQUIRED
                        || httpFallback.getFailureReason() == ProxyTester.FailureReason.AUTH_FAILED
                        || httpFallback.getFailureReason() == ProxyTester.FailureReason.AUTH_SCHEME_MISMATCH) {
                    AuthState state = classifyAuthStateFromReason(
                            proxy, httpFallback.getFailureReason(), httpFallback.getErrorMessage());
                    return new DiagnosticResult(true, tcpMs, state, false, false,
                            httpFallback.getErrorMessage(), null,
                            httpFallback.getFailureReason(), httpFallback.getHttpStatusCode());
                }
                return new DiagnosticResult(true, tcpMs, AuthState.UNKNOWN, false, false,
                        "Proxy trả về mã lỗi HTTP " + code,
                        null, ProxyTester.FailureReason.HTTP_STATUS_ERROR, code);
            }
        } catch (UpstreamProxyConnector.ProxyAuthRequiredException reqErr) {
            return new DiagnosticResult(true, tcpMs, AuthState.REQUIRED, false, false,
                    reqErr.getMessage() != null ? reqErr.getMessage() : "Proxy yêu cầu xác thực",
                    null, ProxyTester.FailureReason.AUTH_REQUIRED, 407);
        } catch (UpstreamProxyConnector.ProxyAuthException authErr) {
            ProxyTester.TestResult classified = ProxyTester.classifyExceptionFailure(authErr, proxy);
            return new DiagnosticResult(true, tcpMs, AuthState.FAILED_407, false, false,
                    classified.getErrorMessage(), null,
                    classified.getFailureReason(), 407);
        } catch (IOException e) {
            ProxyTester.TestResult classified = ProxyTester.classifyExceptionFailure(e, proxy);
            return new DiagnosticResult(true, tcpMs, AuthState.UNKNOWN, false, false,
                    classified.getErrorMessage(), null,
                    classified.getFailureReason(), classified.getHttpStatusCode());
        }
    }

    private static AuthState classifyAuthStateFromReason(
            ProxyConfig proxy, ProxyTester.FailureReason reason, String errorMessage) {
        if (reason == ProxyTester.FailureReason.AUTH_REQUIRED) {
            return AuthState.REQUIRED;
        }
        if (reason == ProxyTester.FailureReason.AUTH_FAILED
                || reason == ProxyTester.FailureReason.AUTH_SCHEME_MISMATCH) {
            return (proxy == null || !proxy.hasAuth()) ? AuthState.REQUIRED : AuthState.FAILED_407;
        }
        return classifyAuthStateFromError(proxy, errorMessage);
    }

    private static AuthState classifyAuthStateFromError(ProxyConfig proxy, String errorMessage) {
        if (errorMessage == null) return AuthState.UNKNOWN;
        String lower = errorMessage.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("407") || lower.contains("xác thực")
                || lower.contains("tài khoản") || lower.contains("mật khẩu") || lower.contains("token")) {
            return (proxy == null || !proxy.hasAuth()) ? AuthState.REQUIRED : AuthState.FAILED_407;
        }
        return AuthState.UNKNOWN;
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
        if (proxy.getProtocol().isAutoDetect()) {
            ProxyTester.TestResult detected = ProxyTester.testProxy(proxy, CONNECT_TIMEOUT_MS);
            if (!detected.isSuccess() || detected.getDetectedProtocol() == null) {
                return new SpeedResult(false, -1, 0L, 0, 0, 0, 0, detected.getErrorMessage());
            }
            ProxyConfig resolved = proxy.copy();
            resolved.setProtocol(detected.getDetectedProtocol());
            proxy = resolved;
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
