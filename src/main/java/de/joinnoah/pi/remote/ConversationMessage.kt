package de.joinnoah.pi.remote

import android.animation.ValueAnimator
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private class BubbleShape(private val outgoing: Boolean) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val tail = with(density) { 8.dp.toPx() }
        val radius = with(density) { 20.dp.toPx() }
        val body =
            Path().apply {
                addRoundRect(
                    RoundRect(
                        if (outgoing) 0f else tail,
                        0f,
                        if (outgoing) size.width - tail else size.width,
                        size.height,
                        CornerRadius(radius),
                    )
                )
            }
        val tip =
            Path().apply {
                if (outgoing) {
                    moveTo(size.width - tail * 2, size.height - tail * 2)
                    lineTo(size.width, size.height)
                    lineTo(size.width - tail * 3, size.height - tail / 2)
                } else {
                    moveTo(tail * 2, size.height - tail * 2)
                    lineTo(0f, size.height)
                    lineTo(tail * 3, size.height - tail / 2)
                }
                close()
            }
        return Outline.Generic(Path.combine(PathOperation.Union, body, tip))
    }
}

private fun Modifier.quoteSwipe(enabled: Boolean, onQuote: () -> Unit): Modifier = composed {
    if (!enabled) this
    else {
        var dragOffset by remember { mutableFloatStateOf(0f) }
        var dragging by remember { mutableStateOf(false) }
        val haptics = LocalHapticFeedback.current
        val offsetX by
            animateFloatAsState(
                targetValue = dragOffset,
                animationSpec = if (dragging) snap() else spring(),
                label = "quote-swipe-offset",
            )
        this.graphicsLayer { translationX = offsetX }.pointerInput(onQuote) {
            val threshold = 64.dp.toPx()
            val verticalTolerance = 24.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var quote = false
                var armed = false
                try {
                    dragging = true
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (
                            change.isConsumed ||
                                event.changes.size > 1 ||
                                (!armed &&
                                    change.uptimeMillis - down.uptimeMillis >=
                                        viewConfiguration.longPressTimeoutMillis)
                        )
                            break
                        val delta = change.position - down.position
                        if (abs(delta.y) > verticalTolerance) break
                        dragOffset = delta.x.coerceIn(-threshold, 0f)
                        val pastThreshold = delta.x <= -threshold && abs(delta.x) > abs(delta.y) * 3
                        if (pastThreshold && !armed) {
                            armed = true
                            haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        }
                        if (!change.pressed) {
                            quote = pastThreshold
                            break
                        }
                    }
                } finally {
                    dragging = false
                    dragOffset = 0f
                    if (quote) onQuote()
                }
            }
        }
    }
}

/**
 * A brief 2dp outline in the primary color that fades out, for the target of a jump. It starts on
 * the transition to [highlighted] true; with animations off it holds for the same time instead.
 * Clearing [highlighted] early removes the outline at once.
 */
private fun Modifier.jumpHighlight(highlighted: Boolean): Modifier = composed {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(highlighted) {
        if (highlighted) {
            alpha.snapTo(1f)
            if (ValueAnimator.areAnimatorsEnabled())
                alpha.animateTo(0f, tween(durationMillis = 1500, easing = LinearOutSlowInEasing))
            else {
                delay(1500)
                alpha.snapTo(0f)
            }
        } else {
            // A cleared flag cancels a running fade; drop the outline instead of freezing it.
            alpha.snapTo(0f)
        }
    }
    val color = MaterialTheme.colorScheme.primary
    drawWithContent {
        drawContent()
        val a = alpha.value
        if (a > 0f) {
            val stroke = 2.dp.toPx()
            drawRoundRect(
                color = color.copy(alpha = a),
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(size.width - stroke, size.height - stroke),
                cornerRadius = CornerRadius(12.dp.toPx()),
                style = Stroke(stroke),
            )
        }
    }
}

