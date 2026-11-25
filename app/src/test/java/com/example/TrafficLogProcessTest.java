package com.example;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.example.data.ProxyDatabaseHelper;
import com.example.data.ProxyLogFormatter;
import com.example.data.SettingsPreferences;
import com.example.model.ProxyConfig;
import com.example.model.ProxyLogEntry;
import com.example.network.LocalProxyServer;
import com.example.network.TunPacketParser;
import com.example.ui.LogListAdapter;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Verifies raw TUN packet DNS/TCP/UDP parsing, full LocalProxyServer send lifecycle logging,
 * SQLite v5 persistence of packageName/eventTag, LogListAdapter app icon + yyyy-MM-dd HH:mm:ss.SSS
 * timestamp rendering, and VI/EN localization of traffic logs.
 */
@RunWith(RobolectricTestRunner.class)
public class TrafficLogProcessTest {

    @After
    public void resetLang() {
        Context context = ApplicationProvider.getApplicationContext();
        SettingsPreferences prefs = new SettingsPreferences(context);
        prefs.setAppLanguage(SettingsPreferences.AppLanguage.SYSTEM);
        SettingsPreferences.applyLanguage(SettingsPreferences.AppLanguage.SYSTEM);
    }

    @Test
    public void tunPacketParser_parsesDnsQueryTcpSynAndUdpPacket() throws Exception {
        byte[] dnsPacket = buildIpv4UdpDnsQueryPacket("play-fe.googleapis.com", 1);
        TunPacketParser.ParsedPacket parsedDns = TunPacketParser.parse(dnsPacket, dnsPacket.length);
        assertNotNull(parsedDns);
        assertEquals(TunPacketParser.Kind.DNS_QUERY, parsedDns.getKind());
        assertEquals("A", parsedDns.getDnsRecordType());
        assertEquals("play-fe.googleapis.com", parsedDns.getDnsDomain());
        assertEquals(53, parsedDns.getDstPort());

        byte[] dnsAaaa = buildIpv4UdpDnsQueryPacket("dns.google", 28);
        TunPacketParser.ParsedPacket parsedAaaa = TunPacketParser.parse(dnsAaaa, dnsAaaa.length);
        assertNotNull(parsedAaaa);
        assertEquals("AAAA", parsedAaaa.getDnsRecordType());
        assertEquals("dns.google", parsedAaaa.getDnsDomain());

        byte[] tcpSyn = buildIpv4TcpSynPacket();
        TunPacketParser.ParsedPacket parsedTcp = TunPacketParser.parse(tcpSyn, tcpSyn.length);
        assertNotNull(parsedTcp);
        assertEquals(TunPacketParser.Kind.TCP_SYN, parsedTcp.getKind());
        assertEquals("142.250.66.78", parsedTcp.getDstIp());
        assertEquals(443, parsedTcp.getDstPort());
    }

