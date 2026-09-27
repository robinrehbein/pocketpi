package de.joinnoah.pi.remote

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

@Composable
fun MarkdownText(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        text.split("```").forEachIndexed { index, part ->
            if (index % 2 == 1) {
                val code = if (part.contains('\n')) part.substringAfter('\n').trimEnd() else part
                val clipboard = LocalClipboardManager.current
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(
                            code,
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { clipboard.setText(AnnotatedString(code)) }) {
                            Text(stringResource(R.string.remote_copy_code))
                        }
                    }
                }
            } else
                part.lines().forEach { line ->
                    val level =
                        line
                            .takeWhile { it == '#' }
                            .length
                            .takeIf { it in 1..6 && line.getOrNull(it) == ' ' }
                    val content =
                        if (level != null) line.drop(level + 1)
                        else line.replace(Regex("^[-*] "), "• ")
                    if (line.trim() == "---") HorizontalDivider()
                    else if (content.isNotEmpty())
                        Text(
                            inlineMarkdown(content, MaterialTheme.colorScheme.primary),
                            style =
                                when (level) {
                                    1 -> MaterialTheme.typography.headlineSmall
                                    2 -> MaterialTheme.typography.titleLarge
                                    null -> MaterialTheme.typography.bodyLarge
                                    else -> MaterialTheme.typography.titleMedium
                                },
                            modifier =
                                if (level != null) Modifier.semantics { heading() } else Modifier,
                        )
                }
        }
    }
}

internal fun inlineMarkdown(text: String, linkColor: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    val regex =
        Regex("`([^`]+)`|\\*\\*([^*]+)\\*\\*|\\*([^*]+)\\*|\\[([^]]+)]\\((https?://[^)]+)\\)|https?://[^\\s<>()]+")
    var offset = 0
    regex.findAll(text).forEach { match ->
        append(text.substring(offset, match.range.first))
        when {
            match.groups[1] != null ->
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                    append(match.groupValues[1])
                }
            match.groups[2] != null ->
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(match.groupValues[2]) }
            match.groups[3] != null ->
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(match.groupValues[3]) }
            else -> {
                val bare = match.groups[4] == null
                val rawUrl = if (bare) match.value else match.groupValues[5]
                val url = if (bare) rawUrl.trimEnd('.', ',', '!', '?', ';', ':') else rawUrl
                if (url.isEmpty()) append(match.value)
                else {
                    withLink(LinkAnnotation.Url(url)) {
                        withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                            append(if (bare) url else match.groupValues[4])
                        }
                    }
                    if (bare) append(rawUrl.substring(url.length))
                }
            }
        }
        offset = match.range.last + 1
    }
    append(text.substring(offset))
}
