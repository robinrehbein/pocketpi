package de.joinnoah.pi.remote

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Contract tests for durable client recovery; host deduplication is tested separately. */
class FileMutationJournalTest {
    private val request = FileMutationRequest(
        routeId = "route-a", deviceId = "device-a", sessionId = "session-a",
        operationId = "operation-a", epoch = "epoch-a", kind = FileMutationKind.CREATE, path = "folder/new.txt",
    )
    private val key = FileMutationKey("device-a", "operation-a")

    private fun journal(storage: FileMutationStorage, capacity: Int = 8) =
        FileMutationJournal(storage, Dispatchers.Unconfined, capacity)

    @Test fun reservationIsPersistedBeforeItReturnsAndSurvivesReopening() = runBlocking {
        val storage = MemoryStorage()
        val reserved = journal(storage).reserve(request)
        val entry = FileMutationEntry(request, FileMutationState.RESERVED)
        assertEquals(FileMutationReservation.New(entry), reserved)
        assertEquals(entry, storage.entries[key])
        assertEquals(listOf(entry), journal(storage).pending("route-a", "device-a", "session-a"))
    }

    @Test fun duplicateReservationPreservesOriginalStateAndIdentity() = runBlocking {
        val storage = MemoryStorage()
        val journal = journal(storage)
        journal.reserve(request)
        journal.markUnknown(key)
        assertEquals(FileMutationReservation.Existing(FileMutationEntry(request, FileMutationState.UNKNOWN)), journal.reserve(request))
        assertEquals(FileMutationEntry(request, FileMutationState.UNKNOWN), storage.entries[key])
    }

    @Test fun reusedDeviceAndOperationIdRejectsEveryChangedRequestField() = runBlocking {
        val storage = MemoryStorage()
        val journal = journal(storage)
        journal.reserve(request)
        val changed = listOf(
            request.copy(routeId = "route-b"), request.copy(sessionId = "session-b"),
            request.copy(kind = FileMutationKind.DELETE), request.copy(path = "another.txt"),
            request.copy(destinationPath = "other.txt"), request.copy(baseVersion = "version-b"),
            request.copy(epoch = "epoch-b"),
        )
        for (other in changed) {
            expectFailure { journal.reserve(other) }
            assertEquals(FileMutationEntry(request, FileMutationState.RESERVED), storage.entries[key])
        }
    }

    @Test fun pendingRecoveryIsScopedToRouteDeviceAndSession() = runBlocking {
        val storage = MemoryStorage()
        val journal = journal(storage)
        val others = listOf(
            request.copy(routeId = "route-b", operationId = "other-route"),
            request.copy(deviceId = "device-b", operationId = "other-device"),
            request.copy(sessionId = "session-b", operationId = "other-session"),
        )
        journal.reserve(request)
        others.forEach { journal.reserve(it) }
        assertEquals(listOf(FileMutationEntry(request, FileMutationState.RESERVED)), journal.pending("route-a", "device-a", "session-a"))
        assertEquals(4, storage.entries.size)
    }

    @Test fun acknowledgingUnknownHidesPromptButRetainsIdentityAcrossRestart() = runBlocking {
        val storage = MemoryStorage()
        val journal = journal(storage)
        journal.reserve(request)
        journal.markUnknown(key)
        journal.acknowledgeUnknown(key)
        val reopened = this@FileMutationJournalTest.journal(storage)
        assertTrue(reopened.pending("route-a", "device-a", "session-a").isEmpty())
        assertEquals(FileMutationReservation.Existing(FileMutationEntry(request, FileMutationState.ACKNOWLEDGED_UNKNOWN)), reopened.reserve(request))
        expectFailure { reopened.reserve(request.copy(path = "different.txt")) }
    }

    @Test fun terminalResultsHidePendingButRetainIdentityAndReceiptAcrossRecreation() = runBlocking {
        val storage = MemoryStorage()
        val first = journal(storage)
        val refusedRequest = request.copy(operationId = "refused", path = "folder/refused.txt")
        val other = request.copy(operationId = "still-pending", sessionId = "session-b")
        first.reserve(request)
        first.reserve(refusedRequest)
        first.reserve(other)
        first.complete(request, FileMutationResult.Applied)
        first.complete(refusedRequest, FileMutationResult.Refused("conflict"))

        val reopened = journal(storage)
        assertTrue(reopened.pending("route-a", "device-a", "session-a").isEmpty())
        assertEquals(listOf(FileMutationEntry(other, FileMutationState.RESERVED)), reopened.pending("route-a", "device-a", "session-b"))
        assertEquals(
            FileMutationReservation.Existing(FileMutationEntry(request, FileMutationState.TERMINAL, FileMutationResult.Applied)),
            reopened.reserve(request),
        )
        assertEquals(
            FileMutationReservation.Existing(FileMutationEntry(refusedRequest, FileMutationState.TERMINAL, FileMutationResult.Refused("conflict"))),
            reopened.reserve(refusedRequest),
        )
        expectFailure { reopened.reserve(request.copy(routeId = "route-b")) }
        expectFailure { reopened.reserve(request.copy(sessionId = "session-b")) }
        expectFailure { reopened.reserve(request.copy(path = "folder/different.txt")) }
    }

