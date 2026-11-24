package com.example.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
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
import com.example.model.ConnectionStatus;
import com.example.model.IpDetails;
import com.example.model.ProxyConfig;
import com.example.network.IpLookupService;
import com.example.network.ProxyRuntimeManager;
import com.example.network.ProxySpeedTester;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Map-free Network Speed Test screen measuring real Ping, Jitter, Download (Mbps),
 * and Upload (Mbps) of the currently connected IP (routed via active VPN proxy or
 * direct device network) along with its IPv4, IPv6, ISP, and location metadata.
 */
public class NetworkSpeedActivity extends AppCompatActivity {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean testingRunning = new AtomicBoolean(false);

    private MaterialToolbar toolbar;
    private MaterialCardView cardSpeedHero;
    private ImageView ivHeroIcon;
    private TextView tvQualityTitle;
    private TextView tvUpdatedAt;
    private TextView tvRouteBadge;
    private LinearProgressIndicator progressSpeed;
    private TextView tvDownload;
    private TextView tvUpload;
    private TextView tvPing;
    private TextView tvJitter;
    private TextView tvTransferred;
    private TextView tvSpeedStatus;
    private MaterialButton btnRetest;

    private TextView tvRouteDetail;
    private View rowProxyServer;
    private TextView tvProxyServer;
    private TextView tvIpv4;
    private TextView tvIpv6;
    private TextView tvIsp;
    private TextView tvRegion;
    private TextView tvCity;
    private TextView tvCountry;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_network_speed);

        toolbar = findViewById(R.id.speed_toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        View root = findViewById(R.id.network_speed_root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            toolbar.setPadding(
                    toolbar.getPaddingLeft(),
                    sysBars.top,
                    toolbar.getPaddingRight(),
                    toolbar.getPaddingBottom()
            );
            root.setPadding(0, 0, 0, sysBars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        cardSpeedHero = findViewById(R.id.card_speed_hero);
        ivHeroIcon = findViewById(R.id.iv_speed_hero_icon);
        tvQualityTitle = findViewById(R.id.tv_speed_quality_title);
        tvUpdatedAt = findViewById(R.id.tv_speed_updated_at);
        tvRouteBadge = findViewById(R.id.tv_speed_route_badge);
        progressSpeed = findViewById(R.id.progress_network_speed);
        tvDownload = findViewById(R.id.tv_network_speed_download);
        tvUpload = findViewById(R.id.tv_network_speed_upload);
        tvPing = findViewById(R.id.tv_network_speed_ping);
        tvJitter = findViewById(R.id.tv_network_speed_jitter);
        tvTransferred = findViewById(R.id.tv_network_speed_transferred);
        tvSpeedStatus = findViewById(R.id.tv_network_speed_status);
        btnRetest = findViewById(R.id.btn_retest_network_speed);

        tvRouteDetail = findViewById(R.id.tv_speed_route_detail);
        rowProxyServer = findViewById(R.id.row_speed_proxy_server);
        tvProxyServer = findViewById(R.id.tv_speed_proxy_server);
        tvIpv4 = findViewById(R.id.tv_speed_ipv4);
        tvIpv6 = findViewById(R.id.tv_speed_ipv6);
        tvIsp = findViewById(R.id.tv_speed_isp);
        tvRegion = findViewById(R.id.tv_speed_region);
        tvCity = findViewById(R.id.tv_speed_city);
        tvCountry = findViewById(R.id.tv_speed_country);

        ImageButton btnCopyIpv4 = findViewById(R.id.btn_copy_speed_ipv4);
        ImageButton btnCopyIpv6 = findViewById(R.id.btn_copy_speed_ipv6);
        btnCopyIpv4.setOnClickListener(v -> copyIpText(tvIpv4.getText().toString()));
        btnCopyIpv6.setOnClickListener(v -> copyIpText(tvIpv6.getText().toString()));

        btnRetest.setOnClickListener(v -> startSpeedCheck());

        renderActiveRouteHeader(resolveActiveProxy());
        startSpeedCheck();
    }

    @Nullable
    private ProxyConfig resolveActiveProxy() {
        ConnectionStatus status = ProxyRuntimeManager.getInstance().getCurrentStatus();
        if (status != null && status.isRunning() && status.getActiveProxy() != null) {
            return status.getActiveProxy();
        }
        return null;
    }

    private void renderActiveRouteHeader(@Nullable ProxyConfig activeProxy) {
        if (activeProxy != null) {
            toolbar.setSubtitle(R.string.subtitle_network_speed_proxy);
            tvRouteBadge.setText(R.string.badge_proxy_routed);
            tvRouteBadge.setTextColor(ContextCompat.getColor(this, R.color.status_connected));
            tvRouteBadge.setBackgroundTintList(
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_connected_bg)));
            tvRouteDetail.setText(R.string.badge_proxy_routed);
            rowProxyServer.setVisibility(View.VISIBLE);
            tvProxyServer.setText(activeProxy.getDisplayTitle() + " • "
                    + activeProxy.getSchemePrefix() + activeProxy.getAddressDisplay());
        } else {
            toolbar.setSubtitle(R.string.subtitle_network_speed_direct);
            tvRouteBadge.setText(R.string.badge_direct_network);
            tvRouteBadge.setTextColor(ContextCompat.getColor(this, R.color.status_idle));
            tvRouteBadge.setBackgroundTintList(
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_idle_bg)));
            tvRouteDetail.setText(R.string.label_direct_no_proxy);
            rowProxyServer.setVisibility(View.GONE);
        }
    }

    private void startSpeedCheck() {
        if (!testingRunning.compareAndSet(false, true)) {
            return;
        }
        ProxyConfig activeProxy = resolveActiveProxy();
        renderActiveRouteHeader(activeProxy);

        progressSpeed.setVisibility(View.VISIBLE);
        btnRetest.setEnabled(false);
        tvQualityTitle.setText(R.string.label_speed_testing_now);
        tvQualityTitle.setTextColor(ContextCompat.getColor(this, R.color.md_theme_onSurface));
        ivHeroIcon.setImageResource(R.drawable.ic_speed);
        ivHeroIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_switching));
        cardSpeedHero.setStrokeColor(ContextCompat.getColor(this, R.color.md_theme_outline));

        tvDownload.setText("—");
        tvUpload.setText("—");
        tvPing.setText("—");
        tvJitter.setText("—");
        tvTransferred.setVisibility(View.GONE);
        tvSpeedStatus.setText(R.string.label_speed_testing_now);
        tvIpv4.setText(R.string.ip_checking_loading);

        executor.execute(() -> {
            try {
                IpDetails ipDetails = activeProxy != null
                        ? IpLookupService.lookupViaProxy(activeProxy)
                        : IpLookupService.lookupDirectOriginIp();

                if (!isFinishing() && !isDestroyed()) {
                    runOnUiThread(() -> renderIpInfo(ipDetails));
                }

                ProxySpeedTester.SpeedResult speed = ProxySpeedTester.measureCurrentNetworkSpeed(activeProxy);
                if (!isFinishing() && !isDestroyed()) {
                    runOnUiThread(() -> renderSpeedResult(speed, activeProxy != null));
                }
            } finally {
                testingRunning.set(false);
                if (!isFinishing() && !isDestroyed()) {
                    runOnUiThread(() -> {
                        progressSpeed.setVisibility(View.GONE);
                        btnRetest.setEnabled(true);
                    });
                }
            }
        });
    }

    private void renderIpInfo(IpDetails ip) {
        tvIpv4.setText(nonEmptyOrFallback(ip.getIpv4(), getString(R.string.ip_not_available)));
        tvIpv6.setText(nonEmptyOrFallback(ip.getIpv6(), getString(R.string.ip_not_available)));
        tvIsp.setText(nonEmptyOrFallback(ip.getIsp(), "—"));
        tvRegion.setText(nonEmptyOrFallback(ip.getRegion(), "—"));
        tvCity.setText(nonEmptyOrFallback(ip.getCity(), "—"));
        tvCountry.setText(formatCountry(ip));
    }

    private void renderSpeedResult(ProxySpeedTester.SpeedResult speed, boolean viaProxy) {
        String timeStr = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        tvUpdatedAt.setText(getString(R.string.label_speed_completed_at, timeStr));

        if (!speed.isSuccess()) {
            ivHeroIcon.setImageResource(R.drawable.ic_error_circle);
            ivHeroIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_error));
            tvQualityTitle.setText(R.string.quality_error);
            tvQualityTitle.setTextColor(ContextCompat.getColor(this, R.color.status_error));
            cardSpeedHero.setStrokeColor(ContextCompat.getColor(this, R.color.status_error));
            tvSpeedStatus.setText(speed.getErrorMessage().isEmpty()
                    ? getString(R.string.quality_error)
                    : speed.getErrorMessage());
            return;
        }

        tvDownload.setText(String.format(Locale.US, "%.2f Mbps", speed.getDownloadMbps()));
        tvUpload.setText(String.format(Locale.US, "%.2f Mbps", speed.getUploadMbps()));
        tvPing.setText(speed.getAvgPingMs() >= 0 ? (speed.getAvgPingMs() + " ms") : "—");
        tvJitter.setText(speed.getAvgPingMs() >= 0 ? (speed.getJitterMs() + " ms") : "—");

        tvTransferred.setVisibility(View.VISIBLE);
        tvTransferred.setText(getString(
                R.string.label_speed_transferred,
                formatBytes(speed.getBytesDownloaded()),
                formatBytes(speed.getBytesUploaded())
        ));

        double dl = speed.getDownloadMbps();
        long ping = speed.getAvgPingMs();
        if (dl >= 25.0 && (ping <= 0 || ping <= 120)) {
            ivHeroIcon.setImageResource(R.drawable.ic_check_circle);
            ivHeroIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_connected));
            tvQualityTitle.setText(R.string.quality_fast);
            tvQualityTitle.setTextColor(ContextCompat.getColor(this, R.color.status_connected));
            cardSpeedHero.setStrokeColor(ContextCompat.getColor(this, R.color.status_connected));
        } else if (dl >= 5.0 || (dl == 0.0 && ping > 0 && ping <= 150)) {
            ivHeroIcon.setImageResource(R.drawable.ic_speed);
            ivHeroIcon.setColorFilter(ContextCompat.getColor(this, R.color.md_theme_primary));
            tvQualityTitle.setText(R.string.quality_moderate);
            tvQualityTitle.setTextColor(ContextCompat.getColor(this, R.color.md_theme_primary));
            cardSpeedHero.setStrokeColor(ContextCompat.getColor(this, R.color.md_theme_primary));
        } else {
            ivHeroIcon.setImageResource(R.drawable.ic_speed);
            ivHeroIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_switching));
            tvQualityTitle.setText(R.string.quality_slow);
            tvQualityTitle.setTextColor(ContextCompat.getColor(this, R.color.status_switching));
            cardSpeedHero.setStrokeColor(ContextCompat.getColor(this, R.color.status_switching));
        }

        tvSpeedStatus.setText(viaProxy
                ? getString(R.string.label_speed_done)
                : getString(R.string.subtitle_network_speed_direct));
    }

    private void copyIpText(String value) {
        if (value == null || value.isEmpty() || "—".equals(value)
                || value.equals(getString(R.string.ip_not_available))
                || value.equals(getString(R.string.ip_checking_loading))) {
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("IP Address", value));
            Toast.makeText(this, R.string.msg_ip_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private static String nonEmptyOrFallback(String value, String fallback) {
        return (value != null && !value.trim().isEmpty()) ? value.trim() : fallback;
    }

    private static String formatCountry(IpDetails details) {
        if (details.getCountry().isEmpty()) return "—";
        if (details.getCountryCode().isEmpty()) return details.getCountry();
        return details.getCountry() + " (" + details.getCountryCode() + ")";
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0));
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
