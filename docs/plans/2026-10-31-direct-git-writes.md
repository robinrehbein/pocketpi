# Direct Git actions implementation

Current state: branch preparation, selective Commit, Push and confirmed pull-request creation
are implemented in the local Host and Android worktrees. PR creation and explicit status use
separate V3 capabilities, a fresh remote HEAD check, bounded recovery bindings and a durable
Android intent. A lost result is not retried automatically. The Pi `pr-status` read path remains
separate and does not establish actual-worker CI qualification. The scoped local results and open
gates are recorded in [PR qualification](../testing/pull-request-qualification.md) and
[push qualification](../testing/push-qualification.md). Preserve owner opt-in, protected branches,
same-branch origin, hook/LFS/signing/config restrictions, guarded HEAD snapshots, worktree
serialization and the request ledger. No live push or PR was created in this qualification.

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

The sequence below records the original incremental plan; the current checkpoint is above.
Each increment receives focused tests, independent review and a local commit. Root owns Docker,
SDK and broad checks. Leaf models and policy helpers can proceed in parallel; avoid shared-file
edits until their integrations are coordinated. Full environment goal remains active.

## Historical foundation evidence

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

## Branch preparation and host admissions (historical checkpoint)

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


## Confirmed pull-request creation: implementation plan and checkpoint

The following design has been implemented locally. Its local qualification and remaining gates
are recorded separately in [PR qualification](../testing/pull-request-qualification.md). No
production PR, push, merge or CI change is part of that qualification.

1. Extend the guarded branch authority with an immutable offered-base list. Match the requested
   base exactly, retaining token/device/session/source/expiry, HEAD/origin/policy and repository
   reservation fences. Use a fresh read of the exact remote branch to establish the intended
   HEAD; a local origin-tracking ref alone does not prove the remote state.
2. Add a provider adapter with an explicit GitHub owner/repository derived only from the bound
   origin. Keep lookup and creation separate. Prefer the installed public `gh api` interface
   with explicit host/method/path and JSON on stdin; private title/body and credentials must
   not enter command arguments or logs. Apply bounded output, process-group cancellation,
   controlled owner authentication/configuration, strict response identity checks and no
   automatic mutation retry. Prove the final invocation/authentication boundary locally before
   daemon enablement. The existing status-only `pr-status/gh.ts` is not a mutation adapter.
3. Lookup the exact open head-owner/repository/branch/base before creation. Validate repository,
   base, branch, current head SHA, PR number and URL in all provider results. Incomplete pages,
   malformed output and ambiguous matches do not establish absence. A complete validated
   successful response is known; loss after dispatch remains unknown. A PR is a mutable branch
   relationship, so observations must not imply an atomic lock against other remote writers.
4. Add V3-only create/status capabilities and an explicit read-only status command backed by
   a bounded recovery binding without old actor closures. The original write ID carries the
   bound intended head/base. Reconnect and owner writes-off must permit newly authorized
   observation; current absence does not prove that creation never happened. Keep status
   outside the mutation ledger/write budget. Preserve unknown outcomes through delayed
   delivery and outbox overflow, just as for Push.
5. Add strict shared Host/Android goldens, capability-gated repository calls and a dedicated
   controller/pane for base/title/body/draft and immutable confirmation. Persist intent before
   send, retain it across disconnect/process recreation, require explicit lookup or
   acknowledgement, and never silently push or create again. Integrate beside Commit/Push.
   Continue to obtain CI/check state from actual Host/Pi status rather than synthesizing it
   from a successful create response.
6. Qualify with isolated fake-provider/process tests, encrypted Host integration and the
   actual Android transport/panes. Include existing PR, incomplete lookup, changed authority,
   known success, loss after effect, late source revocation, outbox overflow, device reconnect,
   owner-off status, durable restart and no duplicate dispatch. Real GitHub mutation, independent Linux and the actual Pi/CI path remain explicit separate runtime gates.

The local `gh 2.98.0` help confirms stdin JSON, explicit host/method and paginated/slurped API
output; its `pr list --head` does not accept owner-qualified branch syntax. The provider contract
uses the [official versioned REST pull-request documentation](https://docs.github.com/en/rest/pulls/pulls?apiVersion=2026-03-10):
list filters include owner-qualified head and base; creation uses head/base plus title and
optional body/draft. The CLI was additionally exercised against a local HTTP fixture; a separate real GitHub HTTPS/authentication check was read-only. Neither created a real PR.


### Wear interaction check for this increment

Evaluate watch interactions by readable context and practical action, not by copying the phone
form or imposing a messages-only limit. PR/CI updates, concise actionable agent questions and
handoff to the relevant phone/tablet view are useful candidates. Full title/body/base editing,
code/diff review and a confirmation that cannot show sufficient context belong on the larger
display. Any later watch approval must carry enough fresh, bound context to support the
decision; do not present a generic approval that conceals its target or uncertain outcome.
