package de.joinnoah.pi.remote

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject

internal const val QUESTION_PAGED_EDITOR_CAPABILITY = "session.question_editor_paged.v1"
internal const val QUESTION_PAGED_EDITOR_OPT_IN = "question_editor_paged.v1"
internal const val QUESTION_EDITOR_POSITIVE_OUTCOME_CAPABILITY = "session.question_editor_positive_outcome.v1"
internal const val QUESTION_EDITOR_POSITIVE_OUTCOME_OPT_IN = "question_editor_positive_outcome.v1"
internal const val MAX_EDITOR_SOURCE_BYTES = 1_048_576
internal const val MAX_EDITOR_UPLOAD_BYTES = 1_048_576
internal const val EDITOR_UPLOAD_CHUNK_BYTES = 49_152

internal sealed class EditorUploadSource {
    data object Inline : EditorUploadSource()
    data class Paged(val id: String) : EditorUploadSource()
}

internal data class EditorSource(val id: String, val totalBytes: Int, val sha256: String)

/** Pure client wire construction and validation; no delivery authority or upload state. */
internal object PagedEditorWire {
    private val opaqueId = Regex("[A-Za-z0-9_-]{1,256}")
    private val hash = Regex("[a-f0-9]{64}")

    private fun legacyId(value: String) {
        require(value.isNotEmpty() && value.toByteArray(Charsets.UTF_8).size <= 256 && value.none { it <= ' ' || it == '\u007f' })
    }
    private fun sourceId(value: String) { require(opaqueId.matches(value)) }
    private fun digest(value: String) { require(hash.matches(value)) }
    private fun envelope(value: JsonObject): JsonObject {
        require(value.toString().toByteArray(Charsets.UTF_8).size < Wire.MAX_PLAINTEXT)
        return value
    }
    private fun ids(requestId: String, sessionId: String, questionId: String) {
        legacyId(requestId); legacyId(sessionId); legacyId(questionId)
    }

    fun begin(requestId: String, sessionId: String, questionId: String, grantId: String, source: EditorUploadSource, totalBytes: Int, sha256: String, ownerIncarnationId: String? = null): JsonObject {
        ids(requestId, sessionId, questionId); sourceId(grantId)
        require(totalBytes in 0..MAX_EDITOR_UPLOAD_BYTES)
        digest(sha256)
        val binding = when (source) {
            EditorUploadSource.Inline -> Wire.objectOf("kind" to "inline")
            is EditorUploadSource.Paged -> {
                sourceId(source.id)
                Wire.objectOf("kind" to "paged", "id" to source.id)
            }
        }
        ownerIncarnationId?.let(::sourceId)
        val fields = Wire.objectOf("type" to "session.question.editor.upload.begin", "requestId" to requestId,
            "sessionId" to sessionId, "questionId" to questionId, "source" to binding, "totalBytes" to totalBytes, "sha256" to sha256, "grantId" to grantId)
        return envelope(if (ownerIncarnationId == null) fields else JsonObject(fields + ("ownerIncarnationId" to kotlinx.serialization.json.JsonPrimitive(ownerIncarnationId))))
    }

    fun chunk(requestId: String, sessionId: String, questionId: String, uploadId: String, offset: Int, bytes: ByteArray): JsonObject {
        ids(requestId, sessionId, questionId); sourceId(uploadId)
        require(bytes.size in 1..EDITOR_UPLOAD_CHUNK_BYTES && offset in 0..MAX_EDITOR_UPLOAD_BYTES - bytes.size)
        return envelope(Wire.objectOf("type" to "session.question.editor.upload.chunk", "requestId" to requestId,
            "sessionId" to sessionId, "questionId" to questionId, "uploadId" to uploadId, "offset" to offset, "data" to Wire.encode(bytes)))
    }

    fun commit(requestId: String, sessionId: String, questionId: String, uploadId: String, ownerIncarnationId: String? = null): JsonObject {
        ids(requestId, sessionId, questionId); sourceId(uploadId)
        ownerIncarnationId?.let(::sourceId)
        val fields = Wire.objectOf("type" to "session.question.editor.upload.commit", "requestId" to requestId,
            "sessionId" to sessionId, "questionId" to questionId, "uploadId" to uploadId)
        return envelope(if (ownerIncarnationId == null) fields else JsonObject(fields + ("ownerIncarnationId" to kotlinx.serialization.json.JsonPrimitive(ownerIncarnationId))))
    }

