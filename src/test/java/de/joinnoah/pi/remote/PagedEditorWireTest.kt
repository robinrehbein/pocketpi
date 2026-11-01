package de.joinnoah.pi.remote

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PagedEditorWireTest {
    private val emptyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    private val hash = "a".repeat(64)

    @Test fun sharedFixtureExercisesClientBuildersAndIncomingValidators() {
        val fixture = javaClass.getResourceAsStream("/paged-editor-wire.json")!!.bufferedReader().use { it.readText() }
        val cases = Json.parseToJsonElement(fixture).jsonObject.getValue("cases").jsonArray
            .associate { it.jsonObject.getValue("name").jsonPrimitive.content to it.jsonObject.getValue("payload").jsonObject }
        assertEquals(cases.getValue("inline-empty-begin"), PagedEditorWire.begin("r", "s", "q", "grant-1", EditorUploadSource.Inline, 0, emptyHash))
        assertEquals(cases.getValue("paged-begin"), PagedEditorWire.begin("r", "s", "q", "grant-1", EditorUploadSource.Paged("doc"), 4, emptyHash))
        assertEquals(cases.getValue("chunk-multibyte"), PagedEditorWire.chunk("r", "s", "q", "u", 0, "😀".toByteArray()))
        assertEquals(cases.getValue("commit"), PagedEditorWire.commit("r", "s", "q", "u"))
        fun data(name: String) = cases.getValue(name).getValue("data").jsonObject
        assertEquals("u", PagedEditorWire.validateBegun(data("begun"), "s", "q"))
        PagedEditorWire.validateAck(data("ack"), "s", "q", "u", 4)
        assertTrue(runCatching { PagedEditorWire.validateBegun(data("begun-offset-nonzero"), "s", "q") }.isFailure)
        assertTrue(runCatching { PagedEditorWire.validateAck(data("ack-extra"), "s", "q", "u", 4) }.isFailure)
        fun question(name: String) = cases.getValue(name).getValue("question").jsonObject
        assertEquals("grant-1", PagedEditorWire.uploadGrantId(question("editor-with-upload-grant")))
        for (name in listOf("grant-on-non-editor", "editor-malformed-grant")) {
            assertTrue(name, runCatching { PagedEditorWire.uploadGrantId(question(name)) }.isFailure)
        }
        assertNotNull(PagedEditorWire.parseQuestionSource(question("editor-source")))
        assertTrue(runCatching { PagedEditorWire.parseQuestionSource(Wire.parse("""{"kind":"confirm","editorSource":{"purpose":"editor_prefill","id":"d","totalBytes":1,"sha256":"$hash"}}""")) }.isFailure)
        for (name in listOf("source-and-prefill", "source-bad-purpose", "source-path-id")) {
            assertTrue(name, runCatching { PagedEditorWire.parseQuestionSource(question(name)) }.isFailure)
        }
    }

    @Test fun boundedEditorRejectsEverySourceUntilPagedRendererExists() {
        val source = """{"purpose":"editor_prefill","id":"d","totalBytes":1,"sha256":"$hash"}"""
        assertNull(editorPrefill(Wire.parse("""{"editorSource":$source}""")))
        assertNull(editorPrefill(Wire.parse("""{"editorSource":$source,"prefill":""}""")))
        assertNull(editorPrefill(Wire.parse("""{"editorSource":null}""")))
        assertNull(editorPrefill(Wire.parse("""{"editorUploadGrantId":"grant","prefill":"hello"}""")))
        assertNull(editorPrefill(Wire.parse("""{"editorUploadGrantId":null}""")))
        assertEquals("", editorPrefill(Wire.parse("{}")))
        assertNotNull(editorPrefill(Wire.parse("""{"prefill":"${"a".repeat(16384)}"}""")))
        assertNull(editorPrefill(Wire.parse("""{"prefill":"${"a".repeat(16385)}"}""")))
    }

    @Test fun beginBindsExplicitInlineOrPagedSourceAndEmptyHash() {
        assertEquals(Wire.parse("""{"type":"session.question.editor.upload.begin","requestId":"r","sessionId":"s","questionId":"q","grantId":"grant","source":{"kind":"inline"},"totalBytes":0,"sha256":"$emptyHash"}"""),
            PagedEditorWire.begin("r", "s", "q", "grant", EditorUploadSource.Inline, 0, emptyHash))
        assertEquals(Wire.parse("""{"type":"session.question.editor.upload.begin","requestId":"r","sessionId":"s","questionId":"q","grantId":"grant","source":{"kind":"paged","id":"d"},"totalBytes":1048576,"sha256":"$hash"}"""),
            PagedEditorWire.begin("r", "s", "q", "grant", EditorUploadSource.Paged("d"), 1048576, hash))
        assertTrue(runCatching { PagedEditorWire.begin("r", "s", "q", "grant", EditorUploadSource.Inline, 1048577, hash) }.isFailure)
        assertTrue(runCatching { PagedEditorWire.begin("r", "s", "q", "grant", EditorUploadSource.Paged("../path"), 1, hash) }.isFailure)
        assertTrue(runCatching { PagedEditorWire.begin("r", "s", "q", "grant", EditorUploadSource.Inline, 1, "A".repeat(64)) }.isFailure)
    }

    @Test fun grantReaderRequiresEditorAndCanonicalNonemptyGrantId() {
        assertEquals("grant_1-2", PagedEditorWire.uploadGrantId(Wire.parse("""{"kind":"editor","editorUploadGrantId":"grant_1-2"}""")))
        for (question in listOf(
            """{"kind":"editor"}""",
            """{"kind":"editor","editorUploadGrantId":null}""",
            """{"kind":"editor","editorUploadGrantId":12}""",
            """{"kind":"editor","editorUploadGrantId":""}""",
            """{"kind":"editor","editorUploadGrantId":"/path"}""",
            """{"kind":"confirm","editorUploadGrantId":"grant"}""",
            """{"kind":"editor","editorUploadGrantId":"${"a".repeat(257)}"}""",
        )) assertTrue(question, runCatching { PagedEditorWire.uploadGrantId(Wire.parse(question)) }.isFailure)
        assertTrue(runCatching { PagedEditorWire.begin("r", "s", "q", "", EditorUploadSource.Inline, 0, emptyHash) }.isFailure)
        assertTrue(runCatching { PagedEditorWire.begin("r", "s", "q", "bad/id", EditorUploadSource.Inline, 0, emptyHash) }.isFailure)
    }

    @Test fun sourceDescriptorRequiresExactShapeAndBounds() {
        val valid = """{"purpose":"editor_prefill","id":"d","totalBytes":1048576,"sha256":"$hash"}"""
        assertNotNull(PagedEditorWire.parseSource(Wire.parse(valid)))
        for (json in listOf(
            """{"purpose":"editor_prefill","id":"d","totalBytes":0,"sha256":"$hash"}""",
            """{"purpose":"editor_prefill","id":"d","totalBytes":1048577,"sha256":"$hash"}""",
            """{"purpose":"editor_prefill","id":"d","totalBytes":1.0,"sha256":"$hash"}""",
            """{"purpose":"editor_prefill","id":"../path","totalBytes":1,"sha256":"$hash"}""",
            """{"purpose":"confirm","id":"d","totalBytes":1,"sha256":"$hash"}""",
            """{"purpose":"editor_prefill","id":"d","totalBytes":1,"sha256":"$hash","extra":true}""",
        )) assertTrue(json, runCatching { PagedEditorWire.parseSource(Wire.parse(json)) }.isFailure)
    }

    @Test fun chunksUseCanonicalBase64urlAndFitThePlaintextEnvelope() {
        val chunk = PagedEditorWire.chunk("r", "s", "q", "u", 0, byteArrayOf(0xfb.toByte(), 0xff.toByte()))
        assertEquals(Wire.parse("""{"type":"session.question.editor.upload.chunk","requestId":"r","sessionId":"s","questionId":"q","uploadId":"u","offset":0,"data":"-_8"}"""), chunk)
        val max = PagedEditorWire.chunk("r", "s", "q", "u", 0, ByteArray(49152))
        assertTrue(max.toString().toByteArray(Charsets.UTF_8).size < 256 * 1024)
        assertTrue(runCatching { PagedEditorWire.chunk("r", "s", "q", "u", 0, ByteArray(49153)) }.isFailure)
        assertTrue(runCatching { PagedEditorWire.chunk("r", "s", "q", "u", -1, byteArrayOf(1)) }.isFailure)
        assertTrue(runCatching { PagedEditorWire.chunk("r", "s", "q", "u", 0, byteArrayOf()) }.isFailure)
        assertTrue(runCatching { PagedEditorWire.chunk("r", "s", "q", "u", 1048576, byteArrayOf(1)) }.isFailure)
    }

    @Test fun resultValidatorsBindAllIdentitiesAndOffsets() {
        val begun = Wire.parse("""{"kind":"editor_upload_begun","sessionId":"s","questionId":"q","uploadId":"u","nextOffset":0}""")
        assertEquals("u", PagedEditorWire.validateBegun(begun, "s", "q"))
        val ack = Wire.parse("""{"kind":"editor_upload_ack","sessionId":"s","questionId":"q","uploadId":"u","nextOffset":3}""")
        PagedEditorWire.validateAck(ack, "s", "q", "u", 3)
        assertTrue(runCatching { PagedEditorWire.validateBegun(begun, "other", "q") }.isFailure)
        assertTrue(runCatching { PagedEditorWire.validateBegun(begun, "s", "other") }.isFailure)
        assertTrue(runCatching { PagedEditorWire.validateAck(ack, "s", "q", "other", 3) }.isFailure)
        assertTrue(runCatching { PagedEditorWire.validateAck(ack, "s", "q", "u", 4) }.isFailure)
        assertTrue(runCatching { PagedEditorWire.validateAck(Wire.parse(ack.toString().dropLast(1) + ",\"extra\":true}"), "s", "q", "u", 3) }.isFailure)
    }

    @Test fun sourceBytesAreDecodedOnlyAfterLengthHashAndStrictUtf8Validation() {
        val bytes = "a€z".toByteArray(Charsets.UTF_8)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val descriptor = PagedEditorWire.parseSource(Wire.parse("""{"purpose":"editor_prefill","id":"d","totalBytes":5,"sha256":"$digest"}"""))
        assertEquals("a€z", PagedEditorWire.decodeSource(bytes, descriptor))
        val bom = "\uFEFFhello".toByteArray(Charsets.UTF_8)
        val bomHash = java.security.MessageDigest.getInstance("SHA-256").digest(bom).joinToString("") { "%02x".format(it) }
        val bomDescriptor = PagedEditorWire.parseSource(Wire.parse("""{"purpose":"editor_prefill","id":"d","totalBytes":8,"sha256":"$bomHash"}"""))
        assertEquals("\uFEFFhello", PagedEditorWire.decodeSource(bom, bomDescriptor))
        val control = byteArrayOf(0)
        val controlHash = java.security.MessageDigest.getInstance("SHA-256").digest(control).joinToString("") { "%02x".format(it) }
        val controlDescriptor = PagedEditorWire.parseSource(Wire.parse("""{"purpose":"editor_prefill","id":"d","totalBytes":1,"sha256":"$controlHash"}"""))
        assertTrue(runCatching { PagedEditorWire.decodeSource(control, controlDescriptor) }.isFailure)
        assertTrue(runCatching { PagedEditorWire.decodeSource(bytes.copyOfRange(0, 4), descriptor) }.isFailure)
        assertTrue(runCatching { PagedEditorWire.decodeSource("b€z".toByteArray(), descriptor) }.isFailure)
        val malformed = byteArrayOf(0xc3.toByte(), 0x28)
        val badHash = java.security.MessageDigest.getInstance("SHA-256").digest(malformed).joinToString("") { "%02x".format(it) }
        val badDescriptor = PagedEditorWire.parseSource(Wire.parse("""{"purpose":"editor_prefill","id":"d","totalBytes":2,"sha256":"$badHash"}"""))
        assertTrue(runCatching { PagedEditorWire.decodeSource(malformed, badDescriptor) }.isFailure)
    }

    @Test fun completeTextRequiresValidScalarsControlsAndUtf8ByteBound() {
        assertTrue(PagedEditorWire.validText("", 1048576))
        assertTrue(PagedEditorWire.validText("a".repeat(1048576), 1048576))
        assertFalse(PagedEditorWire.validText("a".repeat(1048577), 1048576))
        assertTrue(PagedEditorWire.validText("a€\n\t", 6))
        assertFalse(PagedEditorWire.validText("a€\n\t", 5))
        assertFalse(PagedEditorWire.validText("\u0000", 1048576))
        assertFalse(PagedEditorWire.validText("\uD800", 1048576))
        assertFalse(PagedEditorWire.validText("\uDC00", 1048576))
        assertTrue(validEditorText("a".repeat(65536), 65536))
        assertFalse(validEditorText("a".repeat(65537), 65536))
    }
}
