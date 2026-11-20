package com.example.data;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import com.example.model.AppProxyRule;

import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;

/**
 * Settings persistence in 100% Java:
 * - Theme mode defaults to SYSTEM_TIME (06:00-17:59 Light, 18:00-05:59 Dark) and saves
 *   user overrides in SQLite (app_settings table).
 * - App language defaults to SYSTEM (device system language) and saves user overrides
 *   in SQLite (app_settings table).
 * - Active proxy ID and per-app VPN rules continue to be stored reliably.
 */
public class SettingsPreferences {

    public static final int DAY_START_HOUR = 6;
    public static final int NIGHT_START_HOUR = 18;

    public enum ThemeMode {
        SYSTEM_TIME,
        LIGHT,
        DARK;

        public static ThemeMode fromStoredValue(String raw) {
            if ("LIGHT".equalsIgnoreCase(raw)) return LIGHT;
            if ("DARK".equalsIgnoreCase(raw)) return DARK;
            return SYSTEM_TIME;
        }
    }

    public enum AppLanguage {
        SYSTEM("system"),
        VI("vi"),
        EN("en");

        private final String tag;

        AppLanguage(String tag) {
            this.tag = tag;
        }

        public String getTag() {
            return tag;
        }

        public static AppLanguage fromTag(String tag) {
            if ("vi".equalsIgnoreCase(tag)) return VI;
            if ("en".equalsIgnoreCase(tag)) return EN;
            return SYSTEM;
        }
    }

    private static final String PREF_NAME = "proxyflow_prefs";
    private static final String KEY_ACTIVE_PROXY_ID = "active_proxy_id";
    private static final String KEY_APP_FILTER_MODE = "app_filter_mode";
    private static final String KEY_APP_FILTER_PACKAGES = "app_filter_packages";

    public static final String SQLITE_KEY_THEME_MODE = "theme_mode";
    public static final String SQLITE_KEY_LANGUAGE_TAG = "language_tag";
    public static final String SQLITE_KEY_AUTO_STOP_DISCONNECT = "auto_stop_on_disconnect";
    public static final String SQLITE_KEY_VPN_TIMER_MINUTES = "vpn_timer_minutes";
    public static final int VPN_TIMER_ALWAYS = 0;
    public static final int VPN_TIMER_MAX_MINUTES = 1440;

    private final SharedPreferences prefs;
    private final ProxyDatabaseHelper dbHelper;

    public SettingsPreferences(Context context) {
        Context appCtx = context.getApplicationContext();
        this.prefs = appCtx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        this.dbHelper = ProxyDatabaseHelper.getInstance(appCtx);
    }

    public long getActiveProxyId() {
        return prefs.getLong(KEY_ACTIVE_PROXY_ID, -1L);
    }

    public void setActiveProxyId(long proxyId) {
        prefs.edit().putLong(KEY_ACTIVE_PROXY_ID, proxyId).apply();
    }

    public AppProxyRule loadAppProxyRule() {
        String modeStr = prefs.getString(KEY_APP_FILTER_MODE, AppProxyRule.Mode.BLOCK_SELECTED.name());
        AppProxyRule.Mode mode;
        try {
            mode = AppProxyRule.Mode.valueOf(modeStr);
        } catch (IllegalArgumentException e) {
            mode = AppProxyRule.Mode.BLOCK_SELECTED;
        }
        Set<String> stored = prefs.getStringSet(KEY_APP_FILTER_PACKAGES, null);
        Set<String> copy = stored != null ? new HashSet<>(stored) : new HashSet<>();
        return new AppProxyRule(mode, copy);
    }

    public void saveAppProxyRule(AppProxyRule rule) {
        if (rule == null) return;
        prefs.edit()
                .putString(KEY_APP_FILTER_MODE, rule.getMode().name())
                .putStringSet(KEY_APP_FILTER_PACKAGES, new HashSet<>(rule.getSelectedPackages()))
                .apply();
    }

    /**
     * Reads saved ThemeMode from SQLite. Defaults to SYSTEM_TIME if the user has not customized it.
     */
    public ThemeMode getThemeMode() {
        String raw = dbHelper.getSetting(SQLITE_KEY_THEME_MODE, ThemeMode.SYSTEM_TIME.name());
        return ThemeMode.fromStoredValue(raw);
    }

