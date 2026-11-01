# Positive paged-editor outcomes

Status: implemented behind independent private Host and Android gates; production-chain qualification is in progress. Public paged-editor and outcome capabilities remain OFF. This does not complete the [paged editor contract](../plans/2026-11-01-paged-editor-contract.md).

## Binding and storage

Each Native or RPC source editor creates its own random incarnation ID. A separately negotiated source bridge permits that ID to be projected with an authorized upload grant. Android captures the ID and its authenticated paired-device identity with the exact final byte count and SHA-256 before reserving its encrypted attempt. The existing commit request ID is the stable cross-layer commit identity; source transfer IDs and phone upload IDs remain distinct.

The Host reserves a bounded durable entry before dispatching to the original owner. Only a matching original-source ACK with the same incarnation, commit ID, phone upload ID, final size and hash may become `applied`; this state is durably flushed before `accepted`. A normal question close does not invalidate a genuine matching original receipt. A later same-ID replacement cannot supply the old incarnation's proof, but also cannot make a previously genuine original commit un-applied.

The ledger serializes operations and requires the daemon's exclusive socket ownership. It resides in a private socket-specific directory, writes an exclusive temporary file, syncs it, renames it and syncs the directory. A durable provisioning marker detects a missing ledger after initialization; unreadable, malformed, ambiguous, unsafe or incompletely provisioned storage fails closed. Loss of the entire ledger directory and its marker cannot be distinguished from initial creation; missing evidence still yields only `unknown` to Android. A failed post-rename durability step poisons that instance rather than reporting success.

Limits are 32 pending/applied entries per device, 1,024 per Host, four KiB per entry and four MiB for the encoded ledger. It contains metadata, never editor text. There is currently **no automatic expiry**. Pending or unreleased entries retain capacity and admission fails when full. The design's proposed retention expiry is not implemented or qualified.

## Recovery and release

The separately authorized read-only query derives route/device scope from the current authenticated connection. It does not require a live question or contact the owner. Only an exact durable `applied` tuple is positive; pending, absent, corrupt, expired, mismatched and unsupported states are unknown. No negative outcome or commit replay is offered.

Android journal version 2 reads version 1 without discarding its text or fences. Old attempts have no outcome binding and are never inferred from a new question or queried as a new owner. A verified positive response matches the complete persisted tuple and authenticated device/route. Serialized atomic cleanup removes only that exact attempt before an independent, guarded release. Cleanup failure retains the fence and a local retry proof. A release reacquires the current authenticated same route/device after cleanup; navigation during disk IO must not reuse a retired selection epoch. The current connection and selection are checked again at its possible-write boundary.

A successfully verified immutable positive proof can finish local durable cleanup across a later UI navigation; it is not authorization for an owner action. Release remains a separate current-authority operation. Failed or skipped release does not recreate the fence or repeat the commit. Because release retry is not yet durable and expiry is not implemented, process death or a failed release can leave an applied entry occupying Host capacity; this resource/recovery limitation remains a public-enablement gate.

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
