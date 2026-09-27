package de.joinnoah.pi.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionPromptTest {
    @Test
    fun promptWithoutDiagramStaysWhole() {
        assertEquals("Which auth variant?" to null, promptParts("Which auth variant?"))
    }

    @Test
    fun hostDrawnDiagramBecomesTheFencedTail() {
        val art = "┌────────┐\n│ Client │\n└────────┘"
        val (text, diagram) = promptParts("Which auth variant?\n\n```\n$art\n```")
        assertEquals("Which auth variant?", text)
        assertEquals("```\n$art\n```", diagram)
    }

    @Test
    fun onlyTheAppendedDiagramIsSplitOff() {
        val prompt = "Consider:\n\n```js\nfoo()\n```\n\nWhich option?\n\n```\n│ A │\n```"
        val (text, diagram) = promptParts(prompt)
        assertEquals("Consider:\n\n```js\nfoo()\n```\n\nWhich option?", text)
        assertEquals("```\n│ A │\n```", diagram)
    }

    @Test
    fun inlineBackticksInTheQuestionDoNotSplitIt() {
        assertEquals("Use ```code``` here?" to null, promptParts("Use ```code``` here?"))
    }

    @Test
    fun inlineAndUnclosedFencesStayPlainText() {
        assertFalse(hasFencedDiagram("Type ``` to begin a code fence"))
        assertFalse(hasFencedDiagram("Type ``` to begin a code fence\n```"))
        assertFalse(hasFencedDiagram("Before\n```mermaid\ngraph TD\nA --> B"))
        assertFalse(hasFencedDiagram("Before\n```\n┌───┐"))
        assertFalse(hasFencedDiagram("Before\n```javascript\ncode\n```"))
    }

    @Test
    fun completeHostFencesUseMarkdownRendering() {
        assertTrue(hasFencedDiagram("Before\n```\n┌───┐\n```\nAfter"))
        assertTrue(hasFencedDiagram("Before\n```mermaid\ngraph TD\nA --> B\n```\nAfter"))
        assertTrue(hasFencedDiagram("Before\r\n```mermaid\r\ngraph TD\r\n```\r\nAfter"))
        assertTrue(hasFencedDiagram("Before\r\n```\r\n┌───┐\r\n```\r\nAfter"))
    }
}
