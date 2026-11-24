package com.example.data;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.example.R;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves log strings using the user's selected app language (Vietnamese, English, or System),
 * and re-localizes stored connection log entries for display when the app language changes.
 */
public final class ProxyLogFormatter {

    private static final Pattern P_PROXY_UPDATED = Pattern.compile("^(?:Đã cập nhật Proxy|Updated Proxy) (.+)$");
    private static final Pattern P_PROXY_ADDED = Pattern.compile("^(?:Đã thêm Proxy mới|Added new Proxy) (.+)$");
    private static final Pattern P_PROXY_BULK_IMPORTED = Pattern.compile("^(?:Nhập hàng loạt thành công|Bulk imported) (\\d+) (?:Proxy|Proxies)$");
    private static final Pattern P_PROXY_DELETED = Pattern.compile("^(?:Đã xóa Proxy|Deleted Proxy) (.+)$");
    private static final Pattern P_PROXY_TEST_OK = Pattern.compile("^Test OK (.+?) \\((\\d+)ms, IP: (.*)\\)$");
    private static final Pattern P_PROXY_TEST_FAILED = Pattern.compile("^(?:Test lỗi|Test failed) (.+?): (.+)$");
    private static final Pattern P_PROXY_BATCH_DONE = Pattern.compile("^(?:Hoàn tất kiểm tra (\\d+) Proxy \\((\\d+) hoạt động\\)|Finished testing (\\d+) Proxies \\((\\d+) working\\))$");
    private static final Pattern P_PROXY_EXPORTED = Pattern.compile("^(?:Đã xuất (\\d+) Proxy ra file \\.txt|Exported (\\d+) Proxies to \\.txt file)$");
    private static final Pattern P_PROXY_DETAIL_FAILED = Pattern.compile("^(?:Kiểm tra chi tiết (.+?): Không (?:xài|dùng) được|Detailed check (.+?): Unusable) \\((.+)\\)$");
    private static final Pattern P_PROXY_DETAIL_OK = Pattern.compile("^(?:Kiểm tra OK|Check OK) (.+?) • Ping (\\d+)ms • DL ([\\d.,]+) Mbps$");
    private static final Pattern P_PROXY_CONNECT_REFUSED = Pattern.compile("^(?:Từ chối kết nối Proxy lỗi|Refused connecting to faulty Proxy) (.+?) \\((.+)\\)$");

    private static final Pattern P_VPN_CONNECTED = Pattern.compile("^(?:Đã kết nối VPN Proxy cố định|Connected Fixed Proxy VPN) -> (.+?) \\((?:cổng cục bộ|local port) :(\\d+)\\)$");
    private static final Pattern P_VPN_ERROR = Pattern.compile("^(?:Lỗi VPN|VPN Error): (.+)$");
    private static final Pattern P_VPN_POLICY_FAILED = Pattern.compile("^(?:Không thể cập nhật tường lửa / định tuyến VPN|Failed to update VPN firewall / routing): (.+)$");
    private static final Pattern P_APP_RULE_ALLOW = Pattern.compile("^(?:Đã áp dụng quy tắc: Chỉ cho phép (\\d+) ứng dụng qua Proxy|Applied rule: Allow only (\\d+) apps through Proxy)$");
    private static final Pattern P_APP_RULE_BLOCK = Pattern.compile("^(?:Đã áp dụng quy tắc: Chặn (\\d+) ứng dụng không dùng Proxy|Applied rule: Bypass (\\d+) apps from Proxy)$");
    private static final Pattern P_APP_RULE_FAILED = Pattern.compile("^(?:Không thể cập nhật quy tắc ứng dụng VPN|Failed to update VPN app rules): (.+)$");
    private static final Pattern P_VPN_SWITCHED = Pattern.compile("^(?:Người dùng đã đổi Proxy|Switched active Proxy) -> (.+)$");
    private static final Pattern P_VPN_UPSTREAM_FAIL = Pattern.compile("^Proxy (.+?) (?:gặp lỗi truyền tải|encountered a transfer error) \\((.+)\\)\\.$");

