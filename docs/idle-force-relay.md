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

## Runtime application and VPN continuity

Every effective change uses the runtime force-relay reconciliation path. Android
persists the effective value, then asks the running Go client to update its
engine-owned transport policy. The engine, Android VPN service, foreground
lifecycle, TUN descriptor, routes, DNS, firewall, and control-plane sessions stay
active.

Open peers switch in place while preserving their active path. Disabling force
relay adds fresh ICE workers while relay stays active. Enabling it switches to an
established relay before retiring ICE. A peer without a ready relay records the
request as pending and continues using and advertising its existing ICE path;
the relay-ready callback completes the switch without replacing the peer run or
WireGuard peer. Closed lazy peers remain closed and use the new policy on their
next activation.

While any peer is pending, its per-peer requirement keeps ICE candidate
monitoring active even though the engine's desired policy is relay-only. The
connection guard treats a pending peer with working ICE as partially connected,
which keeps bounded relay negotiation probes active without declaring the
usable P2P path disconnected.

Requests are serialized on a background executor and rapid changes are
coalesced to the latest value. If the engine is stopped or still initializing,
NetBird records the desired setting without starting the VPN; the next engine
run consumes it. The `NB_FORCE_RELAY` environment value remains a startup
fallback rather than the runtime source of truth.

See [Runtime force-relay reconfiguration](force-relay-runtime-reconfiguration.md)
for lifecycle, concurrency, and failure behavior.
