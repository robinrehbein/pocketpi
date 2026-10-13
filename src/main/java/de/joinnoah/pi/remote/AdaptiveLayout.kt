package de.joinnoah.pi.remote

import android.content.res.Configuration
import android.animation.ValueAnimator
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.lerp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import kotlinx.serialization.json.JsonObject

/*
 * Tablet layout. The window size alone picks the layout, so portrait, landscape, split-screen
 * and DeX windows all follow the same rules, and a resize simply picks again:
 *
 * - below 720 dp wide or 480 dp high: the phone layout, one full-screen destination at a time
 *   (unchanged), so a phone in landscape keeps it;
 * - from 720 dp wide and 480 dp high: columns. The host's projects on the left, the selected
 *   project's sessions beside them and the selected chat on the right. Once a chat is open, the
 *   projects column narrows to a strip of project avatars, so another project is one tap away.
 *   The session list can be collapsed so the chat gets the rest of the width;
 * - from about 750 dp: tool details, diffs, files and jobs open in an inspector pane beside the
 *   chat instead of covering it, as tabs, and the pane closes with its last tab. Where the session
 *   list, the chat and the inspector do not fit side by side, opening the inspector collapses the
 *   list and closing it brings the list back ([PaneLayout.autoCollapseList]);
 * - from 1600 dp (DeX): the inspector pane stays open, with jobs or the changes by default.
 *
 * A Galaxy Tab S11 reports about 753-800 dp in portrait and 1205-1280 dp in landscape, depending
 * on the display zoom, so both orientations land in a column bucket and both open the inspector
 * beside the chat. Every bucket leaves an open chat at least [CHAT_PANE_MIN_WIDTH] beside the
 * strip and the default session list, or beside the strip and the inspector.
 *
 * The back stack stays the single source of truth in every layout: the columns are only a
 * different way to show the same [RemoteNavKey.Projects], [RemoteNavKey.Sessions] and
 * [RemoteNavKey.Chat] entries, so rotation, split-screen resizes and deep links keep the selected
 * project, session and draft.
 */

internal val TWO_PANE_MIN_WIDTH = 720.dp

/**
 * A phone in landscape is wide enough for two panes but only about 330 dp high, too little for a
 * list and a chat with its composer and keyboard, so it keeps the phone layout.
 */
internal val TWO_PANE_MIN_HEIGHT = 480.dp
/** From here the landscape buckets take a narrower session list and a wider inspector. */
internal val INSPECTOR_MIN_WIDTH = 1180.dp
internal val PERSISTENT_INSPECTOR_MIN_WIDTH = 1600.dp

/** Widest a chat bubble column and the composer grow; wider chat panes centre them. */
internal val MAX_READING_WIDTH = 840.dp

/**
 * Widest a full-screen page (hosts, projects, folders, providers, settings) grows; wider windows
 * centre it.
 */
internal val MAX_PAGE_WIDTH = 720.dp

internal enum class InspectorMode {
    /** Tool details and diffs cover the chat, as on a phone. */
    OVERLAY,
    /** A side pane opens with its first tab and closes with its last one. */
    ON_DEMAND,
    /** The side pane is always there. */
    PERSISTENT,
}

internal data class PaneLayout(
    val twoPane: Boolean,
    val inspector: InspectorMode,
    val listWidth: Dp = 0.dp,
    val inspectorWidth: Dp = 0.dp,
    /** The projects column's width while no chat is open; it is not resizable. */
    val projectsWidth: Dp = 0.dp,
    /** The narrowest the inspector pane gets: portrait tablets take a narrower one. */
    val inspectorMinWidth: Dp = INSPECTOR_PANE_MIN_WIDTH,
    /**
     * The session list, the chat and the inspector at their default widths would leave the chat
     * under [COMFORTABLE_CHAT_WIDTH], so opening the inspector collapses the list
     * ([TwoPaneState.autoCollapseList]) and closing it brings the list back.
     */
    val autoCollapseList: Boolean = false,
    /**
     * The inspector at its default width fits beside the default session list and the chat.
     * Where it does not, it shows beside the chat only while the list is collapsed, and covers
     * the chat while the user keeps the list open.
     */
    val inspectorBesideList: Boolean = false,
) {
    companion object {
        val Phone = PaneLayout(twoPane = false, inspector = InspectorMode.OVERLAY)
    }
}

/**
 * The layout of a [width] by [height] window. The inspector opens beside the chat wherever the
 * chat and the narrowest inspector fit next to the project strip, that is once the session list
 * can make way: 753 and 800 dp portrait windows qualify with [SIDE_INSPECTOR_MIN_WIDTH].
 */
