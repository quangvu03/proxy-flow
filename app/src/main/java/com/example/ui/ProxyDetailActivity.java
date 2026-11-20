package com.example.ui;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.R;
import com.example.data.ProxyRepository;
import com.example.model.IpDetails;
import com.example.model.ProxyConfig;
import com.example.model.ProxyLogEntry;
import com.example.network.IpLookupService;
import com.example.network.ProxySpeedTester;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dedicated Proxy Inspection & Speed Test screen in 100% Java.
 * Evaluates whether the selected proxy actually works (TCP, Auth, HTTPS CONNECT),
 * measures Ping / Download / Upload speeds through the proxy, and resolves its
 * exit IPv4, IPv6, province/region, city, country, ISP, and map location.
 */
public class ProxyDetailActivity extends AppCompatActivity {

    public static final String EXTRA_PROXY_ID = "extra_proxy_id";
    public static final String EXTRA_CONNECT_PROXY_ID = "extra_connect_proxy_id";

    private ProxyRepository repository;
    private ProxyConfig proxy;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final AtomicBoolean fullTestRunning = new AtomicBoolean(false);
    private final AtomicBoolean speedTestRunning = new AtomicBoolean(false);

    private MaterialCardView cardVerdict;
    private ImageView ivVerdictIcon;
    private TextView tvVerdictTitle;
    private TextView tvVerdictSubtitle;
    private LinearProgressIndicator progressDetailAll;
    private MaterialButton btnRetestAll;
    private MaterialButton btnConnectThis;

    private TextView tvDiagTcpLabel;
    private TextView tvDiagTcpStatus;
    private TextView tvDiagAuthStatus;
    private TextView tvDiagHttpsStatus;

    private TextView tvSpeedPing;
    private TextView tvSpeedDownload;
    private TextView tvSpeedUpload;
    private LinearProgressIndicator progressSpeedTest;
    private TextView tvSpeedStatus;
    private MaterialButton btnRunSpeedTest;

    private TextView tvDetailIpv4;
    private TextView tvDetailIpv6;
    private TextView tvDetailRegion;
    private TextView tvDetailCity;
    private TextView tvDetailCountry;
    private TextView tvDetailIsp;
    private MapView mapView;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        MapHelper.initConfig(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_proxy_detail);

        repository = ProxyRepository.getInstance(this);

