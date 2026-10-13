package de.joinnoah.pi.remote

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.invalidateSemantics
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusEventModifierNode

/** The outline colour of [focusRing]; [RemoteNavigation] provides the theme's primary colour. */
internal val LocalFocusRingColor = staticCompositionLocalOf { Color(0xFF6750A4) }

private val FOCUS_RING_WIDTH: Dp = 2.dp

/** Set on an element while [focusRing] draws its outline; for tests. */
internal val FocusRingShown = SemanticsPropertyKey<Boolean>("FocusRingShown")

/**
 * A 2 dp outline in [LocalFocusRingColor] around a row, chip or card while it has keyboard focus,
 * so a keyboard-only user sees where focus is. Put it before the element's `clickable` or
 * `focusable`, whose focus it shows. Touch never shows it.
 */
internal fun Modifier.focusRing(shape: Shape = RoundedCornerShape(12.dp)): Modifier = this then FocusRingElement(shape)

private data class FocusRingElement(val shape: Shape) : ModifierNodeElement<FocusRingNode>() {
    override fun create() = FocusRingNode(shape)

    override fun update(node: FocusRingNode) {
        node.shape = shape
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "focusRing"
        properties["shape"] = shape
    }
}

private class FocusRingNode(var shape: Shape) :
    Modifier.Node(), DrawModifierNode, FocusEventModifierNode, SemanticsModifierNode, CompositionLocalConsumerModifierNode {
    private var focused = false

    private val shown: Boolean
        get() = focused && currentValueOf(LocalInputModeManager).inputMode == InputMode.Keyboard

    override fun onFocusEvent(focusState: FocusState) {
        if (focused == focusState.isFocused) return
        focused = focusState.isFocused
        invalidateDraw()
        invalidateSemantics()
    }

    override fun SemanticsPropertyReceiver.applySemantics() {
        if (shown) this[FocusRingShown] = true
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (!shown) return
        val width = FOCUS_RING_WIDTH.toPx()
        inset(width / 2) {
            drawOutline(shape.createOutline(size, layoutDirection, this), currentValueOf(LocalFocusRingColor), style = Stroke(width))
        }
    }
}

/** The panes F6 moves between, in its order. A later region can lie inside an earlier one. */
internal enum class FocusRegion {
    PROJECTS,
    SESSIONS,
    CHAT,
    INSPECTOR,

    /** Files, Changes, a tool detail or the jobs over the chat, beside a side inspector or not. */
    OVERLAY,
}

/**
 * The region after [current] among [available], [step] (1 or -1) at a time and wrapping around;
 * without a current one, the first (or the last going back). Null when there is none.
 */
internal fun nextFocusRegion(available: Collection<FocusRegion>, current: FocusRegion?, step: Int): FocusRegion? {
    val order = FocusRegion.entries.filter { it in available }
    if (order.isEmpty()) return null
    val index = order.indexOf(current)
    if (index < 0) return if (step > 0) order.first() else order.last()
    return order[Math.floorMod(index + step, order.size)]
}

/**
 * The focus regions on screen ([rememberFocusRegion]) and the one that holds keyboard focus.
 * F6 and Ctrl+Alt+Right move to the next one, Shift+F6 and Ctrl+Alt+Left to the previous one.
 */
internal class FocusRegions {
    /**
     * Each region's focus actions, oldest first; the newest one acts. Two screens of one region
     * can be composed at once (a transition, or one leaving), and the older one must take over
     * again when the newer one leaves.
     */
    private val regions = mutableMapOf<FocusRegion, MutableList<() -> Boolean>>()

    /** The regions holding focus, each with the element ([focusChanged]'s owner) that has it. */
    private val focused = mutableMapOf<FocusRegion, Any>()

    /** The innermost region holding focus: an overlay pane inside the chat counts as the pane. */
    val current: FocusRegion? get() = focused.keys.maxOrNull()

    val available: Set<FocusRegion> get() = regions.keys

    fun register(region: FocusRegion, focus: () -> Boolean) {
        regions.getOrPut(region) { mutableListOf() } += focus
    }

    fun unregister(region: FocusRegion, focus: () -> Boolean) {
        val actions = regions[region] ?: return
        actions.removeAll { it === focus }
        if (actions.isEmpty()) regions.remove(region)
    }

    /**
     * The element [owner] of [region] gained or lost focus. Another screen's element of the same
     * region, coming or going, leaves the one with focus alone.
     */
    fun focusChanged(region: FocusRegion, owner: Any, hasFocus: Boolean) {
        if (hasFocus) focused[region] = owner else if (focused[region] === owner) focused.remove(region)
    }