    @Test
    public void localProxyServer_emitsFullSendLifecycleFromDnsToTransferCompletion() throws Exception {
        try (ServerSocket upstreamServer = new ServerSocket(0)) {
            int upstreamPort = upstreamServer.getLocalPort();
            Thread upstreamThread = new Thread(() -> {
                try (Socket s = upstreamServer.accept()) {
                    BufferedReader br = new BufferedReader(
                            new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
                    String line;
                    while ((line = br.readLine()) != null && !line.isEmpty()) {
                        // consume headers
                    }
                    OutputStream out = s.getOutputStream();
                    out.write("HTTP/1.1 200 Connection Established\r\n\r\n"
                            .getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                    // Read client payload and echo back
                    byte[] buf = new byte[64];
                    int n = s.getInputStream().read(buf);
                    if (n > 0) {
                        out.write(buf, 0, n);
                        out.flush();
                    }
                } catch (Exception ignored) {
                }
            });
            upstreamThread.setDaemon(true);
            upstreamThread.start();

            ProxyConfig proxy = new ProxyConfig();
            proxy.setId(7L);
            proxy.setProtocol(ProxyConfig.Protocol.HTTP);
            proxy.setHost("127.0.0.1");
            proxy.setPort(upstreamPort);

            List<String> events = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch completedLatch = new CountDownLatch(1);

            LocalProxyServer server = new LocalProxyServer();
            server.setTrafficListener(new LocalProxyServer.TrafficListener() {
                @Override
                public String resolveCallerPackage(Socket clientSocket) {
                    return "com.example.caller";
                }

                @Override
                public void onDnsDomainRequested(String callerPackage, String domain) {
                    events.add("DNS:" + callerPackage + ":" + domain);
                }

                @Override
                public void onSendStarted(String callerPackage, String methodTag, String target, ProxyConfig p) {
                    events.add("SEND:" + callerPackage + ":" + methodTag + ":" + target);
                }

                @Override
                public void onTunnelEstablished(String callerPackage, String methodTag, String target, long handshakeMs) {
                    events.add("TUNNEL:" + callerPackage + ":" + methodTag + ":" + target);
                }

                @Override
                public void onTransferCompleted(String callerPackage, String target,
                                                long sentBytes, long recvBytes, long durationMs) {
                    events.add("DONE:" + callerPackage + ":" + target + ":" + sentBytes + ":" + recvBytes);
                    completedLatch.countDown();
                }

                @Override
                public void onSendFailed(String callerPackage, String methodTag, String target, String reason) {
                    events.add("FAIL:" + reason);
                }

                @Override
                public void onRequestBlocked(String callerPackage, LocalProxyServer.PolicyResult reason, String target) {
                    events.add("BLOCK:" + reason);
                }
            });

            int localPort = server.start(proxy);
            try {
                try (Socket client = new Socket("127.0.0.1", localPort)) {
                    OutputStream out = client.getOutputStream();
                    out.write("CONNECT play-fe.googleapis.com:443 HTTP/1.1\r\nHost: play-fe.googleapis.com:443\r\n\r\n"
                            .getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                    BufferedReader br = new BufferedReader(
                            new InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1));
                    String status = br.readLine();
                    assertTrue(status != null && status.contains("200"));
                    // Consume blank header line
                    br.readLine();
                    out.write("ping".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    client.shutdownOutput();
                    // Read echoed payload
                    char[] echo = new char[4];
                    int r = br.read(echo);
                    assertEquals(4, r);
                }
                assertTrue(completedLatch.await(3, TimeUnit.SECONDS));
                assertEquals(4, events.size());
                assertEquals("DNS:com.example.caller:play-fe.googleapis.com", events.get(0));
                assertEquals("SEND:com.example.caller:HTTPS:play-fe.googleapis.com:443", events.get(1));
                assertEquals("TUNNEL:com.example.caller:HTTPS:play-fe.googleapis.com:443", events.get(2));
                assertTrue(events.get(3).startsWith("DONE:com.example.caller:play-fe.googleapis.com:443:"));
            } finally {
                server.stop();
            }
        }
    }

    @Test
    public void databaseAndLogListAdapter_persistPackageTagAndRenderAppIconAndMillisTimestamp() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        context.setTheme(R.style.Theme_ProxyFlow);

        ProxyDatabaseHelper db = new ProxyDatabaseHelper(context);
        db.clearLogs();
        db.insertLog(ProxyLogEntry.Level.INFO, "DNS A play-fe.googleapis.com", context.getPackageName(), "DNS");
        List<ProxyLogEntry> stored = db.getRecentLogs();
        assertEquals(1, stored.size());
        assertEquals(context.getPackageName(), stored.get(0).getPackageName());
        assertEquals("DNS", stored.get(0).getEventTag());

        SimpleDateFormat isoMillis = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
        Date parsedTime = isoMillis.parse("2026-11-24 21:06:56.778");
        assertNotNull(parsedTime);
        ProxyLogEntry entry = new ProxyLogEntry(
                99L,
                parsedTime.getTime(),
                ProxyLogEntry.Level.INFO,
                "DNS A play-fe.googleapis.com",
                context.getPackageName(),
                "DNS"
        );

        LogListAdapter adapter = new LogListAdapter();
        adapter.submitList(Collections.singletonList(entry));

        FrameLayout parent = new FrameLayout(context);
        LogListAdapter.LogViewHolder holder = adapter.onCreateViewHolder(parent, 0);
        adapter.onBindViewHolder(holder, 0);

        ImageView ivIcon = holder.itemView.findViewById(R.id.iv_log_app_icon);
        TextView tvTime = holder.itemView.findViewById(R.id.tv_log_time);
        TextView tvLevel = holder.itemView.findViewById(R.id.tv_log_level);
        TextView tvAppName = holder.itemView.findViewById(R.id.tv_log_app_name);
        TextView tvMessage = holder.itemView.findViewById(R.id.tv_log_message);

        assertNotNull(ivIcon.getDrawable());
        assertEquals(isoMillis.format(parsedTime), tvTime.getText().toString());
        assertEquals("DNS", tvLevel.getText().toString());
        assertEquals(View.VISIBLE, tvAppName.getVisibility());
        assertEquals("DNS A play-fe.googleapis.com", tvMessage.getText().toString());
    }

    @Test
    public void proxyLogFormatter_translatesTrafficSendProcessBetweenViAndEn() {
        Context context = ApplicationProvider.getApplicationContext();
        SettingsPreferences prefs = new SettingsPreferences(context);

        prefs.setAppLanguage(SettingsPreferences.AppLanguage.EN);
        assertEquals(
                "Send HTTPS -> play-fe.googleapis.com:443 via http://1.2.3.4:8080",
                ProxyLogFormatter.localizeForDisplay(
                        context,
                        "Gửi HTTPS -> play-fe.googleapis.com:443 qua http://1.2.3.4:8080")
        );
        assertEquals(
                "Completed play-fe.googleapis.com:443 • Sent ↑ 512 B • Recv ↓ 2.0 KB (45 ms)",
                ProxyLogFormatter.localizeForDisplay(
                        context,
                        "Hoàn tất play-fe.googleapis.com:443 • Gửi ↑ 512 B • Nhận ↓ 2.0 KB (45 ms)")
        );

        prefs.setAppLanguage(SettingsPreferences.AppLanguage.VI);
        assertEquals(
                "Đã mở kết nối HTTPS -> play-fe.googleapis.com:443 (18 ms)",
                ProxyLogFormatter.localizeForDisplay(
                        context,
                        "Tunnel opened HTTPS -> play-fe.googleapis.com:443 (18 ms)")
        );
    }

    private static byte[] buildIpv4UdpDnsQueryPacket(String domain, int qtype) throws Exception {
        ByteArrayOutputStream dns = new ByteArrayOutputStream();
        // Transaction ID + Flags (standard query) + QDCOUNT=1 + AN/NS/AR=0
        dns.write(new byte[]{0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00});
        for (String label : domain.split("\\.")) {
            byte[] bytes = label.getBytes(StandardCharsets.UTF_8);
            dns.write(bytes.length);
            dns.write(bytes);
        }
        dns.write(0x00); // root terminator
        dns.write((qtype >> 8) & 0xFF);
        dns.write(qtype & 0xFF);
        dns.write(0x00);
        dns.write(0x01); // QCLASS IN

        byte[] dnsBytes = dns.toByteArray();
        int udpLen = 8 + dnsBytes.length;
        int totalLen = 20 + udpLen;

        byte[] pkt = new byte[totalLen];
        pkt[0] = 0x45; // IPv4, IHL=5 (20 bytes)
        pkt[2] = (byte) ((totalLen >> 8) & 0xFF);
        pkt[3] = (byte) (totalLen & 0xFF);
        pkt[8] = 64; // TTL
        pkt[9] = 17; // UDP
        // src 10.0.0.2
        pkt[12] = 10; pkt[13] = 0; pkt[14] = 0; pkt[15] = 2;
        // dst 1.1.1.1
        pkt[16] = 1; pkt[17] = 1; pkt[18] = 1; pkt[19] = 1;
        // UDP srcPort=45000, dstPort=53
        pkt[20] = (byte) (45000 >> 8); pkt[21] = (byte) (45000 & 0xFF);
        pkt[22] = 0; pkt[23] = 53;
        pkt[24] = (byte) ((udpLen >> 8) & 0xFF);
        pkt[25] = (byte) (udpLen & 0xFF);
        System.arraycopy(dnsBytes, 0, pkt, 28, dnsBytes.length);
        return pkt;
    }

    private static byte[] buildIpv4TcpSynPacket() {
        byte[] pkt = new byte[40];
        pkt[0] = 0x45;
        pkt[2] = 0;
        pkt[3] = 40;
        pkt[8] = 64;
        pkt[9] = 6; // TCP
        pkt[12] = 10; pkt[13] = 0; pkt[14] = 0; pkt[15] = 2;
        pkt[16] = (byte) 142; pkt[17] = (byte) 250; pkt[18] = 66; pkt[19] = 78;
        // TCP srcPort=50000, dstPort=443
        pkt[20] = (byte) (50000 >> 8); pkt[21] = (byte) (50000 & 0xFF);
        pkt[22] = (byte) (443 >> 8); pkt[23] = (byte) (443 & 0xFF);
        pkt[32] = 0x50; // data offset = 5 (20 bytes)
        pkt[33] = 0x02; // SYN flag only
        return pkt;
    }
}
