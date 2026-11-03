# Android emulator integration qualification

## Implemented path

The `session.emulator.v1` capability connects a host-owned Android AVD to PocketPi and the public
Pi `remote_emulator` tool. Device clients receive profile IDs and labels; executable paths, AVD
paths, ports and private ADB identity remain owner-local. The existing browser and preview grants
are independent. The capability is negotiated only through capability protocol v3.

Owner configuration uses the live host CLI:

```text
config emulator-control on
emulator profile set /absolute/path/to/profile.json
emulator profiles
emulator grant DEVICE_ID PROJECT_ID PROFILE_ID
emulator grants
emulator revoke DEVICE_ID PROJECT_ID PROFILE_ID
emulator profile remove PROFILE_ID
```

A profile contains `id`, `label`, `projectId`, `emulatorExecutable`, `adbExecutable`, `avdHome`,
`avdName`, `stateDir`, `consolePort` and `grpcPort`. Linux also requires an explicit local
`discoveryDirectory`. Executables must be canonical and executable; AVD/state directories must be
private and owned by the host user. The profile file is owner-local configuration, never a model
or device argument. Creating profiles or device grants requires the live owner endpoint.

Pi can list/open a profile, inspect participants, acquire/release control, hand control to another
eligible participant, capture a snapshot, send finite key/tap/swipe input, detach or stop its owned
context. PocketPi starts as a reader. Input requires the current lease and a fully delivered image
for the current generation and geometry. Reconnection rejoins through an explicit profile open
without automatically acquiring control. Frames use bounded PNG chunks and acknowledgements.

## Current evidence

On the current uncommitted integration source, the four focused Android classes passed 15 tests;
the instrumentation source also compiled. These tests cover profile errors, server-bound owner
identity, canceled gestures, frame updates during a gesture, frame assembly and reader rejoin to a
replacement context. They are local JVM checks, not physical Galaxy qualification.

The root passed 41 focused host tests across protocol, policy, CLI, service, stream, coordinator,
tool and configuration persistence. A real local Unix-socket test additionally exercised owner
profile validation, registered Pi open/snapshot, forged-session rejection, registration replacement,
reader-only rejoin and access revocation. A subsequent service test reproduces and guards request-ID
reuse during registration changes. Native Node 24 import and extension type checking passed.

A real phone-layout run passed in 39.79 seconds using SDK Emulator 37.1.11, a separate
1080x2400 target AVD, encrypted transport and the public Pi tool through local IPC. It covered
reader frames, explicit acquisition, native Home observation, Pi snapshot, handoff in both
directions, device-grant revocation and confirmed target stop. This is emulator evidence only.
The initial runs exposed a test-cleanup deadline that masked earlier failures; the fixture now
preserves the original failure and allows bounded owner cleanup. The tablet attempt returned an
uncertain input result. The fixture's activity observation was moved outside the native input
transaction before repeating qualification; no automatic replay was added. The revised tablet run passed in
45.35 seconds at 1600x1000 / density 160, with explicit `accepted` input acknowledgement and native
Home confirmation. Both handoffs, Pi image content, grant revocation and target cleanup passed.
The rendered tablet pane was inspected. Both owned VMs, fixture relay and dedicated ADB server
were shut down; target lock and owned listening ports were absent. The pre-existing standard ADB
server on 5037 remained the same process. The displayed layout is an emulated viewport, not a
Galaxy Tab S11 qualification.

The full extension suite passed 6,399 tests with 27 skipped and one Chromium screenshot/resize
failure. That unchanged test passed in isolation; the full suite is not claimed green. The full
Android run passed 3,080 of 3,081 tests plus Wear, with an obsolete action-menu expectation as its
single failure. After correcting that expectation, 55 focused Android tests and both APK builds
passed. The final complete Android and Wear runs passed (3,081 App tests and 34 Wear tests). Package extension checks passed with 150
warnings and three informational diagnostics. Independent bounded Android review found three
corrected defects; a subsequent bounded Host integration review found no additional concrete
issue.

## Remaining gate

A controlled lost-input-acknowledgement reconnect now passes on emulated phone and tablet; see
[reconnect qualification](emulator-reconnect.md). Complete the remaining lifecycle/long-session matrix and improve the tablet workspace
layout. The phone/tablet smoke flows above establish acquisition and handoff, not all reconnect
and long-session behavior. The backend SDK evidence
in `owned-emulator-runtime.md` does not establish this integrated path. Physical Galaxy S25/Tab S11,
DeX, remote Linux/devcontainer and the full development workflow remain separate goal gates.


Evidence logs: `/private/tmp/emulator-integration-device-test4.log` (phone),
`/private/tmp/emulator-integration-device-tablet7.log` (tablet),
`/private/tmp/emulator-integration-tablet-proof.json` (aggregate proof),
`/private/tmp/emulator-integration-tablet-screen.png` (rendered viewport).
