package com.example.ui;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.R;
import com.example.data.ProxyRepository;
import com.example.model.ConnectionStatus;
import com.example.model.ProxyConfig;
import com.example.model.ProxyConfig.AuthMethod;
import com.example.model.ProxyConfig.DnsMode;
import com.example.model.ProxyConfig.Protocol;
import com.example.network.ProxyRuntimeManager;
import com.example.network.ProxyTester;
import com.example.network.ProxyVpnService;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dedicated full-screen Activity for adding a new Proxy or editing an existing one,
 * supporting configuration name, protocol selection (SOCKS5, HTTP, HTTP + TLS, SOCKS5 + UDP,
 * SOCKS5 + TLS), separate Server/Port rows with real-time port range warning (1..65535),
 * optional inline connection test once server fields are complete, conditional Username/Password
 * authentication, and conditional DNS resolution (Proxy, Cloudflare DNS, Google DNS, Custom).
 */
public class AddEditProxyActivity extends AppCompatActivity {

    public static final String EXTRA_PROXY_ID = "extra_proxy_id";
    private static final String STATE_PROTOCOL = "state_protocol";
    private static final String STATE_AUTH_METHOD = "state_auth_method";
    private static final String STATE_DNS_MODE = "state_dns_mode";
    private static final String STATE_TEST_PASSED = "state_test_passed";

    private ProxyRepository repository;
    private final ExecutorService loaderExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService testExecutor = Executors.newSingleThreadExecutor();

    @Nullable
    private ProxyConfig editingProxy;

    private Protocol selectedProtocol = Protocol.ALL;
    private AuthMethod selectedAuthMethod = AuthMethod.NONE;
    private DnsMode selectedDnsMode = DnsMode.PROXY;
    private boolean isConnectionTestPassed = false;
    private boolean restoringInstanceState = false;
    private boolean isSaving = false;
    private long testedLatencyMs = ProxyConfig.LATENCY_UNTESTED;
    private String testedExitIp = "";
    private String testedCountry = "";

    private MaterialToolbar toolbar;
    private TextInputEditText etName;
    private AutoCompleteTextView actvProtocol;
    private TextInputLayout tilHost;
    private TextInputEditText etHost;
    private TextInputLayout tilPort;
    private TextInputEditText etPort;
    private View layoutTestConnection;
    private MaterialButton btnTestConnection;
    private MaterialCardView cardTestConnectionStatus;
    private LinearProgressIndicator progressTestConnection;
    private TextView tvTestConnectionResult;
    private TextInputLayout tilAuthMethod;
    private AutoCompleteTextView actvAuthMethod;
    private View layoutCredentials;
    private TextInputLayout tilUser;
    private TextInputEditText etUser;
    private TextInputLayout tilPass;
    private TextInputEditText etPass;
    private TextInputLayout tilToken;
    private TextInputEditText etToken;
    private AutoCompleteTextView actvDnsMode;
    private TextInputLayout tilCustomDns;
    private TextInputEditText etCustomDns;
    private MaterialButton btnSave;
    private MaterialButton btnSaveAndConnect;

    private long pendingConnectProxyId = -1L;
    @Nullable
    private ProxyConfig pendingConnectProxy;

