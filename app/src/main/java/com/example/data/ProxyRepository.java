package com.example.data;

import android.content.Context;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.model.AppProxyRule;
import com.example.model.FirewallRule;
import com.example.model.InstalledAppInfo;
import com.example.model.ProxyConfig;
import com.example.model.ProxyLogEntry;
import com.example.network.ProxyTester;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Central repository coordinating local SQLite proxy storage, SharedPreferences settings,
 * concurrent proxy connectivity testing, and reactive LiveData streams.
 */
public class ProxyRepository {

    private static volatile ProxyRepository instance;

    private final Context appContext;
    private final ProxyDatabaseHelper dbHelper;
    private final SettingsPreferences prefs;
    private final ExecutorService ioExecutor;
    private final ExecutorService testPool;

    private final MutableLiveData<List<ProxyConfig>> proxiesLiveData = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<ProxyConfig> selectedProxyLiveData = new MutableLiveData<>(null);
    private final MutableLiveData<List<ProxyLogEntry>> logsLiveData = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<Boolean> batchTestingLiveData = new MutableLiveData<>(false);
    private final MutableLiveData<AppProxyRule> appProxyRuleLiveData = new MutableLiveData<>();
    private final MutableLiveData<List<InstalledAppInfo>> installedAppsLiveData = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<Boolean> scanningAppsLiveData = new MutableLiveData<>(false);
    private final MutableLiveData<List<FirewallRule>> firewallRulesLiveData = new MutableLiveData<>(new ArrayList<>());

    private ProxyRepository(Context context) {
        this.appContext = context.getApplicationContext();
        this.dbHelper = new ProxyDatabaseHelper(appContext);
        this.prefs = new SettingsPreferences(appContext);
        this.ioExecutor = Executors.newSingleThreadExecutor();
        this.testPool = Executors.newFixedThreadPool(4);
        this.appProxyRuleLiveData.setValue(prefs.loadAppProxyRule());
        reloadAll();
    }

    public static ProxyRepository getInstance(Context context) {
        if (instance == null) {
            synchronized (ProxyRepository.class) {
                if (instance == null) {
                    instance = new ProxyRepository(context);
                }
            }
        }
        return instance;
    }

    public LiveData<List<ProxyConfig>> getProxies() {
        return proxiesLiveData;
    }

    public LiveData<ProxyConfig> getSelectedProxy() {
        return selectedProxyLiveData;
    }

    public LiveData<List<ProxyLogEntry>> getLogs() {
        return logsLiveData;
    }

    public LiveData<Boolean> isBatchTesting() {
        return batchTestingLiveData;
    }

    public LiveData<AppProxyRule> getAppProxyRule() {
        return appProxyRuleLiveData;
    }

    public LiveData<List<InstalledAppInfo>> getInstalledApps() {
        return installedAppsLiveData;
    }

    public LiveData<Boolean> isScanningApps() {
        return scanningAppsLiveData;
    }

    public LiveData<List<FirewallRule>> getFirewallRules() {
        return firewallRulesLiveData;
    }

    public List<FirewallRule> getFirewallRulesSync() {
        return dbHelper.getAllFirewallRules();
    }

    public Set<String> getFirewallBlockedPackagesSync() {
        Set<String> blocked = new HashSet<>();
        if (!prefs.isFirewallEnabled()) {
            return blocked;
        }
        for (FirewallRule rule : dbHelper.getAllFirewallRules()) {
            if (rule.isEnabled()) {
                blocked.addAll(rule.getBlockedPackages());
            }
        }
        return blocked;
    }

    public void reloadFirewallRules() {
        ioExecutor.execute(() -> firewallRulesLiveData.postValue(dbHelper.getAllFirewallRules()));
    }

    public void saveFirewallRule(FirewallRule rule, Runnable onComplete) {
        ioExecutor.execute(() -> {
            if (rule.getId() > 0) {
                dbHelper.updateFirewallRule(rule);
            } else {
                dbHelper.insertFirewallRule(rule);
            }
            firewallRulesLiveData.postValue(dbHelper.getAllFirewallRules());
            if (onComplete != null) {
                onComplete.run();
            }
        });
    }

