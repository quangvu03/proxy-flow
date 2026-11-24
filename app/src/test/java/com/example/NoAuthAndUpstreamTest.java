package com.example;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.model.ProxyConfig;
import com.example.network.LocalProxyServer;
import com.example.network.ProxySpeedTester;
import com.example.network.ProxyTester;
import com.example.network.UpstreamProxyConnector;
import com.example.ui.AddEditProxyActivity;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pure JVM unit tests verifying no-auth proxy connections (HTTP passthrough & 407 tolerance,
 * SOCKS5 no-auth and empty-credential subnegotiation).
 */
public class NoAuthAndUpstreamTest {

    @Test
    public void localProxyServer_withoutStoredAuth_forwards407WithoutFailingAndPreservesClientAuthHeader()
            throws Exception {
        AtomicReference<String> receivedClientAuth = new AtomicReference<>("");
        try (ServerSocket upstreamServer = new ServerSocket(0)) {
            int upstreamPort = upstreamServer.getLocalPort();
            Thread upstreamThread = new Thread(() -> {
                for (int i = 0; i < 2; i++) {
                    try (Socket s = upstreamServer.accept()) {
                        BufferedReader reader = new BufferedReader(
                                new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
                        String line;
                        String authHeader = null;
                        while ((line = reader.readLine()) != null && !line.isEmpty()) {
                            if (line.toLowerCase().startsWith("proxy-authorization:")) {
                                authHeader = line;
                            }
                        }
                        OutputStream out = s.getOutputStream();
                        if (authHeader == null) {
                            out.write(("HTTP/1.1 407 Proxy Authentication Required\r\n"
                                    + "Proxy-Authenticate: Basic realm=\"Provider\"\r\n\r\n")
                                    .getBytes(StandardCharsets.ISO_8859_1));
                        } else {
                            receivedClientAuth.set(authHeader);
                            out.write("HTTP/1.1 200 Connection Established\r\n\r\n"
                                    .getBytes(StandardCharsets.ISO_8859_1));
                        }
                        out.flush();
                    } catch (Exception ignored) {
                    }
                }
            });
            upstreamThread.setDaemon(true);
            upstreamThread.start();

            ProxyConfig noAuthProxy = new ProxyConfig();
            noAuthProxy.setId(42L);
            noAuthProxy.setProtocol(ProxyConfig.Protocol.HTTP);
            noAuthProxy.setHost("127.0.0.1");
            noAuthProxy.setPort(upstreamPort);
            noAuthProxy.setAuthMethod(ProxyConfig.AuthMethod.NONE);

            LocalProxyServer localServer = new LocalProxyServer();
            AtomicBoolean failureReported = new AtomicBoolean(false);
            AtomicBoolean successReported = new AtomicBoolean(false);
            localServer.setEventListener(new LocalProxyServer.EventListener() {
                @Override
                public void onUpstreamSuccess(long proxyId) {
                    successReported.set(true);
                }

                @Override
                public void onUpstreamFailure(long proxyId, String reason) {
                    failureReported.set(true);
                }
            });
            int localPort = localServer.start(noAuthProxy);
            try {
                // 1. Client connects without credentials -> upstream sends 407 -> forwarded, NO failure event
                try (Socket client = new Socket("127.0.0.1", localPort)) {
                    OutputStream out = client.getOutputStream();
                    out.write("CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n"
                            .getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                    BufferedReader br = new BufferedReader(
                            new InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1));
                    String status = br.readLine();
                    assertTrue(status != null && status.contains("407"));
                }
                assertFalse("Connecting without stored auth must not mark VPN failure on 407", failureReported.get());

                // 2. Client supplies its own Proxy-Authorization -> preserved to upstream & succeeds
                try (Socket client = new Socket("127.0.0.1", localPort)) {
                    OutputStream out = client.getOutputStream();
                    out.write(("CONNECT example.com:443 HTTP/1.1\r\n"
                            + "Host: example.com:443\r\n"
                            + "Proxy-Authorization: Basic dXNlcjpwYXNz\r\n\r\n")
                            .getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                    BufferedReader br = new BufferedReader(
                            new InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1));
                    String status = br.readLine();
                    assertTrue(status != null && status.contains("200"));
                }
                assertTrue(successReported.get());
                assertEquals("Proxy-Authorization: Basic dXNlcjpwYXNz", receivedClientAuth.get());
            } finally {
                localServer.stop();
            }
        }
    }

    @Test
    public void upstreamSocks5Connector_allowsNoAuthAndEmptyUserPassSubnegotiation() throws Exception {
        try (ServerSocket socksServer = new ServerSocket(0)) {
            int socksPort = socksServer.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try (Socket client = socksServer.accept()) {
                    DataInputStream in = new DataInputStream(client.getInputStream());
                    OutputStream out = client.getOutputStream();

                    // Greeting
                    int ver = in.readUnsignedByte();
                    int nMethods = in.readUnsignedByte();
                    byte[] methods = new byte[nMethods];
                    in.readFully(methods);
                    assertEquals(0x05, ver);

                    // Server asks for 0x02 (user/pass) and accepts empty credentials
                    out.write(new byte[]{0x05, 0x02});
                    out.flush();

                    int authVer = in.readUnsignedByte();
                    int uLen = in.readUnsignedByte();
                    in.readFully(new byte[uLen]);
                    int pLen = in.readUnsignedByte();
                    in.readFully(new byte[pLen]);
                    assertEquals(0x01, authVer);
                    assertEquals(0, uLen);
                    assertEquals(0, pLen);

                    out.write(new byte[]{0x01, 0x00}); // Auth OK
                    out.flush();

                    // CONNECT request
                    in.readUnsignedByte(); // VER
                    in.readUnsignedByte(); // CMD
                    in.readUnsignedByte(); // RSV
                    int atyp = in.readUnsignedByte();
                    if (atyp == 0x03) {
                        int dLen = in.readUnsignedByte();
                        in.readFully(new byte[dLen]);
                    }
                    in.readFully(new byte[2]); // port

                    out.write(new byte[]{0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 80});
                    out.flush();
                } catch (Exception ignored) {
                }
            });
            serverThread.setDaemon(true);
            serverThread.start();

            ProxyConfig socksNoAuth = new ProxyConfig();
            socksNoAuth.setProtocol(ProxyConfig.Protocol.SOCKS5);
            socksNoAuth.setHost("127.0.0.1");
            socksNoAuth.setPort(socksPort);
            socksNoAuth.setAuthMethod(ProxyConfig.AuthMethod.NONE);

            try (Socket tunnel = UpstreamProxyConnector.openTunnel(socksNoAuth, "example.com", 443, 3000)) {
                assertTrue(tunnel.isConnected());
            }
        }
    }

    @Test
    public void proxyTesterAndSpeedTester_withoutCredentials_doNotReportWrongPasswordOn407() throws Exception {
        try (ServerSocket authProxyServer = new ServerSocket(0)) {
            int port = authProxyServer.getLocalPort();
            Thread serverThread = new Thread(() -> {
                for (int i = 0; i < 4; i++) {
                    try (Socket client = authProxyServer.accept()) {
                        BufferedReader reader = new BufferedReader(
                                new InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1));
                        String line;
                        while ((line = reader.readLine()) != null && !line.isEmpty()) {
                            // consume headers
                        }
                        OutputStream out = client.getOutputStream();
                        out.write(("HTTP/1.1 407 Proxy Authentication Required\r\n"
                                + "Proxy-Authenticate: Basic realm=\"Provider\"\r\n"
                                + "Connection: close\r\n\r\n")
                                .getBytes(StandardCharsets.ISO_8859_1));
                        out.flush();
                    } catch (Exception ignored) {
                    }
                }
            });
            serverThread.setDaemon(true);
            serverThread.start();

            // 1. No credentials entered -> must connect/test cleanly without "Sai tài khoản hoặc mật khẩu"
            ProxyConfig noAuthProxy = new ProxyConfig();
            noAuthProxy.setProtocol(ProxyConfig.Protocol.HTTP);
            noAuthProxy.setHost("127.0.0.1");
            noAuthProxy.setPort(port);
            noAuthProxy.setAuthMethod(ProxyConfig.AuthMethod.NONE);

            ProxyTester.TestResult noAuthTest = ProxyTester.testProxy(noAuthProxy, 3000);
            assertTrue(noAuthTest.isSuccess());
            assertFalse(noAuthTest.getErrorMessage().contains("Sai tài khoản"));

            ProxySpeedTester.DiagnosticResult diag = ProxySpeedTester.runDiagnostics(noAuthProxy);
            assertTrue(diag.isUsable());
            assertEquals(ProxySpeedTester.AuthState.NOT_REQUIRED, diag.getAuthState());
            assertFalse(diag.getErrorMessage().contains("Sai tài khoản"));

            // 2. Credentials entered AND wrong -> still reports 407 wrong credentials
            ProxyConfig wrongCredProxy = new ProxyConfig();
            wrongCredProxy.setProtocol(ProxyConfig.Protocol.HTTP);
            wrongCredProxy.setHost("127.0.0.1");
            wrongCredProxy.setPort(port);
            wrongCredProxy.setAuthMethod(ProxyConfig.AuthMethod.USER_PASS);
            wrongCredProxy.setUsername("baduser");
            wrongCredProxy.setPassword("badpass");

            ProxyTester.TestResult badAuthTest = ProxyTester.testProxy(wrongCredProxy, 3000);
            assertFalse(badAuthTest.isSuccess());
            assertTrue(badAuthTest.getErrorMessage().contains("407"));
        }
    }

    @Test
    public void proxyTester_socks5WithoutCredentials_avoidsWrongPasswordErrorWhenServerRequiresAuth()
            throws Exception {
        try (ServerSocket socksServer = new ServerSocket(0)) {
            int port = socksServer.getLocalPort();
            Thread serverThread = new Thread(() -> {
                for (int i = 0; i < 2; i++) {
                    try (Socket client = socksServer.accept()) {
                        DataInputStream in = new DataInputStream(client.getInputStream());
                        OutputStream out = client.getOutputStream();
                        in.readUnsignedByte(); // VER
                        int n = in.readUnsignedByte();
                        in.readFully(new byte[n]);
                        // Server rejects no-auth (0xFF)
                        out.write(new byte[]{0x05, (byte) 0xFF});
                        out.flush();
                    } catch (Exception ignored) {
                    }
                }
            });
            serverThread.setDaemon(true);
            serverThread.start();

            ProxyConfig socksNoAuth = new ProxyConfig();
            socksNoAuth.setProtocol(ProxyConfig.Protocol.SOCKS5);
            socksNoAuth.setHost("127.0.0.1");
            socksNoAuth.setPort(port);
            socksNoAuth.setAuthMethod(ProxyConfig.AuthMethod.NONE);

            ProxyTester.TestResult result = ProxyTester.testProxy(socksNoAuth, 3000);
            assertTrue(result.isSuccess());
            assertFalse(result.getErrorMessage().contains("Sai tài khoản"));
        }
    }

    @Test
    public void addEditProxyActivity_isPortTimeoutError_matchesPortTimeoutsOnly() {
        assertTrue(AddEditProxyActivity.isPortTimeoutError("Timeout connecting to the specified port (8080)"));
        assertTrue(AddEditProxyActivity.isPortTimeoutError("timeout connecting to the specified port"));
        assertTrue(AddEditProxyActivity.isPortTimeoutError("connect timed out"));
        assertTrue(AddEditProxyActivity.isPortTimeoutError("failed to connect to /1.2.3.4 (port 1080)"));
        assertTrue(AddEditProxyActivity.isPortTimeoutError("Quá thời gian kết nối"));

        assertFalse(AddEditProxyActivity.isPortTimeoutError("Lỗi 407: Sai tài khoản hoặc mật khẩu"));
        assertFalse(AddEditProxyActivity.isPortTimeoutError("Connection refused"));
        assertFalse(AddEditProxyActivity.isPortTimeoutError(""));
        assertFalse(AddEditProxyActivity.isPortTimeoutError(null));
    }
}
