package de.joinnoah.pi.remote

import org.junit.Assert.*
import org.junit.Test

class RemoteRequestExceptionTest {
    @Test
    fun hostCodesAreHostErrorsExceptUnsupported() {
        val trust = RemoteRequestException("trust_required")
        assertEquals(RemoteRequestException.Kind.HOST_ERROR, trust.kind)
        assertEquals("trust_required", trust.hostCode)
        assertTrue(trust.answered)
        assertFalse(trust.isUnsupported)

        // The host's own `unsupported` and the app's capability check mean the same.
        for (unsupported in listOf(RemoteRequestException("unsupported"), RemoteRequestException.unsupported())) {
            assertEquals(RemoteRequestException.Kind.UNSUPPORTED, unsupported.kind)
            assertTrue(unsupported.isUnsupported)
            assertTrue(unsupported.answered)
        }
    }

    @Test
    fun requestsWithoutAReplyKeepTheirMessagesButNoHostCode() {
        val timeout = RemoteRequestException.timeout()
        assertEquals(RemoteRequestException.Kind.TIMEOUT, timeout.kind)
        assertEquals("Request timed out", timeout.message)
        assertNull(timeout.hostCode)
        assertFalse(timeout.answered)

        val lost = RemoteRequestException.connectionLost()
        assertEquals(RemoteRequestException.Kind.CONNECTION_LOST, lost.kind)
        assertEquals("Connection lost", lost.message)
        assertNull(lost.hostCode)
        assertFalse(lost.answered)

        val other = IllegalStateException("unsupported")
        assertNull(other.requestKind)
        assertNull(other.hostCode)
        assertFalse(other.isUnsupported)
    }

    @Test
    fun hostCodesStillReachTheirHandlers() {
        assertEquals(
            R.string.remote_folders_error_trust_required,
            folderErrorMessage(RemoteRequestException("trust_required"), FolderCommand.BROWSE),
        )
        assertEquals(R.string.providers_error_in_progress, providerErrorMessage(RemoteRequestException("login_in_progress")))
        assertEquals(R.string.providers_error_unsupported, providerErrorMessage(RemoteRequestException.unsupported()))
        assertEquals(R.string.remote_request_error, providerErrorMessage(RemoteRequestException.connectionLost()))
        assertEquals(TreeFailure.BUSY, treeFailure(RemoteRequestException("busy")))
        assertEquals(ReloadFailure.Busy, reloadFailure(RemoteRequestException("busy")))
    }
}
