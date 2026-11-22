package com.example.network;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.example.MainActivity;
import com.example.R;
import com.example.data.SettingsPreferences;
import com.example.data.SettingsPreferences.NotificationType;
import com.example.model.ProxyConfig;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Centralized notification manager for ProxyFlow:
 * - Ongoing VPN tunnel notification (with live status & VPN timer countdown)
 * - Connection / Disconnect alerts
 * - VPN Timer expiration alerts
 * - Firewall block alerts
 * Respects user master toggle and per-category notification preferences.
 */
public final class ProxyNotificationManager {

    public static final String CHANNEL_VPN_STATUS_ID = "proxyflow_vpn_channel";
    public static final String CHANNEL_EVENTS_ID = "proxyflow_events_channel";

    public static final int NOTIF_ID_VPN_FOREGROUND = 2048;
    public static final int NOTIF_ID_CONNECTION_EVENT = 2049;
    public static final int NOTIF_ID_TIMER_EVENT = 2050;
    public static final int NOTIF_ID_FIREWALL_EVENT = 2051;

    private static final long FIREWALL_ALERT_THROTTLE_MS = 30_000L;
    private static final AtomicLong lastFirewallAlertMs = new AtomicLong(0L);

    private ProxyNotificationManager() {
    }

    public static void ensureChannels(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        NotificationChannel vpnChannel = new NotificationChannel(
                CHANNEL_VPN_STATUS_ID,
                context.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        vpnChannel.setDescription(context.getString(R.string.notif_channel_desc));
        vpnChannel.setShowBadge(false);
        nm.createNotificationChannel(vpnChannel);

        NotificationChannel eventsChannel = new NotificationChannel(
                CHANNEL_EVENTS_ID,
                context.getString(R.string.notif_events_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
        );
        eventsChannel.setDescription(context.getString(R.string.notif_events_channel_desc));
        nm.createNotificationChannel(eventsChannel);
    }

    public static boolean hasNotificationPermission(@NonNull Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    public static Notification buildVpnForegroundNotification(
            @NonNull Context context,
            @Nullable ProxyConfig activeProxy,
            int localPort,
            long vpnTimerDeadlineMs,
            int totalTimerMinutes,
            @Nullable String overrideTitle,
            @Nullable String overrideBody) {

        ensureChannels(context);
        SettingsPreferences prefs = new SettingsPreferences(context);
        boolean masterEnabled = prefs.isNotificationsEnabled();
        boolean statusEnabled = masterEnabled && prefs.isNotificationActive(NotificationType.VPN_STATUS);
        boolean countdownEnabled = masterEnabled && prefs.isNotificationActive(NotificationType.VPN_TIMER_COUNTDOWN);

        PendingIntent openPending = createOpenAppPendingIntent(context);
        PendingIntent stopPending = createStopVpnPendingIntent(context);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_VPN_STATUS_ID)
                .setSmallIcon(R.drawable.ic_nav_connect)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(openPending);

        if (!statusEnabled && !countdownEnabled) {
            // Android requires an ongoing notification for Foreground VpnService; keep it minimal when user disables status alerts
            return builder
                    .setContentTitle(context.getString(R.string.app_name))
                    .setContentText(context.getString(R.string.notif_minimal_running))
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .build();
        }

        builder.addAction(R.drawable.ic_power, context.getString(R.string.notif_action_stop), stopPending);

        if (overrideTitle != null && !overrideTitle.isEmpty()) {
            return builder
                    .setContentTitle(overrideTitle)
                    .setContentText(overrideBody != null ? overrideBody : "")
                    .build();
        }

        String proxyDisplay = activeProxy != null
                ? activeProxy.getAddressDisplay()
                : context.getString(R.string.app_name);

        if (countdownEnabled && vpnTimerDeadlineMs > 0L && totalTimerMinutes > 0) {
            long remainingMs = Math.max(0L, vpnTimerDeadlineMs - System.currentTimeMillis());
            long remainingSec = (remainingMs + 999L) / 1000L;
            String countdown = formatRemainingCountdown(remainingSec);
            int totalSec = Math.max(1, totalTimerMinutes * 60);
            int progressRemaining = (int) Math.min(totalSec, Math.max(0L, remainingSec));

            String title = statusEnabled
                    ? context.getString(R.string.notif_title_timer_countdown, countdown)
                    : context.getString(R.string.notif_title_timer_only, countdown);
            String body = statusEnabled
                    ? context.getString(R.string.notif_text_timer_remaining_detail, proxyDisplay, localPort, countdown)
                    : context.getString(R.string.notif_text_timer_remaining, localPort, countdown);

            builder.setContentTitle(title)
                    .setContentText(body)
                    .setSubText(context.getString(R.string.notif_subtext_countdown, countdown))
                    .setShowWhen(true)
                    .setWhen(vpnTimerDeadlineMs)
                    .setUsesChronometer(true)
                    .setProgress(totalSec, progressRemaining, false);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                builder.setChronometerCountDown(true);
            }
            return builder.build();
        }

        return builder
                .setContentTitle(context.getString(R.string.notif_title_connected, proxyDisplay))
                .setContentText(context.getString(R.string.notif_text_connected,
                        localPort, context.getString(R.string.label_manual_fixed_ip)))
                .setUsesChronometer(false)
                .setProgress(0, 0, false)
                .build();
    }

    public static void updateVpnForegroundNotification(
            @NonNull Context context,
            @Nullable ProxyConfig activeProxy,
            int localPort,
            long vpnTimerDeadlineMs,
            int totalTimerMinutes) {
        if (!hasNotificationPermission(context)) {
            return;
        }
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        Notification notification = buildVpnForegroundNotification(
                context, activeProxy, localPort, vpnTimerDeadlineMs, totalTimerMinutes, null, null);
        nm.notify(NOTIF_ID_VPN_FOREGROUND, notification);
    }

    public static void notifyVpnConnected(@NonNull Context context, @NonNull ProxyConfig proxy, int timerMinutes) {
        SettingsPreferences prefs = new SettingsPreferences(context);
        if (!prefs.isNotificationActive(NotificationType.CONNECTION_EVENTS) || !hasNotificationPermission(context)) {
            return;
        }
        ensureChannels(context);
        String title = context.getString(R.string.notif_event_connected_title);
        String body = timerMinutes > 0
                ? context.getString(R.string.notif_event_connected_timer_body, proxy.getAddressDisplay(), timerMinutes)
                : context.getString(R.string.notif_event_connected_body, proxy.getAddressDisplay());
        postEventNotification(context, NOTIF_ID_CONNECTION_EVENT, R.drawable.ic_nav_connect, title, body);
    }

    public static void notifyVpnDisconnected(@NonNull Context context, @Nullable String reason, boolean isUnexpected) {
        SettingsPreferences prefs = new SettingsPreferences(context);
        if (!prefs.isNotificationActive(NotificationType.CONNECTION_EVENTS) || !hasNotificationPermission(context)) {
            return;
        }
        ensureChannels(context);
        String title = isUnexpected
                ? context.getString(R.string.notif_event_autostop_title)
                : context.getString(R.string.notif_event_disconnected_title);
        String body = (reason != null && !reason.isEmpty())
                ? reason
                : context.getString(R.string.notif_event_disconnected_body);
        postEventNotification(context, NOTIF_ID_CONNECTION_EVENT, R.drawable.ic_power, title, body);
    }

    public static void notifyTimerExpired(@NonNull Context context, @NonNull String durationLabel) {
        SettingsPreferences prefs = new SettingsPreferences(context);
        boolean shouldNotify = prefs.isNotificationActive(NotificationType.VPN_TIMER_COUNTDOWN)
                || prefs.isNotificationActive(NotificationType.CONNECTION_EVENTS);
        if (!shouldNotify || !hasNotificationPermission(context)) {
            return;
        }
        ensureChannels(context);
        String title = context.getString(R.string.notif_event_timer_expired_title);
        String body = context.getString(R.string.notif_event_timer_expired_body, durationLabel);
        postEventNotification(context, NOTIF_ID_TIMER_EVENT, R.drawable.ic_timer, title, body);
    }

    public static void notifyFirewallBlocked(@NonNull Context context, @NonNull String appOrRule, @NonNull String destination) {
        SettingsPreferences prefs = new SettingsPreferences(context);
        if (!prefs.isNotificationActive(NotificationType.FIREWALL_ALERTS) || !hasNotificationPermission(context)) {
            return;
        }
        long now = System.currentTimeMillis();
        long prev = lastFirewallAlertMs.get();
        if (now - prev < FIREWALL_ALERT_THROTTLE_MS || !lastFirewallAlertMs.compareAndSet(prev, now)) {
            return;
        }
        ensureChannels(context);
        String title = context.getString(R.string.notif_event_firewall_title);
        String body = context.getString(R.string.log_firewall_blocked_request, appOrRule, destination);
        postEventNotification(context, NOTIF_ID_FIREWALL_EVENT, R.drawable.ic_shield, title, body);
    }

    public static boolean sendTestNotification(@NonNull Context context) {
        if (!hasNotificationPermission(context)) {
            return false;
        }
        ensureChannels(context);
        String title = context.getString(R.string.notif_test_title);
        String body = context.getString(R.string.notif_test_body);
        postEventNotification(context, NOTIF_ID_CONNECTION_EVENT, R.drawable.ic_notifications, title, body);
        return true;
    }

    private static void postEventNotification(
            @NonNull Context context,
            int notificationId,
            int smallIconRes,
            @NonNull String title,
            @NonNull String body) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        Notification notification = new NotificationCompat.Builder(context, CHANNEL_EVENTS_ID)
                .setSmallIcon(smallIconRes)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(createOpenAppPendingIntent(context))
                .build();
        nm.notify(notificationId, notification);
    }

    public static String formatRemainingCountdown(long remainingSeconds) {
        long safe = Math.max(0L, remainingSeconds);
        long hours = safe / 3600L;
        long mins = (safe % 3600L) / 60L;
        long secs = safe % 60L;
        if (hours > 0) {
            return String.format(Locale.US, "%02d:%02d:%02d", hours, mins, secs);
        }
        return String.format(Locale.US, "%02d:%02d", mins, secs);
    }

    private static PendingIntent createOpenAppPendingIntent(Context context) {
        Intent openIntent = new Intent(context, MainActivity.class);
        return PendingIntent.getActivity(
                context, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent createStopVpnPendingIntent(Context context) {
        Intent stopIntent = new Intent(context, ProxyVpnService.class);
        stopIntent.setAction(ProxyVpnService.ACTION_STOP);
        return PendingIntent.getService(
                context, 2, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