    @Test fun terminalReceiptRequiresMatchingRequestAndCannotBeChanged() = runBlocking {
        val storage = MemoryStorage()
        val journal = journal(storage)
        journal.reserve(request)
        expectFailure { journal.complete(request.copy(path = "folder/different.txt"), FileMutationResult.Applied) }
        expectFailure { journal.complete(request.copy(sessionId = "session-b"), FileMutationResult.Applied) }
        expectFailure { journal.complete(request.copy(operationId = "missing"), FileMutationResult.Applied) }
        assertEquals(FileMutationEntry(request, FileMutationState.RESERVED), storage.entries[key])
        journal.markUnknown(key)
        journal.complete(request, FileMutationResult.Refused("conflict"))
        journal.complete(request, FileMutationResult.Refused("conflict"))
        expectFailure { journal.complete(request, FileMutationResult.Applied) }
        expectFailure { journal.complete(request, FileMutationResult.Refused("different")) }
        assertEquals(FileMutationEntry(request, FileMutationState.TERMINAL, FileMutationResult.Refused("conflict")), storage.entries[key])
    }

    @Test fun invalidRefusalReasonsCannotBeCompletedOrPersisted() = runBlocking {
        val invalidReasons = listOf("", "bad\nreason", "é".repeat(513))
        for (reason in invalidReasons) {
            val storage = MemoryStorage()
            val journal = journal(storage)
            journal.reserve(request)
            val before = storage.entries
            val writesBefore = storage.saveCalls
            expectFailure { journal.complete(request, FileMutationResult.Refused(reason)) }
            assertEquals(before, storage.entries)
            assertEquals(writesBefore, storage.saveCalls)
        }
    }

    @Test fun invalidLoadedRefusalReasonsFailClosedWithoutWriting() = runBlocking {
        val invalidReasons = listOf("", "bad\nreason", "é".repeat(513))
        for (reason in invalidReasons) {
            val storage = MemoryStorage().apply {
                entries = mapOf(key to FileMutationEntry(request, FileMutationState.TERMINAL, FileMutationResult.Refused(reason)))
            }
            val before = storage.entries
            val journal = journal(storage)
            expectFailure { journal.reserve(request) }
            expectFailure { journal.pending("route-a", "device-a", "session-a") }
            expectFailure { journal.reserve(request.copy(operationId = "new-operation")) }
            assertEquals(before, storage.entries)
            assertEquals(0, storage.saveCalls)
        }
    }

    @Test fun capacityIncludesAcknowledgedEntriesAndNeverEvictsThem() = runBlocking {
        val storage = MemoryStorage()
        val journal = journal(storage, capacity = 1)
        journal.reserve(request)
        journal.markUnknown(key)
        journal.acknowledgeUnknown(key)
        expectFailure { journal.reserve(request.copy(operationId = "next")) }
        assertEquals(mapOf(key to FileMutationEntry(request, FileMutationState.ACKNOWLEDGED_UNKNOWN)), storage.entries)
    }

    @Test fun saveFailureDoesNotReturnAReservation() = runBlocking {
        val storage = MemoryStorage().apply { failSave = true }
        val journal = journal(storage)
        expectFailure { journal.reserve(request) }
        assertTrue(storage.entries.isEmpty())
        storage.failSave = false
        assertEquals(FileMutationReservation.New(FileMutationEntry(request, FileMutationState.RESERVED)), journal.reserve(request))
    }

    @Test fun saveMayCommitBeforeFailureAndNextOperationMustReload() = runBlocking {
        val storage = MemoryStorage().apply { failAfterCommit = true }
        val journal = journal(storage)
        expectFailure { journal.reserve(request) }
        val other = request.copy(operationId = "other", path = "other.txt")
        journal.reserve(other)
        assertEquals(setOf(key, FileMutationKey("device-a", "other")), storage.entries.keys)
        assertEquals(request, storage.entries[key]?.request)
    }

    @Test fun unreadableOrMissingProvisionedStorageFailsClosed() = runBlocking {
        val storage = MemoryStorage().apply { failLoad = true }
        val journal = journal(storage)
        expectFailure { journal.reserve(request) }
        expectFailure { journal.pending("route-a", "device-a", "session-a") }
        assertTrue(storage.entries.isEmpty())
        assertEquals(0, storage.saveCalls)
    }

