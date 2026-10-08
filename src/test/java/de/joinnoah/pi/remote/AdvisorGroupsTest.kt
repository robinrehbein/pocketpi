package de.joinnoah.pi.remote

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class AdvisorGroupsTest {
    private fun choice(provider: String, id: String, name: String) =
        AdvisorChoice(provider, id, name, listOf("high"))

    @Test
    fun displayNamesCoverKnownAndUnknownProviders() {
        assertEquals("OpenAI", advisorProviderDisplayName("openai"))
        assertEquals("Anthropic", advisorProviderDisplayName("anthropic"))
        assertEquals("OpenRouter", advisorProviderDisplayName("openrouter"))
        assertEquals("Google", advisorProviderDisplayName("google"))
        assertEquals("Z.ai", advisorProviderDisplayName("zai"))
        assertEquals("Z.ai", advisorProviderDisplayName("z-ai"))
        assertEquals("xAI", advisorProviderDisplayName("xai"))
        assertEquals("Mistral", advisorProviderDisplayName("mistral"))
        assertEquals("DeepSeek", advisorProviderDisplayName("deepseek"))
        assertEquals("Acme-lab", advisorProviderDisplayName("acme-lab"))
        assertEquals("", advisorProviderDisplayName(""))
    }

    @Test
    fun groupsKeepFirstAppearanceOrderAndHostOrderInside() {
        val a1 = choice("openai", "a1", "GPT-6")
        val b1 = choice("anthropic", "b1", "Claude")
        val a2 = choice("openai", "a2", "GPT-6 Astra")
        val c1 = choice("openrouter", "c1", "OpenAI: GPT-6")
        val groups = groupAdvisorChoices(listOf(a1, b1, c1, a2))
        assertEquals(listOf("openai", "anthropic", "openrouter"), groups.map { it.provider })
        assertEquals(listOf("OpenAI", "Anthropic", "OpenRouter"), groups.map { it.displayName })
        assertEquals(listOf(a1, a2), groups[0].choices)
        assertEquals(listOf(c1), groups[2].choices)
    }

    @Test
    fun singleProviderYieldsOneGroupAndEmptyYieldsNone() {
        assertEquals(1, groupAdvisorChoices(listOf(choice("p", "a", "A"), choice("p", "b", "B"))).size)
        assertEquals(0, groupAdvisorChoices(emptyList()).size)
    }

    @Test
    fun rowLabelHiddenWhenItRepeatsTheHeader() {
        assertNull(advisorRowLabel(choice("openai", "a", "OpenAI: GPT-6"), "OpenAI"))
        assertNull(advisorRowLabel(choice("openai", "a", "openai: GPT-6"), "OpenAI"))
        assertNull(advisorRowLabel(choice("openai", "a", "GPT-6"), "OpenAI"))
        assertEquals("OpenAI", advisorRowLabel(choice("openrouter", "a", "OpenAI: GPT-6"), "OpenRouter"))
        assertEquals("GPT-6", advisorRowModelName(choice("openrouter", "a", "OpenAI: GPT-6")))
        assertEquals("GPT-6", advisorRowModelName(choice("openai", "a", "GPT-6")))
    }
}
