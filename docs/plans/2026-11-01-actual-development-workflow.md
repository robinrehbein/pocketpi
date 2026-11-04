# Actual native development workflow implementation plan

> **For agentic workers:** Use superpowers:subagent-driven-development. Root executes runtime checks; independent Terra agents author and review disjoint changes.

**Goal:** Exercise one real Android navigation flow through a single Host-managed native Pi worker, file editing, shell/model verification, explicitly confirmed commit/push/PR and matching PR/CI status.

**Architecture:** Extend the existing private PR-status runtime harness with an exclusive workflow mode and focused helper. Use actual disposable Git objects and a loopback-only remote, controlled provider/CI, actual public Pi 1.4 worker and encrypted Android transport. Android composes production RemoteNavigation rather than isolated panes. No pre-created feature commit, second worker, synthetic session or fixture-generated UI action may stand in for a user step.

**Tech Stack:** Existing TypeScript Host runtime fixture, public Pi faux provider APIs, Git, Kotlin/Compose instrumentation.

**Spec:** docs/remote-development-goal.md acceptance criteria 2, 3, 5 and 6; this is local qualification work toward those criteria, not physical-device acceptance.

## Global constraints

- Preserve normal capability, ownership, explicit confirmation and uncertain-write gates.
- All repositories, credentials, local endpoints, state and workers are task-owned; no external GitHub, push, release or personal Host mutation.
- Keep public paged editor gates disabled. This increment does not alter those features.
- Root alone executes tests, builds, servers, Docker and ADB; agents author/review only.
- Reject changed worker/session/runtime/project identity throughout; record exact commits and test inputs.
- Existing historical evidence remains scoped; generated model/provider responses do not qualify a real external model or GitHub.

## Review focus

1. A mutation before confirmation: verify initial HEAD until commit confirmation, remote HEAD until push confirmation, and zero PR creates until confirmation.
2. Wrong Git remote or credential escape: transport adapter accepts only the exact disposable target and fails closed otherwise.
3. Worker replacement or source mismatch: all phase observations bind to one original PID/session/runtime/project and expected file bytes.
4. Uncertain delivery or duplicate operation: assert single dispatch/create and never automatically retry.
5. Misleading CI success: derive PR status from the actually created controlled PR and resulting HEAD, then verify rendered Android state.

## Shared fixture contract

Existing private metadata keys remain: routeId, relayUrl, deviceId, deviceSecret, projectId, sessionId, caCertPath and statusUrl. Workflow mode adds `developmentWorkflowFixture: true`, `nativeWorker: true`, `sessionKind: "actual-pi"` and `workflow` with `fileName`, `originalText`, `editedText`, `shellCommand`, `shellMarker`, `commitMessage`, `prTitle`, `prBody`. Add `agentPrompt` only if an actual deterministic model-turn probe is implemented; do not label extension command delivery as a model turn.

The private status response adds `workflow` containing nonsecret verification flags and observed identifiers/hashes: `ownerStable`, `fileSaved`, `shellVerified`, `agentVerified` (only actual turn), `commitVerified`, `pushVerified`, `pullRequestVerified`, `checksPassed`, `commitCount`, `pushCount`, `pullRequestCreateCount`, `head`, `remoteHead`, `pullRequestNumber`. The status must additionally expose underlying evidence rather than only aggregate flags: initialHead/initialRemoteHead, expected and observed file hashes, shell command/exit/observed marker, actual commit head/file hash/message/paths, actual remote head, actual PR number/base/branch/head/title and observed named check state. Capture the initial and current owner PID/session/runtime/project identities, and compare at each stage. Authors coordinate exact JSON property names before implementation is finalized. Pending verification must be false, not assumed. A private CI transition may be requested only after the exact actual commit/remote/created PR is verified. Each false flag must remain visible to diagnostics.

## Scope of this increment

This joins previously separate product routes. It is not full acceptance of criteria 2/3/5/6: external file conflicts, live shell output/stop, ticket creation, complete Host audit coverage, actual external model turns, actual GitHub and physical-device qualification still require their own evidence. Shell composer execution is not a model turn. Do not relabel this scenario as ticket-to-PR acceptance.

## Task 1: Host runtime fixture

- [x] Add exclusive `--development-workflow` native mode, reject unrelated editor/question modes.
- [x] Build focused helper(s) alongside runtime-test/pr-status-android-e2e/run.ts for disposable Git, controlled exact-target push/PR provider, metadata and status evidence.
- [x] Preserve one public Pi worker; enable bash/Git settings only in this private fixture. Trace real file/shell/model results and actual Git/provider effects.
- [x] Expose shared contract and readiness marker after complete metadata write. Cleanup all owned processes/state on failure or shutdown.
- [x] Root runs extension type/format checks and focused fixture checks; reproduce any real product defect before fixing it.

## Task 2: Android actual-navigation instrumentation

- [x] Create DevelopmentWorkflowTransportEndToEndTest.kt with explicit fixture opt-in and private TLS/pairing setup following existing PR-status fixture.
- [x] Compose actual RemoteNavigation with production repository and route into the captured session. UI actions perform file edit/save, shell run, selected commit review/confirm, push review/confirm and PR review/confirm; no direct repository mutations for these actions.
- [x] Assert owner identity and no premature writes via private status between UI steps. Assert production rendered success and matching PR/CI status.
- [x] Root builds instrumentation and runs one owned phone emulator scenario. Failure is evidence to diagnose, not a reason to weaken assertions or seed successful state.

## Task 3: Independent review and qualification

- [x] Review both implementations against the shared contract and five review conditions.
- [x] Run focused checks and the actual private chain, saving exact input hashes, logs, status and screenshot after rendered success.
- [x] Record passed/failed/unqualified boundaries, clean exact owned fixture credentials/processes and commit only reviewed changes locally.

## Recorded result

The first complete phone-viewport scenario passed in 82.84 seconds. Root TSC, changed-TypeScript formatting, APK build and independent final runtime review passed. The review found no blocking issue for the bounded chain. Source snapshots were taken after that run; a subsequent pinned-input run should record provenance before launch. Detailed limitations and earlier fixture failures are in ../testing/development-workflow-qualification.md. All private fixture processes and copied device credentials from this run were cleaned up.