    /** Moves focus [step] regions on; a region that cannot take focus now is skipped. */
    fun cycle(step: Int): Boolean {
        val leaving = current
        var from = leaving
        repeat(regions.size) {
            val next = nextFocusRegion(regions.keys, from, step) ?: return false
            if (next != leaving && regions[next]?.lastOrNull()?.invoke() == true) return true
            from = next
        }
        return false
    }
}

/**
 * The rows of a focus region ([rememberRegionChild]) and the one that had focus last, so F6 can
 * come back to it. Lists hold their rows in nested focus groups, which [focusRestorer] alone
 * does not reach into.
 */
@Stable
internal class FocusRegionChildren {
    var last: FocusRequester? = null
}

/** The [FocusRegionChildren] of the region around a list's rows; null outside one. */
internal val LocalFocusRegionChildren = staticCompositionLocalOf<FocusRegionChildren?> { null }

/**
 * Makes the element a focus [region] while [enabled]. [focus] moves focus into it; without one,
 * focus goes back to the row of [children] that had it last, or else to the first focusable child.
 */
@Composable
internal fun rememberFocusRegion(
    region: FocusRegion,
    enabled: Boolean = true,
    children: FocusRegionChildren? = null,
    focus: (() -> Boolean)? = null,
): Modifier {
    val regions = LocalShortcutTargets.current?.regions ?: return Modifier
    val requester = remember { FocusRequester() }
    val latest by rememberUpdatedState(focus)
    DisposableEffect(regions, region, enabled, children) {
        val action = {
            latest?.invoke()
                ?: (children?.last?.let { runCatching { it.requestFocus() }.getOrDefault(false) } == true ||
                    runCatching { requester.requestFocus() }.getOrDefault(false))
        }
        if (enabled) regions.register(region, action)
        onDispose {
            regions.unregister(region, action)
            regions.focusChanged(region, requester, hasFocus = false)
        }
    }
    val tracking = Modifier.onFocusChanged { regions.focusChanged(region, requester, enabled && it.hasFocus) }
    return if (focus != null) tracking else tracking.focusRequester(requester).focusRestorer().focusGroup()
}

/**
 * A row or tile F6 comes back to in its region ([LocalFocusRegionChildren]). Put it before the
 * element's `clickable`, whose focus it follows.
 */
@Composable
internal fun rememberRegionChild(): Modifier {
    val children = LocalFocusRegionChildren.current ?: return Modifier
    val requester = remember { FocusRequester() }
    DisposableEffect(children, requester) { onDispose { if (children.last === requester) children.last = null } }
    return Modifier.onFocusChanged { if (it.isFocused) children.last = requester }.focusRequester(requester)
}

private fun androidx.compose.ui.input.key.KeyEvent.plain(): Boolean =
    type == KeyEventType.KeyDown && !isCtrlPressed && !isAltPressed && !isMetaPressed && !isShiftPressed

/**
 * Right on a focused row or tile opens it, as Enter does, when nothing lies to its right: always
 * in a list, in a grid's last column.
 */
internal fun Modifier.openOnRight(focusManager: FocusManager, open: () -> Unit): Modifier =
    onKeyEvent { event ->
        if (event.key != Key.DirectionRight || !event.plain()) return@onKeyEvent false
        if (!focusManager.moveFocus(FocusDirection.Right)) open()
        true
    }

/**
 * Backspace, and Left where focus cannot move further left inside this pane, go to the parent of
 * a file list: up a folder, or from a file or diff back to its list. [onParent] is null at the
 * top. Left never leaves the pane while there is a parent: in the two-pane layout the chat lies
 * left of the inspector, and Compose's search would otherwise move focus there.
 */
internal fun Modifier.parentOnLeft(focusManager: FocusManager, onParent: (() -> Unit)?): Modifier =
    onKeyEvent { event ->
        val parent = onParent
        if (parent == null || !event.plain()) return@onKeyEvent false
        when (event.key) {
            Key.Backspace -> parent()
            Key.DirectionLeft -> if (!focusManager.moveFocus(FocusDirection.Left)) parent()
            else -> return@onKeyEvent false
        }
        true
    }
        .focusProperties {
            onExit = { if (onParent != null && requestedFocusDirection == FocusDirection.Left) cancelFocusChange() }
        }
        .focusGroup()
