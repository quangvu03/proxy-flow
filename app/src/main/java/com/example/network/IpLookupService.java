package com.example.network;

import com.example.model.IpDetails;
import com.example.model.ProxyConfig;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Pure Java network inspector resolving both:
 * 1) Direct/Original device IPv4, IPv6, and geolocation (bypassing VPN via app disallow list).
 * 2) Active Proxy exit IPv4, IPv6, and geolocation (tunneled through upstream proxy with Basic auth).
 */
public final class IpLookupService {

    private static final int TIMEOUT_MS = 7000;
    private static final int IPV6_TIMEOUT_MS = 4500;

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
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<String> v4Future = pool.submit((Callable<String>) () -> fetchDirectIpFromIpify("https://api4.ipify.org?format=json"));
            Future<String> v6Future = pool.submit((Callable<String>) () -> fetchDirectIpFromIpify("https://api6.ipify.org?format=json"));
            Future<GeoInfo> geoFuture = pool.submit((Callable<GeoInfo>) () -> fetchDirectGeo(""));

            String ipv4 = safeString(v4Future);
            String ipv6 = safeString(v6Future);
            GeoInfo geo = safeGeo(geoFuture);

            if (ipv4.isEmpty() && !geo.queryIp.isEmpty() && !geo.queryIp.contains(":")) {
                ipv4 = geo.queryIp;
            }
            if (!geo.hasGeo && !ipv4.isEmpty()) {
                geo = fetchDirectGeo(ipv4);
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
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<GeoInfo> geoFuture = pool.submit(() -> fetchProxyGeo(proxy));
            Future<String> v4Future = pool.submit(() -> fetchProxyHttpsIp(proxy, "api4.ipify.org", TIMEOUT_MS));
            Future<String> v6Future = pool.submit(() -> fetchProxyHttpsIp(proxy, "api6.ipify.org", IPV6_TIMEOUT_MS));

            GeoInfo geo = safeGeo(geoFuture);
            String ipv4 = safeString(v4Future);
            String ipv6 = safeString(v6Future);

            if (ipv4.isEmpty() && !geo.queryIp.isEmpty() && !geo.queryIp.contains(":")) {
                ipv4 = geo.queryIp;
            }
            if (!geo.hasGeo && !ipv4.isEmpty()) {
                GeoInfo fallbackGeo = fetchProxyHttpsGeoIpWhoIs(proxy, ipv4);
                if (fallbackGeo.hasGeo) {
                    geo = fallbackGeo;
                }
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

    private static String fetchDirectIpFromIpify(String endpoint) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(endpoint);
            conn = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
            conn.setConnectTimeout(IPV6_TIMEOUT_MS);
            conn.setReadTimeout(IPV6_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "ProxyFlow/1.0");
            if (conn.getResponseCode() == 200) {
                String body = readStream(conn);
                JSONObject json = parseFirstJsonObject(body);
                if (json != null) {
                    return json.optString("ip", "");
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return "";
    }

    private static GeoInfo fetchDirectGeo(String targetIp) {
        // Primary: ip-api.com (Vietnamese localization supported)
        String suffix = (targetIp != null && !targetIp.isEmpty()) ? targetIp : "";
        String urlStr = "http://ip-api.com/json/" + suffix
                + "?lang=vi&fields=status,country,countryCode,regionName,city,isp,org,lat,lon,query";
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection(Proxy.NO_PROXY);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
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

        // Fallback: ipwho.is
        String fallbackUrl = "https://ipwho.is/" + suffix;
        try {
            conn = (HttpURLConnection) new URL(fallbackUrl).openConnection(Proxy.NO_PROXY);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
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

    private static GeoInfo fetchProxyGeo(ProxyConfig proxy) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(proxy.getHost(), proxy.getPort()), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);

            StringBuilder req = new StringBuilder();
            req.append("GET http://ip-api.com/json/?lang=vi&fields=status,country,countryCode,regionName,city,isp,org,lat,lon,query HTTP/1.0\r\n");
            req.append("Host: ip-api.com\r\n");
            req.append("User-Agent: ProxyFlow/1.0\r\n");
            if (proxy.hasAuth()) {
                req.append("Proxy-Authorization: Basic ").append(proxy.getBasicAuthToken()).append("\r\n");
            }
            req.append("Connection: close\r\n\r\n");

            OutputStream out = socket.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();

            String resp = readSocketText(socket);
            JSONObject json = parseFirstJsonObject(resp);
            if (json != null && "success".equalsIgnoreCase(json.optString("status"))) {
                return GeoInfo.fromIpApi(json);
            }
        } catch (Exception ignored) {
        }
        return fetchProxyHttpsGeoIpWhoIs(proxy, "");
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

    private static String fetchProxyHttpsIp(ProxyConfig proxy, String host, int timeoutMs) {
        String body = executeHttpsGetViaProxy(proxy, host, "/?format=json", timeoutMs);
        JSONObject json = parseFirstJsonObject(body);
        return json != null ? json.optString("ip", "") : "";
    }

    private static String executeHttpsGetViaProxy(ProxyConfig proxy, String targetHost, String path, int timeoutMs) {
        try (Socket tunnel = new Socket()) {
            tunnel.connect(new InetSocketAddress(proxy.getHost(), proxy.getPort()), timeoutMs);
            tunnel.setSoTimeout(timeoutMs);

            StringBuilder connectReq = new StringBuilder();
            connectReq.append("CONNECT ").append(targetHost).append(":443 HTTP/1.1\r\n");
            connectReq.append("Host: ").append(targetHost).append(":443\r\n");
            if (proxy.hasAuth()) {
                connectReq.append("Proxy-Authorization: Basic ").append(proxy.getBasicAuthToken()).append("\r\n");
            }
            connectReq.append("\r\n");

            OutputStream rawOut = tunnel.getOutputStream();
            rawOut.write(connectReq.toString().getBytes(StandardCharsets.UTF_8));
            rawOut.flush();

            BufferedReader headerReader = new BufferedReader(
                    new InputStreamReader(tunnel.getInputStream(), StandardCharsets.ISO_8859_1));
            String statusLine = headerReader.readLine();
            if (statusLine == null || ProxyTester.parseHttpStatusCode(statusLine) < 200
                    || ProxyTester.parseHttpStatusCode(statusLine) >= 300) {
                return "";
            }
            String line;
            while ((line = headerReader.readLine()) != null && !line.isEmpty()) {
                // Consume proxy CONNECT response headers
            }

            SSLSocketFactory sslFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            try (SSLSocket sslSocket = (SSLSocket) sslFactory.createSocket(tunnel, targetHost, 443, true)) {
                sslSocket.setSoTimeout(timeoutMs);
                sslSocket.startHandshake();

                String httpReq = "GET " + path + " HTTP/1.0\r\n"
                        + "Host: " + targetHost + "\r\n"
                        + "User-Agent: ProxyFlow/1.0\r\n"
                        + "Connection: close\r\n\r\n";
                OutputStream sslOut = sslSocket.getOutputStream();
                sslOut.write(httpReq.getBytes(StandardCharsets.UTF_8));
                sslOut.flush();

                return readSocketText(sslSocket);
            }
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String readStream(HttpURLConnection conn) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
                if (sb.length() > 16384) break;
            }
            return sb.toString();
        }
    }

    private static String readSocketText(Socket socket) throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append('\n');
            if (sb.length() > 16384) break;
        }
        return sb.toString();
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

    private static class GeoInfo {
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

        static GeoInfo fromIpApi(JSONObject json) {
            GeoInfo g = new GeoInfo();
            g.queryIp = json.optString("query", "");
            g.region = json.optString("regionName", "");
            g.city = json.optString("city", "");
            g.country = json.optString("country", "");
            g.countryCode = json.optString("countryCode", "");
            String ispVal = json.optString("isp", "");
            String orgVal = json.optString("org", "");
            g.isp = !ispVal.isEmpty() ? ispVal : orgVal;
            if (json.has("lat") && json.has("lon")) {
                g.lat = json.optDouble("lat", 0.0);
                g.lon = json.optDouble("lon", 0.0);
                g.hasCoords = (g.lat != 0.0 || g.lon != 0.0);
            }
            g.hasGeo = !g.country.isEmpty() || !g.city.isEmpty() || g.hasCoords;
            return g;
        }

        static GeoInfo fromIpWhoIs(JSONObject json) {
            GeoInfo g = new GeoInfo();
            g.queryIp = json.optString("ip", "");
            g.region = json.optString("region", "");
            g.city = json.optString("city", "");
            g.country = json.optString("country", "");
            g.countryCode = json.optString("country_code", "");
            JSONObject conn = json.optJSONObject("connection");
            if (conn != null) {
                g.isp = conn.optString("isp", conn.optString("org", ""));
            }
            if (json.has("latitude") && json.has("longitude")) {
                g.lat = json.optDouble("latitude", 0.0);
                g.lon = json.optDouble("longitude", 0.0);
                g.hasCoords = (g.lat != 0.0 || g.lon != 0.0);
            }
            g.hasGeo = !g.country.isEmpty() || !g.city.isEmpty() || g.hasCoords;
            return g;
        }
    }
}