internal fun paneLayout(width: Dp, height: Dp): PaneLayout {
    if (width < TWO_PANE_MIN_WIDTH || height < TWO_PANE_MIN_HEIGHT) return PaneLayout.Phone
    val columns =
        when {
            width < 750.dp ->
                PaneLayout(
                    true, InspectorMode.ON_DEMAND, listWidth = 240.dp, inspectorWidth = SIDE_INSPECTOR_MIN_WIDTH,
                    projectsWidth = 240.dp, inspectorMinWidth = SIDE_INSPECTOR_MIN_WIDTH,
                )
            width < 960.dp ->
                PaneLayout(
                    true, InspectorMode.ON_DEMAND, listWidth = 280.dp, inspectorWidth = 320.dp, projectsWidth = 240.dp,
                    inspectorMinWidth = SIDE_INSPECTOR_MIN_WIDTH,
                )
            width < INSPECTOR_MIN_WIDTH ->
                PaneLayout(true, InspectorMode.ON_DEMAND, listWidth = 360.dp, inspectorWidth = 360.dp, projectsWidth = 280.dp)
            width < 1280.dp ->
                PaneLayout(
                    true, InspectorMode.ON_DEMAND, listWidth = 320.dp, inspectorWidth = 360.dp, projectsWidth = 280.dp,
                )
            width < PERSISTENT_INSPECTOR_MIN_WIDTH ->
                PaneLayout(
                    true, InspectorMode.ON_DEMAND, listWidth = 360.dp, inspectorWidth = 400.dp, projectsWidth = 280.dp,
                )
            else ->
                PaneLayout(
                    true, InspectorMode.PERSISTENT, listWidth = 360.dp, inspectorWidth = 480.dp, projectsWidth = 280.dp,
                )
        }
    // What the chat and the inspector share once the list is collapsed; the strip always stays.
    val room = width - PROJECT_STRIP_RESERVE
    if (room < CHAT_PANE_MIN_WIDTH + PANE_DIVIDER_WIDTH + columns.inspectorMinWidth)
        return columns.copy(inspector = InspectorMode.OVERLAY, inspectorWidth = 0.dp, inspectorMinWidth = INSPECTOR_PANE_MIN_WIDTH)
    val besideList = room - columns.listWidth - PANE_DIVIDER_WIDTH
    return columns.copy(
        autoCollapseList = besideList - columns.inspectorWidth - PANE_DIVIDER_WIDTH < COMFORTABLE_CHAT_WIDTH,
        inspectorBesideList = besideList >= CHAT_PANE_MIN_WIDTH + PANE_DIVIDER_WIDTH + columns.inspectorWidth,
    )
}

/*
 * The dividers beside the session list and the inspector pane can be dragged. A width set that
 * way holds until the layout bucket changes; every clamp leaves the chat at least
 * [CHAT_PANE_MIN_WIDTH] wide beside the project strip, counting the 1 dp divider lines.
 */

internal val LIST_PANE_MIN_WIDTH = 240.dp
internal val LIST_PANE_MAX_WIDTH = 480.dp
internal val INSPECTOR_PANE_MIN_WIDTH = 300.dp
internal val INSPECTOR_PANE_MAX_WIDTH = 720.dp
internal val CHAT_PANE_MIN_WIDTH = 400.dp

/** The narrowest inspector of a portrait tablet: 753 dp leave 287 dp beside the chat's minimum. */
internal val SIDE_INSPECTOR_MIN_WIDTH = 280.dp

/** A chat narrower than this beside the list and the inspector is cramped; the list makes way. */
internal val COMFORTABLE_CHAT_WIDTH = 560.dp

/**
 * An expanded inspector gets everything but the chat's minimum; where that would be narrower than
 * this, the chat shrinks to a [CHAT_STRIP_WIDTH] strip instead.
 */
internal val EXPANDED_INSPECTOR_MIN_WIDTH = 800.dp
internal val CHAT_STRIP_WIDTH = 48.dp

/** How far the "wider" and "narrower" accessibility actions move a divider. */
internal val PANE_RESIZE_STEP = 40.dp

internal val PANE_DIVIDER_WIDTH = 1.dp
private val PANE_RESIZE_HANDLE_WIDTH = 24.dp

/** How far the inspector's resize handle reaches into the pane before it. */
internal val PANE_RESIZE_HANDLE_OVERHANG = PANE_RESIZE_HANDLE_WIDTH / 2 - PANE_DIVIDER_WIDTH / 2

/** The projects column's width while a chat is open: a strip of project avatars. */
internal val PROJECT_STRIP_WIDTH = 64.dp

/**
 * What the project strip and its divider take from the window. The session list is clamped
 * against the rest at every level, so it keeps its width when a chat opens.
 */
internal val PROJECT_STRIP_RESERVE = PROJECT_STRIP_WIDTH + PANE_DIVIDER_WIDTH

/**
 * The session list's width for [requested] in a [window] wide [layout]: it leaves room for the
 * chat and, where the inspector opens beside the list, the narrowest inspector pane. Where it
 * does not, opening the inspector collapses the list instead.
 */
internal fun listPaneWidth(requested: Dp, window: Dp, layout: PaneLayout): Dp {
    val inspectorReserve =
        if (layout.inspector == InspectorMode.OVERLAY || !layout.inspectorBesideList) 0.dp
        else layout.inspectorMinWidth + PANE_DIVIDER_WIDTH
    val reserve = CHAT_PANE_MIN_WIDTH + PANE_DIVIDER_WIDTH + inspectorReserve
    return requested.coerceIn(LIST_PANE_MIN_WIDTH, maxOf(LIST_PANE_MIN_WIDTH, minOf(LIST_PANE_MAX_WIDTH, window - reserve)))
}

/**
 * The inspector pane's width for [requested] beside a chat with [available] room for both, no
 * narrower than [minimum].
 */
internal fun inspectorPaneWidth(requested: Dp, available: Dp, minimum: Dp = INSPECTOR_PANE_MIN_WIDTH): Dp =
    requested.coerceIn(
        minimum,
        maxOf(minimum, minOf(INSPECTOR_PANE_MAX_WIDTH, available - CHAT_PANE_MIN_WIDTH - PANE_DIVIDER_WIDTH)),
    )

/** An expanded inspector in [available] room leaves the chat only a strip. */
internal fun chatStripped(available: Dp): Boolean =
    available - CHAT_PANE_MIN_WIDTH - PANE_DIVIDER_WIDTH < EXPANDED_INSPECTOR_MIN_WIDTH

/**
 * The expanded inspector's width in [available] room: all but the chat's minimum, past
 * [INSPECTOR_PANE_MAX_WIDTH], or all but the chat strip where that would stay under
 * [EXPANDED_INSPECTOR_MIN_WIDTH].
 */
