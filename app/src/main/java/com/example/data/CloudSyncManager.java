package com.example.data;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.example.BuildConfig;
import com.example.R;
import com.example.auth.GoogleAuthManager;
import com.example.model.ProxyConfig;
import com.example.model.ProxyLogEntry;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Coordinates cross-device cloud sync of proxies, app settings, and user telemetry
 * against the provisioned named Firestore database.
 */
public final class CloudSyncManager {

    private static final String TAG = "CloudSyncManager";
    private static final String KEY_LAST_SYNC_PREFIX = "last_cloud_sync_at_";
    private static final ExecutorService SYNC_EXECUTOR = Executors.newSingleThreadExecutor();
    private static volatile Handler mainHandler;

    private static void postToMain(@NonNull Runnable runnable) {
        if (mainHandler == null) {
            mainHandler = new Handler(Looper.getMainLooper());
        }
        mainHandler.post(runnable);
    }

    public static final class SyncResult {
        private final int uploadedCount;
        private final int downloadedCount;
        private final int totalProxies;
        private final long syncedAtMs;

        public SyncResult(int uploadedCount, int downloadedCount, int totalProxies, long syncedAtMs) {
            this.uploadedCount = uploadedCount;
            this.downloadedCount = downloadedCount;
            this.totalProxies = totalProxies;
            this.syncedAtMs = syncedAtMs;
        }

        public int getUploadedCount() {
            return uploadedCount;
        }

        public int getDownloadedCount() {
            return downloadedCount;
        }

        public int getTotalProxies() {
            return totalProxies;
        }

        public long getSyncedAtMs() {
            return syncedAtMs;
        }
    }

    public interface SyncCallback {
        void onSuccess(@NonNull SyncResult result);

        void onError(@NonNull String message);
    }

    private CloudSyncManager() {
    }

    @Nullable
    public static FirebaseFirestore getFirestore(@NonNull Context context) {
        try {
            Context appCtx = context.getApplicationContext();
            if (GoogleAuthManager.getFirebaseAuth(appCtx) == null) {
                return null;
            }
            String databaseId = appCtx.getString(R.string.firestore_database_id);
            if (TextUtils.isEmpty(databaseId)) {
                return null;
            }
            return FirebaseFirestore.getInstance(databaseId.trim());
        } catch (Exception e) {
            Log.w(TAG, "FirebaseFirestore unavailable: " + e.getMessage());
            return null;
        }
    }

