# EXECUTE BUILD — Ghost Tap (2026-11-17)

Base: `~/workspace/ghosttap/ui-redesign/` (v3.2 + UI redesign, build #178 green).
This tree: `~/workspace/ghosttap/execute-build/` — work copy only. The
ui-redesign tree was not touched.

The standard (Justin's words): Ghost Tap EXECUTES. It does not narrate what
it did, does not make him configure anything, does not show hex numbers. It
sees the thing, connects to it, holds the connection, and hands him the
controls. Flipper Zero standard, for every function.

## What changed, per file

### 1. Real icons
- `app/src/main/java/com/ghosttap/ui/GhostChrome.kt` — rewritten chrome:
  `GhostTile` now takes a required `icon: ImageVector` and shows a 28dp
  accent icon beside the title; `GhostActionCard` takes an optional `icon`
  (explicit wins, otherwise `iconForAction(title)` picks one from the
  card's words — specific phrases first, fallback `Icons.Filled.Bolt`).
  ~25 explicit Material icon imports (core + extended: Usb, Nfc, Terminal,
  Cable, QrCodeScanner, Memory, PhotoCamera, Search, Wifi, Bluetooth, …).
  No new dependency — `material-icons-extended` was already in
  `app/build.gradle.kts`.
- `app/src/main/java/com/ghosttap/ui/GhostTapApp.kt` — all 7 home tiles pass
  a real icon: USB UART / ESP32 = Usb, VIRTUAL ESP32 = Memory, NETWORK
  TOOLS = Wifi, NFC TOOLS = Nfc, VISUAL INTEL = PhotoCamera,
  INVESTIGATIONS = Search, TERMUX INTEGRATION = Terminal. Every
  `GhostActionCard` on every function page now shows an icon through the
  chrome (explicit icon where set, keyword-matched otherwise).

### 2. Bluetooth scan, plain names
- `app/src/main/java/com/ghosttap/core/DeviceClassifier.kt` — maker-only
  label: when a device says who built it and nothing else, the label is
  `"<MAKER> DEVICE"` (e.g. "APPLE DEVICE" for company 0x004C), Confidence
  LOW, evidence "Maker: Apple". Never a guess about the product kind.
  Insufficient evidence still returns "UNKNOWN BLE DEVICE" / "UNKNOWN
  DEVICE" with Low confidence (standing rule, unchanged). RSSI is still
  bands only (CLOSE / NEAR / FAR / VERY FAR) — never an exact distance.
- `app/src/main/java/com/ghosttap/ui/PlainEnglish.kt` — new pure function
  `plainScanName(name, likely)`: a real advertised name shows as-is; a
  name that is really just an address ("Bluetooth device AA:BB:…", a bare
  MAC, BLE-/BT- synthetic) or blank shows the classifier's plain label
  instead; final fallback "Bluetooth device".
- `app/src/main/java/com/ghosttap/ui/GhostTapApp.kt` — the home TARGETS row
  main line is `plainScanName(...)`; UNKNOWN likely shows a second line
  "Low confidence — not enough clues yet"; any other likely shows only
  when it differs from the main line ("Probably: …"); the raw address is
  small grey `bodySmall` detail underneath, never the title. The same
  plain name is used on the device detail line, the DEVICE MAP header, the
  history name map, the baseline-diff names, and the auto-bring-up title.
  Row tap already went to TargetScreen, which auto-brings-up BLE on entry
  and lands on the control screen — unchanged.
- `GhostTapViewModel.kt` was deliberately NOT changed for this (see
  "Protected files" below): the old "Bluetooth device AA:BB:…" fallback
  is caught by `plainScanName` at the display layer, so the user-visible
  result is identical without touching a protected file.

### 3. Virtual ESP32 runs real board jobs
- `app/src/main/java/com/ghosttap/ui/VirtualEsp32Screen.kt` — all
  user-visible "practice board" wording is gone: "A real board running on
  this phone — its software actually executes here." The page keeps its
  four real cards (check the board, use the board, check its status, check
  its light pin). NEW "REAL BOARD" panel after the GPIO panel: "Flash a
  real board" and "Talk to a real board" — both open the USB screen
  (`onOpenRoute("usb_uart")`), where the board's own in-app flasher
  (Esp32Flasher over CH340 USB-serial) writes software onto a plugged-in
  board and the message box talks to it. Op ids (`vesp32.use-practice-board`
  etc.) and the stable `virtual:practice-board` target id were NOT renamed.
- `app/src/main/java/com/ghosttap/UsbUartViewModel.kt` — `confirmFlash()`
  is now one tap: if the first sync fails, it puts the board in download
  mode itself (`enterDownloadMode()`) and retries sync once before saying
  "The board isn't answering". The separate "PUT BOARD IN LOAD MODE" tap
  is no longer needed first. The flash confirmation dialog is NOT bypassed
  — flashing wipes the board.
- HONEST LIMIT, not faked: esptool-in-Termux cannot flash a board. Termux
  has no USB access on non-rooted Android (no /dev/ttyUSB), so an esptool
  flash path would be a lie. The in-app Esp32Flasher over CH340 is the
  real flash path and is what the USB screen runs. No esptool install was
  added.

### 4. Visual Intel auto-connect
- `app/src/main/java/com/ghosttap/VisualActions.kt` — new
  `describeVisualThing(f)`: "a barcode" / "a Wi-Fi code" / first marking /
  first readable line (40 chars) / "something in this picture". Never
  invents.
- `app/src/main/java/com/ghosttap/VisualIntelViewModel.kt` — new
  `autoActedFindingIds` set + `alreadyAutoActed(id)` / `markAutoActed(id)`,
  shared by the screen and the ops so one photo is never acted on twice.
- `app/src/main/java/com/ghosttap/core/ops/OperationAreaHardware.kt` —
  `executeVisualAction` marks the finding acted-on before dispatch; the
  "ble" branch now connects through `intel.bringUpTarget(BringUpTarget(BLE,
  bare MAC, title), autoNavigate = true)` — same call the TV flow makes —
  instead of a bare connect + manual navigate; both empty-action messages
  (capture-and-act, act-on-last-photo) now say "I can see it's [thing],
  but there's nothing to connect to."
- `app/src/main/java/com/ghosttap/ui/VisualIntelScreen.kt` — new
  `LaunchedEffect(finding?.id)` auto-act: a FRESH finding (not the one
  already on screen when opened) runs its first suggested action by
  itself — a Bluetooth address connects through bring-up and lands on the
  control screen; a Wi-Fi code joins; a link fetches. One exception: a
  Wi-Fi code whose password the picture didn't show waits for one typed
  password below and says so. Empty actions set the plain-words
  no-connection note. `runVisualAction` "ble" branch also uses bring-up
  (old direct connect kept only when the thinking engine is null).
  The "nothing connects from a picture" text is gone everywhere on this
  screen. No history/"is this familiar" lookup on the visual path
  (verified: none exists there).

### 5. Termux always on, never set up
- `app/src/main/java/com/ghosttap/ui/TermuxScreen.kt` — ExplainerCard
  reworded ("It's part of Ghost Tap now — always there when you open the
  app."); "install it from F-Droid first" → "Termux isn't on this phone";
  "see the setup steps below" → "Not yet"; capability On/Off toggle
  buttons REMOVED (status text only — nothing on this screen gates what
  Ghost Tap can use); YOUR SCRIPTS empty state no longer tells the user
  to write a script in `~/.termux/tasker/`; the SETUP STEPS panel and
  "Set it up" button are REMOVED — replaced by a status-only line
  ("Termux is ready." / "Termux isn't on this phone." / "Ghost Tap isn't
  allowed to run things in Termux yet." / "The helper tools aren't in
  Termux yet, or the check hasn't run."); the whole SETTINGS panel
  (useTermuxInPlans / keepHistory toggles) is REMOVED from the UI —
  defaults stay on. Termux bridge logic, tool-picking intelligence, and
  the one-paste scripts are untouched. `setupAction` / `setupMissingLine`
  in TermuxViewModel are now unused by the UI (dead code, left in place).
  Sweep confirmed: no "setup" / "one-paste" visible strings remain in
  `ui/`.

### 6. Every function executes — audit
- Home: the v3.2 do-it command box (proven in the #176 field test) is the
  hero; tiles are one tap to each function.
- USB UART / ESP32: flash is one tap now (see §3); connect / send / read
  / watch-replies are real ops.
- VIRTUAL ESP32: real board jobs on the page (see §3); the guest board
  itself runs real software.
- NETWORK TOOLS: SCAN MY NETWORK, LIVE DEVICES — TAP ONE TO CONNECT,
  PING, TCP CONNECT+SEND, UDP SEND+LISTEN, PORT SCAN, HTTP FETCH, GHOST
  AP — all real actions, no plan step.
- NFC TOOLS: all 10 numbered panels (read, copy, write text/link, clone,
  erase, pretend-to-be, raw console, Mifare, tag-memory write) are real
  ops.
- VISUAL INTEL: auto-connect (see §4).
- INVESTIGATIONS: plans exist by design (master prompt) but they RUN the
  steps themselves through the op runner — a plan is never a report the
  user has to tap through.
- TargetScreen WHAT YOU CAN CONTROL: already honest — when a connected
  device exposes nothing readable/writable it says "Connected, but this
  device exposed nothing you can read or write — nothing to control
  here." (verified in GhostTapApp.kt, unchanged). "Read everything" /
  "Turn on all live updates" / "Read the most interesting one" are
  one-tap whole-device moves through the real read/subscribe paths.

## Protected files — verification

`sha256sum -c PROTECTED-BASELINE.sha256` in this tree: **all 7 OK**
- `app/src/main/java/com/ghosttap/core/esp32/VirtualEsp32Client.kt`
- `app/src/main/java/com/ghosttap/GhostTapViewModel.kt`
- `app/src/main/java/com/ghosttap/data/IntelDb.kt`
- `app/src/main/java/com/ghosttap/core/InvestigationLoop.kt`
- `app/src/main/java/com/ghosttap/core/BringUp.kt`
- `build.gradle.kts`
- `app/build.gradle.kts`

Note: the first pass of this build edited GhostTapViewModel.kt (the three
"Bluetooth device $address" name fallbacks). That broke the protected
check, so those edits were REVERTED to byte-identical and the same result
was achieved in the UI layer via `plainScanName` instead. The protected
file is untouched.

## Files changed (11)
`ui/GhostChrome.kt`, `ui/GhostTapApp.kt`, `ui/PlainEnglish.kt`,
`ui/VisualIntelScreen.kt`, `ui/TermuxScreen.kt`, `ui/VirtualEsp32Screen.kt`,
`core/DeviceClassifier.kt`, `UsbUartViewModel.kt`, `VisualIntelViewModel.kt`,
`VisualActions.kt`, `core/ops/OperationAreaHardware.kt`.
(`GhostTapViewModel.kt` was edited in a first pass and then reverted to
byte-identical — it is not among the changed files.)

No other file in the tree differs from ui-redesign. No new permissions. No
new dependency. No behavior/logic/navigation change outside the six items
above. The five missing tools (Traceroute, DNS Lookup, Speed Test, Wi-Fi
Analyzer, QR Generate) were NOT added — separate later decision.
Screen-see-and-tap (accessibility) was NOT built — that is the next build
after this one.

## Verification done here (no compiler on this machine — the build runs on AndroidForge)
- Protected baseline: all 7 OK (above).
- Brace/paren balance on every edited file: identical counts to the
  original tree (the two flagged imbalances are pre-existing in the
  original files — raw strings / regexes fool the crude counter).
- Call-site scan: no `GhostActionCard` call passes more than 5 positional
  args, so the new 6th `icon` parameter (defaulted) cannot break a call.
  `GhostTile` has exactly 7 call sites, all passing `icon`.
- Type checks by hand: `allTargets` is `List<ScanTarget>`; `context.intel`
  exists on `OperationContext` (same use as other ops);
  `bringUpTarget(target, autoNavigate = true)` matches `IntelViewModel`;
  bring-up ids are bare MACs (same as the proven `connectTvCandidate`
  pattern); `markAutoActed` / `alreadyAutoActed` / `describeVisualThing`
  exist where used; `enterDownloadMode()` and `sync()` are blocking
  functions called inside the existing IO coroutine.
- Existing tests: no test asserts the old maker-only → UNKNOWN behavior
  (DeviceClassifierTest has no Apple/manufacturerId/UNKNOWN assertions);
  TvDoItTest uses pre-set `likely` fields, unaffected by the classifier
  change. Tests were NOT run (no Android SDK here).

## What could NOT be built (honest)
- **esptool flash through Termux**: impossible, not just unbuilt — Termux
  cannot reach a USB serial port on non-rooted Android. The real flash
  path is the in-app Esp32Flasher, which is what the new REAL BOARD panel
  opens. Do not demo an esptool flash as working.
- **A Bluetooth-connected TV still has no real controls** (same honest gap
  as v3.2): TVs expose nothing over BLE GATT and there is still no Wi-Fi
  TV protocol code. It will honestly say "nothing to control here".
- **Compile not proven here**: hand-traced + scanned only. The AndroidForge
  build is the first real compile check.

## On-device test list (plain words)
1. Open the app. Every tile on home has a picture icon next to its name.
   Tap each tile — each function's page opens, and its cards each have an
   icon too.
2. Turn on Bluetooth, tap SCAN (home). Every device shows a plain name
   ("Apple device", "TV", a real name) — never a long hex number as the
   main line. A hex number may show small and grey underneath. A device
   with no clues shows "UNKNOWN BLE DEVICE" and "Low confidence — not
   enough clues yet".
3. Tap a device in the list. It connects and opens its control screen
   with what it can actually do laid out. A device with nothing to
   control says so plainly.
4. Virtual ESP32 page: no "practice board" words anywhere. The REAL BOARD
   panel has "Flash a real board" and "Talk to a real board" — both open
   the USB screen.
5. USB screen: pick a software file, tap LOAD IT ONTO THE BOARD once. It
   puts the board in download mode itself and flashes — no separate
   load-mode tap first. (The "are you sure" box still appears — flashing
   wipes the board.)
6. Visual Intel: point the camera at a barcode / a device label with a
   Bluetooth address / a Wi-Fi code. It reads it and connects on its own
   — no tap, no report. Point it at something with nothing to connect to
   (a plain photo, a motherboard with no address): it says "I can see
   it's a [thing], but there's nothing to connect to."
7. Termux page: it says "Termux is ready." No setup steps, no "set it up"
   button, no mention of a one-paste. The tools are all there. Nothing
   asks to be switched on.
8. Network Tools: tap SCAN MY NETWORK, tap a live device — it connects.
   NFC: read a tag, copy a tag, write text to a tag — each does the thing.
9. Investigations: open a case file, tap a check card — it runs the
   checks itself.
