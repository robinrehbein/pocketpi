# Combined Native Android Journey Implementation Plan

> **For agentic workers:** Use parallel agents for independent work and review; the root agent owns Git, builds, tests, emulator processes and cleanup.

**Goal:** Qualify one local, isolated ticket-to-PR Android journey through the real application, one native Pi worker and the production paged-editor path.

**Architecture:** Combine existing ticket/development-workflow and native production editor fixtures in one explicit private test mode. Launch the real application once, create the session from the ticket, and retain the same session, owner and UI through editor, file, agent, shell and Git/PR/CI actions. External services remain isolated fixtures. Run phone and tablet viewport profiles; qualify the Linux package separately afterward.

**Tech stack:** Android instrumentation/Compose, Kotlin, Node 24.18.0, TypeScript, native Pi worker, isolated local TLS fixture and emulator.

**Spec:** `docs/remote-development-goal.md`; current boundaries in `docs/testing/ticket-workflow-qualification.md` and `docs/testing/native-production-editor.md`.

## Constraints and review focus

- Git-first pull and preserve existing uncommitted files in both repos. Pin both source commits and dirty inputs, APK hashes, worker identity and fixture logs.
- Existing test modes, native production feature negotiation and default gates must not be relaxed for unrelated fixtures or runtime.
- One real `MainActivity`/application repository and encrypted journal, ticket-created session, native owner and isolated workspace must be verified throughout; separate successful tests do not establish this.
- Avoid duplicate effects: assert exact Source editor result/receipt/release, file contents, shell marker, commit, push and PR/CI events; detect observer overflow and stale or replaced owner/session.
- Use a fresh owned emulator/ADB instance, isolated repositories, controlled stubs and identity-checked cleanup. Do not mutate personal devices, live hosts or real external services.
- Physical Samsung, vendor IMEs, independent Linux host and release builds remain unqualified by this test; RPC is frozen compatibility only.

## Tasks

### 1. Combine host fixtures without widening other modes

**Modify:** `packages/pi-atlas/extensions/remote/runtime-test/pr-status-android-e2e/run.ts` in the Host worktree.

- [ ] Add a single explicit combined private mode requiring native worker, ticket and development workflow, native production proof, editor submit and max-unchanged; reject incompatible modes. Cover both the development-workflow/editor exclusion and the native wrong-grant whitelist.
- [ ] Preserve production negotiation without test feature-gate overrides. Wait for the ticket-created session and bind both fixture components to the same native worker. Expose workflow and editor status needed by the Android test.
- [ ] Expose a combined-ready result only after asynchronous binding has installed both owner/editor observers and verified the native worker and workflow agent; propagate binding errors as test failures. Before ticket creation, assert no fabricated session/owner. Permit the new flag itself while rejecting unrelated flags.
- [ ] Assert the ticket session, project/worktree, runtime and owner PID are the same before and after the editor and Git stages; correlate capability request, Source bytes and receipt/release to the same session, route and device. Verify `nativeEditorVerified`, `nativeEditorOriginalCalls == 0` and zero `question.answer` calls; a default zero alone is insufficient. Add focused fixture validation for invalid flag combinations.

### 2. Add a single application-level Android journey

**Create:** a focused `src/androidTest/java/de/joinnoah/pi/remote/CombinedNativeJourneyEndToEndTest.kt` in the App worktree.

- [ ] Use `ProductionEntryBootstrap`, `createEmptyComposeRule` and `ActivityScenario<MainActivity>` with the application-created repository and encrypted stores. Start without a preselected session; open the fixture host and create/select the ticket session through UI.
- [ ] Reuse the assertions in `DevelopmentWorkflowTransportEndToEndTest.kt` and `PagedEditorNativeProductionFactoryEndToEndTest.kt` for file edit, agent, native paged question, shell, commit, push and PR/CI. The existing agent fixture expects the edited file, so run file edit before the agent unless the fixture is deliberately changed and revalidated.
- [ ] Verify the same activity, route/project/session and native owner across stages. Verify the paged editor's production capability negotiation, full Source content/hash, one commit and successful outcome release. Ensure bounded transport observers did not overflow.
- [ ] Keep this as a positive production-path proof: wrong-grant behavior requires a separate injecting transport and is not established here. Preserve the workflow agent trace's expected events and edited-file hash when adding the editor probe.

### 3. Own orchestration and qualification evidence

**Create:** a private runner derived from `/private/tmp/pocketpi-native-fresh-tools/run-pocketpi-native-factory.py`; do not run the older ticket runner unchanged.

- [ ] Launch one fixture and one instrumentation run per profile on a freshly owned emulator/ADB instance. Pin source inputs, build/test APKs, installed hashes and run-specific nonce. Guard all cleanup with ownership and process identity; preserve evidence on uncertainty.
- [ ] First establish a meaningful failing boundary on incomplete combined wiring; then complete the wiring and run the actual full journey on phone and tablet viewport profiles. Independently verify action ordering, identical owner/session, exact editor and Git results, APK hashes and cleanup.
- [ ] Record failures and qualification boundaries in a new `docs/testing/` report and update the main goal map only with actually observed evidence. Do not claim real-device or external-provider acceptance.

### 4. Repeat the same journey against a current Linux package

- [ ] Build a fresh package/image from the pinned current Host source and extend the isolated Linux fixture for the same journey; previous Browser-only image and synthetic-client runs are separate evidence.
- [ ] Repeat Android journey and identity/action/cleanup audit against that package. Clearly report local container scope; an independent Linux machine remains a separate gate.
