package com.example.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.R;
import com.example.model.AppProxyRule;
import com.example.model.InstalledAppInfo;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Fragment in 100% Java allowing the user to inspect all installed apps on the device
 * and check/uncheck which apps are allowed or blocked from using the active Proxy.
 */
public class AppProxyFragment extends Fragment implements InstalledAppAdapter.OnAppToggleListener {

    private ProxyViewModel viewModel;
    private InstalledAppAdapter adapter;

    private TextView tvScanSummary;
    private ImageButton btnRescan;
    private ChipGroup chipGroupMode;
    private TextView tvModeHint;
    private MaterialCardView cardPendingApply;
    private MaterialButton btnApplyNow;
    private TextInputEditText etSearch;
    private ChipGroup chipGroupFilter;
    private TextView tvCheckedCount;
    private MaterialButton btnSelectAllVisible;
    private MaterialButton btnClearAll;
    private LinearProgressIndicator progressScan;
    private RecyclerView recyclerApps;
    private TextView tvEmptyApps;

    private List<InstalledAppInfo> allApps = new ArrayList<>();
    private List<InstalledAppInfo> filteredApps = new ArrayList<>();
    private AppProxyRule currentRule = new AppProxyRule(AppProxyRule.Mode.BLOCK_SELECTED, Collections.emptySet());
    private String searchQuery = "";
    private int filterMode = 0; // 0 = all, 1 = user, 2 = system, 3 = checked
    private boolean bindingMode = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_apps, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(ProxyViewModel.class);

        tvScanSummary = view.findViewById(R.id.tv_apps_scan_summary);
        btnRescan = view.findViewById(R.id.btn_rescan_apps);
        chipGroupMode = view.findViewById(R.id.chip_group_app_mode);
        tvModeHint = view.findViewById(R.id.tv_app_mode_hint);
        cardPendingApply = view.findViewById(R.id.card_apply_app_rules);
        btnApplyNow = view.findViewById(R.id.btn_apply_app_rules);
        etSearch = view.findViewById(R.id.et_search_apps);
        chipGroupFilter = view.findViewById(R.id.chip_group_app_filter);
        tvCheckedCount = view.findViewById(R.id.tv_apps_checked_count);
        btnSelectAllVisible = view.findViewById(R.id.btn_select_all_visible);
        btnClearAll = view.findViewById(R.id.btn_clear_all_apps);
        progressScan = view.findViewById(R.id.progress_scan_apps);
        recyclerApps = view.findViewById(R.id.recycler_apps);
        tvEmptyApps = view.findViewById(R.id.tv_empty_apps);

        adapter = new InstalledAppAdapter(this);
        recyclerApps.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerApps.setAdapter(adapter);

        btnRescan.setOnClickListener(v -> viewModel.scanInstalledApps());

        chipGroupMode.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (bindingMode || checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            if (id == R.id.chip_mode_allow_selected) {
                viewModel.setAppFilterMode(AppProxyRule.Mode.ALLOW_SELECTED);
            } else {
                viewModel.setAppFilterMode(AppProxyRule.Mode.BLOCK_SELECTED);
            }
        });

        btnApplyNow.setOnClickListener(v -> {
            viewModel.applyAppRulesToRunningVpn();
            Toast.makeText(requireContext(), R.string.msg_app_rules_applied, Toast.LENGTH_SHORT).show();
        });

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchQuery = s != null ? s.toString().trim().toLowerCase(Locale.ROOT) : "";
                applyFilter();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        chipGroupFilter.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.contains(R.id.chip_apps_user)) {
                filterMode = 1;
            } else if (checkedIds.contains(R.id.chip_apps_system)) {
                filterMode = 2;
            } else if (checkedIds.contains(R.id.chip_apps_checked)) {
                filterMode = 3;
            } else {
                filterMode = 0;
            }
            applyFilter();
        });

        btnSelectAllVisible.setOnClickListener(v -> {
            List<String> pkgs = new ArrayList<>(filteredApps.size());
            for (InstalledAppInfo info : filteredApps) {
                pkgs.add(info.getPackageName());
            }
            viewModel.setMultipleAppsChecked(pkgs, true);
        });

        btnClearAll.setOnClickListener(v -> viewModel.clearSelectedApps());

        viewModel.getInstalledApps().observe(getViewLifecycleOwner(), list -> {
            allApps = list != null ? list : new ArrayList<>();
            updateSummaryCounts();
            applyFilter();
        });

        viewModel.getAppProxyRule().observe(getViewLifecycleOwner(), rule -> {
            if (rule != null) {
                currentRule = rule;
                adapter.setAppProxyRule(rule);
                renderRuleHeader(rule);
                if (filterMode == 3) {
                    applyFilter();
                }
            }
        });

        viewModel.isScanningApps().observe(getViewLifecycleOwner(), scanning -> {
            boolean running = scanning != null && scanning;
            progressScan.setVisibility(running ? View.VISIBLE : View.GONE);
            btnRescan.setEnabled(!running);
        });

        viewModel.isAppRulesPendingApply().observe(getViewLifecycleOwner(), pending -> {
            boolean show = pending != null && pending;
            cardPendingApply.setVisibility(show ? View.VISIBLE : View.GONE);
        });

        if (allApps.isEmpty()) {
            viewModel.scanInstalledApps();
        }
    }

    private void renderRuleHeader(AppProxyRule rule) {
        bindingMode = true;
        try {
            if (rule.getMode() == AppProxyRule.Mode.ALLOW_SELECTED) {
                chipGroupMode.check(R.id.chip_mode_allow_selected);
                tvModeHint.setText(R.string.app_mode_allow_hint);
            } else {
                chipGroupMode.check(R.id.chip_mode_block_selected);
                tvModeHint.setText(R.string.app_mode_block_hint);
            }
        } finally {
            bindingMode = false;
        }
        updateSummaryCounts();
    }

    private void updateSummaryCounts() {
        int total = allApps.size();
        int withInternet = 0;
        for (InstalledAppInfo app : allApps) {
            if (app.hasInternetPermission()) withInternet++;
        }
        tvScanSummary.setText(getString(R.string.label_apps_scan_summary, total, withInternet));

        int checkedCount = currentRule.getSelectedPackages().size();
        if (currentRule.getMode() == AppProxyRule.Mode.ALLOW_SELECTED) {
            tvCheckedCount.setText(getString(R.string.label_apps_allow_count, checkedCount));
        } else {
            tvCheckedCount.setText(getString(R.string.label_apps_block_count, checkedCount));
        }
    }

    private void applyFilter() {
        List<InstalledAppInfo> out = new ArrayList<>();
        for (InstalledAppInfo app : allApps) {
            if (filterMode == 1 && app.isSystemApp()) continue;
            if (filterMode == 2 && !app.isSystemApp()) continue;
            if (filterMode == 3 && !currentRule.isPackageChecked(app.getPackageName())) continue;

            if (!searchQuery.isEmpty()) {
                String hay = (app.getAppName() + " " + app.getPackageName()).toLowerCase(Locale.ROOT);
                if (!hay.contains(searchQuery)) continue;
            }
            out.add(app);
        }
        filteredApps = out;
        adapter.submitList(out);
        boolean empty = out.isEmpty();
        tvEmptyApps.setVisibility(empty ? View.VISIBLE : View.GONE);
        recyclerApps.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onAppToggled(InstalledAppInfo app, boolean checked) {
        viewModel.setAppChecked(app.getPackageName(), checked);
    }
}
