# Positive paged-editor outcomes

Status: implemented behind independent private Host and Android gates; production-chain qualification is in progress. Public paged-editor and outcome capabilities remain OFF. This does not complete the [paged editor contract](../plans/2026-11-01-paged-editor-contract.md).

## Binding and storage

Each Native or RPC source editor creates its own random incarnation ID. A separately negotiated source bridge permits that ID to be projected with an authorized upload grant. Android captures the ID and its authenticated paired-device identity with the exact final byte count and SHA-256 before reserving its encrypted attempt. The existing commit request ID is the stable cross-layer commit identity; source transfer IDs and phone upload IDs remain distinct.

The Host reserves a bounded durable entry before dispatching to the original owner. Only a matching original-source ACK with the same incarnation, commit ID, phone upload ID, final size and hash may become `applied`; this state is durably flushed before `accepted`. A normal question close does not invalidate a genuine matching original receipt. A later same-ID replacement cannot supply the old incarnation's proof, but also cannot make a previously genuine original commit un-applied.

The ledger serializes operations and requires the daemon's exclusive socket ownership. It resides in a private socket-specific directory, writes an exclusive temporary file, syncs it, renames it and syncs the directory. A durable provisioning marker detects a missing ledger after initialization; unreadable, malformed, ambiguous, unsafe or incompletely provisioned storage fails closed. Loss of the entire ledger directory and its marker cannot be distinguished from initial creation; missing evidence still yields only `unknown` to Android. A failed post-rename durability step poisons that instance rather than reporting success.

Limits are 32 pending/applied entries per device, 1,024 per Host, four KiB per entry and four MiB for the encoded ledger. It contains metadata, never editor text. There is currently **no automatic expiry**. Pending or unreleased entries retain capacity and admission fails when full. The design's proposed retention expiry is not implemented or qualified.

## Recovery and release

The separately authorized read-only query derives route/device scope from the current authenticated connection. It does not require a live question or contact the owner. Only an exact durable `applied` tuple is positive; pending, absent, corrupt, expired, mismatched and unsupported states are unknown. No negative outcome or commit replay is offered.

Android journal version 3 reads versions 1 and 2 without discarding their text or fences. Old attempts have no outcome binding and are never inferred from a new question or queried as a new owner. A verified positive response matches the complete persisted tuple and authenticated device/route. Serialized atomic cleanup removes only that exact attempt and enqueues its metadata-only release in one durable write before an independent, guarded release. Cleanup failure retains the fence and a local retry proof. A release reacquires the current authenticated same route/device after cleanup; navigation during disk IO must not reuse a retired selection epoch. The current connection and selection are checked again at its possible-write boundary.

A successfully verified immutable positive proof can finish local durable cleanup across a later UI navigation; it is not authorization for an owner action. Release remains a separate current-authority operation. Failed or skipped release does not recreate the fence or repeat the commit. The encrypted journal now retains a metadata-only pending release through process death and retries it on the current authenticated same route/device, with fresh request IDs and a five-second delay after failure. Only a correlated, strictly validated release response permits atomic removal of that exact queued record. Host expiry remains unimplemented; unavailable or no-longer-authorized scopes can still retain capacity and remain part of resource qualification.

## Current qualification limits

Focused tests cover real filesystem reconstruction, admission, corrupt/duplicate/invalid UTF-8 data, ledger loss and interrupted provisioning, exact scope, no deletion after authorization guard refusal, source identity and receipts, legacy migration, strict wire forms, exact Android matching, cleanup failure and navigation races. These tests are not a substitute for the actual Native/RPC, Host-restart, persistence-crash, shared-settlement, owner-replacement/revocation, IME and quota/lifetime matrix.

The initial actual Native runs `paged-restart-native-positive-01` through `-03` are failed qualification attempts, not passing evidence. Phase A verified a real 1 MiB original-owner/SDK commit, durable applied ledger and dropped Host response. The instrumented third run established exact Android proof reception and durable cleanup, but no release after navigation during storage IO; its regression was reproduced in the repository test before the fix. Final passing runtime evidence must be recorded separately after a rebuilt, frozen-source rerun.

## Frozen production-chain evidence

The private evidence directory is `/private/tmp/pocketpi-paged-production.oge2ybh1`. These three rebuilt-source runs pass, including fixture and app cleanup:

