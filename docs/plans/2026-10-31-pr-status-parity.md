# PR status parity implementation plan

> For agentic workers: use subagent-driven-development for the independent bridge and runtime-fixture tasks. Root owns central builds and runtime resources.

**Goal:** Carry the existing PR/CI status from real terminal/native Pi sessions to PocketPi through the same validated Host state used by host-owned RPC sessions.

**Architecture:** Subscribe to the public `pr-status:status` Pi event, retain only the current session's latest producer value, and republish it after successful local registration/reconnect. Host ingestion must bind the sending peer to the session and reuse the existing PR status mapper, bounds, deduplication, journal and snapshot path. Creation acknowledgements remain independent of observed CI state.

**Tech stack:** Pi 1.4 public extensions/events, TypeScript, local IPC, encrypted Remote protocol, existing Kotlin status/UI models.

**Spec:** `docs/remote-development-goal.md`, acceptance criterion 6 and native/RPC parity. The existing producer contract is documented in the Host repository's `extensions/pr-status/README.md`.

## Constraints and review focus

- No Pi core patch, production host/configuration change, real GitHub mutation or paid model call.
- Preserve actor/session/source-file authority, worker lifetime and writer admission across the upstream merge.
- Drop malformed and foreign-session data without clearing a legitimate status; explicit null clears it.
- Reconnect republishes current state, while replacement/offline cannot leak the previous session's PR.
- A successful PR create never manufactures passing CI. Watch display remains a concise status/handoff candidate, not a copied phone form.

## Tasks

1. Bridge regression and fix (`remote/index.ts`, `host/sessions.ts`, existing runtime/session tests).
   - [x] Demonstrate missing terminal/native bus delivery with a failing focused test.
   - [x] Add current-session publication, register/reconnect replay and guarded Host ingestion.
   - [x] Check valid/null/deduplicated/malformed/foreign-session and stale-peer cases.
2. Isolated actual-Pi runtime fixture (`remote/runtime-test/pr-status-pi-harness`).
   - [x] Launch real Pi with actual remote and PR-status extensions in private home/agent/project/state paths.
   - [x] Drive the actual PR parser using controlled gh JSON; use no direct synthetic status publication.
   - [x] Pair an encrypted simulated device, verify PID/session ownership, and observe pending, named failure, passing and cleared status through snapshots/events.
   - [x] Bound all waits, stop owned processes, remove private transient state, and retain only a sanitized result.
3. Integration qualification.
   - [x] Run merged Android/Wear regressions, Host native-start/source-recovery checks, type checks and appropriate Remote regression coverage.
   - [x] Run the actual-Pi fixture and inspect its sanitized evidence.
   - [x] Review and locally commit the final source and evidence. No push or release.

## Qualification boundaries

The runtime fixture can prove actual Pi extension/event/IPC/Host/encryption behavior with controlled CI data. It does not by itself prove live GitHub CI, Android navigation, physical Samsung devices, production relay/network behavior or an independent Linux host. These remain separate gates for that fixture; the user currently has no physical devices or SSH testhost available.

## Current evidence

App merge `595daae` passed 3,320 Android and 34 Wear unit tests and Wear debug assembly. Host merge `ce44cca47` retains Git admissions around native/reopen/fork starts and the original source-file guard. The missing bus listener produced the expected regression failure; its focused runtime test then passed. A real external Pi 1.4.0 process passed the isolated encrypted status probe (pending, named failure, passed, cleared; cleanup failures zero). The independent review found a mutable producer-reference cache issue. Its regression failed before the fix and passed after the cache copied validated scalar, checks and failing-name values. A fresh actual-Pi run passed all four transitions after that correction. Host full regression passed 3,799 tests with 38 skips using two test workers; type and format checks passed. The two composite Git scenarios hit their global test limits in the earlier default-concurrency run; product limits stayed unchanged. Host source is saved locally as `ca38943a5`. The separate real-repository-to-whole-screen Android integration test passed, covering chip/Changes presentation, check-state changes, clearing and reconnect restoration with a deterministic transport. The later scoped combined Android/real-Pi and native-worker results are recorded below.


## Combined Android and native-worker qualification

The next increment closes two remaining runtime seams without changing the product protocol.

4. Actual Pi and Android (`remote/runtime-test/pr-status-android-e2e`, Android `PrStatusTransportEndToEndTest`).
   - [x] Start an isolated actual Pi producer, Host, private CA/TLS relay and paired device identity.
   - [x] Drive pending, named failure, passed and cleared status through real encrypted transport, repository and production navigation/screens.
   - [x] Disconnect/block the Android socket while the status is cleared; generate pending only while offline, then allow reconnect and require the new state in the UI.
   - [x] Inspect committed-frame screenshots at phone/tablet display sizes and English/German resources. These are display-profile checks, not physical Samsung qualification.
5. Actual Host-managed native startup (`remote/runtime-test/pr-status-native-harness`).
   - [x] Ask the Host to create its native worker with real PTY/Pi, controlled PR producer and temporary settings.
   - [x] Refuse RPC fallback; verify the same PID, registered peer, session and native runtime across all four Host snapshot states.
   - [x] Verify worker termination and private-state cleanup; do not infer an agent turn or causal prompt acknowledgement from startup/status alone.

Root owns all emulator, ADB, server and process execution. Fixtures use new private paths and separate ports; the default ADB server and personal Host remain untouched. No GitHub calls, owner credentials, external mutations or neural-model turns are needed. The Android activity supplies explicit foreground/network signals and a private CA; MainActivity lifecycle, production network monitoring and physical devices remain separate gates.

6. Combined Android and Host-managed native worker.
   - [x] Pass the actual Pi TUI through the native PTY and encrypted Android navigation on English/German phone/tablet emulator profiles, verifying the same owner and native worker across status and reconnect.
   - [x] Complete the external RPC default-mode regression and final cleanup verification.

### Results and remaining limits

The combined fixture passed on the English phone profile (8.663 s) and the German and English
tablet profiles (8.870 s and 8.629 s), and the German phone profile (8.014 s).
The private CA, explicit foreground/network signals and test activity bound this result: it does
not exercise MainActivity or the system network monitor. The pending recovery value was produced
only after the actual device socket was cut. Phone English failed/recovered, tablet German
failed/recovered, tablet English failed and phone German failed screenshots were inspected and
legible. Owned fixture and emulator cleanup was verified as described in the qualification record. See [qualification details](../testing/pr-status-parity-qualification.md).

The separate native-worker harness passed twice with the same Pi PID/session/runtime and no RPC
fallback across four snapshots; PTY exit and temporary-directory cleanup were verified. Its
injected spawn adds flags and a wrapper. It does not prove a production spawn configuration, a
process-group audit, native input or a model turn. The combined native Android matrix passed: phone English 28.523 s, tablet German 26.226 s,
tablet English 33.664 s and phone German 30.394 s. Each verified `piMode=tui`, the same owner
and native worker, one cut and two upgrades. The external RPC default-mode regression passed (German phone, 16.043 s); owned fixture,
emulator, temporary-credential and ADB cleanup was verified. Live GitHub CI, unmodified native spawn, native input and
a model turn, MainActivity, physical Samsung/DeX, independent Linux and production internet
remain open. App merge `66df8f7` passed 3,333 Android and 34 Wear tests and Wear assembly; Host
merge `2588516bd` passed 72 scoped outbox tests, 310 regression tests with five skipped, and
final type checking. The Git PR composite hit its 240-second global limit under emulator load;
the Git-write suite passed six tests. The isolated PR rerun passed in 134.79 s without emulator load and with the original limit. Earlier full-suite counts remain historical baselines.
