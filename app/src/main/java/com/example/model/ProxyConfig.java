package com.example.model;

import android.content.Context;

import com.example.R;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Data model representing a Proxy configuration with protocol
 * (All Protocols / Auto, SOCKS5, HTTP, HTTP + TLS, SOCKS5 + UDP, SOCKS5 + TLS), host, port,
 * authentication method (None / Username & Password), DNS resolution mode
 * (Proxy, Cloudflare DNS, Google DNS, Custom), and health/latency telemetry.
 */
public class ProxyConfig {
    public static final long LATENCY_UNTESTED = -1L;
    public static final long LATENCY_FAILED = -2L;

    public enum Protocol {
        ALL("Tất cả giao thức", "auto://"),
        SOCKS5("SOCKS5", "socks5://"),
        HTTP("HTTP", "http://"),
        HTTP_TLS("HTTP + TLS", "http+tls://"),
        SOCKS5_UDP("SOCKS5 + UDP", "socks5+udp://"),
        SOCKS5_TLS("SOCKS5 + TLS", "socks5+tls://");

        private final String displayLabel;
        private final String schemePrefix;

        Protocol(String displayLabel, String schemePrefix) {
            this.displayLabel = displayLabel;
            this.schemePrefix = schemePrefix;
        }

        public String getDisplayLabel() {
            return displayLabel;
        }

        public String getDisplayLabel(Context context) {
            if (this == ALL && context != null) {
                return context.getString(R.string.protocol_all);
            }
            return displayLabel;
        }

        public String getSchemePrefix() {
            return schemePrefix;
        }

        public boolean isAutoDetect() {
            return this == ALL;
        }

        public boolean isSocks5() {
            return this == SOCKS5 || this == SOCKS5_UDP || this == SOCKS5_TLS;
        }

        public boolean isTls() {
            return this == HTTP_TLS || this == SOCKS5_TLS;
        }

        public static Protocol fromString(String raw) {
            if (raw == null || raw.trim().isEmpty()) {
                return HTTP;
            }
            String norm = raw.trim().toUpperCase(Locale.ROOT)
                    .replace(" ", "")
                    .replace("-", "_");
            switch (norm) {
                case "ALL":
                case "AUTO":
                case "AUTO_DETECT":
                case "ALLPROTOCOLS":
                case "ALL_PROTOCOLS":
                case "TẤTCẢGIAOTHỨC":
                case "TAT_CA_GIAO_THUC":
                    return ALL;
                case "SOCKS5":
                case "SOCKS":
                    return SOCKS5;
                case "HTTP+TLS":
                case "HTTP_TLS":
                case "HTTPS":
                    return HTTP_TLS;
                case "SOCKS5+UDP":
                case "SOCKS5_UDP":
                    return SOCKS5_UDP;
                case "SOCKS5+TLS":
                case "SOCKS5_TLS":
                case "SOCK5+TLS":
                case "SOCK5_TLS":
                    return SOCKS5_TLS;
                case "HTTP":
                default:
                    return HTTP;
            }
        }
    }

    public enum AuthMethod {
        NONE,
        USER_PASS,
        HTTP_BASIC,
        HTTP_DIGEST,
        BEARER_TOKEN;

        public boolean isCredentialPair() {
            return this == USER_PASS || this == HTTP_BASIC || this == HTTP_DIGEST;
        }

        public static AuthMethod fromString(String raw) {
            if (raw == null) return NONE;
            String norm = raw.trim().toUpperCase(Locale.ROOT)
                    .replace(" ", "_")
                    .replace("-", "_");
            switch (norm) {
                case "USER_PASS":
                case "PASSWORD":
                case "AUTO":
                    return USER_PASS;
                case "HTTP_BASIC":
                case "BASIC":
                    return HTTP_BASIC;
                case "HTTP_DIGEST":
                case "DIGEST":
                    return HTTP_DIGEST;
                case "BEARER_TOKEN":
                case "BEARER":
                case "TOKEN":
                    return BEARER_TOKEN;
                case "NONE":
                default:
                    return NONE;
            }
        }
    }

