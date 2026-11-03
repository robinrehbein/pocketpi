package de.joinnoah.pi.remote

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.JsonObject

internal const val QUESTION_DETAILS_CAPABILITY = "session.question_details.v1"
private const val DETAILS_MAX = 1048576
private const val PAGE_MAX = 49152

internal data class QuestionDetailsDescriptor(val id: String, val totalBytes: Int, val sha256: String)
private fun validDetailsId(id: String) = id.isNotEmpty() && id.toByteArray(Charsets.UTF_8).size <= 256 && id.none { it <= ' ' || it == '\u007f' }
internal fun parseQuestionDetails(value: JsonObject): QuestionDetailsDescriptor {
    require(value.keys == setOf("id", "totalBytes", "sha256"))
    val id = value.text("id")
    val total = value.long("totalBytes")
    val hash = value.text("sha256")
    require(validDetailsId(id) && total in 1L..DETAILS_MAX.toLong() && hash.matches(Regex("[a-f0-9]{64}")))
    return QuestionDetailsDescriptor(id, total.toInt(), hash)
}
private fun detailsHash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
internal class QuestionDetailsAssembler(private val sessionId: String, private val questionId: String, private val descriptor: QuestionDetailsDescriptor) {
    private val data = ByteArrayOutputStream()
    val offset: Int get() = data.size()
    fun append(page: JsonObject) {
        require(page.keys == setOf("kind", "sessionId", "questionId", "detailsId", "offset", "totalBytes", "data"))
        require(page.text("kind") == "question_details" && page.text("sessionId") == sessionId && page.text("questionId") == questionId && page.text("detailsId") == descriptor.id)
        require(validDetailsId(sessionId) && validDetailsId(questionId))
        require(page.long("offset") == offset.toLong() && page.long("totalBytes") == descriptor.totalBytes.toLong())
        val encoded = page.text("data")
        require(encoded.length <= 65536 && encoded.matches(Regex("[A-Za-z0-9_-]*")))
        val bytes = Base64.getUrlDecoder().decode(encoded)
        require(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == encoded)
        require(bytes.size <= PAGE_MAX && offset + bytes.size <= descriptor.totalBytes && (bytes.isNotEmpty() || offset == descriptor.totalBytes))
        data.write(bytes)
    }
    fun finish(): String {
        val bytes = data.toByteArray()
        require(bytes.size == descriptor.totalBytes && detailsHash(bytes) == descriptor.sha256)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }
}
internal class QuestionDetailsAuthority {
    private data class Grant(val epoch: Long, val selection: RemoteSelection, val question: JsonObject, val descriptor: QuestionDetailsDescriptor)
    private var grant: Grant? = null
    fun verify(epoch: Long, selection: RemoteSelection, question: JsonObject, descriptor: QuestionDetailsDescriptor, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size == descriptor.totalBytes && detailsHash(bytes) == descriptor.sha256)
        require(question["details"] is JsonObject && parseQuestionDetails(question.obj("details")) == descriptor)
        grant = Grant(epoch, selection, question, descriptor)
    }
    fun allows(epoch: Long, selection: RemoteSelection, question: JsonObject, descriptor: QuestionDetailsDescriptor, answer: JsonObject): Boolean {
        if (!(answer.optionalText("kind") == "confirm" && runCatching { answer.flag("value") }.getOrDefault(false))) return true
        val current = grant ?: return false
        return current.epoch == epoch && current.selection == selection && current.question === question && current.descriptor == descriptor
    }
    fun clear() { grant = null }
}

data class QuestionDetailsView(val loading: Boolean = false, val text: String? = null, val failed: Boolean = false)
