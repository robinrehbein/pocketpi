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

The runtime fixture can prove actual Pi extension/event/IPC/Host/encryption behavior with controlled CI data. It does not prove live GitHub CI, full Android navigation, physical Samsung devices, production relay/network behavior or an independent Linux host. These remain separate gates; the user currently has no physical devices or SSH testhost available.

## Current evidence

App merge `595daae` passed 3,320 Android and 34 Wear unit tests and Wear debug assembly. Host merge `ce44cca47` retains Git admissions around native/reopen/fork starts and the original source-file guard. The missing bus listener produced the expected regression failure; its focused runtime test then passed. A real external Pi 1.4.0 process passed the isolated encrypted status probe (pending, named failure, passed, cleared; cleanup failures zero). The independent review found a mutable producer-reference cache issue. Its regression failed before the fix and passed after the cache copied validated scalar, checks and failing-name values. A fresh actual-Pi run passed all four transitions after that correction. Host full regression passed 3,799 tests with 38 skips using two test workers; type and format checks passed. The two composite Git scenarios hit their global test limits in the earlier default-concurrency run; product limits stayed unchanged. Host source is saved locally as `ca38943a5`. The separate real-repository-to-whole-screen Android integration test passed, covering chip/Changes presentation, check-state changes, clearing and reconnect restoration with a deterministic transport. A combined Android/real-Pi run remains a separate gate.
