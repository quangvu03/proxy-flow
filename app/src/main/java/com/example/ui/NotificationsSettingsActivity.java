package com.example.ui;

import android.Manifest;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.R;
import com.example.data.SettingsPreferences;
import com.example.data.SettingsPreferences.NotificationType;
import com.example.network.ProxyNotificationManager;
import com.example.network.ProxyRuntimeManager;
import com.example.network.ProxyVpnService;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * Dedicated screen in pure Java for configuring ProxyFlow notifications:
 * master toggle, per-category MaterialSwitch toggles (VPN status, live VPN Timer countdown,
 * connection/disconnect alerts, firewall alerts), Android 13+ permission grant, and test alert.
 */
public class NotificationsSettingsActivity extends AppCompatActivity {

    private SettingsPreferences settingsPrefs;

    private View cardPermissionBanner;
    private View cardCategories;
    private MaterialSwitch switchMaster;
    private MaterialSwitch switchStatus;
    private MaterialSwitch switchCountdown;
    private MaterialSwitch switchConnection;
    private MaterialSwitch switchFirewall;

    private boolean pendingTestAfterPermission = false;

    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                updatePermissionBanner();
                if (granted) {
                    if (ProxyRuntimeManager.getInstance().getCurrentStatus().isRunning()) {
                        ProxyVpnService.refreshNotifications(this);
                    }
                    if (pendingTestAfterPermission) {
                        pendingTestAfterPermission = false;
                        ProxyNotificationManager.sendTestNotification(this);
                        Toast.makeText(this, R.string.msg_test_notification_sent, Toast.LENGTH_SHORT).show();
                    }
                } else {
                    pendingTestAfterPermission = false;
                    Toast.makeText(this, R.string.msg_notif_permission_denied, Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_notifications_settings);

        settingsPrefs = new SettingsPreferences(this);

        MaterialToolbar toolbar = findViewById(R.id.notifications_toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        View root = findViewById(R.id.notifications_settings_root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            toolbar.setPadding(toolbar.getPaddingLeft(), sysBars.top, toolbar.getPaddingRight(), toolbar.getPaddingBottom());
            root.setPadding(sysBars.left, 0, sysBars.right, sysBars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        cardPermissionBanner = findViewById(R.id.card_notif_permission_banner);
        cardCategories = findViewById(R.id.card_notification_categories);
        MaterialButton btnGrantPermission = findViewById(R.id.btn_grant_notif_permission);
        MaterialButton btnTestNotification = findViewById(R.id.btn_test_notification);

        View rowMaster = findViewById(R.id.row_notifications_master);
        switchMaster = findViewById(R.id.switch_notifications_master);

        View rowStatus = findViewById(R.id.row_notif_type_status);
        switchStatus = findViewById(R.id.switch_notif_type_status);

        View rowCountdown = findViewById(R.id.row_notif_type_countdown);
        switchCountdown = findViewById(R.id.switch_notif_type_countdown);

        View rowConnection = findViewById(R.id.row_notif_type_connection);
        switchConnection = findViewById(R.id.switch_notif_type_connection);

        View rowFirewall = findViewById(R.id.row_notif_type_firewall);
        switchFirewall = findViewById(R.id.switch_notif_type_firewall);

        syncUiFromPreferences();

        btnGrantPermission.setOnClickListener(v -> requestNotificationPermission());

        switchMaster.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!buttonView.isPressed() && isChecked == settingsPrefs.isNotificationsEnabled()) {
                return;
            }
            settingsPrefs.setNotificationsEnabled(isChecked);
            updateCategoryCardState(isChecked);
            if (isChecked) {
                requestPermissionIfMissing();
            }
            refreshVpnNotificationIfRunning();
        });
        rowMaster.setOnClickListener(v -> switchMaster.toggle());

        bindCategoryRow(rowStatus, switchStatus, NotificationType.VPN_STATUS);
        bindCategoryRow(rowCountdown, switchCountdown, NotificationType.VPN_TIMER_COUNTDOWN);
        bindCategoryRow(rowConnection, switchConnection, NotificationType.CONNECTION_EVENTS);
        bindCategoryRow(rowFirewall, switchFirewall, NotificationType.FIREWALL_ALERTS);

        btnTestNotification.setOnClickListener(v -> {
            if (!ProxyNotificationManager.hasNotificationPermission(this)) {
                pendingTestAfterPermission = true;
                requestNotificationPermission();
                return;
            }
            ProxyNotificationManager.sendTestNotification(this);
            Toast.makeText(this, R.string.msg_test_notification_sent, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncUiFromPreferences();
    }

    private void syncUiFromPreferences() {
        boolean master = settingsPrefs.isNotificationsEnabled();
        switchMaster.setChecked(master);
        switchStatus.setChecked(settingsPrefs.isNotificationTypeEnabled(NotificationType.VPN_STATUS));
        switchCountdown.setChecked(settingsPrefs.isNotificationTypeEnabled(NotificationType.VPN_TIMER_COUNTDOWN));
        switchConnection.setChecked(settingsPrefs.isNotificationTypeEnabled(NotificationType.CONNECTION_EVENTS));
        switchFirewall.setChecked(settingsPrefs.isNotificationTypeEnabled(NotificationType.FIREWALL_ALERTS));
        updateCategoryCardState(master);
        updatePermissionBanner();
    }

    private void bindCategoryRow(View row, MaterialSwitch sw, NotificationType type) {
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!buttonView.isPressed() && isChecked == settingsPrefs.isNotificationTypeEnabled(type)) {
                return;
            }
            settingsPrefs.setNotificationTypeEnabled(type, isChecked);
            if (isChecked) {
                if (!settingsPrefs.isNotificationsEnabled()) {
                    settingsPrefs.setNotificationsEnabled(true);
                    switchMaster.setChecked(true);
                    updateCategoryCardState(true);
                }
                requestPermissionIfMissing();
            } else if (settingsPrefs.getEnabledNotificationTypesCount() == 0 && settingsPrefs.isNotificationsEnabled()) {
                settingsPrefs.setNotificationsEnabled(false);
                switchMaster.setChecked(false);
                updateCategoryCardState(false);
            }
            refreshVpnNotificationIfRunning();
        });
        row.setOnClickListener(v -> sw.toggle());
    }

    private void updateCategoryCardState(boolean masterEnabled) {
        cardCategories.setAlpha(masterEnabled ? 1f : 0.6f);
    }

    private void updatePermissionBanner() {
        boolean missing = !ProxyNotificationManager.hasNotificationPermission(this);
        cardPermissionBanner.setVisibility(missing ? View.VISIBLE : View.GONE);
    }

    private void requestPermissionIfMissing() {
        if (!ProxyNotificationManager.hasNotificationPermission(this)) {
            requestNotificationPermission();
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    private void refreshVpnNotificationIfRunning() {
        if (ProxyRuntimeManager.getInstance().getCurrentStatus().isRunning()) {
            ProxyVpnService.refreshNotifications(this);
        }
    }
}
