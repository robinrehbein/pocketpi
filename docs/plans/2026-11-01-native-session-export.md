# Native session snapshot export

> **For agentic workers:** Use superpowers:subagent-driven-development. Root executes all tests/builds/runtime; Terra authors work on disjoint Source, Host and Android paths.

**Goal:** Export an owned native Pi worker's complete captured session through the existing paginated HTML/share workflow using public Pi APIs.

**Architecture:** The owning Remote extension synchronously captures its public ReadonlySessionManager header, all entries and active leaf. A self-contained PocketPi HTML snapshot retains the entire tree and raw JSON, defaults to the captured active branch, and presents message/tool/unknown entries without executing transcript content. The source writes only the Host's reserved artifact path. Host reuses ExportStore, pagination, limits and scoped authorization. RPC export behavior stays compatible.

**Tech stack:** Pi 1.4 public extension APIs, existing LocalPeer/ExportStore protocol, TypeScript and Kotlin/Compose.

**Spec:** docs/remote-development-goal.md native-worker feature parity. Exact Pi theme/custom-renderer HTML is not a stated goal; this snapshot preserves complete public session entries and branch selection. No SDK internal imports, file-session reopening, second writer or Pi core patch.

## Contract

- Public capability `session.export.native.v1` (`NATIVE_EXPORT_CAPABILITY`) plus per-session `nativeExport: true` is required for native UI/repository entry. Existing RPC `session.export.v1` remains unchanged. Old Hosts and old/unqualified sources must not expose native export.
- Source capability `session.export.native.source.v1` (`NATIVE_EXPORT_SOURCE_CAPABILITY`) qualifies a captured, currently Host-owned native peer. Unrelated terminal sessions cannot qualify merely by TUI origin.
- Internal request `session.export.native` has `v:1`, sessionId, exportId and Host-generated outputPath. Receipt has `v:1`, kind `native.export`, sessionId, exportId, outputPath and captured leafId. Validate captured identity/path/receipt before Store commit.
- Snapshot JSON has format `pocketpi.native.snapshot.v1`, capturedAt, sessionId, header, entries and leafId. Preserve every entry and the full branch tree; mark the active leaf. A null leaf/empty session is valid. Capture is synchronous and immutable; it represents entries committed at capture, including when a turn is active.
- HTML exposes snapshot JSON in a non-executable `script` with id `session-data`; escape transcript content including closing-script sequences. Trusted viewer code uses textContent; no external network resources or transcript HTML execution. Complete JSON remains available for unknown/custom entries and branch selection. Preserve tool arguments/results and message text rather than dropping unsupported types.
- Existing 20 MiB artifact maximum, 49,152-byte chunks, digest, per-device/session/project scope, timeout/busy and cleanup apply. Bound serialization/escaping before large allocations; reject invalid/cyclic input and oversized output with controlled errors.
- Source never creates the reservation directory or opens an existing output file; exclusive private file creation, owner/trust checks before capture and after async write, cleanup of its own abandoned file. Host captures exact peer/runtime/session identity and rechecks access after awaits; no retargeting to replacement owner or replay.

## Review focus

1. Active leaf differs from disk tail: full tree retained and the live selection preserved without opening/writing the session file.
2. Legacy Host/source/terminal and replacement owner: unsupported/hidden, never RPC downgrade or arbitrary native eligibility.
3. Revocation, /new, timeout and late render: no stale delivery, single slot and reservation cleanup.
4. HTML/script/URL injection and huge/custom data: faithful inert data, no unexpected network and bounded artifacts.
5. App share: existing strict length/hash/UTF-8 checks and private FileProvider behavior remain in force.

## Tasks

- [x] Source tests first; root observes RED, then implement capture/render/internal handler and source capability using public APIs.
- [x] Host tests first; root observes native render/eligibility RED, then exact-owner routing, receipt validation, public capability/per-session projection and existing Store reuse.
- [x] Android tests first; root observes native gate RED, then capability+session metadata gates and unchanged download/share route.
- [x] Private real Pi/Host/Android proof: use public command-context tree navigation to make active leaf differ from last entry, retain a large inactive custom entry for multi-chunk proof, invoke Export via UI and inspect the intercepted share URI/HTML snapshot against actual source state.
- [x] Root targeted checks and required broader checks; independent source/host/app/runtime review, precise qualification and local commits. No personal Host, external model/service, release or physical-device claim.

## Result

Completed locally. Independent final review found no concrete remaining blockers. See [qualification](../testing/native-export-qualification.md) for the passing checks and runtime boundaries. The overall remote development goal remains active.
