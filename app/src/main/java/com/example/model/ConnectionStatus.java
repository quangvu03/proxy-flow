package com.example.model;

/**
 * Ảnh chụp trạng thái kết nối VPN và cổng proxy cục bộ tại một thời điểm.
 *
 * Class này là một đối tượng dữ liệu chỉ đọc: các thông tin được truyền từ
 * ProxyVpnService hoặc ProxyRuntimeManager tới giao diện để hiển thị trạng thái
 * và số liệu hiện tại.
 */
public class ConnectionStatus {

    /**
     * Trạng thái hoạt động của phiên kết nối.
     */
    public enum State {
        /** VPN đang ngắt hoặc chưa được khởi động. */
        DISCONNECTED,
        /** VPN đang khởi tạo giao diện và cổng proxy cục bộ. */
        CONNECTING,
        /** VPN và cổng proxy cục bộ đã hoạt động. */
        CONNECTED,
        /** Khởi tạo hoặc duy trì kết nối gặp lỗi. */
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

    /**
     * Tạo một bản ghi trạng thái kết nối hoàn chỉnh.
     *
     * @param state trạng thái VPN, mặc định là DISCONNECTED nếu truyền null
     * @param activeProxy proxy đang hoạt động hoặc proxy đã chọn
     * @param localProxyPort cổng proxy cục bộ, bằng 0 khi chưa chạy
     * @param bytesIn tổng byte nhận vào
     * @param bytesOut tổng byte gửi ra
     * @param activeConnections số kết nối đang hoạt động
     * @param lastError thông báo lỗi gần nhất
     * @param connectedSinceMs thời điểm bắt đầu kết nối bằng millisecond
     */
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

    /**
     * Tạo trạng thái ngắt kết nối mặc định.
     *
     * @param selectedProxy proxy hiện đang được chọn trong danh sách
     * @return trạng thái DISCONNECTED không có dữ liệu truyền tải hoặc lỗi
     */
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

    /** Trả về trạng thái tổng thể của VPN. */
    public State getState() { return state; }
    /** Trả về proxy đang hoạt động hoặc proxy đã chọn. */
    public ProxyConfig getActiveProxy() { return activeProxy; }
    /** Trả về cổng proxy cục bộ; bằng 0 nếu VPN không hoạt động. */
    public int getLocalProxyPort() { return localProxyPort; }
    /** Trả về tổng số byte nhận vào kể từ lần khởi động hiện tại. */
    public long getBytesIn() { return bytesIn; }
    /** Trả về tổng số byte gửi ra kể từ lần khởi động hiện tại. */
    public long getBytesOut() { return bytesOut; }
    /** Trả về số kết nối hiện đang xử lý qua proxy cục bộ. */
    public int getActiveConnections() { return activeConnections; }
    /** Trả về thông báo lỗi gần nhất; chuỗi rỗng khi không có lỗi. */
    public String getLastError() { return lastError; }
    /** Trả về thời điểm bắt đầu kết nối theo millisecond Unix. */
    public long getConnectedSinceMs() { return connectedSinceMs; }

    /**
     * Kiểm tra phiên VPN có đang hoạt động hay đang trong quá trình khởi động không.
     *
     * @return true khi trạng thái là CONNECTED hoặc CONNECTING
     */
    public boolean isRunning() {
        return state == State.CONNECTED || state == State.CONNECTING;
    }
}
