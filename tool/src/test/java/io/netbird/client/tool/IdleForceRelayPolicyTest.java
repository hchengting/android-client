package io.netbird.client.tool;

import org.junit.Assert;
import org.junit.Test;

public class IdleForceRelayPolicyTest {
    @Test
    public void enteringIdleEnablesForceRelay() {
        Assert.assertEquals(
                IdleForceRelayPolicy.Decision.ENABLE,
                IdleForceRelayPolicy.onDeviceIdleModeChanged(true, true)
        );
    }

    @Test
    public void maintenanceWindowDoesNotDisableForceRelay() {
        Assert.assertEquals(
                IdleForceRelayPolicy.Decision.KEEP,
                IdleForceRelayPolicy.onDeviceIdleModeChanged(true, false)
        );
    }

    @Test
    public void idleEventsAreIgnoredWhenAutomaticModeIsDisabled() {
        Assert.assertEquals(
                IdleForceRelayPolicy.Decision.KEEP,
                IdleForceRelayPolicy.onDeviceIdleModeChanged(false, true)
        );
    }

    @Test
    public void userPresentDisablesForceRelayOnlyInAutomaticMode() {
        Assert.assertEquals(
                IdleForceRelayPolicy.Decision.DISABLE,
                IdleForceRelayPolicy.onUserPresent(true)
        );
        Assert.assertEquals(
                IdleForceRelayPolicy.Decision.KEEP,
                IdleForceRelayPolicy.onUserPresent(false)
        );
    }

    @Test
    public void serviceStartReconcilesUnambiguousStates() {
        Assert.assertEquals(
                IdleForceRelayPolicy.Decision.ENABLE,
                IdleForceRelayPolicy.onServiceStarted(true, true, false, true)
        );
        Assert.assertEquals(
                IdleForceRelayPolicy.Decision.DISABLE,
                IdleForceRelayPolicy.onServiceStarted(true, false, true, false)
        );
    }

    @Test
    public void serviceStartKeepsValueDuringAmbiguousNonInteractiveState() {
        Assert.assertEquals(
                IdleForceRelayPolicy.Decision.KEEP,
                IdleForceRelayPolicy.onServiceStarted(true, false, false, true)
        );
        Assert.assertEquals(
                IdleForceRelayPolicy.Decision.KEEP,
                IdleForceRelayPolicy.onServiceStarted(true, false, false, false)
        );
    }

    @Test
    public void disablingAutomaticModeRestoresManualOffBaseline() {
        Assert.assertEquals(
                IdleForceRelayPolicy.Decision.DISABLE,
                IdleForceRelayPolicy.onAutomaticModeChanged(false, true, false, true)
        );
    }
}
