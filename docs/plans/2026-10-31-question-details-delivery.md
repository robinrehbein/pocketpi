# Complete question details delivery implementation plan

> **For agentic workers:** Use subagent-driven development for independent source tasks; root owns all runtime qualification.

**Goal:** Let a phone safely answer an oversized confirm/approval after this exact connection has received the complete immutable document and Android has verified its SHA-256.

**Architecture:** Reuse the existing question_details.v1 contract, with source-owned bounded memory documents and sequential pages. A per-connection delivery tracker advances only after outbox onSent and binds the source incarnation, exact question object and details descriptor. Android verifies and displays the full text before allowing Yes; the Host independently checks delivery before forwarding Yes. No/cancel stay available. No new capability is advertised until the complete path exists.

**Tech stack:** Existing TypeScript Remote/Node crypto, encrypted Host outbox, Kotlin repository/Compose.

**Spec:** Existing protocol/README.md question-details contract and remote-development-goal.md Decisions/criterion 4a. User has authorized implementation and parallel Terra agents; no new approval pause.

## Global constraints

- Full document 1..1048576 UTF-8 bytes; raw page <=49152 bytes, unpadded base64url; lowercase SHA-256.
- Never authorize from a truncated document or client-supplied verification boolean. Over-limit/unavailable complete source stays local-only.
- Connection, question/source incarnation, question close, revocation and reconnect invalidate delivery authority. No model turn, owner configuration, external mutation or Pi core patch.
- Retain compatibility for old clients on live events, snapshots and replay; v2 has 32-capability limit, v3 has 64.
- Only confirm details in this increment; editors and oversized select need their separately complete integrations.

## Tasks

0. Fix the existing non-TUI approval renderer before enabling larger contexts.
   - [x] Reproduce raw ask-secret exposure and positive escalation after context truncation in focused tests.
   - [x] Use the existing nontruncating mask at the RPC rendering boundary; preserve the local TUI presentation.
   - [x] Show complete masked RPC context when it fits the existing title/message UTF-8 limits. Refuse positive approval when complete context cannot fit; never let display-only truncation authorize a call.
   - [x] Keep abort/cancel/failure and no-session-grant behavior. Root runs regressions and independent review; this does not claim paginated details support.

1. Bounded document and delivery primitives (no advertisement or changed answer authority).
   - [x] Add tests first in remote/question-details_test.ts for UTF-8 page boundaries, exact byte/hash fidelity, over-limit/capacity refusal, immutable bindings, scope mismatch and deletion.
   - [x] Observe expected missing-implementation failures centrally.
   - [x] Implement remote/question-details.ts: immutable per-question document store and per-connection delivery tracker with onSent/onDropped tickets, strict sequential progress, safe retries, stale-callback and invalidation protection.
   - [x] Root runs focused tests/type/format; independent review checks source/connection authority and capacity behavior. Commit the reviewed complete internal component.
2. Producer integration.
   - [ ] Source complete immutable masked approval context including command and differing message; native confirm source; RPC sanitized-source gate.
   - [ ] Terminal paged retrieval and trusted internal exact-question delivery attestation; preserve Mac fallback and No/cancel.
3. Host integration.
   - [ ] Parse/read routing, grants and ledger bypass; bind page tickets to real outbox delivery and exact current source/question; check positive answers immediately before owner write.
   - [ ] Per-connection opt-in and legacy projection for broadcast, snapshot and replay, with capability route limits tested.
4. Android integration.
   - [ ] Strict pointer/page validation, sequential download, full UTF-8 and SHA-256 verification; memory-only state keyed to connection/selection/question.
   - [ ] Full-text display and explicit retry, Yes gate in repository/UI/background entry points; No/cancel independent. English/German strings and tablet/phone layout.
5. Combined qualification and activation.
   - [ ] Corruption, dropped/reordered/foreign/revoked/stale-source/race/old-client tests and actual native/RPC producer-to-Android flows.
   - [ ] Advertise only completed supported capability routes; document exact qualification and remaining hardware/Linux gates.

## Safety gates still requiring code evidence

RPC approvals currently distinguish local message from masked remoteMessage; do not treat generic raw RPC UI text as a verified masked approval source. Terminal owner must require a trusted exact-question Host attestation rather than accepting the unchanged client QuestionAnswer as sufficient. Root keeps the feature unadvertised until both integrations pass.


Tasks 0 and 1 are saved in Host commits `1f4163144` and `5ea921b60`. The covering run passed 416 tests across 18 files; types and scoped formatting passed. Tasks 2–5 remain open and no details/editor capability is advertised. See [qualification](../testing/question-details-delivery-qualification.md).
