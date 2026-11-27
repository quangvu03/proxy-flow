package com.example;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;

import com.example.data.ProxyRepository;
import com.example.data.SettingsPreferences;
import com.example.model.ProxyConfig;
import com.example.network.ProxyFailureFormatter;
import com.example.network.ProxySpeedTester;
import com.example.network.ProxyTester;
import com.example.ui.ProxyDetailActivity;
import com.example.ui.ProxyListFragment;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowToast;

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
 * Verifies that all proxy inspection ("Kiểm tra") surfaces diagnose and display
 * the exact underlying connection failure reason inline instead of generic Toasts or vague labels.
 */
@RunWith(RobolectricTestRunner.class)
public class ProxyFailureInspectionTest {

    @After
    public void cleanup() {
        Context context = ApplicationProvider.getApplicationContext();
        SettingsPreferences prefs = new SettingsPreferences(context);
        prefs.setAppLanguage(SettingsPreferences.AppLanguage.SYSTEM);
        SettingsPreferences.applyLanguage(SettingsPreferences.AppLanguage.SYSTEM);
        ShadowToast.reset();
    }

    @Test
    public void proxySpeedTester_classifiesClosedPortAndAuthRequiredWithStructuredReasons() throws Exception {
        int closedPort;
        try (ServerSocket tmp = new ServerSocket(0)) {
            closedPort = tmp.getLocalPort();
        }
        ProxyConfig closedProxy = new ProxyConfig();
        closedProxy.setProtocol(ProxyConfig.Protocol.ALL);
        closedProxy.setHost("127.0.0.1");
        closedProxy.setPort(closedPort);

        ProxySpeedTester.DiagnosticResult closedDiag = ProxySpeedTester.runDiagnostics(closedProxy);
        assertFalse(closedDiag.isTcpReachable());
        assertFalse(closedDiag.isUsable());
        assertEquals(ProxyTester.FailureReason.CONNECTION_REFUSED, closedDiag.getFailureReason());

        try (ServerSocket authProxyServer = new ServerSocket(0)) {
            int port = authProxyServer.getLocalPort();
            Thread serverThread = new Thread(() -> {
                for (int i = 0; i < 8; i++) {
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

            ProxyConfig authAuto = new ProxyConfig();
            authAuto.setProtocol(ProxyConfig.Protocol.ALL);
            authAuto.setHost("127.0.0.1");
            authAuto.setPort(port);
            authAuto.setAuthMethod(ProxyConfig.AuthMethod.NONE);

            ProxySpeedTester.DiagnosticResult authDiag = ProxySpeedTester.runDiagnostics(authAuto);
            assertTrue(authDiag.isTcpReachable());
            assertFalse(authDiag.isUsable());
            assertEquals(ProxySpeedTester.AuthState.REQUIRED, authDiag.getAuthState());
            assertEquals(ProxyTester.FailureReason.AUTH_REQUIRED, authDiag.getFailureReason());
        }
    }

    @Test
    public void proxyDetailActivity_rendersRefusedCauseAndSkippedStepsInline() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        SettingsPreferences prefs = new SettingsPreferences(context);
        prefs.setAppLanguage(SettingsPreferences.AppLanguage.VI);
        SettingsPreferences.applyLanguage(SettingsPreferences.AppLanguage.VI);

        int closedPort;
        try (ServerSocket tmp = new ServerSocket(0)) {
            closedPort = tmp.getLocalPort();
        }
        ProxyRepository repo = ProxyRepository.getInstance(context);
        ProxyConfig broken = new ProxyConfig();
        broken.setName("Inspect Refused");
        broken.setProtocol(ProxyConfig.Protocol.HTTP);
        broken.setHost("127.0.0.1");
        broken.setPort(closedPort);

        CountDownLatch latch = new CountDownLatch(1);
        repo.addOrUpdateProxy(broken, latch::countDown);
        assertTrue(latch.await(3, TimeUnit.SECONDS));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        long id = findProxyId(repo, "127.0.0.1", closedPort);
        assertTrue(id > 0);

        Intent intent = new Intent(context, ProxyDetailActivity.class);
        intent.putExtra(ProxyDetailActivity.EXTRA_PROXY_ID, id);
        try (ActivityController<ProxyDetailActivity> controller =
                     Robolectric.buildActivity(ProxyDetailActivity.class, intent).setup()) {
            ProxyDetailActivity activity = controller.get();
            for (int i = 0; i < 60; i++) {
                Thread.sleep(50);
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
            }
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            TextView tvTcp = activity.findViewById(R.id.tv_diag_tcp_status);
            TextView tvAuth = activity.findViewById(R.id.tv_diag_auth_status);
            TextView tvHttps = activity.findViewById(R.id.tv_diag_https_status);
            TextView tvVerdictSub = activity.findViewById(R.id.tv_verdict_subtitle);

            assertEquals(activity.getString(R.string.diag_tcp_refused), tvTcp.getText().toString());
            assertEquals(activity.getString(R.string.diag_status_skipped), tvAuth.getText().toString());
            assertEquals(activity.getString(R.string.diag_status_skipped), tvHttps.getText().toString());
            assertEquals(
                    activity.getString(R.string.err_proxy_test_refused, "127.0.0.1", closedPort),
                    tvVerdictSub.getText().toString()
            );
        }
    }

    @Test
    public void proxyListFragment_testAllShowsInlineSummaryAndErrorBadgeWithoutDetailedCardError() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        SettingsPreferences prefs = new SettingsPreferences(context);
        prefs.setAppLanguage(SettingsPreferences.AppLanguage.VI);
        SettingsPreferences.applyLanguage(SettingsPreferences.AppLanguage.VI);

        int closedPort;
        try (ServerSocket tmp = new ServerSocket(0)) {
            closedPort = tmp.getLocalPort();
        }
        ProxyRepository repo = ProxyRepository.getInstance(context);
        ProxyConfig broken = new ProxyConfig();
        broken.setName("Card Error Node");
        broken.setProtocol(ProxyConfig.Protocol.ALL);
        broken.setHost("127.0.0.1");
        broken.setPort(closedPort);

        CountDownLatch latch = new CountDownLatch(1);
        repo.addOrUpdateProxy(broken, latch::countDown);
        assertTrue(latch.await(3, TimeUnit.SECONDS));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        List<ProxyConfig> initialRepoList = repo.getProxies().getValue();
        assertNotNull(initialRepoList);
        assertFalse("Repository should contain newly inserted proxy", initialRepoList.isEmpty());

        ShadowToast.reset();
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            // Switch to Proxies tab so ProxyListFragment is active
            activity.onNavigateToTab(R.id.nav_proxies);
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            Fragment listFragment = null;
            for (Fragment f : activity.getSupportFragmentManager().getFragments()) {
                if (f instanceof ProxyListFragment) {
                    listFragment = f;
                    break;
                }
            }
            assertNotNull(listFragment);
            View root = listFragment.getView();
            assertNotNull(root);

            RecyclerView recycler = root.findViewById(R.id.recycler_proxies);
            @SuppressWarnings("unchecked")
            RecyclerView.Adapter<RecyclerView.ViewHolder> adapter =
                    (RecyclerView.Adapter<RecyclerView.ViewHolder>) recycler.getAdapter();
            assertNotNull(adapter);
            for (int i = 0; i < 40 && adapter.getItemCount() == 0; i++) {
                Thread.sleep(25);
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25));
            }
            com.example.ui.ProxyViewModel vm = new androidx.lifecycle.ViewModelProvider(activity)
                    .get(com.example.ui.ProxyViewModel.class);
            assertTrue("Adapter should display proxy before clicking Test All (adapterCount="
                    + adapter.getItemCount()
                    + ", repoSize=" + (repo.getProxies().getValue() != null ? repo.getProxies().getValue().size() : -1)
                    + ", curRepoSize=" + (ProxyRepository.getInstance(context).getProxies().getValue() != null
                        ? ProxyRepository.getInstance(context).getProxies().getValue().size() : -1)
                    + ", vmSize=" + (vm.getProxies().getValue() != null ? vm.getProxies().getValue().size() : -1)
                    + ", repoId=" + System.identityHashCode(repo)
                    + ", curRepoId=" + System.identityHashCode(ProxyRepository.getInstance(context))
                    + ", sameRepo=" + (repo.getProxies() == vm.getProxies())
                    + ", curSameVm=" + (ProxyRepository.getInstance(context).getProxies() == vm.getProxies())
                    + ", ctxApp=" + System.identityHashCode(context.getApplicationContext())
                    + ", actApp=" + System.identityHashCode(activity.getApplication())
                    + ", recyclerVis=" + recycler.getVisibility() + ")", adapter.getItemCount() > 0);