    @Test fun existingReservedAfterRestartDoesNotGrantDispatch() = runBlocking {
        val storage = MemoryStorage()
        assertTrue(journal(storage).reserve(request) is FileMutationReservation.New)
        val recovered = journal(storage).reserve(request)
        assertEquals(FileMutationReservation.Existing(FileMutationEntry(request, FileMutationState.RESERVED)), recovered)
    }

    @Test fun twoInstancesCannotOverwriteEachOthersReservations() = runBlocking {
        val storage = MemoryStorage()
        val first = journal(storage)
        val second = journal(storage)
        first.pending("route-a", "device-a", "session-a")
        second.pending("route-a", "device-a", "session-a")
        first.reserve(request)
        val other = request.copy(operationId = "other", path = "other.txt")
        try { second.reserve(other) } catch (_: Exception) { /* Rejecting a second owner is safe. */ }
        assertEquals(request, storage.entries[key]?.request)
        val secondEntry = storage.entries[FileMutationKey("device-a", "other")]
        if (secondEntry != null) assertEquals(other, secondEntry.request)
    }

    @Test fun differentStorageWrappersForSameBackendCannotLoseConcurrentReservations() = runBlocking {
        val backend = SharedBackend()
        val other = request.copy(operationId = "other", path = "other.txt")
        val firstResult = async(Dispatchers.IO) { runCatching { FileMutationJournal(BackendStorage(backend), Dispatchers.IO, 8).reserve(request) } }
        val secondResult = async(Dispatchers.IO) { runCatching { FileMutationJournal(BackendStorage(backend), Dispatchers.IO, 8).reserve(other) } }
        val results = listOf(firstResult.await(), secondResult.await())
        assertTrue("At least one writer must succeed", results.any { it.isSuccess })
        if (results[0].isSuccess) assertEquals(request, backend.entries[key]?.request)
        if (results[1].isSuccess) assertEquals(other, backend.entries[FileMutationKey("device-a", "other")]?.request)
    }

    @Test fun equalButDistinctBackendIdentitiesShareTheConcurrencyLock() = runBlocking {
        val backend = SharedBackend()
        val firstIdentity = String(charArrayOf('j', 'o', 'u', 'r', 'n', 'a', 'l'))
        val secondIdentity = String(charArrayOf('j', 'o', 'u', 'r', 'n', 'a', 'l'))
        assertEquals(firstIdentity, secondIdentity)
        assertNotSame(firstIdentity, secondIdentity)
        val other = request.copy(operationId = "other", path = "other.txt")
        val firstResult = async(Dispatchers.IO) {
            runCatching { FileMutationJournal(BackendStorage(backend, firstIdentity), Dispatchers.IO, 8).reserve(request) }
        }
        val secondResult = async(Dispatchers.IO) {
            runCatching { FileMutationJournal(BackendStorage(backend, secondIdentity), Dispatchers.IO, 8).reserve(other) }
        }
        val results = listOf(firstResult.await(), secondResult.await())
        assertTrue("At least one writer must succeed", results.any { it.isSuccess })
        if (results[0].isSuccess) assertEquals(request, backend.entries[key]?.request)
        if (results[1].isSuccess) assertEquals(other, backend.entries[FileMutationKey("device-a", "other")]?.request)
    }

    private class SharedBackend {
        val simultaneousLoads = CountDownLatch(2)
        @Volatile var entries: Map<FileMutationKey, FileMutationEntry> = emptyMap()
    }

    private class BackendStorage(private val backend: SharedBackend, private val identity: Any = backend) : FileMutationStorage {
        override val lockIdentity: Any get() = identity
        override fun load(): Map<FileMutationKey, FileMutationEntry> {
            val snapshot = backend.entries.toMap()
            backend.simultaneousLoads.countDown()
            // Allow an unprotected competing wrapper to read the same stale snapshot.
            backend.simultaneousLoads.await(1, TimeUnit.SECONDS)
            return snapshot
        }
        override fun save(entries: Map<FileMutationKey, FileMutationEntry>) {
            backend.entries = entries.toMap()
        }
    }

    @Test fun invalidRequestsFailBeforeWriting() = runBlocking {
        val invalid = listOf(
            request.copy(routeId = ""), request.copy(deviceId = "device\nname"),
            request.copy(sessionId = "s".repeat(1025)), request.copy(operationId = " "),
            request.copy(path = "/absolute"), request.copy(path = "a/../b"),
            request.copy(path = "a//b"), request.copy(path = "a\\b"),
            request.copy(path = "a/./b"), request.copy(path = "a/\nb"),
            request.copy(path = "a".repeat(4097)),
            request.copy(destinationPath = "unexpected"), request.copy(baseVersion = "unexpected"),
            request.copy(kind = FileMutationKind.RENAME),
            request.copy(kind = FileMutationKind.RENAME, destinationPath = "folder/other.txt"),
            request.copy(kind = FileMutationKind.RENAME, baseVersion = "v1"),
            request.copy(kind = FileMutationKind.RENAME, destinationPath = "folder/new.txt", baseVersion = "v1"),
            request.copy(kind = FileMutationKind.RENAME, destinationPath = "another/other.txt", baseVersion = "v1"),
            request.copy(kind = FileMutationKind.DELETE),
            request.copy(kind = FileMutationKind.DELETE, baseVersion = "v1", destinationPath = "other"),
        )
        for (bad in invalid) {
            val storage = MemoryStorage()
            expectFailure { journal(storage).reserve(bad) }
            assertTrue(storage.entries.isEmpty())
            assertEquals(0, storage.saveCalls)
        }
    }

