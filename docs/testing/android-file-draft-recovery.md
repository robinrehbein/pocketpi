# Android file draft recovery

The Android app wires a separate encrypted file-draft journal through `AppStores` and the repository-owned file loader. It protects manual edits independently of chat drafts. This increment targets Galaxy S25 and Galaxy Tab S11; it does not add a watch editor or resume iPhone integration.

## Behavior and limits

Each record belongs to one paired route, session and relative file path. It retains the original text/version, draft text and exact unanswered write requests. Before a file write or rebase retry is transmitted, its intent must be persisted successfully. Journal failures or capacity exhaustion refuse new transmission. Unanswered records are never silently evicted.

The journal permits eight file drafts, sixteen unanswered writes per draft and eight MiB of serialized data. Draft and original text each have a one MiB UTF-8 limit; pending write contents must fit the existing Host write frame. Merely opening a clean file does not consume a draft slot. Corrupt ciphertext, invalid keys and malformed records fail closed: the journal remains available for diagnosis and is not replaced with an empty one. Other encrypted stores retain their existing recovery behavior.

The recovery view blocks editing until an explicit choice. It distinguishes a matching versus changed development-computer file, shows bounded 2,000-character previews of the file, draft and last unconfirmed save, and preserves unanswered records when the draft text is discarded. Restoring sends no request. A later explicit save still uses version checks; a changed remote file is never silently overwritten.

Text snapshots are handed to the loader independently of syntax highlighting. Disk writes remain asynchronous: abrupt process termination can precede the last snapshot. There is no zero-loss guarantee for the last keystrokes. A successful Host save followed by a local storage failure must retain its warning until durable reconciliation succeeds; recovery must not resend that save merely to clear the warning.

## Qualification scope

Unit/Compose coverage exercises encrypted round trips, key/ciphertext preservation, capacity and failed writes, write-before-send barriers including rebase, explicit restoration after changed remote content, selection replacement, per-file in-flight ownership, durable discard, and acknowledgement/snapshot ordering. Existing editor and encrypted-store regression suites also run.

`FileDraftProcessRecoveryTest` runs in two instrumentation invocations separated by an external force-stop. It uses a nonce-scoped `noBackupFilesDir`, the actual `AppStores` encrypted store, a fresh process and the production file loader. The synthetic remote file is changed before recovery; the test checks the retained draft/request and proves no recovery-triggered write. It removes only its own nonce directory. This proves the storage/loader subset across Android processes, not the full MainActivity-to-Pi workflow, physical Samsung hardware or DeX.

Final command results and frozen APK/source identities are recorded in the goal checkpoint. The separate [native MainActivity qualification](native-file-draft-recovery.md) now covers a real local Host with phone/tablet emulator layouts. Physical device review, hardware keyboards, last-keystroke/background lifecycle and broader navigation/parking qualification remain open. No Wear emulator is required.

## Local evidence on 2026-11-01

The final targeted run passes 113 tests across seven suites with zero failures, errors or skips, and builds both app and instrumentation APKs. This includes all 50 existing file-loader tests and 17 existing editor UI tests.

The frozen process run is `/private/tmp/pocketpi-file-draft-process-9p64yo1g`: seed PID 28083, force-stop followed by an empty process lookup, restore PID 28138. Both instrumentation phases pass; the nonce-scoped directory is removed. Independent verification matches nine source/runner hashes and both APK hashes against the evidence manifest:

- App SHA-256: `1ac380d5193b290b883a8e1b60c5d1310a04f7030faad07d80baf9aa4fd0a43d`
- Instrumentation SHA-256: `e180c05ce4c7e5a565b3993b4c52885222019f11ccd2e41d816a0c1d844a5903`

Meaningful RED observations include the missing recovery error view, loss of the storage warning when acknowledgement retry is omitted, and a retained editor stuck loading after an epoch change. Corrupt-key regression also demonstrated that the earlier write path deleted the key even when ciphertext survived; the opt-in preservation path now passes that regression. Initial compile-time missing-API failures are not treated as behavioral proof.

The final repository quick-check passes in 106 seconds: resource parity has zero mismatches; all affected source sets compile; 3573 app and 34 Wear unit tests pass with zero failures, errors or skips. No CI or physical-device result is implied.

## Parked-editor discard completion, 2026-11-01

A repository regression reproduced a completed durable discard whose editor was parked during
journal IO: returning to the session showed the discarded text again with an offline warning.
The corrected completion is matched by route/session/path and exact editor-buffer identity.
It updates the active or parked original editor, keeps a replacement or newer edit, and never
executes an obsolete navigation action after a session switch. A failed discard retains the
text and shows the storage error on return. A direct field edit which precedes its snapshot
watcher is persisted again after the older discard completes.

Seven additional repository cases cover parked success, storage failure, return before IO
completion, newer edits, failure after a newer edit, a replacement editor and direct field edits.
The targeted repository/file-journal run passes 76 tests with zero failures, errors or skips,
and app/instrumentation APK assembly passes. The valid pre-fix RED observation was the old
`discard me` editor returned instead of a closed editor; an earlier test setup timeout did not
count as behavior evidence. A later compilation error from an unnecessary test import was fixed
before the passing run. Independent static review found no remaining clear blocker in the
changed completion paths.

These deterministic delayed-IO tests exercise the actual repository and its parked-state cache.
They do not establish every navigation/parking race or zero-loss recent typing. Public paged
editor capability remains disabled; its separate restart work is planned in
[paged durable recovery](../plans/2026-11-01-paged-editor-durable-recovery.md).

Final quick-check after the parked-editor correction passes in 101 seconds: 3582 app and 34 Wear tests, zero failures/errors/skips, resource parity and all affected source-set compilation. Current native phone/tablet workflow repetitions pass as recorded in the linked native qualification. Physical Samsung/DeX and comprehensive parking/lifecycle coverage remain separate.
