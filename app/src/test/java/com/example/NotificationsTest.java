package com.example;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Notification;
import android.content.Context;
import android.os.Bundle;

import androidx.test.core.app.ApplicationProvider;

import com.example.data.SettingsPreferences;
import com.example.data.SettingsPreferences.NotificationType;
import com.example.model.ProxyConfig;
import com.example.network.ProxyNotificationManager;
import com.example.ui.NotificationsSettingsActivity;
import com.example.ui.SettingsUiHelper;
import com.google.android.material.materialswitch.MaterialSwitch;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

/**
 * Unit tests verifying notification preferences persistence, category summary formatting,
 * live VPN Timer countdown foreground notification construction, and NotificationsSettingsActivity.
 */
@RunWith(RobolectricTestRunner.class)
public class NotificationsTest {

    @Test
    public void notificationPreferences_defaultToEnabledAndPersistPerCategory() {
        Context context = ApplicationProvider.getApplicationContext();
        SettingsPreferences prefs = new SettingsPreferences(context);

        prefs.setNotificationsEnabled(true);
        for (NotificationType type : NotificationType.values()) {
            prefs.setNotificationTypeEnabled(type, true);
            assertTrue(prefs.isNotificationTypeEnabled(type));
            assertTrue(prefs.isNotificationActive(type));
        }
        assertEquals(4, prefs.getEnabledNotificationTypesCount());

        prefs.setNotificationTypeEnabled(NotificationType.FIREWALL_ALERTS, false);
        assertFalse(prefs.isNotificationActive(NotificationType.FIREWALL_ALERTS));
        assertEquals(3, prefs.getEnabledNotificationTypesCount());

        prefs.setNotificationsEnabled(false);
        assertFalse(prefs.isNotificationActive(NotificationType.VPN_TIMER_COUNTDOWN));
        assertFalse(prefs.isNotificationActive(NotificationType.VPN_STATUS));

        String offSummary = SettingsUiHelper.formatNotificationTypesSummary(context, prefs);
        assertTrue(offSummary.contains("tắt") || offSummary.toLowerCase().contains("off"));

        prefs.setNotificationsEnabled(true);
        String activeSummary = SettingsUiHelper.formatNotificationTypesSummary(context, prefs);
        assertTrue(activeSummary.contains("3/4"));
    }

    @Test
    public void buildVpnForegroundNotification_showsCountdownWhenTimerActive() {
        Context context = ApplicationProvider.getApplicationContext();
        SettingsPreferences prefs = new SettingsPreferences(context);
        prefs.setNotificationsEnabled(true);
        prefs.setNotificationTypeEnabled(NotificationType.VPN_STATUS, true);
        prefs.setNotificationTypeEnabled(NotificationType.VPN_TIMER_COUNTDOWN, true);

        ProxyConfig proxy = new ProxyConfig(1L, "VN Proxy", "103.10.10.10", 8080, "", "", true,
                -1L, "", "", 0L, 0, 0);
        long deadlineMs = System.currentTimeMillis() + 15 * 60_000L;

        Notification notif = ProxyNotificationManager.buildVpnForegroundNotification(
                context, proxy, 10808, deadlineMs, 15, null, null);
        assertNotNull(notif);

        Bundle extras = notif.extras;
        String title = extras.getString(Notification.EXTRA_TITLE, "");
        String text = extras.getString(Notification.EXTRA_TEXT, "");
        assertTrue(title.contains(":"));
        assertTrue(text.contains("103.10.10.10:8080"));
        assertTrue(extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false));
        assertEquals(900, extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0));

        // Disabling countdown removes chronometer & progress bar
        prefs.setNotificationTypeEnabled(NotificationType.VPN_TIMER_COUNTDOWN, false);
        Notification withoutCountdown = ProxyNotificationManager.buildVpnForegroundNotification(
                context, proxy, 10808, deadlineMs, 15, null, null);
        assertFalse(withoutCountdown.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false));
        assertEquals(0, withoutCountdown.extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0));
    }

    @Test
    public void notificationsSettingsActivity_rendersSwitchesAndPersistsToggles() {
        Context context = ApplicationProvider.getApplicationContext();
        SettingsPreferences prefs = new SettingsPreferences(context);
        prefs.setNotificationsEnabled(true);
        for (NotificationType type : NotificationType.values()) {
            prefs.setNotificationTypeEnabled(type, true);
        }

        NotificationsSettingsActivity activity = Robolectric.buildActivity(NotificationsSettingsActivity.class)
                .setup()
                .get();

        MaterialSwitch switchMaster = activity.findViewById(R.id.switch_notifications_master);
        MaterialSwitch switchStatus = activity.findViewById(R.id.switch_notif_type_status);
        MaterialSwitch switchCountdown = activity.findViewById(R.id.switch_notif_type_countdown);
        MaterialSwitch switchConnection = activity.findViewById(R.id.switch_notif_type_connection);
        MaterialSwitch switchFirewall = activity.findViewById(R.id.switch_notif_type_firewall);

        assertNotNull(switchMaster);
        assertNotNull(switchStatus);
        assertNotNull(switchCountdown);
        assertNotNull(switchConnection);
        assertNotNull(switchFirewall);

        assertTrue(switchMaster.isChecked());
        assertTrue(switchStatus.isChecked());
        assertTrue(switchCountdown.isChecked());
        assertTrue(switchConnection.isChecked());
        assertTrue(switchFirewall.isChecked());

        // Toggle off all 4 categories via row taps -> master switch automatically turns off
        activity.findViewById(R.id.row_notif_type_countdown).performClick();
        assertFalse(prefs.isNotificationTypeEnabled(NotificationType.VPN_TIMER_COUNTDOWN));
        assertTrue(switchMaster.isChecked());

        activity.findViewById(R.id.row_notif_type_status).performClick();
        activity.findViewById(R.id.row_notif_type_connection).performClick();
        activity.findViewById(R.id.row_notif_type_firewall).performClick();

        assertFalse(prefs.isNotificationsEnabled());
        assertFalse(switchMaster.isChecked());

        // Re-enabling a category turns master switch back on
        activity.findViewById(R.id.row_notif_type_countdown).performClick();
        assertTrue(prefs.isNotificationTypeEnabled(NotificationType.VPN_TIMER_COUNTDOWN));
        assertTrue(prefs.isNotificationsEnabled());
        assertTrue(switchMaster.isChecked());
    }

    @Test
    public void formatRemainingCountdown_formatsMinutesAndHoursCleanly() {
        assertEquals("05:09", ProxyNotificationManager.formatRemainingCountdown(309));
        assertEquals("01:02:03", ProxyNotificationManager.formatRemainingCountdown(3723));
        assertEquals("00:00", ProxyNotificationManager.formatRemainingCountdown(-5));
    }
}