    private final ActivityResultLauncher<Intent> vpnPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    launchVpnAfterPermissions(pendingConnectProxyId);
                } else {
                    ProxyRuntimeManager.getInstance().postDisconnected(pendingConnectProxy, "");
                    pendingConnectProxy = null;
                    if (!isFinishing() && !isDestroyed()) {
                        Toast.makeText(this, R.string.msg_vpn_permission_denied, Toast.LENGTH_SHORT).show();
                        setResult(RESULT_OK);
                        finish();
                    }
                }
            });

    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted ->
                    proceedWithVpnPreparation(pendingConnectProxyId));

    private final Protocol[] protocols = new Protocol[]{
            Protocol.ALL,
            Protocol.SOCKS5,
            Protocol.HTTP,
            Protocol.HTTP_TLS,
            Protocol.SOCKS5_UDP,
            Protocol.SOCKS5_TLS
    };

    private final AuthMethod[] authMethods = new AuthMethod[]{
            AuthMethod.NONE,
            AuthMethod.USER_PASS,
            AuthMethod.HTTP_BASIC,
            AuthMethod.HTTP_DIGEST,
            AuthMethod.BEARER_TOKEN
    };

    private final DnsMode[] dnsModes = new DnsMode[]{
            DnsMode.PROXY,
            DnsMode.CLOUDFLARE,
            DnsMode.GOOGLE,
            DnsMode.CUSTOM
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_edit_proxy);

        repository = ProxyRepository.getInstance(this);

        toolbar = findViewById(R.id.add_edit_proxy_toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        View root = findViewById(R.id.add_edit_proxy_root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sysBars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime()
            );
            toolbar.setPadding(
                    toolbar.getPaddingLeft(),
                    sysBars.top,
                    toolbar.getPaddingRight(),
                    toolbar.getPaddingBottom()
            );
            root.setPadding(0, 0, 0, sysBars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        MaterialButton btnPasteParse = findViewById(R.id.btn_paste_parse);
        etName = findViewById(R.id.et_proxy_name);
        actvProtocol = findViewById(R.id.actv_proxy_protocol);
        tilHost = findViewById(R.id.til_proxy_host);
        etHost = findViewById(R.id.et_proxy_host);
        tilPort = findViewById(R.id.til_proxy_port);
        etPort = findViewById(R.id.et_proxy_port);
        layoutTestConnection = findViewById(R.id.layout_test_proxy_connection);
        btnTestConnection = findViewById(R.id.btn_test_proxy_connection);
        cardTestConnectionStatus = findViewById(R.id.card_test_connection_status);
        progressTestConnection = findViewById(R.id.progress_test_connection);
        tvTestConnectionResult = findViewById(R.id.tv_test_connection_result);
        tilAuthMethod = findViewById(R.id.til_proxy_auth_method);
        actvAuthMethod = findViewById(R.id.actv_proxy_auth_method);
        layoutCredentials = findViewById(R.id.layout_proxy_credentials);
        tilUser = findViewById(R.id.til_proxy_user);
        etUser = findViewById(R.id.et_proxy_user);
        tilPass = findViewById(R.id.til_proxy_pass);
        etPass = findViewById(R.id.et_proxy_pass);
        tilToken = findViewById(R.id.til_proxy_token);
        etToken = findViewById(R.id.et_proxy_token);
        actvDnsMode = findViewById(R.id.actv_proxy_dns_mode);
        tilCustomDns = findViewById(R.id.til_proxy_custom_dns);
        etCustomDns = findViewById(R.id.et_proxy_custom_dns);
        MaterialButton btnCancel = findViewById(R.id.btn_cancel_proxy);
        btnSave = findViewById(R.id.btn_save_proxy);
        btnSaveAndConnect = findViewById(R.id.btn_save_and_connect_proxy);

        setupDropdowns();
        setupServerFieldWatchers();

        if (savedInstanceState != null) {
            selectedProtocol = Protocol.fromString(savedInstanceState.getString(STATE_PROTOCOL));
            selectedAuthMethod = AuthMethod.fromString(savedInstanceState.getString(STATE_AUTH_METHOD));
            selectedDnsMode = DnsMode.fromString(savedInstanceState.getString(STATE_DNS_MODE));
            isConnectionTestPassed = savedInstanceState.getBoolean(STATE_TEST_PASSED, false);
            applyProtocolSelectionInternal(selectedProtocol, false);
            applyAuthMethodSelectionInternal(selectedAuthMethod, false);
            applyDnsModeSelection(selectedDnsMode);
        }

        long proxyId = getIntent() != null ? getIntent().getLongExtra(EXTRA_PROXY_ID, -1L) : -1L;
        if (proxyId > 0) {
            toolbar.setTitle(R.string.title_edit_proxy);
            loadExistingProxy(proxyId, savedInstanceState == null);
        } else {
            toolbar.setTitle(R.string.title_add_proxy);
            if (savedInstanceState == null) {
                applyProtocolSelection(Protocol.ALL);
                applyAuthMethodSelection(AuthMethod.NONE);
                applyDnsModeSelection(DnsMode.PROXY);
            }
        }

        updateTestConnectionVisibility();
        updateSaveAndConnectButtonState();

        btnPasteParse.setOnClickListener(v -> pasteAndParseFromClipboard());
        btnTestConnection.setOnClickListener(v -> testProxyConnection());
        btnCancel.setOnClickListener(v -> finish());
        btnSave.setOnClickListener(v -> validateAndSave(false));
        if (btnSaveAndConnect != null) {
            btnSaveAndConnect.setOnClickListener(v -> validateAndSave(true));
        }
    }

    @Override
    protected void onRestoreInstanceState(@NonNull Bundle savedInstanceState) {
        restoringInstanceState = true;
        super.onRestoreInstanceState(savedInstanceState);
        restoringInstanceState = false;
        isConnectionTestPassed = savedInstanceState.getBoolean(STATE_TEST_PASSED, isConnectionTestPassed);
        updateSaveAndConnectButtonState();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_PROTOCOL, selectedProtocol.name());
        outState.putString(STATE_AUTH_METHOD, selectedAuthMethod.name());
        outState.putString(STATE_DNS_MODE, selectedDnsMode.name());
        outState.putBoolean(STATE_TEST_PASSED, isConnectionTestPassed);
    }

    private void setupServerFieldWatchers() {
        etHost.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (restoringInstanceState) return;
                String host = s != null ? s.toString().trim() : "";
                if (!host.isEmpty()) {
                    tilHost.setError(null);
                }
                String portText = etPort.getText() != null ? etPort.getText().toString().trim() : "";
                if (portText.isEmpty() || parseValidPort(portText) != -1) {
                    tilPort.setError(null);
                }
                resetTestConnectionStatus();
                updateTestConnectionVisibility();
            }
        });

        etPort.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (restoringInstanceState) return;
                String portText = s != null ? s.toString().trim() : "";
                if (portText.isEmpty()) {
                    tilPort.setError(null);
                } else if (parseValidPort(portText) == -1) {
                    tilPort.setError(getString(R.string.error_port_invalid));
                } else {
                    tilPort.setError(null);
                }
                resetTestConnectionStatus();
                updateTestConnectionVisibility();
            }
        });

        TextWatcher credWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (restoringInstanceState) return;
                clearAuthErrors();
                resetTestConnectionStatus();
            }
        };
        etUser.addTextChangedListener(credWatcher);
        etPass.addTextChangedListener(credWatcher);
        if (etToken != null) {
            etToken.addTextChangedListener(credWatcher);
        }
    }

    private void setupDropdowns() {
        String[] protocolLabels = new String[protocols.length];
        for (int i = 0; i < protocols.length; i++) {
            protocolLabels[i] = protocols[i].getDisplayLabel(this);
        }
        actvProtocol.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, protocolLabels));
        actvProtocol.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < protocols.length) {
                applyProtocolSelection(protocols[position]);
            }
        });

        String[] authLabels = new String[authMethods.length];
        for (int i = 0; i < authMethods.length; i++) {
            authLabels[i] = getAuthMethodLabel(authMethods[i]);
        }
        actvAuthMethod.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, authLabels));
        actvAuthMethod.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < authMethods.length) {
                applyAuthMethodSelection(authMethods[position]);
            }
        });

        String[] dnsLabels = new String[]{
                getString(R.string.dns_mode_proxy),
                getString(R.string.dns_mode_cloudflare),
                getString(R.string.dns_mode_google),
                getString(R.string.dns_mode_custom)
        };
        actvDnsMode.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, dnsLabels));
        actvDnsMode.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < dnsModes.length) {
                applyDnsModeSelection(dnsModes[position]);
            }
        });
    }

    private String getAuthMethodLabel(AuthMethod method) {
        if (method == null) return getString(R.string.auth_method_none);
        switch (method) {
            case USER_PASS:
                return getString(R.string.auth_method_user_pass);
            case HTTP_BASIC:
                return getString(R.string.auth_method_http_basic);
            case HTTP_DIGEST:
                return getString(R.string.auth_method_http_digest);
            case BEARER_TOKEN:
                return getString(R.string.auth_method_bearer_token);
            case NONE:
            default:
                return getString(R.string.auth_method_none);
        }
    }

    private void clearAuthErrors() {
        if (tilAuthMethod != null) tilAuthMethod.setError(null);
        if (tilUser != null) tilUser.setError(null);
        if (tilPass != null) tilPass.setError(null);
        if (tilToken != null) tilToken.setError(null);
    }

    public void applyProtocolSelection(Protocol protocol) {
        applyProtocolSelectionInternal(protocol, true);
    }

    private void applyProtocolSelectionInternal(Protocol protocol, boolean resetTestState) {
        selectedProtocol = protocol != null ? protocol : Protocol.ALL;
        actvProtocol.setText(selectedProtocol.getDisplayLabel(this), false);
        if (resetTestState) {
            resetTestConnectionStatus();
        }
        updateTestConnectionVisibility();
    }

    public void applyAuthMethodSelection(AuthMethod authMethod) {
        applyAuthMethodSelectionInternal(authMethod, true);
    }

    private void applyAuthMethodSelectionInternal(AuthMethod authMethod, boolean resetTestState) {
        selectedAuthMethod = authMethod != null ? authMethod : AuthMethod.NONE;
        actvAuthMethod.setText(getAuthMethodLabel(selectedAuthMethod), false);
        clearAuthErrors();
        if (selectedAuthMethod == AuthMethod.NONE) {
            layoutCredentials.setVisibility(View.GONE);
            tilUser.setVisibility(View.VISIBLE);
            if (tilPass != null) tilPass.setVisibility(View.VISIBLE);
            if (tilToken != null) tilToken.setVisibility(View.GONE);
        } else if (selectedAuthMethod == AuthMethod.BEARER_TOKEN) {
            layoutCredentials.setVisibility(View.VISIBLE);
            tilUser.setVisibility(View.GONE);
            if (tilPass != null) tilPass.setVisibility(View.GONE);
            if (tilToken != null) tilToken.setVisibility(View.VISIBLE);
        } else {
            layoutCredentials.setVisibility(View.VISIBLE);
            tilUser.setVisibility(View.VISIBLE);
            if (tilPass != null) tilPass.setVisibility(View.VISIBLE);
            if (tilToken != null) tilToken.setVisibility(View.GONE);
        }
        if (resetTestState) {
            resetTestConnectionStatus();
        }
    }

    public void applyDnsModeSelection(DnsMode dnsMode) {
        selectedDnsMode = dnsMode != null ? dnsMode : DnsMode.PROXY;
        String label;
        switch (selectedDnsMode) {
            case CLOUDFLARE:
                label = getString(R.string.dns_mode_cloudflare);
                tilCustomDns.setVisibility(View.GONE);
                tilCustomDns.setError(null);
                break;
            case GOOGLE:
                label = getString(R.string.dns_mode_google);
                tilCustomDns.setVisibility(View.GONE);
                tilCustomDns.setError(null);
                break;
            case CUSTOM:
                label = getString(R.string.dns_mode_custom);
                tilCustomDns.setVisibility(View.VISIBLE);
                break;
            case PROXY:
            default:
                label = getString(R.string.dns_mode_proxy);
                tilCustomDns.setVisibility(View.GONE);
                tilCustomDns.setError(null);
                break;
        }
        actvDnsMode.setText(label, false);
    }

    private void updateTestConnectionVisibility() {
        String host = etHost.getText() != null ? etHost.getText().toString().trim() : "";
        String portStr = etPort.getText() != null ? etPort.getText().toString().trim() : "";
        boolean complete = !host.isEmpty() && parseValidPort(portStr) != -1 && selectedProtocol != null;
        layoutTestConnection.setVisibility(complete ? View.VISIBLE : View.GONE);
        if (!complete) {
            resetTestConnectionStatus();
        }
    }

    private void resetTestConnectionStatus() {
        isConnectionTestPassed = false;
        testedLatencyMs = ProxyConfig.LATENCY_UNTESTED;
        testedExitIp = "";
        testedCountry = "";
        if (cardTestConnectionStatus != null) {
            cardTestConnectionStatus.setVisibility(View.GONE);
        }
        if (progressTestConnection != null) {
            progressTestConnection.setVisibility(View.GONE);
        }
        updateSaveAndConnectButtonState();
    }

    private void updateSaveAndConnectButtonState() {
        if (btnSaveAndConnect == null) return;
        boolean readyToConnect = isConnectionTestPassed && !isSaving;
        btnSaveAndConnect.setEnabled(readyToConnect);
        btnSaveAndConnect.setAlpha(readyToConnect ? 1.0f : 0.45f);
        int bgColor = ContextCompat.getColor(this,
                readyToConnect ? R.color.md_theme_primary : R.color.md_theme_surfaceVariant);
        int fgColor = ContextCompat.getColor(this,
                readyToConnect ? R.color.md_theme_onPrimary : R.color.md_theme_onSurfaceVariant);
        btnSaveAndConnect.setBackgroundTintList(ColorStateList.valueOf(bgColor));
        btnSaveAndConnect.setTextColor(fgColor);
        btnSaveAndConnect.setIconTint(ColorStateList.valueOf(fgColor));
    }

    private boolean validateAuthInputs(boolean focusOnError) {
        clearAuthErrors();
        if (selectedAuthMethod == AuthMethod.NONE) {
            return true;
        }
        if (selectedAuthMethod == AuthMethod.BEARER_TOKEN) {
            String token = (etToken != null && etToken.getText() != null)
                    ? etToken.getText().toString().trim()
                    : "";
            if (token.isEmpty()) {
                if (tilToken != null) {
                    tilToken.setError(getString(R.string.error_token_required));
                }
                if (focusOnError && etToken != null) {
                    etToken.requestFocus();
                }
                return false;
            }
            return true;
        }
        String user = etUser.getText() != null ? etUser.getText().toString().trim() : "";
        String pass = etPass.getText() != null ? etPass.getText().toString() : "";
        if (user.isEmpty() && pass.isEmpty()) {
            tilUser.setError(getString(R.string.error_auth_credentials_required));
            if (focusOnError) {
                etUser.requestFocus();
            }
            return false;
        }
        return true;
    }

    private void applyAuthToConfig(ProxyConfig target) {
        target.setAuthMethod(selectedAuthMethod);
        if (selectedAuthMethod == AuthMethod.NONE) {
            target.setUsername("");
            target.setPassword("");
        } else if (selectedAuthMethod == AuthMethod.BEARER_TOKEN) {
            String token = (etToken != null && etToken.getText() != null)
                    ? etToken.getText().toString().trim()
                    : "";
            target.setUsername("");
            target.setPassword(token);
        } else {
            String user = etUser.getText() != null ? etUser.getText().toString().trim() : "";
            String pass = etPass.getText() != null ? etPass.getText().toString() : "";
            target.setUsername(user);
            target.setPassword(pass);
        }
    }

    private void testProxyConnection() {
        String host = etHost.getText() != null ? etHost.getText().toString().trim() : "";
        String portStr = etPort.getText() != null ? etPort.getText().toString().trim() : "";
        int port = parseValidPort(portStr);
        if (host.isEmpty() || port == -1) {
            updateTestConnectionVisibility();
            return;
        }
        if (!validateAuthInputs(true)) {
            return;
        }
        tilPort.setError(null);
        isConnectionTestPassed = false;
        updateSaveAndConnectButtonState();

        ProxyConfig probe = new ProxyConfig();
        probe.setProtocol(selectedProtocol);
        probe.setHost(host);
        probe.setPort(port);
        applyAuthToConfig(probe);

        btnTestConnection.setEnabled(false);
        cardTestConnectionStatus.setVisibility(View.VISIBLE);
        cardTestConnectionStatus.setStrokeColor(ContextCompat.getColor(this, R.color.md_theme_outline));
        progressTestConnection.setVisibility(View.VISIBLE);
        tvTestConnectionResult.setText(R.string.msg_testing_connection);
        tvTestConnectionResult.setTextColor(ContextCompat.getColor(this, R.color.md_theme_onSurface));

        testExecutor.execute(() -> {
            ProxyTester.TestResult result = ProxyTester.testProxy(probe, 7000);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                btnTestConnection.setEnabled(true);
                progressTestConnection.setVisibility(View.GONE);
                if (result.isSuccess()) {
                    applyTestSuccessResult(
                            result.getLatencyMs(),
                            result.getExitIp(),
                            result.getCountry(),
                            result.getDetectedProtocol()
                    );
                } else {
                    applyTestFailureResult(result.getErrorMessage());
                }
            });
        });
    }

    @VisibleForTesting
    public void applyTestSuccessResult(long latencyMs, @Nullable String exitIp, @Nullable Protocol detectedProtocol) {
        applyTestSuccessResult(latencyMs, exitIp, "", detectedProtocol);
    }

    private void applyTestSuccessResult(long latencyMs, @Nullable String exitIp,
                                        @Nullable String country, @Nullable Protocol detectedProtocol) {
        boolean wasAutoDetect = selectedProtocol != null && selectedProtocol.isAutoDetect();
        if (wasAutoDetect && detectedProtocol != null && !detectedProtocol.isAutoDetect()) {
            selectedProtocol = detectedProtocol;
            if (actvProtocol != null) {
                actvProtocol.setText(selectedProtocol.getDisplayLabel(this), false);
            }
        }
        isConnectionTestPassed = true;
        testedLatencyMs = latencyMs;
        testedExitIp = exitIp != null ? exitIp : "";
        testedCountry = country != null ? country : "";

        if (tilPort != null) {
            tilPort.setError(null);
        }
        if (cardTestConnectionStatus != null) {
            cardTestConnectionStatus.setVisibility(View.VISIBLE);
            cardTestConnectionStatus.setStrokeColor(ContextCompat.getColor(this, R.color.status_connected));
        }
        if (tvTestConnectionResult != null) {
            tvTestConnectionResult.setTextColor(ContextCompat.getColor(this, R.color.status_connected));
            if (wasAutoDetect && detectedProtocol != null) {
                String protoLabel = detectedProtocol.getDisplayLabel(this);
                if (!testedExitIp.isEmpty()) {
                    tvTestConnectionResult.setText(getString(
                            R.string.msg_test_connection_ok_proto_ip, protoLabel, latencyMs, testedExitIp));
                } else {
                    tvTestConnectionResult.setText(getString(
                            R.string.msg_test_connection_ok_proto, protoLabel, latencyMs));
                }
            } else if (!testedExitIp.isEmpty()) {
                tvTestConnectionResult.setText(getString(
                        R.string.msg_test_connection_ok_ip, latencyMs, testedExitIp));
            } else {
                tvTestConnectionResult.setText(getString(
                        R.string.msg_test_connection_ok, latencyMs));
            }
        }
        updateSaveAndConnectButtonState();
    }

    @VisibleForTesting
    public void applyTestFailureResult(@Nullable String errorMessage) {
        isConnectionTestPassed = false;
        testedLatencyMs = ProxyConfig.LATENCY_FAILED;
        updateSaveAndConnectButtonState();
        String err = (errorMessage == null || errorMessage.trim().isEmpty())
                ? getString(R.string.latency_failed)
                : errorMessage.trim();
        if (isPortTimeoutError(err)) {
            if (cardTestConnectionStatus != null) {
                cardTestConnectionStatus.setVisibility(View.GONE);
            }
            if (tilPort != null) {
                tilPort.setError(err);
            }
        } else {
            String lower = err.toLowerCase(Locale.ROOT);
            boolean authRelated = lower.contains("407") || lower.contains("xác thực")
                    || lower.contains("tài khoản") || lower.contains("mật khẩu")
                    || lower.contains("token");
            if (authRelated && selectedAuthMethod == AuthMethod.NONE && tilAuthMethod != null) {
                tilAuthMethod.setError(getString(R.string.error_proxy_requires_auth_short));
            }
            if (cardTestConnectionStatus != null) {
                cardTestConnectionStatus.setVisibility(View.VISIBLE);
                cardTestConnectionStatus.setStrokeColor(ContextCompat.getColor(this, R.color.status_error));
            }
            if (tvTestConnectionResult != null) {
                tvTestConnectionResult.setTextColor(ContextCompat.getColor(this, R.color.status_error));
                tvTestConnectionResult.setText(getString(R.string.msg_test_connection_failed, err));
            }
        }
    }

    public static boolean isPortTimeoutError(@Nullable String errorMessage) {
        if (errorMessage == null || errorMessage.trim().isEmpty()) {
            return false;
        }
        String lower = errorMessage.toLowerCase(Locale.ROOT);
        return lower.contains("timeout connecting to the specified port")
                || lower.contains("timeout connecting")
                || lower.contains("connect timed out")
                || lower.contains("timed out")
                || lower.contains("timeout")
                || lower.contains("quá thời gian")
                || lower.contains("failed to connect");
    }

    private void loadExistingProxy(long proxyId, boolean populateFields) {
        loaderExecutor.execute(() -> {
            ProxyConfig loaded = repository.getProxyByIdSync(proxyId);
            if (loaded == null) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        finish();
                    }
                });
                return;
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                editingProxy = loaded.copy();
                if (populateFields) {
                    etName.setText(editingProxy.getName());
                    applyProtocolSelection(editingProxy.getProtocol());
                    etHost.setText(editingProxy.getHost());
                    etPort.setText(String.valueOf(editingProxy.getPort()));
                    applyAuthMethodSelection(editingProxy.getAuthMethod());
                    if (editingProxy.getAuthMethod() == AuthMethod.BEARER_TOKEN) {
                        etUser.setText("");
                        etPass.setText("");
                        if (etToken != null) {
                            etToken.setText(editingProxy.getAuthToken());
                        }
                    } else {
                        etUser.setText(editingProxy.getUsername());
                        etPass.setText(editingProxy.getPassword());
                        if (etToken != null) {
                            etToken.setText("");
                        }
                    }
                    applyDnsModeSelection(editingProxy.getDnsMode());
                    etCustomDns.setText(editingProxy.getCustomDns());
                    updateTestConnectionVisibility();
                }
            });
        });
    }

    private void pasteAndParseFromClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip()) {
            ClipData clip = cm.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence text = clip.getItemAt(0).coerceToText(this);
                String rawClip = text != null ? text.toString() : "";
                ProxyConfig parsed = ProxyConfig.parseFromLine(rawClip);
                if (parsed != null) {
                    if (rawClip.contains("://")) {
                        applyProtocolSelection(parsed.getProtocol());
                    } else {
                        resetTestConnectionStatus();
                    }
                    etHost.setText(parsed.getHost());
                    etPort.setText(String.valueOf(parsed.getPort()));
                    if (parsed.hasAuth()) {
                        applyAuthMethodSelection(AuthMethod.USER_PASS);
                        etUser.setText(parsed.getUsername());
                        etPass.setText(parsed.getPassword());
                        if (etToken != null) etToken.setText("");
                    } else {
                        applyAuthMethodSelection(AuthMethod.NONE);
                        etUser.setText("");
                        etPass.setText("");
                        if (etToken != null) etToken.setText("");
                    }
                    tilHost.setError(null);
                    tilPort.setError(null);
                    clearAuthErrors();
                    updateTestConnectionVisibility();
                    Toast.makeText(this, R.string.msg_clipboard_parsed, Toast.LENGTH_SHORT).show();
                    return;
                }
            }
        }
        Toast.makeText(this, R.string.msg_clipboard_invalid, Toast.LENGTH_SHORT).show();
    }

    private void validateAndSave(boolean connectAfterSave) {
        if (connectAfterSave && !isConnectionTestPassed) {
            Toast.makeText(this, R.string.msg_connect_requires_test, Toast.LENGTH_SHORT).show();
            updateSaveAndConnectButtonState();
            return;
        }
        String host = etHost.getText() != null ? etHost.getText().toString().trim() : "";
        String portStr = etPort.getText() != null ? etPort.getText().toString().trim() : "";
        String name = etName.getText() != null ? etName.getText().toString().trim() : "";
        String customDns = etCustomDns.getText() != null ? etCustomDns.getText().toString().trim() : "";

        if (host.isEmpty()) {
            tilHost.setError(getString(R.string.error_host_required));
            etHost.requestFocus();
            return;
        }
        tilHost.setError(null);

        if (portStr.isEmpty()) {
            tilPort.setError(getString(R.string.error_port_required));
            etPort.requestFocus();
            return;
        }
        int port = parseValidPort(portStr);
        if (port == -1) {
            tilPort.setError(getString(R.string.error_port_invalid));
            etPort.requestFocus();
            return;
        }
        tilPort.setError(null);

        if (!validateAuthInputs(true)) {
            return;
        }

        if (selectedDnsMode == DnsMode.CUSTOM) {
            if (!ProxyConfig.isValidCustomDnsInput(customDns)) {
                tilCustomDns.setError(getString(R.string.error_custom_dns_invalid));
                etCustomDns.requestFocus();
                return;
            }
            tilCustomDns.setError(null);
        } else {
            tilCustomDns.setError(null);
            customDns = "";
        }

        ProxyConfig result = editingProxy != null ? editingProxy : new ProxyConfig();
        result.setName(name);
        result.setProtocol(selectedProtocol);
        result.setHost(host);
        result.setPort(port);
        applyAuthToConfig(result);
        result.setDnsMode(selectedDnsMode);
        result.setCustomDns(customDns);
        if (isConnectionTestPassed && testedLatencyMs > 0) {
            result.setLatencyMs(testedLatencyMs);
            if (!testedExitIp.isEmpty()) {
                result.setExitIp(testedExitIp);
            }
            if (!testedCountry.isEmpty()) {
                result.setCountry(testedCountry);
            }
            result.setLastCheckedAt(System.currentTimeMillis());
            result.setFailCount(0);
        }

        isSaving = true;
        btnSave.setEnabled(false);
        updateSaveAndConnectButtonState();
        repository.addOrUpdateProxy(result, () -> {
            if (connectAfterSave && result.getId() > 0) {
                repository.markActiveProxySync(result.getId());
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                Toast.makeText(this, R.string.msg_proxy_saved, Toast.LENGTH_SHORT).show();
                if (connectAfterSave && result.getId() > 0) {
                    pendingConnectProxy = result;
                    connectSavedProxyImmediately(result.getId());
                } else {
                    setResult(RESULT_OK);
                    finish();
                }
            });
        });
    }

    private void connectSavedProxyImmediately(long proxyId) {
        ConnectionStatus currentStatus = ProxyRuntimeManager.getInstance().getCurrentStatus();
        if (currentStatus.isRunning()) {
            ProxyVpnService.switchToProxy(this, proxyId);
            setResult(RESULT_OK);
            finish();
        } else {
            ProxyRuntimeManager.getInstance().postStatus(new ConnectionStatus(
                    ConnectionStatus.State.CONNECTING,
                    pendingConnectProxy,
                    0, 0L, 0L, 0, "", 0L
            ));
            startVpnWithPermissions(proxyId);
        }
    }

    private void startVpnWithPermissions(long proxyId) {
        pendingConnectProxyId = proxyId;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                return;
            }
        }
        proceedWithVpnPreparation(proxyId);
    }

    private void proceedWithVpnPreparation(long proxyId) {
        Intent prepareIntent = VpnService.prepare(this);
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent);
        } else {
            launchVpnAfterPermissions(proxyId);
        }
    }

    private void launchVpnAfterPermissions(long proxyId) {
        try {
            ProxyVpnService.startVpn(this, proxyId);
        } catch (Exception e) {
            String err = e.getMessage() != null ? e.getMessage() : getString(R.string.status_error);
            ProxyRuntimeManager.getInstance().postDisconnected(pendingConnectProxy, err);
        } finally {
            pendingConnectProxy = null;
        }
        if (!isFinishing() && !isDestroyed()) {
            setResult(RESULT_OK);
            finish();
        }
    }

    private static int parseValidPort(String raw) {
        if (raw == null) return -1;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return -1;
        try {
            long parsed = Long.parseLong(trimmed);
            if (parsed >= 1L && parsed <= 65535L) {
                return (int) parsed;
            }
            return -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    protected void onDestroy() {
        loaderExecutor.shutdownNow();
        testExecutor.shutdownNow();
        super.onDestroy();
    }
}
