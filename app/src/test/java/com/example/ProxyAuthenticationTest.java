package com.example;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.example.model.ProxyConfig;
import com.example.network.LocalProxyServer;
import com.example.network.ProxyAuthHelper;
import com.example.network.ProxySpeedTester;
import com.example.network.ProxyTester;
import com.example.network.UpstreamProxyConnector;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pure JVM tests verifying reliable proxy authentication across:
 * - HTTP Basic (RFC 7617 UTF-8)
 * - HTTP Digest (MD5 and SHA-256 with qop=auth challenge retry)
 * - Bearer / Token authentication
 * - SOCKS5 RFC 1929 Username/Password authentication (0x02)
 * - LocalProxyServer end-to-end CONNECT + plain HTTP GET with 407 challenge retry
 */
public class ProxyAuthenticationTest {

    @Test
    public void proxyAuthHelper_computeDigestMd5AndSha256_matchesRfcFormula() throws Exception {
        Map<String, String> md5Params = new LinkedHashMap<>();
        md5Params.put("realm", "ProxyRealm");
        md5Params.put("nonce", "dcd98b7102dd2f0e8b11d0f600bfb0c093");
        md5Params.put("qop", "auth, auth-int");
        md5Params.put("algorithm", "MD5");
        md5Params.put("opaque", "5ccc069c403ebaf9f0171e9517f40e41");

        String header = ProxyAuthHelper.buildChallengeResponseHeaderValue(
                createCredProxy(ProxyConfig.AuthMethod.USER_PASS, "alice", "secret"),
                Collections.singletonList("Digest realm=\"ProxyRealm\", nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", qop=\"auth, auth-int\", algorithm=MD5, opaque=\"5ccc069c403ebaf9f0171e9517f40e41\""),
                "CONNECT",
                "example.com:443",
                "Basic invalid=="
        );
        assertNotNull(header);
        assertTrue(header.startsWith("Digest "));
        assertTrue(header.contains("username=\"alice\""));
        assertTrue(header.contains("realm=\"ProxyRealm\""));
        assertTrue(header.contains("qop=auth"));
        assertTrue(header.contains("opaque=\"5ccc069c403ebaf9f0171e9517f40e41\""));

        Map<String, String> parsed = parseDigestHeaderParams(header);
        assertTrue(verifyDigestResponse("MD5", "alice", "secret", "CONNECT", "example.com:443",
                "ProxyRealm", "dcd98b7102dd2f0e8b11d0f600bfb0c093", parsed));

        // SHA-256 Digest test
        String shaHeader = ProxyAuthHelper.buildChallengeResponseHeaderValue(
                createCredProxy(ProxyConfig.AuthMethod.HTTP_DIGEST, "bob", "p@ss#123"),
                Collections.singletonList("Digest realm=\"CorpProxy\", nonce=\"nonce999\", qop=\"auth\", algorithm=SHA-256"),
                "GET",
                "http://ip-api.com/json",
                null
        );
        assertNotNull(shaHeader);
        assertTrue(shaHeader.contains("algorithm=SHA-256"));
        Map<String, String> shaParsed = parseDigestHeaderParams(shaHeader);
        assertTrue(verifyDigestResponse("SHA-256", "bob", "p@ss#123", "GET", "http://ip-api.com/json",
                "CorpProxy", "nonce999", shaParsed));
    }

