package com.example.model;

/**
 * Geolocation and dual-stack IPv4/IPv6 snapshot for either the device's original network
 * or the currently active proxy exit node.
 */
public class IpDetails {

    private final String ipv4;
    private final String ipv6;
    private final String region;
    private final String city;
    private final String country;
    private final String countryCode;
    private final String isp;
    private final double latitude;
    private final double longitude;
    private final boolean hasCoordinates;
    private final boolean routedViaProxy;
    private final String error;

    public IpDetails(String ipv4, String ipv6, String region, String city,
                     String country, String countryCode, String isp,
                     double latitude, double longitude, boolean hasCoordinates,
                     boolean routedViaProxy, String error) {
        this.ipv4 = ipv4 != null ? ipv4 : "";
        this.ipv6 = ipv6 != null ? ipv6 : "";
        this.region = region != null ? region : "";
        this.city = city != null ? city : "";
        this.country = country != null ? country : "";
        this.countryCode = countryCode != null ? countryCode : "";
        this.isp = isp != null ? isp : "";
        this.latitude = latitude;
        this.longitude = longitude;
        this.hasCoordinates = hasCoordinates;
        this.routedViaProxy = routedViaProxy;
        this.error = error != null ? error : "";
    }

    public IpDetails copyWithRouted(boolean routed) {
        return new IpDetails(ipv4, ipv6, region, city, country, countryCode, isp,
                latitude, longitude, hasCoordinates, routed, error);
    }

    public String getIpv4() { return ipv4; }
    public String getIpv6() { return ipv6; }
    public String getRegion() { return region; }
    public String getCity() { return city; }
    public String getCountry() { return country; }
    public String getCountryCode() { return countryCode; }
    public String getIsp() { return isp; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public boolean hasCoordinates() { return hasCoordinates; }
    public boolean isRoutedViaProxy() { return routedViaProxy; }
    public String getError() { return error; }

    public String getLocationSummary() {
        StringBuilder sb = new StringBuilder();
        if (!city.isEmpty()) sb.append(city);
        if (!region.isEmpty() && !region.equalsIgnoreCase(city)) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(region);
        }
        if (!country.isEmpty()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(country);
        }
        return sb.length() > 0 ? sb.toString() : "Không rõ vị trí";
    }

    public static class IpComparisonResult {
        private final IpDetails currentIp;
        private final IpDetails originIp;
        private final long checkedAtMs;

        public IpComparisonResult(IpDetails currentIp, IpDetails originIp, long checkedAtMs) {
            this.currentIp = currentIp;
            this.originIp = originIp;
            this.checkedAtMs = checkedAtMs;
        }

        public IpDetails getCurrentIp() { return currentIp; }
        public IpDetails getOriginIp() { return originIp; }
        public long getCheckedAtMs() { return checkedAtMs; }

        public boolean isMasked() {
            if (currentIp == null || !currentIp.isRoutedViaProxy()) {
                return false;
            }
            if (currentIp.getIpv4().isEmpty()) {
                return false;
            }
            if (originIp == null || originIp.getIpv4().isEmpty()) {
                return true;
            }
            return !currentIp.getIpv4().equals(originIp.getIpv4());
        }
    }
}
