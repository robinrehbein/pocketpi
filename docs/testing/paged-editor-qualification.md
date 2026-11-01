# Paged editor foundation qualification

Status: **gated source, Host transaction and Android integration; the public paged capability and production opt-in remain disabled**. A limited set of private production-chain Pi/Host/Android cases has passed as described below; the full qualification remains open. See the [binding contract](../plans/2026-11-01-paged-editor-contract.md) and [implementation plan](../plans/2026-11-01-paged-editor-dialogs.md).

## Current-source private matrix, 2026-11-01

All fourteen current-source private emulator runs pass, including fixture/process cleanup and
independent artifact review. Native and managed RPC each cover max-unchanged, max-edited,
empty-to-1-MiB expansion, empty-final, cancellation and lost acknowledgment; two additional
Native/RPC max-edited cases use the tablet layout. Public paged capability and production opt-in
remain **OFF**. This does not complete the broader owner-replacement/authority/quota/race gates.

Inputs: App `32ce8a5e3b1dcf004c65872f0606646db11b4581` with the diagnostic-only Android fixture
change, Host `d66f848d892eb4a0dc4d16ccf035ad89b83196d5` with only unrelated pending Linux deployment
locks/documentation. The runtime source is unchanged. The same 26 pinned runtime/test files and
the same installed/build APKs were independently checked against all fourteen saved snapshots.
App APK SHA-256: `73c52d7eb77459766f1f2eee5f548db6b49b98e0948b96353ca02eabe4a02a4f`; test APK SHA-256: `e0c6d57ce6489322960aa6da0feba3b1c907a1a27cb1e7fbf9d25eafde3f5afd`.
The fresh test build passes in `/private/tmp/pocketpi-native-editor-current-build.log`; no new
production feature, gate or relay limit was changed.

| Evidence label | Owner / case | Recorded runner duration |
| --- | --- | --- |
| `editor-current-native-cancel-01` | native / cancel | 27.946 s |
| `editor-current-native-edited-01` | native / max-edited | 30.501 s |
| `editor-current-native-empty-expand-01` | native / empty-expand | 18.763 s |
| `editor-current-native-empty-final-01` | native / empty-final | 13.662 s |
| `editor-current-native-lost-ack-01` | native / lost-ack | 29.955 s |
| `editor-current-native-max-unchanged-01` | native / max-unchanged | 27.581 s |
| `editor-current-rpc-cancel-01` | rpc / cancel | 28.412 s |
| `editor-current-rpc-empty-expand-01` | rpc / empty-expand | 18.802 s |
| `editor-current-rpc-empty-final-01` | rpc / empty-final | 14.773 s |
| `editor-current-rpc-lost-ack-01` | rpc / lost-ack | 30.300 s |
| `editor-current-rpc-max-edited-01` | rpc / max-edited | 30.305 s |
| `editor-current-rpc-max-unchanged-01` | rpc / max-unchanged | 26.923 s |
| `editor-current-tablet-native-edited-01` | native / max-edited | 30.040 s |
| `editor-current-tablet-rpc-edited-01` | rpc / max-edited | 30.223 s |

Each 1-MiB source transfer has 22 contiguous owner chunks/ACKs totaling 1,048,576 bytes, one
matching commit receipt and one exact SDK result. Empty-final has zero bytes/chunks and one
settlement. Cancellation has zero owner commits and one undefined SDK result; the pinned test
checks the fresh 1-MiB Host reservation, its release, and a three-second no-replay interval.
Lost-ACK traces show the real valid owner receipt before intentional drop; the test checks the
matching `outcome_unknown` and no replay over three seconds. These timings are bounded cases,
not indefinite no-replay or every-race guarantees. All seven Native runs record zero original
local-editor calls. Both tablet display overrides restore 1080 × 2400 at 420 dpi afterward.

The original ~139-second failure is now localized to the former wait for edited draft equality,
before upload; its logs lack the actual draft/callback state required to identify a cause. The
current max-edited diagnostics observe a matching draft/expected/source result hash and one
edit callback with firstDifference=-1. The attempted numeric window observation reports
`window=unavailable`; the existing navigation-button and bounded-marker assertions still pass.
Do not claim a numerical range observation or a fix for the historical timeout from these passes.

