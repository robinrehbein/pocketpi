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

/**
 * Groups choices by case-insensitive provider id. Groups follow the first appearance of each
 * provider and rows keep host order within a group, so interleaved providers are regrouped.
 */
internal fun groupAdvisorChoices(choices: List<AdvisorChoice>): List<AdvisorProviderGroup> =
    choices.groupBy { it.provider.lowercase() }.map { (provider, rows) ->
        AdvisorProviderGroup(provider, advisorProviderDisplayName(provider), rows)
    }

private fun String.normalizedForMatch(): String =
    filterNot { it == '-' || it == '.' || it == '_' || it.isWhitespace() }.lowercase()

/**
 * Small label above the model name: the "Maker: " prefix of the choice name, or null when it is
 * absent or repeats the group header or the raw provider id (ignoring case and "-", ".", "_",
 * spaces).
 */
internal fun advisorRowLabel(choice: AdvisorChoice, group: AdvisorProviderGroup): String? =
    choice.name.substringBefore(": ", missingDelimiterValue = "").takeIf { label ->
        label.isNotEmpty() &&
            !label.equals(group.displayName, ignoreCase = true) &&
            label.normalizedForMatch() != group.provider.normalizedForMatch()
    }

internal fun advisorRowModelName(choice: AdvisorChoice): String =
    choice.name.substringAfter(": ", missingDelimiterValue = choice.name)
