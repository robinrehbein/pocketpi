package de.joinnoah.pi.remote

import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class FileDraftLoaderTest {
    private class Storage : FileDraftStorage {
        var drafts = emptyMap<FileDraftKey, StoredFileDraft>()
        var fail = false
        var onSave: ((Map<FileDraftKey, StoredFileDraft>) -> Unit)? = null
        override fun load() = drafts
        override fun save(drafts: Map<FileDraftKey, StoredFileDraft>) {
            if (fail) error("disk failure")
            this.drafts = drafts
            onSave?.invoke(drafts)
        }
    }

    @Test fun pendingIntentIsDurableBeforeTransmissionAndFailurePreventsTransmission() = runTest {
        val storage = Storage()
        val journal = FileDraftJournal(storage, testScheduler.let { StandardTestDispatcher(it) })
        val key = FileDraftKey("host", "session", "a.kt")
        var sends = 0
        var state = RemoteState(connected = true, capabilitiesKnown = true,
            capabilities = setOf(FILES_CAPABILITY, FILES_WRITE_CAPABILITY),
            selection = RemoteSelection("host", "project", "session"),
            files = FilesState("session", editor = FileEditor("a.kt", "base", "old", "new")))
        val source = object : FileSource {
            override suspend fun filesList(sessionId: String, path: String, after: String?) = error("unused")
            override suspend fun filesRead(sessionId: String, path: String, offset: Long, version: String?) = error("unused")
            override suspend fun filesWrite(sessionId: String, path: String, baseVersion: String, content: String, requestId: String): FileWritten {
                assertEquals(PendingWrite(requestId, baseVersion, content), storage.drafts[key]!!.pending.last())
                sends++
                throw RemoteRequestException.unsupported()
            }
        }
        val loader = ProjectFilesLoader(backgroundScope, source, { state }, journal = journal) { state = it(state) }
        storage.fail = true
        loader.saveEditor()
        runCurrent()
        assertEquals(0, sends)
        assertEquals(FilesFailure.STORAGE, state.files!!.editor!!.saveFailure)
        storage.fail = false
        loader.saveEditor()
        runCurrent()
        assertEquals(1, sends)
    }

    @Test fun restartOffersRecoveryWithoutWritingAndRestoresAgainstChangedHost() = runTest {
        val storage = Storage()
        val key = FileDraftKey("host", "session", "a.kt")
        val pending = PendingWrite("request", "base", "sent")
        storage.drafts = mapOf(key to StoredFileDraft("base", "old", "draft", listOf(pending)))
        val journal = FileDraftJournal(storage, StandardTestDispatcher(testScheduler))
        var state = RemoteState(connected = true, capabilitiesKnown = true,
            capabilities = setOf(FILES_CAPABILITY, FILES_WRITE_CAPABILITY),
            selection = RemoteSelection("host", "project", "session"),
            files = FilesState("session", file = OpenFile("a.kt", loading = false, version = "host-new", content = "host changed")))
        val source = object : FileSource {
            override suspend fun filesList(sessionId: String, path: String, after: String?) = error("unused")
            override suspend fun filesRead(sessionId: String, path: String, offset: Long, version: String?) = error("unused")
            override suspend fun filesWrite(sessionId: String, path: String, baseVersion: String, content: String, requestId: String): FileWritten = error("must not replay")
        }
        val loader = ProjectFilesLoader(backgroundScope, source, { state }, journal = journal) { state = it(state) }
        loader.startEditing()
        runCurrent()
        assertEquals("host changed", state.files!!.editor!!.text)
        assertNotNull(state.files!!.editor!!.recovery)
        loader.restoreEditorDraft()
        runCurrent()
        assertEquals("draft", state.files!!.editor!!.text)
        assertEquals("base", state.files!!.editor!!.baseVersion)
        assertEquals(pending, state.files!!.editor!!.unconfirmed)
        assertTrue(state.files!!.editor!!.conflict)
    }

    @Test fun cleanSnapshotsDoNotConsumeCapacity() = runTest {
        val storage = Storage()
        val journal = FileDraftJournal(storage, StandardTestDispatcher(testScheduler))
        var state = RemoteState(selection = RemoteSelection("host", "project", "session"),
            files = FilesState("session", editor = FileEditor("0.kt", "base", "same")))
        val source = object : FileSource {
            override suspend fun filesList(sessionId: String, path: String, after: String?) = error("unused")
            override suspend fun filesRead(sessionId: String, path: String, offset: Long, version: String?) = error("unused")
        }
        val loader = ProjectFilesLoader(backgroundScope, source, { state }, journal = journal) { state = it(state) }
        repeat(9) { index ->
            state = state.copy(files = state.files!!.copy(editor = FileEditor("$index.kt", "base", "same")))
            loader.updateEditorText("same")
            runCurrent()
        }
        assertTrue(storage.drafts.isEmpty())
    }

    @Test fun discardRemovesTextButPreservesUnansweredWrite() = runTest {
        val storage = Storage()
        val key = FileDraftKey("host", "session", "a.kt")
        val pending = PendingWrite("request", "base", "sent")
        storage.drafts = mapOf(key to StoredFileDraft("base", "old", "draft", listOf(pending)))
        val journal = FileDraftJournal(storage, StandardTestDispatcher(testScheduler))
        var state = RemoteState(selection = RemoteSelection("host", "project", "session"),
            files = FilesState("session", editor = FileEditor("a.kt", "base", "old", "draft", unconfirmed = pending)))
        val source = object : FileSource {
            override suspend fun filesList(sessionId: String, path: String, after: String?) = error("unused")
            override suspend fun filesRead(sessionId: String, path: String, offset: Long, version: String?) = error("unused")
        }
        val loader = ProjectFilesLoader(backgroundScope, source, { state }, journal = journal) { state = it(state) }
        loader.discardEditor()
        runCurrent()
        assertEquals("old", storage.drafts[key]!!.text)
        assertEquals(listOf(pending), storage.drafts[key]!!.pending)
    }

    @Test fun lostReplyIsOfferedAfterJournalRecreation() = runTest {
        val storage = Storage()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val key = FileDraftKey("host", "session", "a.kt")
        var state = RemoteState(connected = true, capabilitiesKnown = true,
            capabilities = setOf(FILES_CAPABILITY, FILES_WRITE_CAPABILITY),
            selection = RemoteSelection("host", "project", "session"),
            files = FilesState("session", editor = FileEditor("a.kt", "base", "old", "draft")))
        val source = object : FileSource {
            override suspend fun filesList(sessionId: String, path: String, after: String?) = error("unused")
            override suspend fun filesRead(sessionId: String, path: String, offset: Long, version: String?) = error("unused")
            override suspend fun filesWrite(sessionId: String, path: String, baseVersion: String, content: String, requestId: String): FileWritten = error("reply lost")
        }
        val loader = ProjectFilesLoader(backgroundScope, source, { state }, journal = FileDraftJournal(storage, dispatcher)) { state = it(state) }
        loader.saveEditor()
        runCurrent()
        val pending = storage.drafts[key]!!.pending.single()
        state = state.copy(files = FilesState("session", file = OpenFile("a.kt", loading = false, version = "base", content = "old")))
        val reopened = ProjectFilesLoader(backgroundScope, source, { state }, journal = FileDraftJournal(storage, dispatcher)) { state = it(state) }
        reopened.startEditing()
        runCurrent()
        assertEquals("draft", state.files!!.editor!!.recovery!!.text)
        assertEquals(listOf(pending), state.files!!.editor!!.recovery!!.pending)
    }

    @Test fun rebaseIntentIsDurableBeforeSecondTransmission() = runTest {
        val storage = Storage()
        val key = FileDraftKey("host", "session", "a.kt")
        val journal = FileDraftJournal(storage, StandardTestDispatcher(testScheduler))
        var calls = 0
        var state = RemoteState(connected = true, capabilitiesKnown = true,
            capabilities = setOf(FILES_CAPABILITY, FILES_WRITE_CAPABILITY),
            selection = RemoteSelection("host", "project", "session"),
            files = FilesState("session", editor = FileEditor("a.kt", "base", "old", "first")))
        val source = object : FileSource {
            override suspend fun filesList(sessionId: String, path: String, after: String?) = error("unused")
            override suspend fun filesRead(sessionId: String, path: String, offset: Long, version: String?) =
                FileChunk(path, "host-version", 5, 0, "first", false)
            override suspend fun filesWrite(sessionId: String, path: String, baseVersion: String, content: String, requestId: String): FileWritten {
                assertTrue(storage.drafts[key]!!.pending.contains(PendingWrite(requestId, baseVersion, content)))
                calls++
                when (calls) {
                    1 -> throw RemoteRequestException.timeout()
                    2 -> throw RemoteRequestException("conflict")
                    else -> { assertEquals("host-version", baseVersion); return FileWritten(path, "new-version", content.length.toLong()) }
                }
            }
        }
        val loader = ProjectFilesLoader(backgroundScope, source, { state }, journal = journal) { state = it(state) }
        loader.saveEditor()
        runCurrent()
        loader.updateEditorText("second")
        loader.saveEditor()
        runCurrent()
        assertEquals(3, calls)
    }

    @Test fun recoveryResultForOldRouteCannotAffectReplacementEditor() = runTest {
        val storage = Storage().apply {
            drafts = mapOf(FileDraftKey("host", "session", "a.kt") to StoredFileDraft("base", "old", "draft"))
        }
        val journal = FileDraftJournal(storage, StandardTestDispatcher(testScheduler))
        var state = RemoteState(connected = true, capabilitiesKnown = true,
            capabilities = setOf(FILES_CAPABILITY, FILES_WRITE_CAPABILITY),
            selection = RemoteSelection("host", "project", "session"),
            files = FilesState("session", file = OpenFile("a.kt", loading = false, version = "base", content = "old")))
        val source = object : FileSource {
            override suspend fun filesList(sessionId: String, path: String, after: String?) = error("unused")
            override suspend fun filesRead(sessionId: String, path: String, offset: Long, version: String?) = error("unused")
        }
        val loader = ProjectFilesLoader(backgroundScope, source, { state }, journal = journal) { state = it(state) }
        loader.startEditing()
        state = state.copy(selection = RemoteSelection("other", "project", "session"),
            files = FilesState("session", editor = FileEditor("a.kt", "other-base", "other")))
        runCurrent()
        assertEquals("other", state.files!!.editor!!.text)
        assertNull(state.files!!.editor!!.recovery)
    }

    @Test fun failedDiscardKeepsEditorOpenAndReportsStorageFailure() = runTest {
        val storage = Storage()
        val key = FileDraftKey("host", "session", "a.kt")
        storage.drafts = mapOf(key to StoredFileDraft("base", "old", "draft"))
        val journal = FileDraftJournal(storage, StandardTestDispatcher(testScheduler))
        var state = RemoteState(selection = RemoteSelection("host", "project", "session"),
            files = FilesState("session", editor = FileEditor("a.kt", "base", "old", "draft")))
        val source = object : FileSource {
            override suspend fun filesList(sessionId: String, path: String, after: String?) = error("unused")
            override suspend fun filesRead(sessionId: String, path: String, offset: Long, version: String?) = error("unused")
        }
        val loader = ProjectFilesLoader(backgroundScope, source, { state }, journal = journal) { state = it(state) }
        storage.fail = true
        loader.discardEditor()
        runCurrent()
        assertEquals("draft", state.files!!.editor!!.text)
        assertEquals(FilesFailure.STORAGE, state.files!!.editor!!.saveFailure)
        assertEquals("draft", storage.drafts[key]!!.text)
    }

    @Test fun discardRecoveryUsesCurrentReceiptsRatherThanStaleOffer() = runTest {
        val storage = Storage()
        val key = FileDraftKey("host", "session", "a.kt")
        val old = PendingWrite("old", "base", "first")
        val newer = PendingWrite("new", "base", "second")
        storage.drafts = mapOf(key to StoredFileDraft("base", "old text", "draft", listOf(old)))
        val journal = FileDraftJournal(storage, StandardTestDispatcher(testScheduler))
        var state = RemoteState(connected = true, capabilitiesKnown = true,
            capabilities = setOf(FILES_CAPABILITY, FILES_WRITE_CAPABILITY),
            selection = RemoteSelection("host", "project", "session"),
            files = FilesState("session", file = OpenFile("a.kt", loading = false, version = "base", content = "old text")))
        val source = object : FileSource {
            override suspend fun filesList(sessionId: String, path: String, after: String?) = error("unused")
            override suspend fun filesRead(sessionId: String, path: String, offset: Long, version: String?) = error("unused")
        }
        val loader = ProjectFilesLoader(backgroundScope, source, { state }, journal = journal) { state = it(state) }
        loader.startEditing()
        runCurrent()
        journal.put(key, StoredFileDraft("base", "old text", "draft", listOf(newer)))
        loader.discardEditorDraft()
        runCurrent()
        assertEquals(listOf(newer), storage.drafts[key]!!.pending)
        assertEquals("old text", storage.drafts[key]!!.text)
        assertEquals(newer, state.files!!.editor!!.unconfirmed)
    }

    @Test fun anotherSaveCannotStartDuringDurableAcknowledgement() = runTest {
        val storage = Storage()
        val key = FileDraftKey("host", "session", "a.kt")
        val journal = FileDraftJournal(storage, StandardTestDispatcher(testScheduler))
        var sends = 0
        var state = RemoteState(connected = true, capabilitiesKnown = true,
            capabilities = setOf(FILES_CAPABILITY, FILES_WRITE_CAPABILITY),
            selection = RemoteSelection("host", "project", "session"),
            files = FilesState("session", editor = FileEditor("a.kt", "base", "old", "first")))
        val source = object : FileSource {
            override suspend fun filesList(sessionId: String, path: String, after: String?) = error("unused")
            override suspend fun filesRead(sessionId: String, path: String, offset: Long, version: String?) = error("unused")
            override suspend fun filesWrite(sessionId: String, path: String, baseVersion: String, content: String, requestId: String): FileWritten {
                sends++
                return FileWritten(path, "new-base", content.length.toLong())
            }
        }
        val loader = ProjectFilesLoader(backgroundScope, source, { state }, journal = journal) { state = it(state) }
        var checked = false
        storage.onSave = { drafts ->
            if (!checked && key !in drafts && sends == 1) {
                checked = true
                assertTrue(state.files!!.editor!!.saving)
                loader.updateEditorText("second")
                loader.saveEditor()
            }
        }
        loader.saveEditor()
        runCurrent()
        assertTrue(checked)
        assertEquals(1, sends)
        assertEquals("new-base", storage.drafts[key]!!.baseVersion)
        assertEquals("second", storage.drafts[key]!!.text)
        assertFalse(state.files!!.editor!!.saving)
    }

    @Test fun staleSnapshotCannotRevertRebasedJournal() = runTest {
        val storage = Storage()
        val key = FileDraftKey("host", "session", "a.kt")
        val journal = FileDraftJournal(storage, StandardTestDispatcher(testScheduler))
        var calls = 0
        var state = RemoteState(connected = true, capabilitiesKnown = true,
            capabilities = setOf(FILES_CAPABILITY, FILES_WRITE_CAPABILITY),
            selection = RemoteSelection("host", "project", "session"),
            files = FilesState("session", editor = FileEditor("a.kt", "base", "old", "first")))
        lateinit var loader: ProjectFilesLoader
        val source = object : FileSource {
            override suspend fun filesList(sessionId: String, path: String, after: String?) = error("unused")
            override suspend fun filesRead(sessionId: String, path: String, offset: Long, version: String?): FileChunk {
                loader.updateEditorText("third")
                return FileChunk(path, "host-version", 5, 0, "first", false)
            }
            override suspend fun filesWrite(sessionId: String, path: String, baseVersion: String, content: String, requestId: String): FileWritten {
                calls++
                when (calls) {
                    1 -> throw RemoteRequestException.timeout()
                    2 -> throw RemoteRequestException("conflict")
                    else -> {
                        assertEquals("host-version", storage.drafts[key]!!.baseVersion)
                        assertEquals("third", storage.drafts[key]!!.text)
                        return FileWritten(path, "new-version", content.length.toLong())
                    }
                }
            }
        }
        loader = ProjectFilesLoader(backgroundScope, source, { state }, journal = journal) { state = it(state) }
        loader.saveEditor()
        runCurrent()
        loader.updateEditorText("second")
        loader.saveEditor()
        runCurrent()
        assertEquals(3, calls)
        assertEquals("third", storage.drafts[key]!!.text)
        assertEquals("new-version", storage.drafts[key]!!.baseVersion)
    }

    @Test fun lateWriteReplyDoesNotModifyReplacementAtSameRouteSessionAndPath() = runTest {
        val storage = Storage()
        val journal = FileDraftJournal(storage, StandardTestDispatcher(testScheduler))
        val response = kotlinx.coroutines.CompletableDeferred<FileWritten>()
        var epoch = 1L
        var state = RemoteState(connected = true, capabilitiesKnown = true,
            capabilities = setOf(FILES_CAPABILITY, FILES_WRITE_CAPABILITY),
            selection = RemoteSelection("host", "project", "session"),
            files = FilesState("session", editor = FileEditor("a.kt", "base", "old", "first")))
        val source = object : FileSource {
            override suspend fun filesList(sessionId: String, path: String, after: String?) = error("unused")
            override suspend fun filesRead(sessionId: String, path: String, offset: Long, version: String?) = error("unused")
            override suspend fun filesWrite(sessionId: String, path: String, baseVersion: String, content: String, requestId: String) = response.await()
        }
        val loader = ProjectFilesLoader(backgroundScope, source, { state }, { epoch }, journal) { state = it(state) }
        loader.saveEditor()
        runCurrent()
        epoch++
        state = state.copy(files = state.files!!.copy(editor = FileEditor("a.kt", "replacement", "other", "new edit")))
        response.complete(FileWritten("a.kt", "written", 5))
        runCurrent()
        assertEquals("replacement", state.files!!.editor!!.baseVersion)
        assertEquals("new edit", state.files!!.editor!!.text)
        assertFalse(storage.drafts.containsKey(FileDraftKey("host", "session", "a.kt")))
    }

    @Test fun reopeningSameFileCannotStartAnotherWriteUntilOldReplyIsJournaled() = runTest {
        val storage = Storage()
        val journal = FileDraftJournal(storage, StandardTestDispatcher(testScheduler))
        val oldReply = kotlinx.coroutines.CompletableDeferred<FileWritten>()
        var epoch = 1L
        var sends = 0
        var state = RemoteState(connected = true, capabilitiesKnown = true,
            capabilities = setOf(FILES_CAPABILITY, FILES_WRITE_CAPABILITY),
            selection = RemoteSelection("host", "project", "session"),
            files = FilesState("session", editor = FileEditor("a.kt", "base", "old", "first")))
        val source = object : FileSource {
            override suspend fun filesList(sessionId: String, path: String, after: String?) = error("unused")
            override suspend fun filesRead(sessionId: String, path: String, offset: Long, version: String?) = error("unused")
            override suspend fun filesWrite(sessionId: String, path: String, baseVersion: String, content: String, requestId: String): FileWritten {
                sends++
                return if (sends == 1) oldReply.await() else FileWritten(path, "second-version", content.length.toLong())
            }
        }
        val loader = ProjectFilesLoader(backgroundScope, source, { state }, { epoch }, journal) { state = it(state) }
        loader.saveEditor()
        runCurrent()
        epoch++
        state = state.copy(files = state.files!!.copy(editor = FileEditor("a.kt", "first-version", "first", "second")))
        loader.updateEditorText("second")
        loader.saveEditor()
        runCurrent()
        assertEquals(1, sends)
        assertEquals(FilesFailure.BUSY, state.files!!.editor!!.saveFailure)
        assertEquals("second", state.files!!.editor!!.text)
        oldReply.complete(FileWritten("a.kt", "first-version", 5))
        runCurrent()
        loader.saveEditor()
        runCurrent()
        assertEquals(2, sends)
        assertEquals("second-version", state.files!!.editor!!.baseVersion)
        assertFalse(storage.drafts.containsKey(FileDraftKey("host", "session", "a.kt")))
    }

    @Test fun retryReconcilesConfirmedWriteAfterStorageFailureWithoutResending() = runTest {
        val storage = Storage()
        val key = FileDraftKey("host", "session", "a.kt")
        val journal = FileDraftJournal(storage, StandardTestDispatcher(testScheduler))
        var sends = 0
        var state = RemoteState(connected = true, capabilitiesKnown = true,
            capabilities = setOf(FILES_CAPABILITY, FILES_WRITE_CAPABILITY),
            selection = RemoteSelection("host", "project", "session"),
            files = FilesState("session", editor = FileEditor("a.kt", "base", "old", "first")))
        val source = object : FileSource {
            override suspend fun filesList(sessionId: String, path: String, after: String?) = error("unused")
            override suspend fun filesRead(sessionId: String, path: String, offset: Long, version: String?) = error("unused")
            override suspend fun filesWrite(sessionId: String, path: String, baseVersion: String, content: String, requestId: String): FileWritten {
                sends++
                storage.fail = true
                return FileWritten(path, "written", 5)
            }
        }
        val loader = ProjectFilesLoader(backgroundScope, source, { state }, journal = journal) { state = it(state) }
        loader.saveEditor()
        runCurrent()
        assertEquals(FilesFailure.STORAGE, state.files!!.editor!!.saveFailure)
        assertEquals("written", state.files!!.editor!!.baseVersion)
        assertEquals(1, storage.drafts[key]!!.pending.size)
        loader.saveEditor()
        runCurrent()
        assertEquals(FilesFailure.STORAGE, state.files!!.editor!!.saveFailure)
        assertEquals(1, sends)
        assertEquals(1, storage.drafts[key]!!.pending.size)
        storage.fail = false
        loader.saveEditor()
        runCurrent()
        assertNull(state.files!!.editor!!.saveFailure)
        assertEquals(1, sends)
        assertFalse(storage.drafts.containsKey(key))
    }

    @Test fun discardAfterFailedAcknowledgementDoesNotResurrectDiscardedText() = runTest {
        val storage = Storage()
        val key = FileDraftKey("host", "session", "a.kt")
        val journal = FileDraftJournal(storage, StandardTestDispatcher(testScheduler))
        var sends = 0
        var state = RemoteState(connected = true, capabilitiesKnown = true,
            capabilities = setOf(FILES_CAPABILITY, FILES_WRITE_CAPABILITY),
            selection = RemoteSelection("host", "project", "session"),
            files = FilesState("session", editor = FileEditor("a.kt", "base", "old", "first")))
        val source = object : FileSource {
            override suspend fun filesList(sessionId: String, path: String, after: String?) = error("unused")
            override suspend fun filesRead(sessionId: String, path: String, offset: Long, version: String?) = error("unused")
            override suspend fun filesWrite(sessionId: String, path: String, baseVersion: String, content: String, requestId: String): FileWritten {
                sends++
                storage.fail = true
                return FileWritten(path, "written", 5)
            }
        }
        val loader = ProjectFilesLoader(backgroundScope, source, { state }, journal = journal) { state = it(state) }
        loader.saveEditor()
        runCurrent()
        assertEquals(FilesFailure.STORAGE, state.files!!.editor!!.saveFailure)
        storage.fail = false
        loader.discardEditor()
        runCurrent()
        assertNull(state.files!!.editor)
        assertFalse(storage.drafts.containsKey(key))
        assertEquals(1, sends)
    }

    @Test fun textSnapshotReconcilesFailedAcknowledgementAndKeepsLatestEdit() = runTest {
        val storage = Storage()
        val key = FileDraftKey("host", "session", "a.kt")
        val journal = FileDraftJournal(storage, StandardTestDispatcher(testScheduler))
        var state = RemoteState(connected = true, capabilitiesKnown = true,
            capabilities = setOf(FILES_CAPABILITY, FILES_WRITE_CAPABILITY),
            selection = RemoteSelection("host", "project", "session"),
            files = FilesState("session", editor = FileEditor("a.kt", "base", "old", "first")))
        val source = object : FileSource {
            override suspend fun filesList(sessionId: String, path: String, after: String?) = error("unused")
            override suspend fun filesRead(sessionId: String, path: String, offset: Long, version: String?) = error("unused")
            override suspend fun filesWrite(sessionId: String, path: String, baseVersion: String, content: String, requestId: String): FileWritten {
                storage.fail = true
                return FileWritten(path, "written", 5)
            }
        }
        val loader = ProjectFilesLoader(backgroundScope, source, { state }, journal = journal) { state = it(state) }
        loader.saveEditor()
        runCurrent()
        storage.fail = false
        loader.updateEditorText("second")
        runCurrent()
        val draft = storage.drafts[key]!!
        assertEquals("written", draft.baseVersion)
        assertEquals("first", draft.original)
        assertEquals("second", draft.text)
        assertTrue(draft.pending.isEmpty())
        assertNull(state.files!!.editor!!.saveFailure)
    }

    @Test fun staleRecoveryOfferReconcilesKnownSuccessWithoutReplayingWrite() = runTest {
        val storage = Storage()
        val key = FileDraftKey("host", "session", "a.kt")
        val journal = FileDraftJournal(storage, StandardTestDispatcher(testScheduler))
        var sends = 0
        var state = RemoteState(connected = true, capabilitiesKnown = true,
            capabilities = setOf(FILES_CAPABILITY, FILES_WRITE_CAPABILITY),
            selection = RemoteSelection("host", "project", "session"),
            files = FilesState("session", editor = FileEditor("a.kt", "base", "old", "first")))
        val source = object : FileSource {
            override suspend fun filesList(sessionId: String, path: String, after: String?) = error("unused")
            override suspend fun filesRead(sessionId: String, path: String, offset: Long, version: String?) = error("unused")
            override suspend fun filesWrite(sessionId: String, path: String, baseVersion: String, content: String, requestId: String): FileWritten {
                sends++
                storage.fail = true
                return FileWritten(path, "written", 5)
            }
        }
        val loader = ProjectFilesLoader(backgroundScope, source, { state }, journal = journal) { state = it(state) }
        loader.saveEditor()
        runCurrent()
        val stale = storage.drafts[key]!!
        storage.fail = false
        state = state.copy(files = FilesState("session", file = OpenFile("a.kt", loading = false, version = "written", content = "first"),
            editor = FileEditor("a.kt", "written", "first", recovery = stale)))
        loader.restoreEditorDraft()
        runCurrent()
        assertNull(state.files!!.editor!!.recovery)
        assertEquals("first", state.files!!.editor!!.text)
        assertEquals("written", state.files!!.editor!!.baseVersion)
        assertFalse(storage.drafts.containsKey(key))
        assertEquals(1, sends)
        loader.discardEditor()
        runCurrent()
        loader.startEditing()
        runCurrent()
        assertNull(state.files!!.editor!!.recovery)
        assertEquals(1, sends)
    }

    @Test fun retainedEditorCompletesRecoveryAfterSelectionEpochChanges() = runTest {
        val storage = Storage()
        val key = FileDraftKey("host", "session", "a.kt")
        storage.drafts = mapOf(key to StoredFileDraft("base", "old", "draft"))
        val journal = FileDraftJournal(storage, StandardTestDispatcher(testScheduler))
        var epoch = 1L
        var state = RemoteState(connected = true, capabilitiesKnown = true,
            capabilities = setOf(FILES_CAPABILITY, FILES_WRITE_CAPABILITY),
            selection = RemoteSelection("host", "project", "session"),
            files = FilesState("session", file = OpenFile("a.kt", loading = false, version = "base", content = "old")))
        val source = object : FileSource {
            override suspend fun filesList(sessionId: String, path: String, after: String?) = error("unused")
            override suspend fun filesRead(sessionId: String, path: String, offset: Long, version: String?) = error("unused")
        }
        val loader = ProjectFilesLoader(backgroundScope, source, { state }, { epoch }, journal) { state = it(state) }
        loader.startEditing()
        epoch++
        runCurrent()
        assertFalse(state.files!!.editor!!.recoveryLoading)
        assertEquals("draft", state.files!!.editor!!.recovery!!.text)
        loader.restoreEditorDraft()
        epoch++
        runCurrent()
        assertFalse(state.files!!.editor!!.recoveryLoading)
        assertEquals("draft", state.files!!.editor!!.text)
        loader.discardEditor()
        epoch++
        runCurrent()
        assertNull(state.files!!.editor)
        assertFalse(storage.drafts.containsKey(key))
    }

    @Test fun recoveryLoadCannotModifyReplacementBufferAtSameIdentity() = runTest {
        val storage = Storage()
        storage.drafts = mapOf(FileDraftKey("host", "session", "a.kt") to StoredFileDraft("base", "old", "draft"))
        val journal = FileDraftJournal(storage, StandardTestDispatcher(testScheduler))
        var epoch = 1L
        var state = RemoteState(connected = true, capabilitiesKnown = true,
            capabilities = setOf(FILES_CAPABILITY, FILES_WRITE_CAPABILITY),
            selection = RemoteSelection("host", "project", "session"),
            files = FilesState("session", file = OpenFile("a.kt", loading = false, version = "base", content = "old")))
        val source = object : FileSource {
            override suspend fun filesList(sessionId: String, path: String, after: String?) = error("unused")
            override suspend fun filesRead(sessionId: String, path: String, offset: Long, version: String?) = error("unused")
        }
        val loader = ProjectFilesLoader(backgroundScope, source, { state }, { epoch }, journal) { state = it(state) }
        loader.startEditing()
        epoch++
        state = state.copy(files = state.files!!.copy(editor = FileEditor("a.kt", "replacement", "other")))
        runCurrent()
        assertNull(state.files!!.editor!!.recovery)
        assertEquals("other", state.files!!.editor!!.text)
    }
}