| Case | Android PID before/after force-stop | Duration |
| --- | --- | --- |
| `paged-restart-native-positive-04` | 9293 → 9679 | 38.264 s |
| `paged-restart-rpc-positive-01` | 9918 → 10473 | 111.768 s |
| `paged-restart-native-tablet-landscape-positive-01` | 10825 → 11238 | 42.101 s |

Each run makes the real bounded-window Compose edit of a 1 MiB source and verifies one original-owner commit, 22 source chunks and the exact SDK result. The fixture passes the genuine source ACK to the Host, checks its applied disk record and drops only the matching accepted Host response at the authenticated phone outbox. It does not manufacture a positive result. The runner observes phase A alive, force-stops it, verifies its PID is gone and launches a fresh phase B with the same encrypted paired identity.

Phase B receives the exact authenticated positive receipt after a current snapshot omits the settled question. It atomically removes the matching attempt, sends one currently authorized release and receives its result. The captured final ledger is empty. Three seconds of further observation contain no new upload or generic answer; each Host trace still has exactly one commit. All three test Pi owner PIDs are absent after cleanup. These are `NavigationTestActivity` production repository/transport/AppStores proofs, not MainActivity, Host-restart, hardware-IME or physical Samsung tests.

The landscape wrapper records 2560×1600 at density 240 and restores 1080×2400 at density 420 in `finally`. The inspected phase-A image shows the expanded session/chat/Changes layout after settlement; it is not a screenshot of the edit or recovered state.

An independent verifier matches 31 source/snapshot hashes per run, both runner/helper snapshots, APK hashes, fresh Android identities, exact owner/commit/upload/grant binding, real SDK and source receipt, dropped Host response, no-replay counters and empty final ledger plus provisioning marker. Its artifact is `paged-positive-independent-audit.json`. App APK SHA-256: `cd71dddfb2c606001f4b5a4d82fbf0c77444077410caf25af80c56d8ffe8c140`; test APK: `96593998843462a0739568f8d5abb06f3ecf7bd54a95470a88d589a4f97b7ac5`.

The focused frozen run passes 629 repository, 13 journal and 10 wire tests; both APK builds pass. The full Android quick-check passes in 216 seconds with 3,623 app and 34 Wear tests, zero failures/errors/skips, resource parity and affected source-set compilation. No Wear emulator ran. Host TypeScript and scoped Biome checks pass. Independent scoped review found no further material blocker; the explicit remaining qualification gates still apply.

The final complete Remote run on Host `dfc13331c` passes with `--maxWorkers=1`: 4,131 passed, 38 skipped, zero failures (4,169 total). JSON evidence is `/private/tmp/pi-positive-current-remote-serial-final.json`. The earlier default-parallel run had one 240-second PR timeout; its isolated rerun passed in 128 seconds. Nine older fixture failures were independently reproduced on the historical starting commit and corrected without weakening production ownership guards; their five suites pass all 94 tests. This is local validation, not hosted CI or public enablement.

## Durable release queue evidence

The v3 queue holds at most 128 records globally, 32 per route/device, four KiB per entry and 512 KiB of encoded release-array data, within the existing 24 MiB journal envelope. It stores no editor text. An overlapping attempt/release ID or duplicate logical release tuple is rejected. Admission failure retains the attempt and proof. A typed capacity failure permits already-durable releases in the current authenticated scope to drain; after a successful removal the retained retirement is retried. Malformed or unreadable storage does not gain this exception. Global capacity belonging only to other scopes requires an authorized connection to those scopes.

The capacity deadlock was reproduced before the correction. The focused final run passes 635 repository, 19 journal and 11 wire tests; it also covers literal v1/v2/v3 decoding, failed atomic writes, exact stale-record removal, lost-response retry, fresh request IDs, wrong route/device refusal and more than 32 sequential retire/release cycles.

Two actual production-chain runs pass: `paged-restart-native-release-01` (Android PIDs 11669 → 12085, 38.024 s) and `paged-restart-rpc-release-01` (12342 → 12733, 40.663 s). Phase A makes the same exact real 1 MiB source edit, observes the genuine SDK result and dropped Host response, reconnects, verifies the exact positive proof and durably retires the attempt into one pending release. A private transport gate holds every release before its possible-write boundary. The external runner then force-stops this observed live process. Phase B restores and flushes the persisted queue with **zero outcome queries**, zero editor uploads or generic answers, and an empty local attempt/release queue and Host ledger. Three guarded release attempts are observed through staged navigation; this is not a claim that all three reached the Host. The accepted response is correlated with the exact original metadata.

