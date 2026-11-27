package com.example.network;

import android.app.Notification;
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
import com.example.R;
import com.example.data.InstalledAppScanner;
import com.example.data.ProxyLogFormatter;
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
import java.util.Locale;
import java.util.Map;
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
 * LocalProxyServer using a fixed user-selected proxy.
 * Never switches or rotates proxies automatically; proxy changes occur only on
 * explicit user action. Supports per-app allowlist / blocklist routing, firewall
 * app-blocking rules, private network routing, and full DNS/traffic process logging.
 */
public class ProxyVpnService extends VpnService
        implements LocalProxyServer.EventListener, LocalProxyServer.PolicyChecker, LocalProxyServer.TrafficListener {

    public static final String ACTION_START = "com.example.action.START_VPN";
    public static final String ACTION_STOP = "com.example.action.STOP_VPN";
    public static final String ACTION_SWITCH_TO_PROXY = "com.example.action.SWITCH_TO_PROXY";
    public static final String ACTION_REAPPLY_APP_RULES = "com.example.action.REAPPLY_APP_RULES";
    public static final String ACTION_REAPPLY_POLICIES = "com.example.action.REAPPLY_POLICIES";
    public static final String ACTION_UPDATE_VPN_TIMER = "com.example.action.UPDATE_VPN_TIMER";
    public static final String ACTION_UPDATE_NOTIFICATIONS = "com.example.action.UPDATE_NOTIFICATIONS";
    public static final String EXTRA_PROXY_ID = "extra_proxy_id";

    private static final long FAILURE_LOG_THROTTLE_MS = 15_000L;
    private static final long NETWORK_LOST_GRACE_MS = 2_500L;
    private static final int AUTO_STOP_CONSECUTIVE_FAILURES = 2;
    private static final int FATAL_UPSTREAM_AUTO_DISCONNECT_FAILURES = 3;
    private static final int CONNECT_VERIFY_TIMEOUT_MS = 6_000;
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
    private final ExecutorService serviceControlExecutor = Executors.newSingleThreadExecutor();
    private volatile ScheduledFuture<?> pendingNetworkLostFuture;

    private final Set<Network> availableUnderlyingNetworks = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private final AtomicBoolean switchingProxy = new AtomicBoolean(false);
    private final AtomicBoolean healthProbeInFlight = new AtomicBoolean(false);
    private final AtomicInteger consecutiveUpstreamFailures = new AtomicInteger(0);
    private final AtomicInteger healthProbeFailures = new AtomicInteger(0);
    private final AtomicInteger telemetryTickCounter = new AtomicInteger(0);
    private final AtomicLong lastFailureLogMs = new AtomicLong(0L);
    private final AtomicLong lastUpstreamSuccessMs = new AtomicLong(0L);
    private final Map<String, Long> recentTrafficDedupeMs = new ConcurrentHashMap<>();

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

    public static void refreshNotifications(Context context) {
        Intent intent = new Intent(context, ProxyVpnService.class);
        intent.setAction(ACTION_UPDATE_NOTIFICATIONS);
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
        localProxyServer.setTrafficListener(this);
        refreshPolicySnapshots();
        ProxyNotificationManager.ensureChannels(this);
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

        if (ACTION_UPDATE_NOTIFICATIONS.equals(action)) {
            if (isRunning.get()) {
                updateNotification();
            }
            return START_STICKY;
        }

        long requestedId = intent != null ? intent.getLongExtra(EXTRA_PROXY_ID, -1L) : -1L;
        Notification initialNotification = ProxyNotificationManager.buildVpnForegroundNotification(
                this,
                activeProxy,
                0,
                0L,
                SettingsPreferences.VPN_TIMER_ALWAYS,
                getString(R.string.status_connecting),
                ""
        );
        startForegroundCompat(initialNotification);
        startTunnelAsync(requestedId);
        return START_STICKY;
    }

    private void startTunnelAsync(long requestedProxyId) {
        if (serviceControlExecutor.isShutdown()) return;
        serviceControlExecutor.execute(() -> startTunnelInternal(requestedProxyId));
    }

    private void startTunnelInternal(long requestedProxyId) {
        ProxyConfig chosen = requestedProxyId > 0
                ? repository.getProxyByIdSync(requestedProxyId)
                : repository.getActiveProxySync();
        if (chosen == null) {
            chosen = repository.getActiveProxySync();
        }
        if (chosen == null) {
            repository.addLog(ProxyLogEntry.Level.ERROR,
                    ProxyLogFormatter.getString(this, R.string.log_vpn_no_proxy));
            stopTunnelAndService(ProxyLogFormatter.getString(this, R.string.msg_no_proxy_to_connect));
            return;
        }

        activeProxy = chosen;
        stopping.set(false);
        consecutiveUpstreamFailures.set(0);
        healthProbeFailures.set(0);
        telemetryTickCounter.set(0);
        lastUpstreamSuccessMs.set(System.currentTimeMillis());

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

        repository.markActiveProxySync(chosen.getId());

        ProxyConfig verifiedProxy = verifyAndResolveProxyOnConnect(chosen);
        if (verifiedProxy == null) {
            return;
        }
        activeProxy = verifiedProxy;

        try {
            int localPort = localProxyServer.start(verifiedProxy);
            establishVpnInterface(verifiedProxy, localPort);
            connectedSinceMs = System.currentTimeMillis();
            applyConfiguredVpnTimer(connectedSinceMs, false);
            isRunning.set(true);
            updateNotification();
            registerNetworkMonitor();
            startTelemetryTicker();

            repository.addLog(ProxyLogEntry.Level.SUCCESS,
                    ProxyLogFormatter.getString(this, R.string.log_vpn_connected,
                            verifiedProxy.getAddressDisplay(), localPort),
                    getPackageName(), "VPN");
            if (activeVpnTimerMinutes > 0) {
                repository.addLog(ProxyLogEntry.Level.INFO,
                        ProxyLogFormatter.getString(this, R.string.log_vpn_timer_set,
                                formatTimerMinutes(activeVpnTimerMinutes)),
                        getPackageName(), "VPN");
            }
            ProxyNotificationManager.notifyVpnConnected(this, verifiedProxy, activeVpnTimerMinutes);
            lastUpstreamSuccessMs.set(System.currentTimeMillis());

            publishStatus(ConnectionStatus.State.CONNECTED, "");
            updateNotification();
        } catch (Exception e) {
            String msg = e.getMessage() != null
                    ? e.getMessage()
                    : ProxyLogFormatter.getString(this, R.string.log_vpn_interface_failed);
            repository.addLog(ProxyLogEntry.Level.ERROR,
                    ProxyLogFormatter.getString(this, R.string.log_vpn_error, msg),
                    getPackageName(), "ERROR");
            stopTunnelAndService(msg);
        }
    }

    private ProxyConfig verifyAndResolveProxyOnConnect(ProxyConfig chosen) {
        ProxyTester.TestResult result = ProxyTester.testProxy(chosen, CONNECT_VERIFY_TIMEOUT_MS);
        if (stopping.get()) {
            return null;
        }
        if (!result.isSuccess()) {
            String reason = com.example.network.ProxyFailureFormatter.format(
                    SettingsPreferences.getLocalizedContext(this),
                    result.getFailureReason(),
                    result.getHttpStatusCode(),
                    chosen.getHost(),
                    chosen.getPort(),
                    chosen.getAuthMethod(),
                    result.getErrorMessage()
            );
            repository.recordProxyFailure(chosen.getId(), result.getFailureReason(),
                    result.getHttpStatusCode(), result.getErrorMessage());
            repository.updateTelemetrySync(
                    chosen.getId(),
                    ProxyConfig.LATENCY_FAILED,
                    chosen.getExitIp(),
                    chosen.getCountry(),
                    chosen.getFailCount() + 1
            );
            repository.addLog(ProxyLogEntry.Level.ERROR,
                    ProxyLogFormatter.getString(this, R.string.log_proxy_connect_refused,
                            chosen.getAddressDisplay(), reason),
                    getPackageName(), "ERROR");
            String errMsg = ProxyLogFormatter.getString(
                    this, R.string.msg_proxy_connect_blocked_error, chosen.getAddressDisplay(), reason);
            stopTunnelAndService(errMsg, null, true);
            return null;
        }
        repository.clearProxyFailure(chosen.getId());

        if (chosen.getProtocol().isAutoDetect() && result.getDetectedProtocol() != null) {
            chosen.setProtocol(result.getDetectedProtocol());
            repository.updateProxyProtocolSync(chosen.getId(), result.getDetectedProtocol());
            repository.addLog(ProxyLogEntry.Level.INFO,
                    ProxyLogFormatter.getString(this, R.string.log_protocol_auto_detected,
                            chosen.getAddressDisplay(), result.getDetectedProtocol().getDisplayLabel()),
                    getPackageName(), "PROXY");
        }
        String exitIp = !result.getExitIp().isEmpty() ? result.getExitIp() : chosen.getExitIp();
        String country = !result.getCountry().isEmpty() ? result.getCountry() : chosen.getCountry();
        chosen.setLatencyMs(result.getLatencyMs());
        chosen.setExitIp(exitIp);
        chosen.setCountry(country);
        chosen.setFailCount(0);
        repository.updateTelemetrySync(chosen.getId(), result.getLatencyMs(), exitIp, country, 0);
        return chosen;
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
        for (String dnsServer : proxy.getResolvedDnsServers()) {
            try {
                builder.addDnsServer(dnsServer);
            } catch (IllegalArgumentException ignored) {
            }
        }
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
            throw new IOException(ProxyLogFormatter.getString(this, R.string.msg_vpn_permission_denied));
        }

        tunDrainThread = new Thread(() -> {
            byte[] packet = new byte[4096];
            try (FileInputStream fis = new FileInputStream(tunInterface.getFileDescriptor())) {
                while (isRunning.get() && tunInterface != null) {
                    int read = fis.read(packet);
                    if (read < 0) break;
                    inspectTunPacket(packet, read);
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
                    String durationLabel = formatTimerMinutes(minutes);
                    ProxyNotificationManager.notifyTimerExpired(this, durationLabel);
                    stopTunnelAndService("",
                            ProxyLogFormatter.getString(this, R.string.log_vpn_timer_expired, durationLabel), false);
                    return;
                }
                if (!switchingProxy.get()) {
                    publishStatus(ConnectionStatus.State.CONNECTED, "");
                    updateNotification();
                }
                int tick = telemetryTickCounter.incrementAndGet();
                if (tick % HEALTH_CHECK_INTERVAL_SECONDS == 0 && isAutoStopEnabled()) {
                    runDisconnectHealthCheck();
                }
            } catch (Exception ignored) {
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    private void switchToSpecificProxyAsync(long targetId) {
        if (serviceControlExecutor.isShutdown()) return;
        serviceControlExecutor.execute(() -> {
            ProxyConfig target = repository.getProxyByIdSync(targetId);
            if (target == null) return;
            if (!isRunning.get()) {
                startTunnelInternal(targetId);
                return;
            }
            stopping.set(false);
            switchingProxy.set(true);
            try {
                runtimeManager.postStatus(new ConnectionStatus(
                        ConnectionStatus.State.CONNECTING,
                        target,
                        localProxyServer != null ? localProxyServer.getLocalPort() : 0,
                        localProxyServer != null ? localProxyServer.getBytesIn() : 0L,
                        localProxyServer != null ? localProxyServer.getBytesOut() : 0L,
                        localProxyServer != null ? localProxyServer.getActiveConnections() : 0,
                        "",
                        connectedSinceMs
                ));
                ProxyConfig verified = verifyAndResolveProxyOnConnect(target);
                if (verified == null || !isRunning.get()) {
                    return;
                }
                applyManualProxyChange(verified);
            } finally {
                switchingProxy.set(false);
            }
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
        if (serviceControlExecutor.isShutdown()) return;
        serviceControlExecutor.execute(() -> {
            refreshPolicySnapshots();
            if (!isRunning.get() || activeProxy == null) return;
            try {
                establishVpnInterface(activeProxy, localProxyServer.getLocalPort());
            } catch (IOException e) {
                repository.addLog(ProxyLogEntry.Level.ERROR,
                        ProxyLogFormatter.getString(this, R.string.log_vpn_policy_update_failed, e.getMessage()));
            }
        });
    }

    private void reapplyAppRulesAsync() {
        if (serviceControlExecutor.isShutdown()) return;
        serviceControlExecutor.execute(() -> {
            if (!isRunning.get() || activeProxy == null) return;
            try {
                establishVpnInterface(activeProxy, localProxyServer.getLocalPort());
                AppProxyRule rule = repository.getAppProxyRuleSync();
                int count = rule.getSelectedPackages().size();
                String msg = rule.getMode() == AppProxyRule.Mode.ALLOW_SELECTED
                        ? ProxyLogFormatter.getString(this, R.string.log_app_rule_allow_applied, count)
                        : ProxyLogFormatter.getString(this, R.string.log_app_rule_block_applied, count);
                repository.addLog(ProxyLogEntry.Level.INFO, msg);
            } catch (IOException e) {
                repository.addLog(ProxyLogEntry.Level.ERROR,
                        ProxyLogFormatter.getString(this, R.string.log_app_rule_update_failed, e.getMessage()));
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
                ProxyLogFormatter.getString(this, R.string.log_vpn_proxy_switched, nextProxy.getAddressDisplay()),
                getPackageName(), "PROXY");
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
        stopTunnelAndService(errorMsg, null, true);
    }

    private void stopTunnelAndService(String errorMsg, String customDisconnectLog, boolean notifyDisconnectEvent) {
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
            repository.addLog(ProxyLogEntry.Level.WARN, customDisconnectLog, getPackageName(), "VPN");
            if (notifyDisconnectEvent) {
                ProxyNotificationManager.notifyVpnDisconnected(this, customDisconnectLog, true);
            }
        } else if (errorMsg == null || errorMsg.isEmpty()) {
            repository.addLog(ProxyLogEntry.Level.INFO,
                    ProxyLogFormatter.getString(this, R.string.log_vpn_disconnected),
                    getPackageName(), "VPN");
            if (notifyDisconnectEvent) {
                ProxyNotificationManager.notifyVpnDisconnected(this, null, false);
            }
        } else {
            if (notifyDisconnectEvent) {
                ProxyNotificationManager.notifyVpnDisconnected(this, errorMsg, true);
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void autoStopOnDisconnect(String logMessage) {
        autoStopOnDisconnect(logMessage, "");
    }

    private void autoStopOnDisconnect(String logMessage, String errorMsg) {
        if (!isRunning.get()) {
            return;
        }
        stopTunnelAndService(errorMsg, logMessage, true);
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
                autoStopOnDisconnect(ProxyLogFormatter.getString(this, R.string.log_auto_stop_network_lost));
            }
            return;
        }
        pendingNetworkLostFuture = exec.schedule(() -> {
            if (isRunning.get() && isAutoStopEnabled()
                    && availableUnderlyingNetworks.isEmpty()
                    && !hasActiveUnderlyingInternet()) {
                autoStopOnDisconnect(ProxyLogFormatter.getString(this, R.string.log_auto_stop_network_lost));
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
            autoStopOnDisconnect(ProxyLogFormatter.getString(this, R.string.log_auto_stop_network_lost));
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
                    autoStopOnDisconnect(ProxyLogFormatter.getString(this, R.string.log_auto_stop_proxy_lost,
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
                    String callerPkg = (callerPackages != null && callerPackages.length > 0)
                            ? callerPackages[0]
                            : "";
                    logFirewallBlock(
                            !callerPkg.isEmpty() ? callerPkg : fwRule.getName(),
                            callerPkg,
                            targetHost + ":" + targetPort);
                    return LocalProxyServer.PolicyResult.BLOCK_FIREWALL;
                }
            }
        }
        return LocalProxyServer.PolicyResult.ALLOW;
    }

    @Override
    public String resolveCallerPackage(Socket clientSocket) {
        String[] pkgs = resolveCallerPackages(clientSocket);
        if (pkgs != null && pkgs.length > 0 && pkgs[0] != null) {
            return pkgs[0];
        }
        return "";
    }

    @Override
    public void onDnsDomainRequested(String callerPackage, String domain) {
        if (repository == null || domain == null || domain.isEmpty()) return;
        String key = "DNS|" + domain.toLowerCase(Locale.ROOT);
        if (shouldEmitDeduplicated(key, 1500L)) {
            String pkg = (callerPackage != null && !callerPackage.isEmpty()) ? callerPackage : getPackageName();
            repository.addLog(
                    ProxyLogEntry.Level.INFO,
                    ProxyLogFormatter.getString(this, R.string.log_dns_query, "A", domain),
                    pkg,
                    "DNS"
            );
        }
    }

    @Override
    public void onSendStarted(String callerPackage, String methodTag, String target, ProxyConfig proxy) {
        if (repository == null || target == null || target.isEmpty() || proxy == null) return;
        String pkg = (callerPackage != null && !callerPackage.isEmpty()) ? callerPackage : getPackageName();
        String route = proxy.getSchemePrefix() + proxy.getAddressDisplay();
        repository.addLog(
                ProxyLogEntry.Level.INFO,
                ProxyLogFormatter.getString(this, R.string.log_traffic_send_request, methodTag, target, route),
                pkg,
                methodTag
        );
    }

    @Override
    public void onTunnelEstablished(String callerPackage, String methodTag, String target, long handshakeMs) {
        if (repository == null || target == null || target.isEmpty()) return;
        String pkg = (callerPackage != null && !callerPackage.isEmpty()) ? callerPackage : getPackageName();
        repository.addLog(
                ProxyLogEntry.Level.SUCCESS,
                ProxyLogFormatter.getString(this, R.string.log_traffic_tunnel_ready, methodTag, target, handshakeMs),
                pkg,
                "SEND"
        );
    }

    @Override
    public void onTransferCompleted(String callerPackage, String target,
                                    long sentBytes, long recvBytes, long durationMs) {
        if (repository == null || target == null || target.isEmpty()) return;
        String pkg = (callerPackage != null && !callerPackage.isEmpty()) ? callerPackage : getPackageName();
        repository.addLog(
                ProxyLogEntry.Level.SUCCESS,
                ProxyLogFormatter.getString(this, R.string.log_traffic_transfer_done,
                        target, formatByteCount(sentBytes), formatByteCount(recvBytes), durationMs),
                pkg,
                "DONE"
        );
    }

    @Override
    public void onSendFailed(String callerPackage, String methodTag, String target, String reason) {
        if (repository == null || target == null || target.isEmpty()) return;
        String pkg = (callerPackage != null && !callerPackage.isEmpty()) ? callerPackage : getPackageName();
        repository.addLog(
                ProxyLogEntry.Level.ERROR,
                ProxyLogFormatter.getString(this, R.string.log_traffic_send_failed, methodTag, target,
                        reason != null ? reason : ""),
                pkg,
                "ERROR"
        );
    }

    @Override
    public void onRequestBlocked(String callerPackage, LocalProxyServer.PolicyResult reason, String target) {
        if (repository == null || target == null || target.isEmpty()) return;
        if (reason == LocalProxyServer.PolicyResult.BLOCK_PRIVATE_ROUTING_DISABLED) {
            String pkg = (callerPackage != null && !callerPackage.isEmpty()) ? callerPackage : getPackageName();
            repository.addLog(
                    ProxyLogEntry.Level.WARN,
                    ProxyLogFormatter.getString(this, R.string.log_traffic_private_blocked, target),
                    pkg,
                    "BLOCK"
            );
        }
    }

    private void inspectTunPacket(byte[] packet, int length) {
        TunPacketParser.ParsedPacket parsed = TunPacketParser.parse(packet, length);
        if (parsed == null || repository == null) {
            return;
        }
        String pkg = resolveCallerPackageForPacket(parsed);
        if (pkg.isEmpty()) {
            pkg = getPackageName();
        }
        switch (parsed.getKind()) {
            case DNS_QUERY: {
                String domain = parsed.getDnsDomain();
                String key = "DNS|" + domain.toLowerCase(Locale.ROOT);
                if (shouldEmitDeduplicated(key, 1500L)) {
                    repository.addLog(
                            ProxyLogEntry.Level.INFO,
                            ProxyLogFormatter.getString(this, R.string.log_dns_query,
                                    parsed.getDnsRecordType(), domain),
                            pkg,
                            "DNS"
                    );
                }
                break;
            }
            case TCP_SYN: {
                String target = parsed.getDstIp() + ":" + parsed.getDstPort();
                if (shouldEmitDeduplicated("TCP|" + pkg + "|" + target, 1500L)) {
                    repository.addLog(
                            ProxyLogEntry.Level.INFO,
                            ProxyLogFormatter.getString(this, R.string.log_traffic_tcp_syn, target),
                            pkg,
                            "TCP"
                    );
                }
                break;
            }
            case UDP_PACKET: {
                String target = parsed.getDstIp() + ":" + parsed.getDstPort();
                if (shouldEmitDeduplicated("UDP|" + pkg + "|" + target, 3000L)) {
                    repository.addLog(
                            ProxyLogEntry.Level.INFO,
                            ProxyLogFormatter.getString(this, R.string.log_traffic_udp_packet,
                                    target, parsed.getPayloadBytes()),
                            pkg,
                            "UDP"
                    );
                }
                break;
            }
        }
    }

    private boolean shouldEmitDeduplicated(String key, long windowMs) {
        long now = System.currentTimeMillis();
        if (recentTrafficDedupeMs.size() > 256) {
            recentTrafficDedupeMs.entrySet().removeIf(e -> now - e.getValue() > 10_000L);
        }
        Long prev = recentTrafficDedupeMs.get(key);
        if (prev != null && (now - prev) < windowMs) {
            return false;
        }
        recentTrafficDedupeMs.put(key, now);
        return true;
    }

    private String resolveCallerPackageForPacket(TunPacketParser.ParsedPacket parsed) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || connectivityManager == null || parsed == null) {
            return "";
        }
        try {
            InetSocketAddress src = new InetSocketAddress(parsed.getSrcIp(), parsed.getSrcPort());
            InetSocketAddress dst = new InetSocketAddress(parsed.getDstIp(), parsed.getDstPort());
            int uid = connectivityManager.getConnectionOwnerUid(parsed.getProtocol(), src, dst);
            if (uid > 0) {
                String[] pkgs = getPackageManager().getPackagesForUid(uid);
                if (pkgs != null && pkgs.length > 0 && pkgs[0] != null) {
                    return pkgs[0];
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String formatByteCount(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0));
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

    private void logFirewallBlock(String appOrRule, String callerPackage, String destination) {
        long now = System.currentTimeMillis();
        long prev = lastFirewallBlockLogMs.get();
        if (now - prev >= 5_000L && lastFirewallBlockLogMs.compareAndSet(prev, now)) {
            repository.addLog(ProxyLogEntry.Level.WARN,
                    ProxyLogFormatter.getString(this, R.string.log_firewall_blocked_request, appOrRule, destination),
                    callerPackage != null ? callerPackage : getPackageName(),
                    "FW");
        }
        ProxyNotificationManager.notifyFirewallBlocked(this, appOrRule, destination);
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
        int failures = consecutiveUpstreamFailures.incrementAndGet();
        int threshold = isAutoStopEnabled()
                ? AUTO_STOP_CONSECUTIVE_FAILURES
                : FATAL_UPSTREAM_AUTO_DISCONNECT_FAILURES;
        if (failures >= threshold) {
            repository.updateTelemetrySync(
                    activeProxy.getId(),
                    ProxyConfig.LATENCY_FAILED,
                    activeProxy.getExitIp(),
                    activeProxy.getCountry(),
                    activeProxy.getFailCount() + 1
            );
            String disconnectMsg = ProxyLogFormatter.getString(
                    this, R.string.log_auto_stop_proxy_lost, activeProxy.getAddressDisplay(), reason);
            autoStopOnDisconnect(disconnectMsg, disconnectMsg);
            return;
        }
        // Log throttled warning while below auto-disconnect threshold
        long now = System.currentTimeMillis();
        long prev = lastFailureLogMs.get();
        if (now - prev >= FAILURE_LOG_THROTTLE_MS && lastFailureLogMs.compareAndSet(prev, now)) {
            repository.addLog(ProxyLogEntry.Level.WARN,
                    ProxyLogFormatter.getString(this, R.string.log_vpn_upstream_failure,
                            activeProxy.getAddressDisplay(), reason));
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
        serviceControlExecutor.shutdownNow();
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
            startForeground(ProxyNotificationManager.NOTIF_ID_VPN_FOREGROUND, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(ProxyNotificationManager.NOTIF_ID_VPN_FOREGROUND, notification);
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
                        ProxyLogFormatter.getString(this, R.string.log_vpn_timer_set, formatTimerMinutes(minutes)));
            }
        } else {
            vpnTimerDeadlineMs = 0L;
            if (logChange) {
                repository.addLog(ProxyLogEntry.Level.INFO,
                        ProxyLogFormatter.getString(this, R.string.log_vpn_timer_always));
            }
        }
    }

    private String formatTimerMinutes(int minutes) {
        return ProxyLogFormatter.formatTimerMinutes(this, minutes);
    }

    private void updateNotification() {
        if (!isRunning.get() || activeProxy == null) return;
        ProxyNotificationManager.updateVpnForegroundNotification(
                this,
                activeProxy,
                localProxyServer.getLocalPort(),
                vpnTimerDeadlineMs,
                activeVpnTimerMinutes
        );
    }
}
