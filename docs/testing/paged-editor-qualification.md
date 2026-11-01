# Paged editor foundation qualification

Status: **foundation only; the paged capability is not advertised and uploads are not enabled**. This is progress toward the complete 1 MiB editor, not a working large-editor UI or production owner path. See the [binding contract](../plans/2026-11-01-paged-editor-contract.md) and [implementation plan](../plans/2026-11-01-paged-editor-dialogs.md).

## Public SDK feasibility

The actual compiled Pi 1.4.0 executable and installed SDK 1.4.0 were used with private offline-configured homes/projects and no model/tool commands:

- Direct RPC editor: unchanged ASCII, escape-heavy and multibyte values of exactly 1,048,576 UTF-8 bytes, and an empty final value, returned with exact hashes in one process/session. This demonstrates SDK stdin/stdout capacity; `extension_ui_response` has no production acceptance ACK. Private report: `/private/tmp/paged-rpc-sdk-probe-report.md`.
- Source-owned prototype, explicitly verified `tui` and `rpc` contexts: a public `ui.editor` deferred promise received an edited 1 MiB value and empty value from real LocalPeer begin/chunk/commit messages, with canonical base64url chunks of at most 49,152 raw bytes. The actual producer returned exact byte count/hash once and the actual commit reply was checked. Proofs: `/private/tmp/paged-native-sdk-mode-proof.json` and `/private/tmp/paged-rpc-source-owner-sdk-proof.json`.
- Those prototype assemblers are not the production receiver, Host authority, delivery proof, upload settlement or Android implementation. Their success does not authorize advertisement. A first prototype attempt failed at CJS resolution of the ESM-only SDK before creating resources; using its public ESM export resolved this setup issue. Child exit and temporary-directory removal were verified for successful context-mode probes.

The chosen qualified RPC design will own its deferred editor and question ID in the public Remote extension and use an explicit LocalPeer commit ACK. It avoids guessing which built-in RPC request matches a title, FIFO position or implementation-specific stdout marker. Old/unqualified paths retain bounded behavior.

## Implemented foundation

The wire schema includes distinct editor-source metadata, an inline/paged upload binding (including empty inline prefill), bounded begin/chunk/commit commands and scoped result validators. The Host and Android use byte-identical fixture `paged-editor-wire.json`, SHA-256 `85b28cd341898fe70d9fdc7806e44c3a1b1ed4b8abb7acc48c55d5aee62b7b8f`.

The grant amendment requires a Host-issued `editorUploadGrantId` captured with the displayed question and a mandatory matching `grantId` at upload begin. It distinguishes byte-identical replacements with the same question ID. Production minting, actual-send activation, invalidation and UI capture remain unimplemented. Source-supplied tokens never establish authority.

The unfinished path fails closed: questions carrying editorSource or upload-grant metadata project to local_required; direct positive answers to them are rejected while cancellation remains available; upload dispatch returns unsupported. The existing Android editor refuses either editorSource or upload-grant metadata rather than displaying an unsupported editor. No new opt-in or capability is announced yet.

The shared pure value collector validates exact binding identity and connection/question exclusivity, canonical sequential chunks and identical last retry, quotas, inactivity expiry, SHA-256, strict UTF-8 including BOM, scalar/control rules and single finish. It collects bytes only. It does not authorize a caller, settle a question or deliver a value to an owner; the transaction layer must retain quota and outcome receipts through owner finalization.

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

This provides a local Linux-container route for later isolated runtime tests. It is not a Pi/Host, remote external host, Devcontainer or physical-device qualification. No cloud resource was provisioned and no image was downloaded.

## Remaining work

Implement typed source capture/read and actual-send proof for inline and paged data; Host upload transaction lifetime and shared settlement; native and source-owned RPC receiver/ACK integration; Android assembly/draft/upload/outcome handling; and complete production-path end-to-end tests for every advertised owner. Keep the capability off until these pass. The larger remote-development goal, independent remote Linux/Devcontainer parity and physical Samsung qualification remain open.