        MaterialToolbar toolbar = findViewById(R.id.detail_toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        View root = findViewById(R.id.detail_root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            toolbar.setPadding(toolbar.getPaddingLeft(), sysBars.top, toolbar.getPaddingRight(), toolbar.getPaddingBottom());
            root.setPadding(0, 0, 0, sysBars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        cardVerdict = findViewById(R.id.card_verdict);
        ivVerdictIcon = findViewById(R.id.iv_verdict_icon);
        tvVerdictTitle = findViewById(R.id.tv_verdict_title);
        tvVerdictSubtitle = findViewById(R.id.tv_verdict_subtitle);
        progressDetailAll = findViewById(R.id.progress_detail_all);
        btnRetestAll = findViewById(R.id.btn_retest_all);
        btnConnectThis = findViewById(R.id.btn_connect_this_proxy);

        tvDiagTcpLabel = findViewById(R.id.tv_diag_tcp_label);
        tvDiagTcpStatus = findViewById(R.id.tv_diag_tcp_status);
        tvDiagAuthStatus = findViewById(R.id.tv_diag_auth_status);
        tvDiagHttpsStatus = findViewById(R.id.tv_diag_https_status);

        tvSpeedPing = findViewById(R.id.tv_speed_ping);
        tvSpeedDownload = findViewById(R.id.tv_speed_download);
        tvSpeedUpload = findViewById(R.id.tv_speed_upload);
        progressSpeedTest = findViewById(R.id.progress_speed_test);
        tvSpeedStatus = findViewById(R.id.tv_speed_status);
        btnRunSpeedTest = findViewById(R.id.btn_run_speed_test);

        tvDetailIpv4 = findViewById(R.id.tv_detail_ipv4);
        tvDetailIpv6 = findViewById(R.id.tv_detail_ipv6);
        tvDetailRegion = findViewById(R.id.tv_detail_region);
        tvDetailCity = findViewById(R.id.tv_detail_city);
        tvDetailCountry = findViewById(R.id.tv_detail_country);
        tvDetailIsp = findViewById(R.id.tv_detail_isp);
        ImageButton btnCopyIpv4 = findViewById(R.id.btn_copy_detail_ipv4);
        ImageButton btnCopyIpv6 = findViewById(R.id.btn_copy_detail_ipv6);
        mapView = findViewById(R.id.map_detail_proxy);
        ImageButton btnDetailZoomIn = findViewById(R.id.btn_detail_zoom_in);
        ImageButton btnDetailZoomOut = findViewById(R.id.btn_detail_zoom_out);
        MapHelper.configureMapView(this, mapView, 5.2);
        MapHelper.attachZoomControls(mapView, btnDetailZoomIn, btnDetailZoomOut);

        long proxyId = getIntent().getLongExtra(EXTRA_PROXY_ID, -1L);
        if (proxyId <= 0) {
            finish();
            return;
        }

        btnRetestAll.setOnClickListener(v -> runFullInspection());
        btnRunSpeedTest.setOnClickListener(v -> runSpeedTestOnly());
        btnCopyIpv4.setOnClickListener(v -> copyText(tvDetailIpv4.getText().toString()));
        btnCopyIpv6.setOnClickListener(v -> copyText(tvDetailIpv6.getText().toString()));
        btnConnectThis.setOnClickListener(v -> {
            Intent res = new Intent();
            res.putExtra(EXTRA_CONNECT_PROXY_ID, proxyId);
            setResult(RESULT_OK, res);
            finish();
        });

        executor.execute(() -> {
            ProxyConfig loaded = repository.getProxyByIdSync(proxyId);
            if (loaded == null) {
                runOnUiThread(this::finish);
                return;
            }
            proxy = loaded;
            runOnUiThread(() -> {
                toolbar.setTitle(proxy.getDisplayTitle());
                toolbar.setSubtitle("http://" + proxy.getAddressDisplay());
                tvDiagTcpLabel.setText(getString(R.string.diag_tcp_connect, proxy.getAddressDisplay()));
                runFullInspection();
            });
        });
    }

    private void runFullInspection() {
        if (proxy == null || !fullTestRunning.compareAndSet(false, true)) {
            return;
        }

        // Reset UI to checking state
        progressDetailAll.setVisibility(View.VISIBLE);
        btnRetestAll.setEnabled(false);
        btnRunSpeedTest.setEnabled(false);
        tvVerdictTitle.setText(R.string.verdict_testing);
        tvVerdictSubtitle.setText(R.string.verdict_testing_sub);
        ivVerdictIcon.setImageResource(R.drawable.ic_speed);
        ivVerdictIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_switching));
        cardVerdict.setStrokeColor(ContextCompat.getColor(this, R.color.md_theme_outline));

        setBadge(tvDiagTcpStatus, getString(R.string.diag_status_pending), R.color.status_idle, R.color.status_idle_bg);
        setBadge(tvDiagAuthStatus, getString(R.string.diag_status_pending), R.color.status_idle, R.color.status_idle_bg);
        setBadge(tvDiagHttpsStatus, getString(R.string.diag_status_pending), R.color.status_idle, R.color.status_idle_bg);

        tvSpeedPing.setText("—");
        tvSpeedDownload.setText("—");
        tvSpeedUpload.setText("—");
        progressSpeedTest.setVisibility(View.VISIBLE);
        tvSpeedStatus.setText(R.string.label_speed_testing);

        executor.execute(() -> {
            try {
                // 1. Technical Diagnostics (TCP + Auth + HTTPS Tunnel)
                ProxySpeedTester.DiagnosticResult diag = ProxySpeedTester.runDiagnostics(proxy);
                if (isFinishing() || isDestroyed()) return;
                runOnUiThread(() -> renderDiagnostics(diag));

                if (!diag.isUsable()) {
                    repository.updateTelemetrySync(proxy.getId(), ProxyConfig.LATENCY_FAILED,
                            proxy.getExitIp(), proxy.getCountry(), proxy.getFailCount() + 1);
                    repository.addLog(ProxyLogEntry.Level.ERROR,
                            "Kiểm tra chi tiết " + proxy.getAddressDisplay() + ": Không xài được (" + diag.getErrorMessage() + ")");
                    runOnUiThread(() -> {
                        progressSpeedTest.setVisibility(View.GONE);
                        tvSpeedStatus.setText(R.string.label_speed_failed);
                        renderVerdict(false, diag.getErrorMessage());
                    });
                    return;
                }

                // 2. IP & Geolocation check through this proxy
                IpDetails ipInfo = IpLookupService.lookupViaProxy(proxy);
                if (!isFinishing() && !isDestroyed()) {
                    runOnUiThread(() -> renderIpDetails(ipInfo));
                }

                // 3. Speed test (Ping, Download Mbps, Upload Mbps)
                ProxySpeedTester.SpeedResult speed = ProxySpeedTester.measureSpeed(proxy);
                long finalLatency = speed.getAvgPingMs() > 0 ? speed.getAvgPingMs() : diag.getTcpLatencyMs();
                repository.updateTelemetrySync(
                        proxy.getId(),
                        finalLatency,
                        ipInfo.getIpv4(),
                        ipInfo.getCountry(),
                        0
                );
                repository.addLog(ProxyLogEntry.Level.SUCCESS,
                        String.format(Locale.US, "Kiểm tra OK %s • Ping %dms • DL %.2f Mbps",
                                proxy.getAddressDisplay(), finalLatency, speed.getDownloadMbps()));

                if (!isFinishing() && !isDestroyed()) {
                    runOnUiThread(() -> {
                        renderSpeedResult(speed);
                        renderVerdict(true, diag.getErrorMessage());
                    });
                }
            } finally {
                fullTestRunning.set(false);
                if (!isFinishing() && !isDestroyed()) {
                    runOnUiThread(() -> {
                        progressDetailAll.setVisibility(View.GONE);
                        btnRetestAll.setEnabled(true);
                        btnRunSpeedTest.setEnabled(true);
                    });
                }
            }
        });
    }

