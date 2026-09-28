package de.joinnoah.pi.remote

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntaxHighlightTest {
    private val colors =
        SyntaxColors(
            keyword = Color.Red,
            string = Color.Green,
            comment = Color.Gray,
            number = Color.Blue,
            key = Color.Magenta,
            heading = Color.Cyan,
        )

    private fun highlight(language: SyntaxLanguage?, vararg lines: String) = highlightLines(lines.toList(), language, colors)

    /** The text of every span in [color], in order. */
    private fun AnnotatedString.spans(color: Color): List<String> =
        spanStyles.filter { it.item.color == color }.sortedBy { it.start }.map { text.substring(it.start, it.end) }

    @Test
    fun languagesByExtension() {
        assertEquals(SyntaxLanguage.KOTLIN, languageFor("app/build.gradle.kts"))
        assertEquals(SyntaxLanguage.KOTLIN, languageFor("Main.KT"))
        assertEquals(SyntaxLanguage.TYPESCRIPT, languageFor("src/view.tsx"))
        assertEquals(SyntaxLanguage.JAVASCRIPT, languageFor("tool.mjs"))
        assertEquals(SyntaxLanguage.SWIFT, languageFor("App.swift"))
        assertEquals(SyntaxLanguage.JSON, languageFor("deno.json"))
        assertEquals(SyntaxLanguage.YAML, languageFor(".github/workflows/ci.yml"))
        assertEquals(SyntaxLanguage.MARKDOWN, languageFor("README.md"))
        assertNull(languageFor("Makefile"))
        assertNull(languageFor(".gitignore"))
        assertNull(languageFor("dir.kt/notes"))
    }

    @Test
    fun kotlinKeywordsStringsNumbersAndComments() {
        val (line) = highlight(SyntaxLanguage.KOTLIN, "val count = 42 + 0x1F // the answer")
        assertEquals(listOf("val"), line.spans(Color.Red))
        assertEquals(listOf("42", "0x1F"), line.spans(Color.Blue))
        assertEquals(listOf("// the answer"), line.spans(Color.Gray))
        val (text) = highlight(SyntaxLanguage.KOTLIN, "fun name() = \"if \\\" else\" + 'x'")
        assertEquals(listOf("fun"), text.spans(Color.Red))
        assertEquals(listOf("\"if \\\" else\"", "'x'"), text.spans(Color.Green))
        // Digits inside a name are not a number.
        assertTrue(highlight(SyntaxLanguage.KOTLIN, "val v2 = x1")[0].spans(Color.Blue).isEmpty())
    }

    @Test
    fun blockCommentsSpanLinesAndNestInKotlin() {
        val lines = highlight(SyntaxLanguage.KOTLIN, "val a = 1 /* one", "/* inner */ still comment", "end */ val b = 2")
        assertEquals(listOf("/* one"), lines[0].spans(Color.Gray))
        assertEquals(listOf("/* inner */ still comment"), lines[1].spans(Color.Gray))
        assertEquals(listOf("end */"), lines[2].spans(Color.Gray))
        assertEquals(listOf("val"), lines[2].spans(Color.Red))
        // TypeScript comments do not nest: the first */ ends it.
        val script = highlight(SyntaxLanguage.TYPESCRIPT, "/* a /* b */ const x = 1")
        assertEquals(listOf("/* a /* b */"), script[0].spans(Color.Gray))
        assertEquals(listOf("const"), script[0].spans(Color.Red))
    }

    @Test
    fun kotlinTemplatesAndRawStrings() {
        val (line) = highlight(SyntaxLanguage.KOTLIN, "val s = \"a \${x + 1} b\"")
        assertEquals(listOf("\"a ", " b\""), line.spans(Color.Green))
        val raw = highlight(SyntaxLanguage.KOTLIN, "val r = \"\"\"first", "second \\ line", "last\"\"\" + val")
        assertEquals(listOf("\"\"\"first"), raw[0].spans(Color.Green))
        assertEquals(listOf("second \\ line"), raw[1].spans(Color.Green))
        assertEquals(listOf("last\"\"\""), raw[2].spans(Color.Green))
        assertEquals(listOf("val"), raw[2].spans(Color.Red))
    }

    @Test
    fun typeScriptTemplateLiteralsSpanLines() {
        val lines = highlight(SyntaxLanguage.TYPESCRIPT, "const t = `a \${b} c", "d` + 'e' // f", "return 1.5")
        assertEquals(listOf("`a ", " c"), lines[0].spans(Color.Green))
        assertEquals(listOf("d`", "'e'"), lines[1].spans(Color.Green))
        assertEquals(listOf("// f"), lines[1].spans(Color.Gray))
        assertEquals(listOf("return"), lines[2].spans(Color.Red))
        assertEquals(listOf("1.5"), lines[2].spans(Color.Blue))
        // An unterminated plain string ends with its line.
        val open = highlight(SyntaxLanguage.JAVASCRIPT, "let s = 'open", "let t = 2")
        assertEquals(listOf("let"), open[1].spans(Color.Red))
    }

    @Test
    fun swiftKeywordsAndStrings() {
        val (line) = highlight(SyntaxLanguage.SWIFT, "guard let x = y else { return nil } // done")
        assertEquals(listOf("guard", "let", "else", "return", "nil"), line.spans(Color.Red))
        assertEquals(listOf("// done"), line.spans(Color.Gray))
    }

    @Test
    fun jsonKeysDifferFromValues() {
        val (line) = highlight(SyntaxLanguage.JSON, "{\"name\" : \"pi\", \"size\": -12.5, \"ok\": true, \"none\": null}")
        assertEquals(listOf("\"name\"", "\"size\"", "\"ok\"", "\"none\""), line.spans(Color.Magenta))
        assertEquals(listOf("\"pi\""), line.spans(Color.Green))
        assertEquals(listOf("-12.5"), line.spans(Color.Blue))
        assertEquals(listOf("true", "null"), line.spans(Color.Red))
    }

    @Test
    fun yamlKeysCommentsAndAnchors() {
        val lines =
            highlight(
                SyntaxLanguage.YAML,
                "# settings",
                "defaults: &base",
                "  - name: \"build # not a comment\" # a comment",
                "  <<: *base",
                "url: http://x",
            )
        assertEquals(listOf("# settings"), lines[0].spans(Color.Gray))
        assertEquals(listOf("defaults"), lines[1].spans(Color.Magenta))
        assertEquals(listOf("&base"), lines[1].spans(Color.Red))
        assertEquals(listOf("name"), lines[2].spans(Color.Magenta))
        assertEquals(listOf("\"build # not a comment\""), lines[2].spans(Color.Green))
        assertEquals(listOf("# a comment"), lines[2].spans(Color.Gray))
        assertEquals(listOf("*base"), lines[3].spans(Color.Red))
        // The colon inside the value does not make another key.
        assertEquals(listOf("url"), lines[4].spans(Color.Magenta))
    }

    @Test
    fun markdownHeadingsFencesInlineCodeAndEmphasis() {
        val lines =
            highlight(
                SyntaxLanguage.MARKDOWN,
                "# Title",
                "Some `code` and **bold** and *soft*, not a*b*c.",
                "```kotlin",
                "# not a heading",
                "```",
                "## After",
            )
        val heading = lines[0].spanStyles.single()
        assertEquals(Color.Cyan, heading.item.color)
        assertEquals(FontWeight.Bold, heading.item.fontWeight)
        assertEquals(listOf("`code`"), lines[1].spans(Color.Green))
        val styles = lines[1].spanStyles
        assertEquals(listOf("**bold**"), styles.filter { it.item.fontWeight == FontWeight.Bold }.map { lines[1].text.substring(it.start, it.end) })
        assertEquals(listOf("*soft*"), styles.filter { it.item.fontStyle == FontStyle.Italic }.map { lines[1].text.substring(it.start, it.end) })
        // Inside the fence everything is code, and the fence closes.
        assertEquals(listOf("```kotlin"), lines[2].spans(Color.Green))
        assertEquals(listOf("# not a heading"), lines[3].spans(Color.Green))
        assertEquals(listOf("```"), lines[4].spans(Color.Green))
        assertEquals(Color.Cyan, lines[5].spanStyles.single().item.color)
    }

    @Test
    fun longLinesStayPlainAndDisplayLinesAreCut() {
        val long = "val x = \"" + "a".repeat(MAX_HIGHLIGHT_LINE_CHARS) + "\""
        val lines = highlight(SyntaxLanguage.KOTLIN, long, "val y = 1")
        assertTrue(lines[0].spanStyles.isEmpty())
        assertEquals(long, lines[0].text)
        assertEquals(listOf("val"), lines[1].spans(Color.Red))
        val huge = "x".repeat(5000)
        assertEquals(MAX_CODE_LINE_CHARS, highlight(null, huge)[0].text.length)
        assertEquals(MAX_CODE_LINE_CHARS, highlight(SyntaxLanguage.KOTLIN, huge)[0].text.length)
    }

    @Test
    fun unknownLanguageIsPlain() {
        val lines = highlight(null, "val x = \"y\" // z")
        assertTrue(lines.single().spanStyles.isEmpty())
        assertEquals("val x = \"y\" // z", lines.single().text)
    }
}
