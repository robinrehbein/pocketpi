# MainActivity cold-process recovery qualification

## Actual recovery path

These cases use the Application-created repository, its actual encrypted pairing/draft/navigation
stores and MainActivity. A debug-only observer decorates the real SecureRemoteTransport to count
possible prompt-write attempts and bounded decoded inbound frames. No repository, lifecycle,
network state or selection is substituted. The scoped fixture CA retains normal hostname checks.

Phase A sends an accepted native prompt whose acknowledgment and source echo are withheld. The
fixture cuts the connection; Android reaches an uncertain pending send. Edited cases change the
actual composer away and back, waiting for each repository update, and persist `promptTextState=edited`.
The actual store is flushed before Home. After real Home/onStop, an app-private proof records the request,
source user ID, native owner, title, app PID and pairing ciphertext hash. The runner verifies the
live PID, raw pairing hash and absent AtomicFile siblings, force-stops A and verifies its absence.
The fixture, native worker and app data remain alive/unchanged between phases.

Phase B consumes a one-use restore preflight tied to A's proof, seven-field fixture identity and
pairing ciphertext. Bootstrap does not read, seed or replace the pairing store before the actual
Application.onCreate constructs its repository and loads it. A normal untargeted launcher opens the computer's projects;
the user path selects the cached project and checks the original session title. The actual draft
journal and ownership are verified while disconnected. After reconnect through the real UI, the
session row opens with Host-confirmed canonical IDs. One exact source receipt settles the pending
send, without a new prompt attempt. Untouched text clears; edit-and-revert text remains, including
after reloading the actual store. All four native owner fields remain unchanged.

Explicit USER_OPEN chat navigation waits for live canonical IDs because it may fork a historical
session. These tests preserve that guard; they do not establish opening the chat offline or
automatically returning to the previous chat. A fresh launcher intentionally opens Projects.

## Final cases

All cases pass with cleanup. Evidence is under `/private/tmp/pocketpi-paged-production.oge2ybh1`.

| Case | Evidence label | App PIDs A → B | Recorded phase duration |
| --- | --- | --- | --- |
| Phone, untouched | `mainactivity-final-process-phone-untouched-01` | 12098 → 12422 | 25.855 s |
| Phone, edited | `mainactivity-final-process-phone-edited-01` | 12706 → 13055 | 27.772 s |
| Tablet layout, untouched | `mainactivity-final-process-tablet-untouched-01` | 13412 → 13756 | 26.782 s |
| Tablet layout, edited | `mainactivity-final-process-tablet-edited-01` | 14127 → 14484 | 27.388 s |

Independent review matched all 21 source snapshots, the runner and built/installed APK hashes
across all four cases, including current-source comparison and post-run hash checks. Each A PID
was independently verified dead before a different B started. Host evidence records one accepted
prompt, queued acknowledgment/echo not sent before death, and the same native worker/session/runtime/project.
B's decoded transport observer records zero matching possible prompt-write attempts; exact source
user ID, request ID, delivery kind and body identify the recovered receipt. A's single possible
write and composer/store outcomes are enforced by the pinned instrumentation assertions.

Both tablet wrappers record 1600 × 2560 at 240 dpi and restoration to 1080 × 2400 at 420 dpi.
Pairing files are preserved between phases, then only the externally verified owned emulator app
is cleared during final cleanup. Secrets are not committed. Restore fingerprints include the
fixture credentials and CA identity; the bootstrap rejects changed ciphertext or `.bak`/`.new` files.

The full required check passes with 3,538 App and 34 Wear results
(`/private/tmp/pocketpi-mainactivity-recovery-final-quick-check.log`, 87 seconds). After the final
test-only edit synchronization, the accepted check passes in 8 seconds using unchanged valid unit
results and compiling the changed test source; final APKs pass
(`/private/tmp/pocketpi-mainactivity-recovery-accepted-quick-check.log` and `-accepted-apks.log`).
Host fixture types and formatting pass separately. Final sources were frozen before these runs.

The existing MainActivity background test also passes unchanged through the extended bootstrap's
default seed mode, without a recovery observer: `production-entry-recovery-compat-phone-01`,
17.405 seconds, cleanup PASS. Independent review matches its 18 source snapshots, runner and
the same installed/build APKs, and verifies the actual connected background delta and same-Activity
return. All seven final process, fresh-client and compatibility cases have independent artifact review.

## Failed setup attempts and limits

`mainactivity-process-phone-untouched-01` restored the app and journal but tried opening an explicit
chat while the fixture was disconnected. The canonical-open guard kept the previous selection, so
the test timed out. The corrected candidate `-02` passed after reconnecting before the UI click;
no production guard was weakened. The first final tablet launch was refused by a private wrapper's
old runner whitelist before changing the display or starting a fixture; only that wrapper was
corrected, and the completed phone cases were retained.

The connection cut precedes Home; this does not prove onStop caused the disconnect. The process
kill is an explicit force-stop followed by a normal cold launcher, with no saved Activity state.
It is not spontaneous OS low-memory task restoration. Evidence uses an owned API-36 emulator,
local Mac Host, deterministic provider and scoped debug trust. Physical Galaxy, production TLS,
FCM, prolonged Doze and independent Linux qualification remain separate. The overall goal is active.