    private void runSpeedTestOnly() {
        if (proxy == null || fullTestRunning.get() || !speedTestRunning.compareAndSet(false, true)) {
            return;
        }
        progressSpeedTest.setVisibility(View.VISIBLE);
        tvSpeedStatus.setText(R.string.label_speed_testing);
        btnRunSpeedTest.setEnabled(false);

        executor.execute(() -> {
            try {
                ProxySpeedTester.SpeedResult speed = ProxySpeedTester.measureSpeed(proxy);
                if (!isFinishing() && !isDestroyed()) {
                    runOnUiThread(() -> renderSpeedResult(speed));
                }
            } finally {
                speedTestRunning.set(false);
                if (!isFinishing() && !isDestroyed()) {
                    runOnUiThread(() -> {
                        progressSpeedTest.setVisibility(View.GONE);
                        btnRunSpeedTest.setEnabled(true);
                    });
                }
            }
        });
    }

    private void renderDiagnostics(ProxySpeedTester.DiagnosticResult diag) {
        if (diag.isTcpReachable()) {
            setBadge(tvDiagTcpStatus, getString(R.string.diag_status_ok) + " (" + diag.getTcpLatencyMs() + "ms)",
                    R.color.status_connected, R.color.status_connected_bg);
        } else {
            setBadge(tvDiagTcpStatus, getString(R.string.diag_status_fail),
                    R.color.status_error, R.color.status_error_bg);
        }

        switch (diag.getAuthState()) {
            case VALID:
                setBadge(tvDiagAuthStatus, getString(R.string.diag_auth_ok),
                        R.color.status_connected, R.color.status_connected_bg);
                break;
            case NOT_REQUIRED:
                setBadge(tvDiagAuthStatus, getString(R.string.diag_auth_none),
                        R.color.status_connected, R.color.status_connected_bg);
                break;
            case FAILED_407:
                setBadge(tvDiagAuthStatus, getString(R.string.diag_auth_failed),
                        R.color.status_error, R.color.status_error_bg);
                break;
            case UNKNOWN:
            default:
                setBadge(tvDiagAuthStatus, getString(R.string.diag_status_fail),
                        R.color.status_idle, R.color.status_idle_bg);
                break;
        }

        if (diag.isHttpsTunnelOk()) {
            setBadge(tvDiagHttpsStatus, getString(R.string.diag_status_ok),
                    R.color.status_connected, R.color.status_connected_bg);
        } else {
            setBadge(tvDiagHttpsStatus, getString(R.string.diag_status_fail),
                    R.color.status_error, R.color.status_error_bg);
        }
    }

