# Paged Editor Durable Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve large Pi-editor drafts and uncertain commit fences across Android process death without replaying an answer or restoring expired authority. Public paged-editor capability remains disabled until the complete binding contract is qualified.

**Current evidence:** Fourteen private Native/RPC production-chain cases and later duplicate-commit cases are already qualified within their recorded scope. `DefaultRemoteRepository` currently keeps `pagedDrafts` in an identity map and `pagedReceipts` in a list; neither survives process death. The composer and manual-file journals do not persist these objects. Host/source outcome state is bounded and in memory, so absence after restart or expiry is not proof of a failed commit.

**Binding references:** [Contract](2026-11-01-paged-editor-contract.md), [original implementation plan](2026-11-01-paged-editor-dialogs.md), [current qualification](../testing/paged-editor-qualification.md). This amendment adds restart work; it does not waive owner, lifetime, authority, quota, boundary or shared-settlement gates.

## File and ownership boundaries

- Add `PagedEditorJournal.kt` and focused tests: encrypted storage, validated stable identifiers, exact draft data, uncertain attempt records, serialized atomic mutation and failure/capacity behavior. Reuse the existing opt-in preservation behavior of `EncryptedFileStore`.
- Update `DefaultRemoteRepository.kt` and paged repository tests: load the journal before permitting paged sends; persist a commit reservation before its possible-write boundary; separate recovered text from live authority; persist terminal reconciliation before lifting quarantine.
- Update `RemoteApplication.kt` / `AppStores` and repository construction: one production store/journal owner with injectable storage/dispatcher for tests. Keep composer/manual-file storage independent.
- Update editor recovery UI/state and its tests only where necessary to show storage failure, explicit restoration and unresolved outcome. Never turn a durable identifier into a callable source action.
- Add opt-in process-restart instrumentation and isolated native/RPC fixture cases once storage/repository behavior passes. Preserve existing original-owner transfer traces.

## Task 1: bounded durable data and fail-closed loading

- [ ] Define serializable keys by paired route, session and question ID; store captured grant/attempt/upload/commit identifiers as evidence only. Do not serialize `JsonObject` identity, connection generation or a reusable authorization object.
- [ ] Separate draft snapshots from uncertain attempts. Persist the exact submitted draft atomically with its pre-commit fence. Preserve UTF-16 code units losslessly for an invalid/non-submittable visible value (for example explicit code-unit bytes, not a replacing charset encoder); test unpaired surrogates and non-BMP text. Preserve exact text, including a visible value which cannot currently be submitted. Choose encoded byte limits consistent with the existing 32-draft / 8 MiB estimated UTF-16 cache and encrypted-file limits; document any deliberate difference before implementation.
- [ ] Keep at most 32 unresolved attempts/reservations; never evict one to make room. Refuse a new send on load/write/corruption/capacity failure. Clean drafts without uncertain attempts may have an explicit bounded retention policy; unresolved attempts may not expire merely with that cache.
- [ ] Define asynchronous snapshot completion honestly: abrupt process death may lose the last uncommitted keystrokes. Cover startup failure before paged UI/send, route removal, key loss and backup/restore boundaries without treating missing restored evidence as permission.
- [ ] Run RED/GREEN tests for round trips, route/session/question isolation, malformed/corrupt data preservation, failed atomic writes, quotas and reconstruction through a new journal instance.

## Task 2: commit persistence barrier and restart quarantine

- [ ] Allocate the stable local attempt/commit identity before possible dispatch and durably reserve its fence before entering the transport's possible-write boundary.
- [ ] Serialize journal mutation and terminal reconciliation. Buffer exact terminal proof racing the pre-send barrier; an old finalizer must never remove a newer attempt or lift a fence after cleanup persistence failed.
- [ ] Revalidate the captured live owner/grant/source/selection authority immediately before actual send. If dispatch is proved impossible, remove only that reservation through an atomic update. A crash between reservation and send remains conservatively uncertain.
- [ ] On restart, block matching route/session/question submissions even when a new grant or generic snapshot appears. Recovered IDs, a fresh open event and question absence are not settlement proof. Preserve the existing proof-independent Cancel behavior within its defined authority rules.
- [ ] Offer draft restoration only after a newly verified source and explicit user action. Never automatically replay an editor answer or commit; never infer authority from restored data.
- [ ] Test process-death boundaries before reservation, after reservation/before write, after possible write and after a lost ACK; include replacement with the same question ID/new grant, stale async completions and continued storage failure.

## Task 3: terminal reconciliation contract

- [ ] Determine whether existing terminal events can prove the exact original attempt after process death. Match route/session/question and captured incarnation/grant/attempt evidence; question ID alone is insufficient when an ID can be replaced.
- [ ] If the existing wire proof is insufficient, first specify immutable owner-incarnation/attempt binding, authenticated query scope, retention and Host/source restart semantics. Determine whether a definite negative outcome can actually be proven. Implement a separately negotiated read-only outcome contract only where it can establish those facts. Missing/expired outcome remains unknown; indefinite quarantine is valid when proof is unavailable, with visible explanation and a controlled recovery path. Do not promise that every lost ACK can be reconciled.
- [ ] Persist a proven removal before re-enabling Send. A failed cleanup retains quarantine and a visible retry path; never resend the original value merely to clear a warning.
- [ ] Test delayed/mismatched terminal proof, byte-identical question replacement, journal cleanup failure, terminal proof after restart and independent new attempts.

## Task 4: production-chain qualification

- [ ] Run actual encrypted Native and RPC paths with 1 MiB draft text and real original-owner final-value/SDK receipts, covering force-stop and fresh-process recovery.
- [ ] Verify no commit replay from restart, snapshot, new grant or Restore; verify explicit source-authorized cancellation and exact terminal reconciliation where qualifying proof exists, otherwise persistent visible quarantine.
- [ ] Keep IME, malformed multi-page/boundary, owner replacement/revocation, resource quota, two-client/shared-settlement and owner rejection/legacy fallback gates visible. Existing duplicate tests prove their recorded overlap, not simultaneous Host decoding.
- [ ] Independently review lifetime/settlement/bounds; run repository checks. Enable each owner only after every applicable contract gate is proven. Physical Galaxy/DeX and independent Linux qualification remain separate full-goal requirements.
