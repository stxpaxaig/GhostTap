# PIECE 2 — Build Notes (2026-11-18)

Ordered by Justin 04:49: make Ghost Tap the smartest, most intelligent
version — cut the cards that re-list what it already has, add the five
missing tools + screen-see-and-tap, and wire everything into the do-it
engine so it discovers and connects to NEW things. No brain is added by
this build: the thinking-engine slot stays as-is (Ghost key). The brain
is still the missing piece (local model or paid key).

## New files

- `app/src/main/java/com/ghosttap/core/QrCode.kt` — pure-Kotlin QR
  encoder, zero dependencies (app/build.gradle.kts is protected, so no
  dependency could be added). Byte mode, UTF-8, error-correction level
  L, versions 1–10 (max 271 bytes). Reed-Solomon GF(256) primitive
  0x11D, BCH format/version info, function patterns, mask selection by
  penalty score. VERIFIED against the reference `qrcode` library:
  **18/18 test strings match bit-for-bit** (versions 1–10, incl. the
  271-byte V10 max). Two bugs found and fixed during verification: the
  dark module was transposed ((x=13,y=8) instead of (8,13), which also
  shifted every later data bit by one), and the V10 level-L block count
  was 2 in the table but the spec needs 4 (271 bytes need 274 data
  codewords; 346 − 274 = 72 = 4 × 18).
- `app/src/main/java/com/ghosttap/accessibility/GhostTapAccessibilityService.kt`
  — Ghost Tap's tap control: an AccessibilityService that screenshots
  the screen (API 30+, Display.DEFAULT_DISPLAY) and taps via
  dispatchGesture. Never decides anything itself; only screenshots and
  taps on the ViewModel's say-so. `isEnabled()` checks the system
  setting by component name.
- `app/src/main/java/com/ghosttap/core/SeeAndTapBrain.kt` — asks the
  existing thinking engine (Ghost key, gpt-4o-mini, the one model in
  the engine that can look at a picture) where to tap on a screenshot.
  Screenshot shrunk to ≤768px, JPEG 70, Base64. Answer must be JSON
  {"x":0–1000,"y":0–1000}; −1/−1 = not found. No key, 401, 429, timeout
  or unreadable answer all come back as plain-words failures — never a
  faked tap.
- `app/src/main/java/com/ghosttap/core/ops/OperationAreaPiece2.kt` —
  six ops: net.traceroute, net.dns_lookup, net.speed_test,
  net.wifi_analyzer, net.qr_generate, visual.see_and_tap. Each wraps an
  existing ViewModel function and reads its result from the log / VM
  state. Host/text/goal come from plan-step args, a pending choice, or
  (traceroute/dns) the first live host.
- `app/src/main/res/xml/accessibility_service_config.xml` + string in
  `res/values/strings.xml` + service entry in `AndroidManifest.xml`
  (BIND_ACCESSIBILITY_SERVICE, canTakeScreenshot + canPerformGestures).

## Changed files

- `NetworkToolsViewModel.kt` — five tools + Termux hook:
  - `termuxCommandRunner` var, set once from GhostTapApp to
    `termuxViewModel.runFreeCommandAndWait`. Traceroute is the only
    tool that needs it; Termux not ready = plain words, never silent.
  - `dnsLookup(host)` — pure Java InetAddress; forward lists every
    address, reverse (IP input) reports the name or "no name on record".
  - `traceroute(host)` — `traceroute -m 20 -w 2 <host>` via Termux,
    60s timeout. Host must match `^[A-Za-z0-9][A-Za-z0-9._:-]*$`
    (no leading dash, so it can never be read as a flag) before it is
    handed to Termux. "Tool missing" and "nothing came back" are said
    in plain words.
  - `speedTest()` — one 10 MB download + one 2 MB upload against
    https://speed.cloudflare.com, timed, once per tap; the log says
    exactly what it measured ("speed to Cloudflare's test server").
  - `analyzeWifi()` — reuses the existing scan; `processWifiScanResults`
    writes one line per network (channel, band, signal word) and the
    engine event when the check asked for it. Empty scan clears the
    pending flag and reports honestly.
  - `generateQr(text)` / `clearQr()` — QrCode.encode, 271-byte limit
    said plainly. State: qrMatrix / qrText / qrError.
  - `WifiNetworkInfo` gains `frequencyMhz` + computed `channel`
    (2.4 GHz (freq−2407)/5, 5 GHz (freq−5000)/5, 6 GHz (freq−5950)/5),
    `band`, `signalWord` (describeRssi — words only, never distance).
- `VisualIntelViewModel.kt` — `seeAndTap(goal)` + `seeAndTapGoal` /
  `seeAndTapStatus` / `seeAndTapBusy` / `tapControlOn` /
  `refreshTapControlState()`. Flow: service screenshots → brain points
  → service taps at scaled pixels → "Tapped it." or the plain-words
  reason. Busy-guarded; bitmap recycled after the brain call.
- `GhostTapApp.kt` — registers piece-2 ops in the LaunchedEffect and
  sets the Termux hook; Network Tools screen gains the five tool panels
  (ROUTE CHECK, NAME LOOK-UP, SPEED CHECK, WI-FI CHECK, MAKE A QR
  CODE with a Compose Canvas rendering of the matrix); see the
  removals list below.
