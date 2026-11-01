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

Host/source outcomes are still bounded and held in memory. There is no implemented authenticated durable outcome query proving every pre-restart attempt. Unknown outcomes may therefore remain quarantined indefinitely. Public activation remains blocked on the remaining contract gates; storage success is not proof that an old remote commit failed.

## Local evidence on 2026-11-01

The focused integration run passes 660 tests: 623 repository tests, 29 question-card UI tests and eight journal tests. It covers the persistence barrier, failure refusal, reconstructed fences/new grants, generic fallback and question-type replacement, live Cancel identity, cleanup retry and exact-text storage. A separate ViewModel regression reproduced a retry dropped during reconnect (`expected 1, was 0`) before changing it to the existing loading-safe editor action path.

The opt-in `PagedEditorJournalProcessTest` uses the actual `AppStores` factory, encrypted store and AndroidKeyStore in a nonce-scoped directory. An external runner force-stops the first Android process, verifies no target PID remains and starts a fresh instrumentation process. It checks exact preservation of a 1,048,576-byte submitted value, a 1,048,578-code-unit visible draft containing an unpaired surrogate and NUL, and one uncertain attempt. A replacement grant cannot reserve another attempt for that key. The test removes only its own nonce directory and does not delete a real user receipt.

This process test is a storage proof. It does not exercise a Pi owner, restore runtime authority, prove a complete source-to-Host-to-Android restart transaction, or qualify physical Samsung hardware/DeX. The earlier Native/RPC matrix remains separately scoped to its original source pins.

## Remaining work

The Native and RPC production-chain restart fixtures must use the actual journal rather than the constructor's legacy null-storage fallback. Qualify both a draft interrupted before commit and an uncertain commit after possible dispatch; require exact original-owner/SDK evidence and no replay. Define a correctly scoped outcome/reconciliation contract where a conclusive proof is possible, and retain quarantine otherwise. Complete owner replacement/revocation, quota/backlog lifetime, shared settlement, malformed/boundary, real IME and adverse-timing gates before public enablement. Long-lived pending-retry retention across draft-cache expiry remains part of that resource qualification.

## Final frozen evidence

The final repository quick-check passes in 94 seconds: 3,611 app and 34 Wear tests with zero failures, errors or skips, resource parity and affected source-set compilation. App and instrumentation APK builds pass. Independent scoped reviews found no further material issue in the reviewed storage, authority, UI and retry paths; they do not replace the remaining public-enablement matrix.

The final process evidence is `/private/tmp/pocketpi-paged-journal-process-fajy861x`, nonce `450a944d097e44dab10063b3d2aeff39`, with Android PIDs 747 → 968 and an empty process lookup after force-stop. Both instrumentation phases pass and remove their nonce-scoped directory. Independent checks match eight source/runner hashes, both APKs and the recorded successful phases. App SHA-256 is `2114a3ef4acac369a5e374bce1b55482a784a91eb24821f3e6e76619c77f7a2b`; test APK SHA-256 is `a89a9fb741456b2b1190437cd5935f68e3f269493b4549afa0c2a633c4fec3d6`.

The earlier same-scope process run `pocketpi-paged-journal-process-4mlmmng7` also passed before the ViewModel reconnect guard correction. The final run uses the rebuilt app. Meaningful observed RED cases included missing storage-state UI, enabled bounded-fallback sends, an old Cancel control reaching a replacement question before identity isolation, and local retry not forwarded while reconnecting. Initial missing-API/constant-name/field compile failures are not behavioral proof. After old UI controls were retired, invoking their detached Compose node was correctly refused by the framework; the regression accepts only that specific detached-node refusal and still requires zero answers.
