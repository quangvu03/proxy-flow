package com.example.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.R;
import com.google.android.material.button.MaterialButton;

/**
 * Fragment in 100% Java displaying the manual fixed-IP mode summary
 * and live connection event logs.
 */
public class LogsFragment extends Fragment {

    private ProxyViewModel viewModel;
    private LogListAdapter logAdapter;

    private MaterialButton btnClearLogs;
    private TextView tvEmptyLogs;
    private RecyclerView recyclerLogs;

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

        logAdapter = new LogListAdapter();
        recyclerLogs.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerLogs.setAdapter(logAdapter);

        btnClearLogs.setOnClickListener(v -> viewModel.clearLogs());

        viewModel.getLogs().observe(getViewLifecycleOwner(), logs -> {
            boolean empty = logs == null || logs.isEmpty();
            tvEmptyLogs.setVisibility(empty ? View.VISIBLE : View.GONE);
            recyclerLogs.setVisibility(empty ? View.GONE : View.VISIBLE);
            logAdapter.submitList(logs);
        });
    }
}
