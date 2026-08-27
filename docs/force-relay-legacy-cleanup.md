# Force-relay runtime migration: legacy cleanup

Status: cleanup implemented after phase 1 runtime reconfiguration. Reconnect
warning UI resources are intentionally retained by product decision.

## Decision summary

Runtime `SetForceRelay` has replaced the old Android engine restart path for
both the manual switch and the idle policy. A force-relay change now keeps the
engine and VPN running and recycles only open peer transports.

The reviewed code is divided into the following cleanup decisions:

| Group | Decision | Reason |
| --- | --- | --- |
| Engine restart coordinator | Removed | It had no production caller after runtime reconciliation replaced `requestRestart()` |
| Retained-TUN restart support | Removed as one atomic cleanup | Its only consumer was the force-relay engine restart path |
| Reconnect warning UI resources | Keep | Retained explicitly for possible reuse; the runtime switch does not display them |
| Android underlying-network control-plane resolver | Keep | It also protects control-plane dialing during ordinary Android network handover; it is not coupled to force-relay restart anymore |
| Startup `NB_FORCE_RELAY` packaging | Keep | It seeds a new engine before a runtime call exists and preserves startup compatibility |

## Restart logic already superseded by phase 1

The runtime implementation has already removed the following behavior from
`VPNService` and `AdvancedFragment`:

- stopping the engine when either force-relay preference changes;
- posting a delayed engine restart from the engine stopped callback;
- keeping the service foreground state in `CONNECTING` across that restart;
- treating a pending restart specially in `onUnbind()`, `onDestroy()`, revoke,
  and manual start/stop paths;
- cancelling a retained-TUN restart when login is required;
- showing a dialog that tells the user to reconnect after changing the manual
  force-relay setting.

These paths must not be restored as fallback behavior. A failed runtime update
is reported and remains eligible for a later reconciliation; restarting the
whole engine would reintroduce the management, signal, relay, DNS, and all-peer
outage that phase 1 was designed to remove.

## Removed code

### 1. Standalone dead code

The following files were deleted because repository-wide reference searches
found no production consumer:

- `tool/src/main/java/io/netbird/client/tool/EngineRestartCoordinator.java`
- `tool/src/test/java/io/netbird/client/tool/EngineRestartCoordinatorTest.java`

The coordinator serialized the old stop-then-run sequence. Runtime requests are
now serialized and latest-value coalesced by
`ForceRelayReconfigurationCoordinator` instead.

The reconnect dialog is no longer displayed, but its resources are deliberately
kept: `dialog_simple_alert_message.xml`, `reconnectionNeededWarningMessage`, and
`exclamation_mark` in the base and localized `strings.xml` files. This cleanup
does not remove or alter those resources.

### 2. Retained-TUN restart support

This group was removed in one change. Deleting only its entry points would have
left Java holding an extra TUN descriptor for every normal connection even
though no restart could reuse it.

Removed from `EngineRunner.java`:

- `stopPreservingTun()` and `cancelPreservedTunRestart()`;
- the `cancelPreservedEngineRestart()` call from ordinary `stop()`;
- `onEngineRunStarted()` and `onEngineStopped()` calls around the Go run loop,
  including the thread-start failure path;
- the stored `IFace tunAdapter` field, which exists only for those lifecycle
  callbacks.

The constructor now accepts the gomobile `TunAdapter` interface again and passes
it directly to `Android.newClient()`. `VPNService` continues owning its concrete
`IFace` instance for ordinary route-driven TUN renewal.

Removed from `IFace.java`:

- `retainedTun`, `retainedTunParameters`, and `TUNRestartState`;
- the reuse branch in `configureInterface()` and the retained configuration
  bookkeeping after configuration;
- the extra `ParcelFileDescriptor.dup()` in `createTun()`;
- `onEngineRunStarted()`, `preserveForEngineRestart()`,
  `cancelPreservedEngineRestart()`, and `onEngineStopped()`;
- `canReuseRetainedTun()`, `duplicateRetainedTun()`, `replaceRetainedTun()`,
  `releaseRetainedTun()`, and `closeTun()`.

With no retained copy, `createTun()` returns the descriptor produced by
`tun.detachFd()`. Ownership is simple: the Go engine owns and closes that
descriptor, while a later ordinary engine run establishes a fresh Android VPN
interface as it did before retained-TUN support was introduced.

Deleted the state helper and its tests:

- `tool/src/main/java/io/netbird/client/tool/TUNRestartState.java`
- `tool/src/test/java/io/netbird/client/tool/TUNRestartStateTest.java`

