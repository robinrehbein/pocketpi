# Durable positive outcome across normal idle Native reload

Scope: private fixture and Android instrumentation only; public gates remain off.
No receiptCurrent weakening, synthetic acknowledgements, Pi core patches or host
maps injection. Android S25/Tab S11 priority; no Wear emulator or iOS work.

- [x] Read actual normal reload guards and existing positive dropped-ACK path.
- [x] Host disjoint `--paged-editor-post-applied-reload-proof`, explicit positive
  outcomes and restart-lost-ack/native submit. After actual Source 1-MiB commit,
  SDK success and full exact durable applied record, drop original phone ACK.
  Normal domain reload only once actual question closed/SDK idle; observe real
  dispatch, successful re-registration, distinct peer and same process/session.
- [x] Android holds real reconciliation query before write without blocking fresh
  command paths. Preserve uncertain attempt; open fresh question/new incarnation,
  query mutated old receipt -> unknown, then real original query -> applied.
  Check full receipt, exact retirement/release and no upload replay; fresh SDK
  success separately. No fabricated queries or results.
- [x] Review, root build/type/runtime/audit with immutable sources and cleanup.
- [x] Record scope/evidence, local commits. RPC remains a separate path.

Root owns all Git/build/runtime. Disjoint Terra Host/Android authors work in
parallel by user request; one scoped reviewer. Existing approved implementation
authority applies; no new discovery approval needed. If same-process normal reload
is actually unsupported, record its actual result and use a supported replacement
path in a later explicit slice rather than bypass production guards.


## Qualified RPC follow-up feasibility (not runtime qualification)

The supported Host-owned RPC route is the same domain `session.reload`, which
sends the actual RPC prompt `/pocketpi-reload <nonce>` and consumes a matching
`pocketpi-reload-v1` status. It does not use Native `session.reload.run` or its
`reloading` response. Existing `host/daemon_reload_test.ts` checks an actual seeded
RPC reload/new Source peer, but no durable outcomes or Android settlement.

A separate qualified RPC case must bind the live RPC object/child PID, file and
SID, exact authenticated upload grant and real Source receipt; verify the nonce
and SID of the actual reload status and same-process replacement peer. Replace
Native runtime/worker/lease assumptions with RPC-specific ownership checks. A
new process reopening the same saved SID is a different replacement case and must
not be substituted for this supported idle reload. Preserve old SDK evidence
before the fresh result; scope trace counts by original/fresh nonce. No RPC
positive replacement success is claimed by this feasibility note.
