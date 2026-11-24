package com.example;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.example.network.ProxySpeedTester;

import org.junit.Test;

/**
 * Pure Java unit tests for ProxySpeedTester throughput calculation and invalid input handling.
 */
public class ProxySpeedTesterTest {

    @Test
    public void calculateMbps_computesAccurateMegabitsPerSecond() {
        // 500,000 bytes (4,000,000 bits) in 0.5 seconds = 8.0 Mbps
        double mbps = ProxySpeedTester.calculateMbps(500_000L, 500_000_000L);
        assertEquals(8.0, mbps, 0.0001);
    }

    @Test
    public void calculateMbps_handlesZeroGracefully() {
        assertEquals(0.0, ProxySpeedTester.calculateMbps(0L, 1_000_000_000L), 0.0);
        assertEquals(0.0, ProxySpeedTester.calculateMbps(1024L, 0L), 0.0);
    }

    @Test
    public void runDiagnostics_nullProxyReturnsUnusable() {
        ProxySpeedTester.DiagnosticResult result = ProxySpeedTester.runDiagnostics(null);
        assertFalse(result.isUsable());
        assertFalse(result.isTcpReachable());
    }

    @Test
    public void speedResult_preservesJitterAndRejectsInvalidProxy() {
        ProxySpeedTester.SpeedResult directSample =
                new ProxySpeedTester.SpeedResult(true, 28L, 6L, 42.5, 18.2, 524288L, 131072L, "");
        assertEquals(6L, directSample.getJitterMs());
        assertEquals(28L, directSample.getAvgPingMs());

        ProxySpeedTester.SpeedResult invalidProxy = ProxySpeedTester.measureSpeed(null);
        assertFalse(invalidProxy.isSuccess());
        assertEquals(0L, invalidProxy.getJitterMs());
    }
}
