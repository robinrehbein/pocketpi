package de.joinnoah.pi.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionTitlesTest {
    private fun manifest(name: String, kind: String) =
        "[{\"id\":\"AAAAAAAAAAAAAAAAAAAAAA\",\"name\":\"$name\",\"kind\":\"$kind\"," +
            "\"mimeType\":\"text/plain\",\"size\":0,\"sha256\":\"${"a".repeat(64)}\"," +
            "\"expiresAt\":604801000,\"path\":\"/fixture/attachments/content\"}]"

    private fun attached(name: String, kind: String, body: String) =
        "[PocketPi attachments v1]\n${manifest(name, kind)}\n[/PocketPi attachments]\n\n$body"

    private val quote =
        "[PocketPi quote v1]\n" +
            "{\"messageId\":\"previous\",\"role\":\"assistant\",\"excerpt\":\"Earlier answer\"}\n" +
            "[/PocketPi quote]\n\n"

    @Test
    fun aFullMarkerWithABodyShowsTheBody() {
        val title = displayTitle(attached("notes.txt", "file", "Read the file."))
        assertEquals(DisplayTitle("Read the file.", TitleAttachment("notes.txt", false)), title)
    }

    @Test
    fun anImageOnlyTitleHasNoTextAndNamesTheImage() {
        val title = displayTitle(attached("a.png", "image", ""))
        assertEquals(DisplayTitle("", TitleAttachment("a.png", true)), title)
    }

    @Test
    fun aTruncatedManifestStillNamesTheFile() {
        val cut = "[PocketPi attachments v1]\n[{\"id\":\"AAAAAAAAAAAAAAAAAAAAAA\",\"name\":\"Grüße \\\"x\\\".txt\",\"ki"
        assertEquals(DisplayTitle("", TitleAttachment("Grüße \"x\".txt", false)), displayTitle(cut))
        val image = "[PocketPi attachments v1]\n[{\"name\":\"a.png\",\"kind\":\"image\",\"mim"
        assertEquals(DisplayTitle("", TitleAttachment("a.png", true)), displayTitle(image))
    }

    @Test
    fun theBareFirstLineGivesAnUnnamedAttachment() {
        assertEquals(
            DisplayTitle("", TitleAttachment(null, false)),
            displayTitle("[PocketPi attachments v1]"),
        )
    }

    @Test
    fun anAttachmentWithAQuoteShowsOnlyTheText() {
        val title = displayTitle(attached("notes.txt", "file", quote + "Read the file."))
        assertEquals(DisplayTitle("Read the file.", TitleAttachment("notes.txt", false)), title)
    }

    @Test
    fun aTruncatedQuoteMarkerIsDropped() {
        assertEquals(DisplayTitle(""), displayTitle("[PocketPi quote v1]\n{\"messageId\":\"prev"))
        val title = displayTitle("[PocketPi quote v1]\n{broken}\n[/PocketPi quote]\n\nHello")
        assertEquals(DisplayTitle("Hello"), title)
    }

    @Test
    fun brokenJsonNeverThrows() {
        for (raw in listOf("", "[PocketPi attachments v1]\n[{\"name\":\"\\", "[PocketPi attachments v1]\n\u0000{{", "[PocketPi quote v1]")) {
            displayTitle(raw)
        }
    }

    @Test
    fun aQuotePrefixIsStripped() {
        assertEquals(DisplayTitle("Why?"), displayTitle(quote + "Why?"))
    }

    @Test
    fun aPlainTitleIsUntouched() {
        assertEquals(DisplayTitle("Fix the build"), displayTitle("Fix the build"))
    }
}
