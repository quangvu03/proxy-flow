package com.example.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.example.R;
import com.example.ads.AdsConfigManager;
import com.example.auth.AdminConfig;
import com.example.auth.GoogleAuthManager;
import android.text.TextUtils;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Fetches live Firebase Firestore statistics for authorized Admin users.
 */
public final class AdminStatsRepository {

    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
    private static final ExecutorService STATS_EXECUTOR = Executors.newSingleThreadExecutor();
    private static volatile Handler mainHandler;

    private static void postToMain(@NonNull Runnable runnable) {
        if (mainHandler == null) {
            mainHandler = new Handler(Looper.getMainLooper());
        }
        mainHandler.post(runnable);
    }

    public static final class UserStatsItem {
        private final String uid;
        private final String displayName;
        private final String email;
        private final int proxyCount;
        private final int workingProxyCount;
        private final int syncCount;
        private final boolean firewallEnabled;
        private final boolean privateRoutingEnabled;
        private final String deviceModel;
        private final String appVersion;
        private final long lastActiveMs;
        private final boolean admin;

        public UserStatsItem(
                String uid,
                String displayName,
                String email,
                int proxyCount,
                int workingProxyCount,
                int syncCount,
                boolean firewallEnabled,
                boolean privateRoutingEnabled,
                String deviceModel,
                String appVersion,
                long lastActiveMs,
                boolean admin) {
            this.uid = uid;
            this.displayName = displayName;
            this.email = email;
            this.proxyCount = proxyCount;
            this.workingProxyCount = workingProxyCount;
            this.syncCount = syncCount;
            this.firewallEnabled = firewallEnabled;
            this.privateRoutingEnabled = privateRoutingEnabled;
            this.deviceModel = deviceModel;
            this.appVersion = appVersion;
            this.lastActiveMs = lastActiveMs;
            this.admin = admin;
        }

        public String getUid() { return uid; }
        public String getDisplayName() { return displayName; }
        public String getEmail() { return email; }
        public int getProxyCount() { return proxyCount; }
        public int getWorkingProxyCount() { return workingProxyCount; }
        public int getSyncCount() { return syncCount; }
        public boolean isFirewallEnabled() { return firewallEnabled; }
        public boolean isPrivateRoutingEnabled() { return privateRoutingEnabled; }
        public String getDeviceModel() { return deviceModel; }
        public String getAppVersion() { return appVersion; }
        public long getLastActiveMs() { return lastActiveMs; }
        public boolean isAdmin() { return admin; }
    }

    public static final class SyncEventItem {
        private final String id;
        private final String userLabel;
        private final String deviceModel;
        private final int uploadedProxies;
        private final int downloadedProxies;
        private final int totalProxies;
        private final String appVersion;
        private final long createdAtMs;

        public SyncEventItem(
                String id,
                String userLabel,
                String deviceModel,
                int uploadedProxies,
                int downloadedProxies,
                int totalProxies,
                String appVersion,
                long createdAtMs) {
            this.id = id;
            this.userLabel = userLabel;
            this.deviceModel = deviceModel;
            this.uploadedProxies = uploadedProxies;
            this.downloadedProxies = downloadedProxies;
            this.totalProxies = totalProxies;
            this.appVersion = appVersion;
            this.createdAtMs = createdAtMs;
        }

        public String getId() { return id; }
        public String getUserLabel() { return userLabel; }
        public String getDeviceModel() { return deviceModel; }
        public int getUploadedProxies() { return uploadedProxies; }
        public int getDownloadedProxies() { return downloadedProxies; }
        public int getTotalProxies() { return totalProxies; }
        public String getAppVersion() { return appVersion; }
        public long getCreatedAtMs() { return createdAtMs; }
    }

    public static final class AdminDashboardData {
        private final int totalUsers;
        private final int activeUsers24h;
        private final int totalSyncedProxies;
        private final int totalWorkingProxies;
        private final int totalSyncRuns;
        private final int firewallEnabledUsers;
        private final int privateRoutingUsers;
        private final int syncEvents24h;
        private final long fetchedAtMs;
        private final List<UserStatsItem> users;
        private final List<SyncEventItem> recentSyncEvents;
        private final boolean adsEnabled;

