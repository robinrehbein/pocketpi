# Native start failure policy implementation plan

> **For agentic workers:** Use superpowers:subagent-driven-development with an independent scoped review.

**Goal:** Keep ordinary Native session creation, reopen and fork on the Native runtime; report a failed attempted Native start instead of silently switching runtimes.

**Architecture:** Limit `createNative`'s compatibility result to an unavailable Native backend. Real launch, exit and registration failures propagate through the existing request-error path after existing ownership-safe cleanup. Retain frozen legacy RPC routes.

**Tech Stack:** TypeScript Host Sessions, existing lifecycle/PTY fixtures and Vitest.

**Spec:** The Native-only runtime decision in `docs/remote-development-goal.md`, reaffirmed by the user on 2026-11-02.

## Global constraints

- RPC remains backward compatibility only; no new features, parity work or acceptance matrix.
- No second writer after an attempted Native start fails.
- Await actual worker exit before removing a newly copied session file or closing its snapshot; preserve resources owned by a surviving, registered or terminal-used worker.
- Preserve explicit no-backend fallback, old RPC routes, shutdown and permission refusal.
- Root owns Git, tests, builds and process operations. Authors do not run runtime checks.
- No new wire error kind or broad Android UI changes in this correction.

## Review focus

- Timeout and early exit must report failure rather than RPC success.
- Resumed generic launch exceptions must not take the fallback path.
- Discard can fail or a late registration can take ownership; cleanup must not destroy those resources.
- Snapshot closure is idempotent and host shutdown still waits for cleanup.
- Compatibility availability must not be confused with an attempted Native failure.

### Task 1: Reproduce Native failure policy

- [x] Add focused regressions before changing production code; root confirms the expected red failures.

### Task 2: Correct Native startup outcome

- [x] Propagate attempted Native errors and preserve existing cleanup/ownership boundaries.
- [x] Update only existing tests/comments whose expected fallback contradicts this policy; keep no-backend compatibility coverage.

### Task 3: Verify and review

- [x] Run focused and affected lifecycle/registration/snapshot suites, TypeScript and formatting.
- [x] Independently review the immutable diff, record results and commit locally.

The App currently shows a generic failed-create state. This slice makes failure reach that existing path; it does not add protocol categories or claim Native-specific Android diagnostic text.

Qualified: 132 affected lifecycle tests plus five selected actual local Pi cases pass; see docs/testing/native-start-failure-policy.md for scope, skipped cases and evidence. Full goal remains active.
