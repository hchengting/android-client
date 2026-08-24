# Force-relay reconfiguration without a VPN route gap

Status: implemented by retaining and reusing the Android TUN file descriptor,
with isolated Android control-plane DNS bootstrap during the restart.

## Problem

Automatic idle force-relay changes must restart the Go engine because
`NB_FORCE_RELAY` is startup configuration. A normal engine stop closes the Go
side of the TUN file descriptor. When that is the last open descriptor, Android
removes the active VPN network and its routes until the next call to
`VpnService.Builder.establish()` succeeds.

Changing force-relay mode does not require Android to remove the VPN network.
It is acceptable for management, signal, relay, and peer connections to
disconnect while the Go engine restarts, but the Android VPN interface and its
route ownership must remain active.

See the Android documentation for
[`VpnService.Builder.establish()`](https://developer.android.com/reference/android/net/VpnService.Builder#establish()).

## Goals

- Keep the existing Android VPN interface and TUN alive across a force-relay
  engine restart.
- Keep Android VPN routes continuously installed.
- Avoid peer-level or engine-level live reconfiguration in Go.
- Continue using the persisted force-relay preference.
- Coalesce repeated requests and apply the latest persisted value on the next
  run.
- Release the retained VPN promptly on an ordinary stop or failed restart.

## Accepted interruptions

- The Go engine still stops and starts.
- Management, signal, and relay sessions reconnect.
- Peer traffic is unavailable until the new engine has rebuilt peer
  connections.
- In-flight packets may be lost while no Go engine is reading from the TUN.

The Android routes remain active during this interval. Packets can be delayed or
dropped, but they do not escape through a temporarily restored non-VPN route.

## Control-plane DNS bootstrap

Keeping the VPN network alive also keeps its DNS routing active. During the
engine restart, the old peer DNS service and peer connections are already down,
but the replacement engine must resolve the management server before it can
rebuild them. Resolving that hostname through the retained VPN DNS path can
therefore leave the replacement run stuck in `Connecting`.

On Android, management, signal, and NetBird relay dialing now use a dedicated
control-plane resolver. It queries these fixed public DNS servers in parallel:

1. Cloudflare at `1.1.1.1:53`.
2. Cloudflare at `[2606:4700:4700::1111]:53`.
3. Google at `8.8.8.8:53`.
4. Google at `[2001:4860:4860::8888]:53`.

The first usable answer wins and cancels the remaining lookups. A failure,
empty answer, or authoritative not-found response from one provider does not
discard a successful answer from another. All four lookups share a five-second
deadline bounded by the caller's context. The existing Android socket-protection
hook applies to every DNS socket, so they leave through the non-VPN path even
while the retained VPN remains active.

The dedicated resolver is limited to Android control-plane bootstrap paths:

- management and signal gRPC plus NetBird relay WebSocket and QUIC dialing;
- the management DNS cache when it resolves management, signal, or relay
  domains;
- route-manager bootstrap resolution for management, signal, and relay URLs.

Route bootstrap resolves each unique hostname only once. After lookup, TCP is
dialed using the resolved IP; QUIC receives a resolved UDP address. The original
hostname remains in the gRPC target, WebSocket URL, and TLS server name, so
certificate validation and SNI are unchanged. Literal IP endpoints bypass DNS.

This does not replace `net.DefaultResolver`, change Android's global DNS, alter
the DNS servers installed on the NetBird VPN, or change peer DNS handling.
Other DNS traffic continues to use the existing peer/VPN DNS path. Non-Android
platforms retain their existing resolver behavior. STUN/TURN, flow, and metrics
dial paths are outside this change.

## Design

```text
VpnService.Builder.establish()
              |
              v
      ParcelFileDescriptor
          /           \
         / dup()       \ detachFd()
        v               v
Java retained fd     Go engine fd
        |               |
        |          Engine.Stop() closes it
        |               X
        |
        +--- dup() ---> new Go engine fd

Android VPN interface and routes stay alive while the retained fd is open.
```

`IFace` is shared by all runs of one `VPNService`. After `establish()` succeeds,
it duplicates the descriptor before detaching the original descriptor for Go.
The duplicate is an ownership guard, not a second TUN interface.

When force-relay changes, `EngineRunner.stopPreservingTun()` marks the next stop
as an internal restart and then calls the existing Go `Client.Stop()`. Go closes
its descriptor while the retained Java descriptor keeps the same Android VPN
network alive.

The next engine run calls `TunAdapter.ConfigureInterface()` as usual. If its
address, MTU, DNS, search domains, and routes match the retained TUN,
`IFace` duplicates the retained descriptor and returns it without calling
`Builder.establish()` again. Force-relay alone does not change those settings,
so this is the expected path.

If the TUN settings changed while the engine was restarting, a new interface is
established before the retained descriptor is released. The engine therefore
receives the updated settings instead of silently using stale routes.

## Lifecycle state

The retained descriptor follows a small state machine:

| Event | Action |
|---|---|
| Initial TUN established | Duplicate and retain its descriptor. |
| Ordinary engine stop | Release the retained descriptor; Go closes its descriptor during shutdown. |
| Force-relay restart requested | Keep the retained descriptor after the old run stops. |
| Replacement run starts | Allow matching `ConfigureInterface()` to reuse the retained TUN. |
| Replacement TUN attached | Return to normal ownership. |
| Replacement run fails before attach | Release the retained descriptor. |
| Restart cancelled, VPN revoked, or service destroyed | Release the retained descriptor. |
| Interactive login required | Save the preference, release the VPN, and wait for a normal user connection. |

The foreground service also remains active during the internal restart. An
ordinary stop still removes its foreground notification.

## Concurrency and cleanup

All retained-descriptor and restart-state operations are serialized by the
shared `IFace` instance. The active TUN configuration is immutable, so matching
does not race with route-renewal callbacks.

`EngineRunner` calls the TUN stop hook from its run-loop `finally` block. Profile
lookup failures, Go client errors, failed TUN attachment, and normal shutdown
therefore take the same cleanup path. A failure to start the Java engine thread
also clears the retained restart state synchronously.

The existing `EngineRestartCoordinator` still prevents overlapping restarts.
Repeated power-state broadcasts while a restart is pending update the persisted
preference but stop the old engine only once. The replacement run reads the
latest value.

## Why engine restart semantics stay unchanged

The engine still owns and closes its descriptor normally. Android retains a
different descriptor created with `dup()`, so Go does not need a special close
mode or a process-global TUN object. The Go networking change is limited to the
Android control-plane dialer described above.

This keeps force-relay startup semantics consistent: each replacement engine
reads one `NB_FORCE_RELAY` value before it starts. It avoids changing peer
workers, `SRWatcher`, management sync locking, or status evaluation.

## Verification

Automated coverage verifies:

- identical TUN configurations are reusable;
- changes to addresses, DNS, or routes are not treated as the same TUN;
- the old engine stop keeps the descriptor only for a requested restart;
- a replacement run can reuse it once;
- cancellation and pre-attach failure do not retain the VPN.
- public DNS providers are queried concurrently under one timeout;
- a successful provider wins even if the other returns not-found;
- control-plane dialing resolves hostnames before dialing while literal IPs
  bypass DNS;
- QUIC receives the UDP endpoint resolved by the control-plane resolver.
- management-cache bootstrap bypasses the peer DNS chain only for management,
  signal, and relay domains;
- route bootstrap resolves a repeated control-plane hostname once.

Manual Android validation should additionally confirm:

1. Connect NetBird and continuously inspect `dumpsys connectivity` and the
   system VPN indicator.
2. Enter device idle mode, then unlock the device to toggle force relay in both
   directions.
3. Confirm the VPN network ID and TUN interface remain present for the matching
   configuration path.
4. Confirm routes remain assigned to the VPN throughout the engine restart.
5. Confirm management and peers reconnect with the requested relay mode.
6. Stop NetBird normally and confirm the VPN interface is removed.
7. Repeat with always-on VPN and **Block connections without VPN** enabled.

## Future optimization

A future version can avoid the Go engine restart by making force-relay
engine-scoped, dynamically updating the signal/relay watcher, and rebuilding
only peer transports. That can reduce peer packet loss, but it is not required
to preserve the Android VPN interface or its routes and would be a much larger
Go concurrency change.
