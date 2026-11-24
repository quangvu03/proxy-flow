package com.example.ui;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.example.R;
import com.example.model.AppProxyRule;
import com.example.model.ConnectionStatus;
import com.example.model.ProxyConfig;
import com.google.android.material.button.MaterialButton;

import java.util.List;
import java.util.Locale;

/**
 * Primary Connection Dashboard Fragment showing the user-selected proxy status,
 * real-time VPN gateway telemetry, fixed-IP mode summary, and quick controls.
 */
public class ConnectFragment extends Fragment {

    public interface HostActions {
        void onRequestToggleVpn(@Nullable ProxyConfig proxy);
        void onRequestConnectProxy(long proxyId);
        void onNavigateToTab(int navItemId);
    }

    private ProxyViewModel viewModel;

    private TextView tvStatusBadge;
    private TextView tvActiveTitle;
    private TextView tvActiveAddress;
    private TextView tvLocalGateway;
    private TextView tvConnectionError;
    private MaterialButton btnToggleVpn;
    private MaterialButton btnOpenIpMap;
    private MaterialButton btnTestActive;
    private TextView tvExitIpValue;
    private TextView tvLatencyValue;
    private TextView tvAuthValue;
    private TextView tvBytesSent;
    private TextView tvBytesReceived;
    private TextView tvActiveConns;
    private TextView tvUptimeValue;
    private TextView tvPoolSummary;
    private TextView tvPoolStrategySummary;
    private MaterialButton btnGoToProxies;
    private TextView tvAppRulesSummary;
    private MaterialButton btnGoToApps;

    private ConnectionStatus latestStatus;
    private ProxyConfig latestSelectedProxy;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_connect, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(ProxyViewModel.class);

        tvStatusBadge = view.findViewById(R.id.tv_status_badge);
        tvActiveTitle = view.findViewById(R.id.tv_active_proxy_title);
        tvActiveAddress = view.findViewById(R.id.tv_active_proxy_address);
        tvLocalGateway = view.findViewById(R.id.tv_local_gateway);
        tvConnectionError = view.findViewById(R.id.tv_connection_error);
        btnToggleVpn = view.findViewById(R.id.btn_toggle_vpn);
        btnOpenIpMap = view.findViewById(R.id.btn_open_ip_map);
        btnTestActive = view.findViewById(R.id.btn_test_active);
        tvExitIpValue = view.findViewById(R.id.tv_exit_ip_value);
        tvLatencyValue = view.findViewById(R.id.tv_latency_value);
        tvAuthValue = view.findViewById(R.id.tv_auth_value);
        tvBytesSent = view.findViewById(R.id.tv_bytes_sent);
        tvBytesReceived = view.findViewById(R.id.tv_bytes_received);
        tvActiveConns = view.findViewById(R.id.tv_active_conns);
        tvUptimeValue = view.findViewById(R.id.tv_uptime_value);
        tvPoolSummary = view.findViewById(R.id.tv_pool_summary);
        tvPoolStrategySummary = view.findViewById(R.id.tv_pool_strategy_summary);
        btnGoToProxies = view.findViewById(R.id.btn_go_to_proxies);
        tvAppRulesSummary = view.findViewById(R.id.tv_app_rules_summary);
        btnGoToApps = view.findViewById(R.id.btn_go_to_apps);

        View cardDiagnostics = view.findViewById(R.id.card_node_diagnostics);
        if (cardDiagnostics != null) {
            cardDiagnostics.setOnClickListener(v -> openIpCheckScreen(false));
        }

        btnToggleVpn.setOnClickListener(v -> {
            if (getActivity() instanceof HostActions) {
                ((HostActions) getActivity()).onRequestToggleVpn(latestSelectedProxy);
            }
        });

        btnOpenIpMap.setOnClickListener(v -> openIpCheckScreen(false));

        btnTestActive.setOnClickListener(v -> openNetworkSpeedScreen());

        btnGoToProxies.setOnClickListener(v -> {
            if (getActivity() instanceof HostActions) {
                ((HostActions) getActivity()).onNavigateToTab(R.id.nav_proxies);
            }
        });

        btnGoToApps.setOnClickListener(v -> {
            if (getActivity() instanceof HostActions) {
                ((HostActions) getActivity()).onNavigateToTab(R.id.nav_apps);
            }
        });

        viewModel.getConnectionStatus().observe(getViewLifecycleOwner(), status -> {
            latestStatus = status;
            renderStatus();
        });

        viewModel.getSelectedProxy().observe(getViewLifecycleOwner(), proxy -> {
            latestSelectedProxy = proxy;
            renderStatus();
        });