    @Test fun invalidLoadedEntryFailsClosedBeforeAnySave() = runBlocking {
        val storage = MemoryStorage().apply {
            entries = mapOf(key to FileMutationEntry(request.copy(path = "../escape"), FileMutationState.RESERVED))
        }
        val original = storage.entries
        expectFailure { journal(storage).reserve(request.copy(operationId = "next")) }
        assertEquals(original, storage.entries)
        assertEquals(0, storage.saveCalls)
    }

    @Test fun loadedStateOverCapacityFailsClosedEvenForDuplicateLookup() = runBlocking {
        val other = request.copy(operationId = "other", path = "other.txt")
        val storage = MemoryStorage().apply {
            entries = mapOf(
                key to FileMutationEntry(request, FileMutationState.RESERVED),
                FileMutationKey("device-a", "other") to FileMutationEntry(other, FileMutationState.UNKNOWN),
            )
        }
        val original = storage.entries
        val journal = journal(storage, capacity = 1)
        expectFailure { journal.reserve(request) }
        expectFailure { journal.pending("route-a", "device-a", "session-a") }
        assertEquals(original, storage.entries)
        assertEquals(0, storage.saveCalls)
    }

    @Test fun lostEpochStaysUnresolvedUntilAcknowledgedAndNeverBecomesTerminal() = runBlocking {
        val storage = MemoryStorage()
        val journal = journal(storage)
        journal.reserve(request)
        journal.markLost(key)
        assertEquals(listOf(FileMutationEntry(request, FileMutationState.LOST)), journal.pending("route-a", "device-a", "session-a"))
        // An unacknowledged lost outcome is kept; forgetting it is refused.
        expectFailure { journal.forget(key) }
        journal.acknowledgeUnknown(key)
        assertEquals(FileMutationState.ACKNOWLEDGED_UNKNOWN, storage.entries.getValue(key).state)
        assertTrue(journal.pending("route-a", "device-a", "session-a").isEmpty())
        expectFailure { journal.reserve(request.copy(path = "another.txt")) }
    }

    @Test fun onlyTerminalReceiptsAreForgotten() = runBlocking {
        val storage = MemoryStorage()
        val journal = journal(storage)
        journal.reserve(request)
        expectFailure { journal.forget(key) }
        journal.complete(request, FileMutationResult.Applied)
        journal.forget(key)
        assertTrue(storage.entries.isEmpty())
        journal.forget(key)
    }

    @Test fun acknowledgedEntriesCanBeDroppedButUnknownOnesCannot() = runBlocking {
        val storage = MemoryStorage()
        val journal = journal(storage)
        journal.reserve(request)
        journal.markUnknown(key)
        expectFailure { journal.forget(key) }
        journal.acknowledgeUnknown(key)
        journal.forget(key)
        assertTrue(storage.entries.isEmpty())
        val other = request.copy(operationId = "other", path = "other.txt")
        journal.reserve(other)
        journal.markUnknown(FileMutationKey("device-a", "other"))
        journal.forgetAcknowledged("route-a", "device-a", "session-a")
        assertEquals(FileMutationState.UNKNOWN, storage.entries.values.single().state)
    }

    private suspend fun expectFailure(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected an exception")
        } catch (expected: Exception) {
            // A rejected operation must not be mistaken for success.
        }
    }

    private class MemoryStorage : FileMutationStorage {
        override val lockIdentity: Any get() = this
        var entries: Map<FileMutationKey, FileMutationEntry> = emptyMap()
        var failLoad = false
        var failSave = false
        var failAfterCommit = false
        var saveCalls = 0
        override fun load(): Map<FileMutationKey, FileMutationEntry> {
            if (failLoad) throw IOException("unavailable, corrupt, or missing provisioned journal")
            return entries.toMap()
        }
        override fun save(entries: Map<FileMutationKey, FileMutationEntry>) {
            saveCalls++
            if (failSave) throw IOException("write failed")
            this.entries = entries.toMap()
            if (failAfterCommit) {
                failAfterCommit = false
                throw IOException("verification failed after commit")
            }
        }
    }
}
