package com.example.ui;

import android.content.Context;
import android.content.Intent;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AlertDialog;

import com.example.BuildConfig;
import com.example.R;
import com.example.auth.GoogleAuthManager;
import com.example.data.ProxyLogFormatter;
import com.example.data.ProxyRepository;
import com.example.data.SettingsPreferences;
import com.example.data.SettingsPreferences.AppLanguage;
import com.example.data.SettingsPreferences.NotificationType;
import com.example.data.SettingsPreferences.ThemeMode;
import com.example.model.FirewallRule;
import com.example.model.ProxyLogEntry;
import com.example.network.PrivateNetworkMatcher;
import com.example.network.ProxyRuntimeManager;
import com.example.network.ProxyVpnService;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.List;
import java.util.Locale;

/**
 * Shared Pure Java helper binding the grouped Settings screen rows and
 * Material selection dialogs for both SettingsFragment and SettingsActivity.
 */
public final class SettingsUiHelper {

    private static final int[] VPN_TIMER_PRESET_MINUTES = {
            SettingsPreferences.VPN_TIMER_ALWAYS,
            15,
            30,
            60,
            120,
            240
    };

    private SettingsUiHelper() {
    }

    public static void bind(@NonNull Context context, @NonNull View root) {
        SettingsPreferences settingsPrefs = new SettingsPreferences(context);
        ProxyRepository repository = ProxyRepository.getInstance(context);

        View rowAccount = root.findViewById(R.id.row_setting_account);

        View rowTheme = root.findViewById(R.id.row_setting_theme);
        TextView tvThemeValue = root.findViewById(R.id.tv_theme_value);

        View rowLanguage = root.findViewById(R.id.row_setting_language);
        TextView tvLanguageValue = root.findViewById(R.id.tv_language_value);

        View rowAutoStop = root.findViewById(R.id.card_auto_stop_disconnect);
        MaterialSwitch switchAutoStop = root.findViewById(R.id.switch_auto_stop_disconnect);

        View rowVpnTimer = root.findViewById(R.id.row_setting_vpn_timer);
        TextView tvVpnTimerValue = root.findViewById(R.id.tv_vpn_timer_value);

        View rowNotifications = root.findViewById(R.id.row_setting_notifications);

        View rowFirewallEnable = root.findViewById(R.id.row_firewall_enable);
        MaterialSwitch switchFirewall = root.findViewById(R.id.switch_firewall_enabled);

        View rowFirewallRules = root.findViewById(R.id.row_firewall_rules);
        TextView tvFirewallRulesValue = root.findViewById(R.id.tv_firewall_rules_value);

        View rowPrivateRouting = root.findViewById(R.id.row_private_routing);
        TextView tvPrivateRoutingSubtitle = root.findViewById(R.id.tv_private_routing_subtitle);
        MaterialSwitch switchPrivateRouting = root.findViewById(R.id.switch_private_routing);

        TextView tvVersion = root.findViewById(R.id.tv_settings_version);
        tvVersion.setText(context.getString(R.string.label_app_version, BuildConfig.VERSION_NAME));

        refreshSummaries(context, root);

        switchAutoStop.setOnCheckedChangeListener((buttonView, isChecked) ->
                settingsPrefs.setAutoStopOnDisconnectEnabled(isChecked));
        rowAutoStop.setOnClickListener(v -> switchAutoStop.toggle());

        if (rowAccount != null) {
            rowAccount.setOnClickListener(v ->
                    context.startActivity(new Intent(context, GoogleLoginActivity.class)));
            FirebaseAuth auth = GoogleAuthManager.getFirebaseAuth(context);
            if (auth != null) {
                FirebaseAuth.AuthStateListener authListener =
                        firebaseAuth -> renderAccountSummary(context, root, firebaseAuth.getCurrentUser());
                rowAccount.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(@NonNull View v) {
                        auth.addAuthStateListener(authListener);
                        renderAccountSummary(context, root, auth.getCurrentUser());
                    }

                    @Override
                    public void onViewDetachedFromWindow(@NonNull View v) {
                        auth.removeAuthStateListener(authListener);
                    }
                });
            }
        }

        if (rowNotifications != null) {
            rowNotifications.setOnClickListener(v ->
                    context.startActivity(new Intent(context, NotificationsSettingsActivity.class)));
        }

        rowTheme.setOnClickListener(v -> showThemeDialog(context, settingsPrefs, tvThemeValue));
        rowLanguage.setOnClickListener(v -> showLanguageDialog(context, settingsPrefs, tvLanguageValue));
        rowVpnTimer.setOnClickListener(v -> showVpnTimerDialog(context, settingsPrefs, tvVpnTimerValue));

        switchFirewall.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!buttonView.isPressed() && isChecked == settingsPrefs.isFirewallEnabled()) {
                return;
            }
            settingsPrefs.setFirewallEnabled(isChecked);
            if (isChecked) {
                int enabledRules = 0;
                for (FirewallRule r : repository.getFirewallRulesSync()) {
                    if (r.isEnabled()) enabledRules++;
                }
                repository.addLog(ProxyLogEntry.Level.INFO,
                        ProxyLogFormatter.getString(context, R.string.log_firewall_toggled_on, enabledRules));
            } else {
                repository.addLog(ProxyLogEntry.Level.INFO,
                        ProxyLogFormatter.getString(context, R.string.log_firewall_toggled_off));
            }
            reapplyVpnPoliciesIfRunning(context);
        });
        rowFirewallEnable.setOnClickListener(v -> switchFirewall.toggle());

        rowFirewallRules.setOnClickListener(v -> {
            Intent intent = new Intent(context, FirewallRulesActivity.class);
            context.startActivity(intent);
        });

        switchPrivateRouting.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!buttonView.isPressed() && isChecked == settingsPrefs.isPrivateNetworkRoutingEnabled()) {
                return;
            }
            settingsPrefs.setPrivateNetworkRoutingEnabled(isChecked);
            updatePrivateRoutingSubtitle(context, settingsPrefs, tvPrivateRoutingSubtitle);
            if (isChecked) {
                repository.addLog(ProxyLogEntry.Level.INFO,
                        ProxyLogFormatter.getString(context, R.string.log_private_routing_enabled,
                                settingsPrefs.getPrivateNetworkRangesList().size()));
            } else {
                repository.addLog(ProxyLogEntry.Level.INFO,
                        ProxyLogFormatter.getString(context, R.string.log_private_routing_disabled));
            }
            reapplyVpnPoliciesIfRunning(context);
        });
        rowPrivateRouting.setOnClickListener(v ->
                showPrivateRoutingDialog(context, settingsPrefs, repository, switchPrivateRouting, tvPrivateRoutingSubtitle));
    }

    public static void refreshSummaries(@NonNull Context context, @NonNull View root) {
        SettingsPreferences settingsPrefs = new SettingsPreferences(context);
        ProxyRepository repository = ProxyRepository.getInstance(context);

        TextView tvThemeValue = root.findViewById(R.id.tv_theme_value);
        TextView tvLanguageValue = root.findViewById(R.id.tv_language_value);
        MaterialSwitch switchAutoStop = root.findViewById(R.id.switch_auto_stop_disconnect);
        TextView tvVpnTimerValue = root.findViewById(R.id.tv_vpn_timer_value);
        TextView tvNotificationsSummary = root.findViewById(R.id.tv_notifications_summary);
        MaterialSwitch switchFirewall = root.findViewById(R.id.switch_firewall_enabled);
        TextView tvFirewallRulesValue = root.findViewById(R.id.tv_firewall_rules_value);
        TextView tvPrivateRoutingSubtitle = root.findViewById(R.id.tv_private_routing_subtitle);
        MaterialSwitch switchPrivateRouting = root.findViewById(R.id.switch_private_routing);

        if (tvThemeValue != null) {
            tvThemeValue.setText(formatThemeLabel(context, settingsPrefs.getThemeMode()));
        }
        if (tvLanguageValue != null) {
            tvLanguageValue.setText(formatLanguageLabel(context, settingsPrefs.getAppLanguage()));
        }
        if (switchAutoStop != null) {
            switchAutoStop.setChecked(settingsPrefs.isAutoStopOnDisconnectEnabled());
        }
        if (tvVpnTimerValue != null) {
            tvVpnTimerValue.setText(formatTimerLabel(context, settingsPrefs.getVpnTimerMinutes()));
        }
        if (tvNotificationsSummary != null) {
            tvNotificationsSummary.setText(formatNotificationTypesSummary(context, settingsPrefs));
        }
        if (switchFirewall != null) {
            switchFirewall.setChecked(settingsPrefs.isFirewallEnabled());
        }
        if (tvFirewallRulesValue != null) {
            tvFirewallRulesValue.setText(formatFirewallRulesSummary(context, repository.getFirewallRulesSync()));
        }
        if (switchPrivateRouting != null) {
            switchPrivateRouting.setChecked(settingsPrefs.isPrivateNetworkRoutingEnabled());
        }
        if (tvPrivateRoutingSubtitle != null) {
            updatePrivateRoutingSubtitle(context, settingsPrefs, tvPrivateRoutingSubtitle);
        }
        renderAccountSummary(context, root, GoogleAuthManager.getCurrentUser(context));
    }

    public static void renderAccountSummary(
            @NonNull Context context,
            @NonNull View root,
            @Nullable FirebaseUser user) {
        if (user == null) {
            renderAccountState(context, root, false, null, null);
            return;
        }
        renderAccountState(context, root, true, user.getDisplayName(), user.getEmail());
    }

    @VisibleForTesting
    public static void renderAccountState(
            @NonNull Context context,
            @NonNull View root,
            boolean signedIn,
            @Nullable String displayName,
            @Nullable String email) {
        View ivGoogle = root.findViewById(R.id.iv_setting_account_google);
        View cardAvatar = root.findViewById(R.id.card_setting_account_avatar);
        TextView tvAvatar = root.findViewById(R.id.tv_setting_account_avatar);
        TextView tvTitle = root.findViewById(R.id.tv_setting_account_title);
        TextView tvSubtitle = root.findViewById(R.id.tv_setting_account_subtitle);
        if (tvTitle == null || tvSubtitle == null) {
            return;
        }

        if (!signedIn) {
            if (ivGoogle != null) ivGoogle.setVisibility(View.VISIBLE);
            if (cardAvatar != null) cardAvatar.setVisibility(View.GONE);
            tvTitle.setText(R.string.setting_account_sign_in_title);
            tvSubtitle.setText(R.string.setting_account_sign_in_subtitle);
            return;
        }

        String safeName = displayName != null ? displayName.trim() : "";
        String safeEmail = email != null ? email.trim() : "";
        String resolvedTitle;
        String resolvedSubtitle;
        if (!safeName.isEmpty()) {
            resolvedTitle = safeName;
            resolvedSubtitle = !safeEmail.isEmpty()
                    ? safeEmail
                    : context.getString(R.string.setting_account_signed_in_subtitle);
        } else if (!safeEmail.isEmpty()) {
            resolvedTitle = safeEmail;
            resolvedSubtitle = context.getString(R.string.setting_account_signed_in_subtitle);
        } else {
            resolvedTitle = context.getString(R.string.title_google_login);
            resolvedSubtitle = context.getString(R.string.setting_account_signed_in_subtitle);
        }

        String initialSource = !safeName.isEmpty() ? safeName : safeEmail;
        String initial = !initialSource.isEmpty()
                ? initialSource.substring(0, 1).toUpperCase(Locale.getDefault())
                : "G";

        if (ivGoogle != null) ivGoogle.setVisibility(View.GONE);
        if (cardAvatar != null) cardAvatar.setVisibility(View.VISIBLE);
        if (tvAvatar != null) tvAvatar.setText(initial);
        tvTitle.setText(resolvedTitle);
        tvSubtitle.setText(resolvedSubtitle);
    }

    public static String formatNotificationTypesSummary(@NonNull Context context, @NonNull SettingsPreferences prefs) {
        if (!prefs.isNotificationsEnabled()) {
            return context.getString(R.string.setting_notification_types_off);
        }
        NotificationType[] types = NotificationType.values();
        int[] shortLabelRes = new int[]{
                R.string.notif_type_vpn_status_short,
                R.string.notif_type_vpn_countdown_short,
                R.string.notif_type_connection_events_short,
                R.string.notif_type_firewall_alerts_short
        };
        StringBuilder sb = new StringBuilder();
        int enabledCount = 0;
        for (int i = 0; i < types.length; i++) {
            if (prefs.isNotificationTypeEnabled(types[i])) {
                if (enabledCount > 0) {
                    sb.append(", ");
                }
                sb.append(context.getString(shortLabelRes[i]));
                enabledCount++;
            }
        }
        if (enabledCount == 0) {
            return context.getString(R.string.setting_notification_types_none);
        }
        return context.getString(R.string.setting_notification_types_summary,
                enabledCount, types.length, sb.toString());
    }

    private static void updatePrivateRoutingSubtitle(Context context, SettingsPreferences prefs, TextView subtitle) {
        if (subtitle == null) return;
        if (prefs.isPrivateNetworkRoutingEnabled()) {
            int count = prefs.getPrivateNetworkRangesList().size();
            subtitle.setText(context.getString(R.string.setting_private_routing_subtitle_count, count));
        } else {
            subtitle.setText(R.string.setting_private_routing_subtitle);
        }
    }

    private static void showPrivateRoutingDialog(Context context, SettingsPreferences prefs,
                                                 ProxyRepository repository,
                                                 MaterialSwitch switchPrivateRouting,
                                                 TextView tvPrivateRoutingSubtitle) {
        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_private_routing, null);
        MaterialSwitch switchDialog = dialogView.findViewById(R.id.switch_dialog_private_routing);
        TextInputLayout tilRanges = dialogView.findViewById(R.id.til_private_ranges);
        TextInputEditText etRanges = dialogView.findViewById(R.id.et_private_ranges);

        switchDialog.setChecked(prefs.isPrivateNetworkRoutingEnabled());
        etRanges.setText(prefs.getPrivateNetworkRangesText());

        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.title_private_routing_dialog)
                .setView(dialogView)
                .setNeutralButton(R.string.action_reset_default_ranges, null)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_confirm_timer, null)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                tilRanges.setError(null);
                etRanges.setText(PrivateNetworkMatcher.getDefaultRangesText());
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String raw = etRanges.getText() != null ? etRanges.getText().toString().trim() : "";
                String invalid = PrivateNetworkMatcher.findFirstInvalidEntry(raw);
                if (invalid != null) {
                    tilRanges.setError(context.getString(R.string.error_private_ip_ranges_invalid, invalid));
                    return;
                }
                tilRanges.setError(null);
                boolean enabled = switchDialog.isChecked();
                prefs.setPrivateNetworkRangesText(raw);
                prefs.setPrivateNetworkRoutingEnabled(enabled);

                switchPrivateRouting.setChecked(enabled);
                updatePrivateRoutingSubtitle(context, prefs, tvPrivateRoutingSubtitle);
                if (enabled) {
                    repository.addLog(ProxyLogEntry.Level.INFO,
                            ProxyLogFormatter.getString(context, R.string.log_private_routing_enabled,
                                    prefs.getPrivateNetworkRangesList().size()));
                } else {
                    repository.addLog(ProxyLogEntry.Level.INFO,
                            ProxyLogFormatter.getString(context, R.string.log_private_routing_disabled));
                }
                Toast.makeText(context, R.string.msg_private_routing_saved, Toast.LENGTH_SHORT).show();
                reapplyVpnPoliciesIfRunning(context);
                dialog.dismiss();
            });
        });

        dialog.show();
    }

    private static void reapplyVpnPoliciesIfRunning(Context context) {
        if (ProxyRuntimeManager.getInstance().getCurrentStatus().isRunning()) {
            ProxyVpnService.reapplyFirewallAndRouting(context);
        }
    }

    private static void showThemeDialog(Context context, SettingsPreferences prefs, TextView tvThemeValue) {
        String[] options = new String[]{
                context.getString(R.string.theme_system),
                context.getString(R.string.theme_light),
                context.getString(R.string.theme_dark)
        };
        ThemeMode current = prefs.getThemeMode();
        int checkedIndex = current == ThemeMode.LIGHT ? 1 : (current == ThemeMode.DARK ? 2 : 0);

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.section_theme)
                .setSingleChoiceItems(options, checkedIndex, (dialog, which) -> {
                    ThemeMode selected;
                    if (which == 1) {
                        selected = ThemeMode.LIGHT;
                    } else if (which == 2) {
                        selected = ThemeMode.DARK;
                    } else {
                        selected = ThemeMode.SYSTEM_TIME;
                    }
                    dialog.dismiss();
                    if (selected != prefs.getThemeMode()) {
                        prefs.setThemeMode(selected);
                        tvThemeValue.setText(formatThemeLabel(context, selected));
                        SettingsPreferences.applyThemeMode(selected);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private static void showLanguageDialog(Context context, SettingsPreferences prefs, TextView tvLanguageValue) {
        AppLanguage[] languages = new AppLanguage[]{
                AppLanguage.SYSTEM,
                AppLanguage.VI,
                AppLanguage.EN,
                AppLanguage.KO,
                AppLanguage.JA,
                AppLanguage.DE,
                AppLanguage.ES,
                AppLanguage.ID,
                AppLanguage.IT,
                AppLanguage.AR,
                AppLanguage.HI,
                AppLanguage.PT
        };
        String[] options = new String[languages.length];
        AppLanguage current = prefs.getAppLanguage();
        int checkedIndex = 0;
        for (int i = 0; i < languages.length; i++) {
            options[i] = formatLanguageLabel(context, languages[i]);
            if (languages[i] == current) {
                checkedIndex = i;
            }
        }

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.section_language)
                .setSingleChoiceItems(options, checkedIndex, (dialog, which) -> {
                    AppLanguage selected = (which >= 0 && which < languages.length)
                            ? languages[which]
                            : AppLanguage.SYSTEM;
                    dialog.dismiss();
                    if (selected != prefs.getAppLanguage()) {
                        prefs.setAppLanguage(selected);
                        tvLanguageValue.setText(formatLanguageLabel(context, selected));
                        SettingsPreferences.applyLanguage(selected);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private static void showVpnTimerDialog(Context context, SettingsPreferences prefs, TextView tvVpnTimerValue) {
        int currentMinutes = prefs.getVpnTimerMinutes();
        int checkedIndex = VPN_TIMER_PRESET_MINUTES.length;
        for (int i = 0; i < VPN_TIMER_PRESET_MINUTES.length; i++) {
            if (VPN_TIMER_PRESET_MINUTES[i] == currentMinutes) {
                checkedIndex = i;
                break;
            }
        }

        String[] options = new String[VPN_TIMER_PRESET_MINUTES.length + 1];
        for (int i = 0; i < VPN_TIMER_PRESET_MINUTES.length; i++) {
            options[i] = formatTimerLabel(context, VPN_TIMER_PRESET_MINUTES[i]);
        }
        options[VPN_TIMER_PRESET_MINUTES.length] = checkedIndex == VPN_TIMER_PRESET_MINUTES.length
                ? context.getString(R.string.vpn_timer_custom_current, formatTimerLabel(context, currentMinutes))
                : context.getString(R.string.vpn_timer_custom);

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.setting_vpn_timer_title)
                .setSingleChoiceItems(options, checkedIndex, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == VPN_TIMER_PRESET_MINUTES.length) {
                        showCustomVpnTimerDialog(context, prefs, tvVpnTimerValue, currentMinutes);
                    } else {
                        applyVpnTimerSelection(context, prefs, tvVpnTimerValue, VPN_TIMER_PRESET_MINUTES[which]);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private static void showCustomVpnTimerDialog(Context context, SettingsPreferences prefs,
                                                 TextView tvVpnTimerValue, int currentMinutes) {
        EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint(R.string.vpn_timer_custom_hint);
        if (currentMinutes > 0) {
            input.setText(String.valueOf(currentMinutes));
            input.setSelection(input.getText().length());
        }

        int padHorizontal = Math.round(24 * context.getResources().getDisplayMetrics().density);
        int padTop = Math.round(12 * context.getResources().getDisplayMetrics().density);
        FrameLayout container = new FrameLayout(context);
        container.setPadding(padHorizontal, padTop, padHorizontal, 0);
        container.addView(input);

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.vpn_timer_custom_dialog_title)
                .setView(container)
                .setPositiveButton(R.string.action_confirm_timer, (dialog, which) -> {
                    String raw = input.getText() != null ? input.getText().toString().trim() : "";
                    try {
                        int minutes = Integer.parseInt(raw);
                        if (minutes < 1 || minutes > SettingsPreferences.VPN_TIMER_MAX_MINUTES) {
                            Toast.makeText(context, R.string.vpn_timer_custom_invalid, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        applyVpnTimerSelection(context, prefs, tvVpnTimerValue, minutes);
                    } catch (NumberFormatException e) {
                        Toast.makeText(context, R.string.vpn_timer_custom_invalid, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private static void applyVpnTimerSelection(Context context, SettingsPreferences prefs,
                                               TextView tvVpnTimerValue, int minutes) {
        prefs.setVpnTimerMinutes(minutes);
        tvVpnTimerValue.setText(formatTimerLabel(context, prefs.getVpnTimerMinutes()));
        if (ProxyRuntimeManager.getInstance().getCurrentStatus().isRunning()) {
            ProxyVpnService.updateVpnTimer(context);
        }
    }

    public static String formatFirewallRulesSummary(@NonNull Context context, List<FirewallRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return context.getString(R.string.setting_firewall_rules_empty_sub);
        }
        String firstName = rules.get(0).getName();
        if (firstName == null || firstName.isEmpty()) {
            firstName = context.getString(R.string.firewall_rule_default_name);
        }
        if (rules.size() == 1) {
            return context.getString(R.string.setting_firewall_rules_summary_single, firstName);
        }
        return context.getString(R.string.setting_firewall_rules_summary_multi, firstName, rules.size() - 1);
    }

    public static String formatThemeLabel(@NonNull Context context, @NonNull ThemeMode mode) {
        if (mode == ThemeMode.LIGHT) {
            return context.getString(R.string.theme_light);
        }
        if (mode == ThemeMode.DARK) {
            return context.getString(R.string.theme_dark);
        }
        return context.getString(R.string.theme_system);
    }

    public static String formatLanguageLabel(@NonNull Context context, @NonNull AppLanguage language) {
        switch (language) {
            case VI:
                return context.getString(R.string.lang_vietnamese);
            case EN:
                return context.getString(R.string.lang_english);
            case KO:
                return context.getString(R.string.lang_korean);
            case JA:
                return context.getString(R.string.lang_japanese);
            case DE:
                return context.getString(R.string.lang_german);
            case ES:
                return context.getString(R.string.lang_spanish);
            case ID:
                return context.getString(R.string.lang_indonesian);
            case IT:
                return context.getString(R.string.lang_italian);
            case AR:
                return context.getString(R.string.lang_arabic);
            case HI:
                return context.getString(R.string.lang_hindi);
            case PT:
                return context.getString(R.string.lang_portuguese);
            case SYSTEM:
            default:
                return context.getString(R.string.lang_system);
        }
    }

    public static String formatTimerLabel(@NonNull Context context, int minutes) {
        if (minutes <= SettingsPreferences.VPN_TIMER_ALWAYS) {
            return context.getString(R.string.vpn_timer_always);
        }
        int hours = minutes / 60;
        int remMinutes = minutes % 60;
        if (hours > 0 && remMinutes > 0) {
            return context.getString(R.string.vpn_timer_hours_minutes, hours, remMinutes);
        }
        if (hours > 0) {
            return context.getString(R.string.vpn_timer_hours, hours);
        }
        return context.getString(R.string.vpn_timer_minutes, minutes);
    }
}
