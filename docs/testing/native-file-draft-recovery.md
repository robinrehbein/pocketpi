# MainActivity file draft recovery against a native Pi Host

This qualifies the manual file-editor workflow through the real Android application, encrypted transport, isolated Mac Host and registered native Pi worker. It extends the earlier storage/loader-only proof. The remote file commands execute in the actual Host against its owned project; no agent model turn is claimed.

## Exercised workflow

Phase A starts the real `RemoteApplication` and exported MainActivity alias from an encrypted paired-route bootstrap. UI actions open the actual project file, replace its text and wait for the production encrypted file-draft journal. Clicking Save transmits a real file write. The fixture suppresses only its successful response after `SessionFiles` has changed the file; the connection otherwise remains active. Further UI typing changes the local draft and is persisted with the unanswered request ID.

The external runner verifies the process, pairing fingerprint and Host audit, force-stops the app and confirms its PID is gone. Phase B launches cold MainActivity through its normal home intent, navigates through project/session/files and reads the current file from the Host. The recovery view displays the remote content, newer local draft and previous unconfirmed save. Restoring sends no write. Keeping the conflict and explicitly clicking Save produces one stale-version rejection followed by one successful rebase against the first write's actual version.

The final Host audit contains exactly three distinct write requests and two successful content changes. Their IDs, contents, base versions, result/error associations and event order are checked both in instrumentation and independently by the runner. A fresh successful Host read precedes the explicit retry. The registered native worker PID/session remain stable across the Android process death.

The original lost-ACK request remains as an unanswered journal/superseded record; only acknowledged requests are cleared. The new journal base/original/text match the final Host version. Recovery does not silently discard unresolved evidence.

## Evidence on 2026-11-01

| Run | Geometry | App PIDs A → B | Duration | Result |
| --- | --- | --- | --- | --- |
| `filedraft-native-phone-02` | 1080×2400, 420 dpi | 29663 → 29933 | 26.427 s | Passed; cleanup passed |
| `filedraft-native-tablet-01` | 1600×2560, 240 dpi | 30343 → 30652 | 29.589 s | Passed; cleanup passed |

Artifacts live in `/private/tmp/pocketpi-paged-production.oge2ybh1` under those labels. Each run freezes 26 source files, runner and build/installed APKs, captures phase proofs, screenshots and ordered Host audits, and verifies source hashes after execution. Independent checks match the pins, mutation/version audit and distinct app PIDs; both owned native worker processes are gone after fixture cleanup. The tablet display override was restored to 1080×2400 at 420 dpi. The actual editor and recovery screenshots were inspected for both layouts.

The earlier `filedraft-native-phone-01` failed at an incorrect final assertion requiring an empty pending journal. It had already completed the two expected Host changes. The corrected assertion requires the exact unanswered original ID/content/base to remain, while checking the final acknowledged version and text. This was a test expectation correction, not a production fix. Its failed artifacts remain available.

## Limits and reproduction

This uses a private Android emulator and isolated local Mac fixture. It does not qualify physical Galaxy S25/Tab S11 hardware, DeX, an independent Linux Host, long background/Doze behavior, immediate last-keystroke survival or all navigation/parking races. No Wear emulator or iPhone work is included. Full-goal completion is not implied.

Build the current app/test APKs and install them only in the explicitly owned emulator. The private runner `/private/tmp/run-pocketpi-file-recovery.py <fresh-label>` validates the AVD and installed APK hashes before clearing the owned test app, starts `--native-worker --native-file-draft-recovery`, executes both instrumentation phases and removes its own fixture state. The tablet wrapper records and restores its display override. These local runner paths require the original private test environment; they are evidence helpers, not general device automation commands.

The final Android quick-check passes in 115 seconds: 3575 app and 34 Wear unit tests, zero failures/errors/skips, resource parity and affected source-set compilation. Host TypeScript and exact-file formatting pass; the 49 targeted file-write/protocol tests pass. No CI status or release is claimed.

## Source refresh after parked-discard correction

The updated production repository/loader were requalified with `filedraft-native-phone-03`
(31.137 s, app PIDs 31202 → 31490) and `filedraft-native-tablet-02` (26.327 s, PIDs 31932 → 32214).
Both pass and clean up; independent verification again matches all 26 source pins, runner/APKs,
Host request/version audit, retained receipt and stopped fixture workers. The tablet display is
restored. These runs cover the existing cold-MainActivity workflow with the changed sources;
the additional parked-discard race is exercised by the deterministic delayed-IO repository tests
in [file draft recovery](android-file-draft-recovery.md), not by an additional UI parking sequence.