    public enum DnsMode {
        PROXY,
        CLOUDFLARE,
        GOOGLE,
        CUSTOM;

        public static DnsMode fromString(String raw) {
            if (raw == null) return PROXY;
            String norm = raw.trim().toUpperCase(Locale.ROOT);
            switch (norm) {
                case "CLOUDFLARE":
                case "CLOUDFLARE_DNS":
                    return CLOUDFLARE;
                case "GOOGLE":
                case "GOOGLE_DNS":
                    return GOOGLE;
                case "CUSTOM":
                    return CUSTOM;
                case "PROXY":
                default:
                    return PROXY;
            }
        }
    }

    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$");

    private long id;
    private String name;
    private Protocol protocol;
    private String host;
    private int port;
    private AuthMethod authMethod;
    private String username;
    private String password;
    private DnsMode dnsMode;
    private String customDns;
    private boolean enabled;
    private long latencyMs;
    private String exitIp;
    private String country;
    private long lastCheckedAt;
    private int failCount;
    private int sortOrder;

    public ProxyConfig() {
        this.name = "";
        this.protocol = Protocol.SOCKS5;
        this.host = "";
        this.port = 8080;
        this.authMethod = AuthMethod.NONE;
        this.username = "";
        this.password = "";
        this.dnsMode = DnsMode.PROXY;
        this.customDns = "";
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
        this(
                id,
                name,
                Protocol.HTTP,
                host,
                port,
                (username != null && !username.trim().isEmpty()) ? AuthMethod.USER_PASS : AuthMethod.NONE,
                username,
                password,
                DnsMode.PROXY,
                "",
                enabled,
                latencyMs,
                exitIp,
                country,
                lastCheckedAt,
                failCount,
                sortOrder
        );
    }