Evidence: `/private/tmp/pocketpi-paged-production.oge2ybh1/editor-current-*` results, source/APK
snapshots, source traces and logs. These are emulator/fixture proofs, not physical Samsung,
external Linux, actual IME, adverse-network, restart or full mutation-matrix qualification.
Historical results below retain their own exact inputs and original bounds.

## Current private production-chain results

The paced max-unchanged case passed on both Native and managed RPC with actual Pi 1.4.0, private Host/relay and Android emulator instrumentation. The clean inputs record App `53aacb75ac533a8a089d210e44592ecd9309bff7` and Host `319c27587508f0a8ac7d256992af4ebc7ce8c5f1`. Each source returned the exact 1,048,576 bytes and matching SHA-256, with 22 sequential owner chunk acknowledgements, one owner commit and Android's matching accepted commit result. Native used the remote owner without calling the original local editor. Instrumentation passed in 25.104 seconds Native and 24.896 seconds RPC. The app and test APK hashes were `9449710f0187b1a274470ba952ea1216d98f7eefee057c10b32463a75d1e5d67` and `bf296d5ceb5ac315fc1baa266804bf1ed9dd3b4fc3206ea680ba8efecd04d6a7`; the installed APKs were pulled and matched the recorded inputs.

Five further private cases passed: Native max-edited, Native and RPC empty-to-1-MiB expansion, and Native and RPC empty-final. Each recorded one source commit, a matching owner ACK and SDK result, and Android's accepted commit result. The three 1-MiB results had 22 sequential owner chunk acknowledgements; empty-final used none. The Native cases recorded matching authority proof and zero original editor calls. These runs used App `8796a2670e8509d2d43ee5c16059f92a3cbac19e` with a dirty diagnostic-only Android fixture and clean Host `ddb00a1c8bea27dfc1ecf6dd72dc880950de54a9`; their app APK was `9449710f0187b1a274470ba952ea1216d98f7eefee057c10b32463a75d1e5d67` and diagnostics-v2 test APK was `27cfa1321de1eeb9c2aa7dad7033f23a31794aeeb802ce6230af81680973e9ae`. They are not runs of the earlier clean test APK. The original Native max-edited attempt failed after approximately 139 seconds; its cause remains unresolved, and the later passes do not establish a product fix.

The same diagnostic fixture also passed Native and RPC cancellation after the first upload chunk while the second was held. It verified a fresh 1-MiB Host reservation before cancellation and zero reservation afterward, an `undefined` SDK return, zero owner commits and no upload replay during a three-second observation. Native and RPC lost-ACK cases each verified one exact 1-MiB SDK result and owner receipt, then deliberately dropped that receipt. Android observed `outcome_unknown` and no upload replay during three seconds. These four runs used the same commits and APK hashes as the five-case diagnostic matrix above; helper cleanup passed. They cover these particular cancellation and lost-ACK timings, not every race or process restart.

Evidence is in `/private/tmp/pocketpi-paged-production.oge2ybh1/` and the independent reviews `/private/tmp/paged-max-production-evidence-review.md` and `/private/tmp/paged-current-matrix-evidence-review.md`. The Android timing covers phone-to-Host collection; the Host trace covers the subsequent Host-to-owner transfer and does not measure relay arrival-window maxima. Real IME, external Linux, physical Samsung, adverse networks and the full matrix remain unqualified. No public capability or production opt-in was enabled. Sections below retain their historical stage and should be read alongside this current status.

## Final owner transfer and Host transaction

The actual Native and source-owned RPC bridges now share a synchronous exact-entry receiver. LocalPeer upload replies contain strict versioned `data.kind` receipts; source capability and explicit Host upload acknowledgement qualify this path. The shared collector reserves at most 32 collecting transfers and 8 MiB declared bytes, with 60-second inactivity cleanup. Separate metadata-only terminal receipts are bounded to 32 entries and 60 seconds. Normal question closure preserves a proven receipt for the captured connection and owner generation; duplicate commit does not settle the SDK promise twice.

