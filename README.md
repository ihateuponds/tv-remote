# TV Remote

An Android app that replaces an LG webOS TV's remote with your phone. No LG app, no LG
account, no ads: it talks to the TV directly over your home Wi-Fi.

- **Power on** with a Wake-on-LAN magic packet, **power off** over the TV's own protocol
- D-pad, OK, back, home, volume, mute, play/pause, input switching
- The phone's volume keys control the TV while the app is open
- Pairs once (the TV asks you to accept), then remembers the client key
- TLS certificate pinning for the TV's secure socket after first pairing

Built with Kotlin and Jetpack Compose; the TV side is LG's SSAP protocol over WebSocket
(`wss://<tv-ip>:3001`, with `ws://…:3000` as the fallback).

## Build

    ./gradlew :app:assembleDebug        # needs the Android SDK (set sdk.dir in local.properties)
    ./gradlew :app:testDebugUnitTest
    adb install app/build/outputs/apk/debug/app-debug.apk

Set your TV's IP and MAC in the app's Settings. On the TV, turn on
*General → Devices → External Devices → TV On With Mobile → Turn on via Wi-Fi* so it can be
woken up.

## How this was made

I designed this and directed the build; the code was written by AI coding agents
(Claude Code and Gemini via Antigravity). The specs and plans in `docs/` are the real
documents the agents worked from.
