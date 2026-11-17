package com.example;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.model.ProxyConfig;

import org.junit.Test;

/**
 * Pure Java unit tests for ProxyConfig parsing, validation, and Basic auth encoding.
 */
public class ProxyConfigTest {

    @Test
    public void parseHostPortUserPass_succeeds() {
        ProxyConfig cfg = ProxyConfig.parseFromLine("103.15.22.4:8080:myuser:mypass123");
        assertNotNull(cfg);
        assertEquals("103.15.22.4", cfg.getHost());
        assertEquals(8080, cfg.getPort());
        assertEquals("myuser", cfg.getUsername());
        assertEquals("mypass123", cfg.getPassword());
        assertTrue(cfg.hasAuth());
        assertEquals("103.15.22.4:8080:myuser:mypass123", cfg.toExportLine());
    }

    @Test
    public void parseHttpUrlFormat_succeeds() {
        ProxyConfig cfg = ProxyConfig.parseFromLine("http://alice:s3cr3t@proxy.example.com:3128/");
        assertNotNull(cfg);
        assertEquals("proxy.example.com", cfg.getHost());
        assertEquals(3128, cfg.getPort());
        assertEquals("alice", cfg.getUsername());
        assertEquals("s3cr3t", cfg.getPassword());
    }

    @Test
    public void parseHostPortOnly_succeedsWithoutAuth() {
        ProxyConfig cfg = ProxyConfig.parseFromLine("192.168.1.50:8888");
        assertNotNull(cfg);
        assertEquals("192.168.1.50", cfg.getHost());
        assertEquals(8888, cfg.getPort());
        assertFalse(cfg.hasAuth());
        assertEquals("192.168.1.50:8888", cfg.toExportLine());
    }

    @Test
    public void parseInvalidPort_returnsNull() {
        assertNull(ProxyConfig.parseFromLine("1.2.3.4:99999:u:p"));
        assertNull(ProxyConfig.parseFromLine("1.2.3.4:abc"));
        assertNull(ProxyConfig.parseFromLine("   "));
    }

    @Test
    public void basicAuthToken_matchesStandardBase64() {
        ProxyConfig cfg = new ProxyConfig();
        cfg.setHost("127.0.0.1");
        cfg.setPort(8080);
        cfg.setUsername("Aladdin");
        cfg.setPassword("open sesame");
        assertEquals("QWxhZGRpbjpvcGVuIHNlc2FtZQ==", cfg.getBasicAuthToken());
    }
}
