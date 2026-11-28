package com.example;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.auth.AdminConfig;
import com.example.data.AdminStatsRepository;
import com.example.data.AdminStatsRepository.AdminDashboardData;
import com.example.data.AdminStatsRepository.SyncEventItem;
import com.example.data.AdminStatsRepository.UserStatsItem;
import com.example.data.CloudSyncManager;
import com.example.model.ProxyConfig;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class AdminAndSyncLogicTest {

    @Test
    public void adminConfig_matchesAdminUidsAndVerifiedOwnerEmailOnly() {
        assertTrue(AdminConfig.isAdminUid("xuonHbFXXrPQEJVZPjyJy9itC7x1"));
        assertTrue(AdminConfig.isAdminUid("jACOpeQtRie82vw5S20AUWfZxD53"));
        assertTrue(AdminConfig.isAdmin("new_project_uid", "resetall1st@gmail.com", true));
        assertTrue(AdminConfig.isAdmin("new_project_uid", "  ResetAll1st@gmail.com ", true));
        assertFalse(AdminConfig.isAdmin("new_project_uid", "resetall1st@gmail.com", false));
        assertFalse(AdminConfig.isAdminUid("regular_user_123"));
        assertFalse(AdminConfig.isAdminUid(""));
        assertFalse(AdminConfig.isAdminUid(null));
    }

    @Test
    public void adminStatsRepository_aggregatesUsersAndSyncEventsAccurately() {
        long now = 1_750_000_000_000L;
        List<UserStatsItem> users = Arrays.asList(
                new UserStatsItem(
                        "xuonHbFXXrPQEJVZPjyJy9itC7x1", "Admin", "a@b.com",
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
        assertEquals("xuonHbFXXrPQEJVZPjyJy9itC7x1", data.getUsers().get(0).getUid());
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