        public AdminDashboardData(
                int totalUsers,
                int activeUsers24h,
                int totalSyncedProxies,
                int totalWorkingProxies,
                int totalSyncRuns,
                int firewallEnabledUsers,
                int privateRoutingUsers,
                int syncEvents24h,
                long fetchedAtMs,
                List<UserStatsItem> users,
                List<SyncEventItem> recentSyncEvents) {
            this(totalUsers, activeUsers24h, totalSyncedProxies, totalWorkingProxies, totalSyncRuns,
                    firewallEnabledUsers, privateRoutingUsers, syncEvents24h, fetchedAtMs,
                    users, recentSyncEvents, true);
        }

        public AdminDashboardData(
                int totalUsers,
                int activeUsers24h,
                int totalSyncedProxies,
                int totalWorkingProxies,
                int totalSyncRuns,
                int firewallEnabledUsers,
                int privateRoutingUsers,
                int syncEvents24h,
                long fetchedAtMs,
                List<UserStatsItem> users,
                List<SyncEventItem> recentSyncEvents,
                boolean adsEnabled) {
            this.totalUsers = totalUsers;
            this.activeUsers24h = activeUsers24h;
            this.totalSyncedProxies = totalSyncedProxies;
            this.totalWorkingProxies = totalWorkingProxies;
            this.totalSyncRuns = totalSyncRuns;
            this.firewallEnabledUsers = firewallEnabledUsers;
            this.privateRoutingUsers = privateRoutingUsers;
            this.syncEvents24h = syncEvents24h;
            this.fetchedAtMs = fetchedAtMs;
            this.users = users;
            this.recentSyncEvents = recentSyncEvents;
            this.adsEnabled = adsEnabled;
        }

        public int getTotalUsers() { return totalUsers; }
        public int getActiveUsers24h() { return activeUsers24h; }
        public int getTotalSyncedProxies() { return totalSyncedProxies; }
        public int getTotalWorkingProxies() { return totalWorkingProxies; }
        public int getTotalSyncRuns() { return totalSyncRuns; }
        public int getFirewallEnabledUsers() { return firewallEnabledUsers; }
        public int getPrivateRoutingUsers() { return privateRoutingUsers; }
        public int getSyncEvents24h() { return syncEvents24h; }
        public long getFetchedAtMs() { return fetchedAtMs; }
        public List<UserStatsItem> getUsers() { return users; }
        public List<SyncEventItem> getRecentSyncEvents() { return recentSyncEvents; }
        public boolean isAdsEnabled() { return adsEnabled; }
    }

    public interface StatsCallback {
        void onLoaded(@NonNull AdminDashboardData data);

        void onError(@NonNull String message);
    }

    private AdminStatsRepository() {
    }

