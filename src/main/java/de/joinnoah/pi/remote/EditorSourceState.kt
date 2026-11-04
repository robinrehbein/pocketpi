package de.joinnoah.pi.remote

import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlinx.serialization.json.JsonObject

/** An immutable upload action bound to the displayed question and its Host grant. */
internal class EditorSourceAction internal constructor(
    internal val owner: EditorSourceState,
    val originalSource: String,
    val draft: String,
    val grantId: String,
    val source: EditorUploadSource,
)

/** Collects one exact editor source and retains its connection and question identity. */
internal class EditorSourceState(
    private val epoch: Long,
    private val selection: RemoteSelection,
    private val question: JsonObject,
) {
    private val sessionId = requireNotNull(selection.sessionId).also { require(it.isNotEmpty()) }
    private val questionId = question.text("id").also { require(it.isNotEmpty()) }
    private val grantId = PagedEditorWire.uploadGrantId(question)
    private val descriptor: EditorSource?
    private val source: EditorUploadSource
    private val bytes = ByteArrayOutputStream()
    private var active = true
    var originalSource: String? = null
        private set
    private var draft: String? = null

    init {
        require(!question.containsKey("details"))
        if (question.containsKey("editorSource")) {
            descriptor = PagedEditorWire.parseQuestionSource(question)
            source = EditorUploadSource.Paged(descriptor.id)
        } else {
            descriptor = null
            source = EditorUploadSource.Inline
            val value = if (question.containsKey("prefill")) question.text("prefill") else ""
            require(PagedEditorWire.validText(value, 16_384))
            originalSource = value
            draft = value
        }
    }

    fun append(page: JsonObject) {
        check(active && originalSource == null)
        val expected = requireNotNull(descriptor)
        require(page.keys == setOf("kind", "sessionId", "questionId", "detailsId", "offset", "totalBytes", "data"))
        require(page.text("kind") == "question_details" && page.text("sessionId") == sessionId &&
            page.text("questionId") == questionId && page.text("detailsId") == expected.id)
        require(page.long("offset") == bytes.size().toLong() && page.long("totalBytes") == expected.totalBytes.toLong())
        val encoded = page.text("data")
        require(encoded.length <= 65536 && encoded.matches(Regex("[A-Za-z0-9_-]*")))
        val decoded = Base64.getUrlDecoder().decode(encoded)
        require(Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == encoded)
        require(decoded.size == minOf(EDITOR_UPLOAD_CHUNK_BYTES, expected.totalBytes - bytes.size()) && decoded.isNotEmpty())
        bytes.write(decoded)
    }

    fun finish() {
        check(active && originalSource == null)
        val value = PagedEditorWire.decodeSource(bytes.toByteArray(), requireNotNull(descriptor))
        originalSource = value
        draft = value
    }

    fun edit(value: String) {
        check(active && originalSource != null)
        require(PagedEditorWire.validText(value, MAX_EDITOR_UPLOAD_BYTES))
        draft = value
    }

    fun capture(): EditorSourceAction? {
        if (!active) return null
        val original = originalSource ?: return null
        return EditorSourceAction(this, original, requireNotNull(draft), grantId, source)
    }

    fun allows(action: EditorSourceAction, epoch: Long, selection: RemoteSelection, question: JsonObject): Boolean =
        active && action.owner === this && this.epoch == epoch && this.selection == selection && this.question === question

    fun clear() {
        active = false
        bytes.reset()
        originalSource = null
        draft = null
    }
}
