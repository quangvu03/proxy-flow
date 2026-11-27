package com.example.network;

import androidx.annotation.Nullable;

import com.example.model.ProxyConfig;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure Java proxy tester that connects directly to an upstream proxy,
 * verifies optional authentication, measures round-trip latency, auto-detects
 * compatible protocols when Protocol.ALL is selected, and resolves real exit IP & country.
 */
public final class ProxyTester {

    private ProxyTester() {}

    public enum FailureReason {
        NONE,
        INVALID_CONFIG,
        AUTH_REQUIRED,
        AUTH_FAILED,
        AUTH_SCHEME_MISMATCH,
        TIMEOUT,
        CONNECTION_REFUSED,
        HOST_UNREACHABLE,
        TLS_FAILED,
        HTTP_STATUS_ERROR,
        PROTOCOL_OR_RESPONSE_ERROR
    }

    public static class TestResult {
        private final boolean success;
        private final long latencyMs;
        private final String exitIp;
        private final String country;
        private final String errorMessage;
        @Nullable
        private final ProxyConfig.Protocol detectedProtocol;
        private final FailureReason failureReason;
        private final int httpStatusCode;

        public TestResult(boolean success, long latencyMs, String exitIp, String country, String errorMessage) {
            this(success, latencyMs, exitIp, country, errorMessage, null);
        }

        public TestResult(boolean success, long latencyMs, String exitIp, String country,
                          String errorMessage, @Nullable ProxyConfig.Protocol detectedProtocol) {
            this(success, latencyMs, exitIp, country, errorMessage, detectedProtocol,
                    success ? FailureReason.NONE : inferFailureReasonFromMessage(errorMessage), -1);
        }

        public TestResult(boolean success, long latencyMs, String exitIp, String country,
                          String errorMessage, @Nullable ProxyConfig.Protocol detectedProtocol,
                          @Nullable FailureReason failureReason, int httpStatusCode) {
            this.success = success;
            this.latencyMs = latencyMs;
            this.exitIp = exitIp != null ? exitIp : "";
            this.country = country != null ? country : "";
            this.errorMessage = errorMessage != null ? errorMessage : "";
            this.detectedProtocol = detectedProtocol;
            this.failureReason = failureReason != null
                    ? failureReason
                    : (success ? FailureReason.NONE : FailureReason.PROTOCOL_OR_RESPONSE_ERROR);
            this.httpStatusCode = httpStatusCode;
        }

        public boolean isSuccess() { return success; }
        public long getLatencyMs() { return latencyMs; }
        public String getExitIp() { return exitIp; }
        public String getCountry() { return country; }
        public String getErrorMessage() { return errorMessage; }
        @Nullable
        public ProxyConfig.Protocol getDetectedProtocol() { return detectedProtocol; }
        public FailureReason getFailureReason() { return failureReason; }
        public int getHttpStatusCode() { return httpStatusCode; }
    }

    private static TestResult failure(FailureReason reason, String errorMessage) {
        return new TestResult(false, -1, "", "", errorMessage, null, reason, -1);
    }

    private static TestResult failure(FailureReason reason, int httpStatusCode, String errorMessage) {
        return new TestResult(false, -1, "", "", errorMessage, null, reason, httpStatusCode);
    }

    public static TestResult testProxy(ProxyConfig proxy, int timeoutMs) {
        if (proxy == null || proxy.getHost().isEmpty() || proxy.getPort() <= 0 || proxy.getPort() > 65535) {
            return failure(FailureReason.INVALID_CONFIG, "Cấu hình Proxy không hợp lệ");
        }

        if (proxy.getProtocol().isAutoDetect()) {
            return testAutoProtocol(proxy, timeoutMs);
        }

        return testSingleProtocol(proxy, timeoutMs);
    }

