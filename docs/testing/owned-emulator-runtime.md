# Owned Android emulator runtime qualification — 2026-10-31

The host now has an implemented emulator backend: `OwnedAndroidEmulatorRuntime`,
`EmulatorGrpcClient` and `EmulatorAdbChannel`. It starts only an explicitly configured private AVD
and its own private ADB Unix-socket server. This is the backend for a future PocketPi/Pi emulator
capability; no emulator action, remote grant or capability is advertised yet.

## Implemented behavior

- Explicit canonical executable paths, owner-controlled private AVD and project paths; private runtime state and lock.
  Only spawned ChildProcess handles may be terminated. Unconfirmed child/client cleanup retains
  ownership and the lock; cleanup can be retried without reopening admission.
- JWT-authenticated loopback gRPC capture with an in-memory private key and temporary public JWK.
  Discovery identity, ports, paths, PID listener, HTTP/2/gRPC status/framing and bounded protobuf/PNG
  contents are checked. Public SDK `active.jwk` may be 0644 inside its private 0700 directory;
  writable or symlinked files are refused. PNG CRC, structure, dimensions and bounded decompression
  are validated. There is no gRPC keyboard input.
- A private ADB smart-socket client has no CLI auto-start or default-server fallback. It binds one
  transport ID and proves the emulator listener plus inverse established TCP endpoints of the
  emulator and its private ADB process. Physical/foreign identity and transport changes are refused.
- Finite key, tap and swipe commands; strict integer coordinates and duration; no arbitrary shell,
  text, clipboard, installation or process command. Geometry and rotation are checked before/after
  input; dispatched failures are uncertain and never retried. Coordinates additionally require a
  previously captured image matching the current geometry/revision.
- Runtime operations are serialized and capped at 32 including the running operation. Closing
  cancels queued actions. Authority or child loss stops admission; keys, private socket and lock
  are released only after cleanup is confirmed.

The lsof-based process attestation is a point-in-time observation, not a claim of atomic operating
system isolation against a compromised local account. Tests prove the defined owned-child and
transport boundaries. Rotation between the last check and actual dispatch cannot be made atomic;
observed persistent changes produce an uncertain result. Display overrides remain refused.

## Evidence

Mac SDK Emulator 37.1.11.0, API 36 disposable `PocketPiBrowserView`, natural 1080×2400 / density 420,
Node 24.18.0. The real supervisor started private ADB and QEMU, captured stable Settings and Launcher
images, applied HOME, tapped the Settings search field and captured the search UI/keyboard.
An authority revocation stopped admission and closed both children, the public JWK, private socket
directory, discovery entry and runtime lock. A separate test occupied the ADB port with a foreign
listener: startup was refused and the foreign listener remained running. Two actual SDK tests
passed in 55.33 seconds. The rendered Settings, Launcher and search images were inspected.

The root independently passed 55 unit/adversarial tests. They cover malformed/fragmented/oversized
messages, HTTP/gRPC errors, deadlines, concurrent capture, key cleanup failure/retry, forged identity,
transport rebinding, unknown/high-bit ADB framing, geometry changes, bounded queues, revocation and
child/cleanup failures. The two opt-in SDK tests are skipped in the ordinary unit suite.

Two bound review rounds corrected missing ADB peer-process attestation and coordinate admission
before a matching captured frame. The final static review reported no further actionable finding.
The reviewed implementation is commit `a2761e9c5`; merge `82dda9d0` incorporates `origin/dev` at
`20bba032e` (protected-push policy updates). The remote extension files are byte-identical across
that merge. Full integrated package check passed with 92 existing warnings / 3 infos; the complete extension suite passed 6,357 tests with 27 skipped in 287.86 seconds. The automatic base merge also passed the pin-only check.

Earlier test runs exposed fixture weaknesses: changed image hashes captured transition frames;
UI Automator `/dev/tty` returned only a notification. The qualified test uses a private device-side
XML file, checks the expected UI hierarchy, waits for stable rendered frames and removes the file.
Those earlier runs do not establish visual qualification.

## Limits and continuation

Owner CLI/configuration/grants, current Pi/device session service, control leases and versioned
emulator wire/frame protocol are not implemented by this backend commit. An ACK-paced app pane
and actual encrypted phone/tablet/Pi handoff remain the next implementation package. Touch/swipe
coverage beyond the demonstrated search tap, rotation, Unicode/clipboard/hardware keyboard,
crash adoption and long sessions still need qualification. Linux executable paths are accommodated
but a real Linux SDK/AVD has not been qualified. Wear and Mac iOS simulator streaming remain open.
Physical Galaxy S25/Tab S11, DeX, independent remote Linux/devcontainer, network/lifecycle and the
complete development workflow remain full-goal gates.

Local evidence: `/private/tmp/emulator-runtime-root-reviewed-sdk.log`,
`/private/tmp/emulator-runtime-root-focused.log`,
`/private/tmp/pocketpi-emulator-runtime-proof/{proof.json,settings.png,launcher.png,search.png}`.
Integrated checks: `/private/tmp/emulator-runtime-integrated-{check,suite}.log`.
Opt-in SDK environment variables are documented in `emulator-runtime_integration_test.ts`.
