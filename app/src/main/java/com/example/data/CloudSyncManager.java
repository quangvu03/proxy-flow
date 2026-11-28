package com.example.data;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
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
import com.google.android.gms.security.ProviderInstaller;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.Source;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Coordinates cross-device cloud sync of proxies, app settings, and user telemetry
 * against the provisioned named Firestore database. Uses the Firestore SDK first and
 * automatically falls back to HTTPS REST v1 when gRPC/HTTP2 is unavailable.
 */
public final class CloudSyncManager {

    private static final String TAG = "CloudSyncManager";
    private static final String KEY_LAST_SYNC_PREFIX = "last_cloud_sync_at_";
    private static final long TASK_TIMEOUT_SECONDS = 10L;
    private static final int MAX_TRANSIENT_ATTEMPTS = 1;
    private static final ExecutorService SYNC_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final ExecutorService PRESENCE_EXECUTOR = Executors.newSingleThreadExecutor();
    private static volatile Handler mainHandler;
    private static volatile String lastFirestoreInitError = "";

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
            Context appCtx = context.getApplicationContext() != null
                    ? context.getApplicationContext()
                    : context;
            FirebaseApp app = GoogleAuthManager.ensureFirebaseApp(appCtx);
            if (app == null) {
                lastFirestoreInitError = "FirebaseApp not initialized";
                return null;
            }
            String databaseId = appCtx.getString(R.string.firestore_database_id);
            if (TextUtils.isEmpty(databaseId)) {
                lastFirestoreInitError = "Missing firestore_database_id";
                return null;
            }
            lastFirestoreInitError = "";
            return FirebaseFirestore.getInstance(app, databaseId.trim());
        } catch (Throwable e) {
            lastFirestoreInitError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            Log.w(TAG, "FirebaseFirestore unavailable: " + lastFirestoreInitError, e);
            return null;
        }
    }

    @NonNull
    public static String getLastFirestoreInitError() {
        return lastFirestoreInitError;
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
        Context appCtx = context.getApplicationContext() != null
                ? context.getApplicationContext()
                : context;
        FirebaseUser user = GoogleAuthManager.getCurrentUser(appCtx);
        if (user == null || TextUtils.isEmpty(user.getUid())) {
            return;
        }
        if (!hasActiveNetworkConnection(appCtx)) {
            return;
        }
        PRESENCE_EXECUTOR.execute(() -> {
            String uid = user.getUid();
            String userDocPath = "users/" + uid;
            FirebaseFirestore db = getFirestore(appCtx);
            if (db != null) {
                try {
                    prepareFirestoreSession(appCtx, user, db);
                    DocumentReference userRef = db.collection("users").document(uid);
                    DocumentSnapshot existingDoc = awaitTask(userRef.get(Source.SERVER), TASK_TIMEOUT_SECONDS);
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
                        awaitTask(userRef.update(summary), TASK_TIMEOUT_SECONDS);
                    } else {
                        awaitTask(userRef.set(summary), TASK_TIMEOUT_SECONDS);
                    }
                    return;
                } catch (Exception e) {
                    if (!FirestoreErrorHelper.isTransientRetryable(e)) {
                        FirestoreErrorHelper.handleFirestoreError(
                                e,
                                FirestoreErrorHelper.OperationType.WRITE,
                                userDocPath
                        );
                        return;
                    }
                    Log.w(TAG, "Presence SDK failed transiently, falling back to REST", e);
                }
            }
            try {
                recordUserPresenceViaRest(appCtx, user);
            } catch (Exception restErr) {
                FirestoreErrorHelper.handleFirestoreError(
                        restErr,
                        FirestoreErrorHelper.OperationType.WRITE,
                        userDocPath
                );
            }
        });
    }

    private static void recordUserPresenceViaRest(
            @NonNull Context appCtx,
            @NonNull FirebaseUser user) throws Exception {
        String uid = user.getUid();
        String userDocPath = "users/" + uid;
        FirestoreRestClient.RestDocument existingDoc = FirestoreRestClient.getDocument(appCtx, user, userDocPath);
        List<ProxyConfig> localProxies = ProxyDatabaseHelper.getInstance(appCtx).getAllProxies();
        SettingsPreferences prefs = new SettingsPreferences(appCtx);

        int existingSyncCount = existingDoc.exists() ? existingDoc.getInt("syncCount") : 0;
        boolean isCreate = !existingDoc.exists();
        Map<String, Object> summary = buildUserSummaryMap(
                appCtx,
                user,
                localProxies,
                prefs,
                existingSyncCount,
                isCreate
        );
        List<String> tsFields = isCreate
                ? Arrays.asList("createdAt", "updatedAt", "lastSyncAt")
                : Arrays.asList("updatedAt", "lastSyncAt");
        FirestoreRestClient.commitDocument(
                appCtx,
                user,
                userDocPath,
                isCreate ? Boolean.FALSE : Boolean.TRUE,
                summary,
                tsFields
        );
    }

    /**
     * Performs full bidirectional sync between local SQLite/Settings and Firestore:
     * 1. Uploads missing/updated local proxies to users/{uid}/proxies
     * 2. Downloads proxies from other devices into local SQLite
     * 3. Syncs app preferences in users/{uid}/sync_state/preferences
     * 4. Updates users/{uid} summary and writes an immutable sync_events telemetry record
     */
    public static void syncAcrossDevices(@NonNull Context context, @NonNull SyncCallback callback) {
        Context appCtx = context.getApplicationContext() != null
                ? context.getApplicationContext()
                : context;
        FirebaseUser user = GoogleAuthManager.getCurrentUser(appCtx);
        if (user == null || TextUtils.isEmpty(user.getUid())) {
            postToMain(() -> callback.onError(appCtx.getString(R.string.msg_sync_requires_login)));
            return;
        }

        if (!hasActiveNetworkConnection(appCtx)) {
            postToMain(() -> callback.onError(appCtx.getString(R.string.msg_sync_no_network)));
            return;
        }

        SYNC_EXECUTOR.execute(() -> {
            String uid = user.getUid();
            String currentPath = "users/" + uid;
            FirestoreErrorHelper.OperationType currentOp = FirestoreErrorHelper.OperationType.WRITE;

            FirebaseFirestore db = getFirestore(appCtx);
            if (db != null) {
                try {
                    prepareFirestoreSession(appCtx, user, db);

                    ProxyDatabaseHelper dbHelper = ProxyDatabaseHelper.getInstance(appCtx);
                    SettingsPreferences prefs = new SettingsPreferences(appCtx);
                    List<ProxyConfig> localProxies = dbHelper.getAllProxies();

                    // 1. Fetch existing cloud proxies
                    currentPath = "users/" + uid + "/proxies";
                    currentOp = FirestoreErrorHelper.OperationType.LIST;
                    final Query proxiesQuery = db.collection("users").document(uid).collection("proxies");
                    QuerySnapshot cloudSnapshot = executeReadWithRetry(db, proxiesQuery::get);

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
                        final DocumentReference docRef = db.collection("users")
                                .document(uid)
                                .collection("proxies")
                                .document(key);

                        final boolean existsOnCloud = cloudByKey.containsKey(key);
                        final Map<String, Object> payload = buildCloudProxyMap(local, !existsOnCloud);
                        executeWithRetry(db, () -> existsOnCloud ? docRef.update(payload) : docRef.set(payload));
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
                    final DocumentReference prefsRef = db.collection("users")
                            .document(uid)
                            .collection("sync_state")
                            .document("preferences");
                    currentOp = FirestoreErrorHelper.OperationType.GET;
                    DocumentSnapshot cloudPrefsDoc = executeDocReadWithRetry(db, prefsRef);

                    if (cloudPrefsDoc.exists() && localProxies.isEmpty()) {
                        applyCloudPreferences(prefs, cloudPrefsDoc);
                    }

                    currentOp = FirestoreErrorHelper.OperationType.WRITE;
                    final boolean prefsExist = cloudPrefsDoc.exists();
                    final Map<String, Object> prefsMap = buildSyncPreferencesMap(prefs, !prefsExist);
                    executeWithRetry(db, () -> prefsExist ? prefsRef.update(prefsMap) : prefsRef.set(prefsMap));

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
                    final DocumentReference userRef = db.collection("users").document(uid);
                    DocumentSnapshot userDoc = executeDocReadWithRetry(db, userRef);

                    int nextSyncCount = 1;
                    if (userDoc.exists()) {
                        Object rawCount = userDoc.get("syncCount");
                        if (rawCount instanceof Number) {
                            nextSyncCount = Math.min(1_000_000, Math.max(0, ((Number) rawCount).intValue()) + 1);
                        }
                    }

                    currentOp = FirestoreErrorHelper.OperationType.WRITE;
                    final boolean userExists = userDoc.exists();
                    final Map<String, Object> summaryMap = buildUserSummaryMap(
                            appCtx,
                            user,
                            mergedProxies,
                            prefs,
                            nextSyncCount,
                            !userExists
                    );
                    executeWithRetry(db, () -> userExists ? userRef.update(summaryMap) : userRef.set(summaryMap));

                    // 7. Write immutable sync event for Admin statistics (non-fatal if custom rules omit sync_events)
                    String eventId = buildSyncEventId(uid);
                    currentPath = "sync_events/" + eventId;
                    currentOp = FirestoreErrorHelper.OperationType.CREATE;
                    final Map<String, Object> eventMap = buildSyncEventMap(
                            user,
                            uploadedCount,
                            downloadedCount,
                            mergedProxies.size()
                    );
                    try {
                        final DocumentReference eventRef = db.collection("sync_events").document(eventId);
                        executeWithRetry(db, () -> eventRef.set(eventMap));
                    } catch (Exception telemetryEx) {
                        Throwable root = FirestoreErrorHelper.unwrapRootCause(telemetryEx);
                        if (root instanceof FirebaseFirestoreException
                                && ((FirebaseFirestoreException) root).getCode() == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                            FirestoreErrorHelper.handleFirestoreError(telemetryEx, currentOp, currentPath);
                        } else {
                            throw telemetryEx;
                        }
                    }

                    long syncedAtMs = System.currentTimeMillis();
                    dbHelper.putSetting(KEY_LAST_SYNC_PREFIX + uid, String.valueOf(syncedAtMs));

                    SyncResult result = new SyncResult(
                            uploadedCount,
                            downloadedCount,
                            mergedProxies.size(),
                            syncedAtMs
                    );
                    postToMain(() -> callback.onSuccess(result));
                    return;
                } catch (Exception e) {
                    if (!FirestoreErrorHelper.isTransientRetryable(e)) {
                        FirestoreErrorHelper.handleFirestoreError(e, currentOp, currentPath);
                        String friendly = FirestoreErrorHelper.getFriendlyErrorMessage(appCtx, e);
                        postToMain(() -> callback.onError(friendly));
                        return;
                    }
                    Log.w(TAG, "Firestore SDK transient failure on " + currentPath + ", switching to HTTPS REST fallback", e);
                }
            } else {
                Log.w(TAG, "Firestore SDK null (" + lastFirestoreInitError + "), using HTTPS REST fallback");
            }

            syncAcrossDevicesViaRest(appCtx, user, callback);
        });
    }

    private static void syncAcrossDevicesViaRest(
            @NonNull Context appCtx,
            @NonNull FirebaseUser user,
            @NonNull SyncCallback callback) {
        String uid = user.getUid();
        String currentPath = "users/" + uid;
        FirestoreErrorHelper.OperationType currentOp = FirestoreErrorHelper.OperationType.WRITE;

        try {
            try {
                ProviderInstaller.installIfNeeded(appCtx);
            } catch (Throwable ignored) {
            }

            ProxyDatabaseHelper dbHelper = ProxyDatabaseHelper.getInstance(appCtx);
            SettingsPreferences prefs = new SettingsPreferences(appCtx);
            List<ProxyConfig> localProxies = dbHelper.getAllProxies();

            // 1. Fetch existing cloud proxies via REST
            currentPath = "users/" + uid + "/proxies";
            currentOp = FirestoreErrorHelper.OperationType.LIST;
            List<FirestoreRestClient.RestDocument> cloudDocs =
                    FirestoreRestClient.listDocuments(appCtx, user, currentPath);

            Map<String, FirestoreRestClient.RestDocument> cloudByKey = new HashMap<>();
            for (FirestoreRestClient.RestDocument doc : cloudDocs) {
                cloudByKey.put(doc.getId(), doc);
            }

            // 2. Upload local proxies to cloud via REST (create or update)
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

                boolean existsOnCloud = cloudByKey.containsKey(key);
                Map<String, Object> payload = buildCloudProxyMap(local, !existsOnCloud);
                List<String> tsFields = existsOnCloud
                        ? Collections.singletonList("updatedAt")
                        : Arrays.asList("createdAt", "updatedAt");
                FirestoreRestClient.commitDocument(
                        appCtx,
                        user,
                        currentPath,
                        existsOnCloud ? Boolean.TRUE : Boolean.FALSE,
                        payload,
                        tsFields
                );
                uploadedCount++;
            }

            // 3. Download cloud proxies missing on this device
            int downloadedCount = 0;
            for (FirestoreRestClient.RestDocument cloudDoc : cloudDocs) {
                if (localKeys.contains(cloudDoc.getId())) {
                    continue;
                }
                ProxyConfig fromCloud = parseCloudProxyFromRest(cloudDoc);
                if (fromCloud != null) {
                    dbHelper.insertProxy(fromCloud);
                    downloadedCount++;
                }
            }

            // 4. Sync app preferences via REST
            currentPath = "users/" + uid + "/sync_state/preferences";
            currentOp = FirestoreErrorHelper.OperationType.GET;
            FirestoreRestClient.RestDocument cloudPrefsDoc =
                    FirestoreRestClient.getDocument(appCtx, user, currentPath);

            if (cloudPrefsDoc.exists() && localProxies.isEmpty()) {
                applyCloudPreferencesFromRest(prefs, cloudPrefsDoc);
            }

            currentOp = FirestoreErrorHelper.OperationType.WRITE;
            boolean prefsExist = cloudPrefsDoc.exists();
            Map<String, Object> prefsMap = buildSyncPreferencesMap(prefs, !prefsExist);
            List<String> prefsTs = prefsExist
                    ? Collections.singletonList("updatedAt")
                    : Arrays.asList("createdAt", "updatedAt");
            FirestoreRestClient.commitDocument(
                    appCtx,
                    user,
                    currentPath,
                    prefsExist ? Boolean.TRUE : Boolean.FALSE,
                    prefsMap,
                    prefsTs
            );

            // 5. Refresh local proxy repository if any cloud proxies were downloaded
            List<ProxyConfig> mergedProxies = dbHelper.getAllProxies();
            if (downloadedCount > 0) {
                ProxyRepository.getInstance(appCtx).reloadAll();
                ProxyRepository.getInstance(appCtx).addLog(
                        ProxyLogEntry.Level.SUCCESS,
                        ProxyLogFormatter.getString(appCtx, R.string.log_cloud_sync_done, uploadedCount, downloadedCount)
                );
            }

            // 6. Update /users/{uid} summary via REST
            currentPath = "users/" + uid;
            currentOp = FirestoreErrorHelper.OperationType.GET;
            FirestoreRestClient.RestDocument userDoc =
                    FirestoreRestClient.getDocument(appCtx, user, currentPath);

            int nextSyncCount = 1;
            if (userDoc.exists()) {
                nextSyncCount = Math.min(1_000_000, Math.max(0, userDoc.getInt("syncCount")) + 1);
            }

            currentOp = FirestoreErrorHelper.OperationType.WRITE;
            boolean userExists = userDoc.exists();
            Map<String, Object> summaryMap = buildUserSummaryMap(
                    appCtx,
                    user,
                    mergedProxies,
                    prefs,
                    nextSyncCount,
                    !userExists
            );
            List<String> userTs = userExists
                    ? Arrays.asList("updatedAt", "lastSyncAt")
                    : Arrays.asList("createdAt", "updatedAt", "lastSyncAt");
            FirestoreRestClient.commitDocument(
                    appCtx,
                    user,
                    currentPath,
                    userExists ? Boolean.TRUE : Boolean.FALSE,
                    summaryMap,
                    userTs
            );

            // 7. Write immutable sync event for Admin statistics (non-fatal if rules omit sync_events)
            String eventId = buildSyncEventId(uid);
            currentPath = "sync_events/" + eventId;
            currentOp = FirestoreErrorHelper.OperationType.CREATE;
            Map<String, Object> eventMap = buildSyncEventMap(
                    user,
                    uploadedCount,
                    downloadedCount,
                    mergedProxies.size()
            );
            try {
                FirestoreRestClient.commitDocument(
                        appCtx,
                        user,
                        currentPath,
                        Boolean.FALSE,
                        eventMap,
                        Collections.singletonList("createdAt")
                );
            } catch (Exception telemetryEx) {
                Throwable root = FirestoreErrorHelper.unwrapRootCause(telemetryEx);
                if (root instanceof FirebaseFirestoreException
                        && ((FirebaseFirestoreException) root).getCode() == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    FirestoreErrorHelper.handleFirestoreError(telemetryEx, currentOp, currentPath);
                } else {
                    throw telemetryEx;
                }
            }

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
    }

    private static void prepareFirestoreSession(
            @NonNull Context appCtx,
            @NonNull FirebaseUser user,
            @NonNull FirebaseFirestore db) {
        try {
            ProviderInstaller.installIfNeeded(appCtx);
        } catch (Throwable ignored) {
        }
        try {
            Tasks.await(user.getIdToken(false), 10L, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
        try {
            Tasks.await(db.enableNetwork(), 10L, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    @VisibleForTesting
    public static boolean hasActiveNetworkConnection(@NonNull Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return true;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network active = cm.getActiveNetwork();
                if (active == null) {
                    return false;
                }
                NetworkCapabilities caps = cm.getNetworkCapabilities(active);
                return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            }
            @SuppressWarnings("deprecation")
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Exception e) {
            return true;
        }
    }

    private interface SourceQueryCaller {
        Task<QuerySnapshot> call(Source source);
    }

    private static QuerySnapshot executeReadWithRetry(
            @NonNull FirebaseFirestore db,
            @NonNull SourceQueryCaller caller) throws Exception {
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_TRANSIENT_ATTEMPTS; attempt++) {
            try {
                Source source = attempt == 1 ? Source.DEFAULT : Source.SERVER;
                return awaitTask(caller.call(source), TASK_TIMEOUT_SECONDS);
            } catch (Exception e) {
                lastError = e;
                if (!FirestoreErrorHelper.isTransientRetryable(e) || attempt >= MAX_TRANSIENT_ATTEMPTS) {
                    throw e;
                }
                recoverNetworkBeforeRetry(db, attempt);
            }
        }
        throw lastError;
    }

    private static DocumentSnapshot executeDocReadWithRetry(
            @NonNull FirebaseFirestore db,
            @NonNull DocumentReference docRef) throws Exception {
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_TRANSIENT_ATTEMPTS; attempt++) {
            try {
                Source source = attempt == 1 ? Source.DEFAULT : Source.SERVER;
                return awaitTask(docRef.get(source), TASK_TIMEOUT_SECONDS);
            } catch (Exception e) {
                lastError = e;
                if (!FirestoreErrorHelper.isTransientRetryable(e) || attempt >= MAX_TRANSIENT_ATTEMPTS) {
                    throw e;
                }
                recoverNetworkBeforeRetry(db, attempt);
            }
        }
        throw lastError;
    }

    private static <T> T executeWithRetry(
            @NonNull FirebaseFirestore db,
            @NonNull Supplier<Task<T>> taskSupplier) throws Exception {
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_TRANSIENT_ATTEMPTS; attempt++) {
            try {
                return awaitTask(taskSupplier.get(), TASK_TIMEOUT_SECONDS);
            } catch (Exception e) {
                lastError = e;
                if (!FirestoreErrorHelper.isTransientRetryable(e) || attempt >= MAX_TRANSIENT_ATTEMPTS) {
                    throw e;
                }
                recoverNetworkBeforeRetry(db, attempt);
            }
        }
        throw lastError;
    }

    private static <T> T awaitTask(@NonNull Task<T> task, long timeoutSeconds) throws Exception {
        try {
            return Tasks.await(task, timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw e;
        }
    }

    private static void recoverNetworkBeforeRetry(@NonNull FirebaseFirestore db, int attempt) {
        try {
            Tasks.await(db.enableNetwork(), 8L, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
        try {
            Thread.sleep(600L * attempt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
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
        map.put("languageTag", safeLanguageTag(prefs));
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
        map.put("languageTag", safeLanguageTag(prefs));
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

    @Nullable
    private static ProxyConfig parseCloudProxyFromRest(@NonNull FirestoreRestClient.RestDocument doc) {
        String host = doc.getString("host");
        Number rawPort = doc.getNumber("port");
        int port = rawPort != null ? rawPort.intValue() : -1;
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

        p.setLatencyMs(doc.getLong("latencyMs", ProxyConfig.LATENCY_UNTESTED));
        p.setExitIp(nonNull(doc.getString("exitIp")));
        p.setCountry(nonNull(doc.getString("country")));
        p.setSortOrder(doc.getInt("sortOrder"));
        return p;
    }

    private static void applyCloudPreferencesFromRest(
            @NonNull SettingsPreferences prefs,
            @NonNull FirestoreRestClient.RestDocument doc) {
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
        Number rawTimer = doc.getNumber("vpnTimerMinutes");
        if (rawTimer != null) {
            prefs.setVpnTimerMinutes(rawTimer.intValue());
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
    private static String safeLanguageTag(@NonNull SettingsPreferences prefs) {
        String tag = truncate(prefs.getAppLanguage().getTag(), 20);
        return tag.isEmpty() ? "system" : tag;
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
