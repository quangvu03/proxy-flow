package com.example.model;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Immutable-friendly data model representing an HTTP Proxy configuration
 * with host, port, optional username/password authentication, rotation flag,
 * and live health/latency telemetry.
 */
public class ProxyConfig {
    public static final long LATENCY_UNTESTED = -1L;
    public static final long LATENCY_FAILED = -2L;

    private long id;
    private String name;
    private String host;
    private int port;
    private String username;
    private String password;
    private boolean enabled;
    private long latencyMs;
    private String exitIp;
    private String country;
    private long lastCheckedAt;
    private int failCount;
    private int sortOrder;

    public ProxyConfig() {
        this.name = "";
        this.host = "";
        this.port = 8080;
        this.username = "";
        this.password = "";
        this.enabled = true;
        this.latencyMs = LATENCY_UNTESTED;
        this.exitIp = "";
        this.country = "";
        this.lastCheckedAt = 0L;
        this.failCount = 0;
        this.sortOrder = 0;
    }

    public ProxyConfig(long id, String name, String host, int port, String username, String password,
                       boolean enabled, long latencyMs, String exitIp, String country,
                       long lastCheckedAt, int failCount, int sortOrder) {
        this.id = id;
        this.name = name != null ? name.trim() : "";
        this.host = host != null ? host.trim() : "";
        this.port = port;
        this.username = username != null ? username.trim() : "";
        this.password = password != null ? password : "";
        this.enabled = enabled;
        this.latencyMs = latencyMs;
        this.exitIp = exitIp != null ? exitIp.trim() : "";
        this.country = country != null ? country.trim() : "";
        this.lastCheckedAt = lastCheckedAt;
        this.failCount = failCount;
        this.sortOrder = sortOrder;
    }

    public ProxyConfig copy() {
        return new ProxyConfig(
                id, name, host, port, username, password,
                enabled, latencyMs, exitIp, country, lastCheckedAt, failCount, sortOrder
        );
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name != null ? name.trim() : ""; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host != null ? host.trim() : ""; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username != null ? username.trim() : ""; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password != null ? password : ""; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(long latencyMs) { this.latencyMs = latencyMs; }

    public String getExitIp() { return exitIp; }
    public void setExitIp(String exitIp) { this.exitIp = exitIp != null ? exitIp.trim() : ""; }

    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country != null ? country.trim() : ""; }

    public long getLastCheckedAt() { return lastCheckedAt; }
    public void setLastCheckedAt(long lastCheckedAt) { this.lastCheckedAt = lastCheckedAt; }

    public int getFailCount() { return failCount; }
    public void setFailCount(int failCount) { this.failCount = failCount; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    public boolean hasAuth() {
        return username != null && !username.isEmpty();
    }

    public String getAddressDisplay() {
        return host + ":" + port;
    }

    public String getDisplayTitle() {
        if (name != null && !name.isEmpty()) {
            return name;
        }
        return getAddressDisplay();
    }

    public String getBasicAuthToken() {
        if (!hasAuth()) {
            return "";
        }
        String raw = username + ":" + (password != null ? password : "");
        return encodeBase64(raw.getBytes(StandardCharsets.UTF_8));
    }

    public String toExportLine() {
        if (hasAuth()) {
            return host + ":" + port + ":" + username + ":" + (password != null ? password : "");
        }
        return host + ":" + port;
    }

    /**
     * Parses common proxy formats:
     * - host:port:user:pass
     * - host:port
     * - http://user:pass@host:port
     * - user:pass@host:port
     * - host:port@user:pass
     */
    public static ProxyConfig parseFromLine(String rawLine) {
        if (rawLine == null) {
            return null;
        }
        String line = rawLine.trim();
        if (line.isEmpty() || line.startsWith("#")) {
            return null;
        }

        if (line.startsWith("http://")) {
            line = line.substring("http://".length());
        } else if (line.startsWith("https://")) {
            line = line.substring("https://".length());
        }
        int slashIdx = line.indexOf('/');
        if (slashIdx > 0) {
            line = line.substring(0, slashIdx);
        }

        String host = "";
        int port = -1;
        String user = "";
        String pass = "";

        if (line.contains("@")) {
            String[] atParts = line.split("@", 2);
            String left = atParts[0];
            String right = atParts[1];
            String[] leftColon = left.split(":", 2);
            String[] rightColon = right.split(":", 2);
            if (rightColon.length == 2 && isValidPortString(rightColon[1])) {
                // user:pass@host:port
                user = leftColon[0];
                pass = leftColon.length > 1 ? leftColon[1] : "";
                host = rightColon[0];
                port = Integer.parseInt(rightColon[1]);
            } else if (leftColon.length == 2 && isValidPortString(leftColon[1])) {
                // host:port@user:pass
                host = leftColon[0];
                port = Integer.parseInt(leftColon[1]);
                user = rightColon[0];
                pass = rightColon.length > 1 ? rightColon[1] : "";
            } else {
                return null;
            }
        } else {
            String[] parts = line.split(":", 4);
            if (parts.length < 2 || !isValidPortString(parts[1])) {
                return null;
            }
            host = parts[0].trim();
            port = Integer.parseInt(parts[1].trim());
            if (parts.length >= 3) {
                user = parts[2].trim();
            }
            if (parts.length == 4) {
                pass = parts[3];
            }
        }

        if (host.isEmpty() || port < 1 || port > 65535) {
            return null;
        }

        ProxyConfig config = new ProxyConfig();
        config.setHost(host);
        config.setPort(port);
        config.setUsername(user);
        config.setPassword(pass);
        config.setEnabled(true);
        return config;
    }

    private static boolean isValidPortString(String val) {
        if (val == null || val.trim().isEmpty()) {
            return false;
        }
        try {
            int p = Integer.parseInt(val.trim());
            return p >= 1 && p <= 65535;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String encodeBase64(byte[] data) {
        final char[] alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
        StringBuilder out = new StringBuilder(((data.length + 2) / 3) * 4);
        int i = 0;
        while (i < data.length) {
            int b0 = data[i++] & 0xFF;
            int b1 = i < data.length ? (data[i++] & 0xFF) : -1;
            int b2 = i < data.length ? (data[i++] & 0xFF) : -1;

            out.append(alphabet[b0 >> 2]);
            out.append(alphabet[((b0 & 0x03) << 4) | (b1 >= 0 ? (b1 >> 4) : 0)]);
            out.append(b1 >= 0 ? alphabet[((b1 & 0x0F) << 2) | (b2 >= 0 ? (b2 >> 6) : 0)] : '=');
            out.append(b2 >= 0 ? alphabet[b2 & 0x3F] : '=');
        }
        return out.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProxyConfig)) return false;
        ProxyConfig that = (ProxyConfig) o;
        return id == that.id
                && port == that.port
                && enabled == that.enabled
                && latencyMs == that.latencyMs
                && failCount == that.failCount
                && Objects.equals(name, that.name)
                && Objects.equals(host, that.host)
                && Objects.equals(username, that.username)
                && Objects.equals(password, that.password)
                && Objects.equals(exitIp, that.exitIp)
                && Objects.equals(country, that.country);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, host, port, username, password, enabled, latencyMs, exitIp, country, failCount);
    }
}