    public static void loadDashboardStats(@NonNull Context context, @NonNull StatsCallback callback) {
        Context appCtx = context.getApplicationContext();
        FirebaseUser currentUser = GoogleAuthManager.getCurrentUser(appCtx);
        if (currentUser == null || TextUtils.isEmpty(currentUser.getUid())) {
            AdminConfig.clearCache();
            postToMain(() -> callback.onError(appCtx.getString(R.string.msg_admin_access_denied)));
            return;
        }

        final String currentUid = currentUser.getUid().trim();
        STATS_EXECUTOR.execute(() -> {
            String currentPath = AdminConfig.COLLECTION_ROLES + "/" + currentUid;
            FirebaseFirestore db = CloudSyncManager.getFirestore(appCtx);
            if (db != null) {
                try {
                    DocumentSnapshot myRoleDoc = Tasks.await(
                            db.collection(AdminConfig.COLLECTION_ROLES).document(currentUid).get(),
                            10L,
                            TimeUnit.SECONDS
                    );
                    AdminConfig.RbacAccess access = AdminConfig.RbacAccess.fromDocument(currentUid, myRoleDoc);
                    AdminConfig.cacheAccess(access);
                    if (!access.canAccessAdminDashboard()) {
                        postToMain(() -> callback.onError(appCtx.getString(R.string.msg_admin_access_denied)));
                        return;
                    }

                    currentPath = AdsConfigManager.COLLECTION_APP_CONFIG + "/" + AdsConfigManager.DOC_ADS;
                    boolean adsEnabled = AdsConfigManager.getInstance(appCtx).isAdsEnabled();
                    try {
                        DocumentSnapshot adsDoc = Tasks.await(
                                db.collection(AdsConfigManager.COLLECTION_APP_CONFIG)
                                        .document(AdsConfigManager.DOC_ADS)
                                        .get(),
                                10L,
                                TimeUnit.SECONDS
                        );
                        if (adsDoc.exists()) {
                            Boolean rawAds = adsDoc.getBoolean(AdsConfigManager.FIELD_ADS_ENABLED);
                            if (rawAds != null) {
                                adsEnabled = rawAds;
                                AdsConfigManager.getInstance(appCtx).updateFromRemoteSnapshot(adsEnabled);
                            }
                        }
                    } catch (Exception adsReadError) {
                        if (FirestoreErrorHelper.isTransientRetryable(adsReadError)) {
                            throw adsReadError;
                        }
                        FirestoreErrorHelper.handleFirestoreError(
                                adsReadError,
                                FirestoreErrorHelper.OperationType.GET,
                                currentPath
                        );
                    }

                    Map<String, AdminConfig.RbacAccess> rolesByUid = new HashMap<>();
                    rolesByUid.put(currentUid, access);
                    List<UserStatsItem> userItems = new ArrayList<>();
                    List<SyncEventItem> eventItems = new ArrayList<>();

                    try {
                        if (access.isAdmin()) {
                            currentPath = AdminConfig.COLLECTION_ROLES;
                            QuerySnapshot rolesSnapshot = Tasks.await(
                                    db.collection(AdminConfig.COLLECTION_ROLES).get(),
                                    10L,
                                    TimeUnit.SECONDS
                            );
                            for (DocumentSnapshot roleDoc : rolesSnapshot.getDocuments()) {
                                String roleUid = nonNull(roleDoc.getString("userId"), roleDoc.getId());
                                AdminConfig.RbacAccess parsedRole = AdminConfig.RbacAccess.fromDocument(roleUid, roleDoc);
                                rolesByUid.put(roleUid, parsedRole);
                            }
                        }

                        currentPath = "users";
                        QuerySnapshot usersSnapshot = Tasks.await(
                                db.collection("users").get(),
                                10L,
                                TimeUnit.SECONDS
                        );
                        for (DocumentSnapshot doc : usersSnapshot.getDocuments()) {
                            String uid = nonNull(doc.getString("userId"), doc.getId());
                            AdminConfig.RbacAccess userRole = rolesByUid.get(uid);
                            boolean isDashboardRole = userRole != null && userRole.canAccessAdminDashboard();
                            userItems.add(parseUserDoc(doc, isDashboardRole));
                        }

                        currentPath = "sync_events";
                        QuerySnapshot eventsSnapshot = Tasks.await(
                                db.collection("sync_events")
                                        .orderBy("createdAt", Query.Direction.DESCENDING)
                                        .limit(30)
                                        .get(),
                                10L,
                                TimeUnit.SECONDS
                        );
                        for (DocumentSnapshot doc : eventsSnapshot.getDocuments()) {
                            eventItems.add(parseEventDoc(doc));
                        }
                    } catch (Exception telemetryError) {
                        if (FirestoreErrorHelper.isTransientRetryable(telemetryError)) {
                            throw telemetryError;
                        }
                        FirestoreErrorHelper.handleFirestoreError(
                                telemetryError,
                                FirestoreErrorHelper.OperationType.LIST,
                                currentPath
                        );
                    }

                    AdminDashboardData dashboardData = aggregateStats(
                            userItems,
                            eventItems,
                            System.currentTimeMillis(),
                            adsEnabled
                    );
                    postToMain(() -> callback.onLoaded(dashboardData));
                    return;
                } catch (Exception e) {
                    Throwable root = FirestoreErrorHelper.unwrapRootCause(e);
                    if (root instanceof FirebaseFirestoreException
                            && ((FirebaseFirestoreException) root).getCode() == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        AdminConfig.clearCache();
                        postToMain(() -> callback.onError(appCtx.getString(R.string.msg_admin_access_denied)));
                        return;
                    }
                    if (!FirestoreErrorHelper.isTransientRetryable(e)) {
                        FirestoreErrorHelper.handleFirestoreError(
                                e,
                                FirestoreErrorHelper.OperationType.GET,
                                currentPath
                        );
                        String friendly = FirestoreErrorHelper.getFriendlyErrorMessage(appCtx, e);
                        postToMain(() -> callback.onError(friendly));
                        return;
                    }
                }
            }

            loadDashboardStatsViaRest(appCtx, currentUser, currentUid, callback);
        });
    }

