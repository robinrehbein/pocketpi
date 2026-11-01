# Paged Editor Dialogs Implementation Plan

> **For agentic workers:** Execute the tasks with tests first. Parallel work is permitted only on the disjoint files and agreed interfaces below; integrate and review at each dependency gate.

**Goal:** Deliver and edit an exact prefill of up to 1,048,576 UTF-8 bytes and return an exact final value of 0–1,048,576 bytes to the original editor owner.

**Architecture:** A separate per-connection capability exposes a purpose-typed source reference. Existing sequential details paging is reused with a distinct editor-prefill delivery grant. A bounded sequential upload is reconstructed and validated at the host, then sent through an independently qualified owner transport; success requires the original owner's acknowledgement. The bounded editor remains unchanged for older peers.

**Tech stack:** TypeScript host/protocol, Kotlin/Compose Android, Pi public extension and RPC APIs.

**Spec:** Task 6 in `docs/plans/2026-10-31-remote-editor-dialogs.md`; contract evidence `/private/tmp/paged-editor-contract-audit.md` (read before execution). Host paths below are relative to `/private/tmp/pi-remote-environment/packages/pi-atlas/extensions/remote`; App paths are relative to this checkout.

## Global constraints and decisions

- No Pi core patches. Preserve existing 16,384-byte inline prefill, 65,536-byte direct answer, confirmation details, and old v2/v3 projections. A paged editor is `local_required` unless this connection opted in and its exact owner path is qualified. Apply projection to events, snapshot, replay and local views, preserving delivery guards.
- Source is exactly the editor prefill, never a confirmation message; inline `prefill` and `editorSource` are mutually exclusive. Empty prefill stays inline. Validate Unicode scalars and allow only LF and tab among C0 controls; preserve bytes without normalization or truncation.
- Both prefill and final value maximum are 1,048,576 UTF-8 bytes; page/chunk raw maximum 49,152 bytes; canonical unpadded base64url. Existing 256 KiB serialized plaintext and 512 KiB frame limits apply to the entire envelope. Test escaping-heavy text and serialized envelopes, not just raw lengths.
- Existing immutable document limits remain 32 documents and 8 MiB aggregate. Uploads: at most 32 active and 8 MiB declared aggregate per host, one active upload per connection/question, expire after 60 seconds of inactivity; reserve declared bytes at begin and release on every terminal path. Zero-byte upload reserves a slot. Reject capacity failures without disturbing the pending question or draft.
- Cancellation uses existing `question.answer` cancel, without source proof or upload. Never treat delivery as evidence of human review. No automatic retry after an uncertain owner write.

## Review focus

- Incorrect purpose, descriptor, reused ID or object incarnation must not transfer authority (Tasks 1–3).
- Dropped or reordered send and changed connection/selection must never grant submission (Tasks 2–4).
- Invalid Unicode, escape expansion and exact byte boundaries must neither truncate nor bypass frame limits (Tasks 1, 3–5).
- Cancellation, revocation, owner replacement or timeout during any await must invalidate state and release resources (Tasks 2–5).
- Lost owner acknowledgement must remain outcome unknown, with no second owner write (Tasks 3, 5).

## Wire contract and task dependencies

The opt-in `question_editor_paged.v1` and capability `session.question_editor_paged.v1` are independent of `question_details.v1` and bounded editor opt-in; capability alone never establishes a confirm grant. Add to editor only `editorSource: { purpose: "editor_prefill", id: string, totalBytes: number, sha256: string }`, with 1..1,048,576 bytes and lowercase SHA-256. Page with existing `session.question.details.get`/`question_details` and `detailsId`, after authorizing the editor purpose separately; identifiers bind to the immutable source and current question/owner incarnation. Each connection's grant is issued only after correctly sized sequential pages have actually been sent, with running digest matching descriptor. App separately assembles and verifies before enabling editing.

