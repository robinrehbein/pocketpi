# Native production editor wiring plan

> **For agentic workers:** Use superpowers:subagent-driven-development with scoped authors and independent review.

**Goal:** Make the qualified large editor and durable positive recovery available through the normal Android/Native Host path, without enabling new RPC features.

**Architecture:** Default Host feature support is Native-only and separately negotiated. Android's production factory opts into Native paged and positive support with its real journal; private fixture gates stay separate. Historical receipt queries remain independent of a live editor owner.

**Spec:** Native-only runtime decision in `docs/remote-development-goal.md`, paged/positive contracts, and the scoped actual Native qualification in `docs/testing/paged-editor-enablement-checkpoint.md`.

## Status, 2026-11-02

The implementation and tests are a local development-branch candidate, with no external
deployment. [Local qualification](../../testing/native-production-editor.md)
records separate direct-repository and debug application-factory runs. Independent App, Host and cross-layer reviews found no concrete defect; scoped
Host and Android checks passed as detailed in that qualification. Remaining broader
workflow qualification is open. The full
remote-development goal remains active; RPC is frozen.

## Constraints and review focus

- No RPC expansion; registration, projection and upload enforce Native ownership, not just global advertisement.
- Base, Native, paged and positive v3 opt-ins remain independent; only successful capability delivery installs negotiated state.
- Keep current source/owner/grant/lease, quota, payload, durable-ledger and journal guards.
- Production App requires a real journal and strict JSON native:true for new paged actions; private test gates are not enabled by the App factory.
- Historical exact-tuple query/release works after owner/question disappearance; do not add a live-owner prerequisite to recovery.
- Explicit feature-off, legacy hosts, v2, unsupported owners and existing frozen compatibility remain conservative.
- Root runs all Git/build/tests/runtime; no deployment, release or personal Host mutation.

### Task 1: Host Native production policy

- [x] Write focused failing production-negotiation/bridge/projection tests before code.
- [x] Default feature support on for eligible Native owners, centralize advertised/delivered eligibility, and block new production RPC bridges/grants.

### Task 2: Android production policy

- [x] Write failing tests for production configuration, strict Native question eligibility, negotiation, journal availability and owner-independent receipt recovery.
- [x] Add explicit production constructor options, wire the real App factory, and preserve private test/legacy defaults.

### Task 3: Review and component qualification

- [x] Run scoped Host/App suites, Host extensions TypeScript and independent App, Host and cross-layer reviews, including failure cases. See qualification for exact counts and the pre-existing Biome finding; this does not claim a fully clean Biome run.

### Task 4: Actual production-path qualification

- [x] Run an actual Native Source/Host/Android direct-repository case with no Host fixture enablement or App test gates; prove negotiated support, bad-grant refusal, exact 1 MiB SDK result and positive journal/release cleanup. A separate debug application-factory run covers the valid-grant path.
- [ ] Preserve raw pins and proofs, document remaining physical/Linux/full-workflow boundaries and commit locally.
