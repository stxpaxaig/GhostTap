# Ghost Tap UI redesign — presentation layer only (2026-11-17)

Built on a copy of the v3.2 tree (`termux-work-v3.2` → `ui-redesign`).
No build triggered. No new Gradle dependencies. No function removed.
The 7 protected files are byte-identical to v3.2 (hashes in
`PROTECTED-BASELINE.sha256` at the tree root, re-verified after editing).

## Why — evidence from Justin's phone (build #176)

He typed "find me a tv close by" into the OLD intent box
("WHAT ARE YOU TRYING TO DO?"), not the new v3.2 command box at the
top — two command boxes on home confused him, and the old box's stale
"Close this case file" outcome was still showing. His directive
(2026-11-17 18:31–18:44): the ChatGPT mockup image is a GENERAL
REFERENCE only (dark background, neon polish, every capability
understandable without a manual) — do NOT copy its exact imagery.
Redesign presentation + interaction only, home AND every function's
actual working page, same visual standard everywhere. Keep all
existing functionality and integrations. And (18:44, his words): "no
barriers or limitations" — nothing gets hidden behind extra taps or
gating; everything stays one tap away, as now.

## 1. Shared chrome — one visual standard (`ui/GhostChrome.kt` NEW)

- Dark background (`Bg` 0xFF050505), neon green primary (`Accent`
  0xFF44FF99), cyan secondary (`Cyan` 0xFF4DD8FF, NEW). No new color
  per screen; warning amber 0xFFFFAA44 kept exactly as-is.
- `GhostTile(title, description, onClick)` — one function as a tile:
  title (kept exactly) + one plain-words line saying what it does +
  a cyan "Tap to open" line.
- `GhostActionCard(title, description, buttonText, onClick, content)`
  — one action inside a function page as a card: title (kept
  exactly) + one plain-words description + its button; or inputs +
  button in the `content` slot when the action needs typing first.
- `PanelBox` (restyled in `GhostTapApp.kt`): same dark fill + a faint
  neon-green edge. `TerminalButton` (restyled): same dark fill + a
  neon-green edge + rounded corners. Both keep their exact signatures,
  so every existing call site gets the new look for free.
- `ExplainerCard` (restyled in `PlainEnglish.kt`): the first line —
  the point of the screen — is neon green; the rest is dim.

### API trap — read this before editing `GhostActionCard`

The LAST parameter is the `content` slot, so a trailing lambda binds
to `content`, NOT to the button handler. A handler passed as a
trailing lambda runs during composition and no button renders (the
button only appears when BOTH `buttonText` and `onClick` are set).
Always pass the handler as a NAMED argument:
`GhostActionCard(title, description, buttonText = "...",
onClick = { ... })`. Trailing lambdas are only for `content` slots.

## 2. Home (`ui/GhostTapApp.kt` — `HomeScreen`)

- The seven function buttons are now seven `GhostTile`s, same titles,
  same order, each with one plain-words line (the same words the old
  paragraph used): USB UART / ESP32 "Talks to a wired board.",
  VIRTUAL ESP32 "Runs pretend hardware with no board needed.",
  NETWORK TOOLS "Tests Wi-Fi connections.", NFC TOOLS "Reads, writes,
  copies, and pretends to be tap cards.", VISUAL INTEL "Points the
  camera at boards, chips, labels, and devices to read text and scan
  barcodes.", INVESTIGATIONS "Turns what the camera saw into a
  planned, step-by-step check using Ghost Tap's real tools.", TERMUX
  INTEGRATION "Asks the Termux app on this phone to run approved
  helper scripts — like checking Python or the network — and brings
  the answer back."
- The old plain-English paragraph is gone — its words now live on the
  tiles, no duplication.
