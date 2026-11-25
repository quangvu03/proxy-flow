package com.example.network;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.model.ProxyConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure Java HTTP proxy authentication engine supporting:
 * - Basic (RFC 7617, UTF-8)
 * - Digest (RFC 2617 / RFC 7616: MD5, MD5-sess, SHA-256, SHA-256-sess, qop=auth, legacy no-qop, opaque)
 * - Bearer / Token (RFC 6750)
 * - Auto-negotiation in AuthMethod.USER_PASS mode (Basic preemptive + Digest 407 challenge retry)
 */
public final class ProxyAuthHelper {

    private static final SecureRandom RANDOM = new SecureRandom();

    private ProxyAuthHelper() {}

    public static final class HttpResponseHead {
        private final byte[] rawBytes;
        private final String statusLine;
        private final int statusCode;
        private final List<String> proxyAuthenticateHeaders;
        private final boolean connectionClose;
        private final long contentLength;
        private final boolean chunked;

        HttpResponseHead(byte[] rawBytes, String statusLine, int statusCode,
                         List<String> proxyAuthenticateHeaders, boolean connectionClose,
                         long contentLength, boolean chunked) {
            this.rawBytes = rawBytes;
            this.statusLine = statusLine != null ? statusLine : "";
            this.statusCode = statusCode;
            this.proxyAuthenticateHeaders = proxyAuthenticateHeaders;
            this.connectionClose = connectionClose;
            this.contentLength = contentLength;
            this.chunked = chunked;
        }

        public byte[] getRawBytes() { return rawBytes; }
        public String getStatusLine() { return statusLine; }
        public int getStatusCode() { return statusCode; }
        public List<String> getProxyAuthenticateHeaders() { return proxyAuthenticateHeaders; }
        public boolean isConnectionClose() { return connectionClose; }
        public long getContentLength() { return contentLength; }
        public boolean isChunked() { return chunked; }
    }

    public static final class TunnelAttemptResult {
        public final Socket socket;
        public final HttpResponseHead responseHead;

        TunnelAttemptResult(Socket socket, HttpResponseHead responseHead) {
            this.socket = socket;
            this.responseHead = responseHead;
        }
    }

    @Nullable
    public static String buildInitialProxyAuthHeaderValue(@Nullable ProxyConfig proxy) {
        if (proxy == null || !proxy.hasAuth()) {
            return null;
        }
        switch (proxy.getAuthMethod()) {
            case BEARER_TOKEN:
                String token = proxy.getAuthToken();
                return !token.isEmpty() ? ("Bearer " + token) : null;
            case HTTP_DIGEST:
                // Digest always requires server nonce challenge first
                return null;
            case HTTP_BASIC:
            case USER_PASS:
            default:
                String b64 = proxy.getBasicAuthToken();
                return !b64.isEmpty() ? ("Basic " + b64) : null;
        }
    }

    @Nullable
    public static String buildChallengeResponseHeaderValue(
            @Nullable ProxyConfig proxy,
            @Nullable List<String> proxyAuthenticateHeaders,
            @NonNull String httpMethod,
            @NonNull String requestUri,
            @Nullable String alreadyAttemptedHeader) {
        if (proxy == null || !proxy.hasAuth()) {
            return null;
        }
        ProxyConfig.AuthMethod mode = proxy.getAuthMethod();
        List<AuthChallenge> challenges = parseProxyAuthenticateHeaders(proxyAuthenticateHeaders);

        boolean triedBasic = alreadyAttemptedHeader != null
                && alreadyAttemptedHeader.toLowerCase(Locale.ROOT).startsWith("basic ");
        boolean triedDigest = alreadyAttemptedHeader != null
                && alreadyAttemptedHeader.toLowerCase(Locale.ROOT).startsWith("digest ");
        boolean triedBearer = alreadyAttemptedHeader != null
                && alreadyAttemptedHeader.toLowerCase(Locale.ROOT).startsWith("bearer ");

        if (mode == ProxyConfig.AuthMethod.BEARER_TOKEN) {
            if (triedBearer) {
                return null;
            }
            String token = proxy.getAuthToken();
            return !token.isEmpty() ? ("Bearer " + token) : null;
        }

        if (mode == ProxyConfig.AuthMethod.HTTP_DIGEST || mode == ProxyConfig.AuthMethod.USER_PASS) {
            AuthChallenge digest = findChallenge(challenges, "digest");
            if (digest != null && (!triedDigest || digest.isStale())) {
                String computed = computeDigestHeader(
                        proxy.getUsername(),
                        proxy.getPassword(),
                        httpMethod,
                        requestUri,
                        digest.params
                );
                if (computed != null) {
                    return computed;
                }
            }
        }

        if (mode == ProxyConfig.AuthMethod.HTTP_BASIC || mode == ProxyConfig.AuthMethod.USER_PASS) {
            AuthChallenge basic = findChallenge(challenges, "basic");
            if ((basic != null || challenges.isEmpty()) && !triedBasic) {
                String b64 = proxy.getBasicAuthToken();
                if (!b64.isEmpty()) {
                    return "Basic " + b64;
                }
            }
        }

        return null;
    }

