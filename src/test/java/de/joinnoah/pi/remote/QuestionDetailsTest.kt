package de.joinnoah.pi.remote

import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class QuestionDetailsTest {
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun pointer(bytes: ByteArray) = Wire.objectOf("id" to "d", "totalBytes" to bytes.size, "sha256" to hash(bytes))
    private fun page(bytes: ByteArray, offset: Int, total: Int, session: String = "s", question: String = "q", details: String = "d") =
        Wire.objectOf("kind" to "question_details", "sessionId" to session, "questionId" to question,
            "detailsId" to details, "offset" to offset, "totalBytes" to total,
            "data" to Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))

    @Test fun pointerRejectsUnknownFieldsInvalidLengthsAndNonLowercaseHashes() {
        val hash = "a".repeat(64)
        for (json in listOf(
            """{"id":"","totalBytes":1,"sha256":"$hash"}""",
            """{"id":"d","totalBytes":0,"sha256":"$hash"}""",
            """{"id":"d","totalBytes":1048577,"sha256":"$hash"}""",
            """{"id":"d","totalBytes":1.5,"sha256":"$hash"}""",
            """{"id":"d","totalBytes":1,"sha256":"${"A".repeat(64)}"}""",
            """{"id":"d","totalBytes":1,"sha256":"$hash","extra":true}""",
        )) assertTrue(json, runCatching { parseQuestionDetails(Wire.parse(json)) }.isFailure)
        assertNotNull(parseQuestionDetails(Wire.parse("""{"id":"d","totalBytes":1048576,"sha256":"$hash"}""")))
    }

    @Test fun pagesCanSplitUtf8ButOnlyCompleteHashedTextIsReturned() {
        val bytes = "a€z".encodeToByteArray()
        val assembler = QuestionDetailsAssembler("s", "q", parseQuestionDetails(pointer(bytes)))
        assembler.append(page(bytes.copyOfRange(0, 2), 0, bytes.size))
        assertTrue(runCatching { assembler.finish() }.isFailure)
        assembler.append(page(bytes.copyOfRange(2, bytes.size), 2, bytes.size))
        assertEquals("a€z", assembler.finish())
    }

    @Test fun pagesRejectWrongIdentityOffsetTotalAndInvalidEncoding() {
        val bytes = "hello".encodeToByteArray()
        val descriptor = parseQuestionDetails(pointer(bytes))
        val bad = listOf(
            page(bytes, 0, 5, session = "other"), page(bytes, 0, 5, question = "other"),
            page(bytes, 0, 5, details = "other"), page(bytes, 1, 5), page(bytes, 0, 6),
            Wire.parse("""{"kind":"question_details","sessionId":"s","questionId":"q","detailsId":"d","offset":0,"totalBytes":5,"data":"aGVsbG8="}"""),
            Wire.parse("""{"kind":"question_details","sessionId":"s","questionId":"q","detailsId":"d","offset":0,"totalBytes":5,"data":"aGVsbG9"}"""),
            page(byteArrayOf(), 0, 5), page(ByteArray(49153), 0, 5),
        )
        for (value in bad) assertTrue(value.toString(), runCatching { QuestionDetailsAssembler("s", "q", descriptor).append(value) }.isFailure)
    }

    @Test fun corruptionAndMalformedUtf8NeverBecomeVerifiedText() {
        val expected = "hello".encodeToByteArray()
        val corrupted = QuestionDetailsAssembler("s", "q", parseQuestionDetails(pointer(expected)))
        corrupted.append(page("jello".encodeToByteArray(), 0, 5))
        assertTrue(runCatching { corrupted.finish() }.isFailure)
        val invalid = byteArrayOf(0xc3.toByte(), 0x28)
        val malformed = QuestionDetailsAssembler("s", "q", parseQuestionDetails(pointer(invalid)))
        malformed.append(page(invalid, 0, 2))
        assertTrue(runCatching { malformed.finish() }.isFailure)
    }

    @Test fun duplicateAndOutOfOrderPagesAreRejected() {
        val bytes = "hello".encodeToByteArray()
        val assembler = QuestionDetailsAssembler("s", "q", parseQuestionDetails(pointer(bytes)))
        val first = page(bytes.copyOfRange(0, 2), 0, 5)
        assembler.append(first)
        assertTrue(runCatching { assembler.append(first) }.isFailure)
        assertTrue(runCatching { assembler.append(page(bytes.copyOfRange(3, 5), 3, 5)) }.isFailure)
    }
}

class QuestionDetailsAuthorityTest {
    private val details = Wire.parse("""{"id":"d","totalBytes":5,"sha256":"2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"}""")
    private val descriptor = parseQuestionDetails(details)
    private val question = Wire.parse("""{"id":"q","kind":"confirm","message":"Preview","details":{"id":"d","totalBytes":5,"sha256":"2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"}}""")
    private val selection = RemoteSelection("route", "project", "s")
    private val yes = Wire.objectOf("kind" to "confirm", "value" to true)
    private val no = Wire.objectOf("kind" to "confirm", "value" to false)
    private val cancel = Wire.objectOf("kind" to "cancel")

    @Test fun yesRequiresVerifiedDocumentButNoAndCancelDoNot() {
        val authority = QuestionDetailsAuthority()
        assertFalse(authority.allows(1, selection, question, descriptor, yes))
        assertTrue(authority.allows(1, selection, question, descriptor, no))
        assertTrue(authority.allows(1, selection, question, descriptor, cancel))
        assertTrue(runCatching { authority.verify(1, selection, question, descriptor, "wrong") }.isFailure)
        assertFalse(authority.allows(1, selection, question, descriptor, yes))
        authority.verify(1, selection, question, descriptor, "hello")
        assertTrue(authority.allows(1, selection, question, descriptor, yes))
    }

    @Test fun verificationCannotCrossConnectionSelectionQuestionReplacementOrDescriptor() {
        val authority = QuestionDetailsAuthority()
        authority.verify(1, selection, question, descriptor, "hello")
        assertFalse(authority.allows(2, selection, question, descriptor, yes))
        assertFalse(authority.allows(1, selection.copy(sessionId = "other"), question, descriptor, yes))
        assertFalse(authority.allows(1, selection, Wire.parse(question.toString()), descriptor, yes))
        val replaced = parseQuestionDetails(Wire.parse("""{"id":"other","totalBytes":5,"sha256":"2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"}"""))
        assertFalse(authority.allows(1, selection, question, replaced, yes))
        authority.clear()
        assertFalse(authority.allows(1, selection, question, descriptor, yes))
    }
}
