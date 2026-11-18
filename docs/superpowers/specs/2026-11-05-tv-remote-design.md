# TV Remote for Pixel: design

Date: 2026-11-05
Status: draft v2 (cut down to the remote only), waiting for Ponds' review

## Goal

Replace the LG Magic Remote with the Pixel. No LG app, no LG account, no ads.

**Done means:** from the Pixel on home Wi-Fi, Ponds can turn the TV **on and off**, move
around with the D-pad, press OK / back / home, change the volume, mute, play/pause and
switch inputs.

## What Ponds said vs what I assumed

| Ponds said | I assumed (correct me) |
|---|---|
| Replace the Magic Remote, low batteries | Works on home Wi-Fi only |
| Must turn the TV on | Installed by USB with `adb`, not the Play Store |
| Not LG's ThinQ app | One TV, IP 192.168.1.50 (changeable in Settings) |

## The TV

LG 43UR8000AUA, webOS 23, at 192.168.1.50, MAC `00:11:22:33:44:55`.

- **Control:** LG's remote protocol (SSAP over WebSocket), `wss://<ip>:3001`
  (checked open 2026-11-05), fallback `ws://<ip>:3000`.
- **Power on:** Wake-on-LAN magic packet. **Tested 2026-11-05: works.** Needs the TV setting
  *General → Devices → External Devices → TV On With Mobile → Turn on via Wi-Fi* (now on).
  Packet sent to the subnet broadcast and to the TV's IP, ports 9 and 7.

## App

- **Kotlin + Jetpack Compose**, one module, package `com.ponds.tvremote`.
- minSdk 31, targetSdk = newest platform in `~/Android/Sdk`.
- Built with the Gradle wrapper from the command line.

### Screens

1. **Remote** (the only main screen): power (on if off, off if on), D-pad + OK, back,
   home, volume up/down, mute, play/pause, input button (picks from the TV's input list).
   A status chip on top: Connected / Connecting / TV off (tap power to wake).
2. **Settings** (gear icon): TV IP, TV MAC, "forget pairing".

### Extras

- While the app is open, the phone's volume keys change the TV volume.
- Haptic tap on every button.
- Keeps the screen awake while the app is open.

## Parts

| Part | Job | Depends on |
|---|---|---|
| `SsapClient` | WebSocket, one-time pairing, requests, replies by id | OkHttp |
| `PointerSocket` | Second socket for D-pad / OK / back / home | `SsapClient` |
| `Waker` | Sends the Wake-on-LAN packet (UDP) | — |
| `TvController` | Plain verbs: `powerOn()`, `powerOff()`, `volumeUp()`, `setInput(id)` … | the three above |
| `Prefs` | IP, MAC, pairing key, pinned cert (app-private DataStore; only this app can read it) | — |
| UI | Compose screens + one ViewModel | `TvController` |

### Power button logic

- The TV keeps its network on in standby (seen 2026-11-05: it answered ping and port 3001
  while off), so "connected" does **not** mean "on". The app asks the TV's power state
  (`getPowerState`).
- State `Active` → `powerOff()` over SSAP.
- Anything else, or not reachable → send Wake-on-LAN, then check every second for up to 15 s.

### Pairing

On the first connect the TV shows "Allow this device?" and Ponds presses OK on the TV
(or the Magic Remote, one last time). The client key is saved, so it never asks again.

### Certificates

The TV's `wss` cert is self-signed. The app pins it on first connect and trusts only that
cert for the saved IP.

## Error handling

- TV off → chip says "TV off"; only power works, the other buttons are greyed out.
- Pairing turned down → message + "Try again".
- Socket drops → reconnect with backoff (1, 2, 4, max 10 s) while the app is open.
- Not on home Wi-Fi → "Can't reach the TV. Are you on home Wi-Fi?"

## Testing

- `SsapClient` / `TvController`: unit tests against a fake WebSocket server.
- `Waker`: unit test that checks the packet bytes (6×FF + 16×MAC).
- One manual run from this PC against the real TV: pair, volume, launch an input.
- On the Pixel: `adb install`, turn the TV off, wake it from the app, press every button.

## Before code

1. Check which Android platform and build-tools are in `~/Android/Sdk`, and install the
   missing ones with `sdkmanager` (dry run shown first).
2. Pixel: USB debugging on (Settings → Developer options).

## Not in scope (later, maybe)

Shortcuts page, TV VPN buttons, quick-settings tile, Magic Remote pointer / air mouse,
keyboard typing, away-from-home control.