        viewModel.getProxies().observe(getViewLifecycleOwner(), this::renderPoolSummary);
        viewModel.getAppProxyRule().observe(getViewLifecycleOwner(), this::renderAppRuleSummary);
    }

    private void openIpCheckScreen(boolean autoCheckCurrentIp) {
        Intent intent = new Intent(requireContext(), IpCheckActivity.class);
        intent.putExtra(IpCheckActivity.EXTRA_AUTO_CHECK_CURRENT_IP, autoCheckCurrentIp);
        startActivity(intent);
    }

    private void openNetworkSpeedScreen() {
        startActivity(new Intent(requireContext(), NetworkSpeedActivity.class));
    }

    private void renderStatus() {
        if (!isAdded()) return;
        ConnectionStatus status = latestStatus != null ? latestStatus : ConnectionStatus.disconnected(latestSelectedProxy);
        ProxyConfig proxy = status.isRunning() && status.getActiveProxy() != null
                ? status.getActiveProxy()
                : latestSelectedProxy;

        switch (status.getState()) {
            case CONNECTED:
                tvStatusBadge.setText(R.string.status_connected);
                tvStatusBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_connected));
                tvStatusBadge.setBackgroundTintList(
                        ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.status_connected_bg)));
                btnToggleVpn.setEnabled(true);
                btnToggleVpn.setText(R.string.action_disconnect);
                btnToggleVpn.setBackgroundTintList(
                        ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.status_error)));
                btnToggleVpn.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white));
                btnToggleVpn.setIconTint(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), android.R.color.white)));
                break;

            case CONNECTING:
                tvStatusBadge.setText(R.string.status_connecting);
                tvStatusBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_switching));
                tvStatusBadge.setBackgroundTintList(
                        ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.status_switching_bg)));
                btnToggleVpn.setEnabled(true);
                btnToggleVpn.setText(R.string.action_disconnect);
                break;

            case ERROR:
                tvStatusBadge.setText(R.string.status_error);
                tvStatusBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_error));
                tvStatusBadge.setBackgroundTintList(
                        ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.status_error_bg)));
                resetConnectButton();
                break;

            case DISCONNECTED:
            default:
                tvStatusBadge.setText(R.string.status_disconnected);
                tvStatusBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_idle));
                tvStatusBadge.setBackgroundTintList(
                        ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.status_idle_bg)));
                resetConnectButton();
                break;
        }

        if (!status.getLastError().isEmpty()) {
            tvConnectionError.setVisibility(View.VISIBLE);
            tvConnectionError.setText(status.getLastError());
        } else {
            tvConnectionError.setVisibility(View.GONE);
        }

        if (status.isRunning() && status.getLocalProxyPort() > 0) {
            tvLocalGateway.setVisibility(View.VISIBLE);
            tvLocalGateway.setText(getString(R.string.label_local_gateway, status.getLocalProxyPort()));
        } else {
            tvLocalGateway.setVisibility(View.GONE);
        }

        if (proxy != null) {
            tvActiveTitle.setText(proxy.getDisplayTitle());
            tvActiveAddress.setText(proxy.getSchemePrefix() + proxy.getAddressDisplay());
            tvExitIpValue.setText(proxy.getExitIp().isEmpty() ? "—" : proxy.getExitIp());
            tvAuthValue.setText(proxy.hasAuth() ? proxy.getUsername() : getString(R.string.badge_auth_no));

            long lat = proxy.getLatencyMs();
            if (lat > 0) {
                tvLatencyValue.setText(getString(R.string.latency_ms, lat));
            } else if (lat == ProxyConfig.LATENCY_FAILED) {
                tvLatencyValue.setText(R.string.latency_failed);
            } else {
                tvLatencyValue.setText(R.string.latency_untested);
            }
        } else {
            tvActiveTitle.setText(R.string.label_no_proxy_selected);
            tvActiveAddress.setText(R.string.label_select_or_add_hint);
            tvExitIpValue.setText("—");
            tvLatencyValue.setText(R.string.latency_untested);
            tvAuthValue.setText("—");
        }

        tvBytesSent.setText(formatBytes(status.getBytesOut()));
        tvBytesReceived.setText(formatBytes(status.getBytesIn()));
        tvActiveConns.setText(String.valueOf(status.getActiveConnections()));

        if (status.isRunning() && status.getConnectedSinceMs() > 0) {
            long sec = Math.max(0L, (System.currentTimeMillis() - status.getConnectedSinceMs()) / 1000L);
            tvUptimeValue.setText(formatDuration(sec));
        } else {
            tvUptimeValue.setText("00:00");
        }
    }

    private void resetConnectButton() {
        btnToggleVpn.setEnabled(true);
        btnToggleVpn.setText(R.string.action_connect);
        btnToggleVpn.setBackgroundTintList(ColorStateList.valueOf(0xFF14B8A6));
        btnToggleVpn.setTextColor(0xFF042F2E);
        btnToggleVpn.setIconTint(ColorStateList.valueOf(0xFF042F2E));
    }

    private void renderPoolSummary(List<ProxyConfig> proxies) {
        int total = proxies != null ? proxies.size() : 0;
        tvPoolSummary.setText(getString(R.string.label_saved_proxies_count, total));
        tvPoolStrategySummary.setText(R.string.label_manual_mode_desc);
    }

    private void renderAppRuleSummary(AppProxyRule rule) {
        if (rule == null || tvAppRulesSummary == null) return;
        int count = rule.getSelectedPackages().size();
        if (rule.getMode() == AppProxyRule.Mode.ALLOW_SELECTED) {
            tvAppRulesSummary.setText(getString(R.string.label_app_rules_allow_summary, count));
        } else if (count == 0) {
            tvAppRulesSummary.setText(R.string.label_app_rules_all_via_proxy);
        } else {
            tvAppRulesSummary.setText(getString(R.string.label_app_rules_block_summary, count));
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private static String formatDuration(long totalSeconds) {
        long hours = totalSeconds / 3600L;
        long mins = (totalSeconds % 3600L) / 60L;
        long secs = totalSeconds % 60L;
        if (hours > 0) {
            return String.format(Locale.US, "%02d:%02d:%02d", hours, mins, secs);
        }
        return String.format(Locale.US, "%02d:%02d", mins, secs);
    }
}
