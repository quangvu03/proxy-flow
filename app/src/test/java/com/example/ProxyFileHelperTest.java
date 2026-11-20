package com.example;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.example.data.ProxyFileHelper;
import com.example.model.ProxyConfig;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

public class ProxyFileHelperTest {

    @Test
    public void readAndCount_handlesBomCommentsAndMultipleFormats() throws Exception {
        String sampleTxt = "\uFEFF# Proxy list exported\n"
                + "103.10.20.1:8080:user1:pass1\n"
                + "http://user2:pass2@45.77.10.2:3128\n"
                + "192.168.1.50:9000\n"
                + "invalid_line_here\n"
                + "\n";

        ByteArrayInputStream in = new ByteArrayInputStream(sampleTxt.getBytes(StandardCharsets.UTF_8));
        String read = ProxyFileHelper.readTextFromStream(in);

        assertEquals(3, ProxyFileHelper.countValidProxyLines(read));

        ProxyConfig first = ProxyConfig.parseFromLine("\uFEFF103.10.20.1:8080:user1:pass1");
        assertNotNull(first);
        assertEquals("103.10.20.1", first.getHost());
        assertEquals(8080, first.getPort());
        assertEquals("user1", first.getUsername());
        assertEquals("pass1", first.getPassword());
    }

    @Test
    public void exportAndRoundTrip_preservesAllProxies() throws Exception {
        ProxyConfig p1 = new ProxyConfig();
        p1.setHost("103.10.20.1");
        p1.setPort(8080);
        p1.setUsername("alice");
        p1.setPassword("secret123");

        ProxyConfig p2 = new ProxyConfig();
        p2.setHost("45.77.10.2");
        p2.setPort(3128);

        List<ProxyConfig> list = Arrays.asList(p1, p2);
        String exported = ProxyFileHelper.formatExportContent(list);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ProxyFileHelper.writeTextToStream(out, exported);

        String reread = ProxyFileHelper.readTextFromStream(new ByteArrayInputStream(out.toByteArray()));
        assertEquals("103.10.20.1:8080:alice:secret123\n45.77.10.2:3128", reread);
        assertEquals(2, ProxyFileHelper.countValidProxyLines(reread));
    }

    @Test
    public void defaultExportFileName_isValidTxtName() {
        String name = ProxyFileHelper.buildDefaultExportFileName();
        assertTrue(name.startsWith("proxyflow_proxies_"));
        assertTrue(name.endsWith(".txt"));
    }
}