Upload commands, each carrying `requestId`, `sessionId`, `questionId`: `session.question.editor.upload.begin` carries `sourceId`, `totalBytes` (0..1,048,576), `sha256`; result `editor_upload_begun` carries random `uploadId` and `nextOffset: 0`. `session.question.editor.upload.chunk` carries `uploadId`, `offset`, `data`; result `editor_upload_ack` carries `uploadId`, `nextOffset`. Require exactly min(49,152, remaining) bytes for each nonempty chunk and no chunks for zero length. Accept only an identical retry of the immediately preceding offset/data and return the same acknowledgement; reject all other duplicates, overlaps, gaps, extra data and noncanonical base64url. `session.question.editor.upload.commit` carries `uploadId` and yields success only on owner acknowledgement. Bind every upload to connection/device, session, exact question object, owner incarnation, source descriptor and authority; authorize and revalidate at every await and immediately before the owner write. Atomic commit claim excludes all competing submissions/cancel; if cancellation wins first commit cannot write; if write has begun cancellation cannot claim a known rejection. A timed-out owner write leaves outcome unknown until authoritative close/state; no replay can write again.

Dependency order: Task 1 freezes schemas; Tasks 2, 3 and 4 may then proceed in parallel on disjoint implementation files using the interfaces below, with integration owned by Task 5. Task 3's owner transport gate must pass for each advertised owner path before Task 5 enables it. No partial transport should be advertised.

### Task 1: Protocol and compatibility contract

**Host files:** `protocol/types.ts`, `protocol/parse.ts`, `protocol/README.md`, new `protocol/paged-editor-protocol_test.ts`, shared fixture under `protocol/fixtures/`. **App fixture test:** new `src/test/java/de/joinnoah/pi/remote/PagedEditorWireTest.kt` (the app consumes the checked-in equivalent fixture).

**Produces:** `EditorSource`, `EditorUploadBegin`, `EditorUploadChunk`, `EditorUploadCommit`, `EditorUploadBegun`, `EditorUploadAck`, constants and exact command/result fields above. Existing `Question` editor union adds `editorSource?`; parser rejects both source and prefill, unknown fields, malformed IDs, sizes and hashes. The upload result hash is SHA-256 of the final UTF-8 bytes including empty input.

- [ ] Write negative and round-trip fixture tests for both opt-ins, old peers, 16,384/16,385 source, 65,536/65,537 answer, 1 MiB/1 MiB+1, empty answer, malformed scalar/control, split multibyte, canonical encoding and serialized maximum envelope; run the focused host and App test targets and record RED.
- [ ] Define and validate the exact schema above without weakening existing answer/parser bounds. Run the same targets to GREEN and review the fixture against both implementations.

### Task 2: Source projection and per-connection proof

**Host files:** `host/question-details-projection.ts`, `question-details.ts`, `host/daemon.ts`, `host/sessions.ts` source/read and projection sections; tests `question-details_test.ts`, `host/question-details-projection_test.ts`, `host/question-details-sessions_integration_test.ts`, `host/question-details-daemon_integration_test.ts`.

**Consumes:** Task 1 contract. **Produces:** `editorPrefillDelivered(connection, sessionId, exactQuestion, editorSource): boolean` (connection-scoped predicate), and an immutable editor-prefill document registered against the exact owner and question. Integrate through existing delivery-ticket `onSent` mechanism; only this predicate authorizes paged editor upload. Do not use confirm details grant.

- [ ] Add failing tests for correct prefill and descriptor, incorrect purpose/source/question/owner, reused IDs, wrong page size/order, dropped or reordered outbox send, digest mismatch, reconnect and revocation. Cover event, snapshot, replay and legacy `local_required`. Run focused host tests RED.
- [ ] Implement source acquisition, quota checks, typed registration and per-connection sent-digest grant; revalidate ownership and access around each source read and send. Invalid, unsupported or over-budget source remains local-only. Run focused tests GREEN.

### Task 3: Upload state and original-owner delivery

**Host files:** new `host/editor-upload.ts` and `host/editor-upload_test.ts`; `host/sessions.ts` answer/owner sections, `host/daemon.ts` command dispatch; `native-editor.ts` and RPC answer integration as needed; focused new host integration tests. Coordinate edits to `sessions.ts`/`daemon.ts` with Task 2 at integration, not concurrently in the same files.

