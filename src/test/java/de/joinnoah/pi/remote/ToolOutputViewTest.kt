package de.joinnoah.pi.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolOutputViewTest {
    @Test fun sourceMetadataWinsOverMarkdown() {
        assertEquals(OutputFormat.SOURCE, outputFormat("read", "{\"path\":\"README.md\"}", "# Title"))
    }
    @Test fun markdownOutputIsRecognized() {
        assertEquals(OutputFormat.MARKDOWN, outputFormat(null, null, "# Summary\n| A | B |\n| --- | --- |"))
    }
    @Test fun jsonAndTerminalOutputPreserveWhitespace() {
        assertEquals(OutputFormat.SOURCE, outputFormat(null, null, "{\n  \"x\": 1\n}"))
        assertEquals(OutputFormat.SOURCE, outputFormat("bash", null, "  error\n    stack frame"))
    }
    @Test fun unknownOutputUsesTextFallback() {
        assertEquals(OutputFormat.TEXT, outputFormat(null, null, "Finished successfully."))
        assertEquals(OutputFormat.TEXT, outputFormat(null, "invalid JSON", "Finished."))
    }
}
