package com.example.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.example.R;
import com.example.auth.AdminConfig;
import com.example.auth.GoogleAuthManager;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
        if (!AdminConfig.isAdmin(currentUser)) {
            postToMain(() -> callback.onError(appCtx.getString(R.string.msg_admin_access_denied)));
            return;
        }

        FirebaseFirestore db = CloudSyncManager.getFirestore(appCtx);
        if (db == null) {
            postToMain(() -> callback.onError(appCtx.getString(R.string.msg_firestore_unavailable)));
            return;
        }

        STATS_EXECUTOR.execute(() -> {
            String currentPath = "users";
            try {
                QuerySnapshot usersSnapshot = Tasks.await(db.collection("users").get());
                List<UserStatsItem> userItems = new ArrayList<>();
                for (DocumentSnapshot doc : usersSnapshot.getDocuments()) {
                    userItems.add(parseUserDoc(doc));
                }

                currentPath = "sync_events";
                QuerySnapshot eventsSnapshot = Tasks.await(
                        db.collection("sync_events")
                                .orderBy("createdAt", Query.Direction.DESCENDING)
                                .limit(30)
                                .get()
                );
                List<SyncEventItem> eventItems = new ArrayList<>();
                for (DocumentSnapshot doc : eventsSnapshot.getDocuments()) {
                    eventItems.add(parseEventDoc(doc));
                }

                AdminDashboardData dashboardData = aggregateStats(
                        userItems,
                        eventItems,
                        System.currentTimeMillis()
                );
                postToMain(() -> callback.onLoaded(dashboardData));
            } catch (Exception e) {
                FirestoreErrorHelper.handleFirestoreError(
                        e,
                        FirestoreErrorHelper.OperationType.LIST,
                        currentPath
                );
                String friendly = FirestoreErrorHelper.getFriendlyErrorMessage(appCtx, e);
                postToMain(() -> callback.onError(friendly));
            }
        });
    }

    @VisibleForTesting
    @NonNull
    public static AdminDashboardData aggregateStats(
            @NonNull List<UserStatsItem> users,
            @NonNull List<SyncEventItem> events,
            long nowMs) {
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
                Collections.unmodifiableList(sortedEvents)
        );
    }

    private static UserStatsItem parseUserDoc(@NonNull DocumentSnapshot doc) {
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
                AdminConfig.isAdmin(uid, email, true)
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
