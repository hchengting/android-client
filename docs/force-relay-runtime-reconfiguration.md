# Runtime force-relay reconfiguration without an engine restart

Status: phase 2 implemented. Open peers switch in place when an active path can
be preserved, while the phase 1 recycle path remains as a fallback when no relay
transport is ready. One-shot endpoint acceleration still protects that fallback.

## Problem

Force relay was originally read from `NB_FORCE_RELAY` while the engine started.
Changing it at runtime therefore required stopping and starting the entire
engine. Even when Android retained the TUN descriptor and its routes, that
restart disconnected management, signal, relay, DNS, and every peer at once.

Force relay is a peer transport policy. It determines whether a peer run creates
only its relay worker or creates both relay and ICE workers. It does not require
rebuilding the overlay interface, route manager, firewall, DNS manager, or
control-plane clients.

## Goals

- Keep the engine, Android VPN interface, TUN, routes, DNS, firewall, and
  control-plane sessions running.
- Make the engine-owned force-relay value the runtime source of truth.
- Preserve the current peer path while adding ICE or switching to an established
  relay.
- Keep closed lazy peers closed and apply the latest value when they activate.
- Coalesce rapid Android requests and perform Go calls off the main thread.
- Preserve the environment variable as startup compatibility input.
- Recover a peer's previous transport policy if applying a new policy fails.

## Interruption boundary

Disabling force relay adds a fresh ICE worker to the live peer while its relay,
handshaker, guard, endpoint, and WireGuard peer remain active. The existing relay
continues carrying traffic until the normal ICE upgrade path selects the new
transport.

Enabling force relay switches the WireGuard endpoint to an already-established
relay before ICE is detached. If no relay proxy is ready, the implementation
falls back to closing and recreating that peer's transport run. That uncommon
fallback can still lose in-flight packets, but other peers and engine-owned
subsystems remain running.

For a normal responder connection, endpoint setup first clears the WireGuard
endpoint and waits five seconds before programming the selected endpoint. That
fallback lets the remote peer initiate a handshake, but it added an unconditional
five-second gap after a runtime force-relay recycle. An in-place ICE addition and
a fallback replacement program their first new endpoint immediately. This
removes that local fallback delay; it does not guarantee that the first
WireGuard handshake attempt will succeed, so a transport or handshake retry can
still add latency.

There is no engine-wide reconnect, Android VPN network replacement, route gap,
or control-plane DNS bootstrap caused by a force-relay change.

## Design

```text
Advanced UI / idle power event
              |
              v
      persisted preference
              |
              v
 VPNService reconciliation
              |
              v
 background latest-value coordinator
              |
              v
 gomobile Client.SetForceRelay()
              |
              v
 ConnectClient desired value
              |
              v
 Engine.SetForceRelay()  -- serialized by syncMsgMux
        |                                  |
        v                                  v
 toggle SRWatcher ICE monitor       peer connection store
                                           |
                     +---------------------+--------------------+
                     |                                          |
              open peer: switch in place                 closed lazy peer:
              or use recycle fallback                   record policy only
```

`Engine` stores the requested policy atomically. New peer connections receive a
snapshot of that value. Each existing connection also owns an atomic applied
value, so a failed transition can keep reporting the policy its working
transport still uses. No runtime path mutates process environment or a shared
connection config.

For an open peer, `Conn.ReconfigureForceRelay()` serializes against `Open()` and
`Close()` and chooses one of three transitions:

- force relay off: construct an ICE worker against the existing peer context,
  publish it to the live handshaker, then advertise fresh ICE credentials;
- force relay on with relay ready: resume the existing relay proxy, switch the
  WireGuard endpoint, stop advertising ICE, then retire the ICE worker and proxy;
- force relay on without relay ready: use the phase 1 recycle path, including its
  rollback behavior.

The in-place force-relay-off transition arms only the first ICE endpoint. The
existing relay endpoint is not reconfigured. An in-place force-relay-on
transition uses the established relay endpoint directly. Fallback replacement
runs retain the per-transport one-shot mask from phase 1. Normal `Open()` and
`OpenWithFirstPacket()` calls, plus later reconnect callbacks, continue to use
the original responder fallback.

This is a local peer-lifecycle and endpoint-programming change. It adds no signal
field, wire protocol, or capability negotiation, so the remote peer does not
require a new version.

