package com.example;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.auth.AdminConfig;
import com.example.auth.AdminConfig.RbacAccess;
import com.example.data.AdminStatsRepository;
import com.example.data.AdminStatsRepository.AdminDashboardData;
import com.example.data.AdminStatsRepository.SyncEventItem;
import com.example.data.AdminStatsRepository.UserStatsItem;
import com.example.data.CloudSyncManager;
import com.example.model.ProxyConfig;

import org.junit.After;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class AdminAndSyncLogicTest {

    @After
    public void tearDown() {
        AdminConfig.clearCache();
    }

    @Test
    public void rbacAccess_evaluatesFirestoreRolesAndGranularPermissionsWithoutHardcoding() {
        RbacAccess admin = RbacAccess.fromRaw("uid_admin", "ADMIN", Collections.emptyList());
        assertTrue(admin.isAdmin());
        assertTrue(admin.canAccessAdminDashboard());
        assertTrue(admin.hasPermission(AdminConfig.PERM_ADMIN_USERS_LIST));
        assertTrue(admin.hasPermission(AdminConfig.PERM_ADMIN_ROLES_MANAGE));

        RbacAccess moderator = RbacAccess.fromRaw(
                "uid_mod",
                "moderator",
                Arrays.asList(
                        AdminConfig.PERM_ADMIN_DASHBOARD_VIEW,
                        AdminConfig.PERM_ADMIN_USERS_LIST,
                        AdminConfig.PERM_ADMIN_SYNC_EVENTS_LIST
                )
        );
        assertFalse(moderator.isAdmin());
        assertTrue(moderator.canAccessAdminDashboard());
        assertTrue(moderator.hasPermission(AdminConfig.PERM_ADMIN_USERS_LIST));
        assertFalse(moderator.hasPermission(AdminConfig.PERM_ADMIN_ROLES_MANAGE));

        RbacAccess regularUser = RbacAccess.fromRaw("uid_user", "user", Collections.emptyList());
        assertFalse(regularUser.isAdmin());
        assertFalse(regularUser.canAccessAdminDashboard());
        assertFalse(regularUser.hasPermission(AdminConfig.PERM_ADMIN_DASHBOARD_VIEW));

        RbacAccess inactiveAdmin = RbacAccess.fromRaw("uid_inactive_admin", "admin", false, Collections.emptyList());
        assertFalse(inactiveAdmin.isActive());
        assertFalse(inactiveAdmin.isAdmin());
        assertFalse(inactiveAdmin.canAccessAdminDashboard());
        assertFalse(inactiveAdmin.hasPermission(AdminConfig.PERM_ADMIN_DASHBOARD_VIEW));
        assertFalse(inactiveAdmin.hasPermission(AdminConfig.PERM_ADMIN_ROLES_MANAGE));

        RbacAccess missingRole = RbacAccess.none("uid_none");
        assertFalse(missingRole.canAccessAdminDashboard());
    }

    @Test
    public void adminConfig_cachesAndClearsDynamicRbacRoles() {
        AdminConfig.clearCache();
        assertFalse(AdminConfig.getCachedAccess("uid_dynamic_1").canAccessAdminDashboard());

        AdminConfig.seedAccessForTesting(
                "uid_dynamic_1",
                AdminConfig.ROLE_ADMIN,
                Collections.singletonList(AdminConfig.PERM_ADMIN_DASHBOARD_VIEW)
        );
        assertTrue(AdminConfig.getCachedAccess("uid_dynamic_1").canAccessAdminDashboard());

        AdminConfig.seedAccessForTesting(
                "uid_dynamic_inactive",
                AdminConfig.ROLE_ADMIN,
                false,
                Collections.singletonList(AdminConfig.PERM_ADMIN_DASHBOARD_VIEW)
        );
        assertFalse(AdminConfig.getCachedAccess("uid_dynamic_inactive").isAdmin());
        assertFalse(AdminConfig.getCachedAccess("uid_dynamic_inactive").canAccessAdminDashboard());

        AdminConfig.clearCache();
        assertFalse(AdminConfig.getCachedAccess("uid_dynamic_1").canAccessAdminDashboard());
    }

    @Test
    public void adminStatsRepository_aggregatesUsersAndSyncEventsAccurately() {
        long now = 1_750_000_000_000L;
        List<UserStatsItem> users = Arrays.asList(
                new UserStatsItem(
                        "rbac_admin_1", "Admin", "a@b.com",
                        5, 4, 3, true, true, "Pixel 8", "3.6", now - 3600_000L, true),
                new UserStatsItem(
                        "user_2", "User 2", "u2@b.com",
                        2, 1, 1, false, false, "Galaxy S24", "3.6", now - 100_000_000L, false)
        );
        List<SyncEventItem> events = Collections.singletonList(
                new SyncEventItem("evt_1", "Admin", "Pixel 8", 2, 1, 5, "3.6", now - 1800_000L)
        );

        AdminDashboardData data = AdminStatsRepository.aggregateStats(users, events, now);

        assertEquals(2, data.getTotalUsers());
        assertEquals(1, data.getActiveUsers24h());
        assertEquals(7, data.getTotalSyncedProxies());
        assertEquals(5, data.getTotalWorkingProxies());
        assertEquals(4, data.getTotalSyncRuns());
        assertEquals(1, data.getFirewallEnabledUsers());
        assertEquals(1, data.getPrivateRoutingUsers());
        assertEquals(1, data.getSyncEvents24h());
        assertEquals("rbac_admin_1", data.getUsers().get(0).getUid());
    }

    @Test
    public void cloudSyncManager_buildsValidDeterministicProxyDocKey() {
        ProxyConfig p = new ProxyConfig();
        p.setProtocol(ProxyConfig.Protocol.SOCKS5);
        p.setHost("proxy.example.com");
        p.setPort(1080);

        String key1 = CloudSyncManager.buildProxyDocKey(p);
        String key2 = CloudSyncManager.buildProxyDocKey(p);

        assertEquals(key1, key2);
        assertTrue(key1.length() <= 128);
        assertTrue(key1.matches("^[a-zA-Z0-9_\\-]+$"));
    }
}