    private static TestResult testAutoProtocol(ProxyConfig proxy, int timeoutMs) {
        List<ProxyConfig.Protocol> candidates = getCandidateProtocolsForPort(proxy.getPort());
        int perProbeTimeout = Math.min(Math.max(timeoutMs, 1500), 4000);
        ExecutorService pool = Executors.newFixedThreadPool(candidates.size(), runnable -> {
            Thread t = new Thread(runnable, "ProxyFlow-ProtoProbe");
            t.setDaemon(true);
            return t;
        });
        CompletionService<TestResult> ecs = new ExecutorCompletionService<>(pool);
        List<Future<TestResult>> futures = new ArrayList<>(candidates.size());
        List<TestResult> failures = new ArrayList<>();
        try {
            for (int i = 0; i < candidates.size(); i++) {
                final ProxyConfig.Protocol candidate = candidates.get(i);
                final int staggerMs = i * 25;
                futures.add(ecs.submit(() -> {
                    if (staggerMs > 0) {
                        Thread.sleep(staggerMs);
                    }
                    ProxyConfig probe = proxy.copy();
                    probe.setProtocol(candidate);
                    TestResult r = testSingleProtocol(probe, perProbeTimeout);
                    if (r.isSuccess()) {
                        return new TestResult(true, r.getLatencyMs(), r.getExitIp(), r.getCountry(), "",
                                candidate, FailureReason.NONE, -1);
                    }
                    return r;
                }));
            }

            long deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(timeoutMs, perProbeTimeout) + 500L);
            for (int i = 0; i < candidates.size(); i++) {
                long remainingNs = deadlineNs - System.nanoTime();
                if (remainingNs <= 0L) {
                    break;
                }
                Future<TestResult> completed = ecs.poll(remainingNs, TimeUnit.NANOSECONDS);
                if (completed == null) {
                    break;
                }
                try {
                    TestResult res = completed.get();
                    if (res != null) {
                        if (res.isSuccess()) {
                            return res;
                        }
                        failures.add(res);
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            for (Future<TestResult> f : futures) {
                f.cancel(true);
            }
            pool.shutdownNow();
        }

        return selectBestProbeFailure(failures);
    }

    private static List<ProxyConfig.Protocol> getCandidateProtocolsForPort(int port) {
        if (port == 1080 || port == 1081) {
            return Arrays.asList(
                    ProxyConfig.Protocol.SOCKS5,
                    ProxyConfig.Protocol.HTTP,
                    ProxyConfig.Protocol.SOCKS5_TLS,
                    ProxyConfig.Protocol.HTTP_TLS
            );
        }
        if (port == 443 || port == 8443) {
            return Arrays.asList(
                    ProxyConfig.Protocol.HTTP_TLS,
                    ProxyConfig.Protocol.SOCKS5_TLS,
                    ProxyConfig.Protocol.HTTP,
                    ProxyConfig.Protocol.SOCKS5
            );
        }
        return Arrays.asList(
                ProxyConfig.Protocol.HTTP,
                ProxyConfig.Protocol.SOCKS5,
                ProxyConfig.Protocol.HTTP_TLS,
                ProxyConfig.Protocol.SOCKS5_TLS
        );
    }

    private static TestResult selectBestProbeFailure(List<TestResult> failures) {
        TestResult best = null;
        int bestScore = -1;
        for (TestResult r : failures) {
            if (r == null) continue;
            String msg = r.getErrorMessage();
            if (msg == null || msg.trim().isEmpty()) continue;
            int score = scoreFailureReason(r.getFailureReason());
            if (score > bestScore) {
                bestScore = score;
                best = r;
            }
        }
        if (best != null) {
            return failure(best.getFailureReason(), best.getHttpStatusCode(), best.getErrorMessage());
        }
        return failure(FailureReason.PROTOCOL_OR_RESPONSE_ERROR,
                "Không tìm thấy giao thức phù hợp hoặc Proxy không phản hồi");
    }

    private static int scoreFailureReason(@Nullable FailureReason reason) {
        if (reason == null) return 1;
        switch (reason) {
            case AUTH_REQUIRED:
                return 10;
            case AUTH_SCHEME_MISMATCH:
                return 9;
            case AUTH_FAILED:
                return 8;
            case HTTP_STATUS_ERROR:
                return 7;
            case CONNECTION_REFUSED:
                return 6;
            case HOST_UNREACHABLE:
                return 5;
            case TIMEOUT:
                return 4;
            case PROTOCOL_OR_RESPONSE_ERROR:
                return 3;
            case TLS_FAILED:
            default:
                return 2;
        }
    }

    private static TestResult testSingleProtocol(ProxyConfig proxy, int timeoutMs) {
        if (proxy.isSocks5Protocol()) {
            return testSocks5Proxy(proxy, timeoutMs);
        }

        final String targetUrl = "http://ip-api.com/json?fields=status,country,query";
        long startNs = System.nanoTime();
        String initialAuth = ProxyAuthHelper.buildInitialProxyAuthHeaderValue(proxy);
        Socket socket = null;
        try {
            socket = UpstreamProxyConnector.openRawUpstreamSocket(proxy, timeoutMs);
            socket.setSoTimeout(timeoutMs);
            sendProbeHttpGet(socket.getOutputStream(), targetUrl, initialAuth);

            ProxyAuthHelper.HttpResponseHead head = ProxyAuthHelper.readHttpResponseHead(socket.getInputStream());
            if (head == null) {
                return failure(FailureReason.PROTOCOL_OR_RESPONSE_ERROR, "Proxy đóng kết nối đột ngột");
            }

            if (head.getStatusCode() == 407 && proxy.hasAuth()) {
                String retryAuth = ProxyAuthHelper.buildChallengeResponseHeaderValue(
                        proxy, head.getProxyAuthenticateHeaders(), "GET", targetUrl, initialAuth);
                if (retryAuth != null) {
                    boolean canReuse = ProxyAuthHelper.drainResponseBodyForSocketReuse(socket.getInputStream(), head);
                    if (!canReuse) {
                        closeQuietly(socket);
                        socket = UpstreamProxyConnector.openRawUpstreamSocket(proxy, timeoutMs);
                        socket.setSoTimeout(timeoutMs);
                    }
                    ProxyAuthHelper.HttpResponseHead retryHead = null;
                    try {
                        sendProbeHttpGet(socket.getOutputStream(), targetUrl, retryAuth);
                        retryHead = ProxyAuthHelper.readHttpResponseHead(socket.getInputStream());
                    } catch (IOException ioEx) {
                        if (!canReuse) throw ioEx;
                    }
                    if (retryHead == null && canReuse) {
                        closeQuietly(socket);
                        socket = UpstreamProxyConnector.openRawUpstreamSocket(proxy, timeoutMs);
                        socket.setSoTimeout(timeoutMs);
                        sendProbeHttpGet(socket.getOutputStream(), targetUrl, retryAuth);
                        retryHead = ProxyAuthHelper.readHttpResponseHead(socket.getInputStream());
                    }
                    if (retryHead == null) {
                        return failure(FailureReason.PROTOCOL_OR_RESPONSE_ERROR, "Proxy đóng kết nối đột ngột");
                    }
                    head = retryHead;
                }
            }

            int statusCode = head.getStatusCode();
            if (statusCode == 407) {
                return build407Failure(proxy, head.getProxyAuthenticateHeaders());
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
                if (body.length() > 8192) break;
            }

            long elapsedMs = Math.max(1L, (System.nanoTime() - startNs) / 1_000_000L);
            if (statusCode >= 200 && statusCode < 300) {
                String trimmedBody = body.toString().trim();
                String[] fields = extractProbeFields(trimmedBody);
                String exitIp = fields[0];
                String country = fields[1];
                if (!exitIp.isEmpty()) {
                    return new TestResult(true, elapsedMs, exitIp, country, "",
                            proxy.getProtocol(), FailureReason.NONE, statusCode);
                }
            }

            // Fallback to CONNECT test in case target HTTP endpoint is blocked or non-JSON
            closeQuietly(socket);
            socket = null;
            return testConnectTunnel(proxy, timeoutMs);
        } catch (IOException e) {
            return classifyExceptionFailure(e, proxy);
        } finally {
            closeQuietly(socket);
        }
    }

    private static void sendProbeHttpGet(OutputStream out, String targetUrl, @Nullable String proxyAuthValue)
            throws IOException {
        StringBuilder req = new StringBuilder();
        req.append("GET ").append(targetUrl).append(" HTTP/1.1\r\n");
        req.append("Host: ip-api.com\r\n");
        req.append("User-Agent: ProxyFlow/1.0\r\n");
        if (proxyAuthValue != null && !proxyAuthValue.isEmpty()) {
            req.append("Proxy-Authorization: ").append(proxyAuthValue).append("\r\n");
        }
        req.append("Proxy-Connection: close\r\n");
        req.append("Connection: close\r\n\r\n");
        out.write(req.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static TestResult testSocks5Proxy(ProxyConfig proxy, int timeoutMs) {
        long startNs = System.nanoTime();
        try (Socket tunnel = UpstreamProxyConnector.openTunnel(proxy, "ip-api.com", 80, timeoutMs)) {
            tunnel.setSoTimeout(timeoutMs);
            String req = "GET /json?fields=status,country,query HTTP/1.1\r\n"
                    + "Host: ip-api.com\r\n"
                    + "User-Agent: ProxyFlow/1.0\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream out = tunnel.getOutputStream();
            out.write(req.getBytes(StandardCharsets.UTF_8));
            out.flush();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(tunnel.getInputStream(), StandardCharsets.UTF_8));
            String statusLine = reader.readLine();
            long elapsedMs = Math.max(1L, (System.nanoTime() - startNs) / 1_000_000L);
            if (statusLine != null && parseHttpStatusCode(statusLine) >= 200 && parseHttpStatusCode(statusLine) < 300) {
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    // Consume headers
                }
                StringBuilder body = new StringBuilder();
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                    if (body.length() > 8192) break;
                }
                String[] fields = extractProbeFields(body.toString().trim());
                String exitIp = fields[0];
                String country = fields[1];
                return new TestResult(true, elapsedMs, exitIp, country, "",
                        proxy.getProtocol(), FailureReason.NONE, 200);
            }
            return new TestResult(true, elapsedMs, proxy.getHost(), "", "",
                    proxy.getProtocol(), FailureReason.NONE, 200);
        } catch (UpstreamProxyConnector.ProxyAuthException authErr) {
            return classifyExceptionFailure(authErr, proxy);
        } catch (IOException e) {
            TestResult initialClassified = classifyExceptionFailure(e, proxy);
            FailureReason r = initialClassified.getFailureReason();
            if (r == FailureReason.TIMEOUT
                    || r == FailureReason.CONNECTION_REFUSED
                    || r == FailureReason.HOST_UNREACHABLE
                    || r == FailureReason.TLS_FAILED) {
                return initialClassified;
            }
            // Fallback CONNECT check to 1.1.1.1:443 in case ip-api.com is blocked
            long fallbackStart = System.nanoTime();
            try (Socket ignored = UpstreamProxyConnector.openTunnel(proxy, "1.1.1.1", 443, timeoutMs)) {
                long elapsedMs = Math.max(1L, (System.nanoTime() - fallbackStart) / 1_000_000L);
                return new TestResult(true, elapsedMs, proxy.getHost(), "", "",
                        proxy.getProtocol(), FailureReason.NONE, 200);
            } catch (IOException fallbackErr) {
                return classifyExceptionFailure(fallbackErr, proxy);
            }
        }
    }

    private static TestResult testConnectTunnel(ProxyConfig proxy, int timeoutMs) {
        long startNs = System.nanoTime();
        try {
            ProxyAuthHelper.TunnelAttemptResult attempt = ProxyAuthHelper.sendHttpConnectWithAuthRetry(
                    proxy, "1.1.1.1", 443, timeoutMs);
            try (Socket ignored = attempt.socket) {
                long elapsedMs = Math.max(1L, (System.nanoTime() - startNs) / 1_000_000L);
                int code = attempt.responseHead.getStatusCode();
                if (code >= 200 && code < 300) {
                    return new TestResult(true, elapsedMs, proxy.getHost(), "", "",
                            proxy.getProtocol(), FailureReason.NONE, code);
                }
                return failure(FailureReason.HTTP_STATUS_ERROR, code, "Mã lỗi HTTP: " + code);
            }
        } catch (IOException e) {
            return classifyExceptionFailure(e, proxy);
        }
    }

    public static TestResult build407Failure(ProxyConfig proxy, @Nullable List<String> authHeaders) {
        String message = ProxyAuthHelper.describe407Failure(proxy, authHeaders);
        if (proxy == null || !proxy.hasAuth()) {
            return failure(FailureReason.AUTH_REQUIRED, 407, message);
        }
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("hãy đổi sang")) {
            return failure(FailureReason.AUTH_SCHEME_MISMATCH, 407, message);
        }
        return failure(FailureReason.AUTH_FAILED, 407, message);
    }