    public void setFirewallRuleEnabled(long ruleId, boolean enabled, Runnable onComplete) {
        ioExecutor.execute(() -> {
            dbHelper.setFirewallRuleEnabled(ruleId, enabled);
            firewallRulesLiveData.postValue(dbHelper.getAllFirewallRules());
            if (onComplete != null) {
                onComplete.run();
            }
        });
    }

    public void deleteFirewallRule(long ruleId, Runnable onComplete) {
        ioExecutor.execute(() -> {
            dbHelper.deleteFirewallRule(ruleId);
            firewallRulesLiveData.postValue(dbHelper.getAllFirewallRules());
            if (onComplete != null) {
                onComplete.run();
            }
        });
    }

    public AppProxyRule getAppProxyRuleSync() {
        AppProxyRule current = appProxyRuleLiveData.getValue();
        return current != null ? current : prefs.loadAppProxyRule();
    }

    public void scanInstalledApps() {
        Boolean running = scanningAppsLiveData.getValue();
        if (running != null && running) return;
        scanningAppsLiveData.postValue(true);
        ioExecutor.execute(() -> {
            try {
                List<InstalledAppInfo> apps = InstalledAppScanner.scanInstalledApps(appContext);
                installedAppsLiveData.postValue(apps);
            } finally {
                scanningAppsLiveData.postValue(false);
            }
        });
    }

    public void setAppFilterMode(AppProxyRule.Mode mode) {
        AppProxyRule current = getAppProxyRuleSync();
        AppProxyRule updated = new AppProxyRule(mode, current.getSelectedPackages());
        prefs.saveAppProxyRule(updated);
        appProxyRuleLiveData.setValue(updated);
    }

    public void setAppChecked(String packageName, boolean checked) {
        if (packageName == null || packageName.isEmpty()) return;
        AppProxyRule current = getAppProxyRuleSync();
        Set<String> next = new HashSet<>(current.getSelectedPackages());
        if (checked) {
            next.add(packageName);
        } else {
            next.remove(packageName);
        }
        AppProxyRule updated = new AppProxyRule(current.getMode(), next);
        prefs.saveAppProxyRule(updated);
        appProxyRuleLiveData.setValue(updated);
    }

    public void setMultipleAppsChecked(List<String> packageNames, boolean checked) {
        if (packageNames == null) return;
        AppProxyRule current = getAppProxyRuleSync();
        Set<String> next = new HashSet<>(current.getSelectedPackages());
        if (checked) {
            next.addAll(packageNames);
        } else {
            for (String p : packageNames) {
                next.remove(p);
            }
        }
        AppProxyRule updated = new AppProxyRule(current.getMode(), next);
        prefs.saveAppProxyRule(updated);
        appProxyRuleLiveData.setValue(updated);
    }

    public void setAppUsesProxy(String packageName, boolean usesProxy) {
        if (packageName == null || packageName.isEmpty()) return;
        Set<String> bypassed = extractBypassedPackages();
        if (usesProxy) {
            bypassed.remove(packageName);
        } else {
            bypassed.add(packageName);
        }
        AppProxyRule updated = new AppProxyRule(AppProxyRule.Mode.BLOCK_SELECTED, bypassed);
        prefs.saveAppProxyRule(updated);
        appProxyRuleLiveData.setValue(updated);
    }

    public void setMultipleAppsUseProxy(List<String> packageNames, boolean usesProxy) {
        if (packageNames == null) return;
        Set<String> bypassed = extractBypassedPackages();
        if (usesProxy) {
            for (String p : packageNames) {
                bypassed.remove(p);
            }
        } else {
            bypassed.addAll(packageNames);
        }
        AppProxyRule updated = new AppProxyRule(AppProxyRule.Mode.BLOCK_SELECTED, bypassed);
        prefs.saveAppProxyRule(updated);
        appProxyRuleLiveData.setValue(updated);
    }

    private Set<String> extractBypassedPackages() {
        AppProxyRule current = getAppProxyRuleSync();
        if (current.getMode() == AppProxyRule.Mode.BLOCK_SELECTED) {
            return new HashSet<>(current.getSelectedPackages());
        }
        // Migrate legacy ALLOW_SELECTED rule to BLOCK_SELECTED bypassed set
        Set<String> bypassed = new HashSet<>();
        List<InstalledAppInfo> known = installedAppsLiveData.getValue();
        if (known != null) {
            for (InstalledAppInfo app : known) {
                if (!current.isPackageChecked(app.getPackageName())) {
                    bypassed.add(app.getPackageName());
                }
            }
        }
        return bypassed;
    }

