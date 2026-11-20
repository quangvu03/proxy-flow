package com.example;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.test.core.app.ApplicationProvider;

import com.example.data.ProxyDatabaseHelper;
import com.example.data.SettingsPreferences;
import com.example.data.SettingsPreferences.AppLanguage;
import com.example.data.SettingsPreferences.ThemeMode;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Unit tests verifying system-time theme mode, system language default,
 * and SQLite persistence in SettingsPreferences.
 */
@RunWith(RobolectricTestRunner.class)
public class SettingsPreferencesTest {

    @Test
    public void themeMode_defaultsToSystemTimeAndPersistsInSqlite() {
        Context context = ApplicationProvider.getApplicationContext();
        ProxyDatabaseHelper db = ProxyDatabaseHelper.getInstance(context);
        db.deleteSetting(SettingsPreferences.SQLITE_KEY_THEME_MODE);

        SettingsPreferences prefs = new SettingsPreferences(context);
        assertEquals(ThemeMode.SYSTEM_TIME, prefs.getThemeMode());

        // Daytime 06:00 - 17:59 -> Light
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO,
                SettingsPreferences.resolveNightMode(ThemeMode.SYSTEM_TIME, 6));
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO,
                SettingsPreferences.resolveNightMode(ThemeMode.SYSTEM_TIME, 14));
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO,
                SettingsPreferences.resolveNightMode(ThemeMode.SYSTEM_TIME, 17));

        // Nighttime 18:00 - 05:59 -> Dark
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES,
                SettingsPreferences.resolveNightMode(ThemeMode.SYSTEM_TIME, 18));
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES,
                SettingsPreferences.resolveNightMode(ThemeMode.SYSTEM_TIME, 23));
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES,
                SettingsPreferences.resolveNightMode(ThemeMode.SYSTEM_TIME, 5));

        prefs.setThemeMode(ThemeMode.DARK);
        assertEquals(ThemeMode.DARK, prefs.getThemeMode());
        assertTrue(db.hasSetting(SettingsPreferences.SQLITE_KEY_THEME_MODE));
        assertEquals("DARK", db.getSetting(SettingsPreferences.SQLITE_KEY_THEME_MODE, ""));

        prefs.setThemeMode(ThemeMode.LIGHT);
        assertEquals(ThemeMode.LIGHT, prefs.getThemeMode());
        assertEquals("LIGHT", db.getSetting(SettingsPreferences.SQLITE_KEY_THEME_MODE, ""));
    }

    @Test
    public void appLanguage_defaultsToSystemAndPersistsInSqlite() {
        Context context = ApplicationProvider.getApplicationContext();
        ProxyDatabaseHelper db = ProxyDatabaseHelper.getInstance(context);
        db.deleteSetting(SettingsPreferences.SQLITE_KEY_LANGUAGE_TAG);

        SettingsPreferences prefs = new SettingsPreferences(context);
        assertEquals(AppLanguage.SYSTEM, prefs.getAppLanguage());

        prefs.setAppLanguage(AppLanguage.VI);
        assertEquals(AppLanguage.VI, prefs.getAppLanguage());
        assertEquals("vi", db.getSetting(SettingsPreferences.SQLITE_KEY_LANGUAGE_TAG, ""));

        prefs.setAppLanguage(AppLanguage.EN);
        assertEquals(AppLanguage.EN, prefs.getAppLanguage());
        assertEquals("en", db.getSetting(SettingsPreferences.SQLITE_KEY_LANGUAGE_TAG, ""));
    }

    @Test
    public void autoStopOnDisconnect_defaultsToFalseAndPersistsInSqlite() {
        Context context = ApplicationProvider.getApplicationContext();
        ProxyDatabaseHelper db = ProxyDatabaseHelper.getInstance(context);
        db.deleteSetting(SettingsPreferences.SQLITE_KEY_AUTO_STOP_DISCONNECT);

        SettingsPreferences prefs = new SettingsPreferences(context);
        assertFalse(prefs.isAutoStopOnDisconnectEnabled());

        prefs.setAutoStopOnDisconnectEnabled(true);
        assertTrue(prefs.isAutoStopOnDisconnectEnabled());
        assertEquals("true", db.getSetting(SettingsPreferences.SQLITE_KEY_AUTO_STOP_DISCONNECT, ""));

        prefs.setAutoStopOnDisconnectEnabled(false);
        assertFalse(prefs.isAutoStopOnDisconnectEnabled());
        assertEquals("false", db.getSetting(SettingsPreferences.SQLITE_KEY_AUTO_STOP_DISCONNECT, ""));
    }

    @Test
    public void vpnTimerMinutes_defaultsToAlwaysAndPersistsInSqlite() {
        Context context = ApplicationProvider.getApplicationContext();
        ProxyDatabaseHelper db = ProxyDatabaseHelper.getInstance(context);
        db.deleteSetting(SettingsPreferences.SQLITE_KEY_VPN_TIMER_MINUTES);

        SettingsPreferences prefs = new SettingsPreferences(context);
        assertEquals(SettingsPreferences.VPN_TIMER_ALWAYS, prefs.getVpnTimerMinutes());

        prefs.setVpnTimerMinutes(30);
        assertEquals(30, prefs.getVpnTimerMinutes());
        assertEquals("30", db.getSetting(SettingsPreferences.SQLITE_KEY_VPN_TIMER_MINUTES, ""));

        prefs.setVpnTimerMinutes(9999);
        assertEquals(SettingsPreferences.VPN_TIMER_MAX_MINUTES, prefs.getVpnTimerMinutes());

        prefs.setVpnTimerMinutes(SettingsPreferences.VPN_TIMER_ALWAYS);
        assertEquals(SettingsPreferences.VPN_TIMER_ALWAYS, prefs.getVpnTimerMinutes());
        assertEquals("0", db.getSetting(SettingsPreferences.SQLITE_KEY_VPN_TIMER_MINUTES, ""));
    }
}
