# PR/CI status parity: local qualification, 2026-10-31

The existing PR-status producer publishes on the public Pi event bus. The Remote extension now
forwards that value through local IPC for its currently registered session; the Host accepts it
only from that session's bound peer and reuses the existing mapping, bounds, deduplication,
journal, snapshot and encrypted event delivery. This repairs the missing terminal/native bridge;
the host-owned RPC status-key path remains separate. PR creation does not synthesize CI status.

The extension retains an independent copy of valid producer data for registration/reconnect.
Malformed or foreign-session values do not replace that cache. A null clears the status; a new
session cannot inherit the previous session's PR. Host tests cover stale and replaced peers.

## Evidence

- Merged App `595daae`: 3,320 Android unit tests and 34 Wear unit tests passed, no failures or
  skips. Wear debug assembly passed. No emulator or physical device was used for these checks.
- The new `PullRequestStatusScreenIntegrationTest` passed (one test, Gradle 23 s). It uses the
  real `DefaultRemoteRepository`, navigation and `RemoteScreen` with a deterministic transport:
  a running snapshot, named-failure event and chip-to-Changes navigation, passing/cleared
  events, then explicit reconnect and a restored running snapshot. It checks the PR/checks
  actions are displayed without opening external URLs. This is a separate Robolectric run,
  not an Android client connected to the real-Pi fixture.
- Extension regression: the missing-listener assertion failed before the bridge and passed
  afterwards. The later mutation-after-publish regression failed with the changed producer
  object, then passed after defensive copying (one selected runtime test, 6.20 s).
- Host Remote full regression passed 3,799 tests with 38 skips across 261 passing and seven
  skipped files (694.38 s), using `--maxWorkers 2`. Earlier default-concurrency runs hit the
  two composite Git tests' overall 240/300-second limits; no production deadline or assertion
  was relaxed. The PR scenario completed in 189.19 s in the bounded-concurrency run.
- Host extension type checking passed. Biome checked all 467 Remote files with no errors;
  147 warnings and three informational diagnostics remain.
- Two selected Host PR-status tests passed, including clear/deduplication, malformed values,
  mismatched session IDs and stale/replaced peers.
- The isolated `runtime-test/pr-status-pi-harness/run.ts` ran a real external Pi 1.4.0 process,
  the actual PR-status extension/parser and actual Remote extension. PID/session ownership was
  verified. An encrypted simulated device received a pending-check snapshot, a named failed
  check, passing checks and a cleared status. A fresh post-review run passed the same sequence;
  both reported zero cleanup failures.

The Pi fixture isolates HOME, agent settings, project trust, session files, Host state and relay.
Its wrapper injects controlled gh JSON into the actual producer; it does not emit synthetic status
bus messages. Pi's RPC stdout is drained rather than consumed by the Host, so this test exercises
the repaired bus/IPC path rather than the host-owned RPC status-key path. No neural model, real
gh authentication, GitHub request or owner configuration was used. Temporary state was deleted.

## Combined Android and native-worker qualification

A subsequent isolated combined run used an external Pi 1.4.0 CLI process with the actual
PR-status producer/parser and Remote extension, the real Host, a private-CA TLS relay, and
Android's encrypted `SecureRemoteTransport`, `DefaultRemoteRepository`, production navigation
and screens. Controlled producer input drove pending, `fixture-build` failure, passing and null
states. For recovery, the socket was cut and upgrades blocked while the status was null;
pending was produced only while the device was offline, then restored after reconnect. The
fixture verified the Pi owner PID and, in each fresh run, one cut and two upgrades.

JUnit passed on the 1080×2340, 420-dpi phone profile in English (8.663 s), and on the
2560×1600, 320-dpi tablet profile in German (8.870 s) and English (8.629 s); the German phone profile passed in 8.014 s. App locales were set explicitly. Committed-frame
screenshots of phone English failed/recovered, tablet German failed/recovered and tablet English
failed, and phone German failed were inspected and legible. These are
emulator display profiles, not physical devices. The test activity supplies `MaterialTheme` and
explicit foreground/validated-network signals; it does not exercise `MainActivity` or the system
network monitor. It does not invoke GitHub, a `gh` binary or a neural-model turn.

The owned fixtures exited after graceful SIGINT, the device fixture CA and JSON were deleted before
the emulator stopped, and the owned ADB server was stopped. The fixture ports had no listeners and
no fixture or native process with the private prefix remained in the checked process list. Host-side temporary
device JSON/CA copies and one invalid navigation artifact were removed; sanitized evidence,
screenshots, logs and the old owned AVD backing were retained. The default ADB server was untouched.
The final TypeScript check passed; Biome passed for the two new fixtures with zero errors.

Two setup assumptions were corrected without product changes. An empty unsaved terminal session
was omitted by the Host, so the fixture seeds user context via the public SessionManager before
Pi's `--session` launch, checks the same session ID and asserts the Host lists it. A reused,
verified sessions list deliberately has `sessionsFresh == false` (covered by the existing
`sessionsFreshOnlyWhenTheListWasActuallyFetchedThisTime` test); the Android test waits for the
positive list entry and then performs the real session open and snapshot.

A separate Host-managed native-worker harness passed twice with a real PTY and Pi 1.4.0. It
verified the same PID, session and native runtime with no RPC fallback across all four Host
snapshots, then verified PTY exit and temporary-directory cleanup. The injected spawn adds flags
and a wrapper, so this is not an unmodified production spawn configuration. It does not prove
process-group cleanup, native input or a model decision.

## Remaining gates

The product source under test was App `deadfee` and Host `ca38943a5`; these qualification
fixtures do not change production source. Host qualification fixtures are saved locally as
`18edf5384`. The earlier 3,320 Android plus 34 Wear and 3,799 Host
plus 38 skipped results above are historical baselines, not reruns for the new fixtures. The
combined Android and native-worker proofs are separate: they do not yet prove Android against
the Host-managed native worker. Live GitHub CI, that combined path, unmodified native spawn,
native input and a model turn, physical Samsung/DeX hardware, an independent Linux host,
`MainActivity` and production internet/network behavior remain open. No push or release is
claimed by this qualification.
