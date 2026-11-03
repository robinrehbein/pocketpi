# Conservative outcome retention and explicit maintenance

Root Git-first: App02500af6 / Hostd987f7845 clean/up-to-date. User authorized
implementation. Root owns Git/build/runtime; disjoint Terra code/test authors and
scoped medium review. No personal/live Host ledger operations or public gates.

## Decision and invariants

Use persisted observed monotonic process uptime as a conservative lower bound.
Downtime never counts. Existing v1 entries receive zero elapsed credit on migration;
wall-clock reservedAt remains metadata, never proves expiry. At least 30 days
(2592000000 ms) observed retention is required for both pending and applied states.
Rollback/invalid monotonic readings freeze expiry for the instance. Never infer a
negative outcome, discard an Android fence or contact/retry an owner from expiry.
Applied exact authenticated release remains unchanged as the narrower exception.

Ledger v2 records a bounded per-entry observed-retention field; continue strict
canonical validation, quota/byte bounds, safe files, atomic fsync/rename and poison
semantics. Accept canonical v1 conservatively and migrate on explicit writer
operation; no lookup mutation. Checkpoint aging through existing writes and an
explicit maintenance call. Anchor each entry to its last process-open/reservation
monotonic value plus persisted credit, avoid double counting between writes.

Explicit preview is non-mutating and returns bounded aggregate counts, not text
or other-device tuples. Explicit expiry is serialized with reserve/markApplied/
release and skips every in-flight protected tuple. Add a transparent async
withActiveReservation(tuple, operation) lifetime primitive: increment before
operation, release in finally after its real result/error, no held queue across
operation. Daemon wraps the actual positive deliver path through this primitive;
reserve-before-owner-write and markApplied-after-genuine-ACK remain unchanged.
The class API is an internal foundation, not an advertised admin operation yet.
No automatic timer/scheduler or remote maintenance protocol is added this slice.

## Tasks

- [x] Inspect original retention contract, current ledger and daemon dispatch.
- [x] Independent deterministic test cases first, root RED on existing implementation.
- [x] Ledger clock/migration/preview/explicit expiry + active lifecycle and daemon integration.
- [x] Strict focused tests for retention boundary, no wall-clock aging, restart/lost
  credit, rollback/invalid time, v1 migration, repeated checkpoint no double count,
  both states/scopes/quota relief, active pending/ACK ordering and failures.
- [x] Existing ledger/crash/daemon checks, TypeScript, formatting, scoped review.
- [x] Reconcile fixture version readers and current real positive Native/RPC evidence
  if shared storage format changes; record remaining maintenance-entry-point and
  product qualification boundaries explicitly, local commits only after checks.

A larger follow-up must expose a reviewable local-admin operation and qualify
actual resource recovery before public enablement. This foundation alone does not
close that gate. Expiry itself irreversibly removes future positive evidence; an
actual product action must explain that missing evidence remains unknown.