    fun ownerIncarnationId(question: JsonObject): String? {
        if (!question.containsKey("editorOwnerIncarnationId")) return null
        require(question.text("kind") == "editor")
        return question.text("editorOwnerIncarnationId").also(::sourceId)
    }

    fun receipt(release: StoredPagedRelease): JsonObject {
        ids(release.commitRequestId, release.key.sessionId, release.key.questionId)
        sourceId(release.ownerIncarnationId); sourceId(release.grantId); sourceId(release.uploadId)
        require(release.byteLength in 0..MAX_EDITOR_UPLOAD_BYTES); digest(release.sha256)
        return Wire.objectOf("sessionId" to release.key.sessionId, "questionId" to release.key.questionId,
            "ownerIncarnationId" to release.ownerIncarnationId, "grantId" to release.grantId,
            "uploadId" to release.uploadId, "commitRequestId" to release.commitRequestId,
            "byteLength" to release.byteLength, "sha256" to release.sha256)
    }

    fun receipt(attempt: StoredPagedAttempt): JsonObject {
        val b = requireNotNull(attempt.outcomeBinding)
        ids(attempt.commitRequestId, attempt.key.sessionId, attempt.key.questionId)
        sourceId(b.ownerIncarnationId); sourceId(attempt.grantId); sourceId(attempt.uploadId)
        require(b.byteLength in 0..MAX_EDITOR_UPLOAD_BYTES); digest(b.sha256)
        return Wire.objectOf("sessionId" to attempt.key.sessionId, "questionId" to attempt.key.questionId,
            "ownerIncarnationId" to b.ownerIncarnationId, "grantId" to attempt.grantId,
            "uploadId" to attempt.uploadId, "commitRequestId" to attempt.commitRequestId,
            "byteLength" to b.byteLength, "sha256" to b.sha256)
    }

    fun parseSource(value: JsonObject): EditorSource {
        require(value.keys == setOf("purpose", "id", "totalBytes", "sha256") && value.text("purpose") == "editor_prefill")
        val id = value.text("id"); sourceId(id)
        val size = value.long("totalBytes"); require(size in 1L..MAX_EDITOR_SOURCE_BYTES.toLong())
        val sha256 = value.text("sha256"); digest(sha256)
        return EditorSource(id, size.toInt(), sha256)
    }

    fun uploadGrantId(question: JsonObject): String {
        require(question.text("kind") == "editor")
        return question.text("editorUploadGrantId").also { sourceId(it) }
    }

    fun parseQuestionSource(question: JsonObject): EditorSource {
        require(question.text("kind") == "editor" && !question.containsKey("prefill") && !question.containsKey("details"))
        return parseSource(question.obj("editorSource"))
    }

    fun validateBegun(value: JsonObject, sessionId: String, questionId: String): String {
        require(value.keys == setOf("kind", "sessionId", "questionId", "uploadId", "nextOffset"))
        require(value.text("kind") == "editor_upload_begun" && value.text("sessionId") == sessionId && value.text("questionId") == questionId)
        legacyId(sessionId); legacyId(questionId)
        require(value.long("nextOffset") == 0L)
        return value.text("uploadId").also { sourceId(it) }
    }

    fun validateAck(value: JsonObject, sessionId: String, questionId: String, uploadId: String, expectedOffset: Int) {
        require(value.keys == setOf("kind", "sessionId", "questionId", "uploadId", "nextOffset"))
        legacyId(sessionId); legacyId(questionId); sourceId(uploadId)
        require(expectedOffset in 1..MAX_EDITOR_UPLOAD_BYTES)
        require(value.text("kind") == "editor_upload_ack" && value.text("sessionId") == sessionId &&
            value.text("questionId") == questionId && value.text("uploadId") == uploadId && value.long("nextOffset") == expectedOffset.toLong())
    }

    fun validText(value: String, maxBytes: Int): Boolean = maxBytes >= 0 && validEditorText(value, maxBytes)

    fun decodeSource(bytes: ByteArray, source: EditorSource): String {
        require(bytes.size == source.totalBytes && bytes.size <= MAX_EDITOR_SOURCE_BYTES)
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(actual == source.sha256)
        val value = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        require(validText(value, MAX_EDITOR_SOURCE_BYTES))
        return value
    }
}