Independent evidence `paged-release-queue-independent-audit.json` matches 31 current source/snapshot pins per case, runner/helper and APK hashes, original SDK/source receipts, the held release barrier, new process identity, exact restored receipt, no-replay counts and empty final ledger with provisioning marker. App SHA-256 is `0c274fbad7b170b91ceb64e6a6b27760e27564fa67ebe3c0700fc001e6fec4c0`; instrumentation APK is `cc1d767a43c85847724f2b703b35e95dd800427ea98d7918a1315339cf48c09b`. Cleanup succeeds.

The complete App quick-check passes in 93 seconds: 3,636 app and 34 Wear tests, zero failures/errors/skips, resource parity and affected source-set compilation; both APK builds pass. The Host is unchanged from its complete 4,131-pass run above. Scoped independent review confirmed the capacity fix. These tests cover Android death before release dispatch. Actual Host-process restart is qualified separately below; disk-crash boundaries, hardware IME, physical Galaxy devices and the remaining full contract gates still require separate qualification. Public gates remain OFF.


## Actual Host-process restart fixture

The opt-in private fixture can persist its actual Host configuration before restart.
The external controller first checks a genuine original-source SDK result against
its session, probe nonce, final size and hash. It binds the still-live SDK PID to
the started Host through its complete parent chain and process start fingerprints.
Pi may replace its command line with the title `pi`; the current controller also
checks the owner's OS-reported canonical working directory against the private
project before signaling. It rechecks the source result, live ownership, process
identity and ancestry immediately before stopping the SDK. The original Host
reaps that child before the controller sends SIGKILL to the Host and reaps it.

A separate Node Host then opens the original socket-scoped ledger and actual
configuration, with the same TLS key/certificate, HTTPS port and paired device
identity. It does not start a Source owner or manufacture a receipt. Android
phase B verifies its encrypted attempt or release queue before connecting, then
authenticates at Host scope without requiring the old session, question or
snapshot to survive. The private fixture restores the paired secret from its
retained private metadata; this does not qualify production Keychain persistence,
automatic Source reattachment or all session restoration.

The initial Native attempt `paged-host-restart-native-positive-01` failed the new
worker's strict private-key mode check. The system LibreSSL command reproduced
mode 0644; explicit mode 0600 on both generated fixture keys fixes creation while
retaining the worker check. RPC attempts `-positive-01` and `-02` stopped before
any process signal because the process title omitted the private path. They are
failed qualification attempts, not passing restart evidence.


Four frozen-source restart cases pass, including cleanup:

| Case | Android PID A → B | Host PID A → B | Duration |
| --- | --- | --- | --- |
| `paged-host-restart-native-positive-02` | 13585 → 13957 | 86880 → 87365 | 36.991 s |
| `paged-host-restart-rpc-positive-03` | 15185 → 15551 | 88343 → 88584 | 35.714 s |
| `paged-host-restart-native-release-01` | 15780 → 16185 | 88628 → 88864 | 36.629 s |
| `paged-host-restart-rpc-release-01` | 16433 → 16837 | 88924 → 89157 | 38.012 s |

Each positive case performs one exact authenticated outcome query and one release.
Each queued-release case performs zero outcome queries and one release. All four
retain the same encrypted Android paired identity across force-stop, observe one
original 1 MiB SDK commit, recover one persisted applied record and finish with
an empty ledger and local attempt/release queue. The new Host dispatches no editor
upload to an owner. The pinned Android test observes zero new upload or generic
answer attempts throughout its final three-second observation period. Configuration,
TLS files and provisioning marker remain byte-identical across the boundary.
All twelve recorded old/new Host and original SDK PIDs are absent after cleanup.

The independent offline auditor passes all four cases in
`paged-host-restart-independent-audit.json`, checking 32 source snapshots per
case, runner/helper snapshots, installed APK hashes, original process evidence,
exact receipts, persisted ledger bytes and instrumentation results. Historical
snapshots remain valid when later runner diagnostics change; current source
matches are reported separately. The original fixture metadata is removed during
cleanup, so its probe nonce is checked by the pinned runtime runner, while the
offline audit can compare only the retained SDK trace. Zero-upload evidence is
from the successful pinned Android instrumentation, rather than a separate Host
counter for every possible phone command.

