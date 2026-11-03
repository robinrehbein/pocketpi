# Remote Editor Dialogs Implementation Plan

> **Execution:** Root coordinates implementation and runs all tests and builds. Use Terra high for authority changes and review, Terra medium for UI and review; at most four concurrent agents including root. Follow tests-first cycles and independent review. No further approval stop is required for this authorized implementation.

**Goal:** Support bounded multiline Pi RPC editor dialogs in PocketPi without losing prefill or admitting stale answers.

**Architecture:** Negotiate editor support explicitly per client connection and project editor questions to `local_required` for legacy clients. The host retains the RPC process as sole answer owner; Android renders and validates an editor only for negotiated questions. Native remote-first handling and oversized/paged prefill are separate feasibility gates, not claims of support in this slice.

**Tech stack:** Pi 1.4.0 public extension/RPC APIs, TypeScript host and protocol, Kotlin/Compose Android.

**Reference:** Installed Pi SDK `dist/modes/rpc/rpc-types.d.ts:427–432,463–475`, `rpc-mode.js:174–192,612–622`, and `dist/core/extensions/types.d.ts:138–139`. Existing protocol limits are `MAX_EDITOR_PREFILL_BYTES = 16384` and `MAX_EDITOR_ANSWER_BYTES = 65536`.

## Constraints and review focus

- Preserve exact prefill, including empty text, Unicode, whitespace and LF; never truncate a document to make it eligible. Validate strict UTF-8 byte lengths and reject malformed Unicode; editor answers allow LF and tab but reject every other C0 control, including CR and ESC.
- Bind every answer to the current connection, selection/session, exact pending question object and original RPC source; enforce existing trust, grant, single-settlement and close/timeout rules. Cancel remains available without submitting text.
- Preserve existing question-details opt-in and v2/v3 behavior. Only explicitly opted-in clients receive editor shape; project broadcast, snapshot, replay/resume and local views consistently with original delivery guards. No editor advertisement before all paths pass.
- Use public Pi APIs only; do not patch Pi core. No cloud, production, owner-state, deployment, push or other external modifications.
- Focus negative tests on malformed/oversized UTF-8, stale or replaced questions, source/connection changes, duplicate settlement, and clients without editor support.

## Files and tasks

Host paths below are relative to `packages/pi-atlas/extensions/remote` in the host checkout; Android paths are relative to the app checkout.

### 1. Negotiation and projection

**Modify:** host `protocol/types.ts`, `protocol/parse.ts`, `protocol/README.md`, `host/question-details-projection.ts`, `host/daemon.ts`; **test:** `protocol/protocol_test.ts`, `host/daemon_test.ts` and projection tests. **Android:** `src/main/java/de/joinnoah/pi/remote/DefaultRemoteRepository.kt`; test `src/test/java/de/joinnoah/pi/remote/RemoteRepositoryTest.kt`.

- [x] Add failing tests for simultaneous editor and existing details opt-ins, legacy v2/v3 parsing, no implicit editor grant, and projection of editor to same-ID `local_required` across open, snapshot and replay. Preserve delivery guards and existing capability limits.
- [x] Root runs focused tests and records the expected failures. Define a backward-compatible v3 opt-in representation for both features, retaining existing single-feature parsers; add an explicit editor capability and connection-scoped negotiated flag. Ensure malformed or unsupported opt-ins never grant it.
- [x] Root reruns focused tests; verify opted-in editor delivery and legacy projection, including reconnect and revocation. Keep advertisement disabled until Tasks 2–3 are qualified.

### 2. RPC source and answer ownership

**Modify:** host `host/sessions.ts`; **test:** `host/sessions_test.ts`, `host/daemon_test.ts`, and a focused RPC integration test alongside them.

- [x] Write failing tests: RPC `{type: "extension_ui_request", id, method: "editor", title, prefill}` retains prefill exactly; an editor answer writes one `{type: "extension_ui_response", id, value}` and cancel writes `{type: "extension_ui_response", id, cancelled: true}`. Include empty, Unicode and newline text.
- [x] Add negative tests for oversized/malformed prefill, invalid control or oversized answer, stale question replacement, source/connection change, close and duplicate answer. Unsupported prefill must stay local-only and never be truncated into remote authority.
- [x] Root runs tests for RED. Map RPC editor to its actual editor question and prefill, route the matching value/cancel to its exact RPC owner, and recheck current source and question immediately before the owner write; retain existing first-settlement lifecycle. Enforce protocol byte and control limits.
- [x] Root reruns focused host tests and relevant protocol/daemon integration checks. Do not infer native support from RPC success.

