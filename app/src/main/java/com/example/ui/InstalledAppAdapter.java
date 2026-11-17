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
 * RecyclerView adapter displaying installed device apps with their package name,
 * system/user classification, INTERNET permission check, live proxy routing status,
 * and checkbox for per-app allow/block selection.
 */
public class InstalledAppAdapter extends ListAdapter<InstalledAppInfo, InstalledAppAdapter.AppViewHolder> {

    public interface OnAppToggleListener {
        void onAppToggled(InstalledAppInfo app, boolean checked);
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
        private final TextView tvTypeBadge;
        private final TextView tvInternetBadge;
        private final MaterialCheckBox cbSelected;

        AppViewHolder(@NonNull View itemView) {
            super(itemView);
            cardView = itemView.findViewById(R.id.card_installed_app);
            ivIcon = itemView.findViewById(R.id.iv_app_icon);
            tvName = itemView.findViewById(R.id.tv_app_name);
            tvPackage = itemView.findViewById(R.id.tv_app_package);
            tvRoutingBadge = itemView.findViewById(R.id.tv_app_routing_badge);
            tvTypeBadge = itemView.findViewById(R.id.tv_app_type_badge);
            tvInternetBadge = itemView.findViewById(R.id.tv_app_internet_badge);
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

            tvTypeBadge.setText(app.isSystemApp() ? R.string.badge_app_system : R.string.badge_app_user);
            tvTypeBadge.setBackgroundTintList(
                    ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.status_idle_bg)));

            if (app.hasInternetPermission()) {
                tvInternetBadge.setText(R.string.badge_app_internet_yes);
                tvInternetBadge.setTextColor(ContextCompat.getColor(ctx, R.color.md_theme_primary));
                tvInternetBadge.setBackgroundTintList(
                        ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.status_idle_bg)));
            } else {
                tvInternetBadge.setText(R.string.badge_app_internet_no);
                tvInternetBadge.setTextColor(ContextCompat.getColor(ctx, R.color.status_idle));
                tvInternetBadge.setBackgroundTintList(
                        ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.status_idle_bg)));
            }

            boolean checked = rule.isPackageChecked(app.getPackageName());
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
            cbSelected.setChecked(checked);
            cbSelected.setOnCheckedChangeListener((buttonView, isChecked) ->
                    listener.onAppToggled(app, isChecked));

            cardView.setOnClickListener(v -> cbSelected.performClick());
        }
    }
}
