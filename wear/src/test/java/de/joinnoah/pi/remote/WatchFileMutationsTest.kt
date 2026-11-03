package de.joinnoah.pi.remote

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/**
 * The watch file browser stays read-only: the watch builds its repository without a mutation
 * journal, so it neither offers nor sends create, rename or delete, and no watch screen asks for
 * them. A client restriction only; the host authorizes every request itself.
 */
class WatchFileMutationsTest {
    private val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()

    @Test
    fun noWatchSourceOffersOrSendsAFileMutation() {
        assertTrue(sources.isNotEmpty())
        val forbidden = Regex(
            """prepareFile(Create|Rename|Delete)|confirmFileMutation|acknowledgeFileMutation|fileMutationStorage|FileMutationStore|""" +
                """FileMutationController|FileMutationPrompt|FileMutationAccess|session\.files\.(create|rename|delete|mutation)"""
        )
        val offending = sources.filter { forbidden.containsMatchIn(it.readText()) }.map { it.name }
        assertEquals(emptyList<String>(), offending)
    }

    @Test
    fun theWatchRepositoryHasNoMutationJournal() {
        val application = sources.single { it.name == "WearApplication.kt" }.readText()
        assertTrue(application.contains("DefaultRemoteRepository("))
        // Without a journal the repository refuses every mutation as unsupported.
        assertFalse(application.contains("fileMutation"))
        assertNull(RemoteState().fileMutations)
        assertEquals(FileMutationAccess(), FileMutationAccess.of(RemoteState(capabilities = setOf(FILES_CAPABILITY, FILES_CREATE_CAPABILITY,
            FILES_RENAME_CAPABILITY, FILES_DELETE_CAPABILITY, FILES_MUTATION_RECOVERY_CAPABILITY), connected = true,
            selection = RemoteSelection("host", "project", "session"))))
    }
}