internal fun expandedInspectorWidth(available: Dp): Dp =
    available - PANE_DIVIDER_WIDTH - if (chatStripped(available)) CHAT_STRIP_WIDTH else CHAT_PANE_MIN_WIDTH

/** Identifies a layout bucket for [TwoPaneState]; a dragged width belongs to one bucket. */
internal fun PaneLayout.sizeKey(): String = "${inspector.name}:${listWidth.value}:${inspectorWidth.value}"

/**
 * The invisible, [PANE_RESIZE_HANDLE_WIDTH] wide strip over a divider line. A drag calls
 * [onDragStart], then [onDrag] with the whole distance moved since the start towards the layout's
 * end side, so right-to-left layouts need no sign of their own and a pane held at a limit lets go
 * as soon as the finger comes back. Double tap resets; TalkBack offers [label] with wider,
 * narrower ([onStep] moves by a delta) and reset actions.
 */
@Composable
internal fun PaneResizeHandle(
    label: String,
    onDragStart: () -> Unit,
    onDrag: (Dp) -> Unit,
    onStep: (Dp) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val currentReset by rememberUpdatedState(onReset)
    val currentDragStart by rememberUpdatedState(onDragStart)
    val currentDrag by rememberUpdatedState(onDrag)
    var dragged by remember { mutableFloatStateOf(0f) }
    val wider = stringResource(R.string.remote_pane_wider)
    val narrower = stringResource(R.string.remote_pane_narrower)
    val reset = stringResource(R.string.remote_pane_reset_width)
    val context = LocalContext.current
    val hoverIcon =
        remember(context) {
            PointerIcon(android.view.PointerIcon.getSystemIcon(context, android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW))
        }
    Box(
        modifier
            .width(PANE_RESIZE_HANDLE_WIDTH)
            .fillMaxHeight()
            .pointerHoverIcon(hoverIcon)
            .draggable(
                orientation = Orientation.Horizontal,
                state =
                    rememberDraggableState { px ->
                        dragged += if (rtl) -px else px
                        with(density) { currentDrag(dragged.toDp()) }
                    },
                onDragStarted = {
                    dragged = 0f
                    currentDragStart()
                },
            )
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { currentReset() }) }
            .semantics {
                contentDescription = label
                customActions =
                    listOf(
                        CustomAccessibilityAction(wider) {
                            onStep(PANE_RESIZE_STEP)
                            true
                        },
                        CustomAccessibilityAction(narrower) {
                            onStep(-PANE_RESIZE_STEP)
                            true
                        },
                        CustomAccessibilityAction(reset) {
                            onReset()
                            true
                        },
                    )
            }
    )
}

/**
 * Hosts a full-screen page no wider than [MAX_PAGE_WIDTH], centred in wider windows. The page
 * background fills the gutters so transitions never show the window behind them.
 */
@Composable
internal fun CenteredPage(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(Modifier.widthIn(max = MAX_PAGE_WIDTH).fillMaxWidth().fillMaxHeight().testTag("pageColumn")) {
                content()
            }
        }
    }
}

/** The layout of the current window; the phone layout wherever nothing provides one. */
internal val LocalPaneLayout = staticCompositionLocalOf { PaneLayout.Phone }

/**
 * Two-pane state that is not part of the back stack. The collapsed list, the expanded inspector
 * and the dragged pane widths survive recreation; the rest is rebuilt by the panes themselves.
 */
@Stable
internal class TwoPaneState(listCollapsed: Boolean = false) {
    private var collapsed by mutableStateOf(listCollapsed)

    /**
     * The session list is hidden beside the chat. Setting it, as the user's toggle, Back and the
     * shortcuts do, makes the state the user's own: closing the inspector then leaves it alone.
     */
    var listCollapsed: Boolean
        get() = collapsed
        set(value) {
            collapsed = value
            autoCollapsed = false
        }

    /**
     * The list collapsed by itself to make room for the inspector ([autoCollapseList]); closing
     * the inspector brings it back ([restoreList]).
     */
    var autoCollapsed by mutableStateOf(false)
        private set

    /**
     * The inspector takes all but the chat's minimum width, or all but a strip of the chat where
     * that would leave it narrow ([expandedInspectorWidth]).
     */
    var inspectorExpanded by mutableStateOf(false)

    /** The inspector opened where the list, the chat and it do not fit side by side. */
    fun autoCollapseList() {
        if (collapsed) return
        collapsed = true
        autoCollapsed = true
    }

    /** The inspector closed: a list that collapsed for it comes back, one the user hid stays hidden. */
    fun restoreList() {
        if (!autoCollapsed) return
        collapsed = false
        autoCollapsed = false
    }

    /** Dragged widths, null for the layout's default; they belong to the bucket [sizedFor]. */
    private var listOverride by mutableStateOf<Dp?>(null)
    private var inspectorOverride by mutableStateOf<Dp?>(null)
    private var sizedFor by mutableStateOf<String?>(null)

    /** The session list's requested width in [layout], before [listPaneWidth] clamps it. */
    fun listWidth(layout: PaneLayout): Dp =
        listOverride?.takeIf { sizedFor == layout.sizeKey() } ?: layout.listWidth

    /** The inspector pane's requested width in [layout], before [inspectorPaneWidth] clamps it. */
    fun inspectorWidth(layout: PaneLayout): Dp =
        inspectorOverride?.takeIf { sizedFor == layout.sizeKey() } ?: layout.inspectorWidth

    /** Sets the session list's width in [layout]; null goes back to its default. */
    fun resizeList(layout: PaneLayout, width: Dp?) {
        adopt(layout)
        listOverride = width
    }

    /** Sets the inspector pane's width in [layout]; null goes back to its default. */
    fun resizeInspector(layout: PaneLayout, width: Dp?) {
        adopt(layout)
        inspectorOverride = width
    }

