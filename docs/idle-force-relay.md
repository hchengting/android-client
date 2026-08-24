# Automatic force relay while the device is idle

The Advanced settings screen includes an optional **Force relay while device is
idle** mode. It is disabled by default and is mutually exclusive with manual
force relay:

- manual force relay disables the automatic-mode row;
- automatic mode disables the manual force-relay row;
- disabling automatic mode restores force relay to off.

## Power-state behavior

`VPNService` dynamically registers for these protected system broadcasts while
the service is alive:

- `PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED`;
- `Intent.ACTION_USER_PRESENT`.

When `isDeviceIdleMode()` becomes true, NetBird enables force relay. A false
value is deliberately ignored because Android also reports false during Doze
maintenance windows. Force relay remains enabled throughout those windows and
is disabled only after `ACTION_USER_PRESENT` reports that the user has passed
the keyguard.

On service startup, NetBird reconciles unambiguous state that may have changed
while no receiver was registered:

- active device idle enables force relay;
- an interactive device with no keyguard disables force relay;
- locked or non-interactive non-idle state preserves the last effective value,
  because it may represent a maintenance window.

## Engine restart and VPN continuity

Every effective change uses the existing force-relay reconciliation path. A
running engine is stopped through `EngineRunner.stopPreservingTun()` and started
again after the stop callback. The Android VPN service, foreground lifecycle,
TUN descriptor, and routes remain active during the normal matching-TUN path.

Repeated broadcasts and already-applied values do not trigger another restart.
If the engine is stopped, NetBird records the effective setting without starting
the VPN; the next engine run reads the reconciled value.

See [Runtime force-relay reconfiguration](force-relay-runtime-reconfiguration.md)
for the retained TUN lifecycle and failure behavior.