    private static void loadDashboardStatsViaRest(
            @NonNull Context appCtx,
            @NonNull FirebaseUser currentUser,
            @NonNull String currentUid,
            @NonNull StatsCallback callback) {
        String currentPath = AdminConfig.COLLECTION_ROLES + "/" + currentUid;
        try {
            FirestoreRestClient.RestDocument myRoleDoc =
                    FirestoreRestClient.getDocument(appCtx, currentUser, currentPath);
            AdminConfig.RbacAccess access = AdminConfig.RbacAccess.fromRestDocument(currentUid, myRoleDoc);
            AdminConfig.cacheAccess(access);
            if (!access.canAccessAdminDashboard()) {
                postToMain(() -> callback.onError(appCtx.getString(R.string.msg_admin_access_denied)));
                return;
            }

            currentPath = AdsConfigManager.COLLECTION_APP_CONFIG + "/" + AdsConfigManager.DOC_ADS;
            boolean adsEnabled = AdsConfigManager.getInstance(appCtx).isAdsEnabled();
            try {
                FirestoreRestClient.RestDocument adsDoc =
                        FirestoreRestClient.getDocument(appCtx, currentUser, currentPath);
                if (adsDoc.exists()) {
                    Boolean rawAds = adsDoc.getBoolean(AdsConfigManager.FIELD_ADS_ENABLED);
                    if (rawAds != null) {
                        adsEnabled = rawAds;
                        AdsConfigManager.getInstance(appCtx).updateFromRemoteSnapshot(adsEnabled);
                    }
                }
            } catch (Exception adsReadError) {
                FirestoreErrorHelper.handleFirestoreError(
                        adsReadError,
                        FirestoreErrorHelper.OperationType.GET,
                        currentPath
                );
            }

            Map<String, AdminConfig.RbacAccess> rolesByUid = new HashMap<>();
            rolesByUid.put(currentUid, access);
            List<UserStatsItem> userItems = new ArrayList<>();
            List<SyncEventItem> eventItems = new ArrayList<>();

            try {
                if (access.isAdmin()) {
                    currentPath = AdminConfig.COLLECTION_ROLES;
                    List<FirestoreRestClient.RestDocument> roleDocs =
                            FirestoreRestClient.listDocuments(appCtx, currentUser, currentPath);
                    for (FirestoreRestClient.RestDocument roleDoc : roleDocs) {
                        String roleUid = nonNull(roleDoc.getString("userId"), roleDoc.getId());
                        rolesByUid.put(roleUid, AdminConfig.RbacAccess.fromRestDocument(roleUid, roleDoc));
                    }
                }

                currentPath = "users";
                List<FirestoreRestClient.RestDocument> userDocs =
                        FirestoreRestClient.listDocuments(appCtx, currentUser, currentPath);
                for (FirestoreRestClient.RestDocument doc : userDocs) {
                    String uid = nonNull(doc.getString("userId"), doc.getId());
                    AdminConfig.RbacAccess userRole = rolesByUid.get(uid);
                    boolean isDashboardRole = userRole != null && userRole.canAccessAdminDashboard();
                    userItems.add(parseUserDocFromRest(doc, isDashboardRole));
                }

                currentPath = "sync_events";
                List<FirestoreRestClient.RestDocument> eventDocs =
                        FirestoreRestClient.listDocuments(appCtx, currentUser, currentPath);
                for (FirestoreRestClient.RestDocument doc : eventDocs) {
                    eventItems.add(parseEventDocFromRest(doc));
                }
                Collections.sort(eventItems, (a, b) -> Long.compare(b.getCreatedAtMs(), a.getCreatedAtMs()));
                if (eventItems.size() > 30) {
                    eventItems = new ArrayList<>(eventItems.subList(0, 30));
                }
            } catch (Exception telemetryError) {
                FirestoreErrorHelper.handleFirestoreError(
                        telemetryError,
                        FirestoreErrorHelper.OperationType.LIST,
                        currentPath
                );
            }

            AdminDashboardData dashboardData = aggregateStats(
                    userItems,
                    eventItems,
                    System.currentTimeMillis(),
                    adsEnabled
            );
            postToMain(() -> callback.onLoaded(dashboardData));
        } catch (Exception restErr) {
            Throwable root = FirestoreErrorHelper.unwrapRootCause(restErr);
            if (root instanceof FirebaseFirestoreException
                    && ((FirebaseFirestoreException) root).getCode() == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                AdminConfig.clearCache();
                postToMain(() -> callback.onError(appCtx.getString(R.string.msg_admin_access_denied)));
                return;
            }
            FirestoreErrorHelper.handleFirestoreError(
                    restErr,
                    FirestoreErrorHelper.OperationType.GET,
                    currentPath
            );
            String friendly = FirestoreErrorHelper.getFriendlyErrorMessage(appCtx, restErr);
            postToMain(() -> callback.onError(friendly));
        }
    }

