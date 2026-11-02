# Conservative outcome retention foundation

The internal Host ledger can preview and explicitly expire entries after at least
30 days of provable observed monotonic retention. There is no timer, automatic
cleanup, remote command or local-admin UI wired by this foundation. Public paged
and positive-outcome gates remain off; resource/recovery enablement is not closed.

Ledger v2 stores bounded retainedMs for each pending/applied record. Canonical v1
records are accepted with zero elapsed credit and migrate on a writer operation.
The existing provision marker remains unchanged. Old Host builds reject v2 rather
than silently normalizing it; downgrading does not make evidence safely disposable.
Only observed process time counts. Downtime and uncheckpointed process time are
lost conservatively. Wall time never ages entries. Writes checkpoint prior credit
and set new anchors only after durable write completion, discarding I/O time.
Rollback, non-finite/out-of-range readings or clock exceptions freeze expiry for
that instance. An old wall timestamp never creates retroactive elapsed credit.

Preview leaves ledger bytes unchanged and returns bounded aggregate counts. The
explicit expiry operation uses the same queue and atomic file/poison handling as
reserve/markApplied/release, and expires neither young nor actively protected
records. The Daemon wraps actual positive Source delivery in withActiveReservation:
the pin is registered before the callback and released in finally, including on
error; the queue is available while the actual Source callback waits. Genuine ACK
validation and reserve-before-possible-owner-write ordering remain unchanged.

An expired record is permanently missing positive evidence. Query therefore stays
unknown, and Android's uncertain attempt, text and no-replay fence remain intact.
This is not a failed-write result or a local-cleanup instruction. Exact authenticated
applied release remains the existing narrow exception for already completed Android
cleanup; this change does not make pending entries releasable through that API.

## Deterministic validation

Independent tests first fail on the absent maintenance APIs (9 failed, 1 passed).
An additional behavioral RED exposes a pre-write clock anchor crediting write time;
the corrected checkpoint samples only after durable completion. All 47 ledger,
retention and crash tests then pass, including the 11 new retention tests. The old
duplicate-version mutation now targets actual v2 output and asserts it changed the
bytes. The 24 scoped Daemon upload/source/negotiation integration tests also pass.
Extension TypeScript and scoped format checks pass. Scoped review covers the clock,
queue/pin lifetime and known-v1/v2 fixture readers.

Tests cover both states at the boundary, no wall-clock aging, restart credit,
repeated checkpoints, rollback/non-finite readings, conservative v1 migration,
read-only preview, active async callbacks/error cleanup, eligible quota recovery,
malformed records and storage failure. This is deterministic injected time, not
30 real calendar days of runtime acceptance. Further local-admin UX/authorization,
confirmation/snapshot semantics and actual maintenance-through-Daemon resource
qualification remain before an exposed cleanup feature can be called complete.


## Actual positive transport regressions

`native-retention-v2-01` passes in 33.508 seconds and `rpc-retention-v2-01` in
32.828 seconds, both with JUnit `OK (1 test)` and cleanup passed. Independent Native
and RPC verifiers report PASS against the current 50-source snapshots and APK/
controller/helper pins. Both exercise real 1-MiB Source ACK/durable applied/client
ACK loss, actual idle reload/new Source peer, wrong-incarnation unknown against a
still-present applied tuple, exact repository reconciliation/release and fresh
32-byte SDK success with the v2 ledger and protected delivery path.

The installed App/test APKs are unchanged from the preceding RPC qualification:
`97fc0d099b0ced0a7ba63f1f620877948204887559be83d3e8b7cb451d484950` and
`dc4c980c11306f68758f730ac731476da6c0fcf94571024b9f835cb06e26f2e4`.
Separate live checks confirm Native Host 98108/Pi 98139/Android 19261 and RPC Host
98334/Pi 98355/Android 19654 absent. These regressions do not advance an injected
clock through actual Daemon maintenance or claim physical Samsung/independent
Linux qualification. The original capability gates remain off.
