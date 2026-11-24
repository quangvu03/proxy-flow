package com.example;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.widget.Button;

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
import com.example.ui.ProxyDetailActivity;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowDialog;

import java.io.BufferedReader;
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
 * Verifies that proxies marked as failed/untested can still connect cleanly without
 * pre-connect blocking in MainActivity and ProxyDetailActivity.
 */
@RunWith(RobolectricTestRunner.class)
public class ProxyPreConnectCheckTest {

    @After
    public void cleanup() {
        Context context = ApplicationProvider.getApplicationContext();
        ProxyRuntimeManager.getInstance().postDisconnected(null, "");
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        SettingsPreferences prefs = new SettingsPreferences(context);
        prefs.setAppLanguage(SettingsPreferences.AppLanguage.SYSTEM);
        SettingsPreferences.applyLanguage(SettingsPreferences.AppLanguage.SYSTEM);
        ShadowDialog.reset();
    }

    @Test
    public void proxyTester_rejectsInvalidPortPlainHttpAndClosedPort_acceptsConnectProxy() throws Exception {
        // Out of range port > 65535
        ProxyConfig badPort = new ProxyConfig();
        badPort.setProtocol(ProxyConfig.Protocol.HTTP);
        badPort.setHost("127.0.0.1");
        badPort.setPort(70000);
        assertFalse(ProxyTester.testProxy(badPort, 1000).isSuccess());

        // Closed port -> immediate connection refused
        int closedPort;
        try (ServerSocket tmp = new ServerSocket(0)) {
            closedPort = tmp.getLocalPort();
        }
        ProxyConfig deadProxy = new ProxyConfig();
        deadProxy.setProtocol(ProxyConfig.Protocol.HTTP);
        deadProxy.setHost("127.0.0.1");
        deadProxy.setPort(closedPort);
        assertFalse(ProxyTester.testProxy(deadProxy, 1500).isSuccess());

        // Plain web server returning HTML on GET and 405 on CONNECT must be rejected as non-proxy
        try (ServerSocket plainWeb = new ServerSocket(0)) {
            int webPort = plainWeb.getLocalPort();
            Thread serverThread = new Thread(() -> {
                for (int i = 0; i < 2; i++) {
                    try (Socket client = plainWeb.accept()) {
                        BufferedReader br = new BufferedReader(
                                new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                        String line = br.readLine();
                        while (br.ready() && br.readLine() != null) {
                            // drain headers
                        }
                        OutputStream out = client.getOutputStream();
                        if (line != null && line.startsWith("GET")) {
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n<html>not proxy</html>"
                                    .getBytes(StandardCharsets.US_ASCII));
                        } else {
                            out.write("HTTP/1.1 405 Method Not Allowed\r\n\r\n"
                                    .getBytes(StandardCharsets.US_ASCII));
                        }
                        out.flush();
                    } catch (Exception ignored) {
                    }
                }
            });
            serverThread.setDaemon(true);
            serverThread.start();

            ProxyConfig nonProxyWeb = new ProxyConfig();
            nonProxyWeb.setProtocol(ProxyConfig.Protocol.HTTP);
            nonProxyWeb.setHost("127.0.0.1");
            nonProxyWeb.setPort(webPort);
            assertFalse(ProxyTester.testProxy(nonProxyWeb, 2000).isSuccess());
        }

        // Real CONNECT-capable local proxy succeeds
        try (ServerSocket connectProxyServer = new ServerSocket(0)) {
            int proxyPort = connectProxyServer.getLocalPort();
            Thread proxyThread = new Thread(() -> {
                for (int i = 0; i < 2; i++) {
                    try (Socket client = connectProxyServer.accept()) {
                        BufferedReader br = new BufferedReader(
                                new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                        String first = br.readLine();
                        String h;
                        while ((h = br.readLine()) != null && !h.isEmpty()) {
                            // drain headers
                        }
                        OutputStream out = client.getOutputStream();
                        if (first != null && first.startsWith("CONNECT")) {
                            out.write("HTTP/1.1 200 Connection established\r\n\r\n"
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
            proxyThread.setDaemon(true);
            proxyThread.start();

            ProxyConfig working = new ProxyConfig();
            working.setProtocol(ProxyConfig.Protocol.HTTP);
            working.setHost("127.0.0.1");
            working.setPort(proxyPort);
            ProxyTester.TestResult okRes = ProxyTester.testProxy(working, 2000);
            assertTrue(okRes.isSuccess());
            assertTrue(okRes.getLatencyMs() > 0);
        }
    }

    @Test
    public void mainActivity_allowsConnectionEvenWhenProxyMarkedFailed() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        Shadows.shadowOf((Application) context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        ProxyRepository repo = ProxyRepository.getInstance(context);

        ProxyConfig proxy = new ProxyConfig();
        proxy.setName("Gate Node");
        proxy.setProtocol(ProxyConfig.Protocol.HTTP);
        proxy.setHost("198.51.100.77");
        proxy.setPort(8080);
        proxy.setLatencyMs(ProxyConfig.LATENCY_FAILED);
        proxy.setFailCount(3);

        CountDownLatch addedLatch = new CountDownLatch(1);
        repo.addOrUpdateProxy(proxy, addedLatch::countDown);
        assertTrue(addedLatch.await(3, TimeUnit.SECONDS));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        List<ProxyConfig> list = repo.getProxies().getValue();
        assertNotNull(list);
        ProxyConfig saved = null;
        for (ProxyConfig p : list) {
            if ("198.51.100.77".equals(p.getHost()) && p.getPort() == 8080) {
                saved = p;
                break;
            }
        }
        assertNotNull(saved);

        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            activity.onRequestConnectProxy(saved.getId());
            Intent startedService = null;
            org.robolectric.shadows.ShadowActivity.IntentForResult startedForResult = null;
            for (int i = 0; i < 40; i++) {
                Thread.sleep(25);
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25));
                startedService = Shadows.shadowOf(activity).getNextStartedService();
                startedForResult = Shadows.shadowOf(activity).getNextStartedActivityForResult();
                if (startedService != null || startedForResult != null) {
                    break;
                }
            }
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertNull(ShadowDialog.getLatestDialog());
            assertTrue((startedService != null && startedService.getComponent() != null
                    && ProxyVpnService.class.getName().equals(startedService.getComponent().getClassName()))
                    || startedForResult != null);
        }
    }

    @Test
    public void proxyDetailActivity_keepsConnectButtonEnabledWhenProxyFailsDiagnostics() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        ProxyRepository repo = ProxyRepository.getInstance(context);

        int closedPort;
        try (ServerSocket tmp = new ServerSocket(0)) {
            closedPort = tmp.getLocalPort();
        }
        ProxyConfig broken = new ProxyConfig();
        broken.setName("Dead Local Node");
        broken.setProtocol(ProxyConfig.Protocol.HTTP);
        broken.setHost("127.0.0.1");
        broken.setPort(closedPort);

        CountDownLatch latch = new CountDownLatch(1);
        repo.addOrUpdateProxy(broken, latch::countDown);
        assertTrue(latch.await(3, TimeUnit.SECONDS));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        List<ProxyConfig> all = repo.getProxies().getValue();
        assertNotNull(all);
        long brokenId = -1L;
        for (ProxyConfig p : all) {
            if ("127.0.0.1".equals(p.getHost()) && p.getPort() == closedPort) {
                brokenId = p.getId();
                break;
            }
        }
        assertTrue(brokenId > 0);

        Intent intent = new Intent(context, ProxyDetailActivity.class);
        intent.putExtra(ProxyDetailActivity.EXTRA_PROXY_ID, brokenId);
        try (ActivityController<ProxyDetailActivity> controller =
                     Robolectric.buildActivity(ProxyDetailActivity.class, intent).setup()) {
            ProxyDetailActivity detailActivity = controller.get();

            Button btnConnect = detailActivity.findViewById(R.id.btn_connect_this_proxy);
            assertNotNull(btnConnect);
            // Always enabled even while diagnostics run
            assertTrue(btnConnect.isEnabled());
            assertEquals(detailActivity.getString(R.string.action_connect_this_proxy),
                    btnConnect.getText().toString());

            for (int i = 0; i < 60; i++) {
                Thread.sleep(50);
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
            }
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            // Remains enabled with normal connect label even when diagnostics fail
            assertTrue(btnConnect.isEnabled());
            assertEquals(detailActivity.getString(R.string.action_connect_this_proxy),
                    btnConnect.getText().toString());
        }
    }

    @Test
    public void proxyLogFormatter_translatesRefusedFaultyProxyLogBetweenViAndEn() {
        Context context = ApplicationProvider.getApplicationContext();
        SettingsPreferences prefs = new SettingsPreferences(context);

        prefs.setAppLanguage(SettingsPreferences.AppLanguage.EN);
        assertEquals(
                "Refused connecting to faulty Proxy 10.0.0.2:8080 (Connection refused)",
                ProxyLogFormatter.localizeForDisplay(
                        context,
                        "Từ chối kết nối Proxy lỗi 10.0.0.2:8080 (Connection refused)")
        );

        prefs.setAppLanguage(SettingsPreferences.AppLanguage.VI);
        assertEquals(
                "Từ chối kết nối Proxy lỗi 10.0.0.2:8080 (Connection refused)",
                ProxyLogFormatter.localizeForDisplay(
                        context,
                        "Refused connecting to faulty Proxy 10.0.0.2:8080 (Connection refused)")
        );
    }
}
