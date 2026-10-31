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

### Android with Host-managed native worker

The combined Android test also passed with a Host-managed native PTY and actual Pi 1.4.0 TUI:
phone English 28.523 s, tablet German 26.226 s, tablet English 33.664 s and phone German
30.394 s. Each run verified the same owner and native worker, `piMode=tui`, one cut and two
upgrades. Phone English failed, tablet German failed and tablet English recovered screenshots
and phone German recovered screenshots were inspected. This uses controlled producer input and the injected native spawn wrapper and
flags, private CA, test activity and explicit foreground/network signals described above. It does
not exercise real GitHub CI, native input, a model turn, MainActivity or production networking.
The external RPC default-mode regression passed on the German phone profile in 16.043 s,
with `piMode=rpc`, owner verification, one cut and two upgrades. All five owned fixtures
exited cleanly; their metadata and private fixture directories were removed. Device JSON/CA
and host-side copies were deleted, the owned emulator and ADB server stopped, and ports
5191/5584/5585 were verified closed. No default ADB server or personal Host was used.

On App merge `66df8f7`, 3,333 Android and 34 Wear unit tests and Wear debug assembly passed.
On Host merge `2588516bd`, 72 scoped outbox tests and a regression run with 310 passed and
five skipped passed; final type checking passed. A Git PR composite hit its 240-second global
limit under emulator load; the Git-write suite passed all six tests. The isolated PR rerun passed in 134.79 s without emulator load and with the original limit.

## Remaining gates

The prior fixture product baseline was App `deadfee` and Host `ca38943a5`, with Host fixtures
saved as `18edf5384`. The new combined native matrix used the merges and regression scope
recorded above. Earlier whole-suite counts remain historical baselines. Live GitHub CI,
unmodified native spawn, raw PTY input and a model turn, physical Samsung/DeX hardware,
an independent Linux host, `MainActivity` and production internet/network behavior remain
open. No push or release is claimed by this qualification.


## Native command delivery from the Android composer

An optional `prStatusInputExpected=true` path passed on the English phone profile (32.610 s)
and German tablet profile (19.680 s). It types a fresh fixture command/nonce into the production
composer and clicks send once. A public Pi extension command handler writes a separate private
trace. `inputVerified` requires exactly one matching nonce, PID, session and TUI mode plus the
current live Host/PTY/native-worker identity; a transport dispatch ACK is insufficient. Both
runs also passed pending/failure/pass/clear and offline-produced reconnect restoration, with
one cut and two upgrades, and retained `inputVerified=true` in the final sanitized proof.

This proves command-handler delivery, not raw terminal bytes, model turns or a Pi completion
response. The phone pending and tablet recovered screenshots were inspected. With the tablet
keyboard open, the Changes header visually overlaps the PR card; this is an open visual issue
under investigation, not a visually qualified tablet result. The two owned fixtures exited,
private trees/metadata and temporary credential copies were removed, the owned emulator/ADB
stopped, and ports 5191/5584/5585 were closed. Type/format checks and Android test APK assembly
passed. Production app/Host source was unchanged by this fixture increment.


### Scroll/IME comparison

A follow-up initially failed its unchanged recovered-card visibility assertion (26.764 s).
The diagnostic was moved before that assertion to record the original state, without changing
production code. A fresh German tablet run passed in 15.636 s. It recorded a retained scroll
value of 142 px: header bottom 314 px, PR card top 204 px. After explicit scroll-to-index-zero,
the card top was 346 px with IME still visible; hiding IME kept the same card/header bounds.
The before/top-with-IME/top-without-IME screenshots were retained; the top-with-IME frame was
inspected and legible. This comparison supports a retained-scroll explanation; it does not
establish a persistent header-layout defect or qualify MainActivity insets.

The instrumentation now explicitly brings the reinserted card to the top before asserting
visibility in the default path. Its optional diagnostic records the unscrolled state first and
then performs that same tested scroll interaction. This changes test preparation only; native
identity, exact command trace and reconnect-state checks remain in place. The prior failure is
not reclassified as a pass. The diagnostic's owned fixture/emulator/ADB and temporary credentials
were cleaned, with ports 5191/5584/5585 closed.

The final test APK compiled successfully after the default-path scroll preparation was added.
That final default branch was not separately rerun in the emulator; the successful diagnostic
run exercised the same public scroll interaction before its retained visibility/state assertions.
