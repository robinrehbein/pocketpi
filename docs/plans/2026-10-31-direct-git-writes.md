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

## Verified foundation increments

Android result parsers and command encoders pass seven focused unit tests against the synchronized
Host fixture. They are not wired to Repository/UI or capability discovery yet.

Host policy and own-checkout capture are implemented. The owner can show, replace or reset branch
patterns through CLI/local IPC, with separate persistence groups, rollback, no-op handling and
pattern-free audit. Twenty focused tests and extension type checking pass. The write-source guard
refuses finished-worktree comparison fallbacks and checks authorization and folder identity again.
Guarded HEAD-snapshot access now passes the 73-test Git/owner regression group and extension
type checking. It checks device/session/expiry, own checkout, source guards and repository identity,
returns independent copies and exposes revalidation. Atomic mutation reservations and actual
Git-write executors remain open; neither these foundations nor the switch makes commit/push/PR available.

## Branch preparation and host admissions

The branch command now reaches the real read-only executor over the encrypted device route.
It returns a device/session/worktree/branch/HEAD-bound ten-minute token, sanitized single origin
target, local upstream/remote-tip state, current protected-branch decisions, hooks skipped and
offered bases. Multiple effective push URLs and incomplete required objects are refused.
Trusted project additions are read from the project layer; configured base references are
resolved against actual remote names without confusing slash-containing branch names.

Host admissions cover session work starts and create, plus NativeLocal actual launches and
restored workers. Setup is ordered per session without holding up completion of independent
running tasks. Reservation guards recheck member identities/idle state and pending discoveries.
This is a host admission boundary, not an index lock or atomic Git commit. Raw terminal-input
coverage and unregistered native-runtime gaps must be audited before enabling a writer.

The final Remote run passed 3,383 tests with 35 skips across 235 passing and seven skipped files.
Type checking passed. Shared Atlas configuration tests passed 13 tests. A previous full run had
one unclassified five-second frame timeout; phase-only diagnostics and two focused passes were
added before the final broad pass. The intermittent timeout has no established cause.

Atomic HEAD verification was probed with the public update-ref transaction interface:
the local Apple Git 2.54.0 supports symref-verify when invoked with no-deref, but Git 2.39.5 in the
existing Linux image refuses that command. Holding HEAD.lock blocks ordinary update-ref too,
so dropping the lock is not an acceptable substitute. The future commit must gate the required
capability and qualify a suitable packaged Git runtime; no exact minimum release is assumed.
Commit, push, PR executors and their confirmation UI remain open.
