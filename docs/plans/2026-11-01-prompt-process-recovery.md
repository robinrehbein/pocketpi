# Prompt Process Recovery Implementation Plan

**Goal:** Resolve an unacknowledged plain prompt from an exact source receipt after app process recreation without losing later composer edits or resending automatically.

**Architecture:** Store independent text, quote, and attachment provenance alongside the send journal and composer in each draft snapshot. Preserve unknown legacy provenance conservatively and retain the current capability and receipt identity checks.

**Tech Stack:** Kotlin, Android encrypted DraftStore, coroutines, JUnit, Android instrumentation.

**Spec:** The requested lost-ACK recovery and user-edit preservation requirements in this task.

## Global Constraints

- Never automatically send a recovered prompt.
- Only a capability-gated source user message with the exact route, session, request ID and prompt delivery resolves the journal.
- Preserve new text, quote and attachments, including an intentional empty value and edits that return to the original value.
- Persist provenance together with the draft snapshot. Legacy data cannot gain ownership from value equality alone; for compatibility an empty legacy composer is still restored, although its prior history is unknowable.
- A recreated repository or encrypted store is not evidence of Android process death.

## Review Focus

- Receipt arrives before capability discovery: existing discovery rescan must settle it.
- Host omits capability or receipt identity mismatches: retain uncertainty and journal.
- Edit-away-back and deliberate deletion: retain the user's composer after reopening.
- Repeated reopen before receipt: retain proven ownership without inferring it from text.
- Old journals without provenance: do not erase matching user text when a receipt arrives.

### Task 1: Reopen regression and durable ownership

**Files:** `src/test/java/de/joinnoah/pi/remote/RemoteRepositoryTest.kt`, `src/main/java/de/joinnoah/pi/remote/DefaultRemoteRepository.kt`, `src/main/java/de/joinnoah/pi/remote/DraftStore.kt`, and encrypted-store instrumentation tests.

- [x] Write tests for a restored unedited composer followed by a reopen and receipt, edit-away-back across reopen, intentional empty across reopen, quote/attachment edits during a send, and legacy matching or empty text on receipt. Assert no automatic `session.prompt`.
- [x] Run the focused tests and verify the first and intentional-empty cases fail for the expected behavioral reason.
- [x] Persist explicit provenance in the same snapshot as text and journal; invalidate it on any real composer edit. Preserve legacy data conservatively and keep existing exact receipt checks.
- [x] Verify encrypted DraftStore serialization and independent store reopening, including legacy data and explicit provenance.
- [x] Run focused tests followed by appropriate repository checks; report their results accurately.

### Task 2: Android process qualification

- [x] In an externally orchestrated two-phase device test, have the relay accept a prompt while withholding its ACK and source echo. Wait for the disconnected state and flush its draft.
- [x] Stop the application process, establish a new process with a different PID, reconnect the same relay, and assert the exact source receipt resolves the journal and untouched composer without a second prompt request.
- [x] Repeat with a flushed edit-away-back, and assert the user's draft survives. Preserve fixture and request-count evidence.

## Result

The regression-first per-field fix, encrypted-store checks, required Android/Wear checks and four actual phone/tablet process-recovery cases pass. See [qualification](../testing/prompt-process-recovery-qualification.md) for precise scope and evidence. The overall remote-development goal remains active.
