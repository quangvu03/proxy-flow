package com.example.model;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Pure Java model representing a single user-configured firewall rule stored in SQLite.
 * A rule can block one or more apps from accessing the internet (all targets when
 * targetAddress is blank, or matching IP/host/CIDR/port filters).
 */
public class FirewallRule {

    private long id;
    private String name;
    private boolean enabled;
    private Set<String> blockedPackages;
    private String targetAddress;
    private long createdAt;

    public FirewallRule(long id, String name, boolean enabled,
                        Set<String> blockedPackages, String targetAddress, long createdAt) {
        this.id = id;
        this.name = name != null ? name.trim() : "";
        this.enabled = enabled;
        this.blockedPackages = blockedPackages != null
                ? new LinkedHashSet<>(blockedPackages)
                : new LinkedHashSet<>();
        this.targetAddress = targetAddress != null ? targetAddress.trim() : "";
        this.createdAt = createdAt;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name != null ? name.trim() : "";
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Set<String> getBlockedPackages() {
        return Collections.unmodifiableSet(blockedPackages);
    }

    public void setBlockedPackages(Set<String> packages) {
        this.blockedPackages = packages != null ? new LinkedHashSet<>(packages) : new LinkedHashSet<>();
    }

    public String getTargetAddress() {
        return targetAddress;
    }

    public void setTargetAddress(String targetAddress) {
        this.targetAddress = targetAddress != null ? targetAddress.trim() : "";
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public boolean blocksAllInternet() {
        return targetAddress == null || targetAddress.trim().isEmpty();
    }

    public boolean appliesToPackage(String packageName) {
        return packageName != null && blockedPackages.contains(packageName);
    }

    /**
     * Checks whether this rule blocks a destination host and port.
     * Supports comma/newline-separated entries of:
     * - Empty targetAddress (blocks everything)
     * - Exact host or domain suffix (e.g. facebook.com blocks m.facebook.com)
     * - IPv4 or CIDR range (e.g. 192.168.1.0/24)
     * - Optional :port suffix (e.g. *:443 or example.com:8080)
     */
    public boolean matchesTarget(String host, int port) {
        if (blocksAllInternet()) {
            return true;
        }
        String cleanHost = host != null ? host.trim().toLowerCase(Locale.ROOT) : "";
        String[] tokens = targetAddress.split("[,;\\r\\n]+");
        for (String rawToken : tokens) {
            String token = rawToken != null ? rawToken.trim().toLowerCase(Locale.ROOT) : "";
            if (token.isEmpty()) continue;

            String patternHost = token;
            int patternPort = -1;
            int colonIdx = token.lastIndexOf(':');
            if (colonIdx > 0 && colonIdx < token.length() - 1 && token.indexOf(']') == -1) {
                String maybePort = token.substring(colonIdx + 1);
                try {
                    patternPort = Integer.parseInt(maybePort);
                    patternHost = token.substring(0, colonIdx);
                } catch (NumberFormatException ignored) {
                }
            }

            if (patternPort > 0 && patternPort != port) {
                continue;
            }
            if ("*".equals(patternHost) || patternHost.isEmpty()) {
                return true;
            }
            if (cleanHost.equals(patternHost) || cleanHost.endsWith("." + patternHost)) {
                return true;
            }
            if (com.example.network.PrivateNetworkMatcher.matchesCidrOrIp(cleanHost, patternHost)) {
                return true;
            }
        }
        return false;
    }

    public static String serializePackages(Set<String> packages) {
        if (packages == null || packages.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String pkg : packages) {
            if (pkg == null) continue;
            String trimmed = pkg.trim();
            if (trimmed.isEmpty()) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(trimmed);
        }
        return sb.toString();
    }

    public static Set<String> deserializePackages(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null || raw.trim().isEmpty()) {
            return out;
        }
        String[] parts = raw.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }
}