    private static final Pattern P_AUTO_STOP_PROXY = Pattern.compile("^(?:Mất kết nối tới Proxy (.+?) \\((.+?)\\) — đã tự động dừng dịch vụ VPN|Connection to Proxy (.+?) lost \\((.+?)\\) — VPN service automatically stopped)$");
    private static final Pattern P_VPN_TIMER_SET = Pattern.compile("^(?:Hẹn giờ VPN: tự động ngắt sau|VPN Timer active: auto-stop in) (.+)$");
    private static final Pattern P_VPN_TIMER_EXPIRED = Pattern.compile("^(?:Đã hết thời gian hẹn giờ VPN|VPN Timer expired) \\((.+?)\\) — (?:tự động dừng dịch vụ|service automatically stopped)$");

    private static final Pattern P_FW_ON = Pattern.compile("^(?:Đã kích hoạt tường lửa \\((\\d+) quy tắc đang bật\\)|Firewall enabled \\((\\d+) active rules\\))$");
    private static final Pattern P_FW_BLOCKED = Pattern.compile("^(?:Tường lửa đã chặn (.+?) truy cập|Firewall blocked (.+?) from accessing) (.+)$");
    private static final Pattern P_FW_RULE_SAVED = Pattern.compile("^(?:Tường lửa: đã lưu (.+?) \\((\\d+) ứng dụng\\)|Firewall: saved (.+?) \\((\\d+) apps\\))$");
    private static final Pattern P_PRIVATE_ON = Pattern.compile("^(?:Đã bật định tuyến mạng riêng qua Proxy \\((\\d+) phạm vi IP\\)|Private network routing via Proxy enabled \\((\\d+) IP ranges\\))$");

    private static final Pattern P_DURATION_HM = Pattern.compile("^(\\d+) (?:giờ|hr) (\\d+) (?:phút|min)$");
    private static final Pattern P_DURATION_H = Pattern.compile("^(\\d+) (?:giờ|hr)$");
    private static final Pattern P_DURATION_M = Pattern.compile("^(\\d+) (?:phút|min)$");

    private ProxyLogFormatter() {
    }

    @NonNull
    public static String getString(@NonNull Context context, @StringRes int resId, Object... args) {
        Context localized = SettingsPreferences.getLocalizedContext(context);
        return localized.getString(resId, args);
    }

    @NonNull
    public static String formatTimerMinutes(@NonNull Context context, int minutes) {
        Context localized = SettingsPreferences.getLocalizedContext(context);
        if (minutes <= SettingsPreferences.VPN_TIMER_ALWAYS) {
            return localized.getString(R.string.vpn_timer_always);
        }
        int hours = minutes / 60;
        int remMinutes = minutes % 60;
        if (hours > 0 && remMinutes > 0) {
            return localized.getString(R.string.vpn_timer_hours_minutes, hours, remMinutes);
        }
        if (hours > 0) {
            return localized.getString(R.string.vpn_timer_hours, hours);
        }
        return localized.getString(R.string.vpn_timer_minutes, minutes);
    }