    @Test
    public void localProxyServer_andProxyTester_digestChallengeRetry_succeedsForConnectAndPlainGet()
            throws Exception {
        final String realm = "SecureProxy";
        final String nonce = "abc123nonceXYZ";
        final String expectedUser = "proxyuser";
        final String expectedPass = "StrongPass!99";

        try (ServerSocket upstreamServer = new ServerSocket(0)) {
            int upstreamPort = upstreamServer.getLocalPort();
            Thread upstreamThread = new Thread(() -> {
                while (!upstreamServer.isClosed()) {
                    try (Socket s = upstreamServer.accept()) {
                        BufferedReader reader = new BufferedReader(
                                new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
                        String reqLine = reader.readLine();
                        if (reqLine == null) continue;
                        String[] parts = reqLine.split("\\s+");
                        String method = parts.length > 0 ? parts[0] : "";
                        String target = parts.length > 1 ? parts[1] : "";

                        String line;
                        String proxyAuth = null;
                        while ((line = reader.readLine()) != null && !line.isEmpty()) {
                            if (line.toLowerCase().startsWith("proxy-authorization:")) {
                                proxyAuth = line.substring("proxy-authorization:".length()).trim();
                            }
                        }

                        OutputStream out = s.getOutputStream();
                        boolean authorized = false;
                        if (proxyAuth != null && proxyAuth.startsWith("Digest ")) {
                            Map<String, String> params = parseDigestHeaderParams(proxyAuth);
                            authorized = expectedUser.equals(params.get("username"))
                                    && verifyDigestResponse("SHA-256", expectedUser, expectedPass,
                                    method, target, realm, nonce, params);
                        }

                        if (!authorized) {
                            out.write(("HTTP/1.1 407 Proxy Authentication Required\r\n"
                                    + "Proxy-Authenticate: Digest realm=\"" + realm
                                    + "\", nonce=\"" + nonce
                                    + "\", qop=\"auth\", algorithm=SHA-256\r\n"
                                    + "Content-Length: 0\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                            out.flush();
                            continue;
                        }

                        if ("CONNECT".equalsIgnoreCase(method)) {
                            out.write("HTTP/1.1 200 Connection Established\r\n\r\n"
                                    .getBytes(StandardCharsets.ISO_8859_1));
                            out.flush();
                        } else {
                            String json = "{\"status\":\"success\",\"country\":\"Vietnam\",\"query\":\"198.51.100.77\"}";
                            byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
                            out.write(("HTTP/1.1 200 OK\r\n"
                                    + "Content-Type: application/json\r\n"
                                    + "Content-Length: " + jsonBytes.length + "\r\n"
                                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                            out.write(jsonBytes);
                            out.flush();
                        }
                    } catch (Exception ignored) {
                    }
                }
            });
            upstreamThread.setDaemon(true);
            upstreamThread.start();

            // 1. ProxyTester auto-negotiates Digest in USER_PASS mode and extracts exit IP
            ProxyConfig proxy = createCredProxy(ProxyConfig.AuthMethod.USER_PASS, expectedUser, expectedPass);
            proxy.setId(101L);
            proxy.setProtocol(ProxyConfig.Protocol.HTTP);
            proxy.setHost("127.0.0.1");
            proxy.setPort(upstreamPort);

            ProxyTester.TestResult testResult = ProxyTester.testProxy(proxy, 3000);
            assertTrue(testResult.isSuccess());
            assertEquals("198.51.100.77", testResult.getExitIp());
            assertEquals("Vietnam", testResult.getCountry());

            // 2. ProxySpeedTester validates HTTPS tunnel + VALID auth state
            ProxySpeedTester.DiagnosticResult diag = ProxySpeedTester.runDiagnostics(proxy);
            assertTrue(diag.isUsable());
            assertTrue(diag.isHttpsTunnelOk());
            assertEquals(ProxySpeedTester.AuthState.VALID, diag.getAuthState());

            // 3. LocalProxyServer handles CONNECT and plain GET with Digest challenge retry cleanly
            LocalProxyServer localServer = new LocalProxyServer();
            AtomicBoolean failed = new AtomicBoolean(false);
            AtomicBoolean succeeded = new AtomicBoolean(false);
            localServer.setEventListener(new LocalProxyServer.EventListener() {
                @Override
                public void onUpstreamSuccess(long proxyId) {
                    succeeded.set(true);
                }

                @Override
                public void onUpstreamFailure(long proxyId, String reason) {
                    failed.set(true);
                }
            });
            int localPort = localServer.start(proxy);
            try {
                try (Socket client = new Socket("127.0.0.1", localPort)) {
                    OutputStream out = client.getOutputStream();
                    out.write("CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n"
                            .getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                    BufferedReader br = new BufferedReader(
                            new InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1));
                    String status = br.readLine();
                    assertTrue(status != null && status.contains("200"));
                }

                try (Socket client = new Socket("127.0.0.1", localPort)) {
                    OutputStream out = client.getOutputStream();
                    out.write("GET http://ip-api.com/json HTTP/1.1\r\nHost: ip-api.com\r\nConnection: close\r\n\r\n"
                            .getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                    BufferedReader br = new BufferedReader(
                            new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder full = new StringBuilder();
                    String l;
                    while ((l = br.readLine()) != null) {
                        full.append(l).append('\n');
                    }
                    String resp = full.toString();
                    assertTrue(resp.contains("200 OK"));
                    assertTrue(resp.contains("198.51.100.77"));
                }

                assertTrue(succeeded.get());
                assertFalse(failed.get());
            } finally {
                localServer.stop();
            }
        }
    }

    @Test
    public void proxyTesterAndLocalServer_bearerTokenAuth_authenticatesCleanly() throws Exception {
        final String expectedToken = "Bearer-Secret-Token-2026";
        AtomicReference<String> seenAuthHeader = new AtomicReference<>("");

        try (ServerSocket upstreamServer = new ServerSocket(0)) {
            int port = upstreamServer.getLocalPort();
            Thread t = new Thread(() -> {
                while (!upstreamServer.isClosed()) {
                    try (Socket s = upstreamServer.accept()) {
                        BufferedReader reader = new BufferedReader(
                                new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
                        String line;
                        String authHeader = "";
                        while ((line = reader.readLine()) != null && !line.isEmpty()) {
                            if (line.toLowerCase().startsWith("proxy-authorization:")) {
                                authHeader = line.substring("proxy-authorization:".length()).trim();
                            }
                        }
                        seenAuthHeader.set(authHeader);
                        OutputStream out = s.getOutputStream();
                        if (("Bearer " + expectedToken).equals(authHeader)) {
                            out.write("HTTP/1.1 200 Connection Established\r\n\r\n"
                                    .getBytes(StandardCharsets.ISO_8859_1));
                        } else {
                            out.write(("HTTP/1.1 407 Proxy Authentication Required\r\n"
                                    + "Proxy-Authenticate: Bearer realm=\"ProxyFlow\"\r\n"
                                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                        }
                        out.flush();
                    } catch (Exception ignored) {
                    }
                }
            });
            t.setDaemon(true);
            t.start();

            ProxyConfig tokenProxy = new ProxyConfig();
            tokenProxy.setProtocol(ProxyConfig.Protocol.HTTP);
            tokenProxy.setHost("127.0.0.1");
            tokenProxy.setPort(port);
            tokenProxy.setAuthMethod(ProxyConfig.AuthMethod.BEARER_TOKEN);
            tokenProxy.setUsername("");
            tokenProxy.setPassword(expectedToken);

            ProxySpeedTester.DiagnosticResult diag = ProxySpeedTester.runDiagnostics(tokenProxy);
            assertTrue(diag.isUsable());
            assertEquals(ProxySpeedTester.AuthState.VALID, diag.getAuthState());
            assertEquals("Bearer " + expectedToken, seenAuthHeader.get());

            // Wrong token returns 407 failure
            tokenProxy.setPassword("wrong-token");
            ProxySpeedTester.DiagnosticResult badDiag = ProxySpeedTester.runDiagnostics(tokenProxy);
            assertFalse(badDiag.isUsable());
            assertEquals(ProxySpeedTester.AuthState.FAILED_407, badDiag.getAuthState());
        }
    }

    @Test
    public void socks5Rfc1929_userPassAuth_succeedsAndRejectsWrongPassword() throws Exception {
        try (ServerSocket socksServer = new ServerSocket(0)) {
            int port = socksServer.getLocalPort();
            Thread t = new Thread(() -> {
                for (int i = 0; i < 3; i++) {
                    try (Socket client = socksServer.accept()) {
                        DataInputStream in = new DataInputStream(client.getInputStream());
                        OutputStream out = client.getOutputStream();

                        int ver = in.readUnsignedByte();
                        int n = in.readUnsignedByte();
                        byte[] methods = new byte[n];
                        in.readFully(methods);
                        assertEquals(0x05, ver);

                        boolean has02 = false;
                        for (byte m : methods) {
                            if (m == 0x02) has02 = true;
                        }
                        if (!has02) {
                            out.write(new byte[]{0x05, (byte) 0xFF});
                            out.flush();
                            continue;
                        }

                        out.write(new byte[]{0x05, 0x02});
                        out.flush();

                        int authVer = in.readUnsignedByte();
                        int uLen = in.readUnsignedByte();
                        byte[] uBytes = new byte[uLen];
                        in.readFully(uBytes);
                        int pLen = in.readUnsignedByte();
                        byte[] pBytes = new byte[pLen];
                        in.readFully(pBytes);
                        assertEquals(0x01, authVer);

                        String user = new String(uBytes, StandardCharsets.UTF_8);
                        String pass = new String(pBytes, StandardCharsets.UTF_8);
                        if ("vnuser".equals(user) && "mật_khẩu_123".equals(pass)) {
                            out.write(new byte[]{0x01, 0x00});
                            out.flush();

                            // Read CONNECT request
                            in.readUnsignedByte(); // VER
                            in.readUnsignedByte(); // CMD
                            in.readUnsignedByte(); // RSV
                            int atyp = in.readUnsignedByte();
                            if (atyp == 0x03) {
                                int dLen = in.readUnsignedByte();
                                in.readFully(new byte[dLen]);
                            }
                            in.readFully(new byte[2]);
                            out.write(new byte[]{0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 80});
                            out.flush();
                        } else {
                            out.write(new byte[]{0x01, 0x01});
                            out.flush();
                        }
                    } catch (Exception ignored) {
                    }
                }
            });
            t.setDaemon(true);
            t.start();

            // 1. Valid UTF-8 SOCKS5 credentials succeed
            ProxyConfig valid = createCredProxy(ProxyConfig.AuthMethod.USER_PASS, "vnuser", "mật_khẩu_123");
            valid.setProtocol(ProxyConfig.Protocol.SOCKS5);
            valid.setHost("127.0.0.1");
            valid.setPort(port);

            try (Socket tunnel = UpstreamProxyConnector.openTunnel(valid, "example.com", 443, 3000)) {
                assertTrue(tunnel.isConnected());
            }

            // 2. Wrong password returns FAILED_407 in diagnostics
            ProxyConfig wrong = valid.copy();
            wrong.setPassword("wrong");
            ProxySpeedTester.DiagnosticResult wrongDiag = ProxySpeedTester.runDiagnostics(wrong);
            assertFalse(wrongDiag.isUsable());
            assertEquals(ProxySpeedTester.AuthState.FAILED_407, wrongDiag.getAuthState());
        }
    }

    @Test
    public void proxyConfig_parseFromLineDecodesPercentEncodedCredentialsAndPreservesExplicitAuthMethod() {
        ProxyConfig parsed = ProxyConfig.parseFromLine("socks5://alice%40corp.com:p%40ss%3A123@203.0.113.9:1080");
        assertNotNull(parsed);
        assertEquals(ProxyConfig.Protocol.SOCKS5, parsed.getProtocol());
        assertEquals("203.0.113.9", parsed.getHost());
        assertEquals(1080, parsed.getPort());
        assertEquals(ProxyConfig.AuthMethod.USER_PASS, parsed.getAuthMethod());
        assertEquals("alice@corp.com", parsed.getUsername());
        assertEquals("p@ss:123", parsed.getPassword());

        ProxyConfig explicitDigest = new ProxyConfig();
        explicitDigest.setAuthMethod(ProxyConfig.AuthMethod.HTTP_DIGEST);
        explicitDigest.setUsername("bob");
        explicitDigest.setPassword("pw");
        assertEquals(ProxyConfig.AuthMethod.HTTP_DIGEST, explicitDigest.getAuthMethod());
    }

    private static ProxyConfig createCredProxy(ProxyConfig.AuthMethod method, String user, String pass) {
        ProxyConfig p = new ProxyConfig();
        p.setAuthMethod(method);
        p.setUsername(user);
        p.setPassword(pass);
        return p;
    }

    private static Map<String, String> parseDigestHeaderParams(String header) {
        Map<String, String> out = new LinkedHashMap<>();
        String body = header.startsWith("Digest ") ? header.substring(7) : header;
        int i = 0;
        while (i < body.length()) {
            while (i < body.length() && (body.charAt(i) == ',' || body.charAt(i) == ' ')) i++;
            int eq = body.indexOf('=', i);
            if (eq < 0) break;
            String key = body.substring(i, eq).trim();
            i = eq + 1;
            String val;
            if (i < body.length() && body.charAt(i) == '"') {
                i++;
                int endQ = body.indexOf('"', i);
                if (endQ < 0) endQ = body.length();
                val = body.substring(i, endQ);
                i = endQ + 1;
            } else {
                int comma = body.indexOf(',', i);
                if (comma < 0) comma = body.length();
                val = body.substring(i, comma).trim();
                i = comma;
            }
            out.put(key, val);
        }
        return out;
    }

    private static boolean verifyDigestResponse(String jcaAlg, String user, String pass, String method,
                                                String uri, String realm, String nonce,
                                                Map<String, String> parsed) throws Exception {
        String ha1 = hexHash(jcaAlg, user + ":" + realm + ":" + pass);
        String ha2 = hexHash(jcaAlg, method.toUpperCase() + ":" + uri);
        String qop = parsed.get("qop");
        String nc = parsed.get("nc");
        String cnonce = parsed.get("cnonce");
        String expected = (qop != null)
                ? hexHash(jcaAlg, ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + qop + ":" + ha2)
                : hexHash(jcaAlg, ha1 + ":" + nonce + ":" + ha2);
        return expected.equals(parsed.get("response"));
    }

    private static String hexHash(String alg, String text) throws Exception {
        byte[] digest = MessageDigest.getInstance(alg).digest(text.getBytes(StandardCharsets.ISO_8859_1));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }
}