    /**
     * Drops the dragged widths once the layout bucket changes. The same bucket, also right after
     * recreation, keeps them.
     */
    fun adopt(layout: PaneLayout) {
        val key = layout.sizeKey()
        if (sizedFor == key) return
        if (sizedFor != null) {
            listOverride = null
            inspectorOverride = null
        }
        sizedFor = key
    }

    companion object {
        val Saver: Saver<TwoPaneState, Any> =
            listSaver(
                save = {
                    listOf(
                        it.listCollapsed,
                        it.listOverride?.value ?: -1f,
                        it.inspectorOverride?.value ?: -1f,
                        it.sizedFor ?: "",
                        it.autoCollapsed,
                        it.inspectorExpanded,
                    )
                },
                restore = {
                    TwoPaneState(it[0] as Boolean).apply {
                        listOverride = (it[1] as Float).takeIf { width -> width >= 0f }?.dp
                        inspectorOverride = (it[2] as Float).takeIf { width -> width >= 0f }?.dp
                        sizedFor = (it[3] as String).ifEmpty { null }
                        // State saved by an older version has neither.
                        autoCollapsed = it.getOrNull(4) as? Boolean ?: false
                        inspectorExpanded = it.getOrNull(5) as? Boolean ?: false
                    }
                },
            )
    }
}

/** The two-pane state; null in the phone layout. */
internal val LocalTwoPane = staticCompositionLocalOf<TwoPaneState?> { null }

/**
 * Set around the session list while it is a column. [selectedSessionId] is the chat shown beside
 * it; [besideProjects] says the projects column is on its left, and [projectsStrip] that the
 * column is the narrow strip.
 */
internal data class ListPane(
    val selectedSessionId: String?,
    val besideProjects: Boolean = false,
    val projectsStrip: Boolean = false,
)

internal val LocalListPane = staticCompositionLocalOf<ListPane?> { null }

/** A hardware keyboard is attached; read once by [RemoteNavigation] and passed down. */
internal val LocalHardwareKeyboard = staticCompositionLocalOf { false }

/** True around the chat while it is the right pane of the two-pane layout. */
internal val LocalDetailPane = staticCompositionLocalOf { false }

/** Metadata key under which each Projects, Sessions and Chat entry carries its [RemoteNavKey]. */
internal const val NAV_KEY_METADATA = "de.joinnoah.pi.remote.navKey"

internal fun navKeyMetadata(key: RemoteNavKey): Map<String, Any> = mapOf(NAV_KEY_METADATA to key)

/**
 * Which back stack positions the columns show: the host's projects, a project's sessions and a
 * chat. Each is null when that column has nothing to show.
 */
internal data class ColumnPanes(val projects: Int?, val list: Int?, val detail: Int?)

/**
 * The column split of [keys], or null when the top destination is not part of it (hosts,
 * folders, providers, settings: those stay full screen). A chat pairs with the Sessions entry of
 * its project below it, skipping the parent chats a child chat is stacked on; the Sessions entry
 * pairs with its host's Projects right below it.
 */
internal fun columnPanes(keys: List<Any?>): ColumnPanes? {
    val lastIndex = keys.lastIndex
    fun projectsBelow(index: Int, routeId: String): Int? =
        (index - 1).takeIf { it >= 0 && keys[it] == RemoteNavKey.Projects(routeId) }
    return when (val top = keys.lastOrNull()) {
        is RemoteNavKey.Projects -> ColumnPanes(lastIndex, null, null)
        is RemoteNavKey.Sessions -> ColumnPanes(projectsBelow(lastIndex, top.routeId), lastIndex, null)
        is RemoteNavKey.Chat -> {
            var index = lastIndex - 1
            while (index >= 0) {
                when (val below = keys[index]) {
                    is RemoteNavKey.Chat ->
                        if (below.routeId != top.routeId || below.projectId != top.projectId) return null
                    is RemoteNavKey.Sessions ->
                        return if (below.routeId == top.routeId && below.projectId == top.projectId)
                            ColumnPanes(projectsBelow(index, top.routeId), index, lastIndex)
                        else null
                    else -> return null
                }
                index--
            }
            null
        }
        else -> null
    }
}

private data class ProjectColumnsSceneKey(val routeId: String)

/**
 * Shows a host's [RemoteNavKey.Projects], the [RemoteNavKey.Sessions] of the selected project and
 * the chat above it as columns. One scene covers all three levels of a host, so opening a
 * project, a session or another project keeps the scene and only changes its columns; the
 * columns animate their own entrance (see [rememberEnterProgress]).
 */
internal class ProjectColumnsSceneStrategy(
    private val layout: PaneLayout,
    private val state: TwoPaneState,
    /** A new strategy per value, so the scene and its back preview follow the list. */
    private val listCollapsed: Boolean = state.listCollapsed,
) : SceneStrategy<NavKey> {
    override fun SceneStrategyScope<NavKey>.calculateScene(
        entries: List<NavEntry<NavKey>>,
    ): Scene<NavKey>? {
        if (!layout.twoPane) return null
        val keys = entries.map { it.metadata[NAV_KEY_METADATA] }
        val panes = columnPanes(keys) ?: return null
        val routeId =
            when (val top = keys.last()) {
                is RemoteNavKey.Projects -> top.routeId
                is RemoteNavKey.Sessions -> top.routeId
                is RemoteNavKey.Chat -> top.routeId
                else -> return null
            }
        val detail = panes.detail?.let(entries::get)
        val collapsed = detail != null && listCollapsed
        return ProjectColumnsScene(
            key = ProjectColumnsSceneKey(routeId),
            projects = panes.projects?.let(entries::get),
            list = panes.list?.let(entries::get),
            detail = detail,
            // Back with the list collapsed only expands it (see twoPaneBackStep): the chat stays.
            // Navigation 3 can only preview a shorter stack, so this state offers no predictive
            // preview at all; the scene's own back handler expands the list instead.
            previousEntries = if (collapsed) emptyList() else entries.dropLast(1),
            layout = layout,
            state = state,
            collapsed = collapsed,
            onBack = onBack,
        )
    }
}

