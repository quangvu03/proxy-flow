package com.example.auth;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.example.R;
import com.example.data.CloudSyncManager;
import com.example.data.FirestoreErrorHelper;
import com.example.data.FirestoreRestClient;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Firestore-backed Role-Based Access Control (RBAC) manager for TopProxy Admin Dashboard.
 * Roles and granular permissions are resolved dynamically from /roles/{uid} in Firestore.
 */
public final class AdminConfig {

    public static final String COLLECTION_ROLES = "roles";

    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_MODERATOR = "moderator";
    public static final String ROLE_VIEWER = "viewer";
    public static final String ROLE_USER = "user";

    public static final String PERM_ADMIN_DASHBOARD_VIEW = "admin.dashboard.view";
    public static final String PERM_ADMIN_USERS_LIST = "admin.users.list";
    public static final String PERM_ADMIN_SYNC_EVENTS_LIST = "admin.sync_events.list";
    public static final String PERM_ADMIN_ROLES_MANAGE = "admin.roles.manage";
    public static final String PERM_WILDCARD = "*";

    private static final long CACHE_TTL_MS = 5L * 60L * 1000L;
    private static final ConcurrentHashMap<String, RbacAccess> ROLE_CACHE = new ConcurrentHashMap<>();
    private static final ExecutorService RBAC_EXECUTOR = Executors.newCachedThreadPool();
    private static volatile Handler mainHandler;

    public static final class RbacAccess {
        private final String uid;
        private final String role;
        private final boolean active;
        private final Set<String> permissions;
        private final long fetchedAtMs;
        private final boolean pinnedForTest;

        private RbacAccess(
                @NonNull String uid,
                @NonNull String role,
                boolean active,
                @NonNull Set<String> permissions,
                long fetchedAtMs,
                boolean pinnedForTest) {
            this.uid = uid;
            this.role = role;
            this.active = active;
            this.permissions = Collections.unmodifiableSet(new HashSet<>(permissions));
            this.fetchedAtMs = fetchedAtMs;
            this.pinnedForTest = pinnedForTest;
        }

        @NonNull
        public static RbacAccess none(@Nullable String uid) {
            String safeUid = uid != null ? uid.trim() : "";
            return new RbacAccess(safeUid, ROLE_USER, false, Collections.emptySet(), System.currentTimeMillis(), false);
        }

        @NonNull
        public static RbacAccess fromRaw(
                @Nullable String uid,
                @Nullable String rawRole,
                @Nullable Collection<String> rawPermissions) {
            return fromRaw(uid, rawRole, true, rawPermissions);
        }

        @NonNull
        public static RbacAccess fromRaw(
                @Nullable String uid,
                @Nullable String rawRole,
                boolean active,
                @Nullable Collection<String> rawPermissions) {
            return build(uid, rawRole, active, rawPermissions, System.currentTimeMillis(), false);
        }

        @NonNull
        public static RbacAccess fromDocument(@NonNull String uid, @Nullable DocumentSnapshot doc) {
            if (doc == null || !doc.exists()) {
                return none(uid);
            }
            String docRole = doc.getString("role");
            boolean isActive = Boolean.TRUE.equals(doc.getBoolean("active"));
            Object rawPerms = doc.get("permissions");
            Set<String> perms = new HashSet<>();
            if (rawPerms instanceof Iterable<?>) {
                for (Object entry : (Iterable<?>) rawPerms) {
                    if (entry instanceof String) {
                        String trimmed = ((String) entry).trim();
                        if (!trimmed.isEmpty()) {
                            perms.add(trimmed);
                        }
                    }
                }
            }
            return build(uid, docRole, isActive, perms, System.currentTimeMillis(), false);
        }

        @NonNull
        public static RbacAccess fromRestDocument(
                @NonNull String uid,
                @Nullable FirestoreRestClient.RestDocument doc) {
            if (doc == null || !doc.exists()) {
                return none(uid);
            }
            String docRole = doc.getString("role");
            boolean isActive = Boolean.TRUE.equals(doc.getBoolean("active"));
            List<String> perms = doc.getStringList("permissions");
            return build(uid, docRole, isActive, perms, System.currentTimeMillis(), false);
        }

