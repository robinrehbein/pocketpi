# Remote File Mutations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development for the user-requested parallel Terra execution. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let an authorized Android user create, rename and delete individual project files directly, with explicit delete confirmation, no destination overwrite, recoverable drafts and honest uncertain-outcome handling.

**Architecture:** Add a separately negotiated, default-off Host capability and a durable file-operation outcome store; do not use the volatile `CommandLedger` as a restart-safe receipt. The Host validates authority and path preconditions at the mutation boundary, while Android durably reserves exact requests and retains drafts and unresolved receipts. The vertical slices below are independently testable but remain unavailable to ordinary users until platform and recovery gates pass.

**Tech Stack:** Kotlin/Compose and encrypted Android storage; Node.js >=24.18.0, TypeScript, Vitest, existing encrypted relay protocol and Host file service.

**Spec:** `docs/superpowers/specs/2026-11-02-remote-file-mutations-design.md`. Read the spec before execution; reconcile any disagreement with this plan before implementing.

## Global constraints

- Follow Git-first for each repository and preserve unrelated changes. Before execution establish current branches, commits, upstreams and isolated worktrees. The Host code is in the separate NOAH monorepo at `packages/pi-atlas/extensions/remote/`; Android code is in PocketPi.
- Work in small standalone slices with interfaces agreed before dispatch. User requested parallel Terra agents; parallelize disjoint Host ledger, protocol and Android journal work only after shared contracts are fixed. Integrate sequentially; one owner runs shared builds, runtime fixtures and Git operations.
- Do not deploy, release, change the configuration of a personal Host, or use a real project in tests. Worktrees, files, sessions, ports and devices used for tests must be task-owned. Test-only capability activation is allowed in isolated fixtures.
- Existing `CommandLedger` is in memory, caches a full-command fingerprint and result only temporarily, and is lost at restart. Existing file-save retry does not establish exactly-once execution after restart or expiry.
- Identify each operation by authenticated device plus opId, unique across sessions and projects for that device; fingerprint the session, project, kind, exact paths and preconditions. A reused identity with a different fingerprint is refused.
- Retain unknown operations until explicitly reconciled; never infer that a delete or rename happened from the current path state, or automatically submit a new operation ID after an uncertain result. Replayed exact IDs must never cause a second filesystem effect, including after retention maintenance.
- No overwrite, recursive directory mutation or implicit parent creation. Rename is restricted to a basename change in the same parent directory. File path, authorization, platform and filesystem guarantees must match the approved spec. Do not promise atomic compare-and-swap against arbitrary external writers unless actually provided and tested by the target platform.
- Exactly one Host writer may own the durable outcome store; refuse admission if exclusive ownership is unavailable. Keep permanent records bounded and refuse new operations when full; no automatic eviction or identity reuse.
- Gate enablement on a verified lock domain covering every Host start path and store instance, plus an external trusted continuity or epoch decision covering combined journal/marker loss and rollback. If either guarantee cannot be established, keep the capability disabled; do not claim universal exactly-once execution.
- Keep each capability disabled by default until its durable storage, platform primitive and end-to-end qualification are complete; then enable the qualified scope within the user's existing authorization.

## Review focus

- Revocation during queueing or immediately before mutation: no filesystem effect after a failed final authority check (Tasks 2–3).
- A destination created by another process between validation and rename: it must remain untouched (Task 3).
- Host crash after the filesystem effect but before durable completion: return unknown, never replay the effect (Tasks 1 and 3).
- Android storage failure or process death during draft relocation/acknowledgement: preserve drafts and pending identity without sending a duplicate (Tasks 4–5).
- Path aliases, parent/symlink replacement and case-insensitive filesystem collisions: reject or fail safely; document what external races cannot be excluded (Tasks 2–3 and 6).

## File map and execution dependencies

