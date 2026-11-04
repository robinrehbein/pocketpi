package de.joinnoah.pi.remote

import org.junit.Assert.*
import org.junit.Test

class ToolOutputCacheTest {
    private fun key(call: String, route: String = "route") = ToolOutputCache.Key(route, "session", call)

    private fun output(call: String, bytes: Int) =
        ToolOutputDownload(call, bytes.toLong(), bytes.toLong(), "x".repeat(bytes), false, false, null)

    @Test
    fun aCompleteOutputIsKeptUntilTheBudgetIsSpentAndTheLeastRecentlyUsedGoesFirst() {
        val cache = ToolOutputCache(capacity = 100)
        cache[key("a")] = output("a", 40)
        cache[key("b")] = output("b", 40)
        assertNotNull(cache[key("a")])
        cache[key("c")] = output("c", 40)
        assertNull(cache[key("b")])
        assertNotNull(cache[key("a")])
        assertNotNull(cache[key("c")])
    }

    @Test
    fun onlyCompleteSuccessfulOutputsAreKept() {
        val cache = ToolOutputCache(capacity = 100)
        cache[key("running")] = ToolOutputDownload("running", 4, 9, null, false, false, null, "abcd")
        cache[key("failed")] = ToolOutputDownload("failed", 0, null, null, false, false, ToolOutputFailure.FAILED)
        assertNull(cache[key("running")])
        assertNull(cache[key("failed")])
    }

    @Test
    fun anOutputLargerThanTheBudgetIsNotKept() {
        val cache = ToolOutputCache(capacity = 100)
        cache[key("big")] = output("big", 101)
        assertNull(cache[key("big")])
    }

    @Test
    fun aRouteIsForgottenWithoutTouchingTheOthers() {
        val cache = ToolOutputCache(capacity = 100)
        cache[key("a", "route-a")] = output("a", 40)
        cache[key("b", "route-b")] = output("b", 40)
        cache.clearRoute("route-a")
        assertNull(cache[key("a", "route-a")])
        assertNotNull(cache[key("b", "route-b")])
        cache[key("c", "route-b")] = output("c", 40)
        cache[key("d", "route-b")] = output("d", 40)
        assertNull(cache[key("b", "route-b")])
        assertNotNull(cache[key("c", "route-b")])
    }
}

class ToolOutputTextTest {
    @Test
    fun chunksSplitAnywhereDecodeToTheWholeText() {
        val text = "aä€😀z"
        val bytes = text.encodeToByteArray()
        for (split in 0..bytes.size) {
            val assembler = ToolOutputText()
            assembler.append(bytes.copyOfRange(0, split))
            assembler.append(bytes.copyOfRange(split, bytes.size))
            assertEquals("split at $split", text, assembler.finish())
        }
    }

    @Test
    fun thePreviewNeverEndsInACharacterThatIsStillOpen() {
        val bytes = "a€".encodeToByteArray()
        val assembler = ToolOutputText()
        assembler.append(bytes.copyOfRange(0, 2))
        assertEquals("a", assembler.preview())
        assembler.append(bytes.copyOfRange(2, bytes.size))
        assertEquals("a€", assembler.preview())
    }

    @Test
    fun aSequenceOpenAtTheEndReadsAsItDoesInAString() {
        val bytes = byteArrayOf(0x61, 0xE2.toByte(), 0x82.toByte())
        val assembler = ToolOutputText()
        assembler.append(bytes)
        assertEquals(String(bytes, Charsets.UTF_8), assembler.finish())
    }

    @Test
    fun malformedBytesReadAsReplacementCharactersLikeAString() {
        val bytes = byteArrayOf(0x61, 0xFF.toByte(), 0x62, 0xC3.toByte(), 0x28)
        val assembler = ToolOutputText()
        assembler.append(bytes.copyOfRange(0, 3))
        assembler.append(bytes.copyOfRange(3, bytes.size))
        assertEquals(String(bytes, Charsets.UTF_8), assembler.finish())
    }

    @Test
    fun anEmptyOutputIsEmpty() {
        assertEquals("", ToolOutputText().finish())
    }
}
