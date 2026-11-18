# SDD ledger — plan: docs/superpowers/plans/2026-11-05-tv-remote.md
Ruling: no git commits — Ponds rule "no commit unless asked" beats plan/skill commit steps — cost if wrong: Ponds commits later himself
Ruling: Gradle from official verified zip in scratch instead of pacman — avoids needing Ponds sudo password; same thing gradlew downloads — cost if wrong: none, nothing system-wide installed
Pre-flight: T3 SsapClient.request vs T5 SsapApi interface — rule: SsapClient implements SsapApi; T4 PointerSocket consumes SsapApi.request; T6 uses Tv interface from T5. No conflicts.
Task 1: Ruling: compileSdk 37 (targetSdk stays 36) — Compose BOM 2026.09 AARs require compile against 37; android-37.0 already installed — cost if wrong: none at runtime
Task 1: complete (no commits per ruling, tests: :app:testDebugUnitTest :app:assembleDebug → BUILD SUCCESSFUL, 1/1, apk 64MB)
Task 2: complete (tests: --tests '*WakerTest*' → 5/5)
Task 3: Ruling: connect() gains onPrompt callback + SsapClient.onClosed hook — ViewModel needs Pairing status and drop detection (spec: status chip, reconnect) — cost if wrong: small API change
Task 3: Ruling: SsapApi is a fun interface defined in Ssap.kt with request(uri, payload); request(uri) is an extension — lets T4/T5 fake it — cost if wrong: none
Task 3: Ruling: pairing timeout (60 s, no answer) maps to Declined — spec: "Pairing turned down → message + Try again"; ignoring the prompt is the same to the user — cost if wrong: message wording
Task 3: Ruling: added tests firstPairingSendsNoClientKey, requestSendsUriAndPayload, requestWhenClosedThrows, pairingTimeoutCountsAsDeclined, closeReportsClosed, pinIsLowercaseHexSha256 beyond plan — cover RF2 and request shape — cost: none
Task 3: complete (tests: --tests '*Ssap*' --tests '*TlsPin*' → 16/16)
Task 4: Ruling: added PointerSocket.openFrom(SsapApi) + isOpen and test openFromAsksTvForSocketPath — keeps the URI in one place — cost: none
Task 4: complete (tests: --tests '*PointerSocket*' → 3/3)
Task 5: Ruling: TvController takes (SsapApi, pressButton: (Button)->Boolean, settings, waker) instead of concrete SsapClient/PointerSocket — testable without sockets — cost: none
Task 5: Ruling: power state "Screen Off" counts as on (TV running, screen blanked; WOL would not help, power-off does) — spec only names Active — cost if wrong: one press turns TV off instead of screen on
Task 5: complete (tests: --tests '*TvController*' --tests '*PrefsTest*' → 14/14)
Task 6: Ruling: VM depends on interfaces Connection{connect(settings,onPrompt):ConnectResult; disconnect(); onDropped} + SettingsStore{get();update()} instead of a connect lambda + Prefs — needs drop signal + pin to save, and virtual-time tests can't use DataStore files — cost: none
Task 6: Ruling: real TvConnection (wss:3001 → ws:3000) added in tv/TvConnection.kt; falls back to plain ws ONLY when no cert pin is saved yet, never after a pin mismatch — otherwise the pin could be bypassed by downgrading — cost if wrong: a TV that loses wss after pairing needs "Forget pairing"
Task 6: Ruling: while connected, VM re-checks power state every 5 s — TV turned off by its own button keeps the socket open in standby, so the chip would lie — cost: one tiny request per 5 s while app is open
Task 6: Ruling: power()/actions catch all non-cancellation exceptions (not just SsapError) — bad MAC or socket error in wake would crash the app; test wakeFailureDoesntCrash RED→GREEN after making the test see uncaught coroutine errors — cost: errors are silent (chip shows state)
Task 6: complete (tests: :app:testDebugUnitTest full → 67/67)
Task 7: Ruling: phone volume keys go to the TV only while status is On; otherwise they change phone volume as normal — plan said always return true, which would make the keys dead when the TV is off — cost if wrong: none
Task 7: Ruling: OkHttp pingInterval 10 s so a TV that vanishes is noticed — cost: tiny traffic
Task 7: complete (tests: :app:assembleDebug :app:testDebugUnitTest → BUILD SUCCESSFUL, 67/67, 0 warnings)
Final review: fresh reviewer (opus). Re-graded: fix #1 power loop/prompts, #2 SsapClient cancel leak, #3 PointerSocket cancel, #4 dead buttons after wake/pointer drop, #6 slow answer tears link (power now decides by status), #8 unexpected exceptions crash connectLoop. Minors deferred: #5 #7 #9 #10 #11.
Final: fixed #1 power loop after Declined/CertChanged — powerAfterDeclinedDoesntLoopPrompts, powerAfterCertChangedKeepsMessage RED→GREEN
Final: fixed #2 SsapClient cancel leak — cancelDuringPairingClosesSocket, cancelDuringOpenPropagates RED→GREEN
Final: fixed #3 PointerSocket cancel — cancelDuringOpenThrowsCancellation RED→GREEN
Final: fixed #4 dead buttons — wakeFromStandbyReopensButtons, deadButtonSocketGetsReopened RED→GREEN
Final: Ruling: #6 power() decides by status On (re-checked every 5 s) instead of a fresh getPowerState — slow answer no longer turns off-press into wake+reconnect — cost if wrong: pressing power within 5 s of turning the TV off by its own button sends powerOff to a sleeping TV (harmless no-op; next poll fixes chip)
Final: fixed #6 — powerWhenOnTurnsOffEvenIfStateCheckFails RED→GREEN
Final: fixed #8 unexpected exceptions in connectLoop — unexpectedConnectErrorDoesntCrash RED→GREEN; suite 76/76
Final: minor (deferred): #5 chip may flash "TV off" for a moment when the connection drops
Final: minor (deferred): #7 changing the TV IP keeps the old pairing; needs Forget pairing for a different TV
Final: minor (deferred): #9 if the first pairing only works over plain ws, the key goes unencrypted on the LAN
Final: minor (deferred): #10 rare power states (Screen Saver, power-off in progress) count as off
Final: minor (deferred): #11 rotating the phone reconnects (harmless)

2026-11-06: fixed screensaver counted as off. isOn now also accepts "Screen On" and any "Screen Saver*" state (tvpowerd strings). Tests screenSaverCountsAsOn RED→GREEN, powerOffMeansOff; suite 89/89. Not yet seen live during a screensaver.