    @NonNull
    public static String describe407Failure(@Nullable ProxyConfig proxy,
                                            @Nullable List<String> proxyAuthenticateHeaders) {
        if (proxy == null || !proxy.hasAuth()) {
            String schemes = formatOfferedSchemes(proxyAuthenticateHeaders);
            if (!schemes.isEmpty()) {
                return "Proxy yêu cầu xác thực " + schemes + " (vui lòng nhập tài khoản / mật khẩu)";
            }
            return "Proxy yêu cầu xác thực (vui lòng nhập tài khoản / mật khẩu)";
        }

        List<AuthChallenge> challenges = parseProxyAuthenticateHeaders(proxyAuthenticateHeaders);
        ProxyConfig.AuthMethod mode = proxy.getAuthMethod();
        if (mode == ProxyConfig.AuthMethod.HTTP_BASIC && findChallenge(challenges, "basic") == null
                && findChallenge(challenges, "digest") != null) {
            return "Lỗi 407: Proxy yêu cầu xác thực HTTP Digest (hãy đổi sang Tự động hoặc HTTP Digest)";
        }
        if (mode == ProxyConfig.AuthMethod.HTTP_DIGEST && findChallenge(challenges, "digest") == null
                && findChallenge(challenges, "basic") != null) {
            return "Lỗi 407: Proxy yêu cầu xác thực HTTP Basic (hãy đổi sang Tự động hoặc HTTP Basic)";
        }
        if (mode == ProxyConfig.AuthMethod.BEARER_TOKEN) {
            return "Lỗi 407: Token xác thực Proxy không hợp lệ hoặc đã hết hạn";
        }
        return "Lỗi 407: Sai tài khoản hoặc mật khẩu Proxy";
    }