/** The easing of the column animations: a quick start that settles softly. */
private val ColumnEasing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

/** How far a column slides in from, and how far the back preview moves the top one. */
private val COLUMN_SLIDE = 48.dp

private data class ProjectColumnsScene(
    override val key: Any,
    private val projects: NavEntry<NavKey>?,
    private val list: NavEntry<NavKey>?,
    private val detail: NavEntry<NavKey>?,
    override val previousEntries: List<NavEntry<NavKey>>,
    private val layout: PaneLayout,
    private val state: TwoPaneState,
    private val collapsed: Boolean,
    private val onBack: () -> Unit,
) : Scene<NavKey> {
    // The Projects entry stays part of the scene beside a chat: it shows the strip.
    override val entries: List<NavEntry<NavKey>> = listOfNotNull(projects, list?.takeUnless { collapsed }, detail)

    private val selectedProjectId =
        (list?.metadata?.get(NAV_KEY_METADATA) as? RemoteNavKey.Sessions)?.projectId

    private val selectedSessionId =
        (detail?.metadata?.get(NAV_KEY_METADATA) as? RemoteNavKey.Chat)?.sessionId

    override val content: @Composable () -> Unit = {
        // Registered before the chat's own handlers, so an open tool detail or diff still closes
        // first; without a predictive preview (see previousEntries).
        BackHandler(enabled = collapsed) { state.listCollapsed = false }
        val strip = detail != null
        val projectsTarget = if (strip) PROJECT_STRIP_WIDTH else layout.projectsWidth
        val projectsWidth = rememberProjectsWidth(projectsTarget)
        // Back from the sessions or a chat stays in this scene, so Navigation 3 has no other scene
        // to preview; the scene previews it itself. Registered before the chat's own handlers too.
        val backPreview =
            rememberBackPreview(enabled = list != null && !collapsed, resetKey = (detail ?: list)?.contentKey) { progress ->
                // The strip goes on growing from where the gesture left it instead of jumping back.
                if (strip) projectsWidth.snapTo(lerp(projectsWidth.value, layout.projectsWidth, progress))
                onBack()
            }
        val listEnter = rememberEnterProgress(list?.contentKey)
        val detailEnter = rememberEnterProgress(detail?.contentKey)
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        val background = MaterialTheme.colorScheme.background
        // The projects column's width on screen. A back gesture from a chat widens the strip
        // towards the full column it goes back to. Only read while placing or drawing.
        fun projectsShown(): Dp =
            if (strip) lerp(projectsWidth.value, layout.projectsWidth, backPreview.value) else projectsWidth.value
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val window by rememberUpdatedState(maxWidth)
            val reserve = if (projects != null) PROJECT_STRIP_RESERVE else 0.dp
            val listWidth = listPaneWidth(state.listWidth(layout), maxWidth - reserve, layout)
            val direction = if (rtl) -1f else 1f
            val slide = with(LocalDensity.current) { COLUMN_SLIDE.toPx() } * direction
            // Slides a column in from the end side and, while it is the top one, moves it with a
            // back gesture. Read while drawing, so neither animation recomposes the column.
            fun Modifier.column(enter: Animatable<Float, AnimationVector1D>, top: Boolean) =
                graphicsLayer {
                    val back = if (top) backPreview.value else 0f
                    translationX = (1f - enter.value + back) * slide
                    alpha = enter.value * (1f - 0.5f * back)
                }
            // The row lays the projects column out at the width it is heading for at once, so the
            // session list and the chat are measured once per change and never while the column
            // grows or shrinks. The column draws its changing width above them, and they follow
            // its edge by moving only.
            // Each column applies only the side insets of the screen edge it touches; a cutout or
            // navigation bar beside the projects column would otherwise indent the list next to it.
            val startInset = WindowInsets.safeDrawing.only(WindowInsetsSides.Start)
            val endInset = WindowInsets.safeDrawing.only(WindowInsetsSides.End)
            Row(Modifier.fillMaxSize().background(background)) {
                if (projects != null) {
                    Box(
                        Modifier.width(projectsTarget)
                            .fillMaxHeight()
                            .consumeWindowInsets(endInset)
                            .zIndex(1f)
                            .testTag(if (strip) "projectStrip" else "projectsPane")
                    ) {
                        Box(
                            Modifier.wrapContentWidth(Alignment.Start, unbounded = true)
                                .width(maxOf(projectsTarget, layout.projectsWidth))
                                .fillMaxHeight()
                                .drawWithContent {
                                    val shown = projectsShown().toPx()
                                    val left = if (rtl) size.width - shown else 0f
                                    clipRect(left = left, right = left + shown) {
                                        drawRect(background)
                                        this@drawWithContent.drawContent()
                                    }
                                }
                        ) {
                            // At the target width too, so the content never reflows either.
                            Box(Modifier.width(projectsTarget).fillMaxHeight()) {
                                CompositionLocalProvider(
                                    LocalProjectsColumn provides ProjectsColumn(selectedProjectId, strip)
                                ) {
                                    projects.Content()
                                }
                            }
                        }
                    }
                }
                Row(
                    Modifier.weight(1f)
                        .fillMaxHeight()
                        .then(if (projects != null) Modifier.consumeWindowInsets(startInset) else Modifier)
                        .graphicsLayer {
                            if (projects != null) translationX = (projectsShown() - projectsTarget).toPx() * direction
                        }
                ) {
                    if (projects != null) VerticalDivider()
                    // Only a shown chat can take the list's room; without one the list is all there is.
                    if (list != null && !collapsed) {
                        Box(
                            Modifier.width(listWidth)
                                .fillMaxHeight()
                                .consumeWindowInsets(endInset)
                                .testTag("sessionListPane")
                                .column(listEnter, top = detail == null)
                        ) {
                            CompositionLocalProvider(
                                LocalListPane provides ListPane(selectedSessionId, projects != null, strip)
                            ) {
                                list.Content()
                            }
                        }
                        VerticalDivider()
                    }
                    Box(
                        Modifier.weight(1f)
                            .fillMaxHeight()
                            .then(if (list != null && !collapsed) Modifier.consumeWindowInsets(startInset) else Modifier)
                            .testTag("chatPane")
                    ) {
                        when {
                            list == null -> DetailPlaceholder(R.string.remote_projects_column_empty, "projectsPlaceholder")
                            detail == null -> DetailPlaceholder(R.string.remote_two_pane_empty, "chatPlaceholder")
                            else ->
                                key(detail.contentKey) {
                                    Box(Modifier.fillMaxSize().column(detailEnter, top = true)) {
                                        CompositionLocalProvider(LocalDetailPane provides true) { detail.Content() }
                                    }
                                }
                        }
                    }
                }
            }
            if (list != null && !collapsed) {
                // Reads the state on every call: several drag events can arrive between frames.
                fun currentWidth() = listPaneWidth(state.listWidth(layout), window - reserve, layout)
                fun resizeTo(width: Dp) =
                    state.resizeList(layout, listPaneWidth(width, window - reserve, layout))
                // A drag sets start + distance, so the divider follows the finger again after a clamp.
                var dragStart by remember { mutableStateOf(0.dp) }
                PaneResizeHandle(
                    label = stringResource(R.string.remote_pane_resize_list),
                    onDragStart = { dragStart = currentWidth() },
                    onDrag = { resizeTo(dragStart + it) },
                    onStep = { resizeTo(currentWidth() + it) },
                    onReset = { state.resizeList(layout, null) },
                    modifier =
                        Modifier.align(Alignment.CenterStart)
                            .offset {
                                // Placed, not composed, at the projects column's edge on screen.
                                val listStart = if (projects != null) projectsShown() + PANE_DIVIDER_WIDTH else 0.dp
                                IntOffset(
                                    (listStart + listWidth + PANE_DIVIDER_WIDTH / 2 - PANE_RESIZE_HANDLE_WIDTH / 2).roundToPx(),
                                    0,
                                )
                            }
                            .testTag("sessionListDivider"),
                )
            }
        }
    }
}