    public static long getLastSyncTimestampMs(@NonNull Context context, @Nullable String uid) {
        if (TextUtils.isEmpty(uid)) {
            return 0L;
        }
        String raw = ProxyDatabaseHelper.getInstance(context.getApplicationContext())
                .getSetting(KEY_LAST_SYNC_PREFIX + uid.trim(), "0");
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * Silently updates /users/{uid} summary metadata when a user signs in or opens the app.
     */
    public static void recordUserPresenceAsync(@NonNull Context context) {
        Context appCtx = context.getApplicationContext();
        FirebaseUser user = GoogleAuthManager.getCurrentUser(appCtx);
        if (user == null || TextUtils.isEmpty(user.getUid())) {
            return;
        }
        FirebaseFirestore db = getFirestore(appCtx);
        if (db == null) {
            return;
        }
        SYNC_EXECUTOR.execute(() -> {
            String uid = user.getUid();
            String userDocPath = "users/" + uid;
            try {
                DocumentReference userRef = db.collection("users").document(uid);
                DocumentSnapshot existingDoc = Tasks.await(userRef.get());
                List<ProxyConfig> localProxies = ProxyDatabaseHelper.getInstance(appCtx).getAllProxies();
                SettingsPreferences prefs = new SettingsPreferences(appCtx);

                int existingSyncCount = 0;
                if (existingDoc.exists()) {
                    Object rawCount = existingDoc.get("syncCount");
                    if (rawCount instanceof Number) {
                        existingSyncCount = Math.max(0, ((Number) rawCount).intValue());
                    }
                }

                Map<String, Object> summary = buildUserSummaryMap(
                        appCtx,
                        user,
                        localProxies,
                        prefs,
                        existingSyncCount,
                        !existingDoc.exists()
                );

                if (existingDoc.exists()) {
                    Tasks.await(userRef.update(summary));
                } else {
                    Tasks.await(userRef.set(summary));
                }
            } catch (Exception e) {
                FirestoreErrorHelper.handleFirestoreError(
                        e,
                        FirestoreErrorHelper.OperationType.WRITE,
                        userDocPath
                );
            }
        });
    }

    /**
     * Performs full bidirectional sync between local SQLite/Settings and Firestore:
     * 1. Uploads missing/updated local proxies to users/{uid}/proxies
     * 2. Downloads proxies from other devices into local SQLite
     * 3. Syncs app preferences in users/{uid}/sync_state/preferences
     * 4. Updates users/{uid} summary and writes an immutable sync_events telemetry record
     */
    public static void syncAcrossDevices(@NonNull Context context, @NonNull SyncCallback callback) {
        Context appCtx = context.getApplicationContext();
        FirebaseUser user = GoogleAuthManager.getCurrentUser(appCtx);
        if (user == null || TextUtils.isEmpty(user.getUid())) {
            postToMain(() -> callback.onError(appCtx.getString(R.string.msg_sync_requires_login)));
            return;
        }

        FirebaseFirestore db = getFirestore(appCtx);
        if (db == null) {
            postToMain(() -> callback.onError(appCtx.getString(R.string.msg_firestore_unavailable)));
            return;
        }

        SYNC_EXECUTOR.execute(() -> {
            String uid = user.getUid();
            String currentPath = "users/" + uid;
            FirestoreErrorHelper.OperationType currentOp = FirestoreErrorHelper.OperationType.WRITE;

            try {
                ProxyDatabaseHelper dbHelper = ProxyDatabaseHelper.getInstance(appCtx);
                SettingsPreferences prefs = new SettingsPreferences(appCtx);
                List<ProxyConfig> localProxies = dbHelper.getAllProxies();

                // 1. Fetch existing cloud proxies
                currentPath = "users/" + uid + "/proxies";
                currentOp = FirestoreErrorHelper.OperationType.LIST;
                QuerySnapshot cloudSnapshot = Tasks.await(
                        db.collection("users").document(uid).collection("proxies").get()
                );

                Map<String, DocumentSnapshot> cloudByKey = new HashMap<>();
                for (DocumentSnapshot doc : cloudSnapshot.getDocuments()) {
                    cloudByKey.put(doc.getId(), doc);
                }

                // 2. Upload local proxies to cloud (create or update)
                Set<String> localKeys = new HashSet<>();
                int uploadedCount = 0;
                currentOp = FirestoreErrorHelper.OperationType.WRITE;

                for (int i = 0; i < localProxies.size(); i++) {
                    ProxyConfig local = localProxies.get(i);
                    if (local.getHost().isEmpty() || local.getPort() < 1 || local.getPort() > 65535) {
                        continue;
                    }
                    String key = buildProxyDocKey(local);
                    localKeys.add(key);
                    currentPath = "users/" + uid + "/proxies/" + key;
                    DocumentReference docRef = db.collection("users")
                            .document(uid)
                            .collection("proxies")
                            .document(key);

                    boolean existsOnCloud = cloudByKey.containsKey(key);
                    Map<String, Object> payload = buildCloudProxyMap(local, !existsOnCloud);
                    if (existsOnCloud) {
                        Tasks.await(docRef.update(payload));
                    } else {
                        Tasks.await(docRef.set(payload));
                    }
                    uploadedCount++;
                }

                // 3. Download cloud proxies missing on this device
                int downloadedCount = 0;
                for (DocumentSnapshot cloudDoc : cloudSnapshot.getDocuments()) {
                    if (localKeys.contains(cloudDoc.getId())) {
                        continue;
                    }
                    ProxyConfig fromCloud = parseCloudProxy(cloudDoc);
                    if (fromCloud != null) {
                        dbHelper.insertProxy(fromCloud);
                        downloadedCount++;
                    }
                }

                // 4. Sync app preferences
                currentPath = "users/" + uid + "/sync_state/preferences";
                DocumentReference prefsRef = db.collection("users")
                        .document(uid)
                        .collection("sync_state")
                        .document("preferences");
                currentOp = FirestoreErrorHelper.OperationType.GET;
                DocumentSnapshot cloudPrefsDoc = Tasks.await(prefsRef.get());

                if (cloudPrefsDoc.exists() && localProxies.isEmpty()) {
                    applyCloudPreferences(prefs, cloudPrefsDoc);
                }

                currentOp = FirestoreErrorHelper.OperationType.WRITE;
                Map<String, Object> prefsMap = buildSyncPreferencesMap(prefs, !cloudPrefsDoc.exists());
                if (cloudPrefsDoc.exists()) {
                    Tasks.await(prefsRef.update(prefsMap));
                } else {
                    Tasks.await(prefsRef.set(prefsMap));
                }

                // 5. Refresh local proxy repository if any cloud proxies were downloaded
                List<ProxyConfig> mergedProxies = dbHelper.getAllProxies();
                if (downloadedCount > 0) {
                    ProxyRepository.getInstance(appCtx).reloadAll();
                    ProxyRepository.getInstance(appCtx).addLog(
                            ProxyLogEntry.Level.SUCCESS,
                            ProxyLogFormatter.getString(appCtx, R.string.log_cloud_sync_done, uploadedCount, downloadedCount)
                    );
                }

                // 6. Update /users/{uid} summary
                currentPath = "users/" + uid;
                currentOp = FirestoreErrorHelper.OperationType.GET;
                DocumentReference userRef = db.collection("users").document(uid);
                DocumentSnapshot userDoc = Tasks.await(userRef.get());

                int nextSyncCount = 1;
                if (userDoc.exists()) {
                    Object rawCount = userDoc.get("syncCount");
                    if (rawCount instanceof Number) {
                        nextSyncCount = Math.min(1_000_000, Math.max(0, ((Number) rawCount).intValue()) + 1);
                    }
                }

                currentOp = FirestoreErrorHelper.OperationType.WRITE;
                Map<String, Object> summaryMap = buildUserSummaryMap(
                        appCtx,
                        user,
                        mergedProxies,
                        prefs,
                        nextSyncCount,
                        !userDoc.exists()
                );
                if (userDoc.exists()) {
                    Tasks.await(userRef.update(summaryMap));
                } else {
                    Tasks.await(userRef.set(summaryMap));
                }

                // 7. Write immutable sync event for Admin statistics
                String eventId = buildSyncEventId(uid);
                currentPath = "sync_events/" + eventId;
                currentOp = FirestoreErrorHelper.OperationType.CREATE;
                Map<String, Object> eventMap = buildSyncEventMap(
                        user,
                        uploadedCount,
                        downloadedCount,
                        mergedProxies.size()
                );
                Tasks.await(db.collection("sync_events").document(eventId).set(eventMap));

                long syncedAtMs = System.currentTimeMillis();
                dbHelper.putSetting(KEY_LAST_SYNC_PREFIX + uid, String.valueOf(syncedAtMs));

                SyncResult result = new SyncResult(
                        uploadedCount,
                        downloadedCount,
                        mergedProxies.size(),
                        syncedAtMs
                );
                postToMain(() -> callback.onSuccess(result));
            } catch (Exception e) {
                FirestoreErrorHelper.handleFirestoreError(e, currentOp, currentPath);
                String friendly = FirestoreErrorHelper.getFriendlyErrorMessage(appCtx, e);
                postToMain(() -> callback.onError(friendly));
            }
        });
    }

    @VisibleForTesting
    @NonNull
    public static String buildProxyDocKey(@NonNull ProxyConfig proxy) {
        String signature = proxy.getProtocol().name()
                + "|" + proxy.getHost().toLowerCase(Locale.ROOT)
                + "|" + proxy.getPort()
                + "|" + proxy.getAuthMethod().name()
                + "|" + proxy.getUsername();

        String rawPrefix = (proxy.getProtocol().name() + "_" + proxy.getHost() + "_" + proxy.getPort())
                .replaceAll("[^a-zA-Z0-9_\\-]", "_");
        if (rawPrefix.length() > 72) {
            rawPrefix = rawPrefix.substring(0, 72);
        }
        String hash = shortSha256(signature);
        String key = rawPrefix + "_" + hash;
        return key.isEmpty() ? "proxy_" + hash : key;
    }

    private static String buildSyncEventId(@NonNull String uid) {
        String uidPrefix = uid.replaceAll("[^a-zA-Z0-9_\\-]", "");
        if (uidPrefix.length() > 16) {
            uidPrefix = uidPrefix.substring(0, 16);
        }
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return "sync_" + uidPrefix + "_" + System.currentTimeMillis() + "_" + suffix;
    }

    private static Map<String, Object> buildUserSummaryMap(
            @NonNull Context context,
            @NonNull FirebaseUser user,
            @NonNull List<ProxyConfig> proxies,
            @NonNull SettingsPreferences prefs,
            int syncCount,
            boolean isCreate) {
        int totalProxies = Math.min(5000, proxies.size());
        int workingProxies = 0;
        for (ProxyConfig p : proxies) {
            if (p.getLatencyMs() > 0) {
                workingProxies++;
            }
        }
        workingProxies = Math.min(5000, workingProxies);

        Map<String, Object> map = new HashMap<>();
        map.put("displayName", truncate(GoogleAuthManager.getDisplayLabel(user, "TopProxy User"), 100));
        map.put("email", truncate(user.getEmail() != null ? user.getEmail() : "", 160));
        map.put("proxyCount", totalProxies);
        map.put("workingProxyCount", workingProxies);
        map.put("firewallEnabled", prefs.isFirewallEnabled());
        map.put("privateRoutingEnabled", prefs.isPrivateNetworkRoutingEnabled());
        map.put("themeMode", prefs.getThemeMode().name());
        map.put("languageTag", truncate(prefs.getAppLanguage().getTag(), 20));
        map.put("lastDeviceModel", truncate(getDeviceLabel(), 120));
        map.put("appVersion", truncate(BuildConfig.VERSION_NAME, 30));
        map.put("syncCount", Math.min(1_000_000, Math.max(0, syncCount)));
        map.put("updatedAt", FieldValue.serverTimestamp());
        map.put("lastSyncAt", FieldValue.serverTimestamp());
        if (isCreate) {
            map.put("userId", user.getUid());
            map.put("createdAt", FieldValue.serverTimestamp());
        }
        return map;
    }

    private static Map<String, Object> buildCloudProxyMap(@NonNull ProxyConfig proxy, boolean isCreate) {
        Map<String, Object> map = new HashMap<>();
        map.put("name", truncate(proxy.getName(), 120));
        map.put("protocol", proxy.getProtocol().name());
        map.put("host", truncate(proxy.getHost(), 255));
        map.put("port", Math.max(1, Math.min(65535, proxy.getPort())));
        map.put("authMethod", proxy.getAuthMethod().name());
        map.put("username", truncate(proxy.getUsername(), 160));
        map.put("password", truncate(proxy.getPassword(), 256));
        map.put("dnsMode", proxy.getDnsMode().name());
        map.put("customDns", truncate(proxy.getCustomDns(), 256));
        map.put("enabled", proxy.isEnabled());
        map.put("latencyMs", Math.max(-2L, Math.min(600_000L, proxy.getLatencyMs())));
        map.put("exitIp", truncate(proxy.getExitIp(), 64));
        map.put("country", truncate(proxy.getCountry(), 64));
        map.put("sortOrder", Math.max(0, Math.min(100_000, proxy.getSortOrder())));
        map.put("updatedAt", FieldValue.serverTimestamp());
        if (isCreate) {
            map.put("createdAt", FieldValue.serverTimestamp());
        }
        return map;
    }

    private static Map<String, Object> buildSyncPreferencesMap(@NonNull SettingsPreferences prefs, boolean isCreate) {
        Map<String, Object> map = new HashMap<>();
        map.put("themeMode", prefs.getThemeMode().name());
        map.put("languageTag", truncate(prefs.getAppLanguage().getTag(), 20));
        map.put("autoStopOnDisconnect", prefs.isAutoStopOnDisconnectEnabled());
        map.put("vpnTimerMinutes", Math.max(0, Math.min(1440, prefs.getVpnTimerMinutes())));
        map.put("firewallEnabled", prefs.isFirewallEnabled());
        map.put("privateRoutingEnabled", prefs.isPrivateNetworkRoutingEnabled());
        map.put("privateRoutingRanges", truncate(prefs.getPrivateNetworkRangesText(), 2000));
        map.put("updatedAt", FieldValue.serverTimestamp());
        if (isCreate) {
            map.put("createdAt", FieldValue.serverTimestamp());
        }
        return map;
    }

    private static Map<String, Object> buildSyncEventMap(
            @NonNull FirebaseUser user,
            int uploaded,
            int downloaded,
            int totalProxies) {
        Map<String, Object> map = new HashMap<>();
        map.put("userId", user.getUid());
        map.put("userLabel", truncate(GoogleAuthManager.getDisplayLabel(user, "User"), 120));
        map.put("deviceModel", truncate(getDeviceLabel(), 120));
        map.put("uploadedProxies", Math.max(0, Math.min(5000, uploaded)));
        map.put("downloadedProxies", Math.max(0, Math.min(5000, downloaded)));
        map.put("totalProxies", Math.max(0, Math.min(5000, totalProxies)));
        map.put("appVersion", truncate(BuildConfig.VERSION_NAME, 30));
        map.put("createdAt", FieldValue.serverTimestamp());
        return map;
    }

    @Nullable
    private static ProxyConfig parseCloudProxy(@NonNull DocumentSnapshot doc) {
        String host = doc.getString("host");
        Object rawPort = doc.get("port");
        int port = rawPort instanceof Number ? ((Number) rawPort).intValue() : -1;
        if (host == null || host.trim().isEmpty() || port < 1 || port > 65535) {
            return null;
        }
        ProxyConfig p = new ProxyConfig();
        p.setName(nonNull(doc.getString("name")));
        p.setProtocol(ProxyConfig.Protocol.fromString(doc.getString("protocol")));
        p.setHost(host.trim());
        p.setPort(port);
        p.setAuthMethod(ProxyConfig.AuthMethod.fromString(doc.getString("authMethod")));
        p.setUsername(nonNull(doc.getString("username")));
        p.setPassword(nonNull(doc.getString("password")));
        p.setDnsMode(ProxyConfig.DnsMode.fromString(doc.getString("dnsMode")));
        p.setCustomDns(nonNull(doc.getString("customDns")));

        Boolean enabled = doc.getBoolean("enabled");
        p.setEnabled(enabled == null || enabled);

        Object rawLatency = doc.get("latencyMs");
        p.setLatencyMs(rawLatency instanceof Number ? ((Number) rawLatency).longValue() : ProxyConfig.LATENCY_UNTESTED);

        p.setExitIp(nonNull(doc.getString("exitIp")));
        p.setCountry(nonNull(doc.getString("country")));

        Object rawSort = doc.get("sortOrder");
        p.setSortOrder(rawSort instanceof Number ? ((Number) rawSort).intValue() : 0);
        return p;
    }

    private static void applyCloudPreferences(@NonNull SettingsPreferences prefs, @NonNull DocumentSnapshot doc) {
        String theme = doc.getString("themeMode");
        if (!TextUtils.isEmpty(theme)) {
            prefs.setThemeMode(SettingsPreferences.ThemeMode.fromStoredValue(theme));
        }
        String lang = doc.getString("languageTag");
        if (!TextUtils.isEmpty(lang)) {
            prefs.setAppLanguage(SettingsPreferences.AppLanguage.fromTag(lang));
        }
        Boolean autoStop = doc.getBoolean("autoStopOnDisconnect");
        if (autoStop != null) {
            prefs.setAutoStopOnDisconnectEnabled(autoStop);
        }
        Object rawTimer = doc.get("vpnTimerMinutes");
        if (rawTimer instanceof Number) {
            prefs.setVpnTimerMinutes(((Number) rawTimer).intValue());
        }
        Boolean firewall = doc.getBoolean("firewallEnabled");
        if (firewall != null) {
            prefs.setFirewallEnabled(firewall);
        }
        Boolean privateRouting = doc.getBoolean("privateRoutingEnabled");
        if (privateRouting != null) {
            prefs.setPrivateNetworkRoutingEnabled(privateRouting);
        }
        String ranges = doc.getString("privateRoutingRanges");
        if (ranges != null) {
            prefs.setPrivateNetworkRangesText(ranges);
        }
    }

    @NonNull
    private static String getDeviceLabel() {
        String mfg = Build.MANUFACTURER != null ? Build.MANUFACTURER.trim() : "";
        String model = Build.MODEL != null ? Build.MODEL.trim() : "Android";
        if (mfg.isEmpty() || model.toLowerCase(Locale.ROOT).startsWith(mfg.toLowerCase(Locale.ROOT))) {
            return model;
        }
        return mfg + " " + model;
    }

    @NonNull
    private static String shortSha256(@NonNull String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8 && i < digest.length; i++) {
                sb.append(String.format(Locale.ROOT, "%02x", digest[i] & 0xFF));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    @NonNull
    private static String truncate(@Nullable String val, int maxLen) {
        if (val == null) {
            return "";
        }
        String trimmed = val.trim();
        return trimmed.length() <= maxLen ? trimmed : trimmed.substring(0, maxLen);
    }

    @NonNull
    private static String nonNull(@Nullable String val) {
        return val != null ? val : "";
    }
}