### 3. Android editor UI

**Modify:** `src/main/java/de/joinnoah/pi/remote/QuestionCard.kt`, `DefaultRemoteRepository.kt`, `src/main/res/values/strings.xml`, `src/main/res/values-de/strings.xml`; **test:** `src/test/java/de/joinnoah/pi/remote/QuestionCardUiTest.kt`, `RemoteRepositoryTest.kt`.

- [x] Write failing UI and repository tests for initialized multiline prefill, exact whitespace/newline submission, explicit Cancel, UTF-8 byte boundaries and prohibited controls, and clearing draft on actual question replacement even when an ID is reused. Test stale selection/connection/question cannot send.
- [x] Root verifies RED. Add a bounded multiline field and localized Send/Cancel; key draft ownership to the actual question and connection/selection, not ID alone. Validate text as UTF-8 bytes before enabling or sending, without silent truncation. Send `kind: editor` and preserve cancel semantics.
- [x] Root runs the focused Android tests and integration checks, then enables editor advertisement only after Tasks 1–3 and independent review pass. Report local tests separately from device qualification.

### 4. Actual Pi-to-Android qualification

- [x] Extend the private actual-Pi fixture with a public command that invokes `ctx.ui.editor` without a model turn or tool execution. Keep pairing data private and use only the owned emulator, isolated ADB server and disposable Host state.
- [x] Prove exact multiline Unicode prefill and submitted value through the actual Pi RPC process, encrypted transport, repository and rendered UI, including a separate explicit Cancel outcome. Cover phone/English and tablet/German layouts; inspect screenshots and distinguish an external RPC process from a Host-managed fallback.
- [x] Run the relevant regression suites, type and format checks, complete independent reviews, and enable only the qualified bounded capability. Record failed attempts and remaining physical-device/Linux boundaries. Clean up all owned runtime resources and credentials.

### 5. Native feasibility gate, separate from RPC completion

**Inspect first:** host `index.ts`, `dialogs.ts` and their focused tests. Pi's public `editor(title, prefill?)` has no signal or timeout, and an already opened native editor cannot be assumed remotely dismissible.

- [x] Design and test a remote-first deferred wrapper only if its lifetime, local fallback trigger and source authority can be proven with public APIs. Before invoking the original native editor, atomically revoke remote answer authority and prove a concurrent phone answer cannot settle it. Test disconnect, session replacement, disposal and local fallback racing a remote answer.
- [x] The bounded native gate was proven with actual Pi/Android runtime evidence and independent review; see [native qualification](../testing/native-editor-qualification.md). This is not SDK signature inference or oversized-editor qualification.

### 6. Oversized/paged prefill, separate gate

- [ ] Keep oversized editor prefill local-only in the bounded slice. A later change must separately specify full, exact prefill delivery and verification, bounded memory, current source/connection/question ownership, compatibility and failure behavior; implement only after its own tests and review. Existing confirm-details support alone does not establish editor support.


## Current status — 2026-10-31

Tasks 1–4 are implemented and independently reviewed. The bounded RPC editor is enabled by
default with explicit per-connection v3 opt-in; an explicit `questionEditorEnabled: false`
disables it. No native editor bridge is advertised. The full Android/Wear checks, 980 covering
Host tests and four actual Host-owned Pi-to-Android Submit/Cancel runs passed.

Host implementation: `0cfd68d05`; the App implementation is included in this change.
See [qualification](../testing/remote-editor-qualification.md) for exact results and boundaries.
Tasks 5–6 and the full remote-development goal remain open.

Native follow-up: the bounded native bridge is now qualified and enabled by default with per-connection native/base opt-in and explicit rendered-chat presence. The earlier RPC-only qualification below remains historical evidence for that slice. Task 6 remains open.
