# Browser transport integration evidence

## Scope

`BrowserTransportEndToEndTest` runs the real `DefaultRemoteRepository`, `SecureRemoteTransport`,
Android browser client and native pane against an isolated TLS relay, real HostDaemon and owned
Chromium. The host fixture is `remote/runtime-test/browser-android-e2e/run.ts` in pi-atlas.
The Pi session registration and empty snapshot come from a synthetic LocalPeer. This is not a
real Pi model/tool turn, full application navigation test, Linux qualification or physical Samsung
qualification. The page itself is loopback HTTP inside the host-owned browser; this does not
qualify the separate protected-preview HTTP/WS origin transport.

## Verified behavior

The test rejects the fixture certificate with a default OkHttp client, then trusts only its
short-lived CA with the standard hostname verifier. It activates the session through USER_OPEN,
negotiates capability v3, explicitly acquires control and creates a new tab through the UI.
It receives and decodes encrypted frames, navigates to the fixture page and independently
observes both pointer-down and pointer-up in that page. Each run sends a unique value; the
fixture must report that exact input and submitted value. Enter is sent once, after the text
receipt is accepted and the control is enabled. The received JPEG and the actually displayed
Compose image must both show the green submit state before the screenshot is saved.

With `session.browser.recovery.v1` negotiated, the test leaves a unique unsent Android draft,
clicks an actual link in the displayed browser image, and waits for the new document revision.
The host's fixture diagnostics confirm delivery of the same lease without another acquire;
Android retains control and the draft, and both the received JPEG and Compose display show
the blue destination. The prior submitted value remains unchanged. Twenty Android mouse-wheel
events then request 480 CSS pixels of scrolling; both the page and a fresh JPEG must report
the full movement, with the same owner and no automatic acquisition or draft replay.

The first real run exposed a product defect: the repository dropped `session.browser.v1` from
projects.list discovery because it was absent from `routeCapabilities`. The regression test
`browserCapabilityOnV3ProjectsRouteEnablesBrowserForSelectedSession` failed at its capability
assertion before the whitelist fix, then passed. `canOpenBrowser` still requires route v3.

## Local results on 2026-10-31

For the recovery increment, the required Android quick-check passed 3,044 app and 34 Wear
unit tests; the host Remote suite passed 3,018 tests with 23 skipped. Encrypted end-to-end
runs of the full navigation and 480-pixel wheel flow passed on the phone emulator in 11.455 s
and the tablet emulator in 11.502 s. These runs preceded the final host control-flow refactor. The final-source tablet
flow with the IME hidden passed in 10.893 s. After correcting the cross-run scroll
baseline, the final phone flow passed in 12.454 s. The earlier normal wheel runs reported zero waiting events. A later phone attempt emitted
scoped moving heartbeats, but its final assertion failed because fixture scroll telemetry from
the previous run was used as the new document baseline. The test now compares against the
current frame, while still requiring new page scroll events and a fresh frame at the target.
Heartbeat timing and rejection behavior remain covered deterministically.

A later tablet run observed a text-send click during IME-driven viewport changes for which no
text request reached the host. The pane can discard a queued send while retaining its draft
when the client refuses it during another control operation; this is a possible explanation,
but the exact refusal path was not captured. The silent-refusal UX race remains unresolved.
The transport test hides the IME and waits for a current frame before its single send; this
does not qualify sending during that transition.

- Merged current origin/main and origin/dev into the isolated feature branches before testing.
- Host Remote suite after the runtime fix: 3,004 passed, 23 skipped; extension checking and root formatting passed.
- Android required quick-check: 3,028 app and 34 Wear unit tests passed, all configured source
  sets compiled and resource parity passed. Debug and instrumentation APK assembly passed.
- API 36 phone emulator, 1080x2400 at density 420: final stronger transport test passed
  in 16.847 s; tablet, 2560x1600 at density 320: passed in 8.661 s. Both screenshots
  show the submitted green page; earlier phone evidence was visually inspected as well.
- Pre-fix phone/tablet runs exposed a second product defect: concurrent screenshots and
  viewport changes could leave the host configured for 411x182 while Chromium still reported
  411x218. Captures then failed strict visual viewport checks and relinquished control.
  The runtime now serializes actual CDP capture/metrics and viewport writes per tab. The
  transaction stays held through the underlying CDP completion even if its caller aborts.
  Abort, session/project and exact lease authority are checked again before queued dispatch.
- Both deterministic ordering/cancellation regressions failed before the fix. The targeted
  runtime/service/owner/stream suite passed 60 tests, including real Chromium overlap and
  queued cancellation/handoff. The final two emulator runs reported no capture/send errors
  or unsupported viewport metrics. This is a targeted fix, not universal stability proof.

Chromium's [screenshot implementation](https://github.com/chromium/chromium/blob/main/content/browser/devtools/protocol/page_handler.cc)
saves emulation parameters and restores them when capture completes. This supports the observed
overlap mechanism; the local browser was Chrome for Testing 156.0.8078.4. Strict dimension,
scale/offset, authority and transport validation remain in place. Diagnostic wrappers can
change microtask timing, so a passing instrumented rerun alone was not treated as a fix.

Builds/emulators do not establish physical Galaxy S25/Tab S11, DeX, process-death recovery,
real Pi turns, reconnect/handoff, independent Linux/devcontainer or public-network
qualification. Those gates remain open.

## Running

Start the host fixture with Node 24 and an existing supported Chromium executable:

```sh
node packages/pi-atlas/extensions/remote/runtime-test/browser-android-e2e/run.ts \
  --output /private/tmp/pocketpi-e2e-fixture.json --chromium /absolute/path/to/chromium
```

It writes private, temporary pairing data and a public CA certificate path; do not print or
commit the JSON. Copy the JSON and public certificate to a dedicated emulator's `/data/local/tmp`
and change `caCertPath` in that copy to the emulator certificate path. Keep the JSON mode 0600.
Build/install the debug and instrumentation APKs, then run only the opt-in class:

```sh
adb shell am instrument -w \
  -e class de.joinnoah.pi.remote.BrowserTransportEndToEndTest \
  -e browserFixturePath /data/local/tmp/pocketpi-e2e-android.json \
  de.robinrehbein.pocketpi.test/de.joinnoah.pi.remote.PocketPiTestRunner
```

Check `OK (1 test)`: the instrumentation shell command can exit zero even when JUnit fails.
The screenshot is `browser-transport-e2e.png` under the app's external files directory.
Without the fixture argument this test is intentionally skipped. Remove copied credentials,
stop the dedicated fixture with SIGINT, and stop only test-owned emulator/ADB processes.
No production network-security policy or user CA installation is needed.
