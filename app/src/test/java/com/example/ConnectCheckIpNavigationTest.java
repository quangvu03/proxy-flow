package com.example;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.core.app.ApplicationProvider;

import com.example.model.ConnectionStatus;
import com.example.model.IpDetails;
import com.example.model.ProxyConfig;
import com.example.network.ProxyRuntimeManager;
import com.example.ui.IpCheckActivity;
import com.example.ui.IpCheckFragment;
import com.example.ui.NetworkSpeedActivity;
import com.example.ui.ProxyViewModel;
import com.google.android.material.button.MaterialButton;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.osmdroid.views.MapView;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowToast;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Robolectric tests verifying that:
 * 1) Tapping the speed-test button on ConnectFragment opens NetworkSpeedActivity without a Toast.
 * 2) NetworkSpeedActivity displays speed metrics and connected IP metadata with NO MapView.
 * 3) Tapping View IP & Map opens IpCheckActivity and auto-check triggers current IP refresh.
 */
@RunWith(RobolectricTestRunner.class)
public class ConnectCheckIpNavigationTest {

    @Test
    public void clickSpeedTestButton_navigatesToNetworkSpeedActivityWithoutToast() {
        ShadowToast.reset();
        MainActivity activity = Robolectric.buildActivity(MainActivity.class)
                .setup()
                .get();

        MaterialButton btnSpeedTest = activity.findViewById(R.id.btn_test_active);
        assertNotNull(btnSpeedTest);
        assertEquals(activity.getString(R.string.action_test_active), btnSpeedTest.getText().toString());

        btnSpeedTest.performClick();

        assertEquals(0, ShadowToast.shownToastCount());
        Intent nextIntent = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(nextIntent);
        assertNotNull(nextIntent.getComponent());
        assertEquals(NetworkSpeedActivity.class.getName(), nextIntent.getComponent().getClassName());
    }

    @Test
    public void networkSpeedActivity_displaysSpeedAndConnectedIpInfo_withZeroMapViews() {
        NetworkSpeedActivity activity = Robolectric.buildActivity(NetworkSpeedActivity.class)
                .setup()
                .get();

        assertNotNull(activity.findViewById(R.id.tv_network_speed_download));
        assertNotNull(activity.findViewById(R.id.tv_network_speed_upload));
        assertNotNull(activity.findViewById(R.id.tv_network_speed_ping));
        assertNotNull(activity.findViewById(R.id.tv_network_speed_jitter));
        assertNotNull(activity.findViewById(R.id.tv_speed_ipv4));
        assertNotNull(activity.findViewById(R.id.tv_speed_ipv6));
        assertNotNull(activity.findViewById(R.id.tv_speed_isp));
        assertNotNull(activity.findViewById(R.id.tv_speed_region));
        assertNotNull(activity.findViewById(R.id.tv_speed_city));
        assertNotNull(activity.findViewById(R.id.tv_speed_country));
        assertNotNull(activity.findViewById(R.id.tv_speed_route_detail));
        assertNotNull(activity.findViewById(R.id.btn_retest_network_speed));

        View root = activity.findViewById(R.id.network_speed_root);
        assertNotNull(root);
        assertFalse("NetworkSpeedActivity must not contain any MapView", containsMapView(root));
    }

    @Test
    public void networkSpeedActivity_whenVpnConnected_showsConnectedProxyDetails() {
        ProxyConfig proxy = new ProxyConfig(1L, "SG Fast", "103.50.12.88", 8080, "user", "pass",
                true, 35L, "103.50.12.88", "SG", 0L, 0, 0);
        ProxyRuntimeManager runtime = ProxyRuntimeManager.getInstance();
        try {
            runtime.postStatus(new ConnectionStatus(
                    ConnectionStatus.State.CONNECTED,
                    proxy,
                    10808,
                    0L,
                    0L,
                    1,
                    "",
                    System.currentTimeMillis()
            ));
            // Wait a moment or set synchronously by observing main looper flush
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

            NetworkSpeedActivity activity = Robolectric.buildActivity(NetworkSpeedActivity.class)
                    .setup()
                    .get();

            View proxyRow = activity.findViewById(R.id.row_speed_proxy_server);
            TextView tvProxyServer = activity.findViewById(R.id.tv_speed_proxy_server);
            TextView tvRouteBadge = activity.findViewById(R.id.tv_speed_route_badge);
            assertEquals(View.VISIBLE, proxyRow.getVisibility());
            assertTrue(tvProxyServer.getText().toString().contains("103.50.12.88:8080"));
            assertEquals(activity.getString(R.string.badge_proxy_routed), tvRouteBadge.getText().toString());
        } finally {
            runtime.postDisconnected(null, "");
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        }
    }

    @Test
    public void ipCheckActivity_withAutoCheckExtra_passesArgAndTriggersCurrentIpCheckEvenIfCached() {
        Intent launchIntent = new Intent(
                ApplicationProvider.getApplicationContext(),
                IpCheckActivity.class
        );
        launchIntent.putExtra(IpCheckActivity.EXTRA_AUTO_CHECK_CURRENT_IP, true);

        ActivityController<IpCheckActivity> controller =
                Robolectric.buildActivity(IpCheckActivity.class, launchIntent).create();
        IpCheckActivity activity = controller.get();

        ProxyViewModel viewModel = new ViewModelProvider(activity).get(ProxyViewModel.class);
        AtomicBoolean sawCheckingTrue = new AtomicBoolean(false);
        viewModel.isCheckingIp().observeForever(checking -> {
            if (Boolean.TRUE.equals(checking)) {
                sawCheckingTrue.set(true);
            }
        });

        IpDetails cached = new IpDetails("1.1.1.1", "", "Hanoi", "Hanoi",
                "Vietnam", "VN", "ISP", 21.0285, 105.8542, true, false, "");
        @SuppressWarnings("unchecked")
        MutableLiveData<IpDetails.IpComparisonResult> cachedLive =
                (MutableLiveData<IpDetails.IpComparisonResult>) viewModel.getIpComparison();
        cachedLive.setValue(new IpDetails.IpComparisonResult(cached, cached, 1L));

        controller.start().resume();

        Fragment fragment = activity.getSupportFragmentManager()
                .findFragmentById(R.id.ip_check_fragment_container);
        assertTrue(fragment instanceof IpCheckFragment);
        assertNotNull(fragment.getArguments());
        assertTrue(fragment.getArguments().getBoolean(IpCheckFragment.ARG_AUTO_CHECK_CURRENT_IP, false));
        assertTrue(sawCheckingTrue.get());
    }

    private static boolean containsMapView(View view) {
        if (view instanceof MapView) {
            return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                if (containsMapView(vg.getChildAt(i))) {
                    return true;
                }
            }
        }
        return false;
    }
}
