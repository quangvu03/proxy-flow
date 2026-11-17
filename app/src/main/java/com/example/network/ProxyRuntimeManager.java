package com.example.network;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.model.ConnectionStatus;
import com.example.model.ProxyConfig;

/**
 * Thread-safe singleton holding live ConnectionStatus telemetry shared between
 * ProxyVpnService and the ViewModel / UI layer.
 */
public final class ProxyRuntimeManager {

    private static volatile ProxyRuntimeManager instance;

    private final MutableLiveData<ConnectionStatus> statusLiveData =
            new MutableLiveData<>(ConnectionStatus.disconnected(null));

    private ProxyRuntimeManager() {}

    public static ProxyRuntimeManager getInstance() {
        if (instance == null) {
            synchronized (ProxyRuntimeManager.class) {
                if (instance == null) {
                    instance = new ProxyRuntimeManager();
                }
            }
        }
        return instance;
    }

    public LiveData<ConnectionStatus> getStatus() {
        return statusLiveData;
    }

    public ConnectionStatus getCurrentStatus() {
        ConnectionStatus s = statusLiveData.getValue();
        return s != null ? s : ConnectionStatus.disconnected(null);
    }

    public void postStatus(ConnectionStatus status) {
        statusLiveData.postValue(status);
    }

    public void postDisconnected(ProxyConfig selectedProxy, String errorMsg) {
        statusLiveData.postValue(new ConnectionStatus(
                errorMsg != null && !errorMsg.isEmpty()
                        ? ConnectionStatus.State.ERROR
                        : ConnectionStatus.State.DISCONNECTED,
                selectedProxy,
                0,
                0L,
                0L,
                0,
                errorMsg,
                0L
        ));
    }
}
