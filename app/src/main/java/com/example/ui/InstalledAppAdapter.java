package com.example.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.example.R;
import com.example.model.AppProxyRule;
import com.example.model.InstalledAppInfo;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.checkbox.MaterialCheckBox;

import java.util.Collections;

/**
 * RecyclerView adapter displaying installed device apps with a single intuitive checkbox:
 * Checked = app uses Proxy, Unchecked = app does NOT use Proxy (direct network).
 */
public class InstalledAppAdapter extends ListAdapter<InstalledAppInfo, InstalledAppAdapter.AppViewHolder> {

    public interface OnAppToggleListener {
        void onAppProxyToggled(InstalledAppInfo app, boolean useProxy);
    }

    private final OnAppToggleListener listener;
    private AppProxyRule currentRule = new AppProxyRule(AppProxyRule.Mode.BLOCK_SELECTED, Collections.emptySet());

    public InstalledAppAdapter(OnAppToggleListener listener) {
        super(DIFF_CALLBACK);
        this.listener = listener;
    }

    public void setAppProxyRule(AppProxyRule rule) {
        if (rule != null) {
            this.currentRule = rule;
            notifyDataSetChanged();
        }
    }

    private static final DiffUtil.ItemCallback<InstalledAppInfo> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<InstalledAppInfo>() {
                @Override
                public boolean areItemsTheSame(@NonNull InstalledAppInfo oldItem, @NonNull InstalledAppInfo newItem) {
                    return oldItem.getPackageName().equals(newItem.getPackageName());
                }

                @Override
                public boolean areContentsTheSame(@NonNull InstalledAppInfo oldItem, @NonNull InstalledAppInfo newItem) {
                    return oldItem.getPackageName().equals(newItem.getPackageName())
                            && oldItem.getAppName().equals(newItem.getAppName())
                            && oldItem.isSystemApp() == newItem.isSystemApp()
                            && oldItem.hasInternetPermission() == newItem.hasInternetPermission();
                }
            };

    @NonNull
    @Override
    public AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_installed_app, parent, false);
        return new AppViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull AppViewHolder holder, int position) {
        holder.bind(getItem(position), currentRule, listener);
    }

    static class AppViewHolder extends RecyclerView.ViewHolder {
        private final MaterialCardView cardView;
        private final ImageView ivIcon;
        private final TextView tvName;
        private final TextView tvPackage;
        private final TextView tvRoutingBadge;
        private final MaterialCheckBox cbSelected;

        AppViewHolder(@NonNull View itemView) {
            super(itemView);
            cardView = itemView.findViewById(R.id.card_installed_app);
            ivIcon = itemView.findViewById(R.id.iv_app_icon);
            tvName = itemView.findViewById(R.id.tv_app_name);
            tvPackage = itemView.findViewById(R.id.tv_app_package);
            tvRoutingBadge = itemView.findViewById(R.id.tv_app_routing_badge);
            cbSelected = itemView.findViewById(R.id.cb_app_selected);
        }

        void bind(InstalledAppInfo app, AppProxyRule rule, OnAppToggleListener listener) {
            Context ctx = itemView.getContext();
            tvName.setText(app.getAppName());
            tvPackage.setText(app.getPackageName());

            if (app.getIcon() != null) {
                ivIcon.setImageDrawable(app.getIcon());
            } else {
                ivIcon.setImageResource(R.drawable.ic_apps);
            }

            // Single checkbox semantics: Checked = Uses Proxy, Unchecked = Does NOT use Proxy
            boolean usesProxy = rule.doesAppUseProxy(app.getPackageName());

            if (usesProxy) {
                tvRoutingBadge.setText(R.string.badge_app_via_proxy);
                tvRoutingBadge.setTextColor(ContextCompat.getColor(ctx, R.color.status_connected));
                tvRoutingBadge.setBackgroundTintList(
                        ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.status_connected_bg)));
            } else {
                tvRoutingBadge.setText(R.string.badge_app_bypass_proxy);
                tvRoutingBadge.setTextColor(ContextCompat.getColor(ctx, R.color.status_error));
                tvRoutingBadge.setBackgroundTintList(
                        ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.status_error_bg)));
            }

            cbSelected.setOnCheckedChangeListener(null);
            cbSelected.setChecked(usesProxy);
            cbSelected.setOnCheckedChangeListener((buttonView, isChecked) ->
                    listener.onAppProxyToggled(app, isChecked));

            cardView.setOnClickListener(v -> cbSelected.performClick());
        }
    }
}
