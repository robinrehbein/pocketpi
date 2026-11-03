# Encrypted journal atomic recovery

Status: locally qualified on the private Android emulator. This change strengthens
shared encrypted storage used by the phone and Wear repositories. It does not
change cipher format, key aliases or public paged-editor/outcome capability gates.
Those gates remain OFF and the full remote-development goal remains incomplete.

## Corrected behavior

Android's platform `AtomicFile.finishWrite` logs some sync/rename failures instead
of throwing them. `EncryptedFileStore` now explicitly syncs the written file
descriptor before finishing and compares the actually committed ciphertext with
the intended bytes. A silent failed replacement is reported as an IO error rather
than durable success. A valid legacy `.bak` is restored even if the main file is
absent. If a backup remains after framework recovery or commit, reading/writing
fails closed while preserving that backup.

An exception during post-commit verification does not prove that the rename was
rolled back. Both `PagedEditorJournal` and `FileDraftJournal` invalidate their
in-memory snapshot after a save exception, under the existing mutex. A later
operation reloads actual storage before another read-modify-write and fails if
that load cannot be verified. It cannot overwrite a newly committed fence from
an old cached snapshot. IO failures do not imply a negative remote outcome.

Root-observed regression sequence: two original tests failed before the initial
fix; three review regressions exposed a post-commit stale cache and unresolved
backup restoration; a fourth related regression reproduced the same cache issue
in the file journal. All six new unit regressions now pass within the full suite.

## Actual Android process matrix

`EncryptedJournalCrashProcessTest` uses the real encrypted store, Android Keystore,
platform `AtomicFile` and file descriptor. Test-only reflection replaces the
private AtomicFile with a delegating subclass. It pauses after a real half-write,
before real `finishWrite`, or after real `finishWrite`. There is no production
fault-injection hook. This is storage qualification: `retire` assumes an already
validated binding; it does not manufacture or establish a remote positive ACK.

Three separate processes seed, write and restore each nonce-scoped journal.
The seed process may exit normally after JUnit completion; the controller verifies
it is gone before starting the writer. The writer emits a synced barrier and
instrumentation status while blocked. The external controller verifies its live
PID and installed APKs, force-stops that exact app and verifies its disappearance.
A fresh restore process checks the complete expected snapshot and the continued
foreign-question fence. Drafts include otherwise invalid UTF-16 code units;
comparison of the decoded snapshot, rather than its diagnostic digest alone,
establishes their preservation.

All nine combinations pass, including cleanup:

| Case | Seed / interrupted writer / restore PIDs | Restored state |
| --- | --- | --- |
| `atomic-journal-reserve-partial-02` | 19807 / 19864 / 19983 | old |
| `atomic-journal-reserve-before-01` | 20190 / 20245 / 20363 | old |
| `atomic-journal-reserve-after-01` | 20556 / 20609 / 20727 | new |
| `atomic-journal-retire-partial-01` | 20913 / 20966 / 21083 | old |
| `atomic-journal-retire-before-01` | 21279 / 21332 / 21447 | old |
| `atomic-journal-retire-after-01` | 21662 / 21715 / 21830 | new |
| `atomic-journal-release-partial-01` | 22014 / 22068 / 22182 | old |
| `atomic-journal-release-before-01` | 22377 / 22429 / 22542 | old |
| `atomic-journal-release-after-01` | 22729 / 22785 / 22896 | new |

`partial` means midway through ciphertext writing; `before` and `after` refer to
platform finishWrite. Before finish, the main ciphertext stays byte-identical to
the seed and the uncommitted `.new` is discarded on recovery. After finish, the
new main ciphertext survives process death. Unrelated pending attempts, queued
releases and exact drafts remain intact. The first `reserve-partial-01` attempt
stopped after a successful seed because the controller incorrectly required the
seed process to remain alive; it is not counted as passing matrix evidence.

The instrumentation arguments are `encryptedJournalCrashPhase=seed|write|restore`,
`encryptedJournalCrashNonce=<32 lowercase hex>`,
`encryptedJournalCrashOperation=reserve|retire|release`, and
`encryptedJournalCrashBoundary=partial-write|before-finish|after-finish`.
The external writer must be stopped at its observed barrier; a successful write
phase intentionally never returns. Use a dedicated, explicitly owned test device.

## Evidence and limits

Private evidence resides in `/private/tmp/pocketpi-paged-production.oge2ybh1`.
`paged-atomic-journal-independent-audit.json` passes all nine unique combinations,
matching eight source/runner/helper snapshots per case, installed/build APK hashes,
three distinct PIDs, force-stop records, JUnit/status, actual old/new ciphertext
files and their partial-write shapes. Current source/build pins also match.
Hashes of source and APK artifacts alone are not a build provenance attestation;
root separately built the APKs from the checked worktree after the full checks.
Plaintext equality is asserted by the successful pinned test, not inferred only
from ciphertext hashes.

The actual Android `EncryptedStoresTest` passes all 12 tests with cleanup. This
includes a real framework rename failure (unlinking the pending file immediately
before real finishWrite) and successful backup-only recovery, not only the
Robolectric model. Evidence is `encrypted-store-framework-01-result.json` and its
log. The full quick-check passes in 89 seconds: 3,642 app plus 34 Wear tests,
zero failures/errors/skips, resource parity and affected source-set compilation.
Both final APK builds pass. No Wear emulator was used.

App APK SHA-256:
`b44e128a1bb1fe996d45bc9aff2ecc513f11f6ad7ad4655ce0cbe5359754d92c`.
Instrumentation APK SHA-256:
`ffedb4c3e89911d54ea6a5508789134f3e8437c14e616dc9caaa1a22a050a5cb`.

This proves the recorded process-death and IO-failure cases on the local emulator,
not power-loss durability or directory sync. Ciphertext readback currently needs
an additional buffer up to the encrypted-file limit; maximum-size physical-device
memory qualification remains open. No remote owner, network operation or authority
is established by this local matrix. Physical Galaxy S25/Tab S11, independent Linux,
owner replacement/shared settlement and the wider contract remain separate gates.