    public static TestResult classifyExceptionFailure(IOException e, @Nullable ProxyConfig proxy) {
        String host = proxy != null ? proxy.getHost() : "";
        int port = proxy != null ? proxy.getPort() : 0;
        String endpoint = (!host.isEmpty() && port > 0) ? (host + ":" + port) : host;
        String rawMsg = e.getMessage() != null ? e.getMessage().trim() : "";

        if (e instanceof UpstreamProxyConnector.ProxyAuthRequiredException) {
            return failure(FailureReason.AUTH_REQUIRED,
                    !rawMsg.isEmpty() ? rawMsg : "Proxy yêu cầu xác thực (vui lòng nhập tài khoản / mật khẩu)");
        }
        if (e instanceof UpstreamProxyConnector.ProxyAuthException) {
            if (proxy == null || !proxy.hasAuth()) {
                return failure(FailureReason.AUTH_REQUIRED,
                        !rawMsg.isEmpty() ? rawMsg : "Proxy yêu cầu xác thực (vui lòng nhập tài khoản / mật khẩu)");
            }
            String lower = rawMsg.toLowerCase(Locale.ROOT);
            if (lower.contains("hãy đổi sang") || lower.contains("phương thức")) {
                return failure(FailureReason.AUTH_SCHEME_MISMATCH, rawMsg);
            }
            return failure(FailureReason.AUTH_FAILED,
                    !rawMsg.isEmpty() ? rawMsg : "Sai tài khoản hoặc mật khẩu Proxy");
        }
        if (e instanceof java.net.SocketTimeoutException) {
            return failure(FailureReason.TIMEOUT,
                    "Quá thời gian kết nối tới " + endpoint);
        }
        if (e instanceof java.net.UnknownHostException) {
            return failure(FailureReason.HOST_UNREACHABLE,
                    "Không tìm thấy hoặc không phân giải được máy chủ " + host);
        }
        if (e instanceof java.net.NoRouteToHostException) {
            return failure(FailureReason.HOST_UNREACHABLE,
                    "Không có đường mạng tới máy chủ " + endpoint);
        }
        if (e instanceof javax.net.ssl.SSLException) {
            return failure(FailureReason.TLS_FAILED,
                    "Lỗi bắt tay SSL/TLS với " + endpoint);
        }
        if (e instanceof java.net.ConnectException) {
            String lower = rawMsg.toLowerCase(Locale.ROOT);
            if (lower.contains("refused") || lower.contains("econnrefused")) {
                return failure(FailureReason.CONNECTION_REFUSED,
                        "Máy chủ từ chối kết nối tại " + endpoint);
            }
            if (lower.contains("timed out") || lower.contains("etimedout")) {
                return failure(FailureReason.TIMEOUT,
                        "Quá thời gian kết nối tới " + endpoint);
            }
            return failure(FailureReason.HOST_UNREACHABLE,
                    !rawMsg.isEmpty() ? rawMsg : ("Không thể kết nối tới " + endpoint));
        }

        FailureReason inferred = inferFailureReasonFromMessage(rawMsg);
        String msg = !rawMsg.isEmpty() ? rawMsg : "Không thể kết nối Proxy";
        return failure(inferred, msg);
    }

