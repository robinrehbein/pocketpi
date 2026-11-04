package de.joinnoah.pi.remote

import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class EditorSourceStateTest {
    private val selection = RemoteSelection("route", "project", "s")
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun question(bytes: ByteArray, grant: String = "grant1"): JsonObject = Wire.objectOf(
        "id" to "q", "kind" to "editor", "editorUploadGrantId" to grant,
        "editorSource" to Wire.objectOf("purpose" to "editor_prefill", "id" to "source1", "totalBytes" to bytes.size, "sha256" to hash(bytes)),
    )
    private fun inline(text: String = "", grant: String = "grant1") = Wire.objectOf(
        "id" to "q", "kind" to "editor", "editorUploadGrantId" to grant, "prefill" to text,
    )
    private fun page(bytes: ByteArray, offset: Int, total: Int, session: String = "s", question: String = "q", source: String = "source1") = Wire.objectOf(
        "kind" to "question_details", "sessionId" to session, "questionId" to question, "detailsId" to source,
        "offset" to offset, "totalBytes" to total, "data" to Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
    )
    private fun fill(state: EditorSourceState, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + EDITOR_UPLOAD_CHUNK_BYTES, bytes.size)
            state.append(page(bytes.copyOfRange(offset, end), offset, bytes.size))
            offset = end
        }
        state.finish()
    }
    private fun state(q: JsonObject) = EditorSourceState(1, selection, q)

    @Test fun oneMiBSourceIsAssembledAndOriginalRemainsSeparateFromDraft() {
        val text = "\uFEFF" + "x".repeat(MAX_EDITOR_SOURCE_BYTES - 3)
        val bytes = text.toByteArray(Charsets.UTF_8)
        val q = question(bytes)
        val state = state(q)
        assertNull(state.capture())
        fill(state, bytes)
        assertEquals(text, state.originalSource)
        state.edit("changed")
        val action = state.capture()!!
        assertEquals(text, action.originalSource)
        assertEquals("changed", action.draft)
        assertEquals("grant1", action.grantId)
        assertEquals(EditorUploadSource.Paged("source1"), action.source)
        assertTrue(state.allows(action, 1, selection, q))
    }

    @Test fun emptyAndBoundedInlineSourcesAreValidWithoutPages() {
        for (text in listOf("", "hello", "x".repeat(16384))) {
            val q = inline(text)
            val state = state(q)
            val action = state.capture()!!
            assertEquals(text, action.originalSource)
            assertEquals(text, action.draft)
            assertEquals(EditorUploadSource.Inline, action.source)
            assertTrue(state.allows(action, 1, selection, q))
        }
        val absent = Wire.objectOf("id" to "q", "kind" to "editor", "editorUploadGrantId" to "grant1")
        assertEquals("", state(absent).capture()!!.originalSource)
        for (text in listOf("x".repeat(16385), "\uD800", "a\u0000b")) {
            assertTrue(runCatching { state(inline(text)) }.isFailure)
        }
    }

    @Test fun pagesCanSplitMultibyteCharactersAtExactBoundary() {
        val text = "a".repeat(EDITOR_UPLOAD_CHUNK_BYTES - 1) + "€z"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val state = state(question(bytes))
        fill(state, bytes)
        assertEquals(text, state.capture()!!.originalSource)
    }

    @Test fun rejectsShortPagesWrongIdentityAndNoncanonicalBase64() {
        val bytes = ByteArray(EDITOR_UPLOAD_CHUNK_BYTES + 1) { 'a'.code.toByte() }
        val q = question(bytes)
        val full = bytes.copyOfRange(0, EDITOR_UPLOAD_CHUNK_BYTES)
        val bad = listOf(
            page(full.copyOfRange(0, full.size - 1), 0, bytes.size),
            page(full, 0, bytes.size, session = "other"), page(full, 0, bytes.size, question = "other"),
            page(full, 0, bytes.size, source = "other"), page(full, 1, bytes.size), page(full, 0, bytes.size + 1),
            Wire.parse("""{"kind":"question_details","sessionId":"s","questionId":"q","detailsId":"source1","offset":0,"totalBytes":49153,"data":"YQ=="}"""),
        )
        for (value in bad) assertTrue(value.toString(), runCatching { state(q).append(value) }.isFailure)
    }

    @Test fun wrongHashInvalidUtf8AndForbiddenControlsNeverAuthorize() {
        for ((expected, delivered) in listOf(
            "hello".encodeToByteArray() to "jello".encodeToByteArray(),
            byteArrayOf(0xc3.toByte(), 0x28) to byteArrayOf(0xc3.toByte(), 0x28),
            "a\u0000b".encodeToByteArray() to "a\u0000b".encodeToByteArray(),
        )) {
            val state = state(question(expected))
            state.append(page(delivered, 0, delivered.size))
            assertTrue(runCatching { state.finish() }.isFailure)
            assertNull(state.capture())
        }
    }

    @Test fun actionsAreBoundToQuestionIdentityEpochSelectionAndCapturedGrant() {
        val q = inline("same", "old_nonce")
        val state = state(q)
        state.edit("draft")
        val action = state.capture()!!
        assertFalse(state.allows(action, 2, selection, q))
        assertFalse(state.allows(action, 1, selection.copy(sessionId = "other"), q))
        assertFalse(state.allows(action, 1, selection, Wire.parse(q.toString())))
        assertFalse(state.allows(action, 1, selection, inline("same", "new_nonce")))
        state.clear()
        assertFalse(state.allows(action, 1, selection, q))
    }

    @Test fun stalePageCompletionAfterClearCannotAuthorizeAndConfirmCannotBeEditor() {
        val bytes = "hello".encodeToByteArray()
        val state = state(question(bytes))
        state.append(page(bytes, 0, bytes.size))
        state.clear()
        assertTrue(runCatching { state.finish() }.isFailure)
        assertNull(state.capture())
        val confirm = Wire.objectOf("id" to "q", "kind" to "confirm", "editorUploadGrantId" to "grant1", "details" to Wire.objectOf("id" to "source1", "totalBytes" to bytes.size, "sha256" to hash(bytes)))
        assertTrue(runCatching { state(confirm) }.isFailure)
    }
}
