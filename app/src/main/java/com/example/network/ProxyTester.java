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

    public static class TestResult {
        private final boolean success;
        private final long latencyMs;
        private final String exitIp;
        private final String country;
        private final String errorMessage;
        @Nullable
        private final ProxyConfig.Protocol detectedProtocol;

        public TestResult(boolean success, long latencyMs, String exitIp, String country, String errorMessage) {
            this(success, latencyMs, exitIp, country, errorMessage, null);
        }

        public TestResult(boolean success, long latencyMs, String exitIp, String country,
                          String errorMessage, @Nullable ProxyConfig.Protocol detectedProtocol) {
            this.success = success;
            this.latencyMs = latencyMs;
            this.exitIp = exitIp != null ? exitIp : "";
            this.country = country != null ? country : "";
            this.errorMessage = errorMessage != null ? errorMessage : "";
            this.detectedProtocol = detectedProtocol;
        }

        public boolean isSuccess() { return success; }
        public long getLatencyMs() { return latencyMs; }
        public String getExitIp() { return exitIp; }
        public String getCountry() { return country; }
        public String getErrorMessage() { return errorMessage; }
        @Nullable
        public ProxyConfig.Protocol getDetectedProtocol() { return detectedProtocol; }
    }

    public static TestResult testProxy(ProxyConfig proxy, int timeoutMs) {
        if (proxy == null || proxy.getHost().isEmpty() || proxy.getPort() <= 0 || proxy.getPort() > 65535) {
            return new TestResult(false, -1, "", "", "Cấu hình Proxy không hợp lệ");
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
                        return new TestResult(true, r.getLatencyMs(), r.getExitIp(), r.getCountry(), "", candidate);
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

        String bestError = selectBestProbeError(failures);
        return new TestResult(false, -1, "", "", bestError, null);
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

    private static String selectBestProbeError(List<TestResult> failures) {
        String best = "";
        int bestScore = -1;
        for (TestResult r : failures) {
            String msg = r.getErrorMessage();
            if (msg == null || msg.trim().isEmpty()) continue;
            String lower = msg.toLowerCase(Locale.ROOT);
            int score = 1;
            if (lower.contains("407") || lower.contains("xác thực")
                    || lower.contains("tài khoản") || lower.contains("mật khẩu")
                    || lower.contains("token")) {
                score = 5;
            } else if (lower.contains("mã lỗi http")) {
                score = 4;
            } else if (lower.contains("timeout") || lower.contains("quá thời gian") || lower.contains("timed out")) {
                score = 3;
            } else if (lower.contains("refused")) {
                score = 2;
            }
            if (score > bestScore) {
                bestScore = score;
                best = msg;
            }
        }
        return !best.isEmpty() ? best : "Không tìm thấy giao thức phù hợp hoặc Proxy không phản hồi";
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
                return new TestResult(false, -1, "", "", "Proxy đóng kết nối đột ngột");
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
                        return new TestResult(false, -1, "", "", "Proxy đóng kết nối đột ngột");
                    }
                    head = retryHead;
                }
            }

            int statusCode = head.getStatusCode();
            if (statusCode == 407) {
                return new TestResult(false, -1, "", "",
                        ProxyAuthHelper.describe407Failure(proxy, head.getProxyAuthenticateHeaders()));
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
                    return new TestResult(true, elapsedMs, exitIp, country, "", proxy.getProtocol());
                }
            }

            // Fallback to CONNECT test in case target HTTP endpoint is blocked or non-JSON
            closeQuietly(socket);
            socket = null;
            return testConnectTunnel(proxy, timeoutMs);
        } catch (IOException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Không thể kết nối Proxy";
            return new TestResult(false, -1, "", "", msg);
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
                return new TestResult(true, elapsedMs, exitIp, country, "", proxy.getProtocol());
            }
            return new TestResult(true, elapsedMs, proxy.getHost(), "", "", proxy.getProtocol());
        } catch (UpstreamProxyConnector.ProxyAuthException authErr) {
            return new TestResult(false, -1, "", "",
                    authErr.getMessage() != null ? authErr.getMessage() : "Lỗi xác thực SOCKS5");
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().toLowerCase(java.util.Locale.ROOT).contains("specified port")) {
                return new TestResult(false, -1, "", "", e.getMessage());
            }
            // Fallback CONNECT check to 1.1.1.1:443
            long fallbackStart = System.nanoTime();
            try (Socket ignored = UpstreamProxyConnector.openTunnel(proxy, "1.1.1.1", 443, timeoutMs)) {
                long elapsedMs = Math.max(1L, (System.nanoTime() - fallbackStart) / 1_000_000L);
                return new TestResult(true, elapsedMs, proxy.getHost(), "", "", proxy.getProtocol());
            } catch (UpstreamProxyConnector.ProxyAuthException authErr) {
                return new TestResult(false, -1, "", "",
                        authErr.getMessage() != null ? authErr.getMessage() : "Lỗi xác thực SOCKS5");
            } catch (IOException fallbackErr) {
                String msg = fallbackErr.getMessage() != null ? fallbackErr.getMessage() : "Không thể kết nối SOCKS5 Proxy";
                return new TestResult(false, -1, "", "", msg);
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
                    return new TestResult(true, elapsedMs, proxy.getHost(), "", "", proxy.getProtocol());
                }
                return new TestResult(false, -1, "", "", "Mã lỗi HTTP: " + code);
            }
        } catch (UpstreamProxyConnector.ProxyAuthException authErr) {
            return new TestResult(false, -1, "", "",
                    authErr.getMessage() != null ? authErr.getMessage() : "Lỗi xác thực Proxy");
        } catch (IOException e) {
            return new TestResult(false, -1, "", "", e.getMessage() != null ? e.getMessage() : "Lỗi kết nối");
        }
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