    @VisibleForTesting
    @NonNull
    public static AdminDashboardData aggregateStats(
            @NonNull List<UserStatsItem> users,
            @NonNull List<SyncEventItem> events,
            long nowMs) {
        return aggregateStats(users, events, nowMs, true);
    }

    @VisibleForTesting
    @NonNull
    public static AdminDashboardData aggregateStats(
            @NonNull List<UserStatsItem> users,
            @NonNull List<SyncEventItem> events,
            long nowMs,
            boolean adsEnabled) {
        long cutoff24h = nowMs - DAY_MS;

        int active24h = 0;
        int totalProxies = 0;
        int totalWorking = 0;
        int totalSyncs = 0;
        int firewallUsers = 0;
        int privateRoutingUsers = 0;

        for (UserStatsItem u : users) {
            if (u.getLastActiveMs() >= cutoff24h) {
                active24h++;
            }
            totalProxies += Math.max(0, u.getProxyCount());
            totalWorking += Math.max(0, u.getWorkingProxyCount());
            totalSyncs += Math.max(0, u.getSyncCount());
            if (u.isFirewallEnabled()) {
                firewallUsers++;
            }
            if (u.isPrivateRoutingEnabled()) {
                privateRoutingUsers++;
            }
        }

        int syncEvents24h = 0;
        for (SyncEventItem e : events) {
            if (e.getCreatedAtMs() >= cutoff24h) {
                syncEvents24h++;
            }
        }

        List<UserStatsItem> sortedUsers = new ArrayList<>(users);
        Collections.sort(sortedUsers, (a, b) -> Long.compare(b.getLastActiveMs(), a.getLastActiveMs()));

        List<SyncEventItem> sortedEvents = new ArrayList<>(events);
        Collections.sort(sortedEvents, (a, b) -> Long.compare(b.getCreatedAtMs(), a.getCreatedAtMs()));

        return new AdminDashboardData(
                sortedUsers.size(),
                active24h,
                totalProxies,
                totalWorking,
                totalSyncs,
                firewallUsers,
                privateRoutingUsers,
                syncEvents24h,
                nowMs,
                Collections.unmodifiableList(sortedUsers),
                Collections.unmodifiableList(sortedEvents),
                adsEnabled
        );
    }

