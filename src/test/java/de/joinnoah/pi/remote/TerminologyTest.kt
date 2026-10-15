package de.joinnoah.pi.remote

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Element

/**
 * One vocabulary on every surface: the session status words match between the phone, the widget,
 * the live-run notification and the watch, and the strings keep to the glossary ("Mac" for the
 * machine, "session" for a pi session) in English and German.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TerminologyTest {
    private val base = ApplicationProvider.getApplicationContext<Context>()

    private fun localized(language: String): Context =
        base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale(language)) })

    @Test
    fun everySurfaceUsesTheSharedStatusWords() {
        for (language in listOf("en", "de")) {
            val context = localized(language)
            val needsYou = context.getString(R.string.remote_status_needs_you)
            val working = context.getString(R.string.remote_status_working)
            val ready = context.getString(R.string.remote_status_ready)
            mapOf(
                R.string.remote_status_waiting to needsYou,
                R.string.remote_status_running to working,
                R.string.remote_status_idle to ready,
                R.string.remote_widget_state_waiting to needsYou,
                R.string.remote_widget_state_running to working,
                R.string.remote_live_run_waiting to needsYou,
                R.string.wear_waiting to needsYou,
                R.string.wear_running to working,
                R.string.wear_idle to ready,
            ).forEach { (resource, expected) ->
                assertEquals("$language ${context.resources.getResourceEntryName(resource)}", expected, context.getString(resource))
            }
        }
    }

    @Test
    fun stringsKeepToTheGlossary() {
        val banned =
            mapOf(
                "values" to
                    listOf(
                        Regex("""\bcomputers?\b""", RegexOption.IGNORE_CASE),
                        // "host key" and "user@host:path" are SSH terms, not the Mac.
                        Regex("""(?<!@)\bhosts?\b(?! key)""", RegexOption.IGNORE_CASE),
                        Regex("""\bconversations?\b""", RegexOption.IGNORE_CASE),
                        Regex("""pi remote extension""", RegexOption.IGNORE_CASE),
                    ),
                "values-de" to
                    listOf(
                        Regex("""Computer|Rechner|Telefon|Smartphone"""),
                        Regex("""(?<!@)\bHosts?\b"""),
                        Regex("""Gespräch|Unterhaltung"""),
                        Regex("""\bSessions?\b"""),
                        Regex("""\bkoppele\b|\bKoppel\b"""),
                    ),
            )
        val found =
            banned.flatMap { (directory, patterns) ->
                strings(File("src/main/res/$directory")).filter { (_, text) -> patterns.any { it.containsMatchIn(text) } }
                    .map { (name, text) -> "$directory/$name: $text" }
            }
        assertTrue(found.joinToString("\n"), found.isEmpty())
    }

    private fun strings(directory: File): List<Pair<String, String>> {
        // Guard against a silent pass when the test runs from a directory where the path does not resolve.
        assertTrue("${directory.absolutePath} is missing", directory.isDirectory)
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        return directory.listFiles { file -> file.extension == "xml" }.orEmpty().sorted().flatMap { file ->
            val root = builder.parse(file).documentElement
            listOf("string", "item").flatMap { tag ->
                val nodes = root.getElementsByTagName(tag)
                (0 until nodes.length).map { index ->
                    val element = nodes.item(index) as Element
                    val name = element.getAttribute("name").ifEmpty { (element.parentNode as Element).getAttribute("name") }
                    name to element.textContent
                }
            }
        }.also { assertTrue("${directory.path} holds only ${it.size} strings", it.size > 100) }
    }
}
