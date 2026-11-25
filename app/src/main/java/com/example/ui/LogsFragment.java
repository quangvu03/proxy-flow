package com.example.ui;

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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.R;
import com.example.model.ProxyLogEntry;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.divider.MaterialDividerItemDecoration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Fragment in 100% Java displaying live DNS, full traffic sending, connection,
 * and firewall event logs with category filtering.
 */
public class LogsFragment extends Fragment {

    private static final int FILTER_ALL = 0;
    private static final int FILTER_DNS = 1;
    private static final int FILTER_TRAFFIC = 2;
    private static final int FILTER_ERROR = 3;

    private ProxyViewModel viewModel;
    private LogListAdapter logAdapter;

    private MaterialButton btnClearLogs;
    private TextView tvEmptyLogs;
    private RecyclerView recyclerLogs;
    private ChipGroup chipGroupFilter;

    private List<ProxyLogEntry> allLogs = Collections.emptyList();
    private int currentFilter = FILTER_ALL;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_logs, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(ProxyViewModel.class);

        btnClearLogs = view.findViewById(R.id.btn_clear_logs);
        tvEmptyLogs = view.findViewById(R.id.tv_empty_logs);
        recyclerLogs = view.findViewById(R.id.recycler_logs);
        chipGroupFilter = view.findViewById(R.id.chip_group_log_filter);

        logAdapter = new LogListAdapter();
        recyclerLogs.setLayoutManager(new LinearLayoutManager(requireContext()));
        MaterialDividerItemDecoration divider =
                new MaterialDividerItemDecoration(requireContext(), LinearLayoutManager.VERTICAL);
        divider.setLastItemDecorated(false);
        divider.setDividerColor(ContextCompat.getColor(requireContext(), R.color.md_theme_surfaceVariant));
        recyclerLogs.addItemDecoration(divider);
        recyclerLogs.setAdapter(logAdapter);

        if (chipGroupFilter != null) {
            chipGroupFilter.setOnCheckedStateChangeListener((group, checkedIds) -> {
                if (checkedIds.contains(R.id.chip_log_dns)) {
                    currentFilter = FILTER_DNS;
                } else if (checkedIds.contains(R.id.chip_log_traffic)) {
                    currentFilter = FILTER_TRAFFIC;
                } else if (checkedIds.contains(R.id.chip_log_error)) {
                    currentFilter = FILTER_ERROR;
                } else {
                    currentFilter = FILTER_ALL;
                }
                applyLogFilter();
            });
        }

        btnClearLogs.setOnClickListener(v -> viewModel.clearLogs());

        viewModel.getLogs().observe(getViewLifecycleOwner(), logs -> {
            allLogs = logs != null ? logs : Collections.emptyList();
            applyLogFilter();
        });
    }

    private void applyLogFilter() {
        List<ProxyLogEntry> filtered;
        if (currentFilter == FILTER_ALL) {
            filtered = allLogs;
        } else {
            filtered = new ArrayList<>();
            for (ProxyLogEntry entry : allLogs) {
                if (matchesFilter(entry, currentFilter)) {
                    filtered.add(entry);
                }
            }
        }
        boolean empty = filtered.isEmpty();
        tvEmptyLogs.setVisibility(empty ? View.VISIBLE : View.GONE);
        recyclerLogs.setVisibility(empty ? View.GONE : View.VISIBLE);
        logAdapter.submitList(filtered);
    }

    private static boolean matchesFilter(ProxyLogEntry entry, int filter) {
        if (entry == null) return false;
        String tag = entry.getEffectiveTag();
        switch (filter) {
            case FILTER_DNS:
                return "DNS".equals(tag) || entry.getMessage().startsWith("DNS ");
            case FILTER_TRAFFIC:
                return "DNS".equals(tag)
                        || "HTTPS".equals(tag)
                        || "HTTP".equals(tag)
                        || "SEND".equals(tag)
                        || "DONE".equals(tag)
                        || "TCP".equals(tag)
                        || "UDP".equals(tag)
                        || "BLOCK".equals(tag)
                        || "FW".equals(tag);
            case FILTER_ERROR:
                return entry.getLevel() == ProxyLogEntry.Level.ERROR
                        || entry.getLevel() == ProxyLogEntry.Level.WARN;
            case FILTER_ALL:
            default:
                return true;
        }
    }
}
