# Public iOS Simulator control feasibility qualification

Status: **one short, scoped public-XCTest fixture run passed; no PocketPi iOS backend is enabled or integrated**. The overall remote-development goal remains active. The probe lives in NOAH `packages/pi-atlas/extensions/remote/runtime-test/ios-simulator-probe`; it is a feasibility component, not a substitute for the required product backend.

## Environment and recorded result

Root verified Xcode 27.0 build `27A5252f` and available iOS 26.5 / 27.0 runtimes. No existing iOS simulator was booted. The qualified run used a newly created iPhone 17 Pro, iOS 26.5, portrait. Public `XCUIAutomation` runs inside a single bounded XCTest method; a private loopback HTTP command bridge issues sequential commands. No private framework, raw simctl touch injection, model call or personal Host operation is used.

Evidence lab: `/private/tmp/pocketpi-ios-probe-rdxeenja`. Simulator `48B5478E-0CD4-43E8-853D-0F245652B32A` was created solely for this run and removed afterward. `xcodebuild` used that exact destination, disabled parallel testing and limited concurrent simulator destinations to one. Its exit code was zero. The destination's launchctl inventory contained both fixture app and UI-test runner; the fixture app-container path also contains that UDID. Before/final simulator inventories have identical device IDs and no booted devices; no extra simulator clone was present in the sampled inventories. This is not continuous clone monitoring.

The generated app Info.plist contains modern Launch Screen and Scene Manifest entries. App coordinates were 402 × 874 points and screenshots were 1206 × 2622 pixels: measured scale 3 in both axes. No other orientation, compatibility window or display scale is qualified.

## Verified behavior

- Coordinate tap changed the actual fixture counter from 0 to 1.
- Coordinate focus and `typeText` produced exact `PocketPi-Remote-27` echo.
- One public Home press waited for background state 3; capture showed the home screen. Activate restored the existing counter and text.
- Three coordinate drags changed the final list item from not hittable to hittable. Root visually inspected the screenshot containing item 29.
- A negative tap coordinate returned `ok:false` / invalidCommand, and a later valid action still worked.
- Two automatic state replies during a six-second idle interval were followed by a second counter increment. This is a bounded idle-loop observation, not a long-running or general runloop guarantee.
- Explicit Stop completed the XCTest; result and cleanup both passed. The recorded owned xcodebuild PID 83148 was subsequently absent.

Root visually inspected the initial XCTest and exact-UDID simctl captures showing the same Count 1 fixture, the exact typed echo, the Home screen and the scrolled final item. This was a manual content comparison, not identical-byte or exhaustive pixel equivalence. The independent read-only audit checked all six original source hashes, disposable project contents, expected scheme differences (session and duration), byte-identical controller snapshot, trace, outcome and sampled inventories. A separate manifest records hashes of 33 compiled build-product files. Documentation was updated after that source audit; executable sources remain unchanged.

## Historical failures

`/private/tmp/pocketpi-ios-probe-3zad7whj`: build passed, test failed before app launch because scheme `shouldUseLaunchSchemeArgsEnv=YES` inherited an empty LaunchAction environment. The generated xctestrun contained none of the three required bridge variables. Changing to explicit TestAction environment (`NO`) fixed that measured absence. Cleanup passed and owned simulator `280882FA-88D1-41A1-BDD7-9D36B386BF85` was removed.

`/private/tmp/pocketpi-ios-probe-y0p2r8_3`: tap, exact text and captures worked, but an immediate state assertion caught the Home animation still in foreground. The app also lacked a generated Launch Screen/Scene Manifest and rendered in a legacy compatibility window: app frame 320 × 480 while capture was 1206 × 2622. This failed run is not coordinate-scale qualification. The final revision generates the modern entries and uses a bounded public XCTest background-state expectation after one Home press. The failed test was interrupted, cleanup passed and owned simulator `56E2E871-B2AC-4B94-B036-F63D84853063` was removed. These specific cleanup outcomes do not establish every failure/timeout path.

## Remaining product and qualification gates

Implement an owner-configured and locked Mac backend, authenticated bounded bridge, actual runner/UDID binding, stable screenshot geometry and uncertainty/no-replay handling. Integrate negotiated per-platform profiles and controls without changing legacy Android v1 payloads; reject unsupported iOS keys before dispatch. Connect the Pi agent and PocketPi viewer to the same owned instance. Then exercise actual Android-to-Mac input/capture, arbitrary intended apps, orientation and geometry changes, revocation, network interruptions, runner failure, close/open races and independent-instance isolation. Real Samsung hardware remains unavailable; no physical-device claim is made.

The runtime API feasibility is positive for the recorded primitives. General system interaction, precise swipe-duration semantics, arbitrary hardware keys, sustained stream performance, repeated startup reliability and full lifecycle recovery are not established.
