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
 * Unit tests for IpDetails comparison, location formatting, JSON parsing,
 * HTTP/1.1 chunked decoding, and multi-provider geo parsing.
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

    @Test
    public void parseIpInfoJson_extractsAccurateRegionCityIspAndCoordinates() throws Exception {
        JSONObject json = new JSONObject("{"
                + "\"ip\":\"113.161.72.10\","
                + "\"city\":\"Da Nang\","
                + "\"region\":\"Da Nang\","
                + "\"country\":\"VN\","
                + "\"loc\":\"16.0678,108.2208\","
                + "\"org\":\"AS45899 VNPT Corp\""
                + "}");

        IpDetails details = IpLookupService.parseIpInfoToDetails(json, true);
        assertEquals("113.161.72.10", details.getIpv4());
        assertEquals("Da Nang", details.getRegion());
        assertEquals("Da Nang", details.getCity());
        assertEquals("VN", details.getCountryCode());
        assertEquals("VNPT Corp", details.getIsp());
        assertTrue(details.hasCoordinates());
        assertEquals(16.0678, details.getLatitude(), 0.0001);
        assertEquals(108.2208, details.getLongitude(), 0.0001);
    }

    @Test
    public void decodeHttpResponseBody_decodesChunkedTransferEncoding() {
        String raw = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/json\r\n"
                + "Transfer-Encoding: chunked\r\n\r\n"
                + "11\r\n"
                + "{\"ip\":\"1.2.3.4\"}\n\r\n"
                + "0\r\n\r\n";
        String body = IpLookupService.decodeHttpResponseBody(raw);
        assertEquals("{\"ip\":\"1.2.3.4\"}", body);
    }

    @Test
    public void isValidIpv4_rejectsIpv6AndInvalidStrings() {
        assertTrue(IpLookupService.isValidIpv4("14.161.20.10"));
        assertFalse(IpLookupService.isValidIpv4("2402:800::1"));
        assertFalse(IpLookupService.isValidIpv4("999.1.2.3"));
        assertFalse(IpLookupService.isValidIpv4(""));
    }
}