- Host protocol: `packages/pi-atlas/extensions/remote/protocol/files.ts`, protocol fixtures and `files_write_test.ts`; define bounded request/result schemas and a dedicated capability.
- Host storage: new `packages/pi-atlas/extensions/remote/host/file-mutation-outcomes.ts` and tests; adapt durable storage patterns in `editor-outcomes.ts`, without assuming that ledger's lifetime or semantics fit file operations.
- Host execution: `packages/pi-atlas/extensions/remote/host/files.ts` and `daemon.ts`, with new focused Host and daemon tests. Reuse existing authority/path checks where valid but do not use existing `replace` to implement no-overwrite rename.
- Android protocol and repository: `ProjectFiles.kt`, `ProjectFilesLoader.kt`, `RemoteRepository.kt`, transport parsing and related protocol fixtures/tests; locate the concrete transport mappings before changing them.
- Android storage and UI: `FileDraftStore.kt`, a focused operation journal beside it, `ProjectFilesLoader.kt`, file browser/confirmation UI and localized resources; existing `ProjectFilesLoaderTest.kt`, `FileDraftStoreTest.kt` and UI tests.
- Qualification: new `docs/testing/remote-file-mutations.md` and task-owned Host/Android integration fixtures. Do not treat a fixture as physical-device or production proof.

### Task 1: Durable operation identity and outcomes

**Files:** new Host `file-mutation-outcomes.ts` and `file-mutation-outcomes_test.ts`; inspect `editor-outcomes.ts` for safe storage primitives.

The editor ledger relies on a caller-held lock, including the existing CLI `flock`, and its instance queue is not a process lock. Its reserve, lookup and release semantics are unsuitable unchanged for this contract. Its provisioning marker detects one missing file but not combined marker/ledger loss or rollback; it does not establish continuity by itself.

**Interfaces:** `FileMutationTuple` identifies an operation by authenticated device plus opId; a canonical fingerprint binds session, project, operation kind, source and destination, and every precondition. Reusing an opId with any changed field is refused even across sessions or projects. `reserve(tuple)` returns new, prior completed result, pending/unknown, or mismatch; `complete(tuple, result)` durably records a terminal result; `lookup(tuple)` is read-only. Exact names may be aligned with the completed spec before parallel dispatch.

- [ ] Add tests for concurrent identical requests, same ID with different kind/path/precondition, authenticated device isolation, capacity and malformed or unreadable storage. No effect may run before reservation is durably committed.
- [ ] Run the focused test before implementation and record the behavioral failure.
- [ ] Implement atomic durable reservation and terminal outcome persistence; fsync as required by the storage contract. Incomplete reservations after restart return unknown and do not dispatch. Fail closed when persistence, integrity or capacity is uncertain.
- [ ] Identify and test the actual exclusive lock domain across all Host start paths and direct store instances, including lock lifetime and loss. Decide and verify an external trusted continuity or epoch mechanism for combined marker/ledger loss and rollback before enabling the capability; otherwise leave it disabled.
- [ ] Add crash-point tests before reservation, after durable reservation, after effect and before result persistence, and after result persistence. An outcome query never starts an operation. Keep permanent records bounded and fail closed when full; do not silently discard anti-replay evidence. Test that a second Host writer cannot acquire the store, and that uncertain interrupted storage writes block admission. Any future reclamation requires a separate decision and a verified issuance/epoch mechanism.
- [ ] Run `pnpm --filter @noah/pi-atlas test:extensions -- remote/host/file-mutation-outcomes_test.ts` from NOAH; require all focused tests to pass. Review storage crash and retention semantics independently.

### Task 2: Protocol, authority and capability gate

**Files:** Host `protocol/files.ts`, `protocol/fixtures/`, `host/daemon.ts`, corresponding protocol/daemon tests; PocketPi fixture copy and transport parsing.

**Interfaces:** Define create, rename, delete and outcome-query requests/results with a random request ID and explicit expected source version/identity where applicable; destination must be absent. The fingerprint includes all fields and the authenticated device scope. Outcome states distinguish applied, definitely refused before effect, and unknown. Status queries require an authenticated device with current access to the shared, trusted project; they remain available with the mutation switch or advertised capability off, but return forbidden on revocation.