    public void clearSelectedApps() {
        AppProxyRule updated = new AppProxyRule(AppProxyRule.Mode.BLOCK_SELECTED, new HashSet<>());
        prefs.saveAppProxyRule(updated);
        appProxyRuleLiveData.setValue(updated);
    }

    public void reloadAll() {
        ioExecutor.execute(() -> {
            List<ProxyConfig> all = dbHelper.getAllProxies();
            proxiesLiveData.postValue(all);
            long activeId = prefs.getActiveProxyId();
            ProxyConfig selected = findById(all, activeId);
            if (selected == null && !all.isEmpty()) {
                selected = all.get(0);
                prefs.setActiveProxyId(selected.getId());
            }
            selectedProxyLiveData.postValue(selected);
            logsLiveData.postValue(dbHelper.getRecentLogs());
            firewallRulesLiveData.postValue(dbHelper.getAllFirewallRules());
        });
    }

    public void addOrUpdateProxy(ProxyConfig proxy, Runnable onComplete) {
        ioExecutor.execute(() -> {
            if (proxy.getId() > 0) {
                dbHelper.updateProxy(proxy);
                dbHelper.insertLog(ProxyLogEntry.Level.INFO,
                        "Đã cập nhật Proxy " + proxy.getAddressDisplay());
            } else {
                long newId = dbHelper.insertProxy(proxy);
                if (prefs.getActiveProxyId() <= 0) {
                    prefs.setActiveProxyId(newId);
                }
                dbHelper.insertLog(ProxyLogEntry.Level.INFO,
                        "Đã thêm Proxy mới " + proxy.getAddressDisplay());
            }
            refreshInternal();
            if (onComplete != null) {
                onComplete.run();
            }
        });
    }

    public void importBulkText(String rawText, ImportCallback callback) {
        ioExecutor.execute(() -> {
            int imported = 0;
            if (rawText != null) {
                String[] lines = rawText.split("\\r?\\n");
                for (String line : lines) {
                    ProxyConfig parsed = ProxyConfig.parseFromLine(line);
                    if (parsed != null) {
                        long id = dbHelper.insertProxy(parsed);
                        if (prefs.getActiveProxyId() <= 0) {
                            prefs.setActiveProxyId(id);
                        }
                        imported++;
                    }
                }
            }
            if (imported > 0) {
                dbHelper.insertLog(ProxyLogEntry.Level.SUCCESS,
                        "Nhập hàng loạt thành công " + imported + " Proxy");
            }
            refreshInternal();
            if (callback != null) {
                callback.onImported(imported);
            }
        });
    }

    public void deleteProxy(long proxyId) {
        ioExecutor.execute(() -> {
            ProxyConfig existing = dbHelper.getProxyById(proxyId);
            dbHelper.deleteProxy(proxyId);
            if (prefs.getActiveProxyId() == proxyId) {
                prefs.setActiveProxyId(-1L);
            }
            if (existing != null) {
                dbHelper.insertLog(ProxyLogEntry.Level.WARN,
                        "Đã xóa Proxy " + existing.getAddressDisplay());
            }
            refreshInternal();
        });
    }

    public void selectProxy(long proxyId) {
        ioExecutor.execute(() -> {
            prefs.setActiveProxyId(proxyId);
            refreshInternal();
        });
    }

    public void addLog(ProxyLogEntry.Level level, String message) {
        ioExecutor.execute(() -> {
            dbHelper.insertLog(level, message);
            logsLiveData.postValue(dbHelper.getRecentLogs());
        });
    }

    public void clearLogs() {
        ioExecutor.execute(() -> {
            dbHelper.clearLogs();
            logsLiveData.postValue(new ArrayList<>());
        });
    }