/**
 * The projects column's width, heading for [target]: the first composition starts there, later
 * targets animate unless animations are off. A back gesture snaps it to where the finger left the
 * column first, so the animation carries on from there.
 */
@Composable
private fun rememberProjectsWidth(target: Dp): Animatable<Dp, AnimationVector1D> {
    val width = remember { Animatable(target, Dp.VectorConverter) }
    LaunchedEffect(target) {
        if (ValueAnimator.areAnimatorsEnabled()) width.animateTo(target, tween(320, easing = ColumnEasing))
        else width.snapTo(target)
    }
    return width
}

/**
 * 0 to 1 while a column with [key] slides in. A new key animates from 0; the first composition
 * (a restore, a rotation, a resize into the columns), a null key and disabled animations start
 * at 1. Only entrances animate: a popped entry is gone at once and never drawn on its way out.
 */
@Composable
private fun rememberEnterProgress(key: Any?): Animatable<Float, AnimationVector1D> {
    val seen = remember { BooleanArray(1) }
    val progress =
        remember(key) {
            Animatable(if (seen[0] && key != null && ValueAnimator.areAnimatorsEnabled()) 0f else 1f)
        }
    SideEffect { seen[0] = true }
    LaunchedEffect(progress) { progress.animateTo(1f, tween(340, easing = ColumnEasing)) }
    return progress
}

/**
 * The progress, 0 to 1, of a back gesture that leaves the top column. Letting go runs [onBack]
 * with the progress it reached; a cancelled gesture eases back to 0. A plain Back press reaches
 * [onBack] the same way, with 0.
 */
@Composable
private fun rememberBackPreview(
    enabled: Boolean,
    resetKey: Any?,
    onBack: suspend (progress: Float) -> Unit,
): Animatable<Float, AnimationVector1D> {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val currentOnBack by rememberUpdatedState(onBack)
    LaunchedEffect(resetKey) { progress.snapTo(0f) }
    PredictiveBackHandler(enabled = enabled) { events ->
        try {
            events.collect { progress.snapTo(it.progress) }
            currentOnBack(progress.value)
            progress.snapTo(0f)
        } catch (e: CancellationException) {
            scope.launch { progress.animateTo(0f, tween(200, easing = ColumnEasing)) }
            throw e
        }
    }
    return progress
}

