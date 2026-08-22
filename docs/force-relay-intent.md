# Force-relay automation intent

NetBird exposes a broadcast intent that changes the global force-relay setting.
When the VPN engine is running and the value changes, NetBird briefly disconnects
and reconnects with the new setting. When the engine is stopped, NetBird saves
the setting and applies it on the next connection without starting the VPN.

> [!WARNING]
> This initial API is intentionally unprotected. Any installed app can send the
> broadcast and change this setting or cause a running NetBird tunnel to restart.

## Intent contract

| Field | Value |
|---|---|
| Target | Broadcast receiver |
| Package | `io.netbird.client` |
| Component | `io.netbird.client.ForceRelayCommandReceiver` |
| Action | `io.netbird.client.intent.action.SET_FORCE_RELAY` |
| Required extra | `io.netbird.client.intent.extra.ENABLED` |
| Extra type | Boolean, or the string `true` / `false` |

The service compares the requested value with the value used for the current
engine run. It does not restart when that value is already applied. If the
preference was changed earlier without reconnecting, sending the same value
does restart the engine so the pending setting takes effect.

An ordered broadcast can receive one of these acceptance results. The result
confirms that the command was processed, not that reconnection has completed.

| Result code | Result data | Meaning |
|---:|---|---|
| `1` | `updated` | The setting changed and the running service was notified. |
| `2` | `unchanged` | The stored preference already matched; the service still reconciles the running engine if needed. |
| `3` | `invalid_action` or `invalid_enabled_extra` | The request was rejected. |

## ADB examples

Enable force relay:

```shell
adb shell am broadcast \
  -n io.netbird.client/.ForceRelayCommandReceiver \
  -a io.netbird.client.intent.action.SET_FORCE_RELAY \
  --ez io.netbird.client.intent.extra.ENABLED true
```

Disable force relay:

```shell
adb shell am broadcast \
  -n io.netbird.client/.ForceRelayCommandReceiver \
  -a io.netbird.client.intent.action.SET_FORCE_RELAY \
  --ez io.netbird.client.intent.extra.ENABLED false
```

## Tasker

Create a **Send Intent** action with these fields:

- Action: `io.netbird.client.intent.action.SET_FORCE_RELAY`
- Extra: `io.netbird.client.intent.extra.ENABLED:true` or
  `io.netbird.client.intent.extra.ENABLED:false`
- Package: `io.netbird.client`
- Class: `io.netbird.client.ForceRelayCommandReceiver`
- Target: `Broadcast Receiver`

The initial NetBird registration and Android VPN permission must already be
complete. If reconnecting requires an interactive login, the setting remains
saved and NetBird waits for the user to connect normally.
