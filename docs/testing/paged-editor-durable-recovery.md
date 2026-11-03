# Durable Android paged-editor recovery

Status: the encrypted Android journal and repository integration are implemented behind the existing disabled public paged-editor gates. This is not public enablement or a complete Native/RPC restart qualification. See the [implementation plan](../plans/2026-11-01-paged-editor-durable-recovery.md) and [binding contract](../plans/2026-11-01-paged-editor-contract.md).

## Data and commit boundaries

`PagedEditorStore` uses a separate `noBackupFilesDir` file and AndroidKeyStore alias through the opt-in unreadable-data preservation mode. The versioned journal stores stable paired route/session/question keys, draft text and uncertain attempt evidence. It never restores a question object, connection generation or callable authorization.

Text is encoded from explicit UTF-16 code units, preserving non-BMP characters, unpaired surrogates and other visible values which cannot currently be submitted. The journal permits 32 drafts, eight MiB of aggregate raw draft code units, four MiB per draft, 32 uncertain attempts and eight MiB of aggregate submitted code units. The complete encoded envelope is capped at 24 MiB. Byte capacity may reject admission before the count limit. Existing records are not evicted to admit another write; malformed, corrupt or unreadable data fail closed.

A reservation atomically persists the exact submitted text and stable attempt/upload/commit evidence before entering the transport's possible-write boundary. The live captured source/grant/selection authority is checked again immediately before sending. A reservation followed by process death is conservatively uncertain even if the process may have died before dispatch.

Matching uncertain records block non-Cancel answers across fresh grants, generic snapshots, changed question types and bounded-editor fallback. While the journal has not loaded or cannot be read/updated, non-Cancel answers fail closed because the affected keys are not known. The UI shows this condition and offers a local storage retry. Genuine Cancel remains subject to current question/selection/connection authority; the journal itself never permits a Cancel or answer.

Draft recovery is offered only after a current source has been verified, and is applied only through explicit user action. A recovered identifier cannot mint a grant or bypass current capabilities. Asynchronous snapshots can lose the newest uncommitted keystrokes on abrupt termination; the exact commit value and fence have a stronger pre-dispatch atomic barrier.

## Known outcomes and local retry

Generic `question.closed` events carry insufficient attempt identity and do not retire durable uncertainty. A live correlated accepted response, or a proved non-dispatch, can retire only its exact attempt after successful atomic cleanup. If cleanup fails, a process-local proof is retained so the local retry can finish disk cleanup without resending the answer. Unknown recovered attempts are never removed merely by retrying storage. Failed draft snapshots are also retried within their existing retention admission.

The local retry remains available during the matching chat's reconnect; it is not forwarded for another chat. Old UI controls are retired when the question object is replaced, even when its JSON contents match. The transport independently checks the current question identity before a genuine Cancel write.

Host/source outcomes are still bounded and held in memory. A separate privately gated [positive-only outcome path](paged-editor-positive-outcomes.md) now exists, but it does not prove every pre-restart attempt or provide a negative outcome. Unknown outcomes may therefore remain quarantined indefinitely. Public activation remains blocked on the remaining contract gates; storage success is not proof that an old remote commit failed.

## Local evidence on 2026-11-01

The focused integration run passes 660 tests: 623 repository tests, 29 question-card UI tests and eight journal tests. It covers the persistence barrier, failure refusal, reconstructed fences/new grants, generic fallback and question-type replacement, live Cancel identity, cleanup retry and exact-text storage. A separate ViewModel regression reproduced a retry dropped during reconnect (`expected 1, was 0`) before changing it to the existing loading-safe editor action path.

The opt-in `PagedEditorJournalProcessTest` uses the actual `AppStores` factory, encrypted store and AndroidKeyStore in a nonce-scoped directory. An external runner force-stops the first Android process, verifies no target PID remains and starts a fresh instrumentation process. It checks exact preservation of a 1,048,576-byte submitted value, a 1,048,578-code-unit visible draft containing an unpaired surrogate and NUL, and one uncertain attempt. A replacement grant cannot reserve another attempt for that key. The test removes only its own nonce directory and does not delete a real user receipt.

This process test is a storage proof. It does not exercise a Pi owner, restore runtime authority, prove a complete source-to-Host-to-Android restart transaction, or qualify physical Samsung hardware/DeX. The earlier Native/RPC matrix remains separately scoped to its original source pins.

## Remaining work

The four scoped Native and RPC production-chain restart cases below use the actual journal. Further qualify unresolved attempts across a new grant and generic close, additional process-death boundaries and longer observation periods. Complete qualification of the separately gated positive-outcome contract where a conclusive proof is possible, and retain quarantine otherwise. Complete owner replacement/revocation, quota/backlog lifetime, shared settlement, malformed/boundary, real IME and adverse-timing gates before public enablement. Long-lived pending-retry retention across draft-cache expiry remains part of that resource qualification.

## Private Native/RPC process-restart evidence

Four isolated emulator runs passed with cleanup and post-run source-pin verification. The evidence directory is `/private/tmp/pocketpi-paged-production.oge2ybh1`; each label identifies its result, phase proofs and preserved Host traces. Durations below are runner-recorded seconds.

