# Native Source peer replacement during an Android editor upload

## Two deliberately distinct reload paths

The normal Host `session.reload` operation refuses an open editor as busy. The
fixture probes that real domain operation and verifies no `session.reload.run`
request reaches the Source; it does not bypass those guards.

A separate fixture terminal sentinel consumes Ctrl-R and asynchronously calls
the current command context's public Pi SDK `reload()` API. This tests an
SDK-initiated extension reload while the old Source ACK is held. It is not a
production Ctrl-R feature, a Pi-core patch, or a successful phone/Host reload
during an open editor. The test records actual SDK startup/shutdown/reload
callbacks and distinct extension-factory generation markers.

## Passing case

`native-peer-reload-04` passes in 37.672 seconds with cleanup passed, against the
actual Native Pi worker and owned Android phone-layout emulator.

The old Source prefill is 1 MiB and uses a real paged claim. Android reads it,
edits only the bounded first window and durably retains the exact complete
submitted value before its possible-write commit. The fixture holds the final
genuine Source chunk ACK after its original handler accepted all 1 MiB.

After the normal Host busy refusal, the separate SDK reload closes the remote
question and settles the old SDK editor with actual `undefined`. A replacement
Source peer registers a genuine bridge under the same Pi PID, Native runtime,
saved file and session ID. The old captured authority and lease no longer apply.
The Source journal epoch is not required to change for this same-process
re-registration; a new peer and real SDK generations are checked independently.

The exact original ACK is returned unchanged. Android receives the matching old
request error and retains the complete uncertain attempt and draft. No old Source
commit occurs. Upload quota cleanup takes 4,223 ms, before expiry. A fresh explicit
Android command uses the newly loaded extension and an empty prefill, correctly
obtaining a distinct unpaged lease. A separate 32-byte value receives the real
Source receipt and exactly one SDK success in that same Pi process. The old
journal entries remain unchanged through a three-second no-replay observation
and are visible in the bounded local recovery viewer.

The recorded SDK lifecycle is startup generation `hp0dj1ovku`, its reload
shutdown, then reload-start generation `x3z33k5hreq`. These are fixture lifecycle
markers, not the positive-outcome protocol's owner-incarnation IDs. Both actual
SDK editor outcomes use Pi PID 80711: old `undefined`, then the fresh 32-byte
value with hash
`0eac036941c8d6e19536806b37999d0382dd5a380937887ae590e472853aee60`.
No SDK outcome contains the old submitted-value hash.

## Checks and failed diagnostics

Android debug/test assembly, extension TypeScript, scoped formatting and diff
checks pass. This increment changes only private fixtures/tests and docs; no new
full production unit-suite run is claimed. Scoped independent review found and
closed missing ACK/timer cleanup and missing old SDK PID/mode checks.

`native-peer-reload-01` failed before the actual reload because the new mode did
not expose the existing Source SDK counter. `native-peer-reload-02` then failed
the stats request: its route had not included the new mode, returning HTTP 404.
Their final status-capture failures remain recorded; the actual owned processes
exited and metadata were independently confirmed removed. These are diagnostic
failures, not passing feature evidence.

`native-peer-reload-03` did perform the real reload and one fresh SDK success,
but its observer counted the fresh commit twice and corrupted its own chunk
observation. Pi legitimately registered the same new peer twice, installing the
observer twice. A per-peer WeakSet now prevents duplicate instrumentation while
leaving every actual registration and bridge reply unchanged. Scoped re-review
confirmed that correction; the final run still observes the real repeated
registrations but records one fresh commit and receipt. Its cleanup passes.

Final app APK:
`97fc0d099b0ced0a7ba63f1f620877948204887559be83d3e8b7cb451d484950`.
Final test APK:
`b376200abb3ba68677d7dcd1335bd00e5693dd5fdc6c9a403f2627d697a663fb`.
The private root controller preserves 50 core/test/resource snapshots, installed
APK hashes and runner/helper snapshots. Artifacts are under
`/private/tmp/pocketpi-paged-production.oge2ybh1`.

`native-peer-reload-independent-audit.json` reports zero errors, checking source
pins, actual lifecycle generations, old/new bridge aliases, old paged/new unpaged
authority, the genuine old begin/22 chunks/ACK chain, exact old journal/error,
SDK identity and values, fresh receipt observation and bounded no-replay/recovery
UI. `native-peer-reload-process-exit-audit.json` separately confirms Host PID
80689, Pi PID 80711 and Android PID 15836 absent, with the owned emulator idle.

## Remaining boundaries

The fresh Source receipt, runtime/file identity and authority transitions are
observations supported by pinned real code and sampled status, not a separately
preserved raw receipt or a complete continuous log. This case disables positive
outcome negotiation; it does not prove that protocol's original-owner positive
receipt across replacement. Physical Samsung/DeX, independent authorized Linux,
resource policy and the rest of the original acceptance contract remain distinct.
No public capability, release, external push, Wear emulator or production host
was enabled or changed.
