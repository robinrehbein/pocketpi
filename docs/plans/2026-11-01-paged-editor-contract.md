# Paged editor contract

This implements the existing full remote-development goal and Task 6 of the remote-editor plan. It is not a claim that an unimplemented path is supported. Keep public Pi APIs, existing ownership boundaries and old bounded editor behavior. Root alone executes tests/runtime; disjoint Terra workers author changes and independent reviews. No personal Host/default ADB, external operations or Pi core patches.

## Scope and negotiation

An original prefill and final value may each contain 0..1,048,576 UTF-8 bytes. Preserve valid Unicode scalars and bytes exactly; only LF and tab are allowed C0 controls. No truncation or implicit normalization. Existing direct prefill and answer limits remain 16,384 and 65,536 bytes. Values above the complete limit or unsupported owners stay local-required, never a misleading read-only editor.

Add `QUESTION_PAGED_EDITOR_CAPABILITY = "session.question_editor_paged.v1"` and `QUESTION_PAGED_EDITOR_OPT_IN = "question_editor_paged.v1"`. Detect its token only in capabilities v3 using a separate parser; do not change existing opt-in return shapes. Host eligibility requires base editor negotiation as well as paged negotiation and both runtime switches. Native questions additionally require native negotiation, source ownership and a current eligible rendered-chat lease. Android opts into base, native and paged independently; none grants confirmation-details authority. Legacy clients retain the bounded path.

The paged capability stays disabled until production owner transport and end-to-end qualification pass for each advertised path. A service-level capability does not make an incapable owner question editable. Project unsupported paged sources to local_required on events, snapshots, replay and local views. Preserve exact question/owner incarnation through defensive clones and delivery guards.

## Two source forms, including empty

The wire editor remains inline with optional `prefill` for 0..16,384 bytes (absent means empty). Larger source uses only `editorSource: {purpose:"editor_prefill", id:string, totalBytes:number, sha256:string}` with 1..1,048,576 bytes and lowercase SHA-256. `editorSource` and `prefill` are mutually exclusive. Confirmation `details` is not an editor source and cannot authorize an upload. New editor source, upload and grant IDs use 1..256 ASCII characters from [A-Za-z0-9_-], including UUID hyphens; never accept path separators. Preserve existing request/session/question and confirmation-details ID rules.

An inline or empty source can also be edited to any final size up to 1 MiB. Its upload binding is therefore explicit and does not require a nonexistent document ID. Begin carries `source: {kind:"inline"}` or `source: {kind:"paged", id:string}`. No extra keys or missing discriminant are accepted. Host binds inline to exact immutable prefill/hash, actual question object and owner incarnation; bind paged to the exact descriptor and immutable source. The client-provided source form is not proof. Each per-connection projected paged-capable editor additionally carries a Host-issued `editorUploadGrantId`; upload begin must carry the captured value as `grantId`. This is required for inline and paged sources, including empty, and prevents an old action attaching to a replacement with the same question ID and contents.

Allocate the opaque grant ID per actual connection and exact question/owner/source incarnation; keep it stable for repeated snapshots of the same incarnation, but generate a fresh one even for byte-identical replacement. The token alone is not authority. Invalidate it on close, replacement, revocation, capability loss/regrant or connection change. Source-supplied tokens cannot authorize anything; only the Host mapping is authoritative. If owner identity changes without changed question bytes, invalidate the old token and ensure a fresh projected snapshot/event can reach the client. Android captures the token with the displayed question, draft and action, never re-resolves it by question ID.

Inline full-delivery proof is recorded only after the guarded question containing the complete exact prefill actually sends on that connection, including snapshot/replay delivery. Paged proof uses sequential correctly sized pages, an onSent ticket for each page and matching final digest. Never grant on enqueue, read completion, capability or a client assertion. Revalidate source identity before each read/send, begin/chunk/commit and final owner write. Grants from confirm, another question, owner, connection or incarnation cannot transfer. Cancellation does not need a source proof. Delivery is not human-review evidence.

Reuse session.question.details.get / question_details only with purpose-discriminated owner lookup, read permission and delivery grants. Native and source-owned RPC sources both need their own actual owner read; do not require confirmation capability or assume a native peer for built-in RPC data. Existing confirm rules remain unchanged. Immutable document limits remain 32 documents and 8 MiB aggregate; all state also has bounded lifetime and cleanup.

## Upload wire and state

Constants: MAX_EDITOR_SOURCE_BYTES and MAX_EDITOR_UPLOAD_BYTES are 1,048,576; EDITOR_UPLOAD_CHUNK_BYTES is 49,152. Each command carries requestId/sessionId/questionId.

- session.question.editor.upload.begin: mandatory grantId (same new-ID grammar), source as above, totalBytes 0..1,048,576, sha256 lowercase SHA-256 of final UTF-8 bytes (including empty).
- Result editor_upload_begun: sessionId, questionId, random uploadId, nextOffset exactly 0.
- session.question.editor.upload.chunk: uploadId, offset, data (canonical unpadded base64url, at most 49,152 decoded bytes). Require exactly min(chunk limit, remaining) bytes at the stateful layer; empty final has no chunks. Reject gaps, overlaps, extra bytes, differing retries and noncanonical encoding. Only the identical immediately previous offset/data can replay its acknowledgement.
- Result editor_upload_ack: sessionId, questionId, uploadId, nextOffset (bounded integer).
- session.question.editor.upload.commit: uploadId. Return existing accepted only after a real original-owner commit acknowledgement; preserve existing error/outcome_unknown representation.

