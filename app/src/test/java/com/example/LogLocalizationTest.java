package com.example;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.example.data.ProxyLogFormatter;
import com.example.data.SettingsPreferences;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class LogLocalizationTest {

    @After
    public void resetLanguage() {
        Context context = ApplicationProvider.getApplicationContext();
        SettingsPreferences prefs = new SettingsPreferences(context);
        prefs.setAppLanguage(SettingsPreferences.AppLanguage.SYSTEM);
        SettingsPreferences.applyLanguage(SettingsPreferences.AppLanguage.SYSTEM);
    }

    @Test
    public void proxyLogFormatter_usesEnglishWhenAppLanguageIsEnglish() {
        Context context = ApplicationProvider.getApplicationContext();
        SettingsPreferences prefs = new SettingsPreferences(context);
        prefs.setAppLanguage(SettingsPreferences.AppLanguage.EN);

        String connected = ProxyLogFormatter.getString(
                context, R.string.log_vpn_connected, "1.2.3.4:8080", 10808);
        String disconnected = ProxyLogFormatter.getString(context, R.string.log_vpn_disconnected);
        String timer = ProxyLogFormatter.getString(
                context, R.string.log_vpn_timer_set, ProxyLogFormatter.formatTimerMinutes(context, 30));

        assertEquals("Connected Fixed Proxy VPN -> 1.2.3.4:8080 (local port :10808)", connected);
        assertEquals("Disconnected Proxy VPN", disconnected);
        assertEquals("VPN Timer active: auto-stop in 30 min", timer);
    }

    @Test
    public void proxyLogFormatter_usesVietnameseWhenAppLanguageIsVietnamese() {
        Context context = ApplicationProvider.getApplicationContext();
        SettingsPreferences prefs = new SettingsPreferences(context);
        prefs.setAppLanguage(SettingsPreferences.AppLanguage.VI);

        String connected = ProxyLogFormatter.getString(
                context, R.string.log_vpn_connected, "1.2.3.4:8080", 10808);
        String disconnected = ProxyLogFormatter.getString(context, R.string.log_vpn_disconnected);
        String timer = ProxyLogFormatter.getString(
                context, R.string.log_vpn_timer_set, ProxyLogFormatter.formatTimerMinutes(context, 30));

        assertEquals("Đã kết nối VPN Proxy cố định -> 1.2.3.4:8080 (cổng cục bộ :10808)", connected);
        assertEquals("Đã ngắt kết nối VPN Proxy", disconnected);
        assertEquals("Hẹn giờ VPN: tự động ngắt sau 30 phút", timer);
    }

    @Test
    public void localizeForDisplay_translatesExistingLogsBetweenVietnameseAndEnglish() {
        Context context = ApplicationProvider.getApplicationContext();
        SettingsPreferences prefs = new SettingsPreferences(context);

        prefs.setAppLanguage(SettingsPreferences.AppLanguage.EN);
        assertEquals(
                "Connected Fixed Proxy VPN -> 10.0.0.1:3128 (local port :9090)",
                ProxyLogFormatter.localizeForDisplay(
                        context,
                        "Đã kết nối VPN Proxy cố định -> 10.0.0.1:3128 (cổng cục bộ :9090)")
        );
        assertEquals(
                "VPN Timer active: auto-stop in 1 hr 30 min",
                ProxyLogFormatter.localizeForDisplay(
                        context,
                        "Hẹn giờ VPN: tự động ngắt sau 1 giờ 30 phút")
        );

        prefs.setAppLanguage(SettingsPreferences.AppLanguage.VI);
        assertEquals(
                "Đã kết nối VPN Proxy cố định -> 10.0.0.1:3128 (cổng cục bộ :9090)",
                ProxyLogFormatter.localizeForDisplay(
                        context,
                        "Connected Fixed Proxy VPN -> 10.0.0.1:3128 (local port :9090)")
        );
        assertTrue(
                ProxyLogFormatter.localizeForDisplay(
                        context,
                        "Firewall blocked com.example.app from accessing example.com:443")
                        .contains("Tường lửa đã chặn com.example.app truy cập example.com:443")
        );
    }
}
