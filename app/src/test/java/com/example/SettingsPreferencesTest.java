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
import com.example.ui.SettingsUiHelper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Locale;

/**
 * Unit tests verifying system-time theme mode, system language default,
 * multi-language localization resolution, and SQLite persistence in SettingsPreferences.
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
    public void appLanguage_defaultsToSystemAndPersistsAllLanguagesInSqlite() {
        Context context = ApplicationProvider.getApplicationContext();
        ProxyDatabaseHelper db = ProxyDatabaseHelper.getInstance(context);
        db.deleteSetting(SettingsPreferences.SQLITE_KEY_LANGUAGE_TAG);

        SettingsPreferences prefs = new SettingsPreferences(context);
        assertEquals(AppLanguage.SYSTEM, prefs.getAppLanguage());
        assertEquals(AppLanguage.ID, AppLanguage.fromTag("in"));
        assertEquals(AppLanguage.ID, AppLanguage.fromTag("ID"));

        AppLanguage[] allLanguages = new AppLanguage[]{
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

        for (AppLanguage lang : allLanguages) {
            prefs.setAppLanguage(lang);
            assertEquals(lang, prefs.getAppLanguage());
            assertEquals(lang.getTag(), db.getSetting(SettingsPreferences.SQLITE_KEY_LANGUAGE_TAG, ""));
            assertFalse(SettingsUiHelper.formatLanguageLabel(context, lang).trim().isEmpty());

            Locale resolved = SettingsPreferences.resolveEffectiveLocale(context);
            assertEquals(Locale.forLanguageTag(lang.getTag()).getLanguage(), resolved.getLanguage());
        }

        prefs.setAppLanguage(AppLanguage.KO);
        assertEquals("설정", SettingsPreferences.getLocalizedContext(context).getString(R.string.title_settings));
        prefs.setAppLanguage(AppLanguage.JA);
        assertEquals("設定", SettingsPreferences.getLocalizedContext(context).getString(R.string.title_settings));
        prefs.setAppLanguage(AppLanguage.DE);
        assertEquals("Einstellungen", SettingsPreferences.getLocalizedContext(context).getString(R.string.title_settings));
        prefs.setAppLanguage(AppLanguage.ES);
        assertEquals("Ajustes", SettingsPreferences.getLocalizedContext(context).getString(R.string.title_settings));
        prefs.setAppLanguage(AppLanguage.ID);
        assertEquals("Pengaturan", SettingsPreferences.getLocalizedContext(context).getString(R.string.title_settings));
        prefs.setAppLanguage(AppLanguage.IT);
        assertEquals("Impostazioni", SettingsPreferences.getLocalizedContext(context).getString(R.string.title_settings));
        prefs.setAppLanguage(AppLanguage.AR);
        assertEquals("الإعدادات", SettingsPreferences.getLocalizedContext(context).getString(R.string.title_settings));
        prefs.setAppLanguage(AppLanguage.HI);
        assertEquals("सेटिंग्स", SettingsPreferences.getLocalizedContext(context).getString(R.string.title_settings));
        prefs.setAppLanguage(AppLanguage.PT);
        assertEquals("Definições", SettingsPreferences.getLocalizedContext(context).getString(R.string.title_settings));
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