Every complete JSON envelope remains below 256 KiB plaintext and 512 KiB frame limits. Uploads: at most 32 active, 8 MiB declared bytes aggregate per Host, one per connection/question; expire after 60 seconds of inactivity. Empty upload consumes a slot. Bind actual connection/device, captured grantId, session, question object, owner epoch, source and authority; reserve at begin and release on all terminal paths. Capacity/recoverable upload failures preserve question and draft. Complete length/hash/strict UTF-8 validation precedes settlement and owner delivery.

For editorSource questions, reject every direct positive question.answer: even a short final value uses upload. Legacy inline bounded views retain direct answers and share one settlement claim with upload commit, cancellation and owner close. A connection receiving a paged-capable view with editorUploadGrantId must use upload for every positive value, including short/empty inline values; its direct positive path must not bypass the captured grant. Cancellation retains its existing proof-independent semantics; they cannot bypass a large source proof because that source is never inline. Collecting an upload does not lock out cancellation or a competing valid answer. Final commit atomically claims the exact question. If cancel wins before write, no owner value is delivered. Once write might have begun, timeout/cancel/lost ACK is not known rejection and never permits automatic resubmission. A real owner ACK may be followed or preceded in the same socket read by that owner's normal question.closed; do not lose an acknowledged receipt just because the question has been removed. Keep exact captured owner/request identity and recheck access without attaching the receipt to a replacement question.

## Original owner transport

For native source-owned questions, use actual bounded begin/chunk/commit IPC with source-side reconstruction and a real commit ACK. Bind transfer to exact current source editor, owner generation, native lease/proof and source form; local fallback/cancel invalidates staged transfers. A large source requires a paged-capable eligible reader at native claim time, not merely a base editor reader. Existing no-reader and raw Ctrl-] fallback semantics remain.

For qualified RPC, use a public Remote-extension ui.editor wrapper owning a deferred promise and its own question ID. It publishes source-owned questions through the registered LocalPeer, including when no reader is present (RPC dialogs are already remote); this keeps the question pending and cancelable. It does not open the built-in RPC editor in this qualified mode, and receives final chunks and acknowledges actual source acceptance over LocalPeer. Existing Sessions peer-question ownership takes precedence over an RPC object's mere presence. No metadata/FIFO/title guessing or implementation-specific stdout marker binding is allowed. Old Host/no bridge acknowledgement delegates to the original bounded RPC path unchanged. Host-owned RPC launches must load the public Remote extension and verify its registration/owner capability before paged advertisement; an unqualified foreign RPC process is not silently treated as capable.

Advertise source support and negotiate a versioned bridge acknowledgement separately from phone opt-ins. Source-owned RPC does not require native UI presence; source-owned native still does. Source commit validates and claims settlement once, reconstructs the exact value, resolves only that editor and sends an explicit matching ACK. Coordinate ACK/normal question close ordering; keep terminal receipts bounded, distinguish empty from cancel, and do not treat stdout/stdin write completion as source acceptance. Source epoch/peer replacement and all stale transfers fail closed. No Pi core patch or second session-file writer.

SDK-only feasibility is not production qualification. Before enablement, exercise actual production source receiver, Host proof/upload/commit and Android for native and RPC: unchanged 1 MiB prefill/final, edited >64 KiB and 1 MiB escape-heavy/multibyte, empty prefill expanded to 1 MiB, empty final, cancel, source replacement, wrong authority, resource exhaustion, duplicate/racing commit and lost ACK. Record exact owner PID/session and outcome. Physical Samsung, independent Linux and broader goal gates remain separate and open.

## Safe staged implementation

Schema support alone must not expose an unfinished editor. During Task 1, keep editorSource questions and unimplemented editorUploadGrantId metadata projected to local_required, reject their direct positive answers (cancel still allowed), and explicitly refuse all new upload commands until the gated implementation exists. Do not allow an unhandled upload commit to fall through to generic accepted. Android bounded rendering also rejects editorSource or editorUploadGrantId until the paged renderer/capability is implemented. These controls are covered by focused negative tests and remain default-deny when new capability is absent.

## Runtime scope clarification, 2026-11-02

The binding product decision in `docs/remote-development-goal.md` remains: native
workers are the one regular session runtime. RPC is only the compatibility
fallback for hosts without node-pty, not an equal product target. Existing private
RPC qualification is retained as historical compatibility evidence; additional
RPC parity or RPC-only acceptance must not block Native-worker delivery. The
both-owner wording below records the earlier qualification scope, not a mandate
to keep expanding two product runtimes. Prioritize remaining Native owner,
authority and Android production-enablement criteria. Future capability wiring
must keep fallback behaviour explicit and must not advertise unqualified new
features merely because global switches were enabled for Native workers.
