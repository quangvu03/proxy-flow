package com.example.ads;

import android.app.Activity;
import android.app.Application;
import android.app.Service;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.example.R;
import com.example.auth.AdminConfig;
import com.example.auth.GoogleAuthManager;
import com.example.data.CloudSyncManager;
import com.example.data.FirestoreErrorHelper;
import com.example.data.FirestoreRestClient;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.SetOptions;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Coordinates global AdMob enable/disable state stored in Firestore at /app_config/ads
 * (field: ads_enabled). Any authenticated user may read; only active admins
 * (roles/{uid} with role == "admin" and active == true) may toggle it.
 */
public final class AdsConfigManager {

    public static final String COLLECTION_APP_CONFIG = "app_config";
    public static final String DOC_ADS = "ads";
    public static final String FIELD_ADS_ENABLED = "ads_enabled";
    public static final String FIELD_UPDATED_AT = "updatedAt";
    public static final String FIELD_UPDATED_BY = "updatedBy";

    private static final String TAG = "AdsConfigManager";
    private static final String PREFS_NAME = "proxyflow_ads_config";
    private static final String KEY_ADS_ENABLED = "ads_enabled";

    public interface AdsConfigListener {
        void onAdsEnabledChanged(boolean enabled);
    }

    public interface ConfigCallback {
        void onLoaded(boolean adsEnabled);

        void onError(@NonNull String message);
    }

    public interface UpdateCallback {
        void onSuccess(boolean adsEnabled);

        void onError(@NonNull String message);
    }

    private static volatile AdsConfigManager instance;

    private final Context appContext;
    private final SharedPreferences prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArraySet<AdsConfigListener> listeners = new CopyOnWriteArraySet<>();

    private volatile boolean adsEnabled;
    private volatile boolean testOverrideActive = false;
    private boolean listeningStarted = false;

    @Nullable
    private FirebaseAuth firebaseAuth;
    @Nullable
    private FirebaseAuth.AuthStateListener authStateListener;
    @Nullable
    private ListenerRegistration adsDocListener;

    private AdsConfigManager(@NonNull Context appContext) {
        this.appContext = appContext;
        this.prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.adsEnabled = prefs.getBoolean(KEY_ADS_ENABLED, true);
    }

    @NonNull
    public static AdsConfigManager getInstance(@NonNull Context context) {
        Context appCtx = resolveCanonicalAppContext(context);
        AdsConfigManager current = instance;
        if (current == null || current.appContext != appCtx) {
            synchronized (AdsConfigManager.class) {
                current = instance;
                if (current == null || current.appContext != appCtx) {
                    if (current != null) {
                        current.detachFirestoreListener();
                    }
                    instance = new AdsConfigManager(appCtx);
                }
            }
        }
        return instance;
    }

    @NonNull
    private static Context resolveCanonicalAppContext(@NonNull Context context) {
        if (context instanceof Application) {
            return context;
        }
        if (context instanceof Activity) {
            Application app = ((Activity) context).getApplication();
            if (app != null) {
                return app;
            }
        }
        if (context instanceof Service) {
            Application app = ((Service) context).getApplication();
            if (app != null) {
                return app;
            }
        }
        Context appCtx = context.getApplicationContext();
        Context cursor = appCtx != null ? appCtx : context;
        int depth = 0;
        while (cursor instanceof ContextWrapper && !(cursor instanceof Application) && depth++ < 16) {
            Context base = ((ContextWrapper) cursor).getBaseContext();
            if (base == null || base == cursor) {
                break;
            }
            cursor = base;
        }
        if (cursor instanceof Application) {
            return cursor;
        }
        return appCtx != null ? appCtx : context;
    }

    public boolean isAdsEnabled() {
        return adsEnabled;
    }

    public void addListener(@NonNull AdsConfigListener listener) {
        listeners.add(listener);
    }

    public void removeListener(@NonNull AdsConfigListener listener) {
        listeners.remove(listener);
    }

    public synchronized void startListening() {
        if (listeningStarted) {
            return;
        }
        listeningStarted = true;
        firebaseAuth = GoogleAuthManager.getFirebaseAuth(appContext);
        if (firebaseAuth == null) {
            return;
        }
        authStateListener = auth -> onAuthChanged(auth.getCurrentUser());
        firebaseAuth.addAuthStateListener(authStateListener);
        onAuthChanged(firebaseAuth.getCurrentUser());
    }

    private synchronized void onAuthChanged(@Nullable FirebaseUser user) {
        if (testOverrideActive) {
            return;
        }
        if (user == null || user.getUid().trim().isEmpty()) {
            detachFirestoreListener();
            return;
        }
        attachFirestoreListener();
    }