        @NonNull
        private static RbacAccess build(
                @Nullable String uid,
                @Nullable String rawRole,
                boolean active,
                @Nullable Collection<String> rawPermissions,
                long fetchedAtMs,
                boolean pinnedForTest) {
            String safeUid = uid != null ? uid.trim() : "";
            String normalizedRole = rawRole != null ? rawRole.trim().toLowerCase(Locale.ROOT) : ROLE_USER;
            if (normalizedRole.isEmpty()) {
                normalizedRole = ROLE_USER;
            }
            Set<String> perms = new HashSet<>();
            if (rawPermissions != null) {
                for (String p : rawPermissions) {
                    if (p != null && !p.trim().isEmpty()) {
                        perms.add(p.trim());
                    }
                }
            }
            return new RbacAccess(safeUid, normalizedRole, active, perms, fetchedAtMs, pinnedForTest);
        }

        @NonNull
        public String getUid() {
            return uid;
        }

        @NonNull
        public String getRole() {
            return role;
        }

        public boolean isActive() {
            return active;
        }

        @NonNull
        public Set<String> getPermissions() {
            return permissions;
        }

        public boolean isAdmin() {
            return !uid.isEmpty() && active && (
                    ROLE_ADMIN.equals(role)
                            || permissions.contains(PERM_WILDCARD)
                            || permissions.contains(PERM_ADMIN_ROLES_MANAGE)
            );
        }

        public boolean hasPermission(@NonNull String permission) {
            return isAdmin() || (!uid.isEmpty() && active && permissions.contains(permission));
        }

        public boolean canAccessAdminDashboard() {
            return !uid.isEmpty() && active && (isAdmin() || hasPermission(PERM_ADMIN_DASHBOARD_VIEW));
        }

        boolean isExpired(long nowMs) {
            return !pinnedForTest && (nowMs - fetchedAtMs) > CACHE_TTL_MS;
        }

        boolean isPinnedForTest() {
            return pinnedForTest;
        }
    }

    public interface RbacCallback {
        void onResolved(@NonNull RbacAccess access);

        void onError(@NonNull String message);
    }

    private AdminConfig() {
    }

    private static void postToMain(@NonNull Runnable runnable) {
        if (mainHandler == null) {
            mainHandler = new Handler(Looper.getMainLooper());
        }
        mainHandler.post(runnable);
    }

    @NonNull
    public static RbacAccess getCachedAccess(@Nullable String uid) {
        if (uid == null || uid.trim().isEmpty()) {
            return RbacAccess.none("");
        }
        String key = uid.trim();
        RbacAccess cached = ROLE_CACHE.get(key);
        if (cached == null || cached.isExpired(System.currentTimeMillis())) {
            if (cached != null) {
                ROLE_CACHE.remove(key, cached);
            }
            return RbacAccess.none(key);
        }
        return cached;
    }

    public static boolean hasCachedAdminDashboardAccess(@Nullable FirebaseUser user) {
        if (user == null || user.getUid().trim().isEmpty()) {
            return false;
        }
        return getCachedAccess(user.getUid()).canAccessAdminDashboard();
    }

    public static void cacheAccess(@NonNull RbacAccess access) {
        if (!access.getUid().isEmpty()) {
            ROLE_CACHE.put(access.getUid(), access);
        }
    }

    public static void clearCache() {
        ROLE_CACHE.clear();
    }

    @VisibleForTesting
    public static void seedAccessForTesting(
            @NonNull String uid,
            @Nullable String role,
            @Nullable List<String> permissions) {
        seedAccessForTesting(uid, role, true, permissions);
    }

    @VisibleForTesting
    public static void seedAccessForTesting(
            @NonNull String uid,
            @Nullable String role,
            boolean active,
            @Nullable List<String> permissions) {
        String key = uid.trim();
        if (key.isEmpty()) {
            return;
        }
        ROLE_CACHE.put(key, RbacAccess.build(key, role, active, permissions, System.currentTimeMillis(), true));
    }