    private void renderVerdict(boolean usable, String note) {
        if (usable) {
            ivVerdictIcon.setImageResource(R.drawable.ic_check_circle);
            ivVerdictIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_connected));
            tvVerdictTitle.setText(R.string.verdict_usable);
            tvVerdictTitle.setTextColor(ContextCompat.getColor(this, R.color.status_connected));
            tvVerdictSubtitle.setText(note != null && !note.isEmpty() ? note : getString(R.string.verdict_usable_sub));
            cardVerdict.setStrokeColor(ContextCompat.getColor(this, R.color.status_connected));
        } else {
            ivVerdictIcon.setImageResource(R.drawable.ic_error_circle);
            ivVerdictIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_error));
            tvVerdictTitle.setText(R.string.verdict_unusable);
            tvVerdictTitle.setTextColor(ContextCompat.getColor(this, R.color.status_error));
            tvVerdictSubtitle.setText(note != null && !note.isEmpty() ? note : getString(R.string.verdict_unusable_sub));
            cardVerdict.setStrokeColor(ContextCompat.getColor(this, R.color.status_error));
        }
    }

    private void renderSpeedResult(ProxySpeedTester.SpeedResult speed) {
        progressSpeedTest.setVisibility(View.GONE);
        if (!speed.isSuccess()) {
            tvSpeedStatus.setText(speed.getErrorMessage().isEmpty()
                    ? getString(R.string.label_speed_failed)
                    : speed.getErrorMessage());
            return;
        }
        tvSpeedPing.setText(speed.getAvgPingMs() + " ms");
        tvSpeedDownload.setText(String.format(Locale.US, "%.2f Mbps", speed.getDownloadMbps()));
        tvSpeedUpload.setText(String.format(Locale.US, "%.2f Mbps", speed.getUploadMbps()));
        tvSpeedStatus.setText(getString(R.string.label_speed_done));
    }

    private void renderIpDetails(IpDetails ip) {
        tvDetailIpv4.setText(ip.getIpv4().isEmpty() ? getString(R.string.ip_not_available) : ip.getIpv4());
        tvDetailIpv6.setText(ip.getIpv6().isEmpty() ? getString(R.string.ip_not_available) : ip.getIpv6());
        tvDetailRegion.setText(ip.getRegion().isEmpty() ? "—" : ip.getRegion());
        tvDetailCity.setText(ip.getCity().isEmpty() ? "—" : ip.getCity());
        String countryText = ip.getCountry().isEmpty()
                ? "—"
                : (ip.getCountryCode().isEmpty() ? ip.getCountry() : ip.getCountry() + " (" + ip.getCountryCode() + ")");
        tvDetailCountry.setText(countryText);
        tvDetailIsp.setText(ip.getIsp().isEmpty() ? "—" : ip.getIsp());

        mapView.getOverlays().clear();
        MapHelper.addVietnamSovereigntyOverlay(this, mapView);
        if (ip.hasCoordinates()) {
            GeoPoint pt = new GeoPoint(ip.getLatitude(), ip.getLongitude());
            MapHelper.addPrecisionHalo(
                    mapView,
                    pt,
                    600.0,
                    Color.argb(40, 234, 67, 53),
                    Color.argb(165, 234, 67, 53)
            );
            Marker marker = new Marker(mapView);
            marker.setPosition(pt);
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            marker.setIcon(ContextCompat.getDrawable(this, R.drawable.ic_map_marker_proxy));
            marker.setTitle(ip.getIpv4());
            marker.setSnippet(ip.getLocationSummary());
            mapView.getOverlays().add(marker);
            marker.showInfoWindow();
            MapHelper.focusOnPoint(mapView, pt, 14.5);
        } else {
            mapView.invalidate();
        }
    }

    private void setBadge(TextView badge, String text, int textColorRes, int bgColorRes) {
        badge.setText(text);
        badge.setTextColor(ContextCompat.getColor(this, textColorRes));
        badge.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, bgColorRes)));
    }

    private void copyText(String val) {
        if (val == null || val.isEmpty() || "—".equals(val) || val.equals(getString(R.string.ip_not_available))) {
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Proxy IP", val));
            Toast.makeText(this, R.string.msg_ip_copied, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mapView != null) mapView.onResume();
    }

    @Override
    protected void onPause() {
        if (mapView != null) mapView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (mapView != null) {
            mapView.onDetach();
            mapView = null;
        }
        executor.shutdownNow();
        super.onDestroy();
    }
}