    /**
     * Persists the user-chosen ThemeMode into SQLite.
     */
    public void setThemeMode(ThemeMode mode) {
        if (mode == null) mode = ThemeMode.SYSTEM_TIME;
        dbHelper.putSetting(SQLITE_KEY_THEME_MODE, mode.name());
    }

    /**
     * Reads saved AppLanguage from SQLite. Defaults to SYSTEM if the user has not customized it.
     */
    public AppLanguage getAppLanguage() {
        String raw = dbHelper.getSetting(SQLITE_KEY_LANGUAGE_TAG, AppLanguage.SYSTEM.getTag());
        return AppLanguage.fromTag(raw);
    }

    /**
     * Persists the user-chosen AppLanguage into SQLite.
     */
    public void setAppLanguage(AppLanguage language) {
        if (language == null) language = AppLanguage.SYSTEM;
        dbHelper.putSetting(SQLITE_KEY_LANGUAGE_TAG, language.getTag());
    }

    /**
     * Reads whether the VPN service should automatically stop when disconnected.
     * Defaults to false until enabled by the user.
     */
    public boolean isAutoStopOnDisconnectEnabled() {
        String raw = dbHelper.getSetting(SQLITE_KEY_AUTO_STOP_DISCONNECT, "false");
        return Boolean.parseBoolean(raw);
    }

    /**
     * Persists the auto-stop on disconnect toggle into SQLite.
     */
    public void setAutoStopOnDisconnectEnabled(boolean enabled) {
        dbHelper.putSetting(SQLITE_KEY_AUTO_STOP_DISCONNECT, String.valueOf(enabled));
    }

    /**
     * Reads the configured VPN auto-stop timer in minutes from SQLite.
     * Defaults to 0 (VPN_TIMER_ALWAYS = Always / no time limit).
     */
    public int getVpnTimerMinutes() {
        String raw = dbHelper.getSetting(SQLITE_KEY_VPN_TIMER_MINUTES, String.valueOf(VPN_TIMER_ALWAYS));
        try {
            int parsed = Integer.parseInt(raw.trim());
            if (parsed <= 0) {
                return VPN_TIMER_ALWAYS;
            }
            return Math.min(parsed, VPN_TIMER_MAX_MINUTES);
        } catch (NumberFormatException e) {
            return VPN_TIMER_ALWAYS;
        }
    }

    /**
     * Persists the VPN timer duration in minutes into SQLite (0 = Always).
     */
    public void setVpnTimerMinutes(int minutes) {
        int safeMinutes = minutes <= 0 ? VPN_TIMER_ALWAYS : Math.min(minutes, VPN_TIMER_MAX_MINUTES);
        dbHelper.putSetting(SQLITE_KEY_VPN_TIMER_MINUTES, String.valueOf(safeMinutes));
    }

    public static void applySavedThemeAndLocale(Context context) {
        SettingsPreferences settings = new SettingsPreferences(context);
        applyThemeMode(settings.getThemeMode());
        applyLanguage(settings.getAppLanguage());
    }

    public static void applyThemeMode(ThemeMode mode) {
        int currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int targetNightMode = resolveNightMode(mode, currentHour);
        if (AppCompatDelegate.getDefaultNightMode() != targetNightMode) {
            AppCompatDelegate.setDefaultNightMode(targetNightMode);
        }
    }

    /**
     * Resolves the AppCompat night mode for a given ThemeMode and system hour (0-23).
     * SYSTEM_TIME uses Light from 06:00 to 17:59 and Dark from 18:00 to 05:59.
     */
    public static int resolveNightMode(ThemeMode mode, int hourOfDay) {
        if (mode == ThemeMode.LIGHT) {
            return AppCompatDelegate.MODE_NIGHT_NO;
        }
        if (mode == ThemeMode.DARK) {
            return AppCompatDelegate.MODE_NIGHT_YES;
        }
        boolean isDaytime = hourOfDay >= DAY_START_HOUR && hourOfDay < NIGHT_START_HOUR;
        return isDaytime ? AppCompatDelegate.MODE_NIGHT_NO : AppCompatDelegate.MODE_NIGHT_YES;
    }

    public static void applyLanguage(AppLanguage language) {
        if (language == null || language == AppLanguage.SYSTEM) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList());
        } else {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language.getTag()));
        }
    }
}
