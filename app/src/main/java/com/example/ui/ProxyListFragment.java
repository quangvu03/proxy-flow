package com.example.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
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
import com.example.data.ProxyFileHelper;
import com.example.model.ProxyConfig;
import com.example.model.ProxyLogEntry;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fragment managing the saved HTTP proxy list: search, filter, add/edit,
 * bulk import (paste or .txt file), export to .txt file via SAF, live latency testing,
 * and 1-tap connection.
 */
public class ProxyListFragment extends Fragment implements ProxyListAdapter.OnProxyActionListener {

    private ProxyViewModel viewModel;
    private ProxyListAdapter adapter;
    private final ExecutorService fileIoExecutor = Executors.newSingleThreadExecutor();

    private static final String BUY_PROXY_URL = "https://topproxy.vn/";

    private TextInputEditText etSearch;
    private ChipGroup chipGroupFilter;
    private MaterialButton btnBuyProxy;
    private MaterialButton btnTestAll;
    private MaterialButton btnImportBulk;
    private MaterialButton btnExportAll;
    private LinearProgressIndicator progressBatchTest;
    private RecyclerView recyclerProxies;
    private LinearLayout layoutEmpty;
    private ExtendedFloatingActionButton fabAddProxy;

    @Nullable
    private TextInputEditText activeBulkImportInput;

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