The Host separately holds its 32-slot/8-MiB reservation through owner preparation and final delivery, including empty values. Direct answers, cancellation and upload finalization share a settlement claim. A possible owner write is marked before dispatch; ambiguous transport, access or result-delivery failure then becomes `outcome_unknown`, retaining the fence against another write. Collecting transfers are discarded on matching reader/lease revocation while finalizing reservations remain protected. Success validates the exact captured source receipt and transport without requiring a pending question or native lease after normal closure.

Compatibility remains explicit: RPC-only acknowledgement retains the established bounded inline owner, while larger RPC sources require upload acknowledgement. Native paged claims require both paged-source and upload acknowledgements; bounded Native behavior is preserved. Missing upload acknowledgement projects an unqualified paged source as `local_required`.

Central final regression: **152 tests passed across 14 files**, with no skipped tests, in 18.82 seconds. Extension TypeScript checking and changed-file Biome formatting passed. The actual LocalPeer mixed-acknowledgement Native regression also passed in the targeted standard-dialog runtime test; unrelated tests were filtered, not qualified. Initial missing-ACK regressions failed before the compatibility fixes. An independent Terra review passed after corrections to direct-write uncertainty, outbox uncertainty and immediate collecting-quota cleanup. One initial review allegation about uncaught receiver errors was withdrawn after verifying the existing enclosing catch.

Evidence: `/private/tmp/pocketpi-final-transfer-regressions.log`, `/private/tmp/editor-owner-receiver-review.md` and `/private/tmp/editor-host-upload-review.md`. Host integration uses controlled LocalPeer owners and actual phone/relay dispatch, not a real production Pi owner. Native deferred-promise and RPC receiver tests establish their tested implementation scope; they do not replace the forthcoming production Pi/Host/Android qualification. An exact native lease-callback end-to-end quota test, exhaustive boundary matrix and durable process-restart recovery remain unqualified. No new public capability or production opt-in was enabled. Earlier foundation sections below describe their historical stage, including then-unimplemented transfer paths.

## Private production-chain fixture and viewport regression

An additional programmatic `questionPagedEditorFixtureEnabled` gate permits actual v3 capability negotiation only alongside the existing internal gate and explicit base/paged request opt-ins. Successful outbox delivery establishes negotiation; dropped delivery does not. Ten focused negotiation tests passed after two expected initial failures. No CLI, environment or configuration switch enables this test gate.

The fixture uses an actual Pi 1.4.0 owner, private Host/relay and Android instrumentation. A separate private metadata-only trace correlates owner, transport, native registry proof, transfer/chunk sequence, actual source receipt and SDK-result hash; Android independently correlates its actual upload commit result. Static review passed after strengthening these checks. These fixture changes alone are not runtime qualification.

The first Native/Android 1-MiB run exposed a real Compose layout failure: an unbounded editor child exceeded representable constraints. A focused emulator regression reproduced it, then passed after bounding only the paged editor viewport to 240 dp. It verified the full 1-MiB semantic value and exact submit in 42.564 seconds. Evidence: `/private/tmp/pocketpi-paged-production.oge2ybh1/layout-red.log` and `layout-green.log`; review: `/private/tmp/paged-editor-layout-review.md`. This is functional emulator evidence, not performance, distant-position editing, physical Samsung or complete production-chain qualification. The full chain is being rerun separately.

The actual managed RPC/Host/Android **empty-final** chain passed: `draft` was loaded through the production repository, cleared through UI semantics, and submitted as an empty string. The test required the actual accepted Android commit result, matching source ACK and SDK return hash/length, and exactly one source commit. Instrumentation reported `OK (1 test)` in 12.706 seconds; private status confirmed current RPC identity and all receipt/result checks. Evidence: the same private lab's `rpc-empty-instrumentation.log`, `rpc-empty-proof-status.json` and `rpc-empty-paged-trace.jsonl`. The exact fixture was then stopped and temporary device credentials removed. This qualifies that tested empty-final path, not max-size RPC or native completion. A native 1-MiB retry after the layout fix still lost its remote editor before submission; its cause is under timestamped investigation, with no passing native upload claim.