            String targetAddress = "127.0.0.1:" + closedPort;
            boolean foundUntestedDotBeforeTest = false;
            for (int i = 0; i < adapter.getItemCount(); i++) {
                RecyclerView.ViewHolder vh = adapter.onCreateViewHolder(recycler, adapter.getItemViewType(i));
                adapter.onBindViewHolder(vh, i);
                TextView tvAddress = vh.itemView.findViewById(R.id.tv_proxy_address);
                View untestedDot = vh.itemView.findViewById(R.id.view_untested_dot);
                TextView tvStatusBadge = vh.itemView.findViewById(R.id.tv_active_badge);
                if (tvAddress != null && tvAddress.getText().toString().contains(targetAddress)) {
                    assertNotNull(untestedDot);
                    assertEquals(View.VISIBLE, untestedDot.getVisibility());
                    assertEquals(View.GONE, tvStatusBadge.getVisibility());
                    foundUntestedDotBeforeTest = true;
                    break;
                }
            }
            assertTrue("Expected yellow dot on untested proxy card before test", foundUntestedDotBeforeTest);

            View btnTestAll = root.findViewById(R.id.btn_test_all);
            assertNotNull(btnTestAll);
            btnTestAll.performClick();

            String expectedSummary = activity.getString(R.string.msg_batch_test_summary,
                    initialRepoList.size(), 0, initialRepoList.size());
            TextView tvSummary = root.findViewById(R.id.tv_batch_test_summary);
            for (int i = 0; i < 100; i++) {
                Thread.sleep(50);
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
                if (expectedSummary.equals(tvSummary.getText().toString())) {
                    break;
                }
            }
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            // Must NOT pop up Toast notifications on testAll
            assertEquals(0, ShadowToast.shownToastCount());

