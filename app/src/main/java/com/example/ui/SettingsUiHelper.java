package com.example.ui;

import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.example.BuildConfig;
import com.example.R;
import com.example.data.SettingsPreferences;
import com.example.data.SettingsPreferences.AppLanguage;
import com.example.data.SettingsPreferences.ThemeMode;
import com.example.network.ProxyRuntimeManager;
import com.example.network.ProxyVpnService;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * Shared Pure Java helper binding the grouped Settings screen rows and
 * Material single-choice selection dialogs for both SettingsFragment and SettingsActivity.
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

        View rowTheme = root.findViewById(R.id.row_setting_theme);
        TextView tvThemeValue = root.findViewById(R.id.tv_theme_value);

        View rowLanguage = root.findViewById(R.id.row_setting_language);
        TextView tvLanguageValue = root.findViewById(R.id.tv_language_value);

        View rowAutoStop = root.findViewById(R.id.card_auto_stop_disconnect);
        MaterialSwitch switchAutoStop = root.findViewById(R.id.switch_auto_stop_disconnect);

        View rowVpnTimer = root.findViewById(R.id.row_setting_vpn_timer);
        TextView tvVpnTimerValue = root.findViewById(R.id.tv_vpn_timer_value);

        TextView tvVersion = root.findViewById(R.id.tv_settings_version);
        tvVersion.setText(context.getString(R.string.label_app_version, BuildConfig.VERSION_NAME));

        tvThemeValue.setText(formatThemeLabel(context, settingsPrefs.getThemeMode()));
        tvLanguageValue.setText(formatLanguageLabel(context, settingsPrefs.getAppLanguage()));
        tvVpnTimerValue.setText(formatTimerLabel(context, settingsPrefs.getVpnTimerMinutes()));

        switchAutoStop.setChecked(settingsPrefs.isAutoStopOnDisconnectEnabled());
        switchAutoStop.setOnCheckedChangeListener((buttonView, isChecked) ->
                settingsPrefs.setAutoStopOnDisconnectEnabled(isChecked));
        rowAutoStop.setOnClickListener(v -> switchAutoStop.toggle());

        rowTheme.setOnClickListener(v -> showThemeDialog(context, settingsPrefs, tvThemeValue));
        rowLanguage.setOnClickListener(v -> showLanguageDialog(context, settingsPrefs, tvLanguageValue));
        rowVpnTimer.setOnClickListener(v -> showVpnTimerDialog(context, settingsPrefs, tvVpnTimerValue));
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
        String[] options = new String[]{
                context.getString(R.string.lang_system),
                context.getString(R.string.lang_vietnamese),
                context.getString(R.string.lang_english)
        };
        AppLanguage current = prefs.getAppLanguage();
        int checkedIndex = current == AppLanguage.VI ? 1 : (current == AppLanguage.EN ? 2 : 0);

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.section_language)
                .setSingleChoiceItems(options, checkedIndex, (dialog, which) -> {
                    AppLanguage selected;
                    if (which == 1) {
                        selected = AppLanguage.VI;
                    } else if (which == 2) {
                        selected = AppLanguage.EN;
                    } else {
                        selected = AppLanguage.SYSTEM;
                    }
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
        int checkedIndex = VPN_TIMER_PRESET_MINUTES.length; // Custom slot if not matching a preset
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
        if (language == AppLanguage.VI) {
            return context.getString(R.string.lang_vietnamese);
        }
        if (language == AppLanguage.EN) {
            return context.getString(R.string.lang_english);
        }
        return context.getString(R.string.lang_system);
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