Timestamped investigation measured a 45.392-second main-queue delay and a 47.417-second gap between accepted Android presence messages. The Host closed the same question/lease 30.134 seconds after the last accepted presence, matching its 15-second TTL plus 15-second grace; the source closed and fell back afterward. Explicit reader disposal occurred later. This strongly supports starvation of the Main-based renewal loop; a project-wide `invalidateAll` was not separately observed, so expiry is the best-supported causal inference rather than an exhaustively exclusive trace proof. The lease and presence rules were not relaxed.

The replacement paged field renders at most 2,048 surrogate-safe UTF-16 units, with first/previous/next/last and validated section navigation. The complete draft remains exact, including large paste and invalid values; Send uses that complete draft. Its entire Compose subtree is keyed by referential question identity and authority generation, with commit/disposal guards for callbacks. This also replaces the underlying Compose text-input state when ownership changes, preventing retained framework actions from targeting the replacement editor.

Seven pure window tests and eight emulator UI tests passed. The UI run reported `OK (8 tests)` in 30.695 seconds while Gradle ran concurrently; it is not performance qualification. Existing QuestionCard tests then passed 23/23 after narrowing ambiguous field selectors and using the actual bounded scroll container before button clicks. Their validation, pending, restore and cancellation assertions remain unchanged. Independent helper/UI reviews passed. For synthetic retained old Button semantics only, the test permits the precise detached-Compose-node exception and requires zero side effects plus a working new Load; retained SetText is not caught. Actual IME composition, longer editing sessions, comprehensive recovery races and the complete Native/RPC matrix after this viewport change remain open. Evidence: `window-final-green.log`, `/private/tmp/pocketpi-window-existing-ui-corrected.log`, `/private/tmp/editor-window-helper-review.md` and `/private/tmp/editor-window-ui-review.md`; design: [bounded editor viewport](../plans/2026-11-01-editor-window-viewport.md).

The final window-source `scripts/quick-check.sh` passed in 86 seconds: **3,437 App and 34 Wear tests**, zero failures/errors/skips, plus shared variant, instrumentation, benchmark and resource-parity checks. Log: `/private/tmp/pocketpi-window-final-quick-check2.log`. Its preceding run failed on the obsolete test selectors/viewport described above; that earlier run was not green. No new public capability or production opt-in was enabled by this milestone.

## Relay-aware outbound dispatch

The next real Native run kept presence healthy and rendered the window promptly, but lost its connection during upload. An instrumented repeat confirmed the production relay's rate-limit branch: eleven full chunks were acknowledged; the twelfth, at offset 540,672, was sent 872 ms after the first, and the relay closed with code 1000. The actual close stack identified `server.ts:679`, the unchanged 32-frame/1-MiB-per-second limit. Encryption and two base64url layers make the wire volume larger than the source. No owner commit occurred. Evidence: the private lab's `native-rate-android-timing.log`, `native-rate-paged-trace.jsonl` and `native-rate-final-status.json`. This diagnostic was stopped after causal capture and is not a passing end-to-end test.

The shared transport now accounts for every outbound frame using a conservative local rolling budget of 24 frames/768 KiB per 1,100 ms and a bounded FIFO with reserved ACK/presence capacity. Guarded requests wait before sequence allocation, then validate, enter the possible-write boundary, seal and enqueue the socket frame in one caller-context turn. Pre-dispatch cancellation consumes no sequence or receipt; after possible dispatch, unknown outcomes remain fenced against replay. ACK saturation retains and retries the highest cursor. Connection-scoped requests remain independent of selection changes, while source reads and editor mutations revalidate their exact authority before dispatch. Notification reply timeouts also distinguish pre-dispatch failure from a possible mutation.

The final targeted run passed **87 tests**, including real encrypted rolling-window, full ACK-queue, cancellation, repository and reply cases. After one further statically identified cancellation-signal correction, the full `quick-check.sh` passed in 90 seconds: **3,467 App and 34 Wear tests**, zero failures/errors/skips, plus shared variant checks. Logs: `/private/tmp/pocketpi-outbound-final-regressions.log` and `/private/tmp/pocketpi-pacing-final-quick-check.log`. Independent reviews passed: `/private/tmp/outbound-pacing-model-review.md`, `outbound-transport-review.md` and `outbound-repository-review.md`.

