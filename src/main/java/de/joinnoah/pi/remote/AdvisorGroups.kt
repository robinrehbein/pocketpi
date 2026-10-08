package de.joinnoah.pi.remote

internal data class AdvisorProviderGroup(
    val provider: String,
    val displayName: String,
    val choices: List<AdvisorChoice>,
)

private val PROVIDER_DISPLAY_NAMES = mapOf(
    "openai" to "OpenAI",
    "anthropic" to "Anthropic",
    "openrouter" to "OpenRouter",
    "google" to "Google",
    "zai" to "Z.ai",
    "z-ai" to "Z.ai",
    "xai" to "xAI",
    "mistral" to "Mistral",
    "deepseek" to "DeepSeek",
)

/** Brand name for a provider id; unknown ids get their first letter capitalised. */
internal fun advisorProviderDisplayName(provider: String): String =
    PROVIDER_DISPLAY_NAMES[provider.lowercase()]
        ?: provider.replaceFirstChar { it.uppercase() }

/** Groups choices by provider; groups and rows keep the host's order. */
internal fun groupAdvisorChoices(choices: List<AdvisorChoice>): List<AdvisorProviderGroup> =
    choices.groupBy { it.provider }.map { (provider, rows) ->
        AdvisorProviderGroup(provider, advisorProviderDisplayName(provider), rows)
    }

/**
 * Small label above the model name: the "Maker: " prefix of the choice name, or null when it is
 * absent or repeats the group header.
 */
internal fun advisorRowLabel(choice: AdvisorChoice, groupDisplayName: String): String? =
    choice.name.substringBefore(": ", missingDelimiterValue = "")
        .takeIf { it.isNotEmpty() && !it.equals(groupDisplayName, ignoreCase = true) }

internal fun advisorRowModelName(choice: AdvisorChoice): String =
    choice.name.substringAfter(": ", missingDelimiterValue = choice.name)
