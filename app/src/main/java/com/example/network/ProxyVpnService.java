package com.example.network;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.ProxyInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import androidx.core.app.NotificationCompat;

import com.example.MainActivity;
import com.example.R;
import com.example.data.InstalledAppScanner;
import com.example.data.ProxyRepository;
import com.example.model.AppProxyRule;
import com.example.model.ConnectionStatus;
import com.example.model.ProxyConfig;
import com.example.model.ProxyLogEntry;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Android VpnService that establishes a system-level proxy tunnel backed by
 * LocalProxyServer using a fixed user-selected HTTP proxy (with user/pass auth).
 * Never switches or rotates proxies automatically; proxy changes occur only on
 * explicit user action. Supports per-app allowlist / blocklist routing.
 */
public class ProxyVpnService extends VpnService implements LocalProxyServer.EventListener {

    public static final String ACTION_START = "com.example.action.START_VPN";
    public static final String ACTION_STOP = "com.example.action.STOP_VPN";
    public static final String ACTION_SWITCH_TO_PROXY = "com.example.action.SWITCH_TO_PROXY";
    public static final String ACTION_REAPPLY_APP_RULES = "com.example.action.REAPPLY_APP_RULES";
    public static final String EXTRA_PROXY_ID = "extra_proxy_id";

    private static final String CHANNEL_ID = "proxyflow_vpn_channel";
    private static final int NOTIFICATION_ID = 2048;
    private static final long FAILURE_LOG_THROTTLE_MS = 15_000L;

    private ProxyRepository repository;
    private ProxyRuntimeManager runtimeManager;
    private LocalProxyServer localProxyServer;

    private ParcelFileDescriptor tunInterface;
    private Thread tunDrainThread;
    private ScheduledExecutorService scheduler;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicLong lastFailureLogMs = new AtomicLong(0L);

    private volatile long connectedSinceMs = 0L;
    private volatile ProxyConfig activeProxy = null;