**Consumes:** Task 1 types and Task 2 predicate. **Produces:** upload store `begin(binding, length, sha256) -> {uploadId, nextOffset}`, `chunk(binding, uploadId, offset, data) -> {uploadId, nextOffset}`, `commit(binding, uploadId) -> Promise<owner acknowledgement>`; binding includes actual connection/device, session, question object, owner incarnation, source descriptor and authority. All errors preserve the pending question unless authoritative close occurs.

- [ ] Test bounds/quotas, expiry, retry acknowledgement, altered duplicate, wrong offset, cross-device/question/owner, hash mismatch, invalid UTF-8/scalars/controls and concurrent commit/cancel. Test replacement, disconnect, close, access loss and timeout at every await, including cleanup and no stale owner write. Verify RED before implementing.
- [ ] Implement sequential bounded upload, exact length/hash validation, atomic settlement and cleanup. Route only a validated complete value to the current original owner, with immediate authority recheck; preserve owner refusal and `outcome_unknown` semantics. Test lost acknowledgement never automatically resends.
- [ ] **Owner transport implementation gate:** for native and RPC independently, implement and qualify a public-API route that reconstructs or transfers the complete value at the owner and receives explicit acknowledgement. Test exact unchanged 1 MiB and edited >64 KiB/1 MiB, escaping-heavy and multibyte, with exactly one delivery to the original owner. Account for serialized local JSON and SDK/RPC limits at every hop; do not assume the 1 MiB local serialized limit carries 1 MiB text. If a public path cannot satisfy this, leave that path unadvertised/local-only and report the concrete barrier; do not silently cap, patch Pi core, substitute read-only editing, or claim the full goal. Run focused tests GREEN for each qualified path.

### Task 4: Android source, draft and upload

**App files:** `src/main/java/de/joinnoah/pi/remote/QuestionDetails.kt`, `QuestionEditor.kt`, `QuestionCard.kt`, `DefaultRemoteRepository.kt`, resource strings in English and German; tests `QuestionDetailsTest.kt`, `QuestionCardUiTest.kt`, `RemoteRepositoryTest.kt`.

**Consumes:** Task 1 wire contract. **Produces:** a distinct editor-prefill assembly/grant scoped to connection generation, selection and question identity, and sequential begin/chunk/commit client. Existing confirm authority remains separate.

- [ ] Test full strict-UTF-8/hash verification before edit/send; split multibyte pages, wrong IDs/hash/size/purpose, selection/reconnect/question replacement, no grant reuse, retry acknowledgements and invalid offsets. Test empty and 1 MiB final text, 1 MiB+1 visible submission prevention without deleting draft, permitted controls and cancellation before download. Run focused Android tests RED.
- [ ] Reuse page assembly without conflating grants; key draft to the current incarnation. Upload one chunk only after previous acknowledgement and revalidate authority after each request. Preserve draft on recoverable upload error; clear authority and resources on lifecycle changes. Surface outcome unknown and reconcile authoritative question state without automatic resubmission. Run focused tests GREEN.

### Task 5: Integration and qualification

**Files:** integration tests adjacent to host and App existing tests; protocol documentation if qualification changes advertised support. Integrate Tasks 1–4 and resolve shared-file edits serially.

- [ ] First add end-to-end tests for each advertised native/RPC owner: exact unchanged 1 MiB, edited >64 KiB and 1 MiB including escape-heavy and multibyte data; explicit cancel, owner rejection, unknown outcome, two-client race, source replacement before owner write, capacity exhaustion and legacy fallback. Verify failures before enabling capability.
- [ ] Enable the per-connection capability only for the owner paths passing the Task 3 transport gate and the end-to-end tests. Run focused protocol, host, App/UI tests and appropriate existing regression/type/format checks; independently review lifetime, single settlement and all bounds. Record actual Pi/device evidence separately from local tests and explicitly report unsupported paths and limits. No production or device qualification is implied by this plan.
