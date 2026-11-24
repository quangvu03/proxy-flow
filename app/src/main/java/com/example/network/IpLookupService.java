package com.example.network;

import com.example.model.IpDetails;
import com.example.model.ProxyConfig;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Pure Java network inspector resolving both:
 * 1) Direct/Original device IPv4, IPv6, and geolocation (bypassing VPN via app disallow list).
 * 2) Active Proxy exit IPv4, IPv6, and geolocation (tunneled through upstream proxy with Basic auth).
 *
 * Always resolves the exact IPv4 address FIRST, then queries geolocation explicitly for that IPv4
 * across multiple accurate providers (ipinfo.io + ipwho.is, with ip-api.com fallback) so dual-stack
 * IPv6 carrier PoPs never skew the reported province/region or city.
 */
public final class IpLookupService {

    private static final int TIMEOUT_MS = 7500;
    private static final int IP_ONLY_TIMEOUT_MS = 5000;
    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$");
    private static final Pattern AS_PREFIX_PATTERN = Pattern.compile("^AS\\d+\\s+", Pattern.CASE_INSENSITIVE);

    private IpLookupService() {}

    public static IpDetails.IpComparisonResult inspectBoth(ProxyConfig activeProxyIfVpnRunning) {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<IpDetails> originFuture = pool.submit(IpLookupService::lookupDirectOriginIp);
            Future<IpDetails> currentFuture = activeProxyIfVpnRunning != null
                    ? pool.submit(() -> lookupViaProxy(activeProxyIfVpnRunning))
                    : null;

            IpDetails origin = safeGet(originFuture, new IpDetails("", "", "", "", "", "", "", 0, 0, false, false, "Timeout"));
            IpDetails current = currentFuture != null
                    ? safeGet(currentFuture, new IpDetails("", "", "", "", "", "", "", 0, 0, false, true, "Không thể truy vấn qua Proxy"))
                    : origin.copyWithRouted(false);

            return new IpDetails.IpComparisonResult(current, origin, System.currentTimeMillis());
        } finally {
            pool.shutdownNow();
        }
    }

    public static IpDetails lookupDirectOriginIp() {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            // Step 1: Resolve exact IPv4 and IPv6 first
            Future<String> v4Future = pool.submit(IpLookupService::fetchDirectIpv4WithFallback);
            Future<String> v6Future = pool.submit(() -> fetchDirectIpText("https://api6.ipify.org?format=json"));

            String ipv4 = safeString(v4Future);
            String ipv6 = safeString(v6Future);

            // Step 2: Query geolocation explicitly for the resolved IPv4 (or IPv6 if v4 unavailable)
            String geoTargetIp = !ipv4.isEmpty() ? ipv4 : ipv6;
            GeoInfo geo = queryDirectGeoConsensus(pool, geoTargetIp);

            if (ipv4.isEmpty() && isValidIpv4(geo.queryIp)) {
                ipv4 = geo.queryIp;
            }

            String err = (ipv4.isEmpty() && ipv6.isEmpty()) ? "Không có kết nối mạng trực tiếp" : "";
            return new IpDetails(
                    ipv4, ipv6, geo.region, geo.city, geo.country, geo.countryCode, geo.isp,
                    geo.lat, geo.lon, geo.hasCoords, false, err
            );
        } finally {
            pool.shutdownNow();
        }
    }

    public static IpDetails lookupViaProxy(ProxyConfig proxy) {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            // Step 1: Resolve exit IPv4 and IPv6 through the proxy first
            Future<String> v4Future = pool.submit(() -> fetchProxyIpv4WithFallback(proxy));
            Future<String> v6Future = pool.submit(() -> fetchProxyHttpsIp(proxy, "api6.ipify.org", "/?format=json", IP_ONLY_TIMEOUT_MS));

            String ipv4 = safeString(v4Future);
            String ipv6 = safeString(v6Future);

            // Step 2: Query geolocation explicitly for the resolved exit IPv4 (or IPv6 / auto)
            String geoTargetIp = !ipv4.isEmpty() ? ipv4 : ipv6;
            GeoInfo geo = queryProxyGeoConsensus(pool, proxy, geoTargetIp);

            if (ipv4.isEmpty() && isValidIpv4(geo.queryIp)) {
                ipv4 = geo.queryIp;
            }

            String err = (ipv4.isEmpty() && !geo.hasGeo) ? "Proxy không phản hồi hoặc lỗi xác thực" : "";
            return new IpDetails(
                    ipv4, ipv6, geo.region, geo.city, geo.country, geo.countryCode, geo.isp,
                    geo.lat, geo.lon, geo.hasCoords, true, err
            );
        } finally {
            pool.shutdownNow();
        }
    }

    private static GeoInfo queryDirectGeoConsensus(ExecutorService pool, String targetIp) {
        String ipSuffix = (targetIp != null) ? targetIp.trim() : "";
        Future<GeoInfo> ipInfoFuture = pool.submit(() -> fetchDirectIpInfo(ipSuffix));
        Future<GeoInfo> ipWhoFuture = pool.submit(() -> fetchDirectIpWhoIs(ipSuffix));

        GeoInfo primary = safeGeo(ipInfoFuture);
        GeoInfo secondary = safeGeo(ipWhoFuture);

        GeoInfo merged = mergeGeoInfo(primary, secondary);
        if (!merged.hasCompleteGeo()) {
            GeoInfo fallback = fetchDirectIpApi(ipSuffix);
            merged = mergeGeoInfo(merged, fallback);
        }
        return merged;
    }

    private static GeoInfo queryProxyGeoConsensus(ExecutorService pool, ProxyConfig proxy, String targetIp) {
        String ipSuffix = (targetIp != null) ? targetIp.trim() : "";
        Future<GeoInfo> ipInfoFuture = pool.submit(() -> fetchProxyHttpsGeoIpInfo(proxy, ipSuffix));
        Future<GeoInfo> ipWhoFuture = pool.submit(() -> fetchProxyHttpsGeoIpWhoIs(proxy, ipSuffix));

        GeoInfo primary = safeGeo(ipInfoFuture);
        GeoInfo secondary = safeGeo(ipWhoFuture);

        GeoInfo merged = mergeGeoInfo(primary, secondary);
        if (!merged.hasCompleteGeo()) {
            GeoInfo fallback = fetchProxyHttpIpApi(proxy, ipSuffix);
            merged = mergeGeoInfo(merged, fallback);
        }
        return merged;
    }

    private static String fetchDirectIpv4WithFallback() {
        String ip = fetchDirectIpText("https://api4.ipify.org?format=json");
        if (isValidIpv4(ip)) return ip;

        ip = fetchDirectIpText("https://ipv4.icanhazip.com/");
        if (isValidIpv4(ip)) return ip;

        ip = fetchDirectIpText("https://checkip.amazonaws.com/");
        if (isValidIpv4(ip)) return ip;

        return "";
    }

    private static String fetchProxyIpv4WithFallback(ProxyConfig proxy) {
        String ip = fetchProxyHttpsIp(proxy, "api4.ipify.org", "/?format=json", IP_ONLY_TIMEOUT_MS);
        if (isValidIpv4(ip)) return ip;

        ip = fetchProxyHttpsIp(proxy, "ipv4.icanhazip.com", "/", IP_ONLY_TIMEOUT_MS);
        if (isValidIpv4(ip)) return ip;

        ip = fetchProxyHttpsIp(proxy, "checkip.amazonaws.com", "/", IP_ONLY_TIMEOUT_MS);
        if (isValidIpv4(ip)) return ip;

        return "";
    }

    private static String fetchDirectIpText(String endpoint) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(endpoint);
            conn = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
            conn.setConnectTimeout(IP_ONLY_TIMEOUT_MS);
            conn.setReadTimeout(IP_ONLY_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "ProxyFlow/1.1");
            conn.setRequestProperty("Accept", "application/json, text/plain");
            if (conn.getResponseCode() == 200) {
                String body = readStream(conn).trim();
                JSONObject json = parseFirstJsonObject(body);
                if (json != null) {
                    return json.optString("ip", "").trim();
                }
                return extractFirstLineIp(body);
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return "";
    }

    private static GeoInfo fetchDirectIpInfo(String targetIp) {
        String urlStr = targetIp.isEmpty()
                ? "https://ipinfo.io/json"
                : "https://ipinfo.io/" + targetIp + "/json";
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection(Proxy.NO_PROXY);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "ProxyFlow/1.1");
            conn.setRequestProperty("Accept", "application/json");
            if (conn.getResponseCode() == 200) {
                JSONObject json = parseFirstJsonObject(readStream(conn));
                if (json != null && !json.has("error")) {
                    return GeoInfo.fromIpInfo(json);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return new GeoInfo();
    }

    private static GeoInfo fetchDirectIpWhoIs(String targetIp) {
        String urlStr = "https://ipwho.is/" + targetIp;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection(Proxy.NO_PROXY);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "ProxyFlow/1.1");
            conn.setRequestProperty("Accept", "application/json");
            if (conn.getResponseCode() == 200) {
                JSONObject json = parseFirstJsonObject(readStream(conn));
                if (json != null && json.optBoolean("success", false)) {
                    return GeoInfo.fromIpWhoIs(json);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return new GeoInfo();
    }

    private static GeoInfo fetchDirectIpApi(String targetIp) {
        String urlStr = "http://ip-api.com/json/" + targetIp
                + "?fields=status,country,countryCode,regionName,city,isp,org,lat,lon,query";
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection(Proxy.NO_PROXY);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "ProxyFlow/1.1");
            if (conn.getResponseCode() == 200) {
                JSONObject json = parseFirstJsonObject(readStream(conn));
                if (json != null && "success".equalsIgnoreCase(json.optString("status"))) {
                    return GeoInfo.fromIpApi(json);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return new GeoInfo();
    }

    private static GeoInfo fetchProxyHttpsGeoIpInfo(ProxyConfig proxy, String targetIp) {
        String path = targetIp.isEmpty() ? "/json" : ("/" + targetIp + "/json");
        String body = executeHttpsGetViaProxy(proxy, "ipinfo.io", path, TIMEOUT_MS);
        JSONObject json = parseFirstJsonObject(body);
        if (json != null && !json.has("error")) {
            return GeoInfo.fromIpInfo(json);
        }
        return new GeoInfo();
    }

    private static GeoInfo fetchProxyHttpsGeoIpWhoIs(ProxyConfig proxy, String targetIp) {
        String path = (targetIp != null && !targetIp.isEmpty()) ? ("/" + targetIp) : "/";
        String body = executeHttpsGetViaProxy(proxy, "ipwho.is", path, TIMEOUT_MS);
        JSONObject json = parseFirstJsonObject(body);
        if (json != null && json.optBoolean("success", false)) {
            return GeoInfo.fromIpWhoIs(json);
        }
        return new GeoInfo();
    }

    private static GeoInfo fetchProxyHttpIpApi(ProxyConfig proxy, String targetIp) {
        String querySuffix = "/json/" + targetIp
                + "?fields=status,country,countryCode,regionName,city,isp,org,lat,lon,query";
        if (proxy.isSocks5Protocol()) {
            try (Socket tunnel = UpstreamProxyConnector.openTunnel(proxy, "ip-api.com", 80, TIMEOUT_MS)) {
                tunnel.setSoTimeout(TIMEOUT_MS);
                String req = "GET " + querySuffix + " HTTP/1.1\r\n"
                        + "Host: ip-api.com\r\n"
                        + "User-Agent: ProxyFlow/1.1\r\n"
                        + "Accept: application/json\r\n"
                        + "Accept-Encoding: identity\r\n"
                        + "Connection: close\r\n\r\n";
                OutputStream out = tunnel.getOutputStream();
                out.write(req.getBytes(StandardCharsets.UTF_8));
                out.flush();

                String rawResp = readSocketText(tunnel);
                String body = decodeHttpResponseBody(rawResp);
                JSONObject json = parseFirstJsonObject(body);
                if (json != null && "success".equalsIgnoreCase(json.optString("status"))) {
                    return GeoInfo.fromIpApi(json);
                }
            } catch (Exception ignored) {
            }
            return new GeoInfo();
        }

        try (Socket socket = UpstreamProxyConnector.openRawUpstreamSocket(proxy, TIMEOUT_MS)) {
            socket.setSoTimeout(TIMEOUT_MS);

            String targetPath = "http://ip-api.com" + querySuffix;
            StringBuilder req = new StringBuilder();
            req.append("GET ").append(targetPath).append(" HTTP/1.1\r\n");
            req.append("Host: ip-api.com\r\n");
            req.append("User-Agent: ProxyFlow/1.1\r\n");
            req.append("Accept: application/json\r\n");
            req.append("Accept-Encoding: identity\r\n");
            if (proxy.hasAuth()) {
                req.append("Proxy-Authorization: Basic ").append(proxy.getBasicAuthToken()).append("\r\n");
            }
            req.append("Connection: close\r\n\r\n");

            OutputStream out = socket.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();

            String rawResp = readSocketText(socket);
            String body = decodeHttpResponseBody(rawResp);
            JSONObject json = parseFirstJsonObject(body);
            if (json != null && "success".equalsIgnoreCase(json.optString("status"))) {
                return GeoInfo.fromIpApi(json);
            }
        } catch (Exception ignored) {
        }
        return new GeoInfo();
    }

    private static String fetchProxyHttpsIp(ProxyConfig proxy, String host, String path, int timeoutMs) {
        String body = executeHttpsGetViaProxy(proxy, host, path, timeoutMs);
        JSONObject json = parseFirstJsonObject(body);
        if (json != null) {
            return json.optString("ip", "").trim();
        }
        return extractFirstLineIp(body);
    }

    private static String executeHttpsGetViaProxy(ProxyConfig proxy, String targetHost, String path, int timeoutMs) {
        try (Socket tunnel = UpstreamProxyConnector.openTunnel(proxy, targetHost, 443, timeoutMs)) {
            tunnel.setSoTimeout(timeoutMs);
            SSLSocketFactory sslFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            try (SSLSocket sslSocket = (SSLSocket) sslFactory.createSocket(tunnel, targetHost, 443, true)) {
                sslSocket.setSoTimeout(timeoutMs);
                sslSocket.startHandshake();

                String httpReq = "GET " + path + " HTTP/1.1\r\n"
                        + "Host: " + targetHost + "\r\n"
                        + "User-Agent: ProxyFlow/1.1\r\n"
                        + "Accept: application/json, text/plain\r\n"
                        + "Accept-Encoding: identity\r\n"
                        + "Connection: close\r\n\r\n";
                OutputStream sslOut = sslSocket.getOutputStream();
                sslOut.write(httpReq.getBytes(StandardCharsets.UTF_8));
                sslOut.flush();

                String rawResp = readSocketText(sslSocket);
                return decodeHttpResponseBody(rawResp);
            }
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * Merges a primary GeoInfo with a secondary provider so missing fields (country name,
     * region/province, city, ISP, or coordinates) are filled accurately without overwriting
     * already resolved values.
     */
    static GeoInfo mergeGeoInfo(GeoInfo primary, GeoInfo secondary) {
        if (primary == null && secondary == null) return new GeoInfo();
        if (primary == null || !primary.hasGeo) return secondary != null ? secondary : new GeoInfo();
        if (secondary == null || !secondary.hasGeo) return primary;

        GeoInfo out = new GeoInfo();
        out.queryIp = !primary.queryIp.isEmpty() ? primary.queryIp : secondary.queryIp;
        out.region = !primary.region.isEmpty() ? primary.region : secondary.region;
        out.city = !primary.city.isEmpty() ? primary.city : secondary.city;
        out.country = !primary.country.isEmpty() ? primary.country : secondary.country;
        out.countryCode = !primary.countryCode.isEmpty() ? primary.countryCode : secondary.countryCode;
        out.isp = !primary.isp.isEmpty() ? primary.isp : secondary.isp;
        if (primary.hasCoords) {
            out.lat = primary.lat;
            out.lon = primary.lon;
            out.hasCoords = true;
        } else if (secondary.hasCoords) {
            out.lat = secondary.lat;
            out.lon = secondary.lon;
            out.hasCoords = true;
        }
        out.hasGeo = !out.country.isEmpty() || !out.region.isEmpty() || !out.city.isEmpty() || out.hasCoords;
        return out;
    }

    public static String decodeHttpResponseBody(String rawResponse) {
        if (rawResponse == null || rawResponse.isEmpty()) return "";
        int sep = rawResponse.indexOf("\r\n\r\n");
        int sepLen = 4;
        if (sep < 0) {
            sep = rawResponse.indexOf("\n\n");
            sepLen = 2;
        }
        if (sep < 0) {
            return rawResponse.trim();
        }
        String headers = rawResponse.substring(0, sep).toLowerCase(Locale.ROOT);
        String body = rawResponse.substring(sep + sepLen);
        if (!headers.contains("transfer-encoding: chunked")) {
            return body.trim();
        }
        // Decode HTTP/1.1 chunked encoding
        StringBuilder decoded = new StringBuilder();
        int pos = 0;
        while (pos < body.length()) {
            int lineEnd = body.indexOf("\r\n", pos);
            int nlLen = 2;
            if (lineEnd < 0) {
                lineEnd = body.indexOf('\n', pos);
                nlLen = 1;
            }
            if (lineEnd < 0) break;
            String sizeToken = body.substring(pos, lineEnd).trim();
            int semi = sizeToken.indexOf(';');
            if (semi >= 0) sizeToken = sizeToken.substring(0, semi).trim();
            int chunkSize;
            try {
                chunkSize = Integer.parseInt(sizeToken, 16);
            } catch (NumberFormatException e) {
                // Not valid chunk header; return raw body as-is
                return body.trim();
            }
            pos = lineEnd + nlLen;
            if (chunkSize == 0) break;
            int end = Math.min(body.length(), pos + chunkSize);
            decoded.append(body, pos, end);
            pos = end;
            if (pos + 1 < body.length() && body.charAt(pos) == '\r' && body.charAt(pos + 1) == '\n') {
                pos += 2;
            } else if (pos < body.length() && body.charAt(pos) == '\n') {
                pos += 1;
            }
        }
        return decoded.toString().trim();
    }

    public static IpDetails parseIpInfoToDetails(JSONObject json, boolean routedViaProxy) {
        GeoInfo g = GeoInfo.fromIpInfo(json);
        return new IpDetails(g.queryIp, "", g.region, g.city, g.country, g.countryCode, g.isp,
                g.lat, g.lon, g.hasCoords, routedViaProxy, "");
    }

    public static IpDetails parseIpWhoIsToDetails(JSONObject json, boolean routedViaProxy) {
        GeoInfo g = GeoInfo.fromIpWhoIs(json);
        return new IpDetails(g.queryIp, "", g.region, g.city, g.country, g.countryCode, g.isp,
                g.lat, g.lon, g.hasCoords, routedViaProxy, "");
    }

    private static String extractFirstLineIp(String text) {
        if (text == null || text.isEmpty()) return "";
        String[] lines = text.trim().split("\\r?\\n");
        for (String line : lines) {
            String candidate = line.trim();
            if (isValidIpv4(candidate)) {
                return candidate;
            }
        }
        return "";
    }

    public static boolean isValidIpv4(String ip) {
        return ip != null && IPV4_PATTERN.matcher(ip.trim()).matches();
    }

    private static String readStream(HttpURLConnection conn) throws IOException {
        try (InputStream is = conn.getInputStream()) {
            return readInputStreamLimited(is);
        }
    }

    private static String readSocketText(Socket socket) throws IOException {
        return readInputStreamLimited(socket.getInputStream());
    }

    private static String readInputStreamLimited(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[2048];
        int total = 0;
        int read;
        while ((read = is.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
            total += read;
            if (total >= 32768) break;
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    public static JSONObject parseFirstJsonObject(String text) {
        if (text == null || text.isEmpty()) return null;
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                return new JSONObject(text.substring(start, end + 1));
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static String cleanOrgName(String org) {
        if (org == null) return "";
        Matcher m = AS_PREFIX_PATTERN.matcher(org.trim());
        return m.replaceFirst("").trim();
    }

    private static String resolveCountryNameFromCode(String code) {
        if (code == null || code.trim().isEmpty()) return "";
        try {
            Locale loc = new Locale("", code.trim().toUpperCase(Locale.ROOT));
            String display = loc.getDisplayCountry(Locale.getDefault());
            return display != null && !display.isEmpty() ? display : code;
        } catch (Exception e) {
            return code;
        }
    }

    private static IpDetails safeGet(Future<IpDetails> f, IpDetails fallback) {
        try {
            return f.get();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String safeString(Future<String> f) {
        try {
            String s = f.get();
            return s != null ? s.trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static GeoInfo safeGeo(Future<GeoInfo> f) {
        try {
            GeoInfo g = f.get();
            return g != null ? g : new GeoInfo();
        } catch (Exception e) {
            return new GeoInfo();
        }
    }

    static class GeoInfo {
        String queryIp = "";
        String region = "";
        String city = "";
        String country = "";
        String countryCode = "";
        String isp = "";
        double lat = 0.0;
        double lon = 0.0;
        boolean hasCoords = false;
        boolean hasGeo = false;

        boolean hasCompleteGeo() {
            return hasGeo && !region.isEmpty() && !city.isEmpty() && !country.isEmpty() && hasCoords;
        }

        static GeoInfo fromIpInfo(JSONObject json) {
            GeoInfo g = new GeoInfo();
            if (json == null) return g;
            g.queryIp = json.optString("ip", "").trim();
            g.region = json.optString("region", "").trim();
            g.city = json.optString("city", "").trim();
            g.countryCode = json.optString("country", "").trim();
            g.country = resolveCountryNameFromCode(g.countryCode);
            g.isp = cleanOrgName(json.optString("org", ""));
            String loc = json.optString("loc", "").trim();
            if (!loc.isEmpty() && loc.contains(",")) {
                String[] parts = loc.split(",");
                if (parts.length == 2) {
                    try {
                        g.lat = Double.parseDouble(parts[0].trim());
                        g.lon = Double.parseDouble(parts[1].trim());
                        g.hasCoords = (g.lat != 0.0 || g.lon != 0.0);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            g.hasGeo = !g.countryCode.isEmpty() || !g.region.isEmpty() || !g.city.isEmpty() || g.hasCoords;
            return g;
        }

        static GeoInfo fromIpApi(JSONObject json) {
            GeoInfo g = new GeoInfo();
            if (json == null) return g;
            g.queryIp = json.optString("query", "").trim();
            g.region = json.optString("regionName", "").trim();
            g.city = json.optString("city", "").trim();
            g.country = json.optString("country", "").trim();
            g.countryCode = json.optString("countryCode", "").trim();
            String ispVal = json.optString("isp", "").trim();
            String orgVal = json.optString("org", "").trim();
            g.isp = !ispVal.isEmpty() ? ispVal : cleanOrgName(orgVal);
            if (json.has("lat") && json.has("lon")) {
                g.lat = json.optDouble("lat", 0.0);
                g.lon = json.optDouble("lon", 0.0);
                g.hasCoords = (g.lat != 0.0 || g.lon != 0.0);
            }
            g.hasGeo = !g.country.isEmpty() || !g.region.isEmpty() || !g.city.isEmpty() || g.hasCoords;
            return g;
        }

        static GeoInfo fromIpWhoIs(JSONObject json) {
            GeoInfo g = new GeoInfo();
            if (json == null) return g;
            g.queryIp = json.optString("ip", "").trim();
            g.region = json.optString("region", "").trim();
            g.city = json.optString("city", "").trim();
            g.country = json.optString("country", "").trim();
            g.countryCode = json.optString("country_code", "").trim();
            JSONObject conn = json.optJSONObject("connection");
            if (conn != null) {
                g.isp = conn.optString("isp", conn.optString("org", "")).trim();
            }
            if (json.has("latitude") && json.has("longitude")) {
                g.lat = json.optDouble("latitude", 0.0);
                g.lon = json.optDouble("longitude", 0.0);
                g.hasCoords = (g.lat != 0.0 || g.lon != 0.0);
            }
            g.hasGeo = !g.country.isEmpty() || !g.region.isEmpty() || !g.city.isEmpty() || g.hasCoords;
            return g;
        }
    }
}
