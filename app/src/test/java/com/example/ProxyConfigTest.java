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

    @Test
    public void parseProtocolPrefixes_setsExpectedProtocolAndAuth() {
        ProxyConfig socks5 = ProxyConfig.parseFromLine("socks5://alice:pw@10.0.0.1:1080");
        assertNotNull(socks5);
        assertEquals(ProxyConfig.Protocol.SOCKS5, socks5.getProtocol());
        assertEquals(ProxyConfig.AuthMethod.USER_PASS, socks5.getAuthMethod());
        assertTrue(socks5.isSocks5Protocol());

        ProxyConfig httpTls = ProxyConfig.parseFromLine("http+tls://proxy.host:8443");
        assertNotNull(httpTls);
        assertEquals(ProxyConfig.Protocol.HTTP_TLS, httpTls.getProtocol());
        assertEquals(ProxyConfig.AuthMethod.NONE, httpTls.getAuthMethod());
        assertTrue(httpTls.isTlsProtocol());

        ProxyConfig socks5Udp = ProxyConfig.parseFromLine("socks5+udp://10.0.0.2:1080");
        assertNotNull(socks5Udp);
        assertEquals(ProxyConfig.Protocol.SOCKS5_UDP, socks5Udp.getProtocol());
        assertTrue(socks5Udp.isSocks5Protocol());

        ProxyConfig socks5Tls = ProxyConfig.parseFromLine("sock5+tls://u:p@10.0.0.3:8443");
        assertNotNull(socks5Tls);
        assertEquals(ProxyConfig.Protocol.SOCKS5_TLS, socks5Tls.getProtocol());
        assertTrue(socks5Tls.isSocks5Protocol());
        assertTrue(socks5Tls.isTlsProtocol());
    }

    @Test
    public void resolvedDnsServers_matchesModeAndCustomValidation() {
        ProxyConfig cfg = new ProxyConfig();
        cfg.setDnsMode(ProxyConfig.DnsMode.PROXY);
        assertTrue(cfg.getResolvedDnsServers().isEmpty());

        cfg.setDnsMode(ProxyConfig.DnsMode.CLOUDFLARE);
        assertEquals(java.util.Arrays.asList("1.1.1.1", "1.0.0.1"), cfg.getResolvedDnsServers());

        cfg.setDnsMode(ProxyConfig.DnsMode.GOOGLE);
        assertEquals(java.util.Arrays.asList("8.8.8.8", "8.8.4.4"), cfg.getResolvedDnsServers());

        cfg.setDnsMode(ProxyConfig.DnsMode.CUSTOM);
        cfg.setCustomDns("9.9.9.9, 149.112.112.112");
        assertEquals(java.util.Arrays.asList("9.9.9.9", "149.112.112.112"), cfg.getResolvedDnsServers());

        assertTrue(ProxyConfig.isValidCustomDnsInput("9.9.9.9"));
        assertTrue(ProxyConfig.isValidCustomDnsInput("2606:4700:4700::1111"));
        assertFalse(ProxyConfig.isValidCustomDnsInput(""));
        assertFalse(ProxyConfig.isValidCustomDnsInput("999.999.999.999"));
        assertFalse(ProxyConfig.isValidCustomDnsInput("not-an-ip"));
    }

    @Test
    public void parseAllProtocols_andAutoPrefix_setsProtocolAll() {
        assertEquals(ProxyConfig.Protocol.ALL, ProxyConfig.Protocol.fromString("ALL"));
        assertEquals(ProxyConfig.Protocol.ALL, ProxyConfig.Protocol.fromString("AUTO"));
        assertEquals(ProxyConfig.Protocol.ALL, ProxyConfig.Protocol.fromString("Tất cả giao thức"));
        assertEquals(ProxyConfig.Protocol.ALL, ProxyConfig.Protocol.fromString("All Protocols"));
        assertTrue(ProxyConfig.Protocol.ALL.isAutoDetect());

        ProxyConfig parsed = ProxyConfig.parseFromLine("auto://u1:p1@198.51.100.10:1080");
        assertNotNull(parsed);
        assertEquals(ProxyConfig.Protocol.ALL, parsed.getProtocol());
        assertEquals("198.51.100.10", parsed.getHost());
        assertEquals(1080, parsed.getPort());
        assertTrue(parsed.hasAuth());
    }
}
