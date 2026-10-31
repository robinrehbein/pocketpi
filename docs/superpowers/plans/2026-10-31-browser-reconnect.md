# Browser reconnect implementation plan

> For agentic workers: use bounded independent implementation and review. Root owns all Gradle and emulator runs.

**Goal:** Recover an already open browser after a real transport loss, preserving the selected tab, login and unsent draft while requiring explicit new control and never replaying uncertain input.

**Architecture:** Keep the existing browser view attached to its session while disconnected. Rebind it read-only only after the repository has restored the same authorized route/project/session and rediscovered browser capability. An isolated TLS fixture drops the actual Android WebSocket after a real input was applied but before its acknowledgment. Actual Pi read-only tools prove old ownership removal and the distinct new participant.

**Tech stack:** Existing Kotlin/Compose repository and BrowserClient, encrypted relay, TypeScript test fixture and public Pi 1.4.0 offline faux provider. No Pi-core patch or new protocol field.

**Spec:** `docs/remote-development-goal.md`, `docs/testing/shared-browser-handoff.md`.

## Constraints and proof boundaries

- Host/app bases are d50101c02 and 79e0049; both were clean and pulled with `--ff-only` before work.
- Preserve unsent and possibly applied input. No automatic acquire, send retry or transfer of old lease credentials.
- User closure, navigation, changed authorization and changed browser identity must not resurrect an old pane or ownership.
- Status/control HTTP stays available during the fixture's socket cut; actual Pi IPC and Chromium stay alive.
- Proof uses a non-first tab and unique form/input values. Single-tab or repository-only disconnect tests are insufficient.
- The fixture uses temporary trust/grants/home/profile and no paid provider or production configuration.
- Phone/tablet emulator and controlled local login evidence do not qualify physical Samsung, public networks, host death or independent remote Linux.

## Task 1: Context-safe Android recovery

Files: BrowserClient.kt, DefaultRemoteRepository.kt, their focused tests and BrowserTransportEndToEndTest.kt.

- [x] Add failing client and repository regressions for a changed connection generation, retained non-first tab and uncertain input, and capability-gated automatic recovery.
- [x] Rebind the existing view without a transient null/session change; keep it read-only and preserve uncertainty. Reject stale callbacks and cross-context recovery.
- [x] Integrate recovery only after the same context is restored and capability discovery completes. Closed/changed contexts stay closed.
- [x] Verify targeted tests, full required quick-check and APK assembly after source freeze.

## Task 2: Real interrupted-input fixture

Files: host runtime-test/browser-android-e2e/run.ts and browser-shared-harness helpers/provider.

- [x] Add optional reconnect mode with a decoy first tab, then the login tab; preserve the existing mode.
- [x] Apply one exact text input through the real runtime and hold only its response outside the owner queue. Allow a newer local unsent draft before cutting both ends of the actual TLS upgrade pair.
- [x] Gate new upgrades until actual Pi Open/Participants observes no owner and no former device participant. Bound held responses and close upgraded sockets on teardown.
- [x] Allow reconnect; actual Pi verifies a distinct reader participant. Device input/acquire counters must not grow before explicit user acquisition.
- [x] After explicit acquisition, return ownership to Pi and prove the same browser/tab/login cookie. Type/format check and independently review the frozen artifact.

## Task 3: End-to-end and Linux evidence

- [x] Run the actual encrypted reconnect flow on phone and tablet emulator sizes, inspect retained draft/uncertainty screenshots, and record exact scope/results.
- [x] Preserve independent Linux evidence: build node-pty offline in an existing full Node image, run the actual native wrapper and Pi CLI, and state which lifecycle gates remain open.
- [x] Commit only verified local changes; remove owned fixture credentials/processes. Keep the overall goal active for remaining preview/emulator/Linux/device requirements.

## Review focus

Old replies after reconnect; user closing or switching context during recovery; capability loss; held-input acknowledgment uncertainty; cleanup before allowing the new participant; zero automatic input or acquire replay.

## Result

Phone 14.175 s and tablet 11.215 s actual TLS-cut flows passed with original browser/tab,
applied-once input, different new participant, unchanged automatic input/acquire counters,
retained newer draft and final cookie proof. Required quick-check: 3,058 app + 34 Wear tests,
80 s; focused client/repository: 464 tests. The activity explicitly supplies foreground/network
availability, not a real Android network monitor. Linux native-worker proof was independently
repeated, but packaging, browser and independent remote host gates remain open. Local commits
and credential/process cleanup follow these verified results; the full goal stays active.
