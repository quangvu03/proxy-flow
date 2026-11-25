package com.example.network;

import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;

/**
 * Pure Java parser for raw IPv4 packets captured on the VPN TUN interface.
 * Extracts DNS queries (record type A/AAAA/HTTPS/etc. and domain name),
 * TCP SYN connection attempts, and UDP outbound packets for full traffic logging.
 */
public final class TunPacketParser {

    public enum Kind {
        DNS_QUERY,
        TCP_SYN,
        UDP_PACKET
    }

    public static final class ParsedPacket {
        private final Kind kind;
        private final int protocol;
        private final String srcIp;
        private final int srcPort;
        private final String dstIp;
        private final int dstPort;
        private final String dnsRecordType;
        private final String dnsDomain;
        private final int payloadBytes;

        ParsedPacket(Kind kind, int protocol, String srcIp, int srcPort,
                     String dstIp, int dstPort, String dnsRecordType,
                     String dnsDomain, int payloadBytes) {
            this.kind = kind;
            this.protocol = protocol;
            this.srcIp = srcIp;
            this.srcPort = srcPort;
            this.dstIp = dstIp;
            this.dstPort = dstPort;
            this.dnsRecordType = dnsRecordType != null ? dnsRecordType : "";
            this.dnsDomain = dnsDomain != null ? dnsDomain : "";
            this.payloadBytes = payloadBytes;
        }

        public Kind getKind() { return kind; }
        public int getProtocol() { return protocol; }
        public String getSrcIp() { return srcIp; }
        public int getSrcPort() { return srcPort; }
        public String getDstIp() { return dstIp; }
        public int getDstPort() { return dstPort; }
        public String getDnsRecordType() { return dnsRecordType; }
        public String getDnsDomain() { return dnsDomain; }
        public int getPayloadBytes() { return payloadBytes; }
    }

    private TunPacketParser() {}

    @Nullable
    public static ParsedPacket parse(byte[] packet, int length) {
        if (packet == null || length < 20 || length > packet.length) {
            return null;
        }
        int version = (packet[0] >> 4) & 0x0F;
        if (version != 4) {
            return null;
        }
        int ihl = (packet[0] & 0x0F) * 4;
        if (ihl < 20 || length < ihl) {
            return null;
        }
        int totalLength = ((packet[2] & 0xFF) << 8) | (packet[3] & 0xFF);
        int boundedLen = totalLength > 0 && totalLength <= length ? totalLength : length;
        int protocol = packet[9] & 0xFF;

        String srcIp = formatIpv4(packet, 12);
        String dstIp = formatIpv4(packet, 16);

        if (protocol == 17) { // UDP
            if (boundedLen < ihl + 8) {
                return null;
            }
            int srcPort = ((packet[ihl] & 0xFF) << 8) | (packet[ihl + 1] & 0xFF);
            int dstPort = ((packet[ihl + 2] & 0xFF) << 8) | (packet[ihl + 3] & 0xFF);
            int udpLen = ((packet[ihl + 4] & 0xFF) << 8) | (packet[ihl + 5] & 0xFF);
            int payloadOffset = ihl + 8;
            int payloadLen = Math.max(0, Math.min(boundedLen, ihl + udpLen) - payloadOffset);

            if (dstPort == 53 && payloadLen >= 12) {
                DnsQuestion q = parseDnsQuestion(packet, payloadOffset, payloadOffset + payloadLen);
                if (q != null) {
                    return new ParsedPacket(Kind.DNS_QUERY, 17, srcIp, srcPort,
                            dstIp, dstPort, q.recordType, q.domain, payloadLen);
                }
            }
            return new ParsedPacket(Kind.UDP_PACKET, 17, srcIp, srcPort,
                    dstIp, dstPort, "", "", payloadLen);
        } else if (protocol == 6) { // TCP
            if (boundedLen < ihl + 20) {
                return null;
            }
            int srcPort = ((packet[ihl] & 0xFF) << 8) | (packet[ihl + 1] & 0xFF);
            int dstPort = ((packet[ihl + 2] & 0xFF) << 8) | (packet[ihl + 3] & 0xFF);
            int flags = packet[ihl + 13] & 0xFF;
            boolean isSyn = (flags & 0x02) != 0;
            boolean isAck = (flags & 0x10) != 0;
            if (isSyn && !isAck) {
                return new ParsedPacket(Kind.TCP_SYN, 6, srcIp, srcPort,
                        dstIp, dstPort, "", "", Math.max(0, boundedLen - ihl - 20));
            }
        }
        return null;
    }

    @Nullable
    private static DnsQuestion parseDnsQuestion(byte[] data, int dnsStart, int dnsEnd) {
        if (dnsEnd - dnsStart < 12) {
            return null;
        }
        int flags = ((data[dnsStart + 2] & 0xFF) << 8) | (data[dnsStart + 3] & 0xFF);
        boolean isResponse = (flags & 0x8000) != 0;
        if (isResponse) {
            return null;
        }
        int qdCount = ((data[dnsStart + 4] & 0xFF) << 8) | (data[dnsStart + 5] & 0xFF);
        if (qdCount < 1) {
            return null;
        }

        int pos = dnsStart + 12;
        StringBuilder domain = new StringBuilder();
        int safety = 0;
        while (pos < dnsEnd && safety++ < 64) {
            int labelLen = data[pos++] & 0xFF;
            if (labelLen == 0) {
                break;
            }
            if ((labelLen & 0xC0) != 0 || pos + labelLen > dnsEnd) {
                return null;
            }
            if (domain.length() > 0) {
                domain.append('.');
            }
            domain.append(new String(data, pos, labelLen, StandardCharsets.UTF_8));
            pos += labelLen;
        }
        if (domain.length() == 0 || pos + 4 > dnsEnd) {
            return null;
        }
        int qtype = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
        return new DnsQuestion(qtypeToString(qtype), domain.toString());
    }

    private static String qtypeToString(int qtype) {
        switch (qtype) {
            case 1: return "A";
            case 2: return "NS";
            case 5: return "CNAME";
            case 12: return "PTR";
            case 15: return "MX";
            case 16: return "TXT";
            case 28: return "AAAA";
            case 33: return "SRV";
            case 65: return "HTTPS";
            case 255: return "ANY";
            default: return "TYPE" + qtype;
        }
    }

    private static String formatIpv4(byte[] buf, int offset) {
        return (buf[offset] & 0xFF) + "."
                + (buf[offset + 1] & 0xFF) + "."
                + (buf[offset + 2] & 0xFF) + "."
                + (buf[offset + 3] & 0xFF);
    }

    private static final class DnsQuestion {
        final String recordType;
        final String domain;

        DnsQuestion(String recordType, String domain) {
            this.recordType = recordType;
            this.domain = domain;
        }
    }
}