The unchanged app APK SHA-256 is
`0c274fbad7b170b91ceb64e6a6b27760e27564fa67ebe3c0700fc001e6fec4c0`;
the rebuilt instrumentation APK is
`85f67ddb450217e296bf8272df30f0cdfa49edcfcea2ad5690a0cb41d8d1dee6`.
Both APK builds, Host TypeScript and scoped formatting checks pass. This slice
changes private qualification fixtures, not production App or Host behavior;
the earlier full-suite evidence remains scoped to its recorded commits. Actual
process restart is covered here, but abrupt power loss, persistence crash windows,
production credential restoration, owner reattachment, physical Samsung devices,
independent Linux and the remaining full contract gates are still unqualified.
Public paged-editor and outcome capabilities remain OFF.


## Ledger process-crash boundaries

A separate subprocess matrix exercises the unchanged production
`EditorOutcomeLedger` at 24 checkpoints: before and after temporary-file write,
temporary-file sync, rename and directory sync, for reservation, applied marking
and release. The test-only child installs filesystem wrappers before importing
the production module. Each selected checkpoint occurs around the actual awaited
filesystem call and pauses the operation before its promise can report success.
The parent observes that checkpoint, sends SIGKILL only to its own child, waits
for the recorded exit and opens the remaining ledger with a fresh production
instance. No production fault-injection hook is added.

Before rename, the previous file remains authoritative: reservation has not
appeared, an applied transition remains pending/unknown, or an unreleased applied
record remains positive. After rename, the newly visible file is read. Pending
records remain unknown and continue to reserve their identities. Released records
are absent and remain unknown; the tests never infer a negative editor outcome
or dispatch an owner action. Every case also preserves an exact applied receipt
and an unresolved reservation belonging to two other devices. A 25th process
crash after provisioning-marker sync leaves marker-only storage that refuses to
open, rather than silently initializing an empty ledger.

The final focused run passes all 25 subprocess cases and the 11 existing ledger
tests: 36 passed, zero failed or skipped. Machine-readable evidence is
`/private/tmp/pi-editor-outcomes-crash-final.json`. Host TypeScript, scoped
formatting and independent review pass. No production source behavior changes.
These tests establish process-death behavior and filesystem visibility on the
local test machine. They do not establish power-loss durability, the owner-ACK
barrier, daemon locking, full encrypted Android recovery at these individual
checkpoints or automatic resend safety of an unrelated protocol. The previously
recorded end-to-end cases remain separate evidence. Actual Android death after
Host release deletion before ACK, and after ACK before durable local queue
removal, are qualified separately below; interrupted writes inside Android storage
and the wider contract gates remain separate.


## Android death after Host release and before local cleanup

The private `--paged-editor-release-crash-proof` fixture adds two separately
observed boundaries to the real Native/RPC editor chain. In `host-deleted`, the
Host sees the exact authenticated release request for the original applied tuple,
checks the actual ledger is empty and suppresses its real successful response.
It does not suppress the request or fabricate a result. Only after the external
runner force-stops the observed live Android process does a fixture action allow
subsequent release ACKs. In `ack-before-local`, the Host passes the real response
and the Android observer validates its correlation and strict success shape. A
test-only delegate around the real encrypted store blocks immediately before the
queue-removal save; the external runner then force-stops that live process. No
production fault-injection hook is added.

Both phases use `NavigationTestActivity` with the actual repository, encrypted
transport and AppStores. Before repository creation or connection in phase B,
the test reads and checks the complete surviving encrypted release record,
including the original route, device, owner incarnation, commit/upload/grant,
size and hash. The original attempt is absent. Phase B sends an idempotent
release for that exact record, receives its correlated success and durably removes
it. Observed release request IDs from B are nonempty and disjoint from A; the
accepted ACK ID belongs to the observed B set. Neither an outcome query nor an
editor upload or generic answer is needed.

All four cases pass, including cleanup:

| Case | Android PID A → B | Duration |
| --- | --- | --- |
| `paged-release-crash-native-deleted-01` | 17112 → 17496 | 36.786 s |
| `paged-release-crash-native-local-01` | 17740 → 18125 | 37.187 s |
| `paged-release-crash-rpc-deleted-01` | 18351 → 18729 | 36.501 s |
| `paged-release-crash-rpc-local-01` | 18961 → 19344 | 37.201 s |