    @Nullable
    public static HttpResponseHead readHttpResponseHead(@NonNull InputStream in) throws IOException {
        byte[] raw = readHttpHeaderBytes(in);
        if (raw == null || raw.length == 0) {
            return null;
        }
        String text = new String(raw, StandardCharsets.ISO_8859_1);
        String[] lines = text.split("\r\n|\n");
        if (lines.length == 0) {
            return null;
        }
        String statusLine = lines[0].trim();
        int statusCode = ProxyTester.parseHttpStatusCode(statusLine);
        List<String> authHeaders = new ArrayList<>();
        boolean connClose = false;
        long contentLength = -1L;
        boolean chunked = false;

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line == null || line.isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String val = line.substring(colon + 1).trim();
            if ("proxy-authenticate".equals(name)) {
                authHeaders.add(val);
            } else if ("connection".equals(name) || "proxy-connection".equals(name)) {
                if (val.toLowerCase(Locale.ROOT).contains("close")) {
                    connClose = true;
                }
            } else if ("content-length".equals(name)) {
                try {
                    contentLength = Long.parseLong(val);
                } catch (NumberFormatException ignored) {
                }
            } else if ("transfer-encoding".equals(name)) {
                if (val.toLowerCase(Locale.ROOT).contains("chunked")) {
                    chunked = true;
                }
            }
        }
        return new HttpResponseHead(raw, statusLine, statusCode, authHeaders, connClose, contentLength, chunked);
    }

    public static boolean drainResponseBodyForSocketReuse(@NonNull InputStream in,
                                                          @NonNull HttpResponseHead head) {
        if (head.isConnectionClose() || head.isChunked()) {
            return false;
        }
        if (head.getStatusCode() == 204 || head.getStatusCode() == 304) {
            return true;
        }
        long len = head.getContentLength();
        if (len == 0L) {
            return true;
        }
        if (len < 0L || len > 65536L) {
            return false;
        }
        try {
            byte[] buf = new byte[4096];
            long remaining = len;
            while (remaining > 0) {
                int toRead = (int) Math.min(buf.length, remaining);
                int read = in.read(buf, 0, toRead);
                if (read == -1) {
                    return false;
                }
                remaining -= read;
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static TunnelAttemptResult sendHttpConnectWithAuthRetry(
            @NonNull ProxyConfig proxy,
            @NonNull String targetHost,
            int targetPort,
            int timeoutMs) throws IOException {
        String authority = targetHost + ":" + targetPort;
        String initialAuth = buildInitialProxyAuthHeaderValue(proxy);

        Socket upstream = UpstreamProxyConnector.openRawUpstreamSocket(proxy, timeoutMs);
        try {
            upstream.setSoTimeout(timeoutMs);
            writeConnectRequest(upstream.getOutputStream(), authority, initialAuth);
            HttpResponseHead head = readHttpResponseHead(upstream.getInputStream());
            if (head == null) {
                throw new IOException("Proxy closed connection during CONNECT");
            }

            if (head.getStatusCode() == 407 && proxy.hasAuth()) {
                String retryAuth = buildChallengeResponseHeaderValue(
                        proxy, head.getProxyAuthenticateHeaders(), "CONNECT", authority, initialAuth);
                if (retryAuth != null) {
                    boolean canReuse = drainResponseBodyForSocketReuse(upstream.getInputStream(), head);
                    if (!canReuse) {
                        closeQuietly(upstream);
                        upstream = UpstreamProxyConnector.openRawUpstreamSocket(proxy, timeoutMs);
                        upstream.setSoTimeout(timeoutMs);
                    }
                    HttpResponseHead retryHead = null;
                    try {
                        writeConnectRequest(upstream.getOutputStream(), authority, retryAuth);
                        retryHead = readHttpResponseHead(upstream.getInputStream());
                    } catch (IOException ioEx) {
                        if (!canReuse) throw ioEx;
                    }
                    if (retryHead == null && canReuse) {
                        closeQuietly(upstream);
                        upstream = UpstreamProxyConnector.openRawUpstreamSocket(proxy, timeoutMs);
                        upstream.setSoTimeout(timeoutMs);
                        writeConnectRequest(upstream.getOutputStream(), authority, retryAuth);
                        retryHead = readHttpResponseHead(upstream.getInputStream());
                    }
                    if (retryHead == null) {
                        throw new IOException("Proxy closed connection during authenticated CONNECT");
                    }
                    head = retryHead;
                }
            }

            if (head.getStatusCode() == 407) {
                if (!proxy.hasAuth()) {
                    throw new UpstreamProxyConnector.ProxyAuthRequiredException(
                            describe407Failure(proxy, head.getProxyAuthenticateHeaders()));
                }
                throw new UpstreamProxyConnector.ProxyAuthException(
                        describe407Failure(proxy, head.getProxyAuthenticateHeaders()));
            }

            return new TunnelAttemptResult(upstream, head);
        } catch (IOException e) {
            closeQuietly(upstream);
            throw e;
        }
    }

    private static void writeConnectRequest(OutputStream out, String authority, @Nullable String proxyAuthValue)
            throws IOException {
        StringBuilder req = new StringBuilder();
        req.append("CONNECT ").append(authority).append(" HTTP/1.1\r\n");
        req.append("Host: ").append(authority).append("\r\n");
        if (proxyAuthValue != null && !proxyAuthValue.isEmpty()) {
            req.append("Proxy-Authorization: ").append(proxyAuthValue).append("\r\n");
        }
        req.append("Proxy-Connection: Keep-Alive\r\n\r\n");
        out.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    @Nullable
    public static String computeDigestHeader(
            @Nullable String username,
            @Nullable String password,
            @NonNull String method,
            @NonNull String uri,
            @NonNull Map<String, String> params) {
        String realm = params.get("realm");
        String nonce = params.get("nonce");
        if (realm == null || nonce == null) {
            return null;
        }
        String user = username != null ? username : "";
        String pass = password != null ? password : "";
        String rawAlgorithm = params.getOrDefault("algorithm", "MD5");
        String algNorm = rawAlgorithm.trim().toUpperCase(Locale.ROOT);
        boolean isSess = algNorm.endsWith("-SESS");
        String jcaAlg;
        if (algNorm.startsWith("SHA-256")) {
            jcaAlg = "SHA-256";
        } else if (algNorm.startsWith("SHA-512")) {
            jcaAlg = "SHA-512";
        } else {
            jcaAlg = "MD5";
            rawAlgorithm = isSess ? "MD5-sess" : "MD5";
        }

        String qopRaw = params.get("qop");
        String selectedQop = null;
        if (qopRaw != null && !qopRaw.trim().isEmpty()) {
            for (String token : qopRaw.split(",")) {
                if ("auth".equalsIgnoreCase(token.trim())) {
                    selectedQop = "auth";
                    break;
                }
            }
            if (selectedQop == null) {
                // Fallback to auth if unknown qop tokens are offered
                selectedQop = "auth";
            }
        }

        String cnonce = generateCnonceHex();
        String nc = "00000001";

        String a1Base = user + ":" + realm + ":" + pass;
        String ha1 = hashHex(jcaAlg, a1Base);
        if (ha1 == null) {
            return null;
        }
        if (isSess) {
            ha1 = hashHex(jcaAlg, ha1 + ":" + nonce + ":" + cnonce);
            if (ha1 == null) return null;
        }

        String ha2 = hashHex(jcaAlg, method.toUpperCase(Locale.ROOT) + ":" + uri);
        if (ha2 == null) {
            return null;
        }

        String response;
        if (selectedQop != null) {
            response = hashHex(jcaAlg, ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + selectedQop + ":" + ha2);
        } else {
            response = hashHex(jcaAlg, ha1 + ":" + nonce + ":" + ha2);
        }
        if (response == null) {
            return null;
        }

        StringBuilder header = new StringBuilder();
        header.append("Digest ");
        header.append("username=\"").append(escapeQuoted(user)).append("\"");
        header.append(", realm=\"").append(escapeQuoted(realm)).append("\"");
        header.append(", nonce=\"").append(escapeQuoted(nonce)).append("\"");
        header.append(", uri=\"").append(escapeQuoted(uri)).append("\"");
        header.append(", response=\"").append(response).append("\"");
        if (params.containsKey("algorithm")) {
            header.append(", algorithm=").append(rawAlgorithm);
        }
        if (selectedQop != null) {
            header.append(", qop=").append(selectedQop);
            header.append(", nc=").append(nc);
            header.append(", cnonce=\"").append(cnonce).append("\"");
        }
        String opaque = params.get("opaque");
        if (opaque != null && !opaque.isEmpty()) {
            header.append(", opaque=\"").append(escapeQuoted(opaque)).append("\"");
        }
        return header.toString();
    }

    static List<AuthChallenge> parseProxyAuthenticateHeaders(@Nullable List<String> headers) {
        List<AuthChallenge> result = new ArrayList<>();
        if (headers == null) return result;
        for (String h : headers) {
            if (h == null || h.trim().isEmpty()) continue;
            parseHeaderChallengesInto(h.trim(), result);
        }
        return result;
    }

    private static void parseHeaderChallengesInto(String header, List<AuthChallenge> out) {
        int len = header.length();
        int i = 0;
        while (i < len) {
            while (i < len && (header.charAt(i) == ' ' || header.charAt(i) == ',')) {
                i++;
            }
            if (i >= len) break;

            int schemeStart = i;
            while (i < len && header.charAt(i) != ' ' && header.charAt(i) != '\t' && header.charAt(i) != ',') {
                i++;
            }
            String scheme = header.substring(schemeStart, i).trim();
            if (scheme.isEmpty()) break;

            Map<String, String> params = new LinkedHashMap<>();
            while (i < len) {
                while (i < len && (header.charAt(i) == ' ' || header.charAt(i) == '\t' || header.charAt(i) == ',')) {
                    i++;
                }
                if (i >= len) break;

                // Peek ahead: if next token before '=' or space looks like a new scheme (no '=')
                int probe = i;
                while (probe < len && header.charAt(probe) != '=' && header.charAt(probe) != ' ' && header.charAt(probe) != ',') {
                    probe++;
                }
                if (probe >= len || header.charAt(probe) != '=') {
                    // Token68 (e.g. Bearer) or start of next auth scheme
                    break;
                }

                String key = header.substring(i, probe).trim().toLowerCase(Locale.ROOT);
                i = probe + 1;
                while (i < len && (header.charAt(i) == ' ' || header.charAt(i) == '\t')) {
                    i++;
                }
                String value;
                if (i < len && header.charAt(i) == '"') {
                    i++; // skip opening quote
                    StringBuilder sb = new StringBuilder();
                    while (i < len) {
                        char c = header.charAt(i++);
                        if (c == '\\' && i < len) {
                            sb.append(header.charAt(i++));
                        } else if (c == '"') {
                            break;
                        } else {
                            sb.append(c);
                        }
                    }
                    value = sb.toString();
                } else {
                    int valStart = i;
                    while (i < len && header.charAt(i) != ',') {
                        i++;
                    }
                    value = header.substring(valStart, i).trim();
                }
                params.put(key, value);
            }
            out.add(new AuthChallenge(scheme, params));
        }
    }

    @Nullable
    private static AuthChallenge findChallenge(List<AuthChallenge> challenges, String schemeName) {
        for (AuthChallenge c : challenges) {
            if (schemeName.equalsIgnoreCase(c.scheme)) {
                return c;
            }
        }
        return null;
    }

    private static String formatOfferedSchemes(@Nullable List<String> headers) {
        List<AuthChallenge> challenges = parseProxyAuthenticateHeaders(headers);
        if (challenges.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (AuthChallenge c : challenges) {
            if (sb.length() > 0) sb.append('/');
            sb.append(c.scheme);
        }
        return sb.toString();
    }

    private static byte[] readHttpHeaderBytes(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
        int state = 0;
        int lfOnlyState = 0;
        int b;
        int total = 0;
        while (total < 32768 && (b = in.read()) != -1) {
            baos.write(b);
            total++;
            if (state == 0 && b == '\r') state = 1;
            else if (state == 1 && b == '\n') state = 2;
            else if (state == 2 && b == '\r') state = 3;
            else if (state == 3 && b == '\n') break;
            else state = (b == '\r') ? 1 : 0;

            if (b == '\n') {
                lfOnlyState++;
                if (lfOnlyState == 2) break;
            } else if (b != '\r') {
                lfOnlyState = 0;
            }
        }
        return baos.toByteArray();
    }

    @Nullable
    private static String hashHex(String jcaAlg, String input) {
        try {
            MessageDigest md = MessageDigest.getInstance(jcaAlg);
            byte[] digest = md.digest(input.getBytes(StandardCharsets.ISO_8859_1));
            return toHexLower(digest);
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    private static String generateCnonceHex() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return toHexLower(bytes);
    }

    private static String toHexLower(byte[] bytes) {
        final char[] hex = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = hex[v >>> 4];
            out[i * 2 + 1] = hex[v & 0x0F];
        }
        return new String(out);
    }

    private static String escapeQuoted(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void closeQuietly(@Nullable Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    static final class AuthChallenge {
        final String scheme;
        final Map<String, String> params;

        AuthChallenge(String scheme, Map<String, String> params) {
            this.scheme = scheme;
            this.params = params;
        }

        boolean isStale() {
            return "true".equalsIgnoreCase(params.get("stale"));
        }
    }
}
