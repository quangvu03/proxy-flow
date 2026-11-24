package com.example.ui;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.data.ProxyRepository;
import com.example.model.AppProxyRule;
import com.example.model.ConnectionStatus;
import com.example.model.InstalledAppInfo;
import com.example.model.IpDetails;
import com.example.model.ProxyConfig;
import com.example.model.ProxyLogEntry;
import com.example.network.IpLookupService;
import com.example.network.ProxyRuntimeManager;
import com.example.network.ProxyVpnService;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shared MVVM ViewModel in 100% Java exposing reactive LiveData streams
 * for proxies, active connection telemetry, IP inspection, and logs.
 */
public class ProxyViewModel extends AndroidViewModel {

    private final ProxyRepository repository;
    private final ProxyRuntimeManager runtimeManager;
    private final ExecutorService ipExecutor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<IpDetails.IpComparisonResult> ipComparisonLiveData = new MutableLiveData<>();
    private final MutableLiveData<Boolean> checkingIpLiveData = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> appRulesPendingApplyLiveData = new MutableLiveData<>(false);

    public ProxyViewModel(@NonNull Application application) {
        super(application);
        this.repository = ProxyRepository.getInstance(application);
        this.runtimeManager = ProxyRuntimeManager.getInstance();
    }

    public LiveData<List<ProxyConfig>> getProxies() {
        return repository.getProxies();
    }

    public LiveData<ProxyConfig> getSelectedProxy() {
        return repository.getSelectedProxy();
    }

    public LiveData<List<ProxyLogEntry>> getLogs() {
        return repository.getLogs();
    }

    public LiveData<Boolean> isBatchTesting() {
        return repository.isBatchTesting();
    }

    public LiveData<ProxyRepository.BatchTestSummary> getBatchTestSummary() {
        return repository.getBatchTestSummary();
    }

    public LiveData<ConnectionStatus> getConnectionStatus() {
        return runtimeManager.getStatus();
    }

    public LiveData<IpDetails.IpComparisonResult> getIpComparison() {
        return ipComparisonLiveData;
    }

    public LiveData<Boolean> isCheckingIp() {
        return checkingIpLiveData;
    }

    public LiveData<AppProxyRule> getAppProxyRule() {
        return repository.getAppProxyRule();
    }

    public LiveData<List<InstalledAppInfo>> getInstalledApps() {
        return repository.getInstalledApps();
    }

    public LiveData<Boolean> isScanningApps() {
        return repository.isScanningApps();
    }

    public LiveData<Boolean> isAppRulesPendingApply() {
        return appRulesPendingApplyLiveData;
    }

    public void scanInstalledApps() {
        repository.scanInstalledApps();
    }

    public void setAppFilterMode(AppProxyRule.Mode mode) {
        repository.setAppFilterMode(mode);
        markPendingApplyIfRunning();
    }

    public void setAppChecked(String packageName, boolean checked) {
        repository.setAppChecked(packageName, checked);
        markPendingApplyIfRunning();
    }

    public void setMultipleAppsChecked(List<String> packageNames, boolean checked) {
        repository.setMultipleAppsChecked(packageNames, checked);
        markPendingApplyIfRunning();
    }

    public void setAppUsesProxy(String packageName, boolean usesProxy) {
        repository.setAppUsesProxy(packageName, usesProxy);
        markPendingApplyIfRunning();
    }

    public void setMultipleAppsUseProxy(List<String> packageNames, boolean usesProxy) {
        repository.setMultipleAppsUseProxy(packageNames, usesProxy);
        markPendingApplyIfRunning();
    }

    public void clearSelectedApps() {
        repository.clearSelectedApps();
        markPendingApplyIfRunning();
    }

    public void applyAppRulesToRunningVpn() {
        appRulesPendingApplyLiveData.setValue(false);
        ConnectionStatus status = runtimeManager.getCurrentStatus();
        if (status.isRunning()) {
            ProxyVpnService.reapplyAppRules(getApplication());
        }
    }

    private void markPendingApplyIfRunning() {
        ConnectionStatus status = runtimeManager.getCurrentStatus();
        if (status.isRunning()) {
            appRulesPendingApplyLiveData.setValue(true);
        }
    }

    public void refreshIpComparison() {
        Boolean running = checkingIpLiveData.getValue();
        if (running != null && running) return;
        checkingIpLiveData.setValue(true);

        ConnectionStatus status = runtimeManager.getCurrentStatus();
        ProxyConfig activeProxy = (status.isRunning() && status.getActiveProxy() != null)
                ? status.getActiveProxy()
                : null;

        ipExecutor.execute(() -> {
            try {
                IpDetails.IpComparisonResult result = IpLookupService.inspectBoth(activeProxy);
                ipComparisonLiveData.postValue(result);
            } finally {
                checkingIpLiveData.postValue(false);
            }
        });
    }

    public void saveProxy(ProxyConfig proxy, Runnable onComplete) {
        repository.addOrUpdateProxy(proxy, onComplete);
    }

    public void importBulk(String rawText, ProxyRepository.ImportCallback callback) {
        repository.importBulkText(rawText, callback);
    }

    public void deleteProxy(long proxyId) {
        repository.deleteProxy(proxyId);
    }

    public void selectProxy(long proxyId) {
        repository.selectProxy(proxyId);
        ConnectionStatus status = runtimeManager.getCurrentStatus();
        if (status.isRunning()) {
            ProxyVpnService.switchToProxy(getApplication(), proxyId);
        }
    }

    public void testProxy(ProxyConfig proxy, ProxyRepository.TestCallback callback) {
        repository.testSingleProxy(proxy, callback);
    }

    public void testAllProxies() {
        repository.testAllProxies();
    }

    public void clearLogs() {
        repository.clearLogs();
    }

    public String exportAllProxiesText() {
        return repository.exportAllProxiesTextSync();
    }

    public void addLog(ProxyLogEntry.Level level, String message) {
        repository.addLog(level, message);
    }
}
