# Runtime force-relay reconfiguration without an engine restart

Status: phase 3 implemented for local transition safety. Open peers switch in
place, and a peer without a ready relay remains on ICE in a pending force-relay
state instead of recycling its transport run.

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
- Keep the requested policy distinct from the transport policy currently
  applied to each peer.

## Interruption boundary

Disabling force relay adds a fresh ICE worker to the live peer while its relay,
handshaker, guard, endpoint, and WireGuard peer remain active. The existing relay
continues carrying traffic until the normal ICE upgrade path selects the new
transport.

Enabling force relay switches the WireGuard endpoint to an already-established
relay before ICE is detached. If no relay proxy is ready, the peer records the
request as pending, keeps the current peer context, handshaker, guard, ICE
worker, proxy, WireGuard peer, endpoint, and ICE credentials, then asks the
existing handshaker to negotiate again. The relay-ready callback completes the
same transition after a usable relay proxy appears.

The normal responder endpoint fallback remains unchanged for ordinary
connections. Runtime P2P-to-relay switching no longer enters that path merely
because relay was not ready at the instant the setting changed.

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
 update SRWatcher base policy       bounded peer fan-out (max 8)
                                           |
                     +---------------------+--------------------+
                     |                                          |
              open peer: switch in place                 closed lazy peer:
              or wait on ICE for relay                  record policy only