Also removed `TUNParameters.hasSameConfiguration()`, its now-unused
`java.util.Objects` import, and
`tool/src/test/java/io/netbird/client/tool/TUNParametersTest.java`. The
`TUNParameters` class itself and `didChange()` must remain because the active
route/search-domain renewal path in `VPNService.recreateTUN()` still uses them.

## Code that must remain

The following code may look related to the retained-TUN work but still has an
active responsibility:

- `UnderlyingNetworkResolver`, the gomobile `ControlPlaneResolver` interface,
  and the Android control-plane resolver implementations in the netbird
  submodule. They resolve and redial management, signal, and NetBird relay
  endpoints on Android's selected non-VPN network during ordinary Wi-Fi/mobile
  handover. Their lifecycle is independent of force-relay switching.
- `NetworkChangeDetector`, `NetworkSwitchNotifier`, and the Go network-change
  notification path. They reconnect sockets bound to an old underlying
  network without rebuilding the engine or TUN.
- `EnvVarPackager.getEnvironmentVariables(forceRelaySetting)` and
  `NB_FORCE_RELAY`. A cold engine still needs an initial value; the runtime
  setter becomes authoritative only after it is called.
- `EngineRunner.requestedForceRelaySetting` and
  `isForceRelaySettingRequested()`. Android uses them to suppress duplicate
  runtime requests and updates the snapshot only after a successful Go call;
  actual application can remain pending on an individual peer.
- `ACTION_APPLY_FORCE_RELAY_SETTING` and
  `ACTION_APPLY_IDLE_FORCE_RELAY_SETTING`. They are app-internal triggers for
  manual and idle reconciliation, not restart commands.
- `ForceRelayReconfigurationCoordinator`, `Client.SetForceRelay()`,
  `Engine.SetForceRelay()`, peer transport reconfiguration, and the dynamic
  `SRWatcher` ICE monitor. These are the replacement runtime path.
- Normal `IFace.configureInterface()` and `VPNService.recreateTUN()` behavior.
  Route and search-domain changes can still require a real TUN renewal even
  though force-relay changes do not.

In particular, the `VPNService.mainHandler` field must remain while it is passed
to `UnderlyingNetworkResolver`; removing restart callbacks does not make that
handler unused.

## Implemented removal order

1. Deleted `EngineRestartCoordinator` and its unit tests.
2. Removed the retained-TUN entry points and lifecycle hooks from `EngineRunner`.
3. Collapsed `IFace` to one descriptor owned by Go, then removed
   `TUNRestartState` and retained-configuration comparison code.
4. Kept all reconnect warning UI resources unchanged.
5. Updated runtime documentation; the applicable automated and manual
   verification gates are listed below.

Keeping steps 2 and 3 in one commit makes descriptor ownership reviewable: no
intermediate revision retains a TUN descriptor without a consumer or calls a
lifecycle hook whose state implementation has already been deleted.

## Verification gates

Before merging the cleanup:

1. Confirm the removed symbols have zero references:

   ```text
   EngineRestartCoordinator
   stopPreservingTun
   cancelPreservedTunRestart
   preserveForEngineRestart
   cancelPreservedEngineRestart
   retainedTun
   TUNRestartState
   hasSameConfiguration
   ```

2. Run the Android/tool unit tests and compile the app against the regenerated
   gomobile binding.
3. Start and normally stop NetBird; confirm the VPN network and TUN disappear
   on stop, proving no Java descriptor remains open.
4. Toggle manual force relay on/off and exercise idle enter/exit; confirm the
   engine, foreground service, VPN network ID, TUN, routes, management, and
   signal sessions remain stable while only open peer transports recycle.
5. Exercise Wi-Fi/mobile handover and temporary loss of connectivity; confirm
   control-plane resolution and redial still work. This protects the resolver
   code intentionally excluded from cleanup.
6. Trigger a route or search-domain update; confirm ordinary TUN renewal still
   succeeds after retained-restart-specific comparison code is gone.

## Resulting ownership model

After cleanup there are two intentionally separate mechanisms:

- force-relay changes are runtime peer-transport reconfiguration and never
  touch engine or TUN lifecycle;
- real engine stops close the Go-owned TUN descriptor, while future engine
  starts establish a new Android VPN interface.

If a future feature needs engine continuity across an internal restart, it
should define that requirement explicitly instead of relying on the removed
force-relay-specific retained descriptor as an undocumented fallback.