- [ ] Add fixture round-trip and invalid-request tests for malformed or absolute paths, traversal, control characters, unsupported operation, mismatched IDs and missing preconditions; unknown capability must leave existing reads/saves working.
- [ ] Run the protocol tests and record the expected failing cases. Implement bounded validation and default-off capability negotiation, with a separate gate for mutation execution and outcome lookup for previously admitted operations.
- [ ] Route new mutations through the durable store, not the daemon's generic volatile ledger; ensure revoked/unshared/untrusted project and disconnected device fail at every required authority check, including after queue wait and before effect. Protect outcome queries by the original authenticated device and current shared/trusted project access, without requiring a live editor or the mutation switch/capability. Test switch-off lookup succeeds and revocation or another device is forbidden.
- [ ] Run `pnpm --filter @noah/pi-atlas test:extensions -- remote/protocol/files_write_test.ts remote/host/files_write_daemon_test.ts` plus the new protocol and daemon tests. Verify existing fixtures remain compatible.

### Task 3: Host file mutations with platform safety gates

**Files:** Host `files.ts`, a focused filesystem primitive adapter if needed, new `files_mutations_test.ts` and daemon integration tests.

**Interfaces:** `create`, `rename`, `delete` consume validated operation tuples, return a durable terminal receipt only after the side effect, and report unknown if effect/result ordering cannot be established. Serialize overlapping source/destination mutations and saves within this Host; external processes are outside that lock.

- [ ] Write tests for create-only exclusive creation, source-version mismatch, absent source, existing destination, cross-parent rename refusal, missing parent, denied path, symlink/hard-link and non-regular-file refusal, and no directory or recursive operation. Verify failed requests do not modify any file.
- [ ] Add deterministic interleavings for source replacement, directory/symlink swaps, destination creation, authority revocation while queued and just before effect, and competing save/rename/delete. An existing destination must never be replaced.
- [ ] Probe the actual platform primitives. Implement exclusive no-follow creation and atomic no-replace rename where supported; a check followed by ordinary `rename` is insufficient because it can overwrite a racing destination. Assess descriptor-relative native helpers as described in the spec, and document the remaining same-user race boundary. If the required operation primitive cannot be established, leave that operation unavailable on that platform rather than silently degrade.
- [ ] For delete and rename bind the final source identity/version to the confirmed observation; state the remaining gap if the platform cannot atomically bind check and effect. Do not claim protection from arbitrary external concurrent replacement until a primitive and test demonstrate it. Recheck access immediately before effect; a post-effect error is unknown unless the durable record proves a terminal outcome.
- [ ] Define and test the persistence boundary: after an effect, perform the platform-supported filesystem synchronization required by the approved contract before persisting and returning applied; any effect, sync or result-persistence uncertainty is unknown. Do not report applied solely because a syscall returned. Explicitly document process-crash versus power-loss guarantees and unsupported directory-sync behavior.
- [ ] Inject crashes around durable reservation/effect/receipt and test replay across a real Host process restart. Check duplicate request IDs never issue another effect, including on completed result replay and pending unknown.
- [ ] Run the focused Host/daemon suite and existing `remote/host/files_write_test.ts` and `remote/host/files_write_daemon_test.ts` under Node 24.18.0. Record supported Mac and Linux filesystem/OS combinations separately; an untested platform stays disabled.

### Task 4: Android durable operation journal and draft ownership

**Files:** new `FileMutationJournal.kt` and `FileMutationJournalTest.kt`; `FileDraftStore.kt`, `ProjectFilesLoader.kt`, related loader tests.

**Interfaces:** Store immutable exact request tuple, request ID and state before possible dispatch, separately from editable draft text. Bind records to route/session/project and both paths. Reserve both overlapping paths while pending; only a matching durable receipt resolves its own operation. The recovery surface lists unknown operations and journal-loss state and requires explicit user acknowledgement, without equating acknowledgement with proof or authorizing a retry.