    public static void startVpn(Context context, long proxyId) {
        Intent intent = new Intent(context, ProxyVpnService.class);
        intent.setAction(ACTION_START);
        if (proxyId > 0) {
            intent.putExtra(EXTRA_PROXY_ID, proxyId);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stopVpn(Context context) {
        Intent intent = new Intent(context, ProxyVpnService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }

    public static void switchToProxy(Context context, long proxyId) {
        Intent intent = new Intent(context, ProxyVpnService.class);
        intent.setAction(ACTION_SWITCH_TO_PROXY);
        intent.putExtra(EXTRA_PROXY_ID, proxyId);
        context.startService(intent);
    }

    public static void reapplyAppRules(Context context) {
        Intent intent = new Intent(context, ProxyVpnService.class);
        intent.setAction(ACTION_REAPPLY_APP_RULES);
        context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        repository = ProxyRepository.getInstance(this);
        runtimeManager = ProxyRuntimeManager.getInstance();
        localProxyServer = new LocalProxyServer();
        localProxyServer.setEventListener(this);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action)) {
            stopTunnelAndService("");
            return START_NOT_STICKY;
        }

        if (ACTION_SWITCH_TO_PROXY.equals(action) && intent != null) {
            long targetId = intent.getLongExtra(EXTRA_PROXY_ID, -1L);
            if (targetId > 0) {
                switchToSpecificProxyAsync(targetId);
            }
            return START_STICKY;
        }

        if (ACTION_REAPPLY_APP_RULES.equals(action)) {
            reapplyAppRulesAsync();
            return START_STICKY;
        }

        long requestedId = intent != null ? intent.getLongExtra(EXTRA_PROXY_ID, -1L) : -1L;
        startForegroundCompat(buildNotification(getString(R.string.status_connecting), ""));
        startTunnelAsync(requestedId);
        return START_STICKY;
    }

    private void startTunnelAsync(long requestedProxyId) {
        Executors.newSingleThreadExecutor().execute(() -> {
            ProxyConfig chosen = requestedProxyId > 0
                    ? repository.getProxyByIdSync(requestedProxyId)
                    : repository.getActiveProxySync();
            if (chosen == null) {
                chosen = repository.getActiveProxySync();
            }
            if (chosen == null) {
                repository.addLog(ProxyLogEntry.Level.ERROR, "Không có Proxy nào trong danh sách để kết nối");
                stopTunnelAndService(getString(R.string.msg_no_proxy_to_connect));
                return;
            }

            activeProxy = chosen;
            repository.markActiveProxySync(chosen.getId());
            runtimeManager.postStatus(new ConnectionStatus(
                    ConnectionStatus.State.CONNECTING,
                    chosen,
                    0,
                    0L,
                    0L,
                    0,
                    "",
                    0L
            ));

            try {
                int localPort = localProxyServer.start(chosen);
                establishVpnInterface(chosen, localPort);
                connectedSinceMs = System.currentTimeMillis();
                isRunning.set(true);
                startTelemetryTicker();

                repository.addLog(ProxyLogEntry.Level.SUCCESS,
                        "Đã kết nối VPN Proxy cố định -> " + chosen.getAddressDisplay() + " (cổng cục bộ :" + localPort + ")");

                // Refresh exit IP and latency once on initial connect without changing proxy
                ProxyTester.TestResult test = ProxyTester.testProxy(chosen, 6000);
                if (test.isSuccess()) {
                    chosen.setLatencyMs(test.getLatencyMs());
                    chosen.setExitIp(test.getExitIp());
                    chosen.setCountry(test.getCountry());
                    repository.updateTelemetrySync(chosen.getId(), test.getLatencyMs(), test.getExitIp(), test.getCountry(), 0);
                }

                publishStatus(ConnectionStatus.State.CONNECTED, "");
                updateNotification();
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : "Không thể khởi tạo giao diện VPN";
                repository.addLog(ProxyLogEntry.Level.ERROR, "Lỗi VPN: " + msg);
                stopTunnelAndService(msg);
            }
        });
    }

    private void establishVpnInterface(ProxyConfig proxy, int localPort) throws IOException {
        if (tunInterface != null) {
            try {
                tunInterface.close();
            } catch (IOException ignored) {
            }
            tunInterface = null;
        }

        Builder builder = new Builder();
        builder.setSession("ProxyFlow - " + proxy.getAddressDisplay());
        builder.addAddress("10.0.0.2", 32);
        builder.addRoute("0.0.0.0", 0);
        builder.setMtu(1500);
        builder.allowBypass();
        applyPerAppRules(builder);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", localPort));
        }

        tunInterface = builder.establish();
        if (tunInterface == null) {
            throw new IOException("Quyền VPN chưa được cấp hoặc bị hệ thống từ chối");
        }

        tunDrainThread = new Thread(() -> {
            byte[] packet = new byte[4096];
            try (FileInputStream fis = new FileInputStream(tunInterface.getFileDescriptor())) {
                while (isRunning.get() && tunInterface != null) {
                    int read = fis.read(packet);
                    if (read < 0) break;
                }
            } catch (IOException ignored) {
            }
        }, "ProxyFlow-TunDrain");
        tunDrainThread.setDaemon(true);
        tunDrainThread.start();
    }

