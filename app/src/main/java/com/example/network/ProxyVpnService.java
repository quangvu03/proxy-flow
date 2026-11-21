package com.example.network;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.ProxyInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.OsConstants;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.example.MainActivity;
import com.example.R;
import com.example.data.InstalledAppScanner;
import com.example.data.ProxyRepository;
import com.example.data.SettingsPreferences;
import com.example.model.AppProxyRule;
import com.example.model.ConnectionStatus;
import com.example.model.FirewallRule;
import com.example.model.ProxyConfig;
import com.example.model.ProxyLogEntry;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Android VpnService that establishes a system-level proxy tunnel backed by
 * LocalProxyServer using a fixed user-selected HTTP proxy (with user/pass auth).
 * Never switches or rotates proxies automatically; proxy changes occur only on
 * explicit user action. Supports per-app allowlist / blocklist routing, firewall
 * app-blocking rules, and private network IP routing through the upstream proxy.
 */
public class ProxyVpnService extends VpnService
        implements LocalProxyServer.EventListener, LocalProxyServer.PolicyChecker {

    public static final String ACTION_START = "com.example.action.START_VPN";
    public static final String ACTION_STOP = "com.example.action.STOP_VPN";
    public static final String ACTION_SWITCH_TO_PROXY = "com.example.action.SWITCH_TO_PROXY";
    public static final String ACTION_REAPPLY_APP_RULES = "com.example.action.REAPPLY_APP_RULES";
    public static final String ACTION_REAPPLY_POLICIES = "com.example.action.REAPPLY_POLICIES";
    public static final String ACTION_UPDATE_VPN_TIMER = "com.example.action.UPDATE_VPN_TIMER";
    public static final String EXTRA_PROXY_ID = "extra_proxy_id";

    private static final String CHANNEL_ID = "proxyflow_vpn_channel";
    private static final int NOTIFICATION_ID = 2048;
    private static final long FAILURE_LOG_THROTTLE_MS = 15_000L;
    private static final long NETWORK_LOST_GRACE_MS = 2_500L;
    private static final int AUTO_STOP_CONSECUTIVE_FAILURES = 2;
    private static final int HEALTH_CHECK_INTERVAL_SECONDS = 15;
    private static final long HEALTH_CHECK_IDLE_THRESHOLD_MS = 20_000L;
    private static final int HEALTH_PROBE_TIMEOUT_MS = 4_000;

    private ProxyRepository repository;
    private ProxyRuntimeManager runtimeManager;
    private SettingsPreferences settingsPreferences;
    private LocalProxyServer localProxyServer;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;

    private ParcelFileDescriptor tunInterface;
    private Thread tunDrainThread;
    private ScheduledExecutorService scheduler;
    private ExecutorService healthProbeExecutor;
    private volatile ScheduledFuture<?> pendingNetworkLostFuture;

    private final Set<Network> availableUnderlyingNetworks = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private final AtomicBoolean healthProbeInFlight = new AtomicBoolean(false);
    private final AtomicInteger consecutiveUpstreamFailures = new AtomicInteger(0);
    private final AtomicInteger healthProbeFailures = new AtomicInteger(0);
    private final AtomicInteger telemetryTickCounter = new AtomicInteger(0);
    private final AtomicLong lastFailureLogMs = new AtomicLong(0L);
    private final AtomicLong lastUpstreamSuccessMs = new AtomicLong(0L);

    private volatile long connectedSinceMs = 0L;
    private volatile long vpnTimerDeadlineMs = 0L;
    private volatile int activeVpnTimerMinutes = SettingsPreferences.VPN_TIMER_ALWAYS;
    private volatile ProxyConfig activeProxy = null;
    private volatile boolean firewallEnabledSnapshot = false;
    private volatile List<FirewallRule> activeFirewallRulesSnapshot = java.util.Collections.emptyList();
    private volatile boolean privateRoutingEnabledSnapshot = false;
    private volatile List<String> privateRoutingRangesSnapshot = PrivateNetworkMatcher.DEFAULT_PRIVATE_RANGES;
    private final AtomicLong lastFirewallBlockLogMs = new AtomicLong(0L);

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

    public static void updateVpnTimer(Context context) {
        Intent intent = new Intent(context, ProxyVpnService.class);
        intent.setAction(ACTION_UPDATE_VPN_TIMER);
        context.startService(intent);
    }

    public static void reapplyFirewallAndRouting(Context context) {
        Intent intent = new Intent(context, ProxyVpnService.class);
        intent.setAction(ACTION_REAPPLY_POLICIES);
        context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        repository = ProxyRepository.getInstance(this);
        runtimeManager = ProxyRuntimeManager.getInstance();
        settingsPreferences = new SettingsPreferences(this);
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        localProxyServer = new LocalProxyServer();
        localProxyServer.setEventListener(this);
        localProxyServer.setPolicyChecker(this);
        refreshPolicySnapshots();
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

        if (ACTION_REAPPLY_POLICIES.equals(action)) {
            reapplyPoliciesAsync();
            return START_STICKY;
        }

        if (ACTION_UPDATE_VPN_TIMER.equals(action)) {
            if (isRunning.get()) {
                applyConfiguredVpnTimer(System.currentTimeMillis(), true);
                updateNotification();
            }
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
            stopping.set(false);
            consecutiveUpstreamFailures.set(0);
            healthProbeFailures.set(0);
            telemetryTickCounter.set(0);
            lastUpstreamSuccessMs.set(System.currentTimeMillis());

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
                applyConfiguredVpnTimer(connectedSinceMs, false);
                isRunning.set(true);
                registerNetworkMonitor();
                startTelemetryTicker();

                repository.addLog(ProxyLogEntry.Level.SUCCESS,
                        "Đã kết nối VPN Proxy cố định -> " + chosen.getAddressDisplay() + " (cổng cục bộ :" + localPort + ")");
                if (activeVpnTimerMinutes > 0) {
                    repository.addLog(ProxyLogEntry.Level.INFO,
                            getString(R.string.log_vpn_timer_set, formatTimerMinutes(activeVpnTimerMinutes)));
                }

                // Refresh exit IP and latency once on initial connect without changing proxy
                ProxyTester.TestResult test = ProxyTester.testProxy(chosen, 6000);
                if (!isRunning.get()) {
                    return;
                }
                if (test.isSuccess()) {
                    lastUpstreamSuccessMs.set(System.currentTimeMillis());
                    chosen.setLatencyMs(test.getLatencyMs());
                    chosen.setExitIp(test.getExitIp());
                    chosen.setCountry(test.getCountry());
                    repository.updateTelemetrySync(chosen.getId(), test.getLatencyMs(), test.getExitIp(), test.getCountry(), 0);
                } else if (isAutoStopEnabled()) {
                    String reason = test.getErrorMessage() != null && !test.getErrorMessage().isEmpty()
                            ? test.getErrorMessage()
                            : getString(R.string.status_error);
                    autoStopOnDisconnect(getString(R.string.log_auto_stop_proxy_lost,
                            chosen.getAddressDisplay(), reason));
                    return;
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

        refreshPolicySnapshots();

        Builder builder = new Builder();
        builder.setSession("ProxyFlow - " + proxy.getAddressDisplay());
        builder.addAddress("10.0.0.2", 32);
        builder.addRoute("0.0.0.0", 0);
        builder.setMtu(1500);
        builder.allowBypass();
        applyPerAppRules(builder);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (privateRoutingEnabledSnapshot) {
                builder.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", localPort));
            } else {
                List<String> exclList = Arrays.asList(
                        "localhost", "127.0.0.1", "10.*",
                        "172.16.*", "172.17.*", "172.18.*", "172.19.*",
                        "172.20.*", "172.21.*", "172.22.*", "172.23.*",
                        "172.24.*", "172.25.*", "172.26.*", "172.27.*",
                        "172.28.*", "172.29.*", "172.30.*", "172.31.*",
                        "192.168.*", "169.254.*"
                );
                builder.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", localPort, exclList));
            }
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
        healthProbeExecutor = Executors.newSingleThreadExecutor();
        scheduler.scheduleWithFixedDelay(() -> {
            if (!isRunning.get()) return;
            try {
                long deadline = vpnTimerDeadlineMs;
                if (deadline > 0L && System.currentTimeMillis() >= deadline) {
                    int minutes = activeVpnTimerMinutes;
                    autoStopOnDisconnect(getString(R.string.log_vpn_timer_expired,
                            formatTimerMinutes(minutes)));
                    return;
                }
                publishStatus(ConnectionStatus.State.CONNECTED, "");
                updateNotification();
                int tick = telemetryTickCounter.incrementAndGet();
                if (tick % HEALTH_CHECK_INTERVAL_SECONDS == 0 && isAutoStopEnabled()) {
                    runDisconnectHealthCheck();
                }
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
        Set<String> firewallBlocked = getFullInternetFirewallBlockedPackages();

        if (rule.getMode() == AppProxyRule.Mode.ALLOW_SELECTED) {
            Set<String> allowed = new HashSet<>(rule.getSelectedPackages());
            allowed.addAll(firewallBlocked);
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
                if (firewallBlocked.contains(pkg)) {
                    // Firewall takes priority: trap blocked apps inside VPN tunnel so internet is blocked
                    continue;
                }
                try {
                    builder.addDisallowedApplication(pkg);
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
        }
    }

    private Set<String> getFullInternetFirewallBlockedPackages() {
        Set<String> blocked = new HashSet<>();
        if (!firewallEnabledSnapshot) {
            return blocked;
        }
        for (FirewallRule fwRule : activeFirewallRulesSnapshot) {
            if (fwRule.isEnabled() && fwRule.blocksAllInternet()) {
                blocked.addAll(fwRule.getBlockedPackages());
            }
        }
        return blocked;
    }

    private void refreshPolicySnapshots() {
        firewallEnabledSnapshot = settingsPreferences != null && settingsPreferences.isFirewallEnabled();
        activeFirewallRulesSnapshot = repository != null
                ? repository.getFirewallRulesSync()
                : java.util.Collections.emptyList();
        privateRoutingEnabledSnapshot = settingsPreferences != null
                && settingsPreferences.isPrivateNetworkRoutingEnabled();
        privateRoutingRangesSnapshot = settingsPreferences != null
                ? settingsPreferences.getPrivateNetworkRangesList()
                : PrivateNetworkMatcher.DEFAULT_PRIVATE_RANGES;
    }

    private void reapplyPoliciesAsync() {
        Executors.newSingleThreadExecutor().execute(() -> {
            refreshPolicySnapshots();
            if (!isRunning.get() || activeProxy == null) return;
            try {
                establishVpnInterface(activeProxy, localProxyServer.getLocalPort());
            } catch (IOException e) {
                repository.addLog(ProxyLogEntry.Level.ERROR,
                        "Không thể cập nhật tường lửa / định tuyến VPN: " + e.getMessage());
            }
        });
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
        consecutiveUpstreamFailures.set(0);
        healthProbeFailures.set(0);
        lastUpstreamSuccessMs.set(System.currentTimeMillis());
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
        stopTunnelAndService(errorMsg, null);
    }

    private void stopTunnelAndService(String errorMsg, String customDisconnectLog) {
        if (!stopping.compareAndSet(false, true)) {
            return;
        }
        isRunning.set(false);
        unregisterNetworkMonitor();
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
        vpnTimerDeadlineMs = 0L;
        activeVpnTimerMinutes = SettingsPreferences.VPN_TIMER_ALWAYS;
        ProxyConfig current = activeProxy != null ? activeProxy : repository.getActiveProxySync();
        runtimeManager.postDisconnected(current, errorMsg);
        if (customDisconnectLog != null && !customDisconnectLog.isEmpty()) {
            repository.addLog(ProxyLogEntry.Level.WARN, customDisconnectLog);
        } else if (errorMsg == null || errorMsg.isEmpty()) {
            repository.addLog(ProxyLogEntry.Level.INFO, "Đã ngắt kết nối VPN Proxy");
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void autoStopOnDisconnect(String logMessage) {
        if (!isRunning.get()) {
            return;
        }
        stopTunnelAndService("", logMessage);
    }

    private boolean isAutoStopEnabled() {
        return settingsPreferences != null && settingsPreferences.isAutoStopOnDisconnectEnabled();
    }

    private void registerNetworkMonitor() {
        unregisterNetworkMonitor();
        if (connectivityManager == null) {
            return;
        }
        availableUnderlyingNetworks.clear();
        try {
            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build();
            ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    availableUnderlyingNetworks.add(network);
                    cancelPendingNetworkLostStop();
                }

                @Override
                public void onLost(@NonNull Network network) {
                    availableUnderlyingNetworks.remove(network);
                    if (!isRunning.get() || !isAutoStopEnabled()) {
                        return;
                    }
                    if (availableUnderlyingNetworks.isEmpty() && !hasActiveUnderlyingInternet()) {
                        scheduleNetworkLostAutoStop();
                    }
                }
            };
            connectivityManager.registerNetworkCallback(request, callback);
            networkCallback = callback;
        } catch (Exception ignored) {
        }
    }

    private void unregisterNetworkMonitor() {
        cancelPendingNetworkLostStop();
        availableUnderlyingNetworks.clear();
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {
            }
            networkCallback = null;
        }
    }

    private void scheduleNetworkLostAutoStop() {
        cancelPendingNetworkLostStop();
        ScheduledExecutorService exec = scheduler;
        if (exec == null || exec.isShutdown()) {
            if (!hasActiveUnderlyingInternet() && isAutoStopEnabled()) {
                autoStopOnDisconnect(getString(R.string.log_auto_stop_network_lost));
            }
            return;
        }
        pendingNetworkLostFuture = exec.schedule(() -> {
            if (isRunning.get() && isAutoStopEnabled()
                    && availableUnderlyingNetworks.isEmpty()
                    && !hasActiveUnderlyingInternet()) {
                autoStopOnDisconnect(getString(R.string.log_auto_stop_network_lost));
            }
        }, NETWORK_LOST_GRACE_MS, TimeUnit.MILLISECONDS);
    }

    private void cancelPendingNetworkLostStop() {
        ScheduledFuture<?> future = pendingNetworkLostFuture;
        if (future != null) {
            future.cancel(false);
            pendingNetworkLostFuture = null;
        }
    }

    private boolean hasActiveUnderlyingInternet() {
        if (connectivityManager == null) {
            return true;
        }
        try {
            Network[] networks = connectivityManager.getAllNetworks();
            for (Network network : networks) {
                NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(network);
                if (caps != null
                        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    private void runDisconnectHealthCheck() {
        if (!isRunning.get() || !isAutoStopEnabled()) {
            return;
        }
        if (!hasActiveUnderlyingInternet()) {
            autoStopOnDisconnect(getString(R.string.log_auto_stop_network_lost));
            return;
        }
        long idleMs = System.currentTimeMillis() - lastUpstreamSuccessMs.get();
        if (idleMs < HEALTH_CHECK_IDLE_THRESHOLD_MS) {
            return;
        }
        ProxyConfig proxy = activeProxy;
        ExecutorService probeExec = healthProbeExecutor;
        if (proxy == null || probeExec == null || probeExec.isShutdown()) {
            return;
        }
        if (!healthProbeInFlight.compareAndSet(false, true)) {
            return;
        }
        probeExec.execute(() -> {
            try {
                if (!isRunning.get() || !isAutoStopEnabled()) {
                    return;
                }
                if (isProxyTcpReachable(proxy, HEALTH_PROBE_TIMEOUT_MS)) {
                    healthProbeFailures.set(0);
                    lastUpstreamSuccessMs.set(System.currentTimeMillis());
                } else if (healthProbeFailures.incrementAndGet() >= AUTO_STOP_CONSECUTIVE_FAILURES
                        && isRunning.get()
                        && isAutoStopEnabled()) {
                    autoStopOnDisconnect(getString(R.string.log_auto_stop_proxy_lost,
                            proxy.getAddressDisplay(), "Timeout"));
                }
            } finally {
                healthProbeInFlight.set(false);
            }
        });
    }

    private static boolean isProxyTcpReachable(ProxyConfig proxy, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(proxy.getHost(), proxy.getPort()), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void stopScheduler() {
        cancelPendingNetworkLostStop();
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (healthProbeExecutor != null) {
            healthProbeExecutor.shutdownNow();
            healthProbeExecutor = null;
        }
        healthProbeInFlight.set(false);
    }

    @Override
    public LocalProxyServer.PolicyResult checkRequest(Socket clientSocket, String targetHost, int targetPort) {
        if (!privateRoutingEnabledSnapshot
                && PrivateNetworkMatcher.isPrivateOrConfiguredTarget(targetHost, privateRoutingRangesSnapshot)) {
            return LocalProxyServer.PolicyResult.BLOCK_PRIVATE_ROUTING_DISABLED;
        }
        if (firewallEnabledSnapshot && !activeFirewallRulesSnapshot.isEmpty()) {
            String[] callerPackages = resolveCallerPackages(clientSocket);
            for (FirewallRule fwRule : activeFirewallRulesSnapshot) {
                if (!fwRule.isEnabled()) continue;
                boolean matchesApp = false;
                if (fwRule.getBlockedPackages().isEmpty()) {
                    // Rule with target filter but no specific apps applies globally
                    matchesApp = !fwRule.blocksAllInternet();
                } else if (callerPackages != null) {
                    for (String pkg : callerPackages) {
                        if (fwRule.appliesToPackage(pkg)) {
                            matchesApp = true;
                            break;
                        }
                    }
                }
                if (matchesApp && fwRule.matchesTarget(targetHost, targetPort)) {
                    logFirewallBlock(callerPackages != null && callerPackages.length > 0 ? callerPackages[0] : fwRule.getName(),
                            targetHost + ":" + targetPort);
                    return LocalProxyServer.PolicyResult.BLOCK_FIREWALL;
                }
            }
        }
        return LocalProxyServer.PolicyResult.ALLOW;
    }

    private String[] resolveCallerPackages(Socket clientSocket) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || connectivityManager == null || clientSocket == null) {
            return null;
        }
        try {
            InetSocketAddress remote = (InetSocketAddress) clientSocket.getRemoteSocketAddress();
            InetSocketAddress local = (InetSocketAddress) clientSocket.getLocalSocketAddress();
            if (remote == null || local == null) {
                return null;
            }
            int uid = connectivityManager.getConnectionOwnerUid(OsConstants.IPPROTO_TCP, remote, local);
            if (uid > 0) {
                return getPackageManager().getPackagesForUid(uid);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void logFirewallBlock(String appOrRule, String destination) {
        long now = System.currentTimeMillis();
        long prev = lastFirewallBlockLogMs.get();
        if (now - prev >= 5_000L && lastFirewallBlockLogMs.compareAndSet(prev, now)) {
            repository.addLog(ProxyLogEntry.Level.WARN,
                    getString(R.string.log_firewall_blocked_request, appOrRule, destination));
        }
    }

    @Override
    public void onUpstreamSuccess(long proxyId) {
        if (activeProxy != null && activeProxy.getId() == proxyId) {
            consecutiveUpstreamFailures.set(0);
            healthProbeFailures.set(0);
            lastUpstreamSuccessMs.set(System.currentTimeMillis());
        }
    }

    @Override
    public void onUpstreamFailure(long proxyId, String reason) {
        if (!isRunning.get() || activeProxy == null || activeProxy.getId() != proxyId) {
            return;
        }
        if (isAutoStopEnabled()) {
            int failures = consecutiveUpstreamFailures.incrementAndGet();
            if (failures >= AUTO_STOP_CONSECUTIVE_FAILURES) {
                autoStopOnDisconnect(getString(R.string.log_auto_stop_proxy_lost,
                        activeProxy.getAddressDisplay(), reason));
                return;
            }
        }
        // Log throttled warning only; never switch or rotate the user's selected proxy
        long now = System.currentTimeMillis();
        long prev = lastFailureLogMs.get();
        if (now - prev >= FAILURE_LOG_THROTTLE_MS && lastFailureLogMs.compareAndSet(prev, now)) {
            repository.addLog(ProxyLogEntry.Level.WARN,
                    "Proxy " + activeProxy.getAddressDisplay() + " gặp lỗi truyền tải (" + reason + ").");
        }
    }

    @Override
    public void onRevoke() {
        stopTunnelAndService("");
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        unregisterNetworkMonitor();
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

    private void applyConfiguredVpnTimer(long baseTimeMs, boolean logChange) {
        int minutes = settingsPreferences != null
                ? settingsPreferences.getVpnTimerMinutes()
                : SettingsPreferences.VPN_TIMER_ALWAYS;
        activeVpnTimerMinutes = minutes;
        if (minutes > 0) {
            vpnTimerDeadlineMs = baseTimeMs + (minutes * 60_000L);
            if (logChange) {
                repository.addLog(ProxyLogEntry.Level.INFO,
                        getString(R.string.log_vpn_timer_set, formatTimerMinutes(minutes)));
            }
        } else {
            vpnTimerDeadlineMs = 0L;
            if (logChange) {
                repository.addLog(ProxyLogEntry.Level.INFO, getString(R.string.log_vpn_timer_always));
            }
        }
    }

    private String formatTimerMinutes(int minutes) {
        if (minutes <= 0) {
            return getString(R.string.vpn_timer_always);
        }
        int hours = minutes / 60;
        int remMinutes = minutes % 60;
        if (hours > 0 && remMinutes > 0) {
            return getString(R.string.vpn_timer_hours_minutes, hours, remMinutes);
        }
        if (hours > 0) {
            return getString(R.string.vpn_timer_hours, hours);
        }
        return getString(R.string.vpn_timer_minutes, minutes);
    }

    private static String formatRemainingCountdown(long remainingSeconds) {
        long safe = Math.max(0L, remainingSeconds);
        long hours = safe / 3600L;
        long mins = (safe % 3600L) / 60L;
        long secs = safe % 60L;
        if (hours > 0) {
            return String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, mins, secs);
        }
        return String.format(java.util.Locale.US, "%02d:%02d", mins, secs);
    }

    private void updateNotification() {
        if (!isRunning.get() || activeProxy == null) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            String title = getString(R.string.notif_title_connected, activeProxy.getAddressDisplay());
            String body;
            long deadline = vpnTimerDeadlineMs;
            if (deadline > 0L) {
                long remainingSec = Math.max(0L, (deadline - System.currentTimeMillis() + 999L) / 1000L);
                body = getString(R.string.notif_text_timer_remaining,
                        localProxyServer.getLocalPort(), formatRemainingCountdown(remainingSec));
            } else {
                body = getString(R.string.notif_text_connected,
                        localProxyServer.getLocalPort(), getString(R.string.label_manual_fixed_ip));
            }
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
