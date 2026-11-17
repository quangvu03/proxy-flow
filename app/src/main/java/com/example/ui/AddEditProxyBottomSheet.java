package com.example.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.R;
import com.example.model.ProxyConfig;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

/**
 * Material 3 BottomSheetDialogFragment for adding or editing an HTTP Proxy
 * (Host/IP, Port, Username, Password, plus 1-tap clipboard parsing).
 */
public class AddEditProxyBottomSheet extends BottomSheetDialogFragment {

    public interface OnSaveProxyListener {
        void onSaveProxy(ProxyConfig proxy);
    }

    private ProxyConfig editingProxy;
    private OnSaveProxyListener saveListener;

    public static AddEditProxyBottomSheet newInstance(@Nullable ProxyConfig existing, OnSaveProxyListener listener) {
        AddEditProxyBottomSheet sheet = new AddEditProxyBottomSheet();
        sheet.editingProxy = existing != null ? existing.copy() : null;
        sheet.saveListener = listener;
        return sheet;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_add_proxy, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        TextView tvTitle = view.findViewById(R.id.tv_sheet_title);
        MaterialButton btnPasteParse = view.findViewById(R.id.btn_paste_parse);
        TextInputEditText etName = view.findViewById(R.id.et_proxy_name);
        TextInputLayout tilHost = view.findViewById(R.id.til_proxy_host);
        TextInputEditText etHost = view.findViewById(R.id.et_proxy_host);
        TextInputLayout tilPort = view.findViewById(R.id.til_proxy_port);
        TextInputEditText etPort = view.findViewById(R.id.et_proxy_port);
        TextInputEditText etUser = view.findViewById(R.id.et_proxy_user);
        TextInputEditText etPass = view.findViewById(R.id.et_proxy_pass);
        MaterialButton btnCancel = view.findViewById(R.id.btn_cancel_proxy);
        MaterialButton btnSave = view.findViewById(R.id.btn_save_proxy);

        if (editingProxy != null) {
            tvTitle.setText(R.string.title_edit_proxy);
            etName.setText(editingProxy.getName());
            etHost.setText(editingProxy.getHost());
            etPort.setText(String.valueOf(editingProxy.getPort()));
            etUser.setText(editingProxy.getUsername());
            etPass.setText(editingProxy.getPassword());
        } else {
            tvTitle.setText(R.string.title_add_proxy);
            etPort.setText("8080");
        }

        btnPasteParse.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip()) {
                ClipData clip = cm.getPrimaryClip();
                if (clip != null && clip.getItemCount() > 0) {
                    CharSequence text = clip.getItemAt(0).coerceToText(requireContext());
                    ProxyConfig parsed = ProxyConfig.parseFromLine(text != null ? text.toString() : "");
                    if (parsed != null) {
                        etHost.setText(parsed.getHost());
                        etPort.setText(String.valueOf(parsed.getPort()));
                        etUser.setText(parsed.getUsername());
                        etPass.setText(parsed.getPassword());
                        tilHost.setError(null);
                        tilPort.setError(null);
                        Toast.makeText(requireContext(), R.string.msg_clipboard_parsed, Toast.LENGTH_SHORT).show();
                        return;
                    }
                }
            }
            Toast.makeText(requireContext(), R.string.msg_clipboard_invalid, Toast.LENGTH_SHORT).show();
        });

        btnCancel.setOnClickListener(v -> dismiss());

        btnSave.setOnClickListener(v -> {
            String host = etHost.getText() != null ? etHost.getText().toString().trim() : "";
            String portStr = etPort.getText() != null ? etPort.getText().toString().trim() : "";
            String name = etName.getText() != null ? etName.getText().toString().trim() : "";
            String user = etUser.getText() != null ? etUser.getText().toString().trim() : "";
            String pass = etPass.getText() != null ? etPass.getText().toString() : "";

            if (host.isEmpty()) {
                tilHost.setError(getString(R.string.error_host_required));
                return;
            }
            tilHost.setError(null);

            int port;
            try {
                port = Integer.parseInt(portStr);
            } catch (NumberFormatException e) {
                port = -1;
            }
            if (port < 1 || port > 65535) {
                tilPort.setError(getString(R.string.error_port_invalid));
                return;
            }
            tilPort.setError(null);

            ProxyConfig result = editingProxy != null ? editingProxy : new ProxyConfig();
            result.setName(name);
            result.setHost(host);
            result.setPort(port);
            result.setUsername(user);
            result.setPassword(pass);

            if (saveListener != null) {
                saveListener.onSaveProxy(result);
            }
            dismiss();
        });
    }
}
