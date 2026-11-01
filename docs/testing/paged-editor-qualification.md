# Paged editor foundation qualification

Status: **gated source and Android integration; the public paged capability and production uploads remain disabled**. Native source production and Android repository/UI paths are implemented and tested in their stated scopes. The final original-owner transfer/ACK and end-to-end path remain open. See the [binding contract](../plans/2026-11-01-paged-editor-contract.md) and [implementation plan](../plans/2026-11-01-paged-editor-dialogs.md).

## Public SDK feasibility

The actual compiled Pi 1.4.0 executable and installed SDK 1.4.0 were used with private offline-configured homes/projects and no model/tool commands:

- Direct RPC editor: unchanged ASCII, escape-heavy and multibyte values of exactly 1,048,576 UTF-8 bytes, and an empty final value, returned with exact hashes in one process/session. This demonstrates SDK stdin/stdout capacity; `extension_ui_response` has no production acceptance ACK. Private report: `/private/tmp/paged-rpc-sdk-probe-report.md`.
- Source-owned prototype, explicitly verified `tui` and `rpc` contexts: a public `ui.editor` deferred promise received an edited 1 MiB value and empty value from real LocalPeer begin/chunk/commit messages, with canonical base64url chunks of at most 49,152 raw bytes. The actual producer returned exact byte count/hash once and the actual commit reply was checked. Proofs: `/private/tmp/paged-native-sdk-mode-proof.json` and `/private/tmp/paged-rpc-source-owner-sdk-proof.json`.
- Those prototype assemblers are not the production receiver, Host authority, delivery proof, upload settlement or Android implementation. Their success does not authorize advertisement. A first prototype attempt failed at CJS resolution of the ESM-only SDK before creating resources; using its public ESM export resolved this setup issue. Child exit and temporary-directory removal were verified for successful context-mode probes.

The chosen qualified RPC design will own its deferred editor and question ID in the public Remote extension and use an explicit LocalPeer commit ACK. It avoids guessing which built-in RPC request matches a title, FIFO position or implementation-specific stdout marker. Old/unqualified paths retain bounded behavior.

## Implemented foundation

The wire schema includes distinct editor-source metadata, an inline/paged upload binding (including empty inline prefill), bounded begin/chunk/commit commands and scoped result validators. The Host and Android use byte-identical fixture `paged-editor-wire.json`, SHA-256 `85b28cd341898fe70d9fdc7806e44c3a1b1ed4b8abb7acc48c55d5aee62b7b8f`.

The grant amendment requires a Host-issued `editorUploadGrantId` captured with the displayed question and a mandatory matching `grantId` at upload begin. It distinguishes byte-identical replacements with the same question ID. Host minting, guarded actual-send activation, scoped invalidation and Android displayed-question capture are now implemented behind disabled gates. Full lifetime/outbox and original-owner end-to-end qualification remain open. Source-supplied tokens never establish authority.

The unfinished path fails closed: questions carrying editorSource or upload-grant metadata project to local_required; direct positive answers to them are rejected while cancellation remains available; upload dispatch returns unsupported. The existing Android editor refuses either editorSource or upload-grant metadata rather than displaying an unsupported editor. No new opt-in or capability is announced yet.

The shared pure value collector validates exact binding identity and connection/question exclusivity, canonical sequential chunks and identical last retry, quotas, inactivity expiry, SHA-256, strict UTF-8 including BOM, scalar/control rules and single finish. It collects bytes only. It does not authorize a caller, settle a question or deliver a value to an owner; the transaction layer must retain quota and outcome receipts through owner finalization.

## Source/proof implementation in progress

A purpose- and exact-incarnation-scoped owner store, connection-local Host grant manager, source-peer paging and guarded event/snapshot/replay projection are implemented behind an internal disabled gate. Root exercised actual encrypted event delivery and sequential owner pages without confirmation negotiation. Direct positive answers from a sent upload-only view are refused; cancellation remains available. Source reads revalidate the actual registered owner before and after awaiting it. A production native source reader is now implemented and covered by factory/LocalPeer tests as described below. This is not an enabled paged editor.

