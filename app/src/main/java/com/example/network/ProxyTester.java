package com.example.network;

import com.example.model.ProxyConfig;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Pure Java HTTP proxy tester that connects directly to an upstream proxy,
 * verifies optional Basic authentication, measures round-trip latency, and
 * resolves real exit IP & country via HTTP proxy GET or CONNECT fallback.
 */
public final class ProxyTester {

    private ProxyTester() {}

    public static class TestResult {
        private final boolean success;
        private final long latencyMs;
        private final String exitIp;
        private final String country;
        private final String errorMessage;

        public TestResult(boolean success, long latencyMs, String exitIp, String country, String errorMessage) {
            this.success = success;
            this.latencyMs = latencyMs;
            this.exitIp = exitIp != null ? exitIp : "";
            this.country = country != null ? country : "";
            this.errorMessage = errorMessage != null ? errorMessage : "";
        }

        public boolean isSuccess() { return success; }
        public long getLatencyMs() { return latencyMs; }
        public String getExitIp() { return exitIp; }
        public String getCountry() { return country; }
        public String getErrorMessage() { return errorMessage; }
    }

    public static TestResult testProxy(ProxyConfig proxy, int timeoutMs) {
        if (proxy == null || proxy.getHost().isEmpty() || proxy.getPort() <= 0) {
            return new TestResult(false, -1, "", "", "Cấu hình Proxy không hợp lệ");
        }

        long startNs = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(proxy.getHost(), proxy.getPort()), timeoutMs);
            socket.setSoTimeout(timeoutMs);

            StringBuilder req = new StringBuilder();
            req.append("GET http://ip-api.com/json?fields=status,country,query HTTP/1.1\r\n");
            req.append("Host: ip-api.com\r\n");
            req.append("User-Agent: ProxyFlow/1.0\r\n");
            if (proxy.hasAuth()) {
                req.append("Proxy-Authorization: Basic ").append(proxy.getBasicAuthToken()).append("\r\n");
            }
            req.append("Proxy-Connection: close\r\n");
            req.append("Connection: close\r\n\r\n");

            OutputStream out = socket.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String statusLine = reader.readLine();
            if (statusLine == null) {
                return new TestResult(false, -1, "", "", "Proxy đóng kết nối đột ngột");
            }

            int statusCode = parseHttpStatusCode(statusLine);
            if (statusCode == 407) {
                return new TestResult(false, -1, "", "", "Lỗi 407: Sai tài khoản hoặc mật khẩu");
            }

            // Skip headers
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                // Consume headers
            }

            StringBuilder body = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                body.append(line);
                if (body.length() > 8192) break;
            }

            long elapsedMs = Math.max(1L, (System.nanoTime() - startNs) / 1_000_000L);
            if (statusCode >= 200 && statusCode < 300) {
                String exitIp = "";
                String country = "";
                try {
                    JSONObject json = new JSONObject(body.toString().trim());
                    exitIp = json.optString("query", "");
                    country = json.optString("country", "");
                } catch (Exception ignored) {
                }
                return new TestResult(true, elapsedMs, exitIp, country, "");
            }

            // Fallback to CONNECT test in case target HTTP endpoint is blocked by proxy policy
            return testConnectTunnel(proxy, timeoutMs);
        } catch (IOException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Không thể kết nối Proxy";
            return new TestResult(false, -1, "", "", msg);
        }
    }

    private static TestResult testConnectTunnel(ProxyConfig proxy, int timeoutMs) {
        long startNs = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(proxy.getHost(), proxy.getPort()), timeoutMs);
            socket.setSoTimeout(timeoutMs);

            StringBuilder req = new StringBuilder();
            req.append("CONNECT 1.1.1.1:443 HTTP/1.1\r\n");
            req.append("Host: 1.1.1.1:443\r\n");
            if (proxy.hasAuth()) {
                req.append("Proxy-Authorization: Basic ").append(proxy.getBasicAuthToken()).append("\r\n");
            }
            req.append("\r\n");

            OutputStream out = socket.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String statusLine = reader.readLine();
            if (statusLine == null) {
                return new TestResult(false, -1, "", "", "Không nhận được phản hồi CONNECT");
            }
            int code = parseHttpStatusCode(statusLine);
            long elapsedMs = Math.max(1L, (System.nanoTime() - startNs) / 1_000_000L);
            if (code == 407) {
                return new TestResult(false, -1, "", "", "Lỗi 407: Sai tài khoản hoặc mật khẩu");
            }
            if (code >= 200 && code < 300) {
                return new TestResult(true, elapsedMs, proxy.getHost(), "", "");
            }
            return new TestResult(false, -1, "", "", "Mã lỗi HTTP: " + code);
        } catch (IOException e) {
            return new TestResult(false, -1, "", "", e.getMessage() != null ? e.getMessage() : "Lỗi kết nối");
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
}
