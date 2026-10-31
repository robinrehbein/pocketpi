# Direct Git actions implementation

Host protocol and owner switch exist, but GitWrites.execute is a stub; Android has no write
parser or callers. Agent/shell support does not implement the agreed direct confirmed actions.
Use the binding Git-writes section of the Host protocol/README.md and protocol/git-write.ts.
Preserve opt-in, protected branches, same-branch origin, hooks/LFS/signing/config restrictions,
guarded HEAD snapshots, worktree serialization and the request ledger. No live pushes or PRs.

1. Android: strict wire models/parser and synchronized canonical fixture. Test results, refusal
   reasons and malformed values. Do not advertise or invoke unimplemented Host writes.
2. Host foundation: protected-branch owner setting, write-only own-worktree source, guarded
   snapshot access and mutation reservations across sessions. Test policy and stale/foreign data.
3. Branch and selective commit: temporary index, index.lock, snapshot content validation,
   update-ref compare-and-swap, preserve unrelated staging, report post-commit index failures.
   Validate with real isolated repositories and revocation/race tests.
4. Push and PR: non-force bounded noninteractive operations, sanitized target, PR lookup before
   create, unknown outcomes and ledger/dispatch audit. Controlled test adapters, no live writes.
5. Changes UI: fresh reads, exact paths/target review, separate explicit confirmations and
   pending/success/refused/uncertain states; retain drafts. No automatic new-ID mutation retry.
   Explicitly reconcile the protocol's same-ID replay with reconnect safety: intentional result
   recovery may reuse the original request only after ledger-expiry semantics are audited.
6. Actual Android/packaged-Host integration: isolated Git and controlled transport. Then ticket
   start, manual conflict recovery, real shell tests, PR/CI display. Label simulated Plane/GitHub/CI.
   Physical Samsung, independent Linux, live external CI and DeX remain separately unqualified.

Each increment receives focused tests, independent review and a local commit. Root owns Docker,
SDK and broad checks. Leaf models and policy helpers can proceed in parallel; avoid shared-file
edits until their integrations are coordinated. Full environment goal remains active.
