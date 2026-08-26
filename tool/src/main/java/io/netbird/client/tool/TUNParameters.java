package io.netbird.client.tool;

public class TUNParameters {
    final String address;
    final String addressV6;
    final long mtu;
    final String dns;
    final String searchDomainsString;
    final String routesString;

    public TUNParameters(String address, String addressV6, long mtu, String dns, String searchDomainsString, String routesString) {
        this.address = address;
        this.addressV6 = addressV6;
        this.mtu = mtu;
        this.dns = dns;
        this.searchDomainsString = searchDomainsString;
        this.routesString = routesString;
    }

    public boolean didChange(String routesString, String searchDomainsString) {
        return didPartChange(this.routesString, routesString)
                || didPartChange(this.searchDomainsString, searchDomainsString);
    }

    private static boolean didPartChange(String current, String updated) {
        if (current != null) {
            return !current.equals(updated);
        }
        return updated != null;
    }
}