    private synchronized void attachFirestoreListener() {
        if (adsDocListener != null) {
            return;
        }
        FirebaseFirestore db = CloudSyncManager.getFirestore(appContext);
        if (db == null) {
            refreshViaRestQuietly();
            return;
        }
        adsDocListener = db.collection(COLLECTION_APP_CONFIG)
                .document(DOC_ADS)
                .addSnapshotListener((snapshot, error) -> {
                    if (testOverrideActive) {
                        return;
                    }
                    if (error != null) {
                        if (error.getCode() != FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                            Log.w(TAG, "app_config/ads listener error: " + error.getMessage());
                        }
                        if (FirestoreErrorHelper.isTransientRetryable(error)) {
                            refreshViaRestQuietly();
                        }
                        return;
                    }
                    if (snapshot != null && snapshot.exists()) {
                        Boolean raw = snapshot.getBoolean(FIELD_ADS_ENABLED);
                        if (raw != null) {
                            applyAdsEnabledState(raw);
                        }
                    }
                });
    }

    private void refreshViaRestQuietly() {
        FirebaseUser currentUser = GoogleAuthManager.getCurrentUser(appContext);
        if (currentUser == null || currentUser.getUid().trim().isEmpty()) {
            return;
        }
        executor.execute(() -> {
            if (testOverrideActive) {
                return;
            }
            try {
                FirestoreRestClient.RestDocument doc = FirestoreRestClient.getDocument(
                        appContext,
                        currentUser,
                        COLLECTION_APP_CONFIG + "/" + DOC_ADS
                );
                if (!testOverrideActive && doc.exists()) {
                    Boolean raw = doc.getBoolean(FIELD_ADS_ENABLED);
                    if (raw != null) {
                        applyAdsEnabledState(raw);
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }

    private synchronized void detachFirestoreListener() {
        if (adsDocListener != null) {
            adsDocListener.remove();
            adsDocListener = null;
        }
    }

    public void updateFromRemoteSnapshot(boolean enabled) {
        if (!testOverrideActive) {
            applyAdsEnabledState(enabled);
        }
    }

    private void applyAdsEnabledState(boolean enabled) {
        boolean changed = this.adsEnabled != enabled;
        this.adsEnabled = enabled;
        prefs.edit().putBoolean(KEY_ADS_ENABLED, enabled).apply();
        if (changed) {
            mainHandler.post(() -> {
                for (AdsConfigListener listener : listeners) {
                    listener.onAdsEnabledChanged(enabled);
                }
            });
        }
    }

    public void fetchAdsEnabled(@NonNull Context context, @NonNull ConfigCallback callback) {
        Context ctx = resolveCanonicalAppContext(context);
        FirebaseUser currentUser = GoogleAuthManager.getCurrentUser(ctx);
        if (currentUser == null || currentUser.getUid().trim().isEmpty()) {
            mainHandler.post(() -> callback.onLoaded(adsEnabled));
            return;
        }
        String path = COLLECTION_APP_CONFIG + "/" + DOC_ADS;
        executor.execute(() -> {
            FirebaseFirestore db = CloudSyncManager.getFirestore(ctx);
            if (db != null) {
                try {
                    DocumentSnapshot doc = Tasks.await(
                            db.collection(COLLECTION_APP_CONFIG).document(DOC_ADS).get(),
                            10L,
                            TimeUnit.SECONDS
                    );
                    boolean enabled = adsEnabled;
                    if (doc.exists()) {
                        Boolean raw = doc.getBoolean(FIELD_ADS_ENABLED);
                        if (raw != null) {
                            enabled = raw;
                        }
                    }
                    applyAdsEnabledState(enabled);
                    final boolean resolved = enabled;
                    mainHandler.post(() -> callback.onLoaded(resolved));
                    return;
                } catch (Exception e) {
                    if (!FirestoreErrorHelper.isTransientRetryable(e)) {
                        FirestoreErrorHelper.handleFirestoreError(e, FirestoreErrorHelper.OperationType.GET, path);
                        String friendly = FirestoreErrorHelper.getFriendlyErrorMessage(ctx, e);
                        mainHandler.post(() -> callback.onError(friendly));
                        return;
                    }
                }
            }

            try {
                FirestoreRestClient.RestDocument doc = FirestoreRestClient.getDocument(ctx, currentUser, path);
                boolean enabled = adsEnabled;
                if (doc.exists()) {
                    Boolean raw = doc.getBoolean(FIELD_ADS_ENABLED);
                    if (raw != null) {
                        enabled = raw;
                    }
                }
                applyAdsEnabledState(enabled);
                final boolean resolved = enabled;
                mainHandler.post(() -> callback.onLoaded(resolved));
            } catch (Exception restErr) {
                FirestoreErrorHelper.handleFirestoreError(restErr, FirestoreErrorHelper.OperationType.GET, path);
                String friendly = FirestoreErrorHelper.getFriendlyErrorMessage(ctx, restErr);
                mainHandler.post(() -> callback.onError(friendly));
            }
        });
    }

    /**
     * Updates /app_config/ads.ads_enabled in Firestore after verifying active admin RBAC.
     */
    public void setAdsEnabled(
            @NonNull Context context,
            boolean enabled,
            @NonNull UpdateCallback callback) {
        Context ctx = resolveCanonicalAppContext(context);
        FirebaseUser currentUser = GoogleAuthManager.getCurrentUser(ctx);
        if (currentUser == null || currentUser.getUid().trim().isEmpty()) {
            mainHandler.post(() -> callback.onError(ctx.getString(R.string.msg_admin_access_denied)));
            return;
        }

        final String uid = currentUser.getUid().trim();
        executor.execute(() -> {
            String path = AdminConfig.COLLECTION_ROLES + "/" + uid;
            FirebaseFirestore db = CloudSyncManager.getFirestore(ctx);
            if (db != null) {
                try {
                    DocumentSnapshot roleDoc = Tasks.await(
                            db.collection(AdminConfig.COLLECTION_ROLES).document(uid).get(),
                            10L,
                            TimeUnit.SECONDS
                    );
                    AdminConfig.RbacAccess access = AdminConfig.RbacAccess.fromDocument(uid, roleDoc);
                    AdminConfig.cacheAccess(access);
                    if (!access.isAdmin()) {
                        mainHandler.post(() -> callback.onError(ctx.getString(R.string.msg_admin_access_denied)));
                        return;
                    }

                    path = COLLECTION_APP_CONFIG + "/" + DOC_ADS;
                    Map<String, Object> payload = new HashMap<>();
                    payload.put(FIELD_ADS_ENABLED, enabled);
                    payload.put(FIELD_UPDATED_AT, FieldValue.serverTimestamp());
                    payload.put(FIELD_UPDATED_BY, uid);

                    Tasks.await(
                            db.collection(COLLECTION_APP_CONFIG)
                                    .document(DOC_ADS)
                                    .set(payload, SetOptions.merge()),
                            10L,
                            TimeUnit.SECONDS
                    );

                    applyAdsEnabledState(enabled);
                    mainHandler.post(() -> callback.onSuccess(enabled));
                    return;
                } catch (Exception e) {
                    Throwable root = FirestoreErrorHelper.unwrapRootCause(e);
                    if (root instanceof FirebaseFirestoreException
                            && ((FirebaseFirestoreException) root).getCode() == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        AdminConfig.clearCache();
                        mainHandler.post(() -> callback.onError(ctx.getString(R.string.msg_admin_access_denied)));
                        return;
                    }
                    if (!FirestoreErrorHelper.isTransientRetryable(e)) {
                        FirestoreErrorHelper.handleFirestoreError(e, FirestoreErrorHelper.OperationType.WRITE, path);
                        String friendly = FirestoreErrorHelper.getFriendlyErrorMessage(ctx, e);
                        mainHandler.post(() -> callback.onError(friendly));
                        return;
                    }
                }
            }

            try {
                path = AdminConfig.COLLECTION_ROLES + "/" + uid;
                FirestoreRestClient.RestDocument roleDoc = FirestoreRestClient.getDocument(ctx, currentUser, path);
                AdminConfig.RbacAccess access = AdminConfig.RbacAccess.fromRestDocument(uid, roleDoc);
                AdminConfig.cacheAccess(access);
                if (!access.isAdmin()) {
                    mainHandler.post(() -> callback.onError(ctx.getString(R.string.msg_admin_access_denied)));
                    return;
                }

                path = COLLECTION_APP_CONFIG + "/" + DOC_ADS;
                Map<String, Object> payload = new HashMap<>();
                payload.put(FIELD_ADS_ENABLED, enabled);
                payload.put(FIELD_UPDATED_AT, FieldValue.serverTimestamp());
                payload.put(FIELD_UPDATED_BY, uid);

                FirestoreRestClient.commitDocument(
                        ctx,
                        currentUser,
                        path,
                        null,
                        payload,
                        Collections.singletonList(FIELD_UPDATED_AT)
                );

                applyAdsEnabledState(enabled);
                mainHandler.post(() -> callback.onSuccess(enabled));
            } catch (Exception restErr) {
                Throwable root = FirestoreErrorHelper.unwrapRootCause(restErr);
                if (root instanceof FirebaseFirestoreException
                        && ((FirebaseFirestoreException) root).getCode() == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    AdminConfig.clearCache();
                    mainHandler.post(() -> callback.onError(ctx.getString(R.string.msg_admin_access_denied)));
                    return;
                }
                FirestoreErrorHelper.handleFirestoreError(restErr, FirestoreErrorHelper.OperationType.WRITE, path);
                String friendly = FirestoreErrorHelper.getFriendlyErrorMessage(ctx, restErr);
                mainHandler.post(() -> callback.onError(friendly));
            }
        });
    }

    @VisibleForTesting
    public void setAdsEnabledForTesting(boolean enabled) {
        testOverrideActive = true;
        boolean changed = this.adsEnabled != enabled;
        this.adsEnabled = enabled;
        prefs.edit().putBoolean(KEY_ADS_ENABLED, enabled).commit();
        if (changed) {
            Runnable notifyTask = () -> {
                for (AdsConfigListener listener : listeners) {
                    listener.onAdsEnabledChanged(enabled);
                }
            };
            if (Looper.myLooper() == Looper.getMainLooper()) {
                notifyTask.run();
            } else {
                mainHandler.post(notifyTask);
            }
        }
    }

    @VisibleForTesting
    public void resetForTesting() {
        testOverrideActive = false;
        adsEnabled = true;
        prefs.edit().clear().commit();
        listeners.clear();
        detachFirestoreListener();
    }
}