            View summaryCard = root.findViewById(R.id.card_batch_test_summary);
            assertEquals(View.VISIBLE, summaryCard.getVisibility());
            assertEquals(expectedSummary, tvSummary.getText().toString());
            assertTrue(adapter.getItemCount() > 0);

            boolean foundExpectedErrorBadge = false;
            String expectedErrorBadge = activity.getString(R.string.filter_error);
            for (int i = 0; i < adapter.getItemCount(); i++) {
                RecyclerView.ViewHolder vh = adapter.onCreateViewHolder(recycler, adapter.getItemViewType(i));
                adapter.onBindViewHolder(vh, i);
                TextView tvAddress = vh.itemView.findViewById(R.id.tv_proxy_address);
                View untestedDot = vh.itemView.findViewById(R.id.view_untested_dot);
                TextView tvStatusBadge = vh.itemView.findViewById(R.id.tv_active_badge);
                if (tvAddress != null && tvAddress.getText().toString().contains(targetAddress)) {
                    assertNotNull(untestedDot);
                    assertEquals(View.GONE, untestedDot.getVisibility());
                    assertNotNull(tvStatusBadge);
                    assertEquals(View.VISIBLE, tvStatusBadge.getVisibility());
                    assertEquals(expectedErrorBadge, tvStatusBadge.getText().toString());
                    foundExpectedErrorBadge = true;
                    break;
                }
            }
            assertTrue("Expected card Error badge after failure", foundExpectedErrorBadge);
        }
    }

    @Test
    public void proxyListAdapter_replacesActiveBadgeWithErrorWhenFailedAndShowsYellowDotWhenUntested() {
        Context context = ApplicationProvider.getApplicationContext();
        SettingsPreferences prefs = new SettingsPreferences(context);
        prefs.setAppLanguage(SettingsPreferences.AppLanguage.EN);
        SettingsPreferences.applyLanguage(SettingsPreferences.AppLanguage.EN);

        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            RecyclerView parent = new RecyclerView(activity);
            parent.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(activity));
            com.example.ui.ProxyListAdapter adapter = new com.example.ui.ProxyListAdapter(
                    new com.example.ui.ProxyListAdapter.OnProxyActionListener() {
                        @Override public void onConnectProxy(ProxyConfig proxy) {}
                        @Override public void onTestProxy(ProxyConfig proxy) {}
                        @Override public void onEditProxy(ProxyConfig proxy) {}
                        @Override public void onDeleteProxy(ProxyConfig proxy) {}
                    });

            ProxyConfig failedActive = new ProxyConfig();
            failedActive.setId(10L);
            failedActive.setHost("10.0.0.1");
            failedActive.setPort(8080);
            failedActive.setLatencyMs(ProxyConfig.LATENCY_FAILED);

            ProxyConfig untestedActive = new ProxyConfig();
            untestedActive.setId(11L);
            untestedActive.setHost("10.0.0.2");
            untestedActive.setPort(8080);
            untestedActive.setLatencyMs(ProxyConfig.LATENCY_UNTESTED);

            ProxyConfig healthyActive = new ProxyConfig();
            healthyActive.setId(12L);
            healthyActive.setHost("10.0.0.3");
            healthyActive.setPort(8080);
            healthyActive.setLatencyMs(42L);

            adapter.submitList(java.util.Arrays.asList(failedActive, untestedActive, healthyActive));

            @SuppressWarnings("unchecked")
            RecyclerView.Adapter<RecyclerView.ViewHolder> rawAdapter =
                    (RecyclerView.Adapter<RecyclerView.ViewHolder>) (RecyclerView.Adapter<?>) adapter;

            adapter.setActiveProxyId(10L);
            RecyclerView.ViewHolder vh0 = rawAdapter.onCreateViewHolder(parent, 0);
            rawAdapter.onBindViewHolder(vh0, 0);
            TextView badge0 = vh0.itemView.findViewById(R.id.tv_active_badge);
            View dot0 = vh0.itemView.findViewById(R.id.view_untested_dot);
            assertEquals(View.VISIBLE, badge0.getVisibility());
            assertEquals("Error", badge0.getText().toString());
            assertEquals(View.GONE, dot0.getVisibility());

            adapter.setActiveProxyId(11L);
            RecyclerView.ViewHolder vh1 = rawAdapter.onCreateViewHolder(parent, 0);
            rawAdapter.onBindViewHolder(vh1, 1);
            TextView badge1 = vh1.itemView.findViewById(R.id.tv_active_badge);
            View dot1 = vh1.itemView.findViewById(R.id.view_untested_dot);
            assertEquals(View.GONE, badge1.getVisibility());
            assertEquals(View.VISIBLE, dot1.getVisibility());

            adapter.setActiveProxyId(12L);
            RecyclerView.ViewHolder vh2 = rawAdapter.onCreateViewHolder(parent, 0);
            rawAdapter.onBindViewHolder(vh2, 2);
            TextView badge2 = vh2.itemView.findViewById(R.id.tv_active_badge);
            View dot2 = vh2.itemView.findViewById(R.id.view_untested_dot);
            assertEquals(View.VISIBLE, badge2.getVisibility());
            assertEquals("Active", badge2.getText().toString());
            assertEquals(View.GONE, dot2.getVisibility());

            adapter.setActiveProxyId(99L);
            RecyclerView.ViewHolder vh3 = rawAdapter.onCreateViewHolder(parent, 0);
            rawAdapter.onBindViewHolder(vh3, 2);
            TextView badge3 = vh3.itemView.findViewById(R.id.tv_active_badge);
            View dot3 = vh3.itemView.findViewById(R.id.view_untested_dot);
            assertEquals(View.GONE, badge3.getVisibility());
            assertEquals(View.GONE, dot3.getVisibility());
        }
    }

    @Test
    public void proxyFailureFormatter_formatsHttpStatusFromRawOrCode() {
        Context context = ApplicationProvider.getApplicationContext();
        String msg = ProxyFailureFormatter.format(
                context,
                ProxyTester.FailureReason.HTTP_STATUS_ERROR,
                502,
                "10.0.0.1",
                8080,
                ProxyConfig.AuthMethod.NONE,
                "HTTP 502"
        );
        assertTrue(msg.contains("502"));
    }

    private static long findProxyId(ProxyRepository repo, String host, int port) {
        List<ProxyConfig> all = repo.getProxies().getValue();
        if (all == null) return -1L;
        for (ProxyConfig p : all) {
            if (host.equals(p.getHost()) && port == p.getPort()) {
                return p.getId();
            }
        }
        return -1L;
    }
}
