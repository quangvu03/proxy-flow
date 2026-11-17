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
}
