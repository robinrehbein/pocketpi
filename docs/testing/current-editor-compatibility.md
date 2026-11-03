# Current editor profile and compatibility evidence

Inputs: App `e72269bd`, Host `32bef63d2`, followed only by the two ledger-boundary
tests described below. Public paged/outcome gates remain off. No production code
or Android behavior changes in this qualification increment.

## Actual Native unchanged source

`native-current-unchanged-profile-01` passes with cleanup in 28.191 seconds. The
corrected four-argument Native claim observer records a genuine `paged: true`
claim and matching authority for the actual 1-MiB prefill. All 22 Source chunks
and ACKs are contiguous and total 1,048,576 bytes, followed by one matching commit
and one exact original-owner SDK result. Source and final SHA-256 are identical:
`84ec34b605d28aeb6752c0f3753cefad3378cd459b6e6a060f04d93df8a52019`.
There are zero original local-editor calls. Actual Pi PID 8485/session
`01a19a75-1723-709e-9525-ab98e05bbbad` is preserved in the trace; owned Host 8459
and Pi 8485 were separately confirmed absent. The controller verifies owned
emulator identity, APKs and app-data cleanup.

The new controller `run-pocketpi-current-unchanged.py` preserves the historical
matrix controllers and records 52 current source/APK/helper/controller pins. The
independent `verify-pocketpi-current-unchanged.py` reports PASS, correlating raw
claim/proof authority and aliased transfer identities, offsets, ACKs, SDK identity
and byte/hash equality. Raw source bytes are not separately exported: the fixture
expected hash is independently compared to actual Source ACK/SDK results.

App APK SHA-256:
`97fc0d099b0ced0a7ba63f1f620877948204887559be83d3e8b7cb451d484950`.
Test APK SHA-256:
`b8d79b85ac031ff624a6d9d3f74e9da0070a1808938058b06306d3ba9d29292c`.
This uses NavigationTestActivity/PrStatusTransportEndToEndTest, not MainActivity
lifecycle or a process-death case. Positive negotiation is disabled in this case;
actual encrypted positive recovery has separate Native/RPC evidence. This closes
the selected unchanged Native paged-claim profile gap, not every historical matrix
pin or physical Samsung/independent Linux acceptance.

## Current focused checks

All commands use Node 24.18.0; Android checks use JDK 25. Exactly 81 distinct Host
tests across seven files and 40 distinct Android tests across three classes pass.
The ledger file was rerun after adding two tests; its repeated cases are not counted
twice. Current TypeScript, scoped formatting and diff checks pass.

| Requirement | Current passing test evidence |
| --- | --- |
| Per-device 32 and Host 1,024 admissions | `editor-outcomes_test.ts`: concurrent admission reserves 32 + 992, rejects overflow and overflow after reopen |
| More than 32 ledger cleanup/release cycles | Same file: 40 exact reserve/applied/release cycles, pending protection, wrong-scope/tuple and duplicate release |
| Independent paged/native v3 negotiation and missing/dropped acknowledgment | `editor-paged-negotiation_integration_test.ts`, `paged-editor-protocol_test.ts`, `native-editor-presence_integration_test.ts` |
| Old Host/source behavior | `native-editor-paged_test.ts` and `rpc-editor-runtime_test.ts`: missing registration/bridge/upload ACK and bounded fallback cases |
| Positive owner receipt handling | `editor-upload-daemon_integration_test.ts`: eight scoped current cases; controlled owners, not another actual Pi matrix |
| Default paged gate and legacy fallback | Android RemoteRepositoryTest: `defaultGateDoesNotAdvertiseOrLoadPagedEditor`, `nativeCapabilityRequiresBaseCapabilityAndV3OptIn`, `editorCapabilityIsNeverGrantedByV2Fallback` |
| Durable local retirement/release capacity and bounded rendering | All 20 PagedEditorJournalTest and 11 PagedEditorWireTest cases |
| Exact positive cleanup and legacy unknown fence | Six focused RemoteRepositoryTest cases: exact bound attempt, mismatched/legacy fence, new epoch/stale request, cleanup failure/retry, release after durable removal, route/device isolation |

Logs: `current-compat-host-01` (71 passed), `current-compat-ledger-boundary-01`
(13 ledger tests, including 11 already counted above), `current-compat-positive-host-01`
(8 passed), `current-compat-android-01` (34 passed), and
`current-compat-android-positive-01` (6 passed), under the private qualification lab.
Both Android filtered result summaries are saved separately before XML replacement.
These are component/controlled-integration tests, not 81 genuine SDK edits.

## Aggregate metadata boundary

Two added ledger tests cover a full canonical v2 ledger with 32 devices × 32 entries,
maximal-length escape-heavy opaque IDs, bounded editor IDs, mixed pending/applied
states and maximum wall timestamp. Reopen, exact applied lookup and read-only bytes
pass; another admission is refused. The measured serialized valid shape remains
below 4 MiB. A separate 4-MiB-plus-one regular file fails closed at the existing stat
size guard (`Unsafe outcome ledger`); existing lookup returns unknown and admission
fails without rewriting the file. This is oversized-corruption protection, not an
invented valid 4-MiB admission or a post-stat concurrent-growth test.

The current strict tuple shape and count bound constrain valid encodings before
the aggregate cap. A conservative maximal-escaping individual-entry shape is
3,638 bytes; 1,024 such encodings plus container bytes are 3,726,361 bytes, below
4,194,304. The conceptual repeated maximal shape is an encoding upper-bound
calculation, not an admissible repeated-identity ledger. The actual valid test uses
unique identities and the required device distribution. The explicit aggregate
read/write guard remains important if fields/limits change and for corrupt input.

## Remaining semantic persistence work

Do not erase the historical fourteen Native/RPC value cases or their source pins.
Current large Native edited/recovery, unchanged, and genuine expiry evidence
complement them. Likewise, forty ledger cycles and >32 Android journal retirements
are focused tests; they are not >32 real end-to-end SDK edits.

Actual encrypted Native/RPC cases already cover durable applied before lost reply,
Host restart with persisted applied data, Android death after cleanup/enqueue and
Host deletion/local ACK release cuts. The 25 Host-ledger SIGKILL filesystem points
and nine encrypted AtomicFile cases remain separate focused evidence. The next
smallest concrete gap is actual Host death after durable pending reservation but
before owner dispatch, followed by restart/authenticated exact pending/unknown
lookup and preserved Android fence/text with zero submitted SDK value or replay.
Then separately qualify a genuine owner ACK without durable applied evidence.
Do not turn those semantic gaps into 25 mandatory full Android filesystem sweeps.
Physical Samsung/DeX and independent Linux remain separate unavailable acceptance.
