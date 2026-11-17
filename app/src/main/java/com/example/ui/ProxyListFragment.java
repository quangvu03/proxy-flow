package com.example.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.R;
import com.example.model.ProxyConfig;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Fragment managing the saved HTTP proxy list: search, filter, add/edit,
 * bulk import/export, live latency testing, and 1-tap connection.
 */
public class ProxyListFragment extends Fragment implements ProxyListAdapter.OnProxyActionListener {

    private ProxyViewModel viewModel;
    private ProxyListAdapter adapter;

    private TextInputEditText etSearch;
    private ChipGroup chipGroupFilter;
    private MaterialButton btnTestAll;
    private MaterialButton btnImportBulk;
    private MaterialButton btnExportAll;
    private LinearProgressIndicator progressBatchTest;
    private RecyclerView recyclerProxies;
    private LinearLayout layoutEmpty;
    private ExtendedFloatingActionButton fabAddProxy;

    private List<ProxyConfig> allProxies = new ArrayList<>();
    private String searchQuery = "";
    private int filterMode = 0; // 0 = all, 1 = online

    private final ActivityResultLauncher<Intent> detailLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    long connectProxyId = result.getData().getLongExtra(
                            ProxyDetailActivity.EXTRA_CONNECT_PROXY_ID, -1L);
                    if (connectProxyId > 0) {
                        viewModel.selectProxy(connectProxyId);
                        if (getActivity() instanceof ConnectFragment.HostActions) {
                            ((ConnectFragment.HostActions) getActivity()).onRequestConnectProxy(connectProxyId);
                        }
                    }
                }
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_proxy_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(ProxyViewModel.class);

        etSearch = view.findViewById(R.id.et_search_proxy);
        chipGroupFilter = view.findViewById(R.id.chip_group_filter);
        btnTestAll = view.findViewById(R.id.btn_test_all);
        btnImportBulk = view.findViewById(R.id.btn_import_bulk);
        btnExportAll = view.findViewById(R.id.btn_export_all);
        progressBatchTest = view.findViewById(R.id.progress_batch_test);
        recyclerProxies = view.findViewById(R.id.recycler_proxies);
        layoutEmpty = view.findViewById(R.id.layout_empty_proxies);
        fabAddProxy = view.findViewById(R.id.fab_add_proxy);

        adapter = new ProxyListAdapter(this);
        recyclerProxies.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerProxies.setAdapter(adapter);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchQuery = s != null ? s.toString().trim().toLowerCase(Locale.ROOT) : "";
                applyFilter();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        chipGroupFilter.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.contains(R.id.chip_filter_online)) {
                filterMode = 1;
            } else {
                filterMode = 0;
            }
            applyFilter();
        });

        fabAddProxy.setOnClickListener(v -> openAddEditSheet(null));

        btnTestAll.setOnClickListener(v -> {
            if (allProxies.isEmpty()) {
                Toast.makeText(requireContext(), R.string.msg_no_proxy_to_connect, Toast.LENGTH_SHORT).show();
                return;
            }
            Toast.makeText(requireContext(), getString(R.string.msg_testing_all, allProxies.size()), Toast.LENGTH_SHORT).show();
            viewModel.testAllProxies();
        });

        btnImportBulk.setOnClickListener(v -> showBulkImportDialog());
        btnExportAll.setOnClickListener(v -> exportProxiesToClipboard());

        viewModel.getProxies().observe(getViewLifecycleOwner(), list -> {
            allProxies = list != null ? list : new ArrayList<>();
            applyFilter();
        });

        viewModel.getSelectedProxy().observe(getViewLifecycleOwner(), selected -> {
            if (selected != null) {
                adapter.setActiveProxyId(selected.getId());
            } else {
                adapter.setActiveProxyId(-1L);
            }
        });

        viewModel.isBatchTesting().observe(getViewLifecycleOwner(), testing -> {
            boolean running = testing != null && testing;
            progressBatchTest.setVisibility(running ? View.VISIBLE : View.GONE);
            btnTestAll.setEnabled(!running);
        });
    }

    private void applyFilter() {
        List<ProxyConfig> filtered = new ArrayList<>();
        for (ProxyConfig p : allProxies) {
            if (filterMode == 1 && p.getLatencyMs() <= 0) {
                continue;
            }
            if (!searchQuery.isEmpty()) {
                String hay = (p.getName() + " " + p.getHost() + ":" + p.getPort() + " " + p.getCountry() + " " + p.getExitIp())
                        .toLowerCase(Locale.ROOT);
                if (!hay.contains(searchQuery)) {
                    continue;
                }
            }
            filtered.add(p);
        }

        adapter.submitList(filtered);
        boolean isEmpty = filtered.isEmpty();
        layoutEmpty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        recyclerProxies.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }

    private void openAddEditSheet(@Nullable ProxyConfig existing) {
        AddEditProxyBottomSheet sheet = AddEditProxyBottomSheet.newInstance(existing, proxy ->
                viewModel.saveProxy(proxy, () -> {
                    if (isAdded()) {
                        requireActivity().runOnUiThread(() ->
                                Toast.makeText(requireContext(), R.string.msg_proxy_saved, Toast.LENGTH_SHORT).show());
                    }
                })
        );
        sheet.show(getChildFragmentManager(), "AddEditProxyBottomSheet");
    }

    private void showBulkImportDialog() {
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_bulk_import, null);
        TextInputEditText etBulk = dialogView.findViewById(R.id.et_bulk_proxies);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.title_import_bulk)
                .setView(dialogView)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_import_confirm, (d, which) -> {
                    String raw = etBulk.getText() != null ? etBulk.getText().toString() : "";
                    viewModel.importBulk(raw, count -> {
                        if (isAdded()) {
                            requireActivity().runOnUiThread(() ->
                                    Toast.makeText(requireContext(),
                                            getString(R.string.msg_imported_count, count),
                                            Toast.LENGTH_SHORT).show());
                        }
                    });
                })
                .show();
    }

    private void exportProxiesToClipboard() {
        new Thread(() -> {
            String text = viewModel.exportAllProxiesText();
            int count = allProxies.size();
            if (isAdded()) {
                requireActivity().runOnUiThread(() -> {
                    if (text.isEmpty()) {
                        Toast.makeText(requireContext(), R.string.msg_no_proxies_to_export, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("ProxyFlow Proxies", text));
                    }
                    Toast.makeText(requireContext(),
                            getString(R.string.msg_exported_copied, count),
                            Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    @Override
    public void onConnectProxy(ProxyConfig proxy) {
        viewModel.selectProxy(proxy.getId());
        if (getActivity() instanceof ConnectFragment.HostActions) {
            ((ConnectFragment.HostActions) getActivity()).onRequestConnectProxy(proxy.getId());
        }
    }

    @Override
    public void onTestProxy(ProxyConfig proxy) {
        Intent intent = new Intent(requireContext(), ProxyDetailActivity.class);
        intent.putExtra(ProxyDetailActivity.EXTRA_PROXY_ID, proxy.getId());
        detailLauncher.launch(intent);
    }

    @Override
    public void onEditProxy(ProxyConfig proxy) {
        openAddEditSheet(proxy);
    }

    @Override
    public void onDeleteProxy(ProxyConfig proxy) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.msg_delete_confirm_title)
                .setMessage(getString(R.string.msg_delete_confirm_body, proxy.getAddressDisplay()))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (d, which) -> viewModel.deleteProxy(proxy.getId()))
                .show();
    }
}
