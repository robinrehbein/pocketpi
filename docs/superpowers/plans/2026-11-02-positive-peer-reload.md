# Positive-outcome binding during actual Source peer reload

> Disjoint Terra authors; root owns Git, builds, runtime, audit and commits.

The existing receipt-current peer/epoch/bridge checks remain unchanged. A durable
Host `applied` record survives later replacement. A genuine Source reply that the
Host has not validated until after its original binding changed may remain
`unknown`; the original close-race contract does not require weakening those
checks. Fixture knowledge that a Source applied a write is not itself durable
Host evidence. Do not silently promote it to `applied`.

## Current slice: replacement before Source commit

- [x] Inspect canonical contract and existing Host/Android implementation.
- [x] Explicit private positive variant of the actual Native peer-reload case.
      Both test/fixture opt-ins are required; default/public gates stay off.
- [x] Source emits real old/new owner-incarnation IDs and actual positive bridge
      acknowledgements. Capture their binding rather than infer IDs from PID,
      session, peer aliases or fixture lifecycle generations.
- [x] Before Source commit, hold its genuine last chunk ACK, verify normal Host
      reload busy, separately execute actual public SDK reload through the fixture
      terminal sentinel. Same PID/runtime/file/session, actual replacement peer.
- [x] Old SDK settles without submitted value; return unchanged old ACK, obtain
      matching error and preserve exact encrypted uncertain attempt/draft. Exact
      old tuple outcome query after replacement returns unknown; mutation to the
      fresh owner ID also returns unknown without changing the old fence.
- [x] Fresh explicit Android work receives a genuinely bound positive ACK;
      verify full receipt, atomic local retirement and exact authenticated release
      acknowledgement. Old pending reservation and recovery text remain intact.
- [x] Scoped review, build/type/format checks, actual Native run, independent
      tuple/SDK/authority/source/cleanup audit, precise docs and local commits.

These pending-record queries do not by themselves prove mismatch handling against
an existing applied record: pending correctly returns unknown for every lookup.
That distinction must remain visible in the qualification.

## Separate remaining cases

Native and qualified RPC still need actual post-applied owner replacement with
an original positive record durable before the switch. Hold/drop the client
response only after Host application; query the exact old tuple after replacement
and compare malformed/fresh-owner tuples against positive stored evidence.
Determine the supported normal reload/resume path while SDK idle first; do not
assume the Native terminal sentinel is available in RPC.

A late Source ACK crossing replacement before durable Host validation is a
separate conservative-unknown boundary, not an assumed mandatory acceptance.
Resource/retention policy and the rest of the original acceptance matrix remain
open before public enablement; physical Samsung and independent authorized Linux
retain their separate full-environment scope.
