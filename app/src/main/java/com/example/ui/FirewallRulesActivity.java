package com.example.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.R;
import com.example.data.InstalledAppScanner;
import com.example.data.ProxyRepository;
import com.example.data.SettingsPreferences;
import com.example.model.FirewallRule;
import com.example.model.InstalledAppInfo;
import com.example.model.ProxyLogEntry;
import com.example.network.ProxyRuntimeManager;
import com.example.network.ProxyVpnService;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * Pure Java Activity displaying Firewall Rules (initially seeded with "Quy tắc 1")
 * and allowing users to configure existing rules or add new rules blocking selected
 * applications from accessing the internet.
 */
public class FirewallRulesActivity extends AppCompatActivity implements FirewallRuleAdapter.Listener {

    private ProxyRepository repository;
    private SettingsPreferences settingsPrefs;
    private FirewallRuleAdapter adapter;

    private TextView tvBanner;
    private ImageView ivBannerIcon;
    private RecyclerView recyclerRules;
    private TextView tvEmpty;

    private List<FirewallRule> currentRules = new ArrayList<>();
    private List<InstalledAppInfo> cachedInstalledApps = new ArrayList<>();
    private final Map<String, String> packageLabels = new HashMap<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_firewall_rules);

        repository = ProxyRepository.getInstance(this);
        settingsPrefs = new SettingsPreferences(this);

        MaterialToolbar toolbar = findViewById(R.id.firewall_toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        View root = findViewById(R.id.firewall_rules_root);
        ExtendedFloatingActionButton fabAdd = findViewById(R.id.fab_add_firewall_rule);
        int baseFabMargin = Math.round(16 * getResources().getDisplayMetrics().density);

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            toolbar.setPadding(toolbar.getPaddingLeft(), sysBars.top, toolbar.getPaddingRight(), toolbar.getPaddingBottom());
            android.view.ViewGroup.MarginLayoutParams lp =
                    (android.view.ViewGroup.MarginLayoutParams) fabAdd.getLayoutParams();
            lp.bottomMargin = baseFabMargin + sysBars.bottom;
            lp.rightMargin = baseFabMargin + sysBars.right;
            fabAdd.setLayoutParams(lp);
            return WindowInsetsCompat.CONSUMED;
        });

        tvBanner = findViewById(R.id.tv_firewall_banner);
        ivBannerIcon = findViewById(R.id.iv_firewall_banner_icon);
        recyclerRules = findViewById(R.id.recycler_firewall_rules);
        tvEmpty = findViewById(R.id.tv_empty_firewall_rules);

        adapter = new FirewallRuleAdapter(this);
        recyclerRules.setLayoutManager(new LinearLayoutManager(this));
        recyclerRules.setAdapter(adapter);

        fabAdd.setOnClickListener(v -> showRuleEditorDialog(null));

        repository.getFirewallRules().observe(this, rules -> {
            currentRules = rules != null ? rules : new ArrayList<>();
            adapter.submitList(currentRules);
            boolean empty = currentRules.isEmpty();
            tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
            recyclerRules.setVisibility(empty ? View.GONE : View.VISIBLE);
            updateStatusBanner();
        });

        repository.reloadFirewallRules();
        loadInstalledAppsAsync();
    }

    private void loadInstalledAppsAsync() {
        Executors.newSingleThreadExecutor().execute(() -> {
            List<InstalledAppInfo> apps = InstalledAppScanner.scanInstalledApps(this);
            Map<String, String> labels = new HashMap<>();
            for (InstalledAppInfo app : apps) {
                labels.put(app.getPackageName(), app.getAppName());
            }
            runOnUiThread(() -> {
                cachedInstalledApps = apps;
                packageLabels.clear();
                packageLabels.putAll(labels);
                adapter.setPackageLabelMap(packageLabels);
            });
        });
    }

    private void updateStatusBanner() {
        boolean firewallOn = settingsPrefs.isFirewallEnabled();
        if (firewallOn) {
            int activeRules = 0;
            Set<String> blockedPkgs = new LinkedHashSet<>();
            for (FirewallRule r : currentRules) {
                if (r.isEnabled()) {
                    activeRules++;
                    blockedPkgs.addAll(r.getBlockedPackages());
                }
            }
            tvBanner.setText(getString(R.string.firewall_status_active_banner, activeRules, blockedPkgs.size()));
            ivBannerIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_connected));
        } else {
            tvBanner.setText(R.string.firewall_status_inactive_banner);
            ivBannerIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_idle));
        }
    }

    @Override
    public void onToggleRule(FirewallRule rule, boolean enabled) {
        repository.setFirewallRuleEnabled(rule.getId(), enabled, () -> runOnUiThread(() -> {
            updateStatusBanner();
            reapplyVpnIfRunning();
        }));
    }

    @Override
    public void onConfigureRule(FirewallRule rule) {
        showRuleEditorDialog(rule);
    }

    @Override
    public void onDeleteRule(FirewallRule rule) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.msg_firewall_delete_title)
                .setMessage(getString(R.string.msg_firewall_delete_body, rule.getName()))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (dialog, which) ->
                        repository.deleteFirewallRule(rule.getId(), () -> runOnUiThread(() -> {
                            Toast.makeText(this,
                                    getString(R.string.msg_firewall_rule_deleted, rule.getName()),
                                    Toast.LENGTH_SHORT).show();
                            reapplyVpnIfRunning();
                        })))
                .show();
    }

    private void showRuleEditorDialog(@Nullable FirewallRule existing) {
        boolean isNew = existing == null;
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_firewall_rule_edit, null);

        TextInputLayout tilName = dialogView.findViewById(R.id.til_rule_name);
        TextInputEditText etName = dialogView.findViewById(R.id.et_rule_name);
        MaterialSwitch switchEnabled = dialogView.findViewById(R.id.switch_dialog_rule_enabled);
        MaterialButton btnPickApps = dialogView.findViewById(R.id.btn_pick_rule_apps);
        TextView tvSelectedApps = dialogView.findViewById(R.id.tv_dialog_selected_apps);
        TextInputEditText etTarget = dialogView.findViewById(R.id.et_rule_target);

        Set<String> workingPackages = new LinkedHashSet<>();
        if (existing != null) {
            etName.setText(existing.getName());
            switchEnabled.setChecked(existing.isEnabled());
            workingPackages.addAll(existing.getBlockedPackages());
            etTarget.setText(existing.getTargetAddress());
        } else {
            String suggestedName = getString(R.string.firewall_rule_name_template, currentRules.size() + 1);
            etName.setText(suggestedName);
            switchEnabled.setChecked(true);
        }

        Runnable refreshAppsPreview = () -> {
            btnPickApps.setText(getString(R.string.action_pick_firewall_apps, workingPackages.size()));
            if (workingPackages.isEmpty()) {
                tvSelectedApps.setText(R.string.firewall_rule_apps_none);
            } else {
                tvSelectedApps.setText(FirewallRuleAdapter.formatAppNamesPreview(workingPackages, packageLabels));
            }
        };
        refreshAppsPreview.run();

        btnPickApps.setOnClickListener(v -> showAppMultiSelectDialog(workingPackages, refreshAppsPreview));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(isNew ? R.string.title_add_firewall_rule : R.string.title_edit_firewall_rule)
                .setView(dialogView)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_confirm_timer, null)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String name = etName.getText() != null ? etName.getText().toString().trim() : "";
                if (name.isEmpty()) {
                    tilName.setError(getString(R.string.error_firewall_rule_name_required));
                    return;
                }
                tilName.setError(null);
                String target = etTarget.getText() != null ? etTarget.getText().toString().trim() : "";
                boolean enabled = switchEnabled.isChecked();

                FirewallRule toSave = existing != null
                        ? new FirewallRule(existing.getId(), name, enabled, workingPackages, target, existing.getCreatedAt())
                        : new FirewallRule(0L, name, enabled, workingPackages, target, System.currentTimeMillis());

                repository.saveFirewallRule(toSave, () -> runOnUiThread(() -> {
                    repository.addLog(ProxyLogEntry.Level.INFO,
                            com.example.data.ProxyLogFormatter.getString(
                                    this, R.string.log_firewall_rule_saved,
                                    toSave.getName(), toSave.getBlockedPackages().size()));
                    Toast.makeText(this, R.string.msg_firewall_rule_saved, Toast.LENGTH_SHORT).show();
                    reapplyVpnIfRunning();
                    dialog.dismiss();
                }));
            });
        });

        dialog.show();
    }

    private void showAppMultiSelectDialog(Set<String> workingPackages, Runnable onDone) {
        if (cachedInstalledApps.isEmpty()) {
            cachedInstalledApps = InstalledAppScanner.scanInstalledApps(this);
            for (InstalledAppInfo app : cachedInstalledApps) {
                packageLabels.put(app.getPackageName(), app.getAppName());
            }
        }
        int count = cachedInstalledApps.size();
        String[] labels = new String[count];
        boolean[] checked = new boolean[count];
        for (int i = 0; i < count; i++) {
            InstalledAppInfo app = cachedInstalledApps.get(i);
            labels[i] = app.getAppName() + " (" + app.getPackageName() + ")";
            checked[i] = workingPackages.contains(app.getPackageName());
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.title_pick_blocked_apps)
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> {
                    String pkg = cachedInstalledApps.get(which).getPackageName();
                    if (isChecked) {
                        workingPackages.add(pkg);
                    } else {
                        workingPackages.remove(pkg);
                    }
                })
                .setPositiveButton(R.string.action_done, (dialog, which) -> onDone.run())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void reapplyVpnIfRunning() {
        if (ProxyRuntimeManager.getInstance().getCurrentStatus().isRunning()) {
            ProxyVpnService.reapplyFirewallAndRouting(this);
        }
    }
}