    @NonNull
    public static String localizeForDisplay(@NonNull Context context, @Nullable String message) {
        if (message == null || message.isEmpty()) {
            return "";
        }
        Context localized = SettingsPreferences.getLocalizedContext(context);

        if ("Không có Proxy nào trong danh sách để kết nối".equals(message)
                || "No Proxy available in the list to connect".equals(message)) {
            return localized.getString(R.string.log_vpn_no_proxy);
        }
        if ("Đã ngắt kết nối VPN Proxy".equals(message)
                || "Disconnected Proxy VPN".equals(message)) {
            return localized.getString(R.string.log_vpn_disconnected);
        }
        if ("Mất kết nối mạng — đã tự động dừng dịch vụ Proxy VPN".equals(message)
                || "Network connection lost — Proxy VPN service automatically stopped".equals(message)) {
            return localized.getString(R.string.log_auto_stop_network_lost);
        }
        if ("Hẹn giờ VPN đã chuyển sang chế độ Luôn luôn".equals(message)
                || "VPN Timer switched to Always".equals(message)) {
            return localized.getString(R.string.log_vpn_timer_always);
        }
        if ("Đã tắt tường lửa".equals(message)
                || "Firewall disabled".equals(message)) {
            return localized.getString(R.string.log_firewall_toggled_off);
        }
        if ("Đã tắt định tuyến mạng riêng qua Proxy".equals(message)
                || "Private network routing via Proxy disabled".equals(message)) {
            return localized.getString(R.string.log_private_routing_disabled);
        }

        Matcher m;
        if ((m = P_PROXY_UPDATED.matcher(message)).matches()) {
            return localized.getString(R.string.log_proxy_updated, m.group(1));
        }
        if ((m = P_PROXY_ADDED.matcher(message)).matches()) {
            return localized.getString(R.string.log_proxy_added, m.group(1));
        }
        if ((m = P_PROXY_BULK_IMPORTED.matcher(message)).matches()) {
            return localized.getString(R.string.log_proxy_bulk_imported, parseIntSafe(m.group(1)));
        }
        if ((m = P_PROXY_DELETED.matcher(message)).matches()) {
            return localized.getString(R.string.log_proxy_deleted, m.group(1));
        }
        if ((m = P_PROXY_TEST_OK.matcher(message)).matches()) {
            return localized.getString(R.string.log_proxy_test_ok, m.group(1), parseLongSafe(m.group(2)), m.group(3));
        }
        if ((m = P_PROXY_TEST_FAILED.matcher(message)).matches()) {
            return localized.getString(R.string.log_proxy_test_failed, m.group(1), m.group(2));
        }
        if ((m = P_PROXY_BATCH_DONE.matcher(message)).matches()) {
            String total = m.group(1) != null ? m.group(1) : m.group(3);
            String ok = m.group(2) != null ? m.group(2) : m.group(4);
            return localized.getString(R.string.log_proxy_batch_test_done, parseIntSafe(total), parseIntSafe(ok));
        }
        if ((m = P_PROXY_EXPORTED.matcher(message)).matches()) {
            String count = m.group(1) != null ? m.group(1) : m.group(2);
            return localized.getString(R.string.log_proxy_exported_txt, parseIntSafe(count));
        }
        if ((m = P_PROXY_DETAIL_FAILED.matcher(message)).matches()) {
            String proxyAddr = m.group(1) != null ? m.group(1) : m.group(2);
            return localized.getString(R.string.log_proxy_detail_failed, proxyAddr, m.group(3));
        }
        if ((m = P_PROXY_DETAIL_OK.matcher(message)).matches()) {
            double mbps = parseDoubleSafe(m.group(3));
            return localized.getString(R.string.log_proxy_detail_ok, m.group(1), parseLongSafe(m.group(2)), mbps);
        }
        if ((m = P_PROXY_CONNECT_REFUSED.matcher(message)).matches()) {
            return localized.getString(R.string.log_proxy_connect_refused, m.group(1), m.group(2));
        }
        if ((m = P_VPN_CONNECTED.matcher(message)).matches()) {
            return localized.getString(R.string.log_vpn_connected, m.group(1), parseIntSafe(m.group(2)));
        }
        if ((m = P_VPN_ERROR.matcher(message)).matches()) {
            String err = m.group(1);
            if ("Không thể khởi tạo giao diện VPN".equals(err) || "Failed to initialize VPN interface".equals(err)) {
                err = localized.getString(R.string.log_vpn_interface_failed);
            }
            return localized.getString(R.string.log_vpn_error, err);
        }
        if ((m = P_VPN_POLICY_FAILED.matcher(message)).matches()) {
            return localized.getString(R.string.log_vpn_policy_update_failed, m.group(1));
        }
        if ((m = P_APP_RULE_ALLOW.matcher(message)).matches()) {
            String count = m.group(1) != null ? m.group(1) : m.group(2);
            return localized.getString(R.string.log_app_rule_allow_applied, parseIntSafe(count));
        }
        if ((m = P_APP_RULE_BLOCK.matcher(message)).matches()) {
            String count = m.group(1) != null ? m.group(1) : m.group(2);
            return localized.getString(R.string.log_app_rule_block_applied, parseIntSafe(count));
        }
        if ((m = P_APP_RULE_FAILED.matcher(message)).matches()) {
            return localized.getString(R.string.log_app_rule_update_failed, m.group(1));
        }
        if ((m = P_VPN_SWITCHED.matcher(message)).matches()) {
            return localized.getString(R.string.log_vpn_proxy_switched, m.group(1));
        }
        if ((m = P_VPN_UPSTREAM_FAIL.matcher(message)).matches()) {
            return localized.getString(R.string.log_vpn_upstream_failure, m.group(1), m.group(2));
        }
        if ((m = P_AUTO_STOP_PROXY.matcher(message)).matches()) {
            String addr = m.group(1) != null ? m.group(1) : m.group(3);
            String reason = m.group(2) != null ? m.group(2) : m.group(4);
            return localized.getString(R.string.log_auto_stop_proxy_lost, addr, reason);
        }
        if ((m = P_VPN_TIMER_SET.matcher(message)).matches()) {
            return localized.getString(R.string.log_vpn_timer_set, localizeDurationText(localized, m.group(1)));
        }
        if ((m = P_VPN_TIMER_EXPIRED.matcher(message)).matches()) {
            return localized.getString(R.string.log_vpn_timer_expired, localizeDurationText(localized, m.group(1)));
        }
        if ((m = P_FW_ON.matcher(message)).matches()) {
            String count = m.group(1) != null ? m.group(1) : m.group(2);
            return localized.getString(R.string.log_firewall_toggled_on, parseIntSafe(count));
        }
        if ((m = P_FW_BLOCKED.matcher(message)).matches()) {
            String appOrRule = m.group(1) != null ? m.group(1) : m.group(2);
            return localized.getString(R.string.log_firewall_blocked_request, appOrRule, m.group(3));
        }
        if ((m = P_FW_RULE_SAVED.matcher(message)).matches()) {
            String name = m.group(1) != null ? m.group(1) : m.group(3);
            String count = m.group(2) != null ? m.group(2) : m.group(4);
            return localized.getString(R.string.log_firewall_rule_saved, name, parseIntSafe(count));
        }
        if ((m = P_PRIVATE_ON.matcher(message)).matches()) {
            String count = m.group(1) != null ? m.group(1) : m.group(2);
            return localized.getString(R.string.log_private_routing_enabled, parseIntSafe(count));
        }
        return message;
    }

    @NonNull
    private static String localizeDurationText(@NonNull Context localized, @NonNull String rawDuration) {
        if ("Luôn luôn".equals(rawDuration) || "Always".equals(rawDuration)) {
            return localized.getString(R.string.vpn_timer_always);
        }
        Matcher m;
        if ((m = P_DURATION_HM.matcher(rawDuration)).matches()) {
            return localized.getString(R.string.vpn_timer_hours_minutes,
                    parseIntSafe(m.group(1)), parseIntSafe(m.group(2)));
        }
        if ((m = P_DURATION_H.matcher(rawDuration)).matches()) {
            return localized.getString(R.string.vpn_timer_hours, parseIntSafe(m.group(1)));
        }
        if ((m = P_DURATION_M.matcher(rawDuration)).matches()) {
            return localized.getString(R.string.vpn_timer_minutes, parseIntSafe(m.group(1)));
        }
        return rawDuration;
    }

    private static int parseIntSafe(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static long parseLongSafe(String raw) {
        try {
            return Long.parseLong(raw.trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    private static double parseDoubleSafe(String raw) {
        try {
            return Double.parseDouble(raw.trim().replace(',', '.'));
        } catch (Exception e) {
            return 0.0;
        }
    }
}
