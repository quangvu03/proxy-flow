package com.example.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.example.R;
import com.example.model.ProxyConfig;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

/**
 * RecyclerView adapter displaying saved proxies with latency, auth status,
 * active indicator, and quick action buttons.
 */
public class ProxyListAdapter extends ListAdapter<ProxyConfig, ProxyListAdapter.ProxyViewHolder> {

    public interface OnProxyActionListener {
        void onConnectProxy(ProxyConfig proxy);
        void onTestProxy(ProxyConfig proxy);
        void onEditProxy(ProxyConfig proxy);
        void onDeleteProxy(ProxyConfig proxy);
    }

    private final OnProxyActionListener listener;
    private long activeProxyId = -1L;

    public ProxyListAdapter(OnProxyActionListener listener) {
        super(DIFF_CALLBACK);
        this.listener = listener;
    }

    public void setActiveProxyId(long activeProxyId) {
        if (this.activeProxyId != activeProxyId) {
            this.activeProxyId = activeProxyId;
            notifyDataSetChanged();
        }
    }

    private static final DiffUtil.ItemCallback<ProxyConfig> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<ProxyConfig>() {
                @Override
                public boolean areItemsTheSame(@NonNull ProxyConfig oldItem, @NonNull ProxyConfig newItem) {
                    return oldItem.getId() == newItem.getId();
                }

                @Override
                public boolean areContentsTheSame(@NonNull ProxyConfig oldItem, @NonNull ProxyConfig newItem) {
                    return oldItem.equals(newItem);
                }
            };

    @NonNull
    @Override
    public ProxyViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_proxy_card, parent, false);
        return new ProxyViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ProxyViewHolder holder, int position) {
        holder.bind(getItem(position), activeProxyId, listener);
    }

    static class ProxyViewHolder extends RecyclerView.ViewHolder {
        private final MaterialCardView cardView;
        private final TextView tvTitle;
        private final TextView tvActiveBadge;
        private final TextView tvAddress;
        private final TextView tvLatencyBadge;
        private final TextView tvAuthBadge;
        private final TextView tvExitIpMeta;
        private final MaterialButton btnConnectNow;
        private final MaterialButton btnTest;
        private final ImageButton btnEdit;
        private final ImageButton btnDelete;

        ProxyViewHolder(@NonNull View itemView) {
            super(itemView);
            cardView = itemView.findViewById(R.id.card_proxy_item);
            tvTitle = itemView.findViewById(R.id.tv_proxy_title);
            tvActiveBadge = itemView.findViewById(R.id.tv_active_badge);
            tvAddress = itemView.findViewById(R.id.tv_proxy_address);
            tvLatencyBadge = itemView.findViewById(R.id.tv_latency_badge);
            tvAuthBadge = itemView.findViewById(R.id.tv_auth_badge);
            tvExitIpMeta = itemView.findViewById(R.id.tv_exit_ip_meta);
            btnConnectNow = itemView.findViewById(R.id.btn_proxy_connect_now);
            btnTest = itemView.findViewById(R.id.btn_proxy_test);
            btnEdit = itemView.findViewById(R.id.btn_proxy_edit);
            btnDelete = itemView.findViewById(R.id.btn_proxy_delete);
        }

        void bind(ProxyConfig proxy, long activeProxyId, OnProxyActionListener listener) {
            Context ctx = itemView.getContext();
            boolean isActive = proxy.getId() == activeProxyId;

            tvTitle.setText(proxy.getDisplayTitle());
            tvAddress.setText(proxy.getAddressDisplay());
            tvActiveBadge.setVisibility(isActive ? View.VISIBLE : View.GONE);

            if (isActive) {
                cardView.setStrokeColor(ContextCompat.getColor(ctx, R.color.md_theme_primary));
                cardView.setStrokeWidth((int) (2 * ctx.getResources().getDisplayMetrics().density));
                tvActiveBadge.setBackgroundTintList(
                        ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.status_connected_bg)));
            } else {
                cardView.setStrokeColor(ContextCompat.getColor(ctx, R.color.md_theme_outline));
                cardView.setStrokeWidth((int) (1 * ctx.getResources().getDisplayMetrics().density));
            }

            // Auth badge
            if (proxy.hasAuth()) {
                tvAuthBadge.setText(ctx.getString(R.string.badge_auth_yes) + ": " + proxy.getUsername());
            } else {
                tvAuthBadge.setText(R.string.badge_auth_no);
            }

            // Latency badge
            long lat = proxy.getLatencyMs();
            if (lat == ProxyConfig.LATENCY_UNTESTED) {
                tvLatencyBadge.setText(R.string.latency_untested);
                tvLatencyBadge.setTextColor(ContextCompat.getColor(ctx, R.color.status_idle));
                tvLatencyBadge.setBackgroundTintList(
                        ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.status_idle_bg)));
            } else if (lat == ProxyConfig.LATENCY_FAILED) {
                tvLatencyBadge.setText(R.string.latency_failed);
                tvLatencyBadge.setTextColor(ContextCompat.getColor(ctx, R.color.status_error));
                tvLatencyBadge.setBackgroundTintList(
                        ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.status_error_bg)));
            } else {
                tvLatencyBadge.setText(ctx.getString(R.string.latency_ms, lat));
                if (lat <= 250) {
                    tvLatencyBadge.setTextColor(ContextCompat.getColor(ctx, R.color.status_connected));
                    tvLatencyBadge.setBackgroundTintList(
                            ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.status_connected_bg)));
                } else {
                    tvLatencyBadge.setTextColor(ContextCompat.getColor(ctx, R.color.status_switching));
                    tvLatencyBadge.setBackgroundTintList(
                            ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.status_switching_bg)));
                }
            }

            // Exit IP & Country
            if (!proxy.getExitIp().isEmpty()) {
                String meta = proxy.getCountry().isEmpty()
                        ? proxy.getExitIp()
                        : proxy.getExitIp() + " • " + proxy.getCountry();
                tvExitIpMeta.setText(meta);
            } else {
                tvExitIpMeta.setText("");
            }

            cardView.setOnClickListener(v -> listener.onConnectProxy(proxy));
            btnConnectNow.setOnClickListener(v -> listener.onConnectProxy(proxy));
            btnTest.setOnClickListener(v -> listener.onTestProxy(proxy));
            btnEdit.setOnClickListener(v -> listener.onEditProxy(proxy));
            btnDelete.setOnClickListener(v -> listener.onDeleteProxy(proxy));
        }
    }
}
