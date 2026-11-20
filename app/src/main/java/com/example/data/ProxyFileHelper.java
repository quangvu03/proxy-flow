package com.example.data;

import com.example.model.ProxyConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Pure Java helper for importing and exporting proxy lists as UTF-8 .txt files
 * via Android Storage Access Framework (zero storage permissions required).
 */
public final class ProxyFileHelper {

    public static final int MAX_IMPORT_BYTES = 2 * 1024 * 1024; // 2 MB safety cap

    private ProxyFileHelper() {}

    /**
     * Reads UTF-8 text from an InputStream, stripping any UTF-8 BOM and enforcing a safe size limit.
     */
    public static String readTextFromStream(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int totalRead = 0;
        int read;
        while ((read = inputStream.read(chunk)) != -1) {
            int remaining = MAX_IMPORT_BYTES - totalRead;
            if (remaining <= 0) {
                break;
            }
            int toWrite = Math.min(read, remaining);
            buffer.write(chunk, 0, toWrite);
            totalRead += toWrite;
        }
        String text = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        return stripUtf8Bom(text);
    }

    /**
     * Writes UTF-8 text cleanly to an OutputStream.
     */
    public static void writeTextToStream(OutputStream outputStream, String content) throws IOException {
        if (outputStream == null) {
            throw new IOException("OutputStream is null");
        }
        String safeContent = content != null ? content : "";
        outputStream.write(safeContent.getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
    }

    /**
     * Formats a list of ProxyConfig items as one proxy per line (host:port[:user:pass]).
     */
    public static String formatExportContent(List<ProxyConfig> proxies) {
        if (proxies == null || proxies.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < proxies.size(); i++) {
            ProxyConfig proxy = proxies.get(i);
            if (proxy == null) continue;
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(proxy.toExportLine());
        }
        return sb.toString();
    }

    /**
     * Counts how many lines in raw text parse into valid ProxyConfig objects.
     */
    public static int countValidProxyLines(String rawText) {
        if (rawText == null || rawText.trim().isEmpty()) {
            return 0;
        }
        int valid = 0;
        String cleaned = stripUtf8Bom(rawText);
        for (String line : cleaned.split("\\r?\\n")) {
            if (ProxyConfig.parseFromLine(line) != null) {
                valid++;
            }
        }
        return valid;
    }

    /**
     * Builds a clean timestamped default filename for exporting proxies as .txt.
     */
    public static String buildDefaultExportFileName() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US);
        return "proxyflow_proxies_" + sdf.format(new Date()) + ".txt";
    }

    public static String stripUtf8Bom(String text) {
        if (text != null && !text.isEmpty() && text.charAt(0) == '\uFEFF') {
            return text.substring(1);
        }
        return text != null ? text : "";
    }
}
