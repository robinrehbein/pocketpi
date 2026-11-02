# Saved editor recovery access and continued work

> **For agentic workers:** Use subagent-driven development with disjoint ownership. Root owns Git and all runtime, builds and tests. Follow the test-first checkpoints below.

**Goal:** Let the Android user inspect and save locally retained editor text after its original question disappeared, then continue explicitly in a newly opened Pi session without replaying an old uncertain submission.

**Architecture:** Read the existing encrypted paged journal through a read-only repository API. A local full-screen recovery dialog is reachable from the Hosts screen even offline; it lists saved drafts and exact uncertain attempts separately, renders only a bounded text window and offers explicit file export. Reopen continues through the existing Android `sessions.open` path; offline sessions intentionally fork into a new session/owner, leaving old journal keys untouched.

**Tech stack:** Existing Kotlin/Compose, encrypted PagedEditorJournal, Android document picker, current Native/RPC Pi/Host fixture. No additional library or protocol capability.

## Design decisions

- Preserve a draft and an uncertain attempt as separate entries even for the same key; their text may differ. Do not merge, clear, delete, resend, migrate or otherwise settle either entry.
- `RemoteRepository.loadEditorRecovery(): EditorRecoveryLoad` is local and read-only. Models in `EditorRecovery.kt`: `EditorRecoveryKind { DRAFT, UNCERTAIN_ATTEMPT }`; `EditorRecoveryEntry(key, kind, text, savedAt, attemptId = null, hostName = null)` with collision-free stable `id`; `EditorRecoveryLoad.Ready(entries)`, `.Unavailable`, `.Failed`. Default interface implementation is Unavailable; production reads `pagedJournal.snapshot()`, propagates coroutine cancellation and represents storage failure explicitly, never as an empty result.
- No network operation, connection or Host authorization is required to read local encrypted content. Missing pairings use a clearly identified fallback host/session label. Metadata labels must not pretend to know an unavailable title.
- Reach the local dialog through a Hosts-screen button tagged `openEditorRecovery`. Keep its presentation outside the remote activation stack. Phone uses list then detail; tablet may use side-by-side panes where useful. Full text is never placed into a TextField, semantics tree or saved-instance Bundle. Use existing EditorWindows (2,048 UTF-16-unit windows), section navigation, read-only preview and bounded current-section clipboard copying.
- Export is an explicit Android CreateDocument action. Freeze the selected entry for the pending operation; never export another newly selected entry if the result arrives later. No automatic sharing/upload. Valid Unicode is streamed as UTF-8 `.txt`; invalid UTF-16 is preserved as BOM + original little-endian code units in `.utf16` binary data, with an explanatory label. Test exact bytes, including surrogate and non-BMP cases. Handle cancellation, unavailable document provider, write failure and lost pending request without false success or clearing journal data. Close owned output streams off the main thread.
- Reopening a history session creates a new ID through normal `sessions.open`. Verify actual old/new IDs and owned PIDs separately, never rewrite the old identity verifier or inject session maps. New editor work is explicit and uses a fresh question/grant/nonce/value; the old uncertain attempt and draft remain unchanged and no old commit is replayed.
- Public paged/outcome gates remain OFF. Wear is unchanged; no Wear emulator or iPhone work. Physical Galaxy and independent authorized Linux host remain separate unavailable acceptance gates.

## Task 1 — read-only local API

Owner: repository author. Files: EditorRecovery.kt, RemoteRepository.kt, DefaultRemoteRepository.kt, focused repository tests.

- [x] Add models/default API and behavior tests before production override. Prove offline access to distinct draft/attempt text, unchanged stored attempts/releases, zero transport activity, storage failure versus real empty data and cancellation propagation. Root runs focused RED.
- [x] Implement the production snapshot projection without mutating the journal or active session. Root runs focused GREEN.

## Task 2 — recovery UI and explicit copy

Owner: UI author. Files: new EditorRecoveryDialog.kt, EditorRecoveryExport.kt, RemoteScreens.kt, RemoteNavigation.kt, English/German editor_recovery.xml, export unit tests and EditorRecoveryUiTest.

- [x] Establish failing tests/stubs for the missing UI/export behavior. Root runs focused RED before implementation.
- [x] Implement offline Hosts entry, loading/error/empty/list/detail states, separate entries, bounded first/last/section navigation, uncertain warning, bounded copy and explicit export lifecycle. Root runs export tests and phone/tablet UI cases, inspects screenshots.
- [ ] Complete real-device/lifecycle export-error qualification. Bounded UI, read-only journal access, stale pending-entry snapshot and invalid Unicode preservation pass their source/unit/emulator checks; real picker cancellation/recreation and controlled late-result/missing-provider cases now pass; write/close failure, interruption during IO and process death remain open.

## Task 3 — actual normal reopen path

Owner: Host fixture author; Android E2E author assigned after API checkpoint. Host changes limited to existing pr-status-android-e2e/run.ts and README; new Android test/controller extend the proven owner-loss scenario.

- [x] Observe the real phone sessions.open request/response and actual new owner. Separate old/new IDs/PIDs/nonces/traces; do not directly create a session as a substitute for the user action.
- [x] After actual old owner loss, open the real offline/history row through Android UI, verify fork and fresh owner, then perform distinct explicit editor work and inspect/export retained old data through the new local UI. Root runs Native and RPC cases with source/APK pins and checks no old write/replay, unchanged old attempt/draft and correct new SDK result.

## Final verification

- [x] Scoped independent code review, applicable unit/UI/integration checks and one final full quick-check after production changes settle. Preserve actual failures and distinguish fixture defects from product defects.
- [x] Record exact evidence and remaining limits; local commits only, full goal active.

## Recorded completion scope

See [qualification](../../testing/editor-recovery-access.md) for the exact final matrix. API/UI/export and actual normal reopen are implemented and locally qualified; the unchecked lifecycle/error matrix and physical/independent-host gates remain open. Local commits do not publish or enable public paged/outcome capabilities.
