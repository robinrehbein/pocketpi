# Native local editor takeover during an Android upload

## Qualified behavior

`native-local-takeover-02` passes on the owned phone-layout emulator against an
actual Native Pi worker. Its large Source prefill has a real paged lease. While
Android's possible-write commit is waiting for the final genuine Source chunk
ACK, Ctrl-] transfers the editor to the original local Pi editor. The worker,
runtime, IPC peer, saved file and session remain the same.

The Source closes its published remote question and invalidates the old upload.
The Host observes an active old lease immediately before the real `forget`
delegate and no proof immediately afterward, using the same fresh, still active
reader and unchanged owner identity. The observed takeover transition is
53.065 ms, below the three-second bound and the reader/lease expiry window. A
historical forget log alone is not accepted as proof of active revocation.

The local editor receives the original Source prefill, checked by its hash, and
the original SDK call remains pending. Returning the exact held ACK does not
cause an old Source commit. Android receives a correlated error and retains the
exact 1-MiB draft and uncertain attempt in its encrypted journal. Old upload
quota is released in 4,152 ms, before expiry. Android had already observed the
question removal at takeover and before local cancellation; this run does not
prove its local guard can detect an unannounced Host-only lease change.

A separate explicit Escape in the owned terminal cancels the genuine local
editor. Only then does the old SDK call return `undefined`; it does not return
the Android submission. A fresh explicit command through Android opens a new
question and grant under that same worker. Its empty prefill correctly uses an
unpaged lease distinct from the old paged lease. An explicit 32-byte value is
accepted by the real Source and returns once through the SDK. The old uncertain
attempt/draft stay unchanged during a three-second no-replay window and are
shown through the bounded local recovery viewer, selected by the old question ID.

## Implementation and checks

This increment adds only private Host fixture behavior, an opt-in Android test
and evidence. Android/Host production code and public capability switches are
unchanged. The private actions send Ctrl-] and Escape through the verified owned
PTY, delegate all Source requests unchanged and return the actual held ACK; they
do not mutate session/grant/lease maps or fabricate a Source reply.

Independent scoped review caught a possible false lease inference from reader
loss. The final implementation brackets the actual forget synchronously with the
raw same-reader proof checks and tracks detach/inactive presence. The reviewer
confirmed that correction in its scoped re-review. Android edits only the first
2,048-unit Source window, replacing its 1,024 emoji with the bounded prefix; the
full encrypted result is checked exactly. The fresh SDK invocation legitimately
uses an empty prefill rather than pretending a one-window edit replaced an entire
large Source document.

Debug/test assembly and extension TypeScript checks pass, as do scoped formatting
and diff checks. `native-local-takeover-01` reached the genuine held ACK but failed
because the fixture omitted its top-level upload stats field; it completed cleanup.
The final run includes the real store stats for this mode and passes one
instrumentation case in 42.495 seconds, with cleanup passed. No production source
changed, so the prior full unit suite is not presented as a new run here.

Final APKs:

- App: `97fc0d099b0ced0a7ba63f1f620877948204887559be83d3e8b7cb451d484950`
- Test: `6ad8f347cc1acd22642f766012f363359ad1399b44acd23db670a78a5b134d5d`

Artifacts are under `/private/tmp/pocketpi-paged-production.oge2ybh1`.
The root controller preserves 50 current core/test/resource snapshots plus APKs
and runner/helper snapshots, verifies the owned emulator and installed packages,
and removes only its own resources. The independent audit checks the complete
old Source begin/22 chunks/ACK offsets and bindings, unchanged final ACK, old
journal tuple and negative result, old paged/new unpaged claim profiles, exact
same actual SDK PID/session, and exactly two total SDK returns: old local
`undefined` and fresh 32-byte value. No SDK return matches the old submitted hash.
It reports zero errors in `native-local-takeover-independent-audit.json`.
`native-local-takeover-process-exit-audit.json` separately confirms the owned Host
PID 75661, Pi PID 75690 and Android PID 14330 absent, with the emulator idle.

## Limits

Immediate lease state, stable runtime/peer and the fresh Source receipt are
observations of pinned real fixture code. A complete continuous event log and
raw fresh receipt are not separately preserved. The observed no-replay interval
is three seconds. Local takeover closes the published remote question; it does
not qualify silent Host-only revocation, same-question reacquisition or peer
replacement with the old process alive. Positive-outcome incarnation, physical
Samsung/DeX, independent authorized Linux and broader input/resource cases retain
their separate gates. Public paged/outcome capabilities remain off; no release,
external push, Wear emulator or production-host change is included.
