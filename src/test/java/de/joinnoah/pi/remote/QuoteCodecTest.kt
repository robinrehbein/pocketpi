package de.joinnoah.pi.remote

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class QuoteCodecTest {
    @Test
    fun sharedFixturesRoundTripAndInvalidEnvelopesStayLiteral() {
        val fixture =
            Wire.json
                .parseToJsonElement(javaClass.getResource("/quotes-v1.json")!!.readText())
                .jsonObject
        for (value in fixture.array("valid")) {
            val quote = QuoteCodec.parse(value.obj("quote"))!!
            assertEquals(value.text("body"), QuoteCodec.decode(value.text("text")).body)
            assertEquals(quote, QuoteCodec.decode(value.text("text")).quote)
            assertEquals(value.text("text"), QuoteCodec.encode(value.text("body"), quote))
        }
        for (value in fixture.array("invalid")) {
            assertEquals(value.text("text"), QuoteCodec.decode(value.text("text")).body)
            assertNull(QuoteCodec.decode(value.text("text")).quote)
        }
    }

    @Test
    fun encodedLimitPreservesUnicodeAndRejectsOversizedBodyWithoutTruncating() {
        assertEquals("a".repeat(2047), boundedUtf8("a".repeat(2047) + "😀", 2048))
        val quote = MessageQuote("m", "assistant", "excerpt", "Model")
        assertThrows(IllegalArgumentException::class.java) {
            QuoteCodec.encode("a".repeat(128 * 1024), quote)
        }
    }
}
