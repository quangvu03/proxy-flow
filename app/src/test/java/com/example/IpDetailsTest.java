package com.example;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.example.model.IpDetails;
import com.example.network.IpLookupService;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Unit tests for IpDetails comparison, location formatting, and JSON parsing.
 */
@RunWith(RobolectricTestRunner.class)
public class IpDetailsTest {

    @Test
    public void ipComparison_maskedWhenRoutedAndDifferentIpv4() {
        IpDetails origin = new IpDetails("14.161.20.10", "2402:800::1", "Hà Nội", "Hà Nội",
                "Việt Nam", "VN", "VNPT", 21.0285, 105.8542, true, false, "");
        IpDetails current = new IpDetails("103.50.12.88", "", "Singapore", "Singapore",
                "Singapore", "SG", "DigitalOcean", 1.3521, 103.8198, true, true, "");

        IpDetails.IpComparisonResult res = new IpDetails.IpComparisonResult(current, origin, 1000L);
        assertTrue(res.isMasked());
        assertEquals("Hà Nội, Việt Nam", origin.getLocationSummary());
        assertEquals("Singapore, Singapore", current.getLocationSummary());
    }

    @Test
    public void ipComparison_notMaskedWhenDirect() {
        IpDetails origin = new IpDetails("14.161.20.10", "", "TP. Hồ Chí Minh", "Hồ Chí Minh",
                "Việt Nam", "VN", "FPT", 10.7769, 106.7009, true, false, "");
        IpDetails current = origin.copyWithRouted(false);

        IpDetails.IpComparisonResult res = new IpDetails.IpComparisonResult(current, origin, 1000L);
        assertFalse(res.isMasked());
    }

    @Test
    public void parseFirstJsonObject_extractsBodyAroundHttpHeaders() {
        String rawHttp = "HTTP/1.0 200 OK\r\nContent-Type: application/json\r\n\r\n{\"ip\":\"103.1.2.3\"}";
        JSONObject obj = IpLookupService.parseFirstJsonObject(rawHttp);
        assertNotNull(obj);
        assertEquals("103.1.2.3", obj.optString("ip"));
    }
}