The peer object, relay worker, handshaker, guard, status recorder, configured
allowed IPs, and engine membership remain intact during an in-place transition.
The retired ICE worker is unpublished before it is closed, so a delayed callback
cannot mutate the relay-only state.

`SRWatcher` keeps its signal and relay reconnect callbacks registered for the
engine lifetime. Its ICE candidate monitor is stopped when force relay is on and
started again when force relay is off.

## Startup and request ordering

Android writes the preference first, then submits runtime application to a
single background executor. The coordinator retains one pending Boolean, so a
burst such as on/off/on applies the latest pending value instead of scheduling
three independent reconfigurations. A change arriving during an application is
handled by one additional pass.

The gomobile client records a desired value even when login or engine creation
is still in progress. A generation counter prevents an older environment
snapshot captured by the Java run thread from overwriting a newer runtime call.
When no runtime call has occurred, `NB_FORCE_RELAY` seeds the initial value for
backward compatibility.

If the client is stopped, setting force relay updates only the desired value.
It does not start the engine or VPN. A later engine instance is created with the
latest desired value.

## Concurrency and failure handling

`Engine.SetForceRelay()` takes `syncMsgMux`, the same serialization boundary used
for engine lifecycle and network-map changes. This prevents peer creation or
removal from interleaving with the store-wide policy update.

Each peer has a separate lifecycle mutex around open, close, and transport
reconfiguration. The optional ICE worker is published through an atomic snapshot
for the lock-free guard status check. Handshaker offer construction and ICE
worker changes share a mutex, preventing signaling from observing a partial
transition. Retired ICE resources are closed after releasing the peer mutex.

If ICE construction fails, relay-only mode remains applied. If switching the
endpoint to an established relay fails, the relay proxy is paused again and the
working ICE policy, worker, proxy, and credentials remain intact. If the recycle
fallback cannot open the requested policy, it restores the previous policy and
attempts to reopen it. The engine aggregates errors from peers that could not
apply the setting.

Android updates its applied-value snapshot only after the Go call succeeds. A
failed call can therefore be retried by the next reconciliation event.

## Legacy cleanup boundary

The force-relay reconciliation path no longer calls `stopPreservingTun()` or
the engine restart coordinator. No other production path consumed the retained
descriptor, so the coordinator and retained-TUN restart mechanism have been
removed as legacy code.

The Android underlying-network control-plane resolver must remain. It has an
independent role during ordinary Wi-Fi/mobile handover and is not part of the
force-relay restart mechanism. See
[Force-relay runtime migration: legacy cleanup](force-relay-legacy-cleanup.md)
for the exact removal set, dependency order, and verification gates.

## Verification

Automated coverage verifies:

- disabling force relay preserves the peer run, relay worker, handshaker, and
  WireGuard peer while adding fresh ICE credentials;
- enabling force relay with an established relay switches its endpoint before
  retiring ICE and removes ICE credentials from later signaling;
- a failed relay endpoint update leaves ICE active and advertised;
- enabling force relay without a ready relay uses the recycle fallback;
- the first new ICE endpoint bypasses responder fallback while the retained
  relay keeps its normal behavior;
- a closed lazy peer remains closed and uses the new value on activation;
- engine updates reach stored peers without starting closed peers;
- a stopped or not-yet-started connect client queues the desired value;
- the ICE monitor can be disabled and restarted without removing signal or
  relay reconnect callbacks;
- rapid Android requests coalesce to the latest value, including a request that
  arrives while another value is being applied;
- touched Go packages pass focused race-detector tests;
- the regenerated Android binding and app Java source compile together.

Manual Android validation should additionally confirm:

1. Connect NetBird and note the engine connection plus Android VPN network ID.
2. Toggle manual force relay on and off while continuously sending traffic to
   multiple peers.
3. Enter device idle mode, then unlock the device to exercise both automatic
   transitions.
4. Confirm the Android VPN network ID, TUN interface, routes, foreground service,
   and management/signal sessions remain present.
5. Confirm a relay-to-ICE transition does not log peer removal or handshaker and
   guard shutdown, and its resulting transport mode matches the requested value.
6. Repeat rapid on/off/on changes and confirm the final mode wins.
7. Repeat with an idle lazy peer and confirm the setting does not activate it.
8. Stop NetBird normally and confirm the VPN interface is removed.

## Future optimization

The remaining recycle fallback can be replaced by a pending force-relay state
that keeps ICE active until a relay connection becomes ready. A later phase can
also wait for an observed WireGuard handshake on the target path before retiring
the previous transport.
