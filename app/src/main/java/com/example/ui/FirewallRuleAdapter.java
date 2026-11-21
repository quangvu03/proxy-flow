package com.example.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.R;
import com.example.model.FirewallRule;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure Java RecyclerView adapter displaying user-configured firewall rules.
 */
public class FirewallRuleAdapter extends RecyclerView.Adapter<FirewallRuleAdapter.RuleViewHolder> {

    public interface Listener {
        void onToggleRule(FirewallRule rule, boolean enabled);
        void onConfigureRule(FirewallRule rule);
        void onDeleteRule(FirewallRule rule);
    }

    private final Listener listener;
    private final List<FirewallRule> items = new ArrayList<>();
    private final Map<String, String> packageToLabelMap = new HashMap<>();

    public FirewallRuleAdapter(@NonNull Listener listener) {
        this.listener = listener;
    }

    public void submitList(List<FirewallRule> rules) {
        items.clear();
        if (rules != null) {
            items.addAll(rules);
        }
        notifyDataSetChanged();
    }

    public void setPackageLabelMap(Map<String, String> labels) {
        packageToLabelMap.clear();
        if (labels != null) {
            packageToLabelMap.putAll(labels);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public RuleViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_firewall_rule, parent, false);
        return new RuleViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RuleViewHolder holder, int position) {
        FirewallRule rule = items.get(position);
        Context ctx = holder.itemView.getContext();

        holder.tvName.setText(rule.getName());

        holder.switchEnabled.setOnCheckedChangeListener(null);
        holder.switchEnabled.setChecked(rule.isEnabled());
        holder.switchEnabled.setOnCheckedChangeListener((buttonView, isChecked) ->
                listener.onToggleRule(rule, isChecked));

        int appCount = rule.getBlockedPackages().size();
        if (appCount == 0) {
            holder.tvAppsSummary.setText(R.string.firewall_rule_apps_none);
        } else {
            holder.tvAppsSummary.setText(ctx.getString(
                    R.string.firewall_rule_apps_summary,
                    appCount,
                    formatAppNamesPreview(rule.getBlockedPackages(), packageToLabelMap)
            ));
        }

        if (rule.blocksAllInternet()) {
            holder.tvTargetSummary.setText(R.string.firewall_rule_target_all);
        } else {
            holder.tvTargetSummary.setText(ctx.getString(
                    R.string.firewall_rule_target_custom, rule.getTargetAddress()));
        }

        holder.itemView.setOnClickListener(v -> listener.onConfigureRule(rule));
        holder.btnConfigure.setOnClickListener(v -> listener.onConfigureRule(rule));
        holder.btnDelete.setOnClickListener(v -> listener.onDeleteRule(rule));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static String formatAppNamesPreview(Iterable<String> packages, Map<String, String> labelMap) {
        List<String> names = new ArrayList<>();
        for (String pkg : packages) {
            String label = labelMap != null ? labelMap.get(pkg) : null;
            names.add(label != null && !label.isEmpty() ? label : pkg);
            if (names.size() >= 3) break;
        }
        int total = 0;
        for (String ignored : packages) {
            total++;
        }
        String joined = android.text.TextUtils.join(", ", names);
        if (total > names.size()) {
            return joined + " (+" + (total - names.size()) + ")";
        }
        return joined;
    }

    static class RuleViewHolder extends RecyclerView.ViewHolder {
        final TextView tvName;
        final MaterialSwitch switchEnabled;
        final TextView tvAppsSummary;
        final TextView tvTargetSummary;
        final MaterialButton btnConfigure;
        final ImageButton btnDelete;

        RuleViewHolder(@NonNull View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tv_rule_name);
            switchEnabled = itemView.findViewById(R.id.switch_rule_enabled);
            tvAppsSummary = itemView.findViewById(R.id.tv_rule_apps_summary);
            tvTargetSummary = itemView.findViewById(R.id.tv_rule_target_summary);
            btnConfigure = itemView.findViewById(R.id.btn_configure_rule);
            btnDelete = itemView.findViewById(R.id.btn_delete_rule);
        }
    }
}
