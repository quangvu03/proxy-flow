package com.example;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;

import com.example.data.ProxyLogFormatter;
import com.example.data.ProxyRepository;
import com.example.data.SettingsPreferences;
import com.example.model.ConnectionStatus;
import com.example.model.ProxyConfig;
import com.example.model.ProxyLogEntry;
import com.example.network.ProxyRuntimeManager;
import com.example.network.ProxyTester;
import com.example.network.ProxyVpnService;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Verifies Protocol.ALL auto-detection (HTTP & SOCKS5), rejection of invalid servers,
 * and ProxyVpnService connect-time verification + automatic disconnect on faulty proxies.
 */
@RunWith(RobolectricTestRunner.class)
public class ProtocolAutoDetectAndConnectGuardTest {

    @After
    public void cleanup() {
        Context context = ApplicationProvider.getApplicationContext();
        ProxyRuntimeManager.getInstance().postDisconnected(null, "");
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        SettingsPreferences prefs = new SettingsPreferences(context);
        prefs.setAppLanguage(SettingsPreferences.AppLanguage.SYSTEM);
        SettingsPreferences.applyLanguage(SettingsPreferences.AppLanguage.SYSTEM);
    }

    @Test
    public void proxyTester_allProtocol_autoDetectsHttpConnectProxy() throws Exception {
        try (ServerSocket httpProxy = new ServerSocket(0)) {
            int port = httpProxy.getLocalPort();
            Thread serverThread = new Thread(() -> {
                for (int i = 0; i < 6; i++) {
                    try (Socket client = httpProxy.accept()) {
                        client.setSoTimeout(1000);
                        BufferedReader br = new BufferedReader(
                                new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                        String first = br.readLine();
                        String h;
                        while ((h = br.readLine()) != null && !h.isEmpty()) {
                            // drain headers
                        }
                        OutputStream out = client.getOutputStream();
                        if (first != null && first.startsWith("CONNECT")) {
                            out.write("HTTP/1.1 200 Connection Established\r\n\r\n"
                                    .getBytes(StandardCharsets.US_ASCII));
                        } else {
                            out.write("HTTP/1.1 403 Forbidden\r\n\r\n"
                                    .getBytes(StandardCharsets.US_ASCII));
                        }
                        out.flush();
                    } catch (Exception ignored) {
                    }
                }
            });
            serverThread.setDaemon(true);
            serverThread.start();

            ProxyConfig proxy = new ProxyConfig();
            proxy.setProtocol(ProxyConfig.Protocol.ALL);
            proxy.setHost("127.0.0.1");
            proxy.setPort(port);

            ProxyTester.TestResult result = ProxyTester.testProxy(proxy, 3500);
            assertTrue(result.isSuccess());
            assertEquals(ProxyConfig.Protocol.HTTP, result.getDetectedProtocol());
        }
    }

    @Test
    public void proxyTester_allProtocol_autoDetectsSocks5Proxy() throws Exception {
        try (ServerSocket socksServer = new ServerSocket(0)) {
            int port = socksServer.getLocalPort();
            Thread serverThread = new Thread(() -> {
                for (int i = 0; i < 8; i++) {
                    try {
                        Socket client = socksServer.accept();
                        Thread worker = new Thread(() -> {
                            try (Socket s = client) {
                                s.setSoTimeout(1500);
                                DataInputStream in = new DataInputStream(s.getInputStream());
                                OutputStream out = s.getOutputStream();
                                int ver = in.readUnsignedByte();
                                if (ver != 0x05) {
                                    // Non-SOCKS5 probe (HTTP / TLS): close immediately
                                    return;
                                }
                                int nMethods = in.readUnsignedByte();
                                in.readFully(new byte[nMethods]);
                                out.write(new byte[]{0x05, 0x00});
                                out.flush();

                                // CONNECT request
                                int rVer = in.readUnsignedByte();
                                int cmd = in.readUnsignedByte();
                                in.readUnsignedByte(); // RSV
                                int atyp = in.readUnsignedByte();
                                if (atyp == 0x03) {
                                    int len = in.readUnsignedByte();
                                    in.readFully(new byte[len]);
                                } else if (atyp == 0x01) {
                                    in.readFully(new byte[4]);
                                } else if (atyp == 0x04) {
                                    in.readFully(new byte[16]);
                                }
                                in.readFully(new byte[2]);
                                if (rVer == 0x05 && cmd == 0x01) {
                                    out.write(new byte[]{0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 80});
                                    out.flush();
                                }
                            } catch (Exception ignored) {
                            }
                        });
                        worker.setDaemon(true);
                        worker.start();
                    } catch (Exception ignored) {
                    }
                }
            });
            serverThread.setDaemon(true);
            serverThread.start();

            ProxyConfig proxy = new ProxyConfig();
            proxy.setProtocol(ProxyConfig.Protocol.ALL);
            proxy.setHost("127.0.0.1");
            proxy.setPort(port);

            ProxyTester.TestResult result = ProxyTester.testProxy(proxy, 3500);
            assertTrue(result.isSuccess());
            assertEquals(ProxyConfig.Protocol.SOCKS5, result.getDetectedProtocol());
        }
    }

    @Test
    public void proxyTester_allProtocol_failsOnClosedPort() throws Exception {
        int closedPort;
        try (ServerSocket tmp = new ServerSocket(0)) {
            closedPort = tmp.getLocalPort();
        }
        ProxyConfig proxy = new ProxyConfig();
        proxy.setProtocol(ProxyConfig.Protocol.ALL);
        proxy.setHost("127.0.0.1");
        proxy.setPort(closedPort);

        ProxyTester.TestResult result = ProxyTester.testProxy(proxy, 2000);
        assertFalse(result.isSuccess());
    }

    @Test
    public void proxyVpnService_verifiesOnConnectAndAutoDisconnectsWhenProxyFails() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        ProxyRepository repo = ProxyRepository.getInstance(context);

        int closedPort;
        try (ServerSocket tmp = new ServerSocket(0)) {
            closedPort = tmp.getLocalPort();
        }

        ProxyConfig broken = new ProxyConfig();
        broken.setName("Broken Verify Proxy");
        broken.setProtocol(ProxyConfig.Protocol.ALL);
        broken.setHost("127.0.0.1");
        broken.setPort(closedPort);

        CountDownLatch addLatch = new CountDownLatch(1);
        repo.addOrUpdateProxy(broken, addLatch::countDown);
        assertTrue(addLatch.await(3, TimeUnit.SECONDS));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        long proxyId = broken.getId();
        assertTrue(proxyId > 0);

        Intent startIntent = new Intent(context, ProxyVpnService.class);
        startIntent.setAction(ProxyVpnService.ACTION_START);
        startIntent.putExtra(ProxyVpnService.EXTRA_PROXY_ID, proxyId);

        ServiceController<ProxyVpnService> controller =
                Robolectric.buildService(ProxyVpnService.class, startIntent);
        controller.create().startCommand(0, 1);

        ConnectionStatus latest = null;
        for (int i = 0; i < 60; i++) {
            Thread.sleep(50);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
            latest = ProxyRuntimeManager.getInstance().getCurrentStatus();
            if (latest.getState() == ConnectionStatus.State.ERROR) {
                break;
            }
        }
        assertNotNull(latest);
        assertEquals(ConnectionStatus.State.ERROR, latest.getState());
        assertFalse(latest.getLastError().isEmpty());

        ProxyConfig updated = repo.getProxyByIdSync(proxyId);
        assertNotNull(updated);
        assertEquals(ProxyConfig.LATENCY_FAILED, updated.getLatencyMs());

        boolean foundRefusedLog = false;
        for (int i = 0; i < 160 && !foundRefusedLog; i++) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            List<ProxyLogEntry> logs = repo.getLogs().getValue();
            if (logs != null) {
                for (ProxyLogEntry entry : logs) {
                    if (entry.getLevel() == ProxyLogEntry.Level.ERROR
                            && entry.getMessage().contains("127.0.0.1:" + closedPort)) {
                        foundRefusedLog = true;
                        break;
                    }
                }
            }
            if (!foundRefusedLog) {
                Thread.sleep(25);
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25));
            }
        }
        StringBuilder diag = new StringBuilder();
        List<ProxyLogEntry> finalLogs = repo.getLogs().getValue();
        diag.append("closedPort=").append(closedPort)
                .append(", state=").append(latest.getState())
                .append(", lastError=").append(latest.getLastError())
                .append(", logsSize=").append(finalLogs != null ? finalLogs.size() : -1);
        if (finalLogs != null) {
            int limit = Math.min(8, finalLogs.size());
            for (int i = 0; i < limit; i++) {
                ProxyLogEntry e = finalLogs.get(i);
                diag.append(" | [").append(e.getLevel()).append("] ").append(e.getMessage());
            }
        }
        assertTrue(diag.toString(), foundRefusedLog);
        controller.destroy();
    }

    @Test
    public void proxyLogFormatter_translatesProtocolAutoDetectedBetweenViAndEn() {
        Context context = ApplicationProvider.getApplicationContext();
        SettingsPreferences prefs = new SettingsPreferences(context);

        prefs.setAppLanguage(SettingsPreferences.AppLanguage.EN);
        assertEquals(
                "Detected protocol SOCKS5 for Proxy 10.0.0.5:1080",
                ProxyLogFormatter.localizeForDisplay(
                        context,
                        "Đã nhận diện giao thức SOCKS5 cho Proxy 10.0.0.5:1080")
        );

        prefs.setAppLanguage(SettingsPreferences.AppLanguage.VI);
        assertEquals(
                "Đã nhận diện giao thức HTTP + TLS cho Proxy 10.0.0.5:8443",
                ProxyLogFormatter.localizeForDisplay(
                        context,
                        "Detected protocol HTTP + TLS for Proxy 10.0.0.5:8443")
        );
    }

    @Test
    public void connectFragment_showsSpinningIndicatorWhenConnecting_andRevertsToConnectOnFailure() {
        ProxyRuntimeManager runtime = ProxyRuntimeManager.getInstance();
        try (org.robolectric.android.controller.ActivityController<MainActivity> controller =
                     Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            com.google.android.material.button.MaterialButton btnToggle = activity.findViewById(R.id.btn_toggle_vpn);
            android.view.View layoutLoading = activity.findViewById(R.id.layout_connect_loading);
            android.view.View progressSpin = activity.findViewById(R.id.progress_connect_vpn);

            assertNotNull(btnToggle);
            assertNotNull(layoutLoading);
            assertNotNull(progressSpin);

            // Initially disconnected -> normal Connect Proxy button, no spinner
            assertTrue(btnToggle.isEnabled());
            assertEquals(activity.getString(R.string.action_connect), btnToggle.getText().toString());
            assertEquals(android.view.View.GONE, layoutLoading.getVisibility());

            // CONNECTING -> spinning indicator visible, button busy
            ProxyConfig proxy = new ProxyConfig();
            proxy.setProtocol(ProxyConfig.Protocol.ALL);
            proxy.setHost("203.0.113.20");
            proxy.setPort(1080);
            runtime.postStatus(new ConnectionStatus(
                    ConnectionStatus.State.CONNECTING,
                    proxy,
                    0, 0L, 0L, 0, "", 0L
            ));
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertFalse(btnToggle.isEnabled());
            assertEquals(activity.getString(R.string.action_connecting), btnToggle.getText().toString());
            assertEquals(android.view.View.VISIBLE, layoutLoading.getVisibility());
            assertEquals(android.view.View.VISIBLE, progressSpin.getVisibility());

            // Connect fails -> ERROR reverts back to normal Connect Proxy button & hides spinner
            runtime.postDisconnected(proxy, "Connection refused");
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertTrue(btnToggle.isEnabled());
            assertEquals(activity.getString(R.string.action_connect), btnToggle.getText().toString());
            assertEquals(android.view.View.GONE, layoutLoading.getVisibility());

            // CONNECTED -> spinner hidden, button shows Disconnect
            runtime.postStatus(new ConnectionStatus(
                    ConnectionStatus.State.CONNECTED,
                    proxy,
                    10808, 0L, 0L, 1, "", System.currentTimeMillis()
            ));
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertTrue(btnToggle.isEnabled());
            assertEquals(activity.getString(R.string.action_disconnect), btnToggle.getText().toString());
            assertEquals(android.view.View.GONE, layoutLoading.getVisibility());
        } finally {
            runtime.postDisconnected(null, "");
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
    }
}