The exact rare fallback-wins/dequeued cancellation interleaving lacks a deterministic test; its fix was statically reviewed and the related regression suite passed. Local enqueue timing cannot prove relay arrival timing under arbitrary network buffering. Actual paced Native/RPC chain, device latency and the remaining mutation matrix are still separate runtime gates. The production relay limits and public paged opt-in remain unchanged. See the [outbound pacing plan](../plans/2026-11-01-relay-outbound-pacing.md).

The paced **max-unchanged** production chain subsequently passed separately for Native and managed RPC on App `53aacb75a` and Host `319c27587`. Android instrumentation reported `OK (1 test)` in **25.104 seconds Native** and **24.896 seconds RPC**. Each original public Pi 1.4.0 editor returned the exact 1,048,576-byte value and SHA-256, with one commit, one actual source receipt and one SDK result. The Native original local editor was called zero times. Both runs required Android's matching accepted commit result; no relay-rate closure occurred during upload. The fixture's later intentional PR reconnect is a different phase.

Evidence in the same private lab: `paced-current-inputs.json`; `native-paced-*` and `rpc-paced-*` instrumentation, final status, paged trace and Android timing records; `paced-installed-apks-proof.json`. Both installed APKs were pulled from the owned emulator and their hashes matched the recorded inputs; copies are retained under `paced-installed-apks/`. Independent evidence review passed: `/private/tmp/paged-max-production-evidence-review.md`.

The Android trace measures the paced phone-to-Host collection, while the Host paged trace measures the subsequent local Host-to-original-owner transfer after phone commit. Their durations are different stages, not a measured relay arrival window. These passes qualify the recorded unchanged max-size cases only. Edited/expanded values, cancellation during collection, lost ACK, real IME, external Linux hosts, physical Samsung devices and arbitrary network behavior remain separately qualified work. Public production opt-in remains disabled.

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

## Duplicate commit cohort

Four additional owned-emulator cases passed with cleanup: `editor-current-native-duplicate-01`, `editor-current-rpc-duplicate-01`, `editor-duplicate-regression-native-edited-01`, and `editor-duplicate-regression-rpc-edited-01`. Their result, instrumentation, Android timing, paged trace and final-status records are in `/private/tmp/pocketpi-paged-production.oge2ybh1/`. Each case recorded 26 source snapshots; an independent audit matched every snapshot and its current source against the recorded SHA-256. The App baseline was `9827fe01398de89e9841fef3d312d512481770c0` with the recorded uncommitted instrumentation change; the Host baseline was `d66f848d892eb4a0dc4d16ccf035ad89b83196d5` with the recorded fixture change. These pins identify the tested source more precisely than the baseline commits alone. The pulled installed App APK SHA-256 was `73c52d7eb77459766f1f2eee5f548db6b49b98e0948b96353ca02eabe4a02a4f` and test APK SHA-256 was `32720d614b5e8e98395d41eac0b6463f6bcf9c30bd4728e32653fabcb5459cb1`; saved copies matched their recorded hashes across all four cases. Type checking and the fresh Android test APK build are recorded in `/private/tmp/pocketpi-paged-duplicate-types.log` and `/private/tmp/pocketpi-paged-duplicate-build.log`.

In each duplicate case the Android trace records three commit requests: the first was accepted and the other two returned `not_found`. The passing test asserts distinct request IDs and that the second phone send completed successfully while the first owner commit was still held, before the gate was released. The Host trace records one valid commit and ACK for 1,048,576 bytes with SHA-256 `84ec34b605d28aeb6752c0f3753cefad3378cd459b6e6a060f04d93df8a52019`; final status records one owner commit and verified SDK result. The same-peer Host queue serializes processing, so the phone-side overlap does not establish simultaneous Host decoding. Both edited regression cases likewise recorded one valid owner commit and ACK, with 1,048,576 bytes and SHA-256 `900dce5cc5d1bc1b5f209c09f600c445e85149e116e2b87542913b07d06f1763`, and a verified SDK result.

This cohort covers the recorded private Native and RPC scenarios; it does not complete the security or lifecycle matrix, qualify physical devices, or enable public paged-editor flags.
