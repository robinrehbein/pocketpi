# Actual Host death after durable pending, before Source dispatch

The private Native and qualified RPC cases now qualify the actual Host-process
boundary between the durable pending reservation and the first Source upload call.
Sessions awaits positive.reserve before editor.upload.begin; the fixture holds the
return only after the real ledger write and fsync complete. The exact authenticated
ten-field tuple is read from the real canonical ledger and matches Android's
captured eight-field receipt. Actual source read authority, bridge/capture and SDK
start identity are checked; no fabricated SDK result is used.

Phase A runs the actual Pi editor with a 1-MiB source and the encrypted Android
AppStores/repository. The edited value is fully journaled at the possible-write
boundary; its hash is
`900dce5cc5d1bc1b5f209c09f600c445e85149e116e2b87542913b07d06f1763`.
The Source has started its editor, but begin/chunk/commit dispatch counters and
submitted-value SDK count are zero. Root verifies the live Host/SDK ancestry,
PID/start/arguments/CWD and Android process, captures fresh status/raw ledger, then
SIGKILLs the Host first. Neither the SDK nor Android is deliberately stopped before
that signal. In both passing cases the original SDK exits naturally after Host
death. Only afterwards Android is force-stopped for the second encrypted-store
process. Its phase-A `Process crashed` instrumentation ending is intentional after
the saved boundary proof, not a normal JUnit completion.

The new Host reuses byte-identical private ledger, TLS, config and provision marker.
The restart helper supports strict known canonical v1/v2 and an explicit pending
expectation; old callers still default to applied. Phase B authenticates the
original device and queries the exact captured receipt without a live Source.
The result is exactly unknown; pending count stays one and applied count zero.
Android compares the complete original attempt/binding/key/text, draft timestamp
and hash, through a three-second no-replay observation. Upload, answer, release
and owner-dispatch counts stay zero. The ledger bytes remain identical across all
five saved filesystem stages through the end of phase B.

## Passing runs

| Label | Mode | Duration including cleanup | Original Host / SDK → new Host | Android A → B |
| --- | --- | --- | --- | --- |
| native-host-pending-02 | Actual Native | 35.679 s | 13681 / 13704 → 13908 | 22571 → 22942 |
| rpc-host-pending-01 | Actual Host-owned RPC | 35.576 s | 14174 / 14196 → 14404 | 23253 → 23637 |

Both controllers and cleanup pass, phase B reports JUnit `OK (1 test)`, and the
independent `verify-pocketpi-host-pending.py` reports PASS. All listed processes
were separately confirmed absent. The new root controller
`run-pocketpi-host-pending.py` preserves historical controllers, captures 36
current source pins, installed APK/controller/helper hashes, five raw filesystem
snapshots, actual SDK-start/absence-of-value traces, signal ordering, authenticated
wire query/result and two App-PID/encrypted-store proofs. Fingerprints omit the
inherited process environment; they do not copy provider secrets.

App APK SHA-256:
`97fc0d099b0ced0a7ba63f1f620877948204887559be83d3e8b7cb451d484950`.
Test APK SHA-256:
`083c6581b13ed2843c228aae507e23d65932b86c16b6c69f1e1accc6dfc35ebe`.
Private artifacts are under `/private/tmp/pocketpi-paged-production.oge2ybh1`.
The authenticated query observer follows the actual SecureChannel.open → authorized
execute → outbox result path and correlates request IDs/data with Android. No query,
positive receipt or SDK result is synthesized. The raw ledger snapshots, rather
than tuple counts alone, prove byte preservation.

`native-host-pending-01` reached the real crash/restart/unknown result with the
pending record intact, but failed the new observer assertion. The observer had
incorrectly watched CommandLedger.run; outcome.get deliberately bypasses that
side-effect ledger. It now observes the actual authenticated decrypt/outbox path.
The failed run retains cleanup PASS and is not passing combined evidence. Its
owned Host/SDK/restart processes were independently confirmed absent. A separate
first offline audit of Native02 initially confused the Android nonce and Source
probe nonce. The corrected audit binds the raw SDK-start Source nonce through the
actual SHA-256 identity fields; it passes without changing the runtime or evidence.

## Applied-default compatibility control

`native-applied-restart-v2-control-01` passes with cleanup in 36.601 seconds using
the same current helper and test APK. The actual original Source SDK returns the
1-MiB value and the Host stores applied before client-response loss. After restart,
the unchanged default applied branch returns the exact positive receipt, Android
cleans locally, releases it and leaves an empty v2 ledger. The separate independent
Applied-v2 audit passes against 35 pins. Host 14489/SDK 14513/new Host 14794 and
Android 23938/24341 were confirmed absent. This is deliberately the historical
control ordering (owner stopped before Host), not another Host-first pending proof.
Its new controller/verifier copies preserve all historical controller/audit files.

Android assembly, extension TypeScript, scoped formatting/diff and scoped review
pass. This increment changes only private fixtures/tests/docs; no new full
production unit-suite run is claimed. These are local owned-emulator and real-Pi
cases, not physical Samsung/DeX or independent Linux acceptance, same-question
revival, fresh-session-after-this-cut, or indefinite no-replay guarantees.
The next semantic boundary is genuine owner ACK/SDK acceptance without durable
applied evidence. Standalone filesystem crash points and existing later applied/
release cases remain valid, separately scoped evidence.
