package io.netbird.client.tool;

import org.junit.Assert;
import org.junit.Test;

public class TUNParametersTest {
    @Test
    public void identicalConfigurationsCanReuseTun() {
        TUNParameters current = parameters("100.64.0.1/16", "1.1.1.1", "0.0.0.0/0");
        TUNParameters requested = parameters("100.64.0.1/16", "1.1.1.1", "0.0.0.0/0");

        Assert.assertTrue(current.hasSameConfiguration(requested));
    }

    @Test
    public void changedRoutesCannotReuseTun() {
        TUNParameters current = parameters("100.64.0.1/16", "1.1.1.1", "10.0.0.0/8");
        TUNParameters requested = parameters("100.64.0.1/16", "1.1.1.1", "10.1.0.0/16");

        Assert.assertFalse(current.hasSameConfiguration(requested));
    }

    @Test
    public void changedAddressOrDnsCannotReuseTun() {
        TUNParameters current = parameters("100.64.0.1/16", "1.1.1.1", "0.0.0.0/0");

        Assert.assertFalse(current.hasSameConfiguration(
                parameters("100.64.0.2/16", "1.1.1.1", "0.0.0.0/0")));
        Assert.assertFalse(current.hasSameConfiguration(
                parameters("100.64.0.1/16", "8.8.8.8", "0.0.0.0/0")));
    }

    private static TUNParameters parameters(String address, String dns, String routes) {
        return new TUNParameters(address, "fd00::1/64", 1280, dns, "example.test", routes);
    }
}