```

`Engine` stores the requested policy atomically. New peer connections receive a
snapshot of that value. Each existing connection owns one atomic state rather
than independent Boolean flags, so desired, applied, and pending observations
cannot contradict one another:

| Peer state | Desired force relay | Applied force relay | Meaning |
| --- | --- | --- | --- |
| `disabled` | no | no | ICE is allowed by the active or next run |
| `pending` | yes | no | the existing ICE path stays active while relay is prepared |
| `enabled` | yes | yes | relay-only policy is applied |

No runtime path mutates process environment or a shared connection config.

For an open peer, `Conn.ReconfigureForceRelay()` serializes against `Open()` and
`Close()` and chooses one of three transitions:

- force relay off: construct an ICE worker against the existing peer context,
  publish it to the live handshaker, then advertise fresh ICE credentials;
- force relay on with relay ready: resume the existing relay proxy, switch the
  WireGuard endpoint, stop advertising ICE, then retire the ICE worker and proxy;
- force relay on without relay ready: enter `pending`, preserve and continue
  advertising ICE, and send an updated offer. When the relay-ready callback
  arrives, switch the endpoint and retire ICE using the same ready-relay path.

A pending request can be cancelled by turning force relay off. This changes the
state back to `disabled` without replacing the already-active ICE worker.

The in-place force-relay-off transition arms only the first ICE endpoint. The
existing relay endpoint is not reconfigured. An in-place force-relay-on
transition uses the established relay endpoint directly. Normal `Open()` and
`OpenWithFirstPacket()` calls, plus later reconnect callbacks, continue to use
the original responder fallback. Entering pending also arms the first relay
endpoint update: it is consumed only if ICE disappears before relay becomes
ready, so the now-disconnected peer does not add the responder delay before
activating relay.

This is a local peer-lifecycle and endpoint-programming change. It adds no signal
field, wire protocol, or capability negotiation, so the remote peer does not
require a new version.

The peer object, relay worker, handshaker, guard, status recorder, configured
allowed IPs, and engine membership remain intact during an in-place transition.
The retired ICE worker is unpublished before it is closed, so a delayed callback
cannot mutate the relay-only state.

`SRWatcher` keeps its signal and relay reconnect callbacks registered for the
engine lifetime. Its ICE candidate monitor has both an engine base policy and
per-peer requirements. Enabling force relay first lets peers acquire a monitor
requirement when they enter `pending`, then disables the base policy; therefore
the monitor cannot stop in the middle of a still-active ICE transition. It stops
only after every pending peer has applied relay or cancelled the request.

The guard classifies a pending peer with a connected ICE path as partially
connected. It therefore performs bounded relay/capability probes while still
treating the old path as usable. If ICE is also down, the peer is disconnected
and retains the guard's aggressive retry behavior.

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
removal from interleaving with the store-wide policy update. Requests remain
serialized at the engine level, but one request reconfigures up to eight peers
concurrently. Results are stored by peer-list position and errors are aggregated
after every worker finishes, so completion order does not affect diagnostics.

The shared `WGIface` mutex continues to serialize actual WireGuard device
writes. Bounded fan-out instead overlaps peer-local lifecycle work, signaling,
resource cleanup, and the 100 ms WireGuard stabilization workaround. For ready
relay paths, this changes the dominant delay from one stabilization interval per
peer to approximately one interval per batch of eight, plus serialized device
writes. The bound prevents large accounts from creating one goroutine per peer.

Each peer has a separate lifecycle mutex around open, close, and transport
reconfiguration. The optional ICE worker is published through an atomic snapshot
for the lock-free guard status check. Handshaker offer construction and ICE
worker changes share a mutex, preventing signaling from observing a partial
transition. Retired ICE resources are closed after releasing the peer mutex.

If ICE construction fails, relay-only mode remains applied. If switching the
endpoint to an established relay fails, the relay proxy is paused again and the
working ICE policy, worker, proxy, and credentials remain intact while the peer
remains pending. A failure reported synchronously is aggregated by the engine;
an asynchronous relay-ready failure discards that relay proxy and lets the
guard negotiate another one without dropping ICE.

Android records that the requested value was accepted after the Go call
succeeds. For force-relay-on, successful acceptance may mean that one or more
peers are still pending; the per-peer state remains the authority for actual
transport application.

## Logcat observability

The transition logs distinguish request acceptance from per-peer application:

- `force-relay runtime request updated to true; reconfigured ... in ...` means
  the engine accepted the desired value and reports the bounded fan-out wall
  time; it does not imply every pending peer already retired ICE;
- `force-relay transition pending; keep ICE active until relay is ready` marks
  the make-before-break waiting state and must not be accompanied by `close peer
  connection` for that policy change;
- `pending force-relay transition applied after relay became ready` confirms
  callback-driven completion;
- `force-relay transition applied using ready relay` confirms the immediate
  ready-relay path;
- `cancelled pending force-relay transition; keep ICE active` confirms that an
  off request cancelled the wait without replacing the ICE run.

On Android, `Accepted runtime force-relay setting` likewise means that Go
accepted the requested policy. It intentionally does not claim that every peer
has already reached relay-only state.

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
- enabling force relay without a ready relay preserves the peer run, ICE path,
  endpoint, WireGuard peer, and credentials while marking the request pending;
- a later relay-ready callback applies the pending request and only then retires
  ICE;
- the same callback completes relay-only mode if ICE is lost while waiting and
  programs the first relay endpoint immediately;
- cancelling a pending request preserves the existing ICE path;
- the first new ICE endpoint bypasses responder fallback while the retained
  relay keeps its normal behavior;
- a closed lazy peer remains closed and uses the new value on activation;
- engine updates reach stored peers without starting closed peers;
- engine peer reconfiguration starts up to eight operations concurrently,
  enforces that bound, counts successful changes, and preserves input order when
  aggregating errors;
- a stopped or not-yet-started connect client queues the desired value;
- the ICE monitor can be disabled and restarted without removing signal or
  relay reconnect callbacks, and a pending peer requirement keeps it running
  while the engine base policy is disabled;
- guard status remains partially connected while pending ICE is usable and
  becomes disconnected when that path is lost;
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
7. Repeat with at least nine active peers and compare the engine's `in ...`
   duration with the prior sequential build; confirm peer transitions overlap.
8. Repeat with an idle lazy peer and confirm the setting does not activate it.
9. Stop NetBird normally and confirm the VPN interface is removed.

## Future optimization

The relay-ready callback proves that the relay transport and local proxy exist,
but it does not prove that encrypted peer traffic has completed a WireGuard
handshake on the new endpoint. A later phase can wait for an observed handshake
or verified data-path activity on the target path before retiring the previous
transport. That refinement is local for observation and rollback, although a
fully coordinated make-before-break protocol would require remote-peer support.
