package io.netbird.client.tool;

import org.junit.Assert;
import org.junit.Test;

public class TUNRestartStateTest {
    @Test
    public void forceRelayRestartKeepsThenReusesTun() {
        TUNRestartState state = new TUNRestartState();
        TUNParameters parameters = parameters("0.0.0.0/0");

        state.preserveForRestart();

        Assert.assertTrue(state.shouldKeepTunOnEngineStopped());

        state.onEngineRunStarted();

        Assert.assertTrue(state.canReuseTun(true, parameters, parameters("0.0.0.0/0")));

        state.onTunConfigured();

        Assert.assertFalse(state.shouldKeepTunOnEngineStopped());
        Assert.assertFalse(state.canReuseTun(true, parameters, parameters));
    }

    @Test
    public void failedRestartReleasesTun() {
        TUNRestartState state = new TUNRestartState();

        state.preserveForRestart();
        state.onEngineRunStarted();

        Assert.assertFalse(state.shouldKeepTunOnEngineStopped());
    }

    @Test
    public void changedTunConfigurationIsNotReused() {
        TUNRestartState state = new TUNRestartState();
        state.preserveForRestart();
        state.onEngineRunStarted();

        Assert.assertFalse(state.canReuseTun(
                true, parameters("10.0.0.0/8"), parameters("10.1.0.0/16")));
    }

    @Test
    public void cancelledRestartDoesNotKeepTun() {
        TUNRestartState state = new TUNRestartState();
        state.preserveForRestart();

        state.cancel();

        Assert.assertFalse(state.shouldKeepTunOnEngineStopped());
    }

    private static TUNParameters parameters(String routes) {
        return new TUNParameters(
                "100.64.0.1/16", "fd00::1/64", 1280,
                "1.1.1.1", "example.test", routes);
    }
}
