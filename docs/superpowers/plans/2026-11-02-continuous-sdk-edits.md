# Continuous genuine SDK edits implementation plan

> **For agentic workers:** Use superpowers:subagent-driven-development to implement the scoped tasks with independent review.

**Goal:** Qualify more than 32 sequential successful SDK editor submissions with durable Android cleanup and exact Host release in both Native and RPC.

**Architecture:** A private fixture runs 40 real `ctx.ui.editor` calls in the same owner/session and gates each next question on completed observations. Android uses the actual Compose editor, repository, encrypted journal and transport; passive observers preserve per-request and per-save evidence.

**Tech Stack:** TypeScript Pi fixture, Kotlin Android instrumentation, owned local emulator, saved-evidence Python verifier.

**Spec:** `docs/plans/2026-11-01-paged-editor-positive-outcomes.md`, acceptance items 4 and durable release retry.

## Global constraints

- Public paged/positive gates remain disabled. No Pi core or production behavior changes.
- Forty distinct successful questions in each mode, same live Host/owner/device/session/ledger and same Android repository/store.
- Each unique 32-byte ASCII value is the actual SDK return, not inferred from an ACK; empty Source prefill must still use the real paged/positive transport.
- Durable bound attempt, correlated accepted result, atomic attempt removal plus exact release enqueue, successful actual release and durable queue removal must be observed per iteration.
- Exact authenticated receipt and original Source ACK must match; zero unrelated answer/replay/expiry/reset. No full-store reset between iterations.
- Root owns Git, builds, emulator and processes; scoped Terra authors may edit disjoint files. No external push/release.

## Review focus

- Singleton/latest-only observations cannot count as forty distinct results.
- Success-path release may race UI polling; record completed encrypted saves synchronously.
- A next-question barrier cannot manufacture completion or bypass real SDK/wire work.
- Native lease identity and RPC owner identity have different evidence; verify each actual mode.
- Forty fresh Hosts do not prove continuity; stable identities and the same ledger are required.

### Task 1: Private Host loop fixture

- [x] Add mutually exclusive loop flag, generated SDK loop, indexed owner/wire/ledger observers, exact status and next-index barrier; preserve existing proof modes.
- [x] Source-format/type check and review actual call graph; root runtime validates actual Native/RPC results.

### Task 2: Android loop driver and durable evidence

- [x] Add opt-in instrumentation using real Compose load/edit/send, passive request/result observer and real encrypted storage save/readback observer.
- [x] Correlate all forty complete tuples and actual SDK values with atomic journal transitions and release acknowledgements; advance only after both sides pass.

### Task 3: Root execution and independent audit

- [x] Build debug and instrumentation APKs and run forty-edit Native and RPC cases on the owned emulator without touching default ADB/personal sessions.
- [x] Independently verify pinned source/APKs/controller and saved raw SDK/wire/ledger/journal evidence, process cleanup and unique counts.
- [x] Record results and limitations, update existing enablement checkpoint and commit locally.

The normal successful accepted path retires the exact attempt directly; no additional outcome query is required for this continuity criterion. Existing query/crash/recovery tests retain their scopes. These 32-byte cycles prove count continuity, not maximum-byte pressure or physical Samsung/DeX/independent Linux qualification. Full environment goal remains active.

## Evidence-driven correction during qualification

Native attempt `native-sdk-loop-01` failed at the 33rd draft save after 32 real
SDK successes and releases; cleanup passed. Completed drafts exhausted the
unchanged 32-draft journal limit because `retireApplied` kept their contents.
The focused regression failed with `Applied draft must be retired in the same
save` before any production correction. The initial unqualified Gradle task also
selected Wear and found no matching test; the explicit App `:testDebugUnitTest`
then established the actual red assertion.

Ruling: remove only a draft exactly equal to the reserved attempt's text and
creation timestamp, in the same serialized durable retirement transaction. Keep
newer/changed drafts, other keys and unknown attempts. No limit increase, expiry,
generic removal or per-iteration store reset. Observe draft readbacks in the new
instrumentation and retain the failed run separately. This fixes continuous use
rather than bypassing the resource gate.

Final local Native40 and RPC40 runs, cleanup and combined independent audit passed; see `docs/testing/continuous-sdk-edits.md`. No full-goal or public-enablement claim.
