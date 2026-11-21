package com.example.network;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Pure Java utility for validating and matching private / LAN IPv4 addresses
 * and CIDR ranges (RFC1918, CGNAT, link-local, loopback, and custom ranges).
 */
public final class PrivateNetworkMatcher {

    public static final List<String> DEFAULT_PRIVATE_RANGES = Arrays.asList(
            "10.0.0.0/8",
            "172.16.0.0/12",
            "192.168.0.0/16",
            "100.64.0.0/10",
            "169.254.0.0/16",
            "127.0.0.0/8"
    );

    private PrivateNetworkMatcher() {
    }

    public static String getDefaultRangesText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < DEFAULT_PRIVATE_RANGES.size(); i++) {
            sb.append(DEFAULT_PRIVATE_RANGES.get(i));
            if (i < DEFAULT_PRIVATE_RANGES.size() - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    public static List<String> parseRanges(String rawText) {
        List<String> list = new ArrayList<>();
        if (rawText == null || rawText.trim().isEmpty()) {
            return list;
        }
        String[] lines = rawText.split("[,;\\r\\n]+");
        for (String line : lines) {
            String trimmed = line != null ? line.trim() : "";
            if (!trimmed.isEmpty()) {
                list.add(trimmed);
            }
        }
        return list;
    }

    /**
     * Returns the first invalid entry in rawText, or null if all entries are valid IPv4/CIDR values.
     */
    public static String findFirstInvalidEntry(String rawText) {
        List<String> entries = parseRanges(rawText);
        for (String entry : entries) {
            if (!isValidIpv4OrCidr(entry)) {
                return entry;
            }
        }
        return null;
    }

    public static boolean isValidIpv4OrCidr(String entry) {
        if (entry == null) return false;
        String trimmed = entry.trim();
        if (trimmed.isEmpty()) return false;
        int slashIdx = trimmed.indexOf('/');
        if (slashIdx >= 0) {
            String ipPart = trimmed.substring(0, slashIdx).trim();
            String prefixPart = trimmed.substring(slashIdx + 1).trim();
            if (parseIpv4Int(ipPart) == null) {
                return false;
            }
            try {
                int prefix = Integer.parseInt(prefixPart);
                return prefix >= 0 && prefix <= 32;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return parseIpv4Int(trimmed) != null;
    }

    public static boolean isPrivateOrConfiguredTarget(String host, List<String> configuredRanges) {
        if (host == null) return false;
        String clean = stripHostBrackets(host).trim().toLowerCase(Locale.ROOT);
        if (clean.isEmpty()) return false;
        if ("localhost".equals(clean) || clean.endsWith(".local")) {
            return true;
        }
        if (matchesAny(clean, DEFAULT_PRIVATE_RANGES)) {
            return true;
        }
        return configuredRanges != null && !configuredRanges.isEmpty() && matchesAny(clean, configuredRanges);
    }

    public static boolean matchesAny(String host, List<String> cidrsOrIps) {
        if (host == null || cidrsOrIps == null) return false;
        String clean = stripHostBrackets(host).trim();
        for (String entry : cidrsOrIps) {
            if (matchesCidrOrIp(clean, entry)) {
                return true;
            }
        }
        return false;
    }

    public static boolean matchesCidrOrIp(String hostIp, String cidrOrIp) {
        if (hostIp == null || cidrOrIp == null) return false;
        Integer target = parseIpv4Int(stripHostBrackets(hostIp).trim());
        if (target == null) return false;

        String rule = cidrOrIp.trim();
        int slashIdx = rule.indexOf('/');
        if (slashIdx < 0) {
            Integer exact = parseIpv4Int(rule);
            return exact != null && exact.intValue() == target.intValue();
        }
        String netStr = rule.substring(0, slashIdx).trim();
        String prefixStr = rule.substring(slashIdx + 1).trim();
        Integer network = parseIpv4Int(netStr);
        if (network == null) return false;
        int prefix;
        try {
            prefix = Integer.parseInt(prefixStr);
        } catch (NumberFormatException e) {
            return false;
        }
        if (prefix < 0 || prefix > 32) return false;
        if (prefix == 0) return true;
        int mask = 0xFFFFFFFF << (32 - prefix);
        return (target & mask) == (network & mask);
    }

    public static Integer parseIpv4Int(String ip) {
        if (ip == null || ip.isEmpty()) return null;
        String[] octets = ip.split("\\.", -1);
        if (octets.length != 4) return null;
        int result = 0;
        for (String octetStr : octets) {
            if (octetStr.isEmpty() || octetStr.length() > 3) return null;
            int val;
            try {
                val = Integer.parseInt(octetStr);
            } catch (NumberFormatException e) {
                return null;
            }
            if (val < 0 || val > 255) return null;
            result = (result << 8) | val;
        }
        return result;
    }

    private static String stripHostBrackets(String host) {
        if (host.startsWith("[") && host.endsWith("]") && host.length() > 2) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }
}