@Composable
private fun DetailPlaceholder(text: Int, tag: String) {
    Box(Modifier.fillMaxSize().padding(24.dp).testTag(tag), contentAlignment = Alignment.Center) {
        Text(
            stringResource(text),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * What Back does in the two-pane layout before the regular navigation runs. Tool details, diffs
 * and jobs close first through their own back handlers, in every layout. After that:
 *
 * 1. Settings on top closes (regular navigation).
 * 2. A chat shown with the session list collapsed brings the list back.
 * 3. Everything else is the phone's back stack: a child chat returns to its parent, a chat to
 *    its project's sessions (the full projects column, the list and an empty chat pane), the
 *    sessions to the projects alone and the projects to the hosts.
 */
internal enum class BackStep { EXPAND_LIST, NAVIGATE }

internal fun twoPaneBackStep(layout: PaneLayout, listCollapsed: Boolean, top: NavKey?): BackStep =
    if (layout.twoPane && listCollapsed && top is RemoteNavKey.Chat) BackStep.EXPAND_LIST
    else BackStep.NAVIGATE

/** A connected hardware keyboard, such as a Book Cover Keyboard; its keys only count then. */
@Composable
internal fun hardwareKeyboardAttached(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.keyboard != Configuration.KEYBOARD_NOKEYS &&
        configuration.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
}

/** Ctrl+N's condition, the same as the "new session" button: connected, not loading, in this project. */
internal fun canCreateSession(state: RemoteState, sessions: RemoteNavKey.Sessions): Boolean =
    state.connected &&
        !state.loading &&
        state.selection.routeId == sessions.routeId &&
        state.selection.projectId == sessions.projectId

/**
 * The session or project [step] rows away from [current] in [ids]; from no selection, the first
 * or last.
 */
internal fun adjacentId(ids: List<String>, current: String?, step: Int): String? {
    if (ids.isEmpty()) return null
    val index = ids.indexOf(current)
    if (index < 0) return if (step > 0) ids.first() else ids.last()
    return ids.getOrNull(index + step)
}

/** What a hardware Enter does in the composer. */
internal enum class ComposerEnter {
    /** The text field handles the key as before (a newline, or nothing). */
    DEFAULT,
    SEND,
    NEWLINE,
}

/**
 * Enter sends and Shift+Enter inserts a newline while [enterSends] is on; with it off, Enter keeps
 * inserting a newline. Ctrl+Enter sends either way. Only hardware keys count: an on-screen
 * keyboard sends its newline through the input connection, not as a key press.
 */
internal fun composerEnter(
    key: Key,
    keyDown: Boolean,
    ctrl: Boolean,
    shift: Boolean,
    alt: Boolean,
    hardwareKeyboard: Boolean,
    enterSends: Boolean,
): ComposerEnter {
    if (!hardwareKeyboard || !keyDown || (key != Key.Enter && key != Key.NumPadEnter)) return ComposerEnter.DEFAULT
    return when {
        alt -> ComposerEnter.DEFAULT
        ctrl -> ComposerEnter.SEND
        !enterSends -> ComposerEnter.DEFAULT
        shift -> ComposerEnter.NEWLINE
        else -> ComposerEnter.SEND
    }
}

/** What a hardware key does in the composer besides Enter ([composerEnter]). */
internal enum class ComposerKey {
    /** The text field handles the key as before. */
    DEFAULT,

    /** Moves the slash suggestions' highlight up or down. */
    SUGGESTION_UP,
    SUGGESTION_DOWN,

    /** Runs the highlighted slash suggestion, as a tap on it does. */
    SUGGESTION_RUN,

    /** Takes the prompt prediction into the empty composer. */
    ACCEPT_PREDICTION,

    /** Shows an older or newer user message of this session ([MessageRecall]). */
    PREVIOUS_MESSAGE,
    NEXT_MESSAGE,
}

/**
 * The composer's completion keys for a hardware key without modifiers. While the slash
 * suggestions show, Up and Down move the highlight, Tab runs it, and Enter runs it too unless the highlighted command is [highlightTyped] in full already, so a partial
 * "/com" is never sent. With an empty draft, Tab or Right takes a shown prediction, and Up walks
 * this session's earlier messages; while [recalling] one, Up and Down walk on. Esc never gets
 * here: the layout runs it through [ChatKeyTarget.escape], whose first step closes the list.
 */
internal fun composerKey(
    key: Key,
    keyDown: Boolean,
    ctrl: Boolean,
    alt: Boolean,
    shift: Boolean,
    hardwareKeyboard: Boolean,
    suggestionsOpen: Boolean,
    highlightTyped: Boolean,
    predictionShown: Boolean,
    draftEmpty: Boolean,
    recalling: Boolean,
): ComposerKey {
    if (!hardwareKeyboard || !keyDown || ctrl || alt || shift) return ComposerKey.DEFAULT
    if (suggestionsOpen)
        when (key) {
            Key.DirectionUp -> return ComposerKey.SUGGESTION_UP
            Key.DirectionDown -> return ComposerKey.SUGGESTION_DOWN
            Key.Tab -> return ComposerKey.SUGGESTION_RUN
            Key.Enter, Key.NumPadEnter -> return if (highlightTyped) ComposerKey.DEFAULT else ComposerKey.SUGGESTION_RUN
        }
    if (draftEmpty && predictionShown && (key == Key.Tab || key == Key.DirectionRight)) return ComposerKey.ACCEPT_PREDICTION
    if (draftEmpty || recalling)
        when (key) {
            Key.DirectionUp -> return ComposerKey.PREVIOUS_MESSAGE
            Key.DirectionDown -> if (recalling) return ComposerKey.NEXT_MESSAGE
        }
    return ComposerKey.DEFAULT
}

/** The highlight [step]s on through [count] suggestions, wrapping at either end. */
internal fun moveHighlight(current: Int, count: Int, step: Int): Int =
    if (count <= 0) 0 else Math.floorMod(current + step, count)

/** This session's user messages, newest first and without repeats in a row, for [MessageRecall]. */
internal fun recallableMessages(messages: List<JsonObject>): List<String> =
    messages.asReversed().asSequence()
        .filter { it.optionalText("role") == "user" }
        .mapNotNull { it.optionalText("text")?.takeIf(String::isNotBlank) }
        .fold(mutableListOf<String>()) { list, text -> list.apply { if (lastOrNull() != text) add(text) } }

/**
 * Up and Down in an empty composer, as in a shell: Up shows this session's previous message,
 * Down a newer one and past the newest an empty draft again. Editing the shown text ends it.
 */
internal class MessageRecall {
    private var index = -1
    private var shown: String? = null

    /** The composer shows [draft]: true while it is the recalled message, unchanged. */
    fun recalling(draft: String): Boolean = shown != null && shown == draft

    /**
     * The text to show for a step through [history] (newest first): 1 is older, -1 newer. Null
     * when there is nothing further that way.
     */
    fun step(history: List<String>, draft: String, step: Int): String? {
        if (!recalling(draft)) {
            index = -1
            shown = null
        }
        val next = index + step
        if (next >= history.size || index < 0 && step < 0) return null
        index = next
        if (next < 0) {
            shown = null
            return ""
        }
        return history[next].also { shown = it }
    }
}

/** The Settings keyboard section shows with a keyboard attached or in the wide layout. */
internal fun keyboardSettingsShown(hardwareKeyboard: Boolean, twoPane: Boolean): Boolean = hardwareKeyboard || twoPane

/** Case-insensitive title or preview match; a blank query keeps every row. */
internal fun filterSessions(items: List<SessionListItem>, query: String): List<SessionListItem> {
    val needle = query.trim()
    if (needle.isEmpty()) return items
    return items.filter {
        it.title.contains(needle, ignoreCase = true) || it.preview?.contains(needle, ignoreCase = true) == true
    }
}

/**
 * The chat with its inspector pane beside it while [shown]. The chat keeps its place in the
 * composition either way, so opening or closing the pane (or a resize across buckets) keeps its
 * scroll position and open tool.
 *
 * [width] is the requested pane width, read again on every drag event, and [minWidth] the
 * narrowest it gets; with [onResize] the divider can be dragged, and null resets the width.
 * [expanded] gives the pane all but the chat's minimum; where that stays narrow, the chat makes
 * way for [chatStrip] instead, and [onChatStripped] hears when that starts and ends.
 */
@Composable
internal fun InspectorRow(
    shown: Boolean,
    width: () -> Dp,
    onResize: ((Dp?) -> Unit)?,
    inspector: (@Composable () -> Unit)?,
    minWidth: Dp = INSPECTOR_PANE_MIN_WIDTH,
    expanded: Boolean = false,
    chatStrip: (@Composable () -> Unit)? = null,
    onChatStripped: (Boolean) -> Unit = {},
    content: @Composable () -> Unit,
) {
    // F6 reaches the pane's content, and comes back to the row it left there; the pane keeps its
    // own rows, apart from a pane over the chat beside it.
    val children = remember { FocusRegionChildren() }
    val region = rememberFocusRegion(FocusRegion.INSPECTOR, enabled = shown && inspector != null, children = children)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val available by rememberUpdatedState(maxWidth)
        val widened = shown && expanded
        val stripped = widened && chatStrip != null && chatStripped(maxWidth)
        val currentStripped by rememberUpdatedState(onChatStripped)
        LaunchedEffect(stripped) { currentStripped(stripped) }
        val paneWidth = if (widened) expandedInspectorWidth(maxWidth) else inspectorPaneWidth(width(), maxWidth, minWidth)
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                // A stripped chat keeps its usual width behind the strip, so nothing in it rewraps,
                // and TalkBack skips it.
                Box(
                    if (stripped)
                        Modifier.wrapContentWidth(Alignment.Start, unbounded = true)
                            .width(CHAT_PANE_MIN_WIDTH)
                            .fillMaxHeight()
                            .clearAndSetSemantics {}
                    else Modifier.fillMaxSize()
                ) {
                    content()
                }
                if (stripped && chatStrip != null) Box(Modifier.fillMaxSize().testTag("chatStrip")) { chatStrip() }
            }
            if (shown) {
                VerticalDivider()
                Box(Modifier.width(paneWidth).fillMaxHeight().testTag("inspectorPane").then(region)) {
                    if (inspector != null) CompositionLocalProvider(LocalFocusRegionChildren provides children) { inspector() }
                    else
                        Box(
                            Modifier.fillMaxSize().padding(32.dp).testTag("inspectorPlaceholder"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(R.string.remote_inspector_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                }
            }
        }
        // An expanded pane has no width of its own to drag.
        if (shown && !widened && onResize != null) {
            // The pane sits at the end side, so moving the divider towards the end narrows it.
            fun currentWidth() = inspectorPaneWidth(width(), available, minWidth)
            fun resizeTo(width: Dp) = onResize(inspectorPaneWidth(width, available, minWidth))
            var dragStart by remember { mutableStateOf(0.dp) }
            PaneResizeHandle(
                label = stringResource(R.string.remote_pane_resize_inspector),
                onDragStart = { dragStart = currentWidth() },
                onDrag = { resizeTo(dragStart - it) },
                onStep = { resizeTo(currentWidth() + it) },
                onReset = { onResize(null) },
                modifier =
                    Modifier.align(Alignment.CenterEnd)
                        .offset(x = -(paneWidth + PANE_DIVIDER_WIDTH / 2 - PANE_RESIZE_HANDLE_WIDTH / 2))
                        .testTag("inspectorDivider"),
            )
        }
    }
}

@Composable
internal fun SessionSearchField(
    query: String,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    focusRequester: FocusRequester,
) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        placeholder = { Text(stringResource(R.string.remote_session_search)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        singleLine = true,
        shape = CircleShape,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier =
            Modifier.fillMaxWidth()
                .focusRequester(focusRequester)
                // Down leaves the one-line field for the rows below it, as in a list.
                .onPreviewKeyEvent { event ->
                    event.key == Key.DirectionDown && event.type == KeyEventType.KeyDown &&
                        !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed &&
                        focusManager.moveFocus(FocusDirection.Down)
                }
                .testTag("sessionSearch"),
    )
}
