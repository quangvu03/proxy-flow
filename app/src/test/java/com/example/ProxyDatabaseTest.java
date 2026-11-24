package com.example;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.example.data.ProxyDatabaseHelper;
import com.example.model.ProxyConfig;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

/**
 * Robolectric persistence tests verifying SQLite proxy list storage in 100% Java.
 */
@RunWith(RobolectricTestRunner.class)
public class ProxyDatabaseTest {

    @Test
    public void insertQueryAndDeleteProxy_persistsCorrectly() {
        Context context = ApplicationProvider.getApplicationContext();
        ProxyDatabaseHelper db = new ProxyDatabaseHelper(context);

        ProxyConfig p = new ProxyConfig();
        p.setName("SG Node");
        p.setHost("10.20.30.40");
        p.setPort(8080);
        p.setUsername("user1");
        p.setPassword("pass1");

        long id = db.insertProxy(p);
        assertTrue(id > 0);

        List<ProxyConfig> all = db.getAllProxies();
        assertEquals(1, all.size());
        ProxyConfig loaded = db.getProxyById(id);
        assertNotNull(loaded);
        assertEquals("SG Node", loaded.getName());
        assertEquals("10.20.30.40", loaded.getHost());
        assertEquals(8080, loaded.getPort());
        assertEquals("user1", loaded.getUsername());
        assertEquals("pass1", loaded.getPassword());

        db.deleteProxy(id);
        assertTrue(db.getAllProxies().isEmpty());
        db.close();
    }

    @Test
    public void appSettingsTable_persistsAndUpdatesValues() {
        Context context = ApplicationProvider.getApplicationContext();
        ProxyDatabaseHelper db = new ProxyDatabaseHelper(context);

        assertEquals("default", db.getSetting("custom_key", "default"));
        db.putSetting("custom_key", "DARK");
        assertTrue(db.hasSetting("custom_key"));
        assertEquals("DARK", db.getSetting("custom_key", "default"));

        db.putSetting("custom_key", "LIGHT");
        assertEquals("LIGHT", db.getSetting("custom_key", "default"));

        db.deleteSetting("custom_key");
        assertEquals("default", db.getSetting("custom_key", "default"));
        db.close();
    }

    @Test
    public void protocolAuthAndDnsFields_persistAndReloadCorrectly() {
        Context context = ApplicationProvider.getApplicationContext();
        ProxyDatabaseHelper db = new ProxyDatabaseHelper(context);

        ProxyConfig p = new ProxyConfig();
        p.setName("Custom SOCKS5 TLS");
        p.setProtocol(ProxyConfig.Protocol.SOCKS5_TLS);
        p.setHost("198.51.100.77");
        p.setPort(4433);
        p.setAuthMethod(ProxyConfig.AuthMethod.NONE);
        p.setUsername("");
        p.setPassword("");
        p.setDnsMode(ProxyConfig.DnsMode.CUSTOM);
        p.setCustomDns("9.9.9.9");

        long id = db.insertProxy(p);
        assertTrue(id > 0);

        ProxyConfig loaded = db.getProxyById(id);
        assertNotNull(loaded);
        assertEquals("Custom SOCKS5 TLS", loaded.getName());
        assertEquals(ProxyConfig.Protocol.SOCKS5_TLS, loaded.getProtocol());
        assertEquals(ProxyConfig.AuthMethod.NONE, loaded.getAuthMethod());
        assertEquals(ProxyConfig.DnsMode.CUSTOM, loaded.getDnsMode());
        assertEquals("9.9.9.9", loaded.getCustomDns());

        loaded.setProtocol(ProxyConfig.Protocol.SOCKS5_UDP);
        loaded.setAuthMethod(ProxyConfig.AuthMethod.USER_PASS);
        loaded.setUsername("u1");
        loaded.setPassword("p1");
        loaded.setDnsMode(ProxyConfig.DnsMode.CLOUDFLARE);
        loaded.setCustomDns("");
        db.updateProxy(loaded);

        ProxyConfig updated = db.getProxyById(id);
        assertNotNull(updated);
        assertEquals(ProxyConfig.Protocol.SOCKS5_UDP, updated.getProtocol());
        assertEquals(ProxyConfig.AuthMethod.USER_PASS, updated.getAuthMethod());
        assertEquals("u1", updated.getUsername());
        assertEquals("p1", updated.getPassword());
        assertEquals(ProxyConfig.DnsMode.CLOUDFLARE, updated.getDnsMode());

        db.deleteProxy(id);
        db.close();
    }
}