| Run label (`paged-restart-` prefix) | Android PID A → B | Duration |
| --- | --- | ---: |
| `native-draft-02` | 3087 → 3619 | 45.646 s |
| `native-lost-ack-01` | 3886 → 4301 | 36.940 s |
| `rpc-draft-01` | 4537 → 4874 | 44.710 s |
| `rpc-lost-ack-01` | 5130 → 5515 | 34.806 s |

The private `NavigationTestActivity` fixture constructs the real repository with the encrypted `AppStores.pagedEditor` in a nonce-scoped directory and connects to actual Native or RPC Pi owners. It edits a window of a 1,048,576-byte source through the UI. The runner observes phase A alive, force-stops it and verifies a fresh phase B process. Native recovery operates within the existing 15-second owner grace. This is not a MainActivity or physical-device/IME qualification.

In both draft cases, the new process accepts a current snapshot, obtains a different grant and reads the source again from the owner. Before and after explicit Restore, Host answer and upload counts remain zero; a subsequent explicit Send delivers the exact 1,048,576-byte edited value to the original owner and SDK. Source SHA-256 is `84ec34b605d28aeb6752c0f3753cefad3378cd459b6e6a060f04d93df8a52019`; edited value SHA-256 is `900dce5cc5d1bc1b5f209c09f600c445e85149e116e2b87542913b07d06f1763`.

In both lost-ACK cases, the fixture verifies the actual owner receipt, drops it, and separately verifies the exact SDK result. Phase B accepts a current snapshot with the settled question absent, retains the exact persisted attempt, and observes no answer or upload replay for three seconds. The external fixture's SDK evidence does not authorize the app to clear the attempt. This does not exercise a new grant or generic close while an attempt remains unresolved, nor establish an authenticated outcome query. The earlier `native-draft-01` JUnit run passed but its runner failed on a `sha(str)` type error; the fresh `native-draft-02` run passed in full.

## Additional tablet-format evidence and checks

The same draft recovery passes on the owned emulator configured to 1600×2560 at density 240: `paged-restart-native-tablet-draft-01` (PIDs 5825 → 6195, 43.736 s) and `paged-restart-rpc-tablet-draft-01` (6568 → 6926, 47.793 s). Both retain the same exact source/final hashes and one 22-chunk commit. The wrapper records the display settings and restores 1080×2400 at density 420 in its `finally` cleanup. The captured phase-A images were inspected for both phone and tablet layouts; they show the bounded editor after the Compose edit, not a hardware-IME proof or post-restart screen.

An independent audit of all six cases matches 30 source pins per case, the runner/helper snapshots and both APKs, the exact receipt/SDK hashes, fresh process identities, draft Restore counters and retained lost-ACK attempt fields. Its artifact is `paged-restart-six-case-independent-audit.json` in the evidence directory. Each case reports successful cleanup; exact process lookup finds none of the six recorded Pi owner PIDs remaining. The six-case test APK SHA-256 is `9d9201507379f246827bae213dc537c8bbb126a430c1b2ad046335ff28b3533a`; the app APK remains the hash recorded below.

On this frozen source, Host TypeScript checking and scoped Biome checking pass. Four source-grant/source-daemon/upload-daemon/native-presence suites pass all 29 tests. The current Android quick-check passes in 10 seconds using up-to-date compilation and unit-test tasks; their input-matching reports contain 3,611 app and 34 Wear tests with no failures, errors or skips. No Wear emulator ran. At this historical frozen checkpoint the positive-outcome contract was a proposal; the later [private implementation](paged-editor-positive-outcomes.md) has its own separate evidence and limits.

## Journal implementation frozen evidence

The final repository quick-check passes in 94 seconds: 3,611 app and 34 Wear tests with zero failures, errors or skips, resource parity and affected source-set compilation. App and instrumentation APK builds pass. Independent scoped reviews found no further material issue in the reviewed storage, authority, UI and retry paths; they do not replace the remaining public-enablement matrix.

The final process evidence is `/private/tmp/pocketpi-paged-journal-process-fajy861x`, nonce `450a944d097e44dab10063b3d2aeff39`, with Android PIDs 747 → 968 and an empty process lookup after force-stop. Both instrumentation phases pass and remove their nonce-scoped directory. Independent checks match eight source/runner hashes, both APKs and the recorded successful phases. App SHA-256 is `2114a3ef4acac369a5e374bce1b55482a784a91eb24821f3e6e76619c77f7a2b`; test APK SHA-256 is `a89a9fb741456b2b1190437cd5935f68e3f269493b4549afa0c2a633c4fec3d6`.

The earlier same-scope process run `pocketpi-paged-journal-process-4mlmmng7` also passed before the ViewModel reconnect guard correction. The final run uses the rebuilt app. Meaningful observed RED cases included missing storage-state UI, enabled bounded-fallback sends, an old Cancel control reaching a replacement question before identity isolation, and local retry not forwarded while reconnecting. Initial missing-API/constant-name/field compile failures are not behavioral proof. After old UI controls were retired, invoking their detached Compose node was correctly refused by the framework; the regression accepts only that specific detached-node refusal and still requires zero answers.