    private static UserStatsItem parseUserDoc(@NonNull DocumentSnapshot doc, boolean isDashboardAdmin) {
        String uid = nonNull(doc.getString("userId"), doc.getId());
        String displayName = nonNull(doc.getString("displayName"), "User");
        String email = nonNull(doc.getString("email"), "");
        int proxyCount = getInt(doc, "proxyCount");
        int workingCount = getInt(doc, "workingProxyCount");
        int syncCount = getInt(doc, "syncCount");
        boolean firewall = Boolean.TRUE.equals(doc.getBoolean("firewallEnabled"));
        boolean privateRouting = Boolean.TRUE.equals(doc.getBoolean("privateRoutingEnabled"));
        String device = nonNull(doc.getString("lastDeviceModel"), "");
        String appVersion = nonNull(doc.getString("appVersion"), "");

        long lastSyncMs = getTimestampMs(doc, "lastSyncAt");
        long updatedMs = getTimestampMs(doc, "updatedAt");
        long lastActiveMs = Math.max(lastSyncMs, updatedMs);

        return new UserStatsItem(
                uid,
                displayName,
                email,
                proxyCount,
                workingCount,
                syncCount,
                firewall,
                privateRouting,
                device,
                appVersion,
                lastActiveMs,
                isDashboardAdmin
        );
    }

    private static SyncEventItem parseEventDoc(@NonNull DocumentSnapshot doc) {
        return new SyncEventItem(
                doc.getId(),
                nonNull(doc.getString("userLabel"), "User"),
                nonNull(doc.getString("deviceModel"), ""),
                getInt(doc, "uploadedProxies"),
                getInt(doc, "downloadedProxies"),
                getInt(doc, "totalProxies"),
                nonNull(doc.getString("appVersion"), ""),
                getTimestampMs(doc, "createdAt")
        );
    }

    private static UserStatsItem parseUserDocFromRest(
            @NonNull FirestoreRestClient.RestDocument doc,
            boolean isDashboardAdmin) {
        String uid = nonNull(doc.getString("userId"), doc.getId());
        String displayName = nonNull(doc.getString("displayName"), "User");
        String email = nonNull(doc.getString("email"), "");
        int proxyCount = doc.getInt("proxyCount");
        int workingCount = doc.getInt("workingProxyCount");
        int syncCount = doc.getInt("syncCount");
        boolean firewall = Boolean.TRUE.equals(doc.getBoolean("firewallEnabled"));
        boolean privateRouting = Boolean.TRUE.equals(doc.getBoolean("privateRoutingEnabled"));
        String device = nonNull(doc.getString("lastDeviceModel"), "");
        String appVersion = nonNull(doc.getString("appVersion"), "");

        long lastSyncMs = doc.getTimestampMs("lastSyncAt");
        long updatedMs = doc.getTimestampMs("updatedAt");
        long lastActiveMs = Math.max(lastSyncMs, updatedMs);

        return new UserStatsItem(
                uid,
                displayName,
                email,
                proxyCount,
                workingCount,
                syncCount,
                firewall,
                privateRouting,
                device,
                appVersion,
                lastActiveMs,
                isDashboardAdmin
        );
    }

    private static SyncEventItem parseEventDocFromRest(@NonNull FirestoreRestClient.RestDocument doc) {
        return new SyncEventItem(
                doc.getId(),
                nonNull(doc.getString("userLabel"), "User"),
                nonNull(doc.getString("deviceModel"), ""),
                doc.getInt("uploadedProxies"),
                doc.getInt("downloadedProxies"),
                doc.getInt("totalProxies"),
                nonNull(doc.getString("appVersion"), ""),
                doc.getTimestampMs("createdAt")
        );
    }

    private static int getInt(@NonNull DocumentSnapshot doc, @NonNull String key) {
        Object val = doc.get(key);
        return val instanceof Number ? Math.max(0, ((Number) val).intValue()) : 0;
    }

    private static long getTimestampMs(@NonNull DocumentSnapshot doc, @NonNull String key) {
        Timestamp ts = doc.getTimestamp(key, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE);
        return ts != null ? ts.toDate().getTime() : 0L;
    }

    @NonNull
    private static String nonNull(@Nullable String val, @NonNull String fallback) {
        return val != null && !val.trim().isEmpty() ? val.trim() : fallback;
    }
}