The Android pure source state assembles exact-size sequential pages, validates the complete source, keeps original and draft separate, and captures question object, Host nonce, connection epoch and selection. Seven source-state tests plus nine codec and five details tests passed; independent specification/quality review passed. The App quick-check then passed in 84 seconds: 3,402 App tests and 34 Wear tests with zero failures/errors/skips, including shared Wear/instrumentation/benchmark compilation. The repository and UI integration is described below. The pure state alone does not implement visible draft retention or submission controls.

The Host review found missing equal-metadata owner refresh, direct-answer downgrade after capability loss, and transient authority revival. Tests reproduced these cases before fixes. A later fix review caught invalidation of unrelated sessions, which also received explicit regression coverage. After two fix rounds, the scoped independent review passed for those fixes. The final relevant Host run passed 205 tests across 14 files in 18.08 seconds, TypeScript checking passed, and changed-file formatting/whitespace checks passed. This is evidence for the implemented subset; source production, full lifecycle/outbox qualification and public negotiation remain open. No public capability was enabled.

## Android repository and UI integration

The internal `pagedEditorTestGate` defaults false in production construction. The actual repository fetches and verifies source pages before editing, captures the displayed question object/grant/connection/selection, and sends sequential begin/chunk/commit requests with scoped receipt validation. Composer, screen and ViewModel callbacks carry the displayed question rather than resolving it again by ID. The card preserves invalid/oversized visible text and disables positive submission. No phone opt-in or public capability was enabled.

Uncertain commit receipts are recorded before a transport write that may have reached the Host. They survive connection/selection/capability invalidation and generic snapshots; new grants or open events alone do not clear them. Associated terminal close handling covers applied live and replay events. The UI explains quarantine and disables Send while retaining the proof-independent Cancel path, including capability loss; legacy uncertain blocks do not acquire this exception. A pending upload disables editing/send for its captured value. Old upload finalizers cannot release a newer attempt's busy state.

Draft text is kept separately from authority with referential keys. Another editor can be loaded without losing a retained draft; a draft from an ambiguous/new question object is offered only through explicit restoration after source verification. Retention is bounded to 32 drafts, 8 MiB estimated UTF-16 aggregate and 4 MiB per draft. Unreachable history becomes ineligible for recovery after 15 minutes and is pruned on subsequent load/edit, not by a physical-memory-erasure timer. Oversized or unretainable current text remains exact in the active UI, with an explicit warning that it will not survive leaving/reloading. Active UI/IME storage is outside this retention-cache budget. There are at most 32 unresolved receipts/in-flight reservations; unknown receipts are never evicted to permit another write, and capacity blocks a new begin with an explanation.

Root witnessed the initial typed-API compile RED, then behavioral RED for uncertain lifecycle, draft retention, pending UI, stale busy ownership, terminal replay, visible quarantine, resource limits and proof-independent cancellation. The final targeted run passed 28 tests with zero failures/errors/skips. After three scoped review fix rounds, specification and quality passed for that reviewed subset. Final `scripts/quick-check.sh` passed in 90 seconds: 3,430 App and 34 Wear tests with zero failures/errors/skips, plus shared instrumentation and benchmark compilation and resource parity. Logs: `/private/tmp/pocketpi-paged-repository-cancel-green.log`, `pocketpi-paged-repository-final-quick-check.log`; review: `paged-android-repository-review.md`.

Late ACK after request/selection invalidation currently leaves conservative quarantine until a matching terminal outcome; durable process-restart receipt recovery is not qualified. Comprehensive malformed reply/multi-page boundary testing, actual production owner/Host/Android end-to-end tests, emulator/device performance and public enablement remain open. These checks do not prove completed Task 4 or full 1 MiB owner delivery.

## Native source producer

The actual Remote extension now captures a native editor prefill into its exact-entry scoped store and serves purpose-specific pages. The source validates scalar/control/byte limits before allocation, preserves the original local fallback arguments, and removes the source on fallback, cancellation, close, host loss or disposal. Large sources require explicit versioned registration/claim acknowledgements and a currently paged-capable reader through native lease claim, proof and coverage. A Host that ignores the version cannot accidentally qualify a large source. No public paged capability was enabled.

The independent review required confirmed lease release before local fallback after an inconsistent unversioned successful claim. The new large-source path now rejects with a controlled error if release is refused, missing, failed or timed out. An attempted paged claim with an unknown result after transport loss, timeout or Host loss also rejects instead of opening locally. Explicit refusal, no registration acknowledgement and existing bounded paths retain their local fallback behavior. Late leases receive a cleanup attempt; an attempted cleanup is not claimed as confirmed release.

