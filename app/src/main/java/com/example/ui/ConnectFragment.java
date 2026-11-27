package com.example.ui;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.R;
import com.example.model.AppProxyRule;
import com.example.model.ConnectionStatus;
import com.example.model.ProxyConfig;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicator;

import java.util.ArrayList;
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

    private View layoutActiveProxySelector;
    private TextView tvStatusBadge;
    private TextView tvActiveTitle;
    private TextView tvActiveAddress;
    private TextView tvLocalGateway;
    private TextView tvConnectionError;
    private MaterialButton btnToggleVpn;
    private View layoutConnectLoading;
    private CircularProgressIndicator progressConnectVpn;
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
    private List<ProxyConfig> latestProxies = new ArrayList<>();
    @Nullable
    private AlertDialog proxyPickerDialog;

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

        layoutActiveProxySelector = view.findViewById(R.id.layout_active_proxy_selector);
        tvStatusBadge = view.findViewById(R.id.tv_status_badge);
        tvActiveTitle = view.findViewById(R.id.tv_active_proxy_title);
        tvActiveAddress = view.findViewById(R.id.tv_active_proxy_address);
        tvLocalGateway = view.findViewById(R.id.tv_local_gateway);
        tvConnectionError = view.findViewById(R.id.tv_connection_error);
        btnToggleVpn = view.findViewById(R.id.btn_toggle_vpn);
        layoutConnectLoading = view.findViewById(R.id.layout_connect_loading);
        progressConnectVpn = view.findViewById(R.id.progress_connect_vpn);
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

        if (layoutActiveProxySelector != null) {
            layoutActiveProxySelector.setOnClickListener(v -> showSelectProxyDialog());
        }

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

        viewModel.getProxies().observe(getViewLifecycleOwner(), proxies -> {
            latestProxies = proxies != null ? proxies : new ArrayList<>();
            renderPoolSummary(latestProxies);
        });
        viewModel.getAppProxyRule().observe(getViewLifecycleOwner(), this::renderAppRuleSummary);
    }

    @Override
    public void onDestroyView() {
        if (proxyPickerDialog != null && proxyPickerDialog.isShowing()) {
            proxyPickerDialog.dismiss();
        }
        proxyPickerDialog = null;
        super.onDestroyView();
    }

    private void showSelectProxyDialog() {
        if (!isAdded()) {
            return;
        }
        if (latestStatus != null && latestStatus.getState() == ConnectionStatus.State.CONNECTING) {
            return;
        }
        if (proxyPickerDialog != null && proxyPickerDialog.isShowing()) {
            proxyPickerDialog.dismiss();
        }

        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_select_proxy, null);
        TextView tvSubtitle = dialogView.findViewById(R.id.tv_select_proxy_subtitle);
        RecyclerView rvProxies = dialogView.findViewById(R.id.rv_proxy_picker);
        View emptyView = dialogView.findViewById(R.id.layout_proxy_picker_empty);
        MaterialButton btnAdd = dialogView.findViewById(R.id.btn_picker_add_proxy);
        MaterialButton btnCancel = dialogView.findViewById(R.id.btn_picker_cancel);

        List<ProxyConfig> items = new ArrayList<>(latestProxies);
        if (items.isEmpty()) {
            tvSubtitle.setVisibility(View.GONE);
            rvProxies.setVisibility(View.GONE);
            emptyView.setVisibility(View.VISIBLE);
        } else {
            tvSubtitle.setVisibility(View.VISIBLE);
            tvSubtitle.setText(getString(R.string.desc_select_proxy_dialog, items.size()));
            rvProxies.setVisibility(View.VISIBLE);
            emptyView.setVisibility(View.GONE);

            long currentActiveId = -1L;
            if (latestStatus != null && latestStatus.isRunning() && latestStatus.getActiveProxy() != null) {
                currentActiveId = latestStatus.getActiveProxy().getId();
            } else if (latestSelectedProxy != null) {
                currentActiveId = latestSelectedProxy.getId();
            }

            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            if (rvProxies.getLayoutParams() instanceof androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) {
                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams lp =
                        (androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) rvProxies.getLayoutParams();
                int minMaxHeight = Math.round(240f * dm.density);
                int maxCapHeight = Math.round(540f * dm.density);
                int viewportMaxHeight = Math.round(dm.heightPixels * 0.58f);
                lp.matchConstraintMaxHeight = Math.max(minMaxHeight, Math.min(maxCapHeight, viewportMaxHeight));
                rvProxies.setLayoutParams(lp);
            }

            rvProxies.setLayoutManager(new LinearLayoutManager(requireContext()));
            rvProxies.setAdapter(new ProxyPickerAdapter(items, currentActiveId, selected -> {
                viewModel.selectProxy(selected.getId());
                Toast.makeText(
                        requireContext(),
                        getString(R.string.msg_proxy_selected, selected.getDisplayTitle()),
                        Toast.LENGTH_SHORT
                ).show();
                if (proxyPickerDialog != null) {
                    proxyPickerDialog.dismiss();
                }
            }));
        }

        btnAdd.setOnClickListener(v -> {
            if (proxyPickerDialog != null) {
                proxyPickerDialog.dismiss();
            }
            startActivity(new Intent(requireContext(), AddEditProxyActivity.class));
        });

        btnCancel.setOnClickListener(v -> {
            if (proxyPickerDialog != null) {
                proxyPickerDialog.dismiss();
            }
        });

        proxyPickerDialog = new MaterialAlertDialogBuilder(requireContext())
                .setView(dialogView)
                .create();
        proxyPickerDialog.show();

        android.view.Window window = proxyPickerDialog.getWindow();
        if (window != null) {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            int maxDialogWidth = Math.round(560f * dm.density);
            int targetWidth = Math.min(Math.round(dm.widthPixels * 0.94f), maxDialogWidth);
            window.setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
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
                if (layoutConnectLoading != null) {
                    layoutConnectLoading.setVisibility(View.GONE);
                }
                btnToggleVpn.setEnabled(true);
                btnToggleVpn.setIconResource(R.drawable.ic_power);
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
                btnToggleVpn.setEnabled(false);
                btnToggleVpn.setIcon(null);
                btnToggleVpn.setText(R.string.action_connecting);
                btnToggleVpn.setBackgroundTintList(ColorStateList.valueOf(0xFF0D9488));
                btnToggleVpn.setTextColor(Color.TRANSPARENT);
                if (layoutConnectLoading != null) {
                    layoutConnectLoading.setVisibility(View.VISIBLE);
                }
                if (progressConnectVpn != null) {
                    progressConnectVpn.setVisibility(View.VISIBLE);
                }
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
            tvAuthValue.setText(proxy.getAuthDisplaySummary(requireContext()));

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
        if (layoutConnectLoading != null) {
            layoutConnectLoading.setVisibility(View.GONE);
        }
        btnToggleVpn.setEnabled(true);
        btnToggleVpn.setIconResource(R.drawable.ic_power);
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

    private interface OnPickerProxySelectedListener {
        void onSelected(@NonNull ProxyConfig proxy);
    }

    private static final class ProxyPickerAdapter
            extends RecyclerView.Adapter<ProxyPickerAdapter.PickerViewHolder> {

        private final List<ProxyConfig> items;
        private final long activeProxyId;
        private final OnPickerProxySelectedListener listener;

        ProxyPickerAdapter(@NonNull List<ProxyConfig> items,
                           long activeProxyId,
                           @NonNull OnPickerProxySelectedListener listener) {
            this.items = items;
            this.activeProxyId = activeProxyId;
            this.listener = listener;
        }

        @NonNull
        @Override
        public PickerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_proxy_picker_option, parent, false);
            return new PickerViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull PickerViewHolder holder, int position) {
            holder.bind(items.get(position), activeProxyId, listener);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static final class PickerViewHolder extends RecyclerView.ViewHolder {
            private final MaterialCardView card;
            private final View dotStatus;
            private final TextView tvTitle;
            private final TextView tvAddress;
            private final TextView tvLatency;
            private final TextView tvMeta;
            private final ImageView ivSelected;

            PickerViewHolder(@NonNull View itemView) {
                super(itemView);
                card = itemView.findViewById(R.id.card_proxy_picker_option);
                dotStatus = itemView.findViewById(R.id.view_picker_status_dot);
                tvTitle = itemView.findViewById(R.id.tv_picker_proxy_title);
                tvAddress = itemView.findViewById(R.id.tv_picker_proxy_address);
                tvLatency = itemView.findViewById(R.id.tv_picker_latency_badge);
                tvMeta = itemView.findViewById(R.id.tv_picker_meta);
                ivSelected = itemView.findViewById(R.id.iv_picker_selected);
            }

            void bind(@NonNull ProxyConfig proxy,
                      long activeProxyId,
                      @NonNull OnPickerProxySelectedListener listener) {
                android.content.Context ctx = itemView.getContext();
                boolean isSelected = proxy.getId() == activeProxyId;
                float density = ctx.getResources().getDisplayMetrics().density;

                tvTitle.setText(proxy.getDisplayTitle());
                tvAddress.setText(proxy.getSchemePrefix() + proxy.getAddressDisplay());

                long lat = proxy.getLatencyMs();
                if (lat == ProxyConfig.LATENCY_FAILED) {
                    dotStatus.setBackgroundTintList(
                            ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.status_error)));
                    tvLatency.setText(R.string.latency_failed);
                    tvLatency.setTextColor(ContextCompat.getColor(ctx, R.color.status_error));
                    tvLatency.setBackgroundTintList(
                            ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.status_error_bg)));
                } else if (lat == ProxyConfig.LATENCY_UNTESTED) {
                    dotStatus.setBackgroundTintList(
                            ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.status_idle)));
                    tvLatency.setText(R.string.latency_untested);
                    tvLatency.setTextColor(ContextCompat.getColor(ctx, R.color.status_idle));
                    tvLatency.setBackgroundTintList(
                            ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.status_idle_bg)));
                } else if (lat <= 250) {
                    dotStatus.setBackgroundTintList(
                            ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.status_connected)));
                    tvLatency.setText(ctx.getString(R.string.latency_ms, lat));
                    tvLatency.setTextColor(ContextCompat.getColor(ctx, R.color.status_connected));
                    tvLatency.setBackgroundTintList(
                            ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.status_connected_bg)));
                } else {
                    dotStatus.setBackgroundTintList(
                            ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.status_switching)));
                    tvLatency.setText(ctx.getString(R.string.latency_ms, lat));
                    tvLatency.setTextColor(ContextCompat.getColor(ctx, R.color.status_switching));
                    tvLatency.setBackgroundTintList(
                            ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.status_switching_bg)));
                }

                String metaText;
                if (!proxy.getExitIp().isEmpty()) {
                    metaText = proxy.getCountry().isEmpty()
                            ? proxy.getExitIp()
                            : proxy.getCountry() + " • " + proxy.getExitIp();
                } else {
                    metaText = proxy.getAuthDisplaySummary(ctx);
                }
                tvMeta.setText(metaText);

                if (isSelected) {
                    card.setStrokeColor(ContextCompat.getColor(ctx, R.color.md_theme_primary));
                    card.setStrokeWidth((int) (2f * density));
                    ivSelected.setImageResource(R.drawable.ic_check_circle);
                    ivSelected.setImageTintList(
                            ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.md_theme_primary)));
                } else {
                    card.setStrokeColor(ContextCompat.getColor(ctx, R.color.md_theme_outline));
                    card.setStrokeWidth((int) (1f * density));
                    ivSelected.setImageResource(R.drawable.ic_radio_unchecked);
                    ivSelected.setImageTintList(
                            ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.md_theme_onSurfaceVariant)));
                }

                card.setOnClickListener(v -> listener.onSelected(proxy));
            }
        }
    }
}