    public ProxyConfig(long id, String name, Protocol protocol, String host, int port,
                       AuthMethod authMethod, String username, String password,
                       DnsMode dnsMode, String customDns,
                       boolean enabled, long latencyMs, String exitIp, String country,
                       long lastCheckedAt, int failCount, int sortOrder) {
        this.id = id;
        this.name = name != null ? name.trim() : "";
        this.protocol = protocol != null ? protocol : Protocol.HTTP;
        this.host = host != null ? host.trim() : "";
        this.port = port;
        this.username = username != null ? username.trim() : "";
        this.password = password != null ? password : "";
        if (authMethod != null) {
            this.authMethod = authMethod;
        } else {
            this.authMethod = !this.username.isEmpty() ? AuthMethod.USER_PASS : AuthMethod.NONE;
        }
        this.dnsMode = dnsMode != null ? dnsMode : DnsMode.PROXY;
        this.customDns = customDns != null ? customDns.trim() : "";
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
                id, name, protocol, host, port, authMethod, username, password,
                dnsMode, customDns, enabled, latencyMs, exitIp, country,
                lastCheckedAt, failCount, sortOrder
        );
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name != null ? name.trim() : ""; }

    public Protocol getProtocol() { return protocol != null ? protocol : Protocol.HTTP; }
    public void setProtocol(Protocol protocol) { this.protocol = protocol != null ? protocol : Protocol.HTTP; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host != null ? host.trim() : ""; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public AuthMethod getAuthMethod() { return authMethod != null ? authMethod : AuthMethod.NONE; }
    public void setAuthMethod(AuthMethod authMethod) {
        this.authMethod = authMethod != null ? authMethod : AuthMethod.NONE;
    }

    public String getUsername() { return username; }
    public void setUsername(String username) {
        this.username = username != null ? username.trim() : "";
        if (!this.username.isEmpty() && getAuthMethod() == AuthMethod.NONE) {
            this.authMethod = AuthMethod.USER_PASS;
        }
    }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password != null ? password : ""; }

    public DnsMode getDnsMode() { return dnsMode != null ? dnsMode : DnsMode.PROXY; }
    public void setDnsMode(DnsMode dnsMode) { this.dnsMode = dnsMode != null ? dnsMode : DnsMode.PROXY; }

    public String getCustomDns() { return customDns != null ? customDns : ""; }
    public void setCustomDns(String customDns) { this.customDns = customDns != null ? customDns.trim() : ""; }

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

    public boolean isSocks5Protocol() {
        return getProtocol().isSocks5();
    }

    public boolean isTlsProtocol() {
        return getProtocol().isTls();
    }

    public String getProtocolLabel() {
        return getProtocol().getDisplayLabel();
    }

    public String getSchemePrefix() {
        return getProtocol().getSchemePrefix();
    }

    public boolean hasAuth() {
        AuthMethod method = getAuthMethod();
        if (method == AuthMethod.NONE) {
            return false;
        }
        if (method == AuthMethod.BEARER_TOKEN) {
            return !getAuthToken().isEmpty();
        }
        return (username != null && !username.isEmpty())
                || (password != null && !password.isEmpty());
    }

    public String getAuthToken() {
        if (password != null && !password.trim().isEmpty()) {
            return password.trim();
        }
        return username != null ? username.trim() : "";
    }

    public String getAuthDisplaySummary(Context context) {
        if (!hasAuth()) {
            return context != null ? context.getString(R.string.badge_auth_no) : "No Auth";
        }
        String userDisplay = (username != null && !username.isEmpty()) ? username : "Password";
        switch (getAuthMethod()) {
            case BEARER_TOKEN:
                return context != null ? context.getString(R.string.badge_auth_token) : "Bearer Token";
            case HTTP_DIGEST:
                return (context != null ? context.getString(R.string.badge_auth_digest) : "Digest")
                        + ": " + userDisplay;
            case HTTP_BASIC:
                return (context != null ? context.getString(R.string.badge_auth_basic) : "Basic")
                        + ": " + userDisplay;
            case USER_PASS:
            default:
                return (context != null ? context.getString(R.string.badge_auth_yes) : "User/Pass")
                        + ": " + userDisplay;
        }
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

    public List<String> getResolvedDnsServers() {
        List<String> list = new ArrayList<>();
        switch (getDnsMode()) {
            case CLOUDFLARE:
                list.add("1.1.1.1");
                list.add("1.0.0.1");
                break;
            case GOOGLE:
                list.add("8.8.8.8");
                list.add("8.8.4.4");
                break;
            case CUSTOM:
                if (customDns != null && !customDns.trim().isEmpty()) {
                    String[] tokens = customDns.split("[,;\\s]+");
                    for (String token : tokens) {
                        String trimmed = token.trim();
                        if (isValidDnsAddress(trimmed)) {
                            list.add(trimmed);
                        }
                    }
                }
                break;
            case PROXY:
            default:
                return Collections.emptyList();
        }
        return list;
    }

    public static boolean isValidCustomDnsInput(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return false;
        }
        String[] tokens = raw.trim().split("[,;\\s]+");
        if (tokens.length == 0) {
            return false;
        }
        for (String token : tokens) {
            if (!isValidDnsAddress(token.trim())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidDnsAddress(String val) {
        if (val == null || val.isEmpty()) return false;
        if (IPV4_PATTERN.matcher(val).matches()) return true;
        // Support IPv6 literal DNS servers (e.g. 2606:4700:4700::1111)
        return val.contains(":") && val.matches("^[0-9a-fA-F:]+$");
    }

    public String getBasicAuthToken() {
        if (!hasAuth()) {
            return "";
        }
        String raw = (username != null ? username : "") + ":" + (password != null ? password : "");
        return encodeBase64(raw.getBytes(StandardCharsets.UTF_8));
    }

    public String toExportLine() {
        if (hasAuth()) {
            return host + ":" + port + ":" + (username != null ? username : "") + ":" + (password != null ? password : "");
        }
        return host + ":" + port;
    }

    /**
     * Parses common proxy formats:
     * - host:port:user:pass
     * - host:port
     * - http://user:pass@host:port
     * - https://user:pass@host:port / http+tls://...
     * - socks5://user:pass@host:port / socks5+udp://... / socks5+tls://...
     * - user:pass@host:port
     * - host:port@user:pass
     */
    public static ProxyConfig parseFromLine(String rawLine) {
        if (rawLine == null) {
            return null;
        }
        String line = rawLine.replace("\uFEFF", "").trim();
        if (line.isEmpty() || line.startsWith("#")) {
            return null;
        }

        Protocol detectedProtocol = Protocol.HTTP;
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.startsWith("auto://") || lower.startsWith("all://")) {
            detectedProtocol = Protocol.ALL;
            line = line.substring(line.indexOf("://") + 3);
        } else if (lower.startsWith("socks5+tls://") || lower.startsWith("sock5+tls://")) {
            detectedProtocol = Protocol.SOCKS5_TLS;
            line = line.substring(line.indexOf("://") + 3);
        } else if (lower.startsWith("socks5+udp://")) {
            detectedProtocol = Protocol.SOCKS5_UDP;
            line = line.substring(line.indexOf("://") + 3);
        } else if (lower.startsWith("socks5://")) {
            detectedProtocol = Protocol.SOCKS5;
            line = line.substring(line.indexOf("://") + 3);
        } else if (lower.startsWith("http+tls://") || lower.startsWith("https://")) {
            detectedProtocol = Protocol.HTTP_TLS;
            line = line.substring(line.indexOf("://") + 3);
        } else if (lower.startsWith("http://")) {
            detectedProtocol = Protocol.HTTP;
            line = line.substring("http://".length());
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
            int lastAt = line.lastIndexOf('@');
            String left = line.substring(0, lastAt);
            String right = line.substring(lastAt + 1);
            String[] leftColon = left.split(":", 2);
            String[] rightColon = right.split(":", 2);
            if (rightColon.length == 2 && isValidPortString(rightColon[1])) {
                user = decodePercentComponent(leftColon[0]);
                pass = leftColon.length > 1 ? decodePercentComponent(leftColon[1]) : "";
                host = rightColon[0];
                port = Integer.parseInt(rightColon[1]);
            } else if (leftColon.length == 2 && isValidPortString(leftColon[1])) {
                host = leftColon[0];
                port = Integer.parseInt(leftColon[1]);
                user = decodePercentComponent(rightColon[0]);
                pass = rightColon.length > 1 ? decodePercentComponent(rightColon[1]) : "";
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
        config.setProtocol(detectedProtocol);
        config.setHost(host);
        config.setPort(port);
        if (!user.isEmpty() || !pass.isEmpty()) {
            config.setAuthMethod(AuthMethod.USER_PASS);
            config.setUsername(user);
            config.setPassword(pass);
        } else {
            config.setAuthMethod(AuthMethod.NONE);
            config.setUsername("");
            config.setPassword("");
        }
        config.setDnsMode(DnsMode.PROXY);
        config.setCustomDns("");
        config.setEnabled(true);
        return config;
    }

    private static String decodePercentComponent(String input) {
        if (input == null || !input.contains("%")) {
            return input != null ? input : "";
        }
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '%' && i + 2 < input.length()) {
                int hi = Character.digit(input.charAt(i + 1), 16);
                int lo = Character.digit(input.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    baos.write((hi << 4) | lo);
                    i += 2;
                    continue;
                }
            }
            byte[] utf8 = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
            baos.write(utf8, 0, utf8.length);
        }
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
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
                && protocol == that.protocol
                && authMethod == that.authMethod
                && dnsMode == that.dnsMode
                && Objects.equals(name, that.name)
                && Objects.equals(host, that.host)
                && Objects.equals(username, that.username)
                && Objects.equals(password, that.password)
                && Objects.equals(customDns, that.customDns)
                && Objects.equals(exitIp, that.exitIp)
                && Objects.equals(country, that.country);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, protocol, host, port, authMethod, username, password,
                dnsMode, customDns, enabled, latencyMs, exitIp, country, failCount);
    }
}