The original real 1 MiB SDK commit, 22 chunks and source acknowledgement remain
unchanged across recovery; the actual Host and SDK owner remain alive until
fixture cleanup. Host ledger snapshots before force-stop and after recovery are
empty with valid provisioning markers. The new App process observes zero outcome
queries, uploads or generic answers, and three seconds of further observation
show no replay. All eight recorded fixture Host and SDK PIDs are absent after
cleanup. These cases do not combine the boundary with a Host process restart;
that is covered by the separate matrix above.

Independent evidence `paged-release-crash-independent-audit.json` passes four
cases, checking 31 source/snapshot pins per case, runner/helper and installed APK
hashes, the genuine SDK/source trace, exact stored receipt, boundary-specific ACK
and local-write barriers, fresh request IDs and final cleanup. The pinned runtime
runner compares route/device and SDK probe nonce with actual fixture metadata;
the offline audit cannot independently repeat that comparison after metadata
cleanup. Queue/attempt state and zero-upload assertions are established by the
successful pinned Android test, not by an independent capture of every Host
command. The auditor's initial missing-parenthesis syntax error was corrected
before it ran successfully; it did not change or rerun the runtime cases.

Both APK builds, Host TypeScript, scoped Host formatting and independent scoped
review pass. App APK is unchanged:
`0c274fbad7b170b91ceb64e6a6b27760e27564fa67ebe3c0700fc001e6fec4c0`;
instrumentation APK is
`281c652fdedb7675ba65c52b3bbb8f5187350518484854d0dc611cc5f9b7606a`.
This slice changes test fixtures only. Selected interrupted Android writes are now
qualified in [encrypted journal atomic recovery](encrypted-journal-atomic-recovery.md).
Power loss,
full owner replacement/shared-settlement and resource qualification, physical
Galaxy devices and independent Linux acceptance remain separate open gates.
Public paged-editor and outcome capabilities remain OFF.


## Native lease callbacks and upload reservations

Host `bf9202587` adds focused daemon/LocalPeer integration evidence for two
previously separate paths. The fixture negotiates base, native and paged v3
capabilities over the actual encrypted phone channel and checks the source
registration's upload-bridge acknowledgement rather than manually granting it.
Public capability defaults remain OFF.

The collecting case opens an actual native lease, sends and acknowledges a valid
one-byte phone upload chunk, then withdraws the rendered reader's presence. The
actual reader-revocation callback immediately frees the collecting slot and byte
reservation; old chunks and commit are refused and no owner transfer occurs.
A new question gets a fresh grant and can begin another upload. This is not a
same-question replacement/regrant qualification.

The finalizing case sends the byte through the controlled LocalPeer source and
holds its commit response. A second phone has already negotiated capabilities,
reported active presence and received the native question. Its cancellation during
the in-flight commit is rejected specifically as `outcome_unknown`, with no
second source write. The owner independently releases its actual lease over IPC;
the finalizing slot and one-byte quota remain reserved. Only the matching original
source receipt ends that transaction and frees both reservations.

Both tests pass, together with 29 existing upload/presence/negotiation tests:
31 passed, zero failed or skipped. JSON evidence is
`/private/tmp/pi-native-lease-upload-final.json`; Host TypeScript and scoped
formatting pass. Independent scoped review confirmed the strengthened registration
and competing-reader assertions. The first finalizing attempt timed out because
it sent presence withdrawal through the same phone command queue as the held
commit; the corrected test uses the independent owner IPC release.

These are actual daemon, relay and encrypted phone-channel tests with a controlled
LocalPeer owner. They do not establish a real Pi SDK result or Android workflow
at these exact revocation timings. Full original-owner replacement/revocation,
resource exhaustion/recovery and the remaining production-chain matrix stay open.

## Later production-chain gates, 2026-11-02

[Quota recovery](paged-editor-quota-recovery.md) records actual Native/RPC byte and slot exhaustion, explicit retry and terminal cleanup. [Shared settlement](paged-editor-shared-settlement.md) records separately paired competing cancellation at two explicitly ordered Android/Host commit boundaries. These later cases narrow the earlier open resource and settlement items; they do not establish full owner replacement, arbitrary simultaneous decoding, all authority/lifetime paths or public enablement.