    private void startTelemetryTicker() {
        stopScheduler();
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(() -> {
            if (!isRunning.get()) return;
            try {
                publishStatus(ConnectionStatus.State.CONNECTED, "");
                updateNotification();
            } catch (Exception ignored) {
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    private void switchToSpecificProxyAsync(long targetId) {
        Executors.newSingleThreadExecutor().execute(() -> {
            ProxyConfig target = repository.getProxyByIdSync(targetId);
            if (target == null) return;
            if (!isRunning.get()) {
                startTunnelAsync(targetId);
                return;
            }
            applyManualProxyChange(target);
        });
    }

    private void applyPerAppRules(Builder builder) {
        AppProxyRule rule = repository.getAppProxyRuleSync();
        if (rule.getMode() == AppProxyRule.Mode.ALLOW_SELECTED) {
            Set<String> allowed = rule.getSelectedPackages();
            if (!allowed.isEmpty()) {
                for (String pkg : allowed) {
                    if (!getPackageName().equals(pkg)) {
                        try {
                            builder.addAllowedApplication(pkg);
                        } catch (PackageManager.NameNotFoundException ignored) {
                        }
                    }
                }
            } else {
                // No app selected in ALLOW_SELECTED mode: keep ProxyFlow and all known apps out of proxy
                try {
                    builder.addDisallowedApplication(getPackageName());
                } catch (PackageManager.NameNotFoundException ignored) {
                }
                for (String pkg : InstalledAppScanner.scanInstalledPackageNames(this)) {
                    try {
                        builder.addDisallowedApplication(pkg);
                    } catch (PackageManager.NameNotFoundException ignored) {
                    }
                }
            }
        } else {
            try {
                builder.addDisallowedApplication(getPackageName());
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            for (String pkg : rule.getSelectedPackages()) {
                try {
                    builder.addDisallowedApplication(pkg);
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
        }
    }

    private void reapplyAppRulesAsync() {
        Executors.newSingleThreadExecutor().execute(() -> {
            if (!isRunning.get() || activeProxy == null) return;
            try {
                establishVpnInterface(activeProxy, localProxyServer.getLocalPort());
                AppProxyRule rule = repository.getAppProxyRuleSync();
                String msg = rule.getMode() == AppProxyRule.Mode.ALLOW_SELECTED
                        ? "Đã áp dụng quy tắc: Chỉ cho phép " + rule.getSelectedPackages().size() + " ứng dụng qua Proxy"
                        : "Đã áp dụng quy tắc: Chặn " + rule.getSelectedPackages().size() + " ứng dụng không dùng Proxy";
                repository.addLog(ProxyLogEntry.Level.INFO, msg);
            } catch (IOException e) {
                repository.addLog(ProxyLogEntry.Level.ERROR,
                        "Không thể cập nhật quy tắc ứng dụng VPN: " + e.getMessage());
            }
        });
    }

    private void applyManualProxyChange(ProxyConfig nextProxy) {
        activeProxy = nextProxy;
        localProxyServer.setActiveProxy(nextProxy);
        repository.markActiveProxySync(nextProxy.getId());
        repository.addLog(ProxyLogEntry.Level.SUCCESS,
                "Người dùng đã đổi Proxy -> " + nextProxy.getAddressDisplay());
        publishStatus(ConnectionStatus.State.CONNECTED, "");
        updateNotification();
    }

    private void publishStatus(ConnectionStatus.State state, String error) {
        runtimeManager.postStatus(new ConnectionStatus(
                state,
                activeProxy,
                localProxyServer.getLocalPort(),
                localProxyServer.getBytesIn(),
                localProxyServer.getBytesOut(),
                localProxyServer.getActiveConnections(),
                error,
                connectedSinceMs
        ));
    }

    private void stopTunnelAndService(String errorMsg) {
        isRunning.set(false);
        stopScheduler();
        if (localProxyServer != null) {
            localProxyServer.stop();
        }
        if (tunInterface != null) {
            try {
                tunInterface.close();
            } catch (IOException ignored) {
            }
            tunInterface = null;
        }
        if (tunDrainThread != null) {
            tunDrainThread.interrupt();
            tunDrainThread = null;
        }
        connectedSinceMs = 0L;
        ProxyConfig current = activeProxy != null ? activeProxy : repository.getActiveProxySync();
        runtimeManager.postDisconnected(current, errorMsg);
        if (errorMsg == null || errorMsg.isEmpty()) {
            repository.addLog(ProxyLogEntry.Level.INFO, "Đã ngắt kết nối VPN Proxy");
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void stopScheduler() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    @Override
    public void onUpstreamSuccess(long proxyId) {
        // Keep selected proxy fixed; no rotation or automatic switching
    }

    @Override
    public void onUpstreamFailure(long proxyId, String reason) {
        if (!isRunning.get() || activeProxy == null || activeProxy.getId() != proxyId) {
            return;
        }
        // Log throttled warning only; never switch or rotate the user's selected proxy
        long now = System.currentTimeMillis();
        long prev = lastFailureLogMs.get();
        if (now - prev >= FAILURE_LOG_THROTTLE_MS && lastFailureLogMs.compareAndSet(prev, now)) {
            repository.addLog(ProxyLogEntry.Level.WARN,
                    "Proxy " + activeProxy.getAddressDisplay() + " gặp lỗi truyền tải (" + reason
                            + "). IP vẫn được giữ cố định.");
        }
    }

    @Override
    public void onRevoke() {
        stopTunnelAndService("");
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        stopScheduler();
        if (localProxyServer != null) {
            localProxyServer.stop();
        }
        if (tunInterface != null) {
            try {
                tunInterface.close();
            } catch (IOException ignored) {
            }
        }
        super.onDestroy();
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification() {
        if (!isRunning.get() || activeProxy == null) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            String title = getString(R.string.notif_title_connected, activeProxy.getAddressDisplay());
            String body = getString(R.string.notif_text_connected,
                    localProxyServer.getLocalPort(), getString(R.string.label_manual_fixed_ip));
            nm.notify(NOTIFICATION_ID, buildNotification(title, body));
        }
    }

    private Notification buildNotification(String title, String content) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(
                this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, ProxyVpnService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(
                this, 2, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_nav_connect)
                .setContentTitle(title)
                .setContentText(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openPending)
                .addAction(R.drawable.ic_power, getString(R.string.notif_action_stop), stopPending)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(getString(R.string.notif_channel_desc));
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }
}