    public static FailureReason inferFailureReasonFromMessage(@Nullable String message) {
        if (message == null || message.trim().isEmpty()) {
            return FailureReason.PROTOCOL_OR_RESPONSE_ERROR;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("hãy đổi sang") || lower.contains("từ chối phương thức")
                || lower.contains("scheme mismatch")) {
            return FailureReason.AUTH_SCHEME_MISMATCH;
        }
        if (lower.contains("yêu cầu xác thực") || lower.contains("vui lòng nhập tài khoản")
                || lower.contains("requires auth")) {
            return FailureReason.AUTH_REQUIRED;
        }
        if (lower.contains("sai tài khoản") || lower.contains("mật khẩu")
                || lower.contains("token") || lower.contains("407")
                || lower.contains("xác thực")) {
            return FailureReason.AUTH_FAILED;
        }
        if (lower.contains("refused") || lower.contains("econnrefused") || lower.contains("từ chối kết nối")) {
            return FailureReason.CONNECTION_REFUSED;
        }
        if (lower.contains("timeout") || lower.contains("timed out") || lower.contains("quá thời gian")) {
            return FailureReason.TIMEOUT;
        }
        if (lower.contains("unknown host") || lower.contains("unable to resolve")
                || lower.contains("no route to host") || lower.contains("unreachable")
                || lower.contains("không tìm thấy") || lower.contains("không phân giải")) {
            return FailureReason.HOST_UNREACHABLE;
        }
        if (lower.contains("ssl") || lower.contains("tls") || lower.contains("handshake")
                || lower.contains("certificate")) {
            return FailureReason.TLS_FAILED;
        }
        if (lower.contains("mã lỗi http") || lower.contains("http ")) {
            return FailureReason.HTTP_STATUS_ERROR;
        }
        return FailureReason.PROTOCOL_OR_RESPONSE_ERROR;
    }

    private static void closeQuietly(@Nullable Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    public static int parseHttpStatusCode(String statusLine) {
        if (statusLine == null) return -1;
        String[] parts = statusLine.trim().split("\\s+");
        if (parts.length < 2) return -1;
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String[] extractProbeFields(String jsonText) {
        if (jsonText == null || jsonText.isEmpty()) {
            return new String[]{"", ""};
        }
        try {
            JSONObject json = new JSONObject(jsonText);
            return new String[]{
                    json.optString("query", ""),
                    json.optString("country", "")
            };
        } catch (Throwable ignored) {
            // Pure-JVM fallback when org.json.JSONObject is the un-instrumented Android stub
            return new String[]{
                    extractJsonStringField(jsonText, "query"),
                    extractJsonStringField(jsonText, "country")
            };
        }
    }

    private static String extractJsonStringField(String jsonText, String key) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        Matcher m = p.matcher(jsonText);
        if (m.find()) {
            String raw = m.group(1);
            return raw != null ? raw.replace("\\\"", "\"").replace("\\\\", "\\") : "";
        }
        return "";
    }
}
