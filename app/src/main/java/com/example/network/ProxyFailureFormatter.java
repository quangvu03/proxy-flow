package com.example.network;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.R;
import com.example.model.ProxyConfig;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Formats structured proxy test/diagnostic failures into clear, localized,
 * actionable explanations for the user across Add/Edit, Proxy Detail, and Proxy List screens.
 */
public final class ProxyFailureFormatter {

    private static final Pattern HTTP_CODE_PATTERN = Pattern.compile("(\\d{3})");

    private ProxyFailureFormatter() {}

    @NonNull
    public static String format(@NonNull Context context,
                                @Nullable ProxyTester.FailureReason reason,
                                int httpStatusCode,
                                @Nullable String host,
                                int port,
                                @Nullable ProxyConfig.AuthMethod authMethod,
                                @Nullable String rawMessage) {
        String raw = rawMessage != null ? rawMessage.trim() : "";
        ProxyTester.FailureReason effectiveReason = reason != null
                ? reason
                : ProxyTester.inferFailureReasonFromMessage(raw);
        String displayHost = (host != null && !host.trim().isEmpty()) ? host.trim() : "Proxy";
        ProxyConfig.AuthMethod mode = authMethod != null ? authMethod : ProxyConfig.AuthMethod.NONE;

        switch (effectiveReason) {
            case AUTH_REQUIRED:
                return context.getString(R.string.err_proxy_test_auth_required);
            case AUTH_SCHEME_MISMATCH:
                return context.getString(R.string.err_proxy_test_auth_scheme);
            case AUTH_FAILED:
                if (mode == ProxyConfig.AuthMethod.NONE) {
                    return context.getString(R.string.err_proxy_test_auth_required);
                }
                return context.getString(R.string.err_proxy_test_auth_failed);
            case TIMEOUT:
                if (port > 0) {
                    return context.getString(R.string.err_proxy_test_timeout, displayHost, port);
                }
                return !raw.isEmpty() ? raw : context.getString(R.string.latency_failed);
            case CONNECTION_REFUSED:
                if (port > 0) {
                    return context.getString(R.string.err_proxy_test_refused, displayHost, port);
                }
                return !raw.isEmpty() ? raw : context.getString(R.string.latency_failed);
            case HOST_UNREACHABLE:
                return context.getString(R.string.err_proxy_test_host_unreachable, displayHost);
            case TLS_FAILED:
                if (port > 0) {
                    return context.getString(R.string.err_proxy_test_tls_failed, displayHost, port);
                }
                return !raw.isEmpty() ? raw : context.getString(R.string.err_proxy_test_protocol);
            case HTTP_STATUS_ERROR:
                int code = httpStatusCode > 0 ? httpStatusCode : extractHttpStatusCode(raw);
                if (code > 0) {
                    return context.getString(R.string.err_proxy_test_http_status, code);
                }
                return !raw.isEmpty() ? raw : context.getString(R.string.err_proxy_test_protocol);
            case PROTOCOL_OR_RESPONSE_ERROR:
                return context.getString(R.string.err_proxy_test_protocol);
            default:
                return !raw.isEmpty() ? raw : context.getString(R.string.latency_failed);
        }
    }

    public static int extractHttpStatusCode(@Nullable String text) {
        if (text == null || text.isEmpty()) return -1;
        Matcher m = HTTP_CODE_PATTERN.matcher(text);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }
}