@Composable
internal fun ConversationMessage(
    item: ConversationItem,
    thinkingDisplay: String,
    thinkingActive: Boolean,
    onQuote: (String) -> Unit,
    onOpenTool: ((ConversationItem.Activity) -> Unit)? = null,
    onOpenAgent: ((ConversationItem.Subagent, Int) -> Unit)? = null,
    onAskToFix: ((messageId: String) -> Unit)? = null,
    highlighted: Boolean = false,
    /** Set only for the first bubble of a user message when the chat can be rewound. */
    fork: MessageFork? = null,
    /** Without a source, sent images show as plain text like any other attachment. */
    images: SentImageSource? = null,
) {
    Box(Modifier.jumpHighlight(highlighted)) {
        ConversationMessageContent(
            item,
            thinkingDisplay,
            thinkingActive,
            onQuote,
            onOpenTool,
            onOpenAgent,
            onAskToFix,
            fork,
            images,
        )
    }
}

@Composable
private fun ConversationMessageContent(
    item: ConversationItem,
    thinkingDisplay: String,
    thinkingActive: Boolean,
    onQuote: (String) -> Unit,
    onOpenTool: ((ConversationItem.Activity) -> Unit)?,
    onOpenAgent: ((ConversationItem.Subagent, Int) -> Unit)?,
    onAskToFix: ((messageId: String) -> Unit)?,
    fork: MessageFork?,
    images: SentImageSource?,
) {
    val quoteLabel = stringResource(R.string.remote_quote)
    val forkLabel = stringResource(R.string.remote_fork_action)
    when (item) {
        is ConversationItem.Bubble -> {
            val outgoing = item.role == "user"
            val canQuote = item.text.isNotBlank()
            val forkShown = fork != null && outgoing
            var forkMenu by remember(item.id) { mutableStateOf(false) }
            var forkConfirming by remember(item.id) { mutableStateOf<ForkMode?>(null) }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val width = if (item.text.contains("```")) maxWidth else maxWidth * 0.88f
                Column(
                    Modifier.align(if (outgoing) Alignment.CenterEnd else Alignment.CenterStart)
                        .widthIn(max = width)
                ) {
                    if (!outgoing)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                item.author ?: stringResource(R.string.remote_model_unknown),
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(1f, fill = false).padding(start = 8.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    Row(verticalAlignment = Alignment.Bottom) {
                        if (forkShown)
                            ForkControl(
                                item.sourceId,
                                item.text,
                                fork,
                                forkMenu,
                                { forkMenu = it },
                                forkConfirming,
                                { forkConfirming = it },
                            )
                        Surface(
                            modifier = Modifier.weight(1f, fill = false)
                                .quoteSwipe(canQuote) { onQuote(item.sourceId) }
                                .semantics {
                                    customActions = buildList {
                                        if (canQuote)
                                            add(
                                                CustomAccessibilityAction(quoteLabel) {
                                                    onQuote(item.sourceId)
                                                    true
                                                }
                                            )
                                        if (forkShown)
                                            add(
                                                CustomAccessibilityAction(forkLabel) {
                                                    forkMenu = true
                                                    true
                                                }
                                            )
                                    }
                                },
                            shape = remember(outgoing) { BubbleShape(outgoing) },
                            color =
                                if (outgoing) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainer,
                        ) {
                            Column(
                                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                    item.quote?.let { QuotePreview(it) }
                                    val sentImages =
                                        if (images == null) emptyList()
                                        else item.attachments.filter { it.kind == "image" }
                                    if (images != null && sentImages.isNotEmpty())
                                        SentImageAttachments(sentImages, images)
                                    for (attachment in item.attachments - sentImages.toSet()) {
                                        Column(Modifier.padding(vertical = 4.dp)) {
                                            Text(
                                                attachment.name,
                                                style = MaterialTheme.typography.labelLarge,
                                            )
                                            Text(
                                                Formatter.formatShortFileSize(
                                                    LocalContext.current,
                                                    attachment.size,
                                                ),
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                            Text(
                                                stringResource(
                                                    if (
                                                        attachment.expiresAt <=
                                                            System.currentTimeMillis()
                                                    )
                                                        R.string.remote_attachment_expired
                                                    else R.string.remote_attachment_expires,
                                                    java.text.DateFormat.getDateTimeInstance(
                                                            java.text.DateFormat.SHORT,
                                                            java.text.DateFormat.SHORT,
                                                        )
                                                        .format(java.util.Date(attachment.expiresAt)),
                                                ),
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        }
                                    }
                                    if (item.text.isNotEmpty())
                                        SelectionContainer { MarkdownText(item.text) }
                                    if (item.truncated)
                                        Text(
                                            stringResource(R.string.remote_truncated),
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    item.timestamp?.let { MessageTimestamp(it) }
                            }
                        }
                    }
                    if (!outgoing && item.role == "assistant") item.usage?.let { UsageCaption(it) }
                }
            }
        }
        is ConversationItem.Subagent -> {
            var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        Modifier.fillMaxWidth().clickable { expanded = !expanded }.heightIn(min = 40.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.remote_subagent_title),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = stringResource(
                                if (expanded) R.string.remote_hide_output else R.string.remote_show_output
                            ),
                        )
                    }
                    if (item.legacy || item.agents.isEmpty()) {
                        Text(stringResource(R.string.remote_subagent_unavailable), style = MaterialTheme.typography.bodySmall)
                    } else {
                        val openAgentLabel = stringResource(R.string.remote_card_open_subagent)
                        for ((index, agent) in item.agents.withIndex()) {
                            val state = when (agent.state) {
                                "queued" -> R.string.remote_subagent_queued
                                "running" -> R.string.remote_subagent_running
                                "succeeded" -> R.string.remote_subagent_succeeded
                                "failed" -> R.string.remote_subagent_failed
                                else -> R.string.remote_subagent_cancelled
                            }
                            Column(
                                if (onOpenAgent != null)
                                    Modifier.fillMaxWidth()
                                        .testTag("subagentAgent")
                                        .clickable(onClickLabel = openAgentLabel) { onOpenAgent(item, index) }
                                        .heightIn(min = 40.dp)
                                else Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text("${agent.name} · ${stringResource(state)}", style = MaterialTheme.typography.labelLarge)
                                agent.task?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                agent.usage?.let { AgentUsageLine(it) }
                                agent.activity?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                if (!expanded || item.output == null)
                                    agent.preview?.let {
                                        Text(
                                            inlineMarkdown(
                                                subagentPreviewText(it),
                                                MaterialTheme.colorScheme.primary,
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                            }
                        }
                    }
                    if (expanded) {
                        item.output?.let { SelectionContainer { MarkdownText(it) } }
                        if (item.truncated)
                            Text(
                                stringResource(R.string.remote_truncated),
                                style = MaterialTheme.typography.labelSmall,
                            )
                    }
                }
            }
        }
        // Mirrors conversationItemVisible: any other Thinking state renders nothing.
        is ConversationItem.Thinking -> {
            if (thinkingDisplay == "text" && item.text.isNotEmpty())
                CompositionLocalProvider(
                    LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant
                ) {
                    SelectionContainer {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                            MarkdownText(item.text)
                        }
                    }
                }
            else if (item.streaming && thinkingActive) ThinkingStatus(true)
        }
        is ConversationItem.Activity -> {
            val planMarkdown = remember(item.name, item.arguments) {
                if (item.name == "submit_plan")
                    runCatching {
                        Json.parseToJsonElement(item.arguments.orEmpty()).jsonObject["plan"]
                            ?.jsonPrimitive?.content
                    }.getOrNull()
                else null
            }
            var expanded by remember(item.id) { mutableStateOf(planMarkdown != null) }
            // Plan cards keep their inline toggle; other tools open the detail screen when the host
            // screen offers one, and the expand icon toggles the inline preview on its own.
            val openTool = onOpenTool.takeIf { planMarkdown == null }
            val diff = remember(item.arguments, item.details) { toolDiff(item) }
            val summary = item.summary
            val activityShape = MaterialTheme.shapes.medium
            val activityContainer =
                if (item.state == "error") MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.surfaceContainer
            Card(
                Modifier.fillMaxWidth()
                    .testTag("toolCard")
                    .quoteSwipe(!expanded && !item.output.isNullOrBlank()) {
                        onQuote(item.sourceId)
                    }
                    .semantics {
                        if (!item.output.isNullOrBlank())
                            customActions =
                                listOf(
                                    CustomAccessibilityAction(quoteLabel) {
                                        onQuote(item.sourceId)
                                        true
                                    }
                                )
                },
                shape = activityShape,
                colors =
                    CardDefaults.cardColors(
                        containerColor = activityContainer,
                    ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Box(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                    val state =
                        when (item.state) {
                            "error" -> R.string.remote_tool_error
                            "streaming" ->
                                if (thinkingActive) R.string.remote_tool_pending
                                else R.string.remote_tool_unavailable
                            "unavailable" -> R.string.remote_tool_unavailable
                            else -> R.string.remote_tool_complete
                        }
                    val stateLabel = stringResource(state)
                    val openToolLabel = stringResource(R.string.remote_card_open_tool)
                    Row(
                        Modifier.fillMaxWidth()
                            .testTag("toolCardHeader")
                            .then(
                                if (openTool != null)
                                    Modifier.clickable(onClickLabel = openToolLabel) { openTool(item) }
                                else Modifier.clickable { expanded = !expanded }
                            )
                            .heightIn(min = 40.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (planMarkdown != null) stringResource(R.string.remote_plan_title)
                                else summary.labelRes?.let { stringResource(it) }
                                    ?: item.name
                                    ?: stringResource(R.string.remote_tool),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            if (summary.detail != null || diff != null)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    summary.detail?.let {
                                        Text(
                                            it,
                                            modifier = Modifier.weight(1f, fill = false),
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    diff?.let { DiffCounts(it) }
                                }
                        }
                        when (state) {
                            R.string.remote_tool_error ->
                                Icon(
                                    Icons.Default.Error,
                                    stateLabel,
                                    Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            R.string.remote_tool_complete ->
                                Icon(
                                    Icons.Default.Check,
                                    stateLabel,
                                    Modifier.size(18.dp),
                                    tint = sessionStatusColor(SessionAvailability.IDLE),
                                )
                            R.string.remote_tool_pending ->
                                CircularProgressIndicator(
                                    Modifier.size(16.dp).semantics { contentDescription = stateLabel },
                                    strokeWidth = 2.dp,
                                )
                            else ->
                                Icon(
                                    Icons.Default.Remove,
                                    stateLabel,
                                    Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.outline,
                                )
                        }
                        val toggleIcon = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore
                        val toggleLabel =
                            stringResource(
                                if (expanded) R.string.remote_hide_output
                                else R.string.remote_show_output
                            )
                        if (openTool != null)
                            IconButton(
                                onClick = { expanded = !expanded },
                                modifier = Modifier.size(40.dp).testTag("toolCardToggle"),
                            ) {
                                Icon(toggleIcon, toggleLabel)
                            }
                        else Icon(toggleIcon, toggleLabel)
                    }
                    diff?.let { DiffView(it.lines, Modifier.fillMaxWidth(), maxLines = 12) }
                    if (item.state == "error" && onAskToFix != null)
                        item.outputMessageId?.let { messageId ->
                            TextButton(
                                onClick = { onAskToFix(messageId) },
                                modifier = Modifier.testTag("askToFix"),
                            ) {
                                Text(stringResource(R.string.remote_tool_detail_ask_fix))
                            }
                        }
                    if (expanded) {
                        item.arguments?.let {
                            Text(
                                stringResource(if (planMarkdown != null) R.string.remote_plan_title else R.string.remote_tool_arguments),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            if (planMarkdown != null) SelectionContainer { MarkdownText(planMarkdown) }
                            else ArgumentsText(it)
                        }
                        item.output?.let {
                            Text(
                                stringResource(R.string.remote_tool_output),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            SelectionContainer { MarkdownText(it) }
                            TextButton(onClick = { onQuote(item.sourceId) }) { Text(quoteLabel) }
                        }
                        if (item.truncated)
                            Text(
                                stringResource(R.string.remote_truncated),
                                style = MaterialTheme.typography.labelSmall,
                            )
                    }
                    }
                }
            }
        }
    }
}

/** Arguments as plain monospace rows; Markdown would break on fences inside the values. */
@Composable
private fun ArgumentsText(arguments: String) {
    val text =
        remember(arguments) {
            val rows = outputLines(prettyArguments(arguments) ?: arguments)
            rows.take(MAX_CARD_ARGUMENT_ROWS).joinToString("\n") +
                if (rows.size > MAX_CARD_ARGUMENT_ROWS) "\n…" else ""
        }
    // Rows are soft-split at MAX_CODE_LINE_CHARS, which keeps the unbounded width layout safe.
    SelectionContainer {
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("toolCardArguments")) {
            Text(text, style = monoTextStyle(), softWrap = false)
        }
    }
}

private const val MAX_CARD_ARGUMENT_ROWS = 400

@Composable
private fun DiffCounts(diff: ToolDiff) {
    val description = stringResource(R.string.remote_tool_detail_diff_summary, diff.added, diff.removed)
    Text(
        stringResource(R.string.remote_card_diff_counts, diff.added, diff.removed),
        modifier = Modifier.testTag("toolCardDiffCounts").semantics { contentDescription = description },
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

@Composable
private fun UsageCaption(usage: MessageUsage) {
    val locale = LocalConfiguration.current.locales[0]
    val input = formatTokenCount(usage.input, locale)
    val output = formatTokenCount(usage.output, locale)
    val spelledInput = spelledCount(usage.input, locale)
    val spelledOutput = spelledCount(usage.output, locale)
    val cost = usage.cost?.let { formatCost(it, locale) }
    val text =
        if (cost == null) stringResource(R.string.remote_card_usage, input, output)
        else stringResource(R.string.remote_card_usage_cost, input, output, cost)
    val description =
        if (cost == null) stringResource(R.string.remote_card_usage_description, spelledInput, spelledOutput)
        else stringResource(R.string.remote_card_usage_cost_description, spelledInput, spelledOutput, cost)
    Text(
        text,
        modifier =
            Modifier.padding(start = 16.dp, top = 2.dp)
                .testTag("messageUsage")
                .semantics { contentDescription = description },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun AgentUsageLine(usage: AgentUsage) {
    val locale = LocalConfiguration.current.locales[0]
    val turns = usage.turns.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    val tokens = usage.input + usage.output + usage.cacheRead + usage.cacheWrite
    val turnsText = pluralStringResource(R.plurals.remote_card_agent_turns, turns, turns)
    val cost = formatCost(usage.cost, locale)
    val description =
        stringResource(R.string.remote_card_agent_usage_description, turnsText, spelledCount(tokens, locale), cost)
    Text(
        stringResource(R.string.remote_card_agent_usage, turnsText, formatTokenCount(tokens, locale), cost),
        modifier = Modifier.testTag("subagentUsage").semantics { contentDescription = description },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun spelledCount(value: Long, locale: Locale): String =
    NumberFormat.getIntegerInstance(locale).format(value.coerceAtLeast(0))

internal fun subagentPreviewText(preview: String): String =
    preview.replace(Regex("\\[([^]\\n]+)]\\(https?://[^)\\s]*$"), "$1…")

@Composable
private fun MessageTimestamp(timestamp: Long) {
    val context = LocalContext.current
    val date = Date(timestamp)
    val time = DateFormat.getTimeFormat(context).format(date)
    val label =
        if (DateUtils.isToday(timestamp)) time
        else "${DateFormat.getDateFormat(context).format(date)} $time"
    Text(
        label,
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = androidx.compose.ui.text.style.TextAlign.End,
    )
}

@Composable
private fun ThinkingStatus(active: Boolean) {
    val animate = active && ValueAnimator.areAnimatorsEnabled()
    var labelIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(animate) {
        labelIndex = 0
        if (animate)
            while (true) {
                delay(3000)
                labelIndex = (labelIndex + 1) % 2
            }
    }
    val alpha =
        if (animate) {
            val transition = rememberInfiniteTransition(label = "thinking")
            transition
                .animateFloat(
                    0.35f,
                    1f,
                    infiniteRepeatable(tween(900), RepeatMode.Reverse),
                    label = "thinking-alpha",
                )
                .value
        } else 1f
    Row(
        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(7.dp)
                .graphicsLayer { this.alpha = alpha }
                .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)
        )
        Text(
            stringResource(
                if (!active) R.string.remote_thinking_complete
                else if (labelIndex == 0) R.string.remote_thinking_active
                else R.string.remote_thinking_processing
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