- The OLD `IntentBox` ("WHAT ARE YOU TRYING TO DO?") is REMOVED from
  home. The v3.2 command box ("TELL GHOST TAP WHAT YOU WANT" + "Do
  it") stays the home hero action, unchanged — it is the do-it flow,
  field-tested working on build #176. No separate "Tap to Activate"
  button was added.
- `onIntent` / `intentAnswer` stay in the `HomeScreen` signature
  (unused; legal Kotlin) so the call site does not change. The
  "Find devices" / "Investigate the unfamiliar" / "Stop finding" /
  "Activity log" row, the baseline panel, the targets list, and the
  live-target card are untouched.

## 3. Network Tools (`ui/GhostTapApp.kt` — `NetworkToolsScreen`)

- `IntentBox` removed (same reason as home). The page lists its own
  actions instead. `onIntent` / `intentAnswer` stay in the signature,
  unused.
- "Investigate the unfamiliar" is now a `GhostActionCard` (same
  `discoverNetwork()` handler). The tool panels (SCAN MY NETWORK,
  WHAT'S TALKING, GHOST AP, PING, TCP, UDP, PORT SCAN, HTTP) were
  already titled cards with plain-words descriptions — they keep
  their shape and get the unified `PanelBox` chrome. The live-device
  rows, Wi-Fi password flow, and RESULTS log are untouched.
- NEW surface (op already existed, no button before): "Open Wi-Fi
  settings" card (`visual.open-wifi-settings`, op title verbatim) —
  opens the phone's Wi-Fi settings so you can join a network by hand.
- RESULTS now also shows the last command answer
  (`intelVm.lastCommandAnswer`, format "Title — answer"), so an
  action run through the shared runner always shows its answer on
  this page.
- The hotspot tools are surfaced as they already were (GHOST AP:
  "Start hotspot" / "Stop hotspot" / "CHECK WHO JOINED"). Port scan,
  ping, "Find devices on my Wi-Fi", "What's talking" all already had
  their panels — nothing was hidden, nothing added beyond the Wi-Fi
  settings card.

## 4. NFC Tools (`ui/GhostTapApp.kt` — `NfcToolsScreen`)

- `IntentBox` removed. `onIntent` / `intentAnswer` stay in the
  signature, unused.
- The ten numbered panels (1. READ A TAG OR CARD … 10. TAG MEMORY
  WRITE) were already titled cards with "What this does / what it
  unlocks / honest limit" lines — they keep their shape and get the
  unified `PanelBox` chrome. All gates, the double-tap erase confirm,
  the raw console, tap-run, and the LAST RESULT line are untouched.
- NEW surface (op already existed, no button before): "Set up this
  blank card" in panel 6 (ERASE TAG), wired to the existing
  `viewModel.nfcFormat()` behind the same gate as Erase
  (`nfc.set-up-blank-card` — the format half of erase; a wiped card
  set up here can hold your words or a link).
- Everything the page lists was already there: "Read this card",
  "Copy last tapped card" + "Write saved onto this card" (the
  card-to-card copy), "Pretend to be the card I just read" + the ON/OFF
  state line, "Write words" / "Write link", "Erase card", "Read card
  blocks" (long-range cards).

## 5. USB UART / ESP32 (`ui/UsbUartScreen.kt`)

- `IntentBox` removed. Local color duplicates deleted (same-value
  swaps to shared `Bg` / `GhostCard` / `Accent` / `Dim`); warning
  amber kept. The `UartPanelBox` helper keeps its name (body now uses
  shared colors); `UartButton` kept for BACK / "What do you want to
  do?" (navigation, not tool actions).
- Every action is now a `GhostActionCard` with the same handler:
  "Investigate the unfamiliar" (`discoverUsb()`), USB BRIDGE (CHECK
  AGAIN / ALLOW USB ACCESS / CONNECT / DISCONNECT), SERIAL TERMINAL
  (speed, SEND, CODE/TEXT MODE, CLEAR MESSAGES, quick messages,
  ADD A NEW LINE), CONTROL LINES (Wire DTR / Wire RTS), ESP32
  PROGRAMMER (TEST THE BOARD, load mode, PICK SOFTWARE FILE, flash
  spot, LOAD IT ONTO THE BOARD, CHECK WHAT'S ON THE BOARD), DETAILS /
  RAW LOG (SHOW/HIDE DETAILS, COPY THE LOG). Auto-connect and the
  live-target card untouched.
- NEW surfaces (ops already existed, no button before — titles from
  the ops file, wired to `onRunLiveCommand("<id>", null)`):
  `usb.describe-board` ("What is this plugged-in board?"),
  `usb.watch-replies` ("Is the board talking back?"),
  `usb.wires-what-they-do` ("What do the control wires do?"),
  `usb.read-board-setting` ("Read the board's ID"),
  `usb.check-software-file` ("Check the software file").
- Known trade-off: the DTR/RTS toggle cards lose the old
  filled-when-ON highlight (`GhostActionCard` has no highlight
  param); ON/OFF state is still in the title text.

## 6. Virtual ESP32 (`ui/VirtualEsp32Screen.kt`)

- `IntentBox` removed. This screen had NO `ExplainerCard`, so one was
  ADDED at the top (3 lines: the practice board runs on the phone, not
  real hardware; the bridge connects by itself on open; each card runs
  one thing when tapped; starting software stays one tap).
- Every action is now a `GhostActionCard` with the same handler:
  "Investigate the unfamiliar", FIRMWARE (LOAD / REMOVE / BUILT-IN
  TEST SOFTWARE), CONNECTION (CONNECT / DISCONNECT), GUEST CONTROL
  (START / STOP / RESTART), UART TERMINAL (SEND / TEST REPLY / CLEAR
  MESSAGES), GPIO (SWITCH PIN 4). Cards stack vertically — full-width
  cards in a `Row` would overflow. TARGET panel, live-target card,
  auto-connect, and firmware checks untouched.
- NEW surfaces (ops already existed, no button before):
  `vesp32.check-installed` ("Is the practice-board app installed?"),
  `vesp32.use-practice-board` ("Use the practice board"),
  `vesp32.check-practice-status` ("How is the practice board doing?"),
  `vesp32.check-light-pin` ("What is the practice board's light
  doing?").

## 7. Visual Intel (`ui/VisualIntelScreen.kt`)

- `IntentBox` removed. ExplainerCard 2nd line now says to tap a card
  and that each card says what it does before it runs.
- LIVE CAMERA: "TAKE PHOTO & READ IT" / "PICK FROM GALLERY" row is
  now 4 cards: "Take a photo and do what it suggests"
  (`visual.capture-and-act` — ops-file title, not the brief's
  paraphrase), "Read a photo from the gallery"
  (`visual.read-gallery-photo`), "Open the camera"
  (`visual.open-camera`), "Turn the camera off"
  (`visual.stop-camera`).
- WHAT WAS FOUND: 5 new cards — `visual.fetch-link`,
  `visual.copy-from-photo`, `net.check-spotted-address`,
  `visual.act-on-last-photo`, `visual.open-wifi-settings`.
- WHAT YOU CAN DO WITH THIS: Wi-Fi join (no password) is a card with
  the password field + JOIN button in its content slot; every
  suggested action (`VisualActionRow`) now renders as a
  `GhostActionCard` (title, description, "Then:" line).
- "Look into the last photo" card now runs the `visual.investigate-photo`
  op (same outcome as the old direct `onInvestigate` path — starts an
  investigation of what the camera last found; the op's preparation
  fails in plain words when no photo was taken yet). "COPY FULL TEXT"
  is a plain card, same direct handler. SAVE AS TARGET PROFILE is a
  card (name field + SAVE IT, direct handler so the typed name is not
  ignored). SAVED TARGET PROFILES: "Reload the saved profiles" card
  (`visual.reload-profiles`); profile RENAME / SAVE NOTES / DELETE are
  stacked cards, same handlers.
- Bottom of screen: 3 new cards — `intel.what-is-this`,
  `intel.search-the-web-for-this`, `learn.save-with-name` (op
  preparation fails gracefully in plain words when no device/case
  file is open). CAMERA ACCESS panel, per-item COPY buttons, LINK
  PREVIEW, report/live-target blocks untouched.
- Two runtime caveats (unverified — see test list): (1)
  `visual.open-camera`'s op navigates with plain `nav.navigate(
  "visual_intel")` and no `launchSingleTop`, so tapping it while
  already on the screen may stack a duplicate destination. (2)
  `visual.stop-camera` unbinds the camera, but the preview's
  `AndroidView` update lambda re-calls `bindCamera` on recomposition,
  so the camera may come straight back on.

## 8. Investigations (`ui/InvestigationsScreen.kt`)

- `IntentBox` removed; four required-op cards added in its place
  (always visible, above STATUS), wired to `onRunOperation`:
  `intel.look-into-device` ("Look into this device"),
  `intel.search-the-web-for-this` ("Search the web for this" —
  ops-file title; the brief's "Look this up on the internet" is a
  paraphrase, not the op title), `intel.list-case-files` ("List the
  case files"), `intel.show-all-history` ("Show all history").
- Every action group is now cards with the same handlers: "Investigate
  the unfamiliar", START A NEW INVESTIGATION (fields + CREATE CASE
  FILE), saved-investigation rows (per-case cards), plan steps
  (per-step cards: RUN / APPROVE & RUN, risk badge and "(not checked
  yet)" preserved; only wording tweak: `" ${riskLevel}"` →
  `"Risk: ${riskLevel}"`), plan buttons (RUN THE CHECKS / MAKE A NEW
  PLAN / CLOSE THIS CASE / MARK AS DONE), AUTO INVESTIGATE (start/stop
  + pending-approval card), CHECK THIS NEW CONTROL (approve/discard),
  TIMELINE (add-note card), SAVE THIS PLAN, CHECK WHAT ELSE IT CAN
  DO, PROCEDURES (use/delete per plan), KNOWLEDGE SEARCH, THINKING
  ENGINE (SAVE / CLEAR / CHECK THE AI CONNECTION — the duplicate
  explainer text was folded into the card description), INVESTIGATE
  SOMETHING, LIVE TARGETS ("Check for new targets").
- The timeline, step/state logic, case-file handling, plan display,
  history lists, and `VnicStatusPanel` are untouched. BACK and "What
  do you want to do?" stay plain buttons (navigation, not actions).

## 9. Termux (`ui/TermuxScreen.kt`)

- `IntentBox` removed. Local `TermuxPanel` deleted (same-value swap
  to shared `GhostCard`); amber kept. ExplainerCard 3rd line now
  points at the cards and WHAT IT SAID BACK.
- Every action is now a card with the same handler: "Investigate the
  unfamiliar" (`discoverTermux`), IS TERMUX THERE? ("Check Termux is
  on this phone" — kept `checkAgain` handler), "Check what Termux can
  do" (kept `runProbe` — the op only reports the last check),
  RUN SOMETHING ("Run a Termux script", same handler incl. args),
  TYPE YOUR OWN COMMAND ("Run a command in Termux", same
  `runFreeCommand(commandText)` — the op would otherwise prompt for a
  choice or fail with "no command given"), YOUR SCRIPTS ("Check what
  scripts are in Termux", kept `refreshLibrary`).
- NEW surfaces (ops already existed, no button before):
  `termux.check-bridge-health` ("Check the link to Termux"),
  `termux.list-library` ("List your Termux scripts").
- Untouched: probe results, capabilities list + On/Off toggles,
  script lists, past runs, setup panel, SETTINGS panel,
  auto-discover, ALL bridge/token/port logic. Nothing about how Ghost
  Tap interacts with Termux changed — bridge, intelligence, op
  routing, helper scripts, free-command / run-script path all exactly
  as they were.
- Cards whose action needs on-page input keep their DIRECT handler
  instead of `onRunLiveCommand`, so the input is not ignored (same
  judgment as Visual Intel's SAVE IT). Card titles still use the
  ops-file titles.

## Deliberately left out

- The five tools that do NOT exist in the tree — Traceroute, DNS
  Lookup, Speed Test, Wi-Fi Analyzer (as a named tool), QR Generate —
  are NOT in this build. Justin first said to build them as
  Ghost Tap + Termux together (18:41), then REVERSED that (18:44):
  do not change Ghost Tap and how it interacts with Termux, do not
  alter any bit of that. The "Ghost Tap + Termux" point is context
  only: the Termux screen and the existing Termux ops are presented
  as part of the app (see §9). No new Termux-backed operations were
  added; no new helper script was added to the one-paste file, so no
  re-paste is needed for this build.
- NO Settings screen, NO History screen, NO bottom nav (the mockup's
  panels for those were not built — zero-config forbids behavior
  toggles, and those screens do not exist).
- NO invented capabilities from the mockup: no Hotspot button was
  added — the hotspot tools already existed (GHOST AP on Network
  Tools, `net.start-hotspot` / `net.stop-hotspot` /
  `net.check-hotspot-joined`) and are surfaced as they were. No
  NFC/RFID clone button was added — the card-to-card copy already
  existed (NFC panel 5, `nfc.copy-to-second-card`) and is surfaced
  as it was.
- The `IntentBox` is KEPT on the device TargetScreen
  (`GhostTapApp.kt`, the one remaining call site) — that page is a
  device's own controls, not a function page, and its box is scoped
  to the device. The "What do you want to do?" buttons (per-area
  picker routes) are also kept — they list one area's ops only, not
  the generic box.
- The v3.2 command box is untouched. The do-it flow, TV flow,
  status-line truth, and per-screen auto-run (v3.2 §5) are untouched.

## Files changed (vs v3.2)

- NEW `ui/GhostChrome.kt` — shared chrome + `GhostTile` /
  `GhostActionCard` / `Cyan`.
- MODIFIED `ui/GhostTapApp.kt` — home tiles, IntentBox removal (home
  / Network / NFC), Network "Investigate" + "Open Wi-Fi settings"
  cards, RESULTS last-answer line, NFC "Set up this blank card"
  button, `PanelBox` / `TerminalButton` restyle, `BorderStroke`
  import.
- MODIFIED `ui/PlainEnglish.kt` — `ExplainerCard` first line Accent.
- MODIFIED `ui/UsbUartScreen.kt`, `ui/VirtualEsp32Screen.kt`,
  `ui/VisualIntelScreen.kt`, `ui/TermuxScreen.kt`,
  `ui/InvestigationsScreen.kt` — per-screen card conversion + op
  surfacing + IntentBox removal (see §5–§9).
- NEW `PROTECTED-BASELINE.sha256` — the 7 protected-file hashes
  recorded before editing (not app code).
- NOTHING else changed. No core/, data/, termux/, build files, or
  resources touched. No behavior, logic, navigation target,
  permission, or integration changed anywhere.

## On-device test list

1. Home: seven tiles, each with its title + one plain-words line +
   "Tap to open"; each opens its screen. No old "WHAT ARE YOU
   TRYING TO DO?" box anywhere on home. Command box still do-it:
   "find me a tv close by" runs by itself, shows what happened.
2. Status line truthful on fresh install ("checking…" → true state).
3. Network Tools: "Investigate the unfamiliar" card runs discovery;
   "Open Wi-Fi settings" opens the phone's Wi-Fi settings; tool
   panels (scan / talking / hotspot / ping / TCP / UDP / port scan /
   HTTP) all still run; RESULTS shows the tool log AND the last
   command answer. Hotspot: Start / Stop / CHECK WHO JOINED.
4. NFC Tools: ten panels, all buttons run; "Set up this blank card"
   in panel 6 formats a blank card (try on a blank tag). Copy flow
   (panel 2 → panel 5) still works. No old box.
5. USB UART: connect a board, send text, speed check, DTR/RTS
   toggles show ON/OFF in the title; new cards: "What is this
   plugged-in board?", "Is the board talking back?", "What do the
   control wires do?", "Read the board's ID", "Check the software
   file".
6. Virtual ESP32: bridge connects on open; START / STOP / RESTART
   the guest; new cards: "Is the practice-board app installed?",
   "Use the practice board", "How is the practice board doing?",
   "What is the practice board's light doing?"
7. Visual Intel: open camera, take a photo, the four new camera
   cards; "Look into the last photo" starts an investigation;
   "Fetch what the barcode points to" / "Copy something from the
   photo" on a finding. CHECK: tapping "Open the camera" while
   already on the screen (possible duplicate destination — see §7);
   tapping "Turn the camera off" (camera may come back on
   recomposition — see §7).
8. Investigations: the four new cards above STATUS run
   (look-into-device / web search / case files / all history); plan
   steps RUN / APPROVE & RUN; auto-investigate start/stop; close /
   mark-done a case; timeline add-note; no old box.
9. Termux: "Check Termux is on this phone", "Check the link to
   Termux", "List what Termux can do", "Run a Termux script", "Run a
   command in Termux", "List your Termux scripts", "Check what
   scripts are in Termux" — all run and show answers. Auto-discover
   still runs on open. No old box.
10. Regression: the six home tiles + command box + Investigations
    timeline all still work as before; no screen asks for a new
    permission; no setting gates anything.