- [ ] Add tests for persistence-before-send, exact-ID recovery after process recreation, storage failure/corruption/full capacity, duplicate payload mismatch and retained unknown. No storage failure can result in an unjournaled dispatch. Test lost/unreadable journal exposes an explicit recovery and acknowledgement surface and blocks unsafe mutation, including after acknowledgement when operation identity is unavailable.
- [ ] Add tests for active and parked drafts, newer typing and delayed acknowledgements, existing destination draft, session switch, discard with unresolved receipts and interrupted draft mapping. Rename/delete must not silently remove or overwrite either draft or any pending save receipt.
- [ ] Implement serialized atomic journal updates, or retain the original path-keyed draft as an explicitly recoverable orphan; never move by separate remove and add. If a destination draft exists, retain both and require explicit recovery. Preserve data when the Host operation is unknown or refused.
- [ ] Run `./gradlew testDebugUnitTest --tests '*FileMutationJournalTest' --tests '*FileDraftStoreTest' --tests '*ProjectFilesLoaderTest'` from PocketPi. Require passing assertions and no skipped relevant cases.

### Task 5: Android user workflow and reconciliation

**Files:** `ProjectFiles.kt`, `ProjectFilesLoader.kt`, `RemoteRepository.kt`, file browser/confirmation Compose UI, `values/` and `values-de/`, focused repository and UI tests.

**Interfaces:** User creates a file under a selected directory, renames one file within that directory, or explicitly confirms deletion of the displayed file/version. Before rename or delete perform a fresh authorized read and version preflight; do not use a cached listing as the confirmation source. Confirmation is invalidated on target/version change. UI reports pending, applied, refused and unknown distinctly and offers explicit outcome lookup/recovery without retrying a new destructive request.

- [ ] Add tests that delete has no effect before confirmation; confirmation names the exact path and is invalid after the selected file changes. Check disabled/offline/unsupported behavior and collisions are understandable. Confirm fresh preflight occurs before the confirmation is enabled and stale, failed or revoked reads prevent dispatch.
- [ ] Implement workflows with capability gating and the durable journal barrier. Keep the original request ID and payload for exact replay only when the durable Host contract allows it; outcome lookup after restart or lost ACK must not perform a new mutation.
- [ ] Test delayed result after navigation/replacement editor, concurrent Save and mutation, changed source after confirmation, loss of ACK followed by process recreation, and explicit recovery of drafts for missing/renamed files. No background replay or unrelated buffer replacement is allowed. Verify every affected dirty, parked or recovery draft blocks rename/delete until saved or explicitly discarded; pending saves must settle first, and an existing destination draft must not be overwritten. Test that the watch exposes no mutation controls and issues no mutation commands; this client restriction is not a host security boundary, and Host authorization and validation remain mandatory.
- [ ] Run `./gradlew testDebugUnitTest --tests '*ProjectFilesLoaderTest' --tests '*FileMutation*Test' --tests '*FileDraftRecoveryUiTest'` and resource parity checks from the repository's `scripts/quick-check.sh` workflow.

### Task 6: Cross-process qualification and review gate

**Files:** task-owned integration fixtures and `docs/testing/remote-file-mutations.md`.

- [ ] Exercise real Host plus Android repository/UI with isolated files: each action, denial, collision, stale delete confirmation, lost ACK, app force-stop, Host restart at each ledger/effect boundary, and explicit recovery. Independently audit filesystem changes and request IDs; record source commits, fixture paths, commands and results.
- [ ] Run Mac and Linux tests on actual supported filesystems; include simultaneous external destination creation and symlink/parent replacement. Record no-replace adapter feasibility for each operation and platform; unavailable or unqualified operations are not a completed goal. If filesystem primitives, crash durability or source identity guarantees differ, record the exact limitation and keep affected capability disabled.
- [ ] Run `pnpm --filter @noah/pi-atlas check:extensions` and the focused plus existing Host/protocol suites; run PocketPi `./scripts/quick-check.sh` and applicable Android instrumentation on owned emulator(s). Record failures, skipped tests, and whether physical devices were exercised. Do not equate local tests with CI or production.
- [ ] Review protocol compatibility, authority, no-overwrite, recovery, draft preservation and retention capacity across both repositories. Resolve blockers and update the qualification document. Keep default-off until the applicable platform gates pass, then enable only the qualified scope within the user's existing authorization.