    public void testSingleProxy(ProxyConfig proxy, TestCallback callback) {
        if (proxy == null) return;
        testPool.execute(() -> {
            ProxyTester.TestResult result = ProxyTester.testProxy(proxy, 8000);
            long now = System.currentTimeMillis();
            if (result.isSuccess()) {
                dbHelper.updateProxyTelemetry(
                        proxy.getId(),
                        result.getLatencyMs(),
                        result.getExitIp(),
                        result.getCountry(),
                        now,
                        0
                );
                dbHelper.insertLog(ProxyLogEntry.Level.SUCCESS,
                        "Test OK " + proxy.getAddressDisplay() + " (" + result.getLatencyMs()
                                + "ms, IP: " + result.getExitIp() + ")");
            } else {
                dbHelper.updateProxyTelemetry(
                        proxy.getId(),
                        ProxyConfig.LATENCY_FAILED,
                        proxy.getExitIp(),
                        proxy.getCountry(),
                        now,
                        proxy.getFailCount() + 1
                );
                dbHelper.insertLog(ProxyLogEntry.Level.ERROR,
                        "Test lỗi " + proxy.getAddressDisplay() + ": " + result.getErrorMessage());
            }
            refreshInternal();
            if (callback != null) {
                callback.onTested(result);
            }
        });
    }

    public void testAllProxies() {
        Boolean running = batchTestingLiveData.getValue();
        if (running != null && running) return;

        ioExecutor.execute(() -> {
            List<ProxyConfig> all = dbHelper.getAllProxies();
            if (all.isEmpty()) {
                return;
            }
            batchTestingLiveData.postValue(true);
            CountDownLatch latch = new CountDownLatch(all.size());
            AtomicInteger okCount = new AtomicInteger(0);

            for (ProxyConfig p : all) {
                testPool.execute(() -> {
                    try {
                        ProxyTester.TestResult res = ProxyTester.testProxy(p, 7000);
                        long now = System.currentTimeMillis();
                        if (res.isSuccess()) {
                            okCount.incrementAndGet();
                            dbHelper.updateProxyTelemetry(
                                    p.getId(), res.getLatencyMs(), res.getExitIp(), res.getCountry(), now, 0);
                        } else {
                            dbHelper.updateProxyTelemetry(
                                    p.getId(), ProxyConfig.LATENCY_FAILED, p.getExitIp(), p.getCountry(), now, p.getFailCount() + 1);
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            try {
                latch.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }

            dbHelper.insertLog(ProxyLogEntry.Level.INFO,
                    "Hoàn tất kiểm tra " + all.size() + " Proxy (" + okCount.get() + " hoạt động)");
            batchTestingLiveData.postValue(false);
            refreshInternal();
        });
    }

    /**
     * Synchronous lookup used by ProxyVpnService when connecting to the user-selected proxy.
     */
    public ProxyConfig getActiveProxySync() {
        long id = prefs.getActiveProxyId();
        ProxyConfig active = id > 0 ? dbHelper.getProxyById(id) : null;
        if (active == null) {
            List<ProxyConfig> all = dbHelper.getAllProxies();
            if (!all.isEmpty()) {
                active = all.get(0);
                prefs.setActiveProxyId(active.getId());
            }
        }
        return active;
    }

    public ProxyConfig getProxyByIdSync(long id) {
        return dbHelper.getProxyById(id);
    }

    public void markActiveProxySync(long proxyId) {
        prefs.setActiveProxyId(proxyId);
        refreshInternal();
    }

    public void updateTelemetrySync(long proxyId, long latencyMs, String exitIp, String country, int failCount) {
        dbHelper.updateProxyTelemetry(proxyId, latencyMs, exitIp, country, System.currentTimeMillis(), failCount);
        refreshInternal();
    }

    public String exportAllProxiesTextSync() {
        List<ProxyConfig> all = dbHelper.getAllProxies();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < all.size(); i++) {
            sb.append(all.get(i).toExportLine());
            if (i < all.size() - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private void refreshInternal() {
        List<ProxyConfig> all = dbHelper.getAllProxies();
        proxiesLiveData.postValue(all);
        long activeId = prefs.getActiveProxyId();
        ProxyConfig selected = findById(all, activeId);
        if (selected == null && !all.isEmpty()) {
            selected = all.get(0);
            prefs.setActiveProxyId(selected.getId());
        }
        selectedProxyLiveData.postValue(selected);
        logsLiveData.postValue(dbHelper.getRecentLogs());
    }

    private ProxyConfig findById(List<ProxyConfig> list, long id) {
        if (id <= 0) return null;
        for (ProxyConfig p : list) {
            if (p.getId() == id) {
                return p;
            }
        }
        return null;
    }

    public interface ImportCallback {
        void onImported(int count);
    }

    public interface TestCallback {
        void onTested(ProxyTester.TestResult result);
    }
}