- `Planner.kt` — section 3b: command-box intents for all six tools
  ("traceroute / route to / hops to" + host; "dns / resolve / look up"
  + host; "speed test / internet speed / how fast is"; "wifi analyzer
  / analyze wifi / wifi around / networks around"; "qr code / make a
  qr" + text; "tap/click/press the …" + goal, never for NFC taps).
  These flow through `planForCommand`, so the command box runs them
  itself with no plan shown.
- `VisualIntelScreen.kt` — see-and-tap panel (tap-control status, one
  button to the phone's accessibility settings, goal box, Look-and-tap
  button, plain-words status). Tap-control state refreshes on open and
  on resume.
- `InvestigationsScreen.kt`, `UsbUartScreen.kt`,
  `VirtualEsp32Screen.kt`, `OperationEntryScreen.kt` — removals only
  (below).

## Removed — page by page (22 cards + 1 merge)

- Home (3): the "WHAT THIS SCREEN DOES" ExplainerCard; the NFC pointer
  line ("tap NFC TOOLS above…"); the infrared/long-range hardware note.
- USB UART / ESP32 (1): its ExplainerCard.
- Virtual ESP32 (1): its ExplainerCard.
- Network Tools (2): its ExplainerCard; the description half of the
  header line (the "only use these on devices you own or are allowed
  to test" reminder stays as one line).
- NFC Tools (2): its ExplainerCard (the "never write to a card you
  actually use" warning stays as one line); the "your phone can read
  and write tap cards" line.
- Visual Intel (2): its ExplainerCard; the long "point the camera at a
  circuit board…" paragraph.
- Investigations (3): its ExplainerCard; the "Ghost Tap looks into a
  device for you…" paragraph; the whole DISCOVERED CAPABILITIES panel
  (it scanned Ghost Tap's own code and re-listed its own built-ins —
  the prime offender). The scan function and its op stay in place,
  untouched; only the panel goes.
- Target (device) page (6): "WHAT TO DO" panel (state line already in
  WATCH, paragraphs only described the buttons); "CAPABILITIES" panel
  (read-only list duplicating CONTROL / MAP INTERFACE); CONTROL
  panel's else-branch pointer line (panel now renders only for NFC /
  BLE); RAW panel's NFC branch (two pointer lines) and its fallback
  branch (a dead input box + "no direct-command box yet" note);
  "LOG" panel (one pointer line to the home Activity log).
- Operation Entry screens (2): its ExplainerCard; the "NOTHING HERE
  YET" panel (every section now has real operations).
- MERGED, not deleted: "WHAT CAN GHOST TAP DO?" rendered twice on
  every target page (terse "DO THIS FIRST" shortlist at top + the same
  list in full below). Now ONE list, in full, at the top. The `compact`
  / `onShowDetails` shortlist plumbing is gone.
- KEPT DELIBERATELY (verified, not built-in re-lists): the capability
  lists in LiveTargetCard / InvestigateReportCard /
  NetworkInvestigationPanel / NfcInvestigationPanel — those describe a
  NEW target just found, with real buttons when the context provides
  them; hiding them would hide real findings. The "Also possible, but
  no button here" lines list that target's controls that live on its
  own page.
- The `ExplainerCard` component itself and `plainConnectionState` are
  now unused (definition only, no call sites). Left in place — unused
  functions are warnings, not errors; removing the component is a
  separate cleanup.

## Verification

- Protected files: `sha256sum -c PROTECTED-BASELINE.sha256` — all 7 OK
  in the tree (re-checked inside the package after packaging).
- #181 stall fix intact: `sample(500)` throttle + baseline loaded once
  per home entry (remember {}).
- Piece-1 unified changes intact: bundled firmware asset present
  (4,194,304 B), zero references to TermuxScreen, CommandBox on all 7
  pages (home + 6 function screens).
- Error-class sweep (#182/#183): every new/edited multi-line call site
  checked by hand — all named args comma-terminated except the last
  before `)`; zero `remember {}` blocks contain composable calls in
  new code; no local functions called before declaration (all new
  helpers are top-level or object members). Brace/paren/bracket balance
  is 0/0/0 in all 13 touched Kotlin files + the manifest.
- One real bug caught by the sweep: SeeAndTapBrain first took a
  `Context`, but `IntelSettings.getEndpoint/getApiKey` take an
  `Application` — fixed to `Application` before packaging.
- QR encoder: 18/18 test strings match the reference library bit-for-
  bit (see QrCode.kt header).
- Compile is NOT proven locally (no Android SDK on this machine) —
  AndroidForge is the first real compile check, as before.

## Honest limits

- No brain added. The thinking-engine slot is unchanged (Ghost key).
  See-and-tap does nothing at all without a key with credit — it says
  so and never fakes a tap.
- Traceroute needs Termux ready; without it, it says so.
- Speed test measures speed to Cloudflare's test server only.
- Wi-Fi Analyzer shows channel/band/signal words only — never a
  distance (RSSI bands only, per the standing rule).
- QR codes are error-correction level L, byte mode, max 271 bytes.
- Screen-see-and-tap needs Android 11+ for screenshots and one
  switch-on in the phone's accessibility settings first.
- The app still cannot control a TV over Bluetooth (no Wi-Fi TV
  protocol code — unchanged honest gap from v3.2).
- Termux flash of a physical board is still impossible (no USB access
  in Termux on a non-rooted phone — unchanged honest gap).