    private final ActivityResultLauncher<String[]> importTxtLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    readProxiesFromTxtUri(uri);
                }
            });

    private final ActivityResultLauncher<String> exportTxtLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("text/plain"), uri -> {
                if (uri != null) {
                    writeProxiesToTxtUri(uri);
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
        btnBuyProxy = view.findViewById(R.id.btn_buy_proxy);
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

        if (btnBuyProxy != null) {
            btnBuyProxy.setOnClickListener(v -> openBuyProxyPage());
        }
        btnImportBulk.setOnClickListener(v -> showBulkImportDialog());
        btnExportAll.setOnClickListener(v -> startExportProxiesToTxt());

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

    private void openBuyProxyPage() {
        try {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(BUY_PROXY_URL));
            startActivity(browserIntent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(requireContext(), R.string.msg_no_browser_found, Toast.LENGTH_SHORT).show();
        }
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

    @SuppressLint("ClickableViewAccessibility")
    private void showBulkImportDialog() {
        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());
        View sheetView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_bulk_import, null);
        sheet.setContentView(sheetView);

        TextInputEditText etBulk = sheetView.findViewById(R.id.et_bulk_proxies);
        TextView tvValidCount = sheetView.findViewById(R.id.tv_bulk_valid_count);
        MaterialButton btnPickFile = sheetView.findViewById(R.id.btn_bulk_pick_file);
        MaterialButton btnPaste = sheetView.findViewById(R.id.btn_bulk_paste);
        MaterialButton btnClear = sheetView.findViewById(R.id.btn_bulk_clear);
        MaterialButton btnCancel = sheetView.findViewById(R.id.btn_bulk_cancel);
        MaterialButton btnConfirm = sheetView.findViewById(R.id.btn_bulk_confirm);

        activeBulkImportInput = etBulk;
        sheet.setOnDismissListener(d -> {
            if (activeBulkImportInput == etBulk) {
                activeBulkImportInput = null;
            }
        });

        Runnable updateCounter = () -> {
            String raw = etBulk.getText() != null ? etBulk.getText().toString() : "";
            int valid = ProxyFileHelper.countValidProxyLines(raw);
            tvValidCount.setText(getString(R.string.label_valid_proxy_lines, valid));
        };
        updateCounter.run();

        etBulk.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateCounter.run();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        // Allow vertical scrolling inside the large editor without dragging the BottomSheet
        etBulk.setOnTouchListener((v, event) -> {
            v.getParent().requestDisallowInterceptTouchEvent(true);
            if ((event.getAction() & MotionEvent.ACTION_MASK) == MotionEvent.ACTION_UP) {
                v.getParent().requestDisallowInterceptTouchEvent(false);
            }
            return false;
        });

        btnPickFile.setOnClickListener(v -> launchImportTxtPicker());

        btnPaste.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence pasted = cm.getPrimaryClip().getItemAt(0).coerceToText(requireContext());
                if (pasted != null && pasted.length() > 0) {
                    String existing = etBulk.getText() != null ? etBulk.getText().toString().trim() : "";
                    String next = existing.isEmpty() ? pasted.toString() : (existing + "\n" + pasted);
                    etBulk.setText(next);
                    etBulk.setSelection(next.length());
                }
            }
        });

        btnClear.setOnClickListener(v -> etBulk.setText(""));
        btnCancel.setOnClickListener(v -> sheet.dismiss());
        btnConfirm.setOnClickListener(v -> {
            String raw = etBulk.getText() != null ? etBulk.getText().toString() : "";
            viewModel.importBulk(raw, count -> {
                if (isAdded()) {
                    requireActivity().runOnUiThread(() ->
                            Toast.makeText(requireContext(),
                                    getString(R.string.msg_imported_count, count),
                                    Toast.LENGTH_SHORT).show());
                }
            });
            sheet.dismiss();
        });

        sheet.setOnShowListener(dialogInterface -> {
            FrameLayout bottomSheet = sheet.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(bottomSheet);
                behavior.setSkipCollapsed(true);
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            }
        });

        sheet.show();
    }

    private void launchImportTxtPicker() {
        try {
            importTxtLauncher.launch(new String[]{"text/plain", "text/*", "*/*"});
        } catch (ActivityNotFoundException e) {
            Toast.makeText(requireContext(),
                    getString(R.string.msg_import_file_failed, "No file picker"),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void readProxiesFromTxtUri(@NonNull Uri uri) {
        Context appCtx = requireContext().getApplicationContext();
        fileIoExecutor.execute(() -> {
            try (InputStream in = appCtx.getContentResolver().openInputStream(uri)) {
                String content = ProxyFileHelper.readTextFromStream(in);
                int validCount = ProxyFileHelper.countValidProxyLines(content);
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    if (validCount <= 0) {
                        Toast.makeText(requireContext(), R.string.msg_no_valid_proxy_in_file, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (activeBulkImportInput != null) {
                        String existing = activeBulkImportInput.getText() != null
                                ? activeBulkImportInput.getText().toString().trim()
                                : "";
                        String merged = existing.isEmpty() ? content.trim() : (existing + "\n" + content.trim());
                        activeBulkImportInput.setText(merged);
                        activeBulkImportInput.setSelection(merged.length());
                        Toast.makeText(requireContext(),
                                getString(R.string.msg_loaded_from_txt, validCount),
                                Toast.LENGTH_SHORT).show();
                    } else {
                        viewModel.importBulk(content, imported -> {
                            if (isAdded()) {
                                requireActivity().runOnUiThread(() ->
                                        Toast.makeText(requireContext(),
                                                getString(R.string.msg_imported_count, imported),
                                                Toast.LENGTH_SHORT).show());
                            }
                        });
                    }
                });
            } catch (Exception e) {
                if (!isAdded()) return;
                String err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                requireActivity().runOnUiThread(() ->
                        Toast.makeText(requireContext(),
                                getString(R.string.msg_import_file_failed, err),
                                Toast.LENGTH_LONG).show());
            }
        });
    }

    private void startExportProxiesToTxt() {
        if (allProxies.isEmpty()) {
            Toast.makeText(requireContext(), R.string.msg_no_proxies_to_export, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            exportTxtLauncher.launch(ProxyFileHelper.buildDefaultExportFileName());
        } catch (ActivityNotFoundException e) {
            Toast.makeText(requireContext(),
                    getString(R.string.msg_export_file_failed, "No file saver"),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void writeProxiesToTxtUri(@NonNull Uri uri) {
        Context appCtx = requireContext().getApplicationContext();
        fileIoExecutor.execute(() -> {
            try {
                String exportText = viewModel.exportAllProxiesText();
                if (exportText == null || exportText.trim().isEmpty()) {
                    if (isAdded()) {
                        requireActivity().runOnUiThread(() ->
                                Toast.makeText(requireContext(), R.string.msg_no_proxies_to_export, Toast.LENGTH_SHORT).show());
                    }
                    return;
                }
                int exportedCount = ProxyFileHelper.countValidProxyLines(exportText);
                try (OutputStream out = openSafeOutputStream(appCtx, uri)) {
                    ProxyFileHelper.writeTextToStream(out, exportText);
                }
                viewModel.addLog(ProxyLogEntry.Level.INFO,
                        "Đã xuất " + exportedCount + " Proxy ra file .txt");
                if (isAdded()) {
                    requireActivity().runOnUiThread(() ->
                            Toast.makeText(requireContext(),
                                    getString(R.string.msg_exported_txt_success, exportedCount),
                                    Toast.LENGTH_LONG).show());
                }
            } catch (Exception e) {
                if (!isAdded()) return;
                String err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                requireActivity().runOnUiThread(() ->
                        Toast.makeText(requireContext(),
                                getString(R.string.msg_export_file_failed, err),
                                Toast.LENGTH_LONG).show());
            }
        });
    }

    private static OutputStream openSafeOutputStream(Context context, Uri uri) throws Exception {
        try {
            OutputStream stream = context.getContentResolver().openOutputStream(uri, "wt");
            if (stream != null) return stream;
        } catch (UnsupportedOperationException | IllegalArgumentException ignored) {
            // Fall back to default write mode for providers that do not support "wt"
        }
        return context.getContentResolver().openOutputStream(uri);
    }

    @Override
    public void onDestroyView() {
        activeBulkImportInput = null;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        fileIoExecutor.shutdownNow();
        super.onDestroy();
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
