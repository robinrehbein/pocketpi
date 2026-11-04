# Native editor wrong-grant qualification plan

> **For agentic workers:** Use superpowers:subagent-driven-development with scoped authors and independent review.

**Goal:** Demonstrate actual Native Host refusal of an invalid upload grant before any Source upload or SDK value, followed only by explicit valid retry.

**Architecture:** A private Android transport mutates only the first genuine upload-begin grant. The actual authenticated Host returns the negative result and records Source counters at that response. The real UI retains its encrypted draft and sends a second, valid attempt only on an explicit click.

**Spec:** Native wrong-authority acceptance in `docs/plans/2026-11-01-paged-editor-contract.md`, with RPC frozen per the main goal decision.

## Constraints and review focus

- Native only; no RPC parity, new public capability or production guard changes.
- No fake result, direct Host method invocation, automatic retry, reconnect or grant refresh.
- Actual Source upload counters stay zero and SDK result absent at the rejected response.
- Preserve the same live question, owner, authenticated connection and exact encrypted draft.
- Root owns builds, Git, emulator and process cleanup; authors edit disjoint files.
- Source reads before refusal are legitimate; zero upload dispatch does not mean zero earlier owner reads.

### Task 1: Host observation

- [x] Add exclusive private Native proof flag and immutable first-refusal request/result/counter snapshot.
- [x] Preserve actual raw Source begin/ACK/commit/ACK for the later valid attempt.

### Task 2: Android actual denial and retry

- [x] Load 1 MiB Source; edit and restore the first window through real UI callbacks so a genuine encrypted draft exists with identical final bytes.
- [x] First explicit Send uses only a mutated grant; assert actual negative ACK, no commit, retained draft/question and no uncertain fence.
- [x] Second explicit Send uses the original valid grant, yielding exactly one real SDK result with the exact Source hash.

### Task 3: Qualification

- [x] Build/type/format/review, run Native on the owned emulator, independently verify pinned raw evidence and cleanup.
- [x] Record scope and commit locally. Production Native enablement is a subsequent reviewed change; RPC remains frozen.

Actual Native case and independent audit pass; see docs/testing/native-wrong-grant.md. Public production wiring remains the next step.
