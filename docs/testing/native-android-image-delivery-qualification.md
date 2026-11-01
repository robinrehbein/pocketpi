# Android image delivery qualification

Status: **four scoped emulator image cases passed, cleanup passed**. The overall remote-development goal remains active.

The fixture exercises the real Android composer and system photo picker on an isolated emulator. It creates an owned 2001 × 1 black PNG in MediaStore, selects it through the picker, and measures the actual JPEG produced by the app importer. The test checks its dimensions, black pixels, byte count and SHA-256 against the native host's attachment upload. It separately checks the SDK-normalized 2000 × 1 model image and its own digest. The source JPEG hash and the normalized model-image hash represent different byte sequences and must not be conflated.

The lost-ACK scenario withholds the prompt acknowledgement and source echo, cuts the connection, and then reconnects. The assertions bind the exact request and source receipt to the uploaded image and verify exactly one matching Android write attempt and one host prompt request. In the edited variant, the user removes image A and selects a distinct image B while offline; after A's receipt, the test checks that B remains in the composer and the flushed draft store.

The diagnostic edited run `image-picker-readiness-phone-edited-01` passed with cleanup. Earlier attempts are part of the record: one edited run completed its body assertions but failed while deleting an already removed owned MediaStore row; a later run timed out importing the second photo; a diagnostic run subsequently failed before opening the second picker because the photo menu was not present after the add action. Test-only cleanup bookkeeping, diagnostics and bounded UI readiness checks were added. These observations do not establish a common cause for the earlier picker-related failures.

## Frozen image matrix

| Layout | Composer after submission | Result / cleanup | Evidence label |
| --- | --- | --- | --- |
| Phone 1080 × 2400 / 420 dpi | Original image | PASS / PASS | `image-enabled-readiness-phone-untouched-01` |
| Phone 1080 × 2400 / 420 dpi | Replacement image | PASS / PASS | `image-enabled-readiness-phone-edited-01` |
| Tablet override 1600 × 2560 / 240 dpi | Original image | PASS / PASS | `image-enabled-readiness-tablet-untouched-01` |
| Tablet override 1600 × 2560 / 240 dpi | Replacement image | PASS / PASS | `image-enabled-readiness-tablet-edited-01` |

The final test waits for the matching nonloading selection and a semantically enabled Add button before one actual touch action. It then checks the single picker selection, enabled Done, application return and actual encrypted-store import. No retry, direct callback, injected picker URI or production behavior change was added. Monotonic stage timestamps and passive screenshots are retained.

An earlier final edited attempt (`image-readiness-final-phone-edited-01`) also failed at the missing menu: the post-tap screenshot showed a reconnect/loading state and disabled Add. The captured observations do not establish whether the state changed before the touch or a menu disappeared afterward. The final passing matrix establishes these cases, not a causal explanation or universal stability claim.

Each private controller retains 37 source snapshots, its own snapshot, installed/build APK hashes, instrumentation result, application proof, final host status, stages and owned-media cleanup evidence. The held native image turn is still running at the final checkpoint: this matrix does not claim Stop, follow-up or agent-turn completion. Tablet display overrides are restored after each run.

Scope is the isolated emulator and fixture used in this turn. No physical Samsung device was tested, and these results do not establish behavior on physical devices or other Android picker implementations.


## Compatibility and checks

`image-enabled-text-process-compat-phone-01` passed both real MainActivity process phases and cleanup with the existing text recovery controller. The image observation decorator remains an explicit test-only bootstrap opt-in; release transport behavior is unchanged.

The frozen Android test APK build passed. `scripts/quick-check.sh` passed with all 98 Gradle tasks up-to-date; existing App/Wear unit results were reused, not newly executed by this test-only change. The native fixture TypeScript and exact-file formatting checks passed. Evidence resides in the private lab `/private/tmp/pocketpi-paged-production.oge2ybh1`; the private lab is not a distributable release artifact.
