package com.example;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.example.data.ProxyDatabaseHelper;
import com.example.data.SettingsPreferences;
import com.example.model.FirewallRule;
import com.example.network.PrivateNetworkMatcher;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Unit tests covering FirewallRule persistence (including seeded "Quy tắc 1"),
 * Firewall/Private routing SQLite preferences, and PrivateNetworkMatcher CIDR logic.
 */
@RunWith(RobolectricTestRunner.class)
public class FirewallAndPrivateRoutingTest {

    @Test
    public void firewallRules_seededWithRule1AndCrudWorks() {
        Context context = ApplicationProvider.getApplicationContext();
        ProxyDatabaseHelper db = ProxyDatabaseHelper.getInstance(context);

        List<FirewallRule> initial = db.getAllFirewallRules();
        assertFalse(initial.isEmpty());
        assertEquals("Quy tắc 1", initial.get(0).getName());

        Set<String> pkgs = new LinkedHashSet<>(Arrays.asList("com.example.browser", "com.example.chat"));
        FirewallRule rule2 = new FirewallRule(0L, "Quy tắc 2", true, pkgs, "ads.example.com:443", 123456L);
        long id = db.insertFirewallRule(rule2);
        assertTrue(id > 0);

        List<FirewallRule> afterInsert = db.getAllFirewallRules();
        FirewallRule loaded = null;
        for (FirewallRule r : afterInsert) {
            if (r.getId() == id) {
                loaded = r;
                break;
            }
        }
        assertNotNull(loaded);
        assertEquals("Quy tắc 2", loaded.getName());
        assertTrue(loaded.isEnabled());
        assertTrue(loaded.appliesToPackage("com.example.browser"));
        assertTrue(loaded.appliesToPackage("com.example.chat"));
        assertTrue(loaded.matchesTarget("sub.ads.example.com", 443));
        assertFalse(loaded.matchesTarget("sub.ads.example.com", 80));

        db.setFirewallRuleEnabled(id, false);
        for (FirewallRule r : db.getAllFirewallRules()) {
            if (r.getId() == id) {
                assertFalse(r.isEnabled());
            }
        }

        db.deleteFirewallRule(id);
        for (FirewallRule r : db.getAllFirewallRules()) {
            assertFalse(r.getId() == id);
        }
    }

    @Test
    public void firewallAndPrivateRoutingPreferences_persistInSqlite() {
        Context context = ApplicationProvider.getApplicationContext();
        ProxyDatabaseHelper db = ProxyDatabaseHelper.getInstance(context);
        db.deleteSetting(SettingsPreferences.SQLITE_KEY_FIREWALL_ENABLED);
        db.deleteSetting(SettingsPreferences.SQLITE_KEY_PRIVATE_ROUTING_ENABLED);
        db.deleteSetting(SettingsPreferences.SQLITE_KEY_PRIVATE_ROUTING_RANGES);

        SettingsPreferences prefs = new SettingsPreferences(context);
        assertFalse(prefs.isFirewallEnabled());
        assertFalse(prefs.isPrivateNetworkRoutingEnabled());
        assertFalse(prefs.getPrivateNetworkRangesList().isEmpty());

        prefs.setFirewallEnabled(true);
        assertTrue(prefs.isFirewallEnabled());

        prefs.setPrivateNetworkRoutingEnabled(true);
        assertTrue(prefs.isPrivateNetworkRoutingEnabled());

        prefs.setPrivateNetworkRangesText("192.168.10.0/24\n10.20.30.40");
        List<String> parsed = prefs.getPrivateNetworkRangesList();
        assertEquals(2, parsed.size());
        assertEquals("192.168.10.0/24", parsed.get(0));
        assertEquals("10.20.30.40", parsed.get(1));
    }

    @Test
    public void privateNetworkMatcher_validatesAndMatchesCidrs() {
        assertNull(PrivateNetworkMatcher.findFirstInvalidEntry("192.168.0.0/16\n10.0.0.1\n172.16.0.0/12"));
        assertEquals("999.1.1.1", PrivateNetworkMatcher.findFirstInvalidEntry("10.0.0.0/8\n999.1.1.1"));
        assertEquals("192.168.1.0/33", PrivateNetworkMatcher.findFirstInvalidEntry("192.168.1.0/33"));

        List<String> custom = Arrays.asList("198.51.100.0/24", "203.0.113.42");
        assertTrue(PrivateNetworkMatcher.isPrivateOrConfiguredTarget("192.168.1.55", custom));
        assertTrue(PrivateNetworkMatcher.isPrivateOrConfiguredTarget("10.8.0.2", custom));
        assertTrue(PrivateNetworkMatcher.isPrivateOrConfiguredTarget("172.20.14.2", custom));
        assertTrue(PrivateNetworkMatcher.isPrivateOrConfiguredTarget("198.51.100.88", custom));
        assertTrue(PrivateNetworkMatcher.isPrivateOrConfiguredTarget("203.0.113.42", custom));
        assertFalse(PrivateNetworkMatcher.isPrivateOrConfiguredTarget("8.8.8.8", custom));
        assertFalse(PrivateNetworkMatcher.isPrivateOrConfiguredTarget("1.1.1.1", custom));
    }
}
