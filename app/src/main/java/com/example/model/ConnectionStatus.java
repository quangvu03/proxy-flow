package com.example.model;

/**
 * Snapshot of the live proxy VPN tunnel and local gateway state.
 */
public class ConnectionStatus {

    public enum State {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    private final State state;
    private final ProxyConfig activeProxy;
    private final int localProxyPort;
    private final long bytesIn;
    private final long bytesOut;
    private final int activeConnections;
    private final String lastError;
    private final long connectedSinceMs;

    public ConnectionStatus(State state, ProxyConfig activeProxy, int localProxyPort,
                            long bytesIn, long bytesOut, int activeConnections,
                            String lastError, long connectedSinceMs) {
        this.state = state != null ? state : State.DISCONNECTED;
        this.activeProxy = activeProxy;
        this.localProxyPort = localProxyPort;
        this.bytesIn = bytesIn;
        this.bytesOut = bytesOut;
        this.activeConnections = activeConnections;
        this.lastError = lastError != null ? lastError : "";
        this.connectedSinceMs = connectedSinceMs;
    }

    public static ConnectionStatus disconnected(ProxyConfig selectedProxy) {
        return new ConnectionStatus(
                State.DISCONNECTED,
                selectedProxy,
                0,
                0L,
                0L,
                0,
                "",
                0L
        );
    }

    public State getState() { return state; }
    public ProxyConfig getActiveProxy() { return activeProxy; }
    public int getLocalProxyPort() { return localProxyPort; }
    public long getBytesIn() { return bytesIn; }
    public long getBytesOut() { return bytesOut; }
    public int getActiveConnections() { return activeConnections; }
    public String getLastError() { return lastError; }
    public long getConnectedSinceMs() { return connectedSinceMs; }

    public boolean isRunning() {
        return state == State.CONNECTED || state == State.CONNECTING;
    }
}
