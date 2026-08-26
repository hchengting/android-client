# Runtime force-relay reconfiguration without an engine restart

Status: phase 1 implemented. Force-relay changes keep the Go engine and Android
VPN alive and recycle only open peer transports.

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
- Recycle only peer transports that are currently open.
- Keep closed lazy peers closed and apply the latest value when they activate.
- Coalesce rapid Android requests and perform Go calls off the main thread.
- Preserve the environment variable as startup compatibility input.
- Recover a peer's previous transport policy if applying a new policy fails.

## Accepted interruption

An open peer is briefly unavailable while its relay, ICE, handshaker, guard, and
endpoint state are closed and recreated. In-flight packets for that peer may be
lost. Other peers and engine-owned subsystems remain running.

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
              open peer: recycle                         closed lazy peer:
              connection transports                     record policy only
```

`Engine` stores the active policy atomically. New peer connections receive a
snapshot of that value. Existing connections also own an atomic desired value,
so no runtime path mutates process environment or a shared connection config.

For an open peer, `Conn.ReconfigureForceRelay()` serializes against `Open()` and
`Close()`, closes the current connection run, then creates a new run:

- force relay on: relay worker only, with no ICE credentials in signaling;
- force relay off: relay and a fresh ICE worker, with fresh ICE credentials.

The peer object, status recorder, configured allowed IPs, and engine membership
remain intact. The previous run's worker pointers are cleared only after its
goroutines stop, so a stale ICE worker cannot leak into relay-only mode.

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
reconfiguration. Run-owned objects are constructed in local variables and are
published to the connection only after every fallible construction step
succeeds. Goroutines receive those run-owned objects as parameters instead of
reading fields that a later run can replace. ICE and relay callbacks carry the
originating worker identity, so a delayed callback from an old run cannot mutate
the replacement run.

If opening the new policy fails, the connection restores its previous policy
and attempts to reopen it. The engine keeps the new desired value and returns an
aggregated error for peers that could not apply it. Repeating the same engine
setting reconciles peers again, while peers already on that value remain
untouched. If both the new run and rollback fail, the peer remains closed and
the combined error is reported.

Android updates its applied-value snapshot only after the Go call succeeds. A
failed call can therefore be retried by the next reconciliation event.

## Legacy cleanup boundary

The force-relay reconciliation path no longer calls `stopPreservingTun()` or
the engine restart coordinator. No other production path consumes the retained
descriptor, so the coordinator and retained-TUN restart mechanism can be
removed as legacy code.

The Android underlying-network control-plane resolver must remain. It has an
independent role during ordinary Wi-Fi/mobile handover and is not part of the
force-relay restart mechanism. See
[Force-relay runtime migration: legacy cleanup](force-relay-legacy-cleanup.md)
for the exact removal set, dependency order, and verification gates.

## Verification

Automated coverage verifies:

- an open relay-only peer is rebuilt with a fresh ICE worker when force relay is
  disabled;
- enabling force relay removes the previous run's ICE worker and ICE signaling
  credentials;
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
5. Confirm only peer connections reconnect and their resulting transport mode
   matches the requested value.
6. Repeat rapid on/off/on changes and confirm the final mode wins.
7. Repeat with an idle lazy peer and confirm the setting does not activate it.
8. Stop NetBird normally and confirm the VPN interface is removed.

## Future optimization

Phase 2 can investigate changing a live peer between ICE and relay without
closing the whole peer connection run. That could reduce the remaining
per-peer packet loss, but it requires a more granular handshaker and worker
lifecycle than phase 1.