Central initial RED: seven failures, 26 passes. Initial source/lease regression GREEN: 127 tests in 14 files. Factory/actual Unix LocalPeer and Host integration GREEN: 75 passes and six existing skips. Review fixes were reproduced with unit and real LocalPeer disconnect/refused-release failures; after correcting a duplicate fixture variable, a misplaced fixture ACK and a TypeScript callback, final covering GREEN was 85 passes and six existing skips across four files with no unhandled errors. Additional final native/source/details/local-IPC regression GREEN: 226 tests in 15 files, 18.18 seconds. TypeScript and changed-file formatting passed; the scoped independent review passed after two fix rounds.

Private logs: `/private/tmp/pocketpi-native-source-claim-error-green.log`, `pocketpi-native-source-final-regressions.log`, `pocketpi-native-source-final-types.log`, `pocketpi-native-source-final-biome.log`; review: `paged-native-source-review.md`. Skipped tests are not passing evidence. The final native/RPC value receiver, shared upload settlement and actual owner acknowledgement remain unimplemented; these source-read tests are not final-value end-to-end qualification.

## Verification

These completed results precede the grant amendment unless explicitly identified. Its central RED produced four expected Host failures (24 passed), and Android compilation failed on the missing grant API. Android grant GREEN: nine tests, zero failures/errors/skips; scoped independent review passed. Host grant GREEN: 28 tests in four files; TypeScript check and scoped independent review passed. The first GREEN attempt found two stale valid test inputs missing the new grant; correcting the inputs preserved the missing-grant negative case and resolved the accompanying test type error.

- Initial Host RED: seven failures before protocol/default-deny implementation; focused GREEN: 25 tests in four files.
- Initial Android RED: missing codec API; codec GREEN: eight tests. Repository/editor regression run: 539 tests, zero failures/errors/skips.
- Collector initial RED: missing module prevented test discovery. After implementation, eight collector tests passed. Type checking caught an unsupported parameter property under erasableSyntaxOnly; explicit field assignment fixed it, and the collector tests and type check passed afterward.
- Relevant protocol/dialog/native/collector regression run: 855 passed and six existing skipped tests across 30 files in 31.44 seconds. Skips are not passing evidence. Independent Host, Android codec and collector reviews reported no findings for their scopes.

Private logs: `/private/tmp/pocketpi-paged-protocol-green.log`, `pocketpi-paged-android-regressions.log`, `pocketpi-paged-foundation-regressions.log`, `pocketpi-editor-value-buffer-green.log` and `pocketpi-paged-types3.log`. No production, physical Samsung or real CI qualification follows from these checks.

The full pre-amendment App quick-check passed in 113 seconds, including App/Wear tests and source-set builds. The grant amendment was then compiled and covered by its nine focused App tests. Final changed TypeScript formatting and whitespace checks passed.

## Linux test route

Read-only inventory found an already running local Docker Desktop Linux/aarch64 daemon and cached official Node 24.18.0 ARM64 image. A single owned container verified actual Linux/arm64, Node v24.18.0 and Linux kernel `7.0.12-linuxkit`, with no network, host mounts or additional capabilities and a read-only root. It was automatically removed and its absence was confirmed by Docker's no-such-object response. No existing container, image, context or daemon configuration was changed. Proof: `/private/tmp/pocketpi-linux-node-probe.json`.

A subsequent actual production Host/Pi RPC session passed in the cached full Node image; see [Linux container qualification](linux-container-qualification.md). This provides a local Linux-container runtime test route. This does not establish remote external-host, Devcontainer or physical-device qualification. No cloud resource was provisioned and no image was downloaded.

## Remaining work

Complete qualified RPC source production and remaining actual-send/lifecycle qualification for inline and paged data; Host upload transaction lifetime and shared settlement; native and source-owned RPC final-value receiver/ACK integration; remaining Android outcome/restart and boundary qualification; and complete production-path end-to-end tests for every advertised owner. Keep the capability off until these pass. The larger remote-development goal, independent remote Linux/Devcontainer parity and physical Samsung qualification remain open.