    public static void fetchCurrentUserAccess(@NonNull Context context, @NonNull RbacCallback callback) {
        Context appCtx = context.getApplicationContext();
        FirebaseUser currentUser = GoogleAuthManager.getCurrentUser(appCtx);
        if (currentUser == null || currentUser.getUid().trim().isEmpty()) {
            clearCache();
            postToMain(() -> callback.onResolved(RbacAccess.none("")));
            return;
        }
        fetchAccessForUser(appCtx, currentUser.getUid(), callback);
    }

    public static void fetchAccessForUser(
            @NonNull Context context,
            @Nullable String uid,
            @NonNull RbacCallback callback) {
        Context appCtx = context.getApplicationContext();
        String safeUid = uid != null ? uid.trim() : "";
        if (safeUid.isEmpty()) {
            postToMain(() -> callback.onResolved(RbacAccess.none("")));
            return;
        }

        RbacAccess existing = ROLE_CACHE.get(safeUid);
        if (existing != null && existing.isPinnedForTest()) {
            postToMain(() -> callback.onResolved(existing));
            return;
        }

        String path = COLLECTION_ROLES + "/" + safeUid;
        RBAC_EXECUTOR.execute(() -> {
            FirebaseFirestore db = CloudSyncManager.getFirestore(appCtx);
            if (db != null) {
                try {
                    DocumentSnapshot doc = Tasks.await(
                            db.collection(COLLECTION_ROLES).document(safeUid).get(),
                            10L,
                            TimeUnit.SECONDS
                    );
                    RbacAccess resolved = RbacAccess.fromDocument(safeUid, doc);
                    cacheAccess(resolved);
                    postToMain(() -> callback.onResolved(resolved));
                    return;
                } catch (Exception e) {
                    Throwable root = FirestoreErrorHelper.unwrapRootCause(e);
                    if (root instanceof FirebaseFirestoreException
                            && ((FirebaseFirestoreException) root).getCode() == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        RbacAccess denied = RbacAccess.none(safeUid);
                        ROLE_CACHE.remove(safeUid);
                        postToMain(() -> callback.onResolved(denied));
                        return;
                    }
                    if (!FirestoreErrorHelper.isTransientRetryable(e)) {
                        FirestoreErrorHelper.handleFirestoreError(e, FirestoreErrorHelper.OperationType.GET, path);
                        String friendly = FirestoreErrorHelper.getFriendlyErrorMessage(appCtx, e);
                        postToMain(() -> callback.onError(friendly));
                        return;
                    }
                }
            }

            FirebaseUser currentUser = GoogleAuthManager.getCurrentUser(appCtx);
            if (currentUser == null || !safeUid.equals(currentUser.getUid().trim())) {
                String msg = appCtx.getString(R.string.msg_firestore_unavailable);
                postToMain(() -> callback.onError(msg));
                return;
            }
            try {
                FirestoreRestClient.RestDocument restDoc = FirestoreRestClient.getDocument(appCtx, currentUser, path);
                RbacAccess resolved = RbacAccess.fromRestDocument(safeUid, restDoc);
                cacheAccess(resolved);
                postToMain(() -> callback.onResolved(resolved));
            } catch (Exception restErr) {
                Throwable root = FirestoreErrorHelper.unwrapRootCause(restErr);
                if (root instanceof FirebaseFirestoreException
                        && ((FirebaseFirestoreException) root).getCode() == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    RbacAccess denied = RbacAccess.none(safeUid);
                    ROLE_CACHE.remove(safeUid);
                    postToMain(() -> callback.onResolved(denied));
                    return;
                }
                FirestoreErrorHelper.handleFirestoreError(restErr, FirestoreErrorHelper.OperationType.GET, path);
                String friendly = FirestoreErrorHelper.getFriendlyErrorMessage(appCtx, restErr);
                postToMain(() -> callback.onError(friendly));
            }
        });
    }
}
