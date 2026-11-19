# UNIFIED BUILD — Ghost Tap + Termux as one thing (2026-11-18)

Base: `~/workspace/ghosttap/execute-build/` (execute build #179 + tile fix #180 + stall fix #181).
This tree: same work copy. Nothing else in the tree was touched.

The standard (Justin's words, 2026-11-18 01:07): Ghost Tap from now on
means Ghost Tap AND Termux integrated within each other. No Termux
section. No configuring on any function. Virtual ESP32 firmware already
inside Ghost Tap — Load just loads. The command box does the job from
ANY page. Intelligence must NOT rely on itself: its smarts come from
outside itself — from Termux, from what it actually does, from growing
with Termux. Refined 01:15: the two must NOT rely on each other either
— neither one gated on or waiting for the other; they execute, grow,
adapt, communicate, and expand TOGETHER.

## What changed, per file

### 1. Virtual ESP32 ready to go
- `app/src/main/assets/virtualesp32-test-micropython-4mb.bin` (NEW, 4 MB,
  SHA256 7adfc0b15179a8d242ac856bce15516472f046df67c61c836a70bdb3311abaec) —
  the first-pick MicroPython v1.28.0 test image, bundled inside Ghost Tap.
- `app/src/main/java/com/ghosttap/core/esp32/BundledFirmware.kt` (NEW) —
  `loadBundledFirmware(context, client)`: copies the bundled image from
  assets to app-private cache, validates it (same FirmwareValidator as a
  picked file), shares it via FileProvider, loads it through the existing
  `client.loadFirmware(uri)` call. Returns the check line in plain words.
  Call from a background thread (4 MB file write).
- `app/src/main/java/com/ghosttap/ui/VirtualEsp32Screen.kt` — the file
  picker is gone: LOAD SOFTWARE loads the bundled image directly (off the
  main thread, check line shown as "Last check"). REMOVE SOFTWARE and
  BUILT-IN TEST SOFTWARE are unchanged. The "Is the board app installed?"
  card stays — the guest runtime is the separate board app (see honest
  limits). The REAL BOARD panel (in-app CH340 flash) is unchanged.
- `app/src/main/java/com/ghosttap/core/ops/OperationAreaHardware.kt` —
  `vesp32.use-practice-board` ("Use the board") loads the bundled image
  when nothing is loaded yet (falls back to the board app's built-in test
  software only when there is no app context). The "pick your own file"
  wording is gone.
- `app/src/main/java/com/ghosttap/core/ops/Operation.kt` — OperationContext
  gains `appContext: Context?` so an operation can read a bundled file.

### 2. No Termux section
- `app/src/main/java/com/ghosttap/ui/TermuxScreen.kt` — DELETED.
- `app/src/main/java/com/ghosttap/ui/GhostTapApp.kt` — the TERMUX
  INTEGRATION tile is gone from home (six tiles now); the `termux` route
  is gone; `onOpenTermux` is gone. Home now runs
  `termuxViewModel.autoDiscoverOnce()` when it opens, so the discovery
  chain (link + probe) still runs once per session with no screen.
- `app/src/main/java/com/ghosttap/ui/CommandBox.kt` — the one-paste setup
  is reachable from the status line only: when Termux is installed but
  the helper tools aren't in yet, the box shows "Set up Termux — one
  paste", which copies the setup and opens Termux; the outcome is a line
  under the box. Never shown when Termux is ready.
- `app/src/main/java/com/ghosttap/TermuxViewModel.kt` —
  `copySetupPasteAndOpenTermux()` is public now and returns the outcome
  line. One stale string fixed ("see the setup steps on the Termux
  screen" → "the home screen says what's missing").
- `app/src/main/java/com/ghosttap/core/ops/OperationAreaTermux.kt` —
  `screen.termux` no longer navigates anywhere (the route is gone); it
  checks Termux is there and says so in plain words. `termux.run-command`
  no longer points at the Termux screen: with no command typed it fails
  honestly ("type it in the box at the top of the screen"). The
  Termux bridge logic, tool-picking intelligence, and one-paste scripts
  are untouched.

### 3. Command box on every page
- `app/src/main/java/com/ghosttap/ui/GhostTapApp.kt` — the status line
  (Brain · VirtualESP32 · Termux · Network) moved from the home block to
  the app level so every page's box shares it. The same do-it box
  (CommandBox, same engine, same state) is now rendered at the top of
  USB UART / ESP32, VIRTUAL ESP32, NETWORK TOOLS, NFC TOOLS, VISUAL
  INTEL, and INVESTIGATIONS, plus home. Clearing and retrying are shared
  helpers (`clearDoItCommand`, `retryDoItCommand`), not one copy per page.
- `app/src/main/java/com/ghosttap/ui/UsbUartScreen.kt`,
  `VirtualEsp32Screen.kt`, `VisualIntelScreen.kt`,
  `InvestigationsScreen.kt`, and the in-file `NetworkToolsScreen` /
  `NfcToolsScreen` in `GhostTapApp.kt` — each takes the same optional
  command-box params (all defaulted; standalone use still compiles) and
  renders the shared `CommandBox` at the top.

### 4. Zero-config sweep
- No setup screens, no "configure first" gates, no "open the other app"
  steps remain on any function page. The only honest prerequisites left
  are Android's own (USB permission prompt; Termux installed for Termux
  tools; the board app installed for the guest runtime) and each is
  stated in plain words where it applies. The real-board USB flash flow
  (pick a file on the USB screen, one tap to flash) is unchanged — that
  file is the board's own software, not Ghost Tap setup.

## Protected files — verification

`sha256sum -c PROTECTED-BASELINE.sha256` in this tree: **all 7 OK**
- `app/src/main/java/com/ghosttap/core/esp32/VirtualEsp32Client.kt`
- `app/src/main/java/com/ghosttap/GhostTapViewModel.kt`
- `app/src/main/java/com/ghosttap/data/IntelDb.kt`
- `app/src/main/java/com/ghosttap/core/InvestigationLoop.kt`
- `app/src/main/java/com/ghosttap/core/BringUp.kt`
- `build.gradle.kts`
- `app/build.gradle.kts`

#181 stall fix still in place: home redraw throttled with
`snapshotFlow { viewModel.uiTick }.sample(500)` and the baseline
snapshot loaded once per home entry (`remember { ... }`, no tick key).

## Files changed (13)
`ui/GhostTapApp.kt`, `ui/VirtualEsp32Screen.kt`, `ui/UsbUartScreen.kt`,
`ui/VisualIntelScreen.kt`, `ui/InvestigationsScreen.kt`,
`ui/CommandBox.kt`, `core/esp32/BundledFirmware.kt` (new),
`core/ops/Operation.kt`, `core/ops/OperationAreaHardware.kt`,
`core/ops/OperationAreaTermux.kt`, `TermuxViewModel.kt`,
`app/src/main/assets/virtualesp32-test-micropython-4mb.bin` (new),
`ui/TermuxScreen.kt` (deleted).
No new permissions. No new dependency. No behavior/logic change outside
the four items above. The five missing tools (Traceroute, DNS Lookup,
Speed Test, Wi-Fi Analyzer, QR Generate) were NOT added — piece 2.
Screen-see-and-tap was NOT built — piece 2.

## Verification done here (no compiler on this machine — the build runs on AndroidForge)
- Protected baseline: all 7 OK (above).
- #181 fix present (sample(500) + once-per-entry baseline).
- Brace/paren balance on every edited file: identical to the original
  tree (the two flagged imbalances are pre-existing in the original
  files — raw strings fool the crude counter; verified identical in
  ui-redesign and v3.2).
- Call-site scan: all 7 command-box call sites pass the same 12 params;
  every screen signature has matching optional params with defaults;
  `TvCandidate` imported where used; `TermuxScreen`, `onOpenTermux`,
  `composable("termux")`, `navigate("termux")`, `firmwarePicker` have zero
  remaining references (MainActivity's firmware picker launcher stays —
  it is the USB screen's real-board flash picker).
- Type checks by hand: `loadBundledFirmware(Context, VirtualEsp32Client)`
  matches the helper; `client.loadFirmware(Uri)` is the existing client
  call; `copySetupPasteAndOpenTermux(): String` callers ignore or use the
  return; `autoDiscoverOnce()` is a plain function, safe in LaunchedEffect;
  `OperationContext.appContext` is nullable with a null default, so all
  other constructions still compile.

## What could NOT be built (honest)
- **The guest's own network is not wired by Ghost Tap.** "VNIC" in this
  codebase is the brain's learning engine (VnicEngine), not a virtual
  network card — there is no guest-network code in this tree to wire.
  The guest is a QEMU board in the separate board app: its MicroPython
  socket calls go through that app's QEMU user networking, which Ghost
  Tap cannot see, control, or verify. What IS real: Ghost Tap runs real
  network / Wi-Fi / Bluetooth commands on the phone (Network Tools,
  Termux, BLE) and talks to the guest over UART — tell the guest what to
  fetch over UART and read the reply back. No new network stack was
  invented. Do not demo the guest reaching a network as Ghost Tap's own
  work until the board app's networking is verified on the phone.
- **The board app is still the guest runtime.** Ghost Tap never opens it
  and Load never asks for it, but the guest executes inside the separate
  Virtual ESP32 app. If it isn't installed, the board screen says so
  plainly. Bundling the whole guest runtime inside Ghost Tap would mean
  rewriting the protected client — out of scope for piece 1.
- **One Termux paste after a fresh install is still needed.** Android
  will not let one app write inside another. The paste is now one tap
  from the status line (copies + opens Termux), and after that nothing.
- **esptool flash through Termux**: still impossible (no USB access on
  non-rooted Android). The in-app CH340 flasher is the real flash path.
- **A Bluetooth-connected TV still has no real controls** (same honest
  gap as v3.2).
- **Compile not proven here**: hand-traced + scanned only. The
  AndroidForge build is the first real compile check.

## On-device test list (plain words)
1. Open the app. Six tiles on home — no Termux tile anywhere. The status
   line says Termux is ready (or says plainly what's missing).
2. Tap each tile — each function's page opens with the same command box
   at the top. Type "find me a tv" on the Network Tools page: it runs
   there, same as home.
3. Virtual ESP32 page: tap LOAD SOFTWARE — it loads straight away, no
   file picker, no other app. Tap START — the board runs.
4. If Termux isn't ready, the box shows "Set up Termux — one paste":
   tap it, paste once in Termux, press Enter, come back — the line
   under the box says what happened.
5. USB screen: flash a real board still works the same (pick a file
   there, one tap to flash).
6. Open the app fresh — it opens smooth, no lag (the #181 fix is in).
