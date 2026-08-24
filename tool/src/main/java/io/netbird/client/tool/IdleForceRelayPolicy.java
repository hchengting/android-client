package io.netbird.client.tool;

/**
 * Pure state decisions for automatic force relay while Android is in device idle mode.
 */
final class IdleForceRelayPolicy {
    enum Decision {
        KEEP,
        ENABLE,
        DISABLE
    }

    private IdleForceRelayPolicy() {
    }

    static Decision onDeviceIdleModeChanged(boolean automaticModeEnabled,
                                            boolean deviceIdle) {
        if (!automaticModeEnabled || !deviceIdle) {
            // isDeviceIdleMode() is also false during Doze maintenance windows.
            // Only the transition into active idle is actionable here.
            return Decision.KEEP;
        }
        return Decision.ENABLE;
    }

    static Decision onUserPresent(boolean automaticModeEnabled) {
        return automaticModeEnabled ? Decision.DISABLE : Decision.KEEP;
    }

    static Decision onServiceStarted(boolean automaticModeEnabled,
                                     boolean deviceIdle,
                                     boolean interactive,
                                     boolean keyguardLocked) {
        if (!automaticModeEnabled) {
            return Decision.KEEP;
        }
        if (deviceIdle) {
            return Decision.ENABLE;
        }
        if (interactive && !keyguardLocked) {
            return Decision.DISABLE;
        }

        // This may be a maintenance window or a locked/non-interactive device.
        // Preserve the last effective value until an unambiguous event arrives.
        return Decision.KEEP;
    }

    static Decision onAutomaticModeChanged(boolean automaticModeEnabled,
                                           boolean deviceIdle,
                                           boolean interactive,
                                           boolean keyguardLocked) {
        if (!automaticModeEnabled) {
            // Manual force relay was guaranteed off when automatic mode was enabled.
            return Decision.DISABLE;
        }
        return onServiceStarted(true, deviceIdle, interactive, keyguardLocked);
    }
}
