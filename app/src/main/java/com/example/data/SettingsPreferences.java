package com.example.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.example.model.AppProxyRule;

import java.util.HashSet;
import java.util.Set;

/**
 * SharedPreferences store for the manually selected active proxy ID
 * and per-app proxy allow/block rules.
 */
public class SettingsPreferences {

    private static final String PREF_NAME = "proxyflow_prefs";
    private static final String KEY_ACTIVE_PROXY_ID = "active_proxy_id";
    private static final String KEY_APP_FILTER_MODE = "app_filter_mode";
    private static final String KEY_APP_FILTER_PACKAGES = "app_filter_packages";

    private final SharedPreferences prefs;

    public SettingsPreferences(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
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
}
