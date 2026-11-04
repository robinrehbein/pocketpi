package de.joinnoah.pi.remote

import android.view.View
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.geometry.Rect
import java.util.WeakHashMap
import kotlin.math.roundToInt

internal enum class MorphMenuDirection { DownFromEnd, UpFromStart }

/** Only one morph menu may own input in an activity window. Values do not retain the window. */
internal class MorphMenuCoordinator {
    private var active: MorphMenuState? = null
    fun activate(state: MorphMenuState) {
        if (active !== state) active?.dismiss()
        active = state
    }
    fun remove(state: MorphMenuState) { if (active === state) active = null }
}

private val morphCoordinators = WeakHashMap<View, MorphMenuCoordinator>()

@Stable
internal class MorphMenuState internal constructor(private val coordinator: MorphMenuCoordinator = MorphMenuCoordinator()) {
    internal val visibility = MutableTransitionState(false)
    private var requested by mutableStateOf(false)
    internal var placed by mutableStateOf(false)
    internal var anchor by mutableStateOf(Rect.Zero)
    var expanded: Boolean
        get() = requested
        set(value) {
            if (value) coordinator.activate(this)
            requested = value
            visibility.targetState = value
        }
    val anchorHidden: Boolean get() = placed && (expanded || visibility.currentState || !visibility.isIdle)
    /** Rows must not accept keyboard focus or activation while clipped by a transition. */
    val contentInteractive: Boolean get() = expanded && visibility.isIdle
    fun dismiss() { expanded = false }
    internal fun dispose() { dismiss(); coordinator.remove(this); placed = false }
}

@Composable
internal fun rememberMorphMenuState(): MorphMenuState {
    val root = LocalView.current.rootView
    val coordinator = remember(root) { morphCoordinators.getOrPut(root) { MorphMenuCoordinator() } }
    val state = remember(coordinator) { MorphMenuState(coordinator) }
    DisposableEffect(state) { onDispose { state.dispose() } }
    return state
}

internal fun Modifier.morphMenuAnchor(state: MorphMenuState): Modifier = composed {
    val view = LocalView.current
    onGloballyPositioned {
        val screen = IntArray(2)
        val window = IntArray(2)
        view.getLocationOnScreen(screen)
        view.getLocationInWindow(window)
        val bounds = it.boundsInWindow().translate((screen[0] - window[0]).toFloat(), (screen[1] - window[1]).toFloat())
        val clipped = bounds.width < it.size.width - 1f || bounds.height < it.size.height - 1f
        if (state.expanded && (clipped || (state.anchor != Rect.Zero && state.anchor != bounds))) {
            // Scrolling/IME changes dismiss rather than chase a moving anchor mid-morph.
            state.dismiss()
        }
        if (!state.anchorHidden) state.anchor = bounds
    }.drawWithContent { if (!state.anchorHidden) drawContent() }
        .then(if (state.expanded || state.anchorHidden) Modifier.clearAndSetSemantics {} else Modifier)
}

/** Expand the control frame symmetrically so a 48dp target retains a 32dp glyph centre. */
internal fun morphMenuAnchorFrame(anchor: Rect, controlSize: Float, progress: Float): Rect {
    val inset = ((controlSize - anchor.height) / 2f).coerceAtLeast(0f) * progress
    return Rect(anchor.left - inset, anchor.top - inset, anchor.right + inset, anchor.bottom + inset)
}

/** Absolute pixel position, with logical start/end converted exactly once. */
internal fun morphMenuPosition(anchor: Rect, width: Int, height: Int, window: IntSize, direction: MorphMenuDirection, rtl: Boolean): IntOffset {
    val end = direction == MorphMenuDirection.DownFromEnd
    val pinRight = end != rtl
    val x = if (pinRight) anchor.right - width else anchor.left
    val y = if (end) anchor.top else anchor.bottom - height
    return IntOffset(x.roundToInt().coerceIn(0, (window.width - width).coerceAtLeast(0)), y.roundToInt().coerceIn(0, (window.height - height).coerceAtLeast(0)))
}

/** A popup handoff: the anchor keeps its layout slot while one visible surface changes size. */
@Composable
internal fun MorphingMenu(
    state: MorphMenuState,
    direction: MorphMenuDirection,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    panelWidth: Dp? = 320.dp,
    controls: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val transition = rememberTransition(state.visibility, label = "morphMenu")
    val progress by transition.animateFloat(transitionSpec = { tween(240) }, label = "surface") { if (it) 1f else 0f }
    val visible = state.expanded || state.visibility.currentState || !state.visibility.isIdle
    if (!visible) {
        SideEffect { state.placed = false }
        return
    }
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == LayoutDirection.Rtl
    val provider = remember { object : PopupPositionProvider {
        override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset.Zero
    } }
    Popup(popupPositionProvider = provider, onDismissRequest = state::dismiss,
        properties = PopupProperties(focusable = true, clippingEnabled = true)) {
        val popupView = LocalView.current
        var origin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        var originReady by remember { mutableStateOf(false) }
        BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned {
            val location = IntArray(2)
            popupView.getLocationOnScreen(location)
            origin = androidx.compose.ui.geometry.Offset(location[0].toFloat(), location[1].toFloat())
            originReady = true
        }.pointerInput(state) { detectTapGestures { state.dismiss() } }
            .onPreviewKeyEvent { if (it.key == Key.Escape) { state.dismiss(); true } else false }) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val window = with(density) { IntSize(maxWidth.roundToPx(), maxHeight.roundToPx()) }
            val anchor = state.anchor
            val down = direction == MorphMenuDirection.DownFromEnd
            val pinRight = down != rtl
            // Capture the popup origin rather than assuming that activity and popup windows align.
            val localAnchor = anchor.translate(-origin.x, -origin.y)
            val available = if (down) window.height - localAnchor.top else localAnchor.bottom
            val maxHeight = available.roundToInt().coerceIn(48, window.height.coerceAtLeast(48))
            val widthLimit = if (panelWidth == null || panelWidth == Dp.Unspecified) window.width
                else with(density) { panelWidth.roundToPx() }.coerceAtMost(window.width)
            val closedWidth = anchor.width.roundToInt().coerceAtLeast(1)
            val closedHeight = anchor.height.roundToInt().coerceAtLeast(1)
            val radius = with(density) { (closedHeight / 2f).toDp() } * (1f - progress) + 20.dp * progress
            Layout(content = {
                FloatingSurface(
                    modifier = modifier.onGloballyPositioned { state.placed = true }
                        .pointerInput(Unit) { detectTapGestures {} },
                    shape = RoundedCornerShape(radius), color = color, blur = false,
                ) {
                    Layout(content = {
                        Box(Modifier.fillMaxWidth(), contentAlignment = if (down) Alignment.TopEnd else Alignment.BottomStart) { controls() }
                        Column(Modifier.then(if (state.contentInteractive) Modifier else
                            Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                                }
                            }).verticalScroll(rememberScrollState()).graphicsLayer {
                            alpha = ((progress - 0.25f) / 0.75f).coerceIn(0f, 1f)
                        }, content = content)
                    }) { measurables, _ ->
                        // Query only menu rows, never tooltip-bearing controls, for intrinsic width.
                        val width = if (panelWidth == null || panelWidth == Dp.Unspecified)
                            measurables[1].maxIntrinsicWidth(Constraints.Infinity).coerceIn(closedWidth.coerceAtMost(widthLimit), widthLimit)
                        else widthLimit
                        val control = measurables[0].measure(Constraints.fixedWidth(width).copy(maxHeight = maxHeight))
                        val rows = measurables[1].measure(Constraints.fixedWidth(width).copy(maxHeight = (maxHeight - control.height).coerceAtLeast(0)))
                        val fullHeight = control.height + rows.height
                        val w = (closedWidth + (width - closedWidth) * progress).roundToInt()
                        val h = (closedHeight + (fullHeight - closedHeight) * progress).roundToInt()
                        layout(w, h) {
                            // A 32dp painted anchor has a 48dp control: preserve its glyph centre.
                            val inset = ((48.dp.roundToPx() - closedHeight) / 2f * (1f - progress)).roundToInt()
                            val x = (if (pinRight) w - width else 0) + if (pinRight) inset else -inset
                            val y = (if (down) 0 else h - fullHeight) + if (down) -inset else inset
                            control.place(x, y + if (down) 0 else rows.height)
                            rows.place(x, y + if (down) control.height else 0)
                        }
                    }
                }
            }) { measurables, _ ->
                val child = measurables.single().measure(Constraints(maxWidth = window.width, maxHeight = window.height))
                val frame = morphMenuAnchorFrame(localAnchor, 48.dp.toPx(), progress)
                val position = morphMenuPosition(frame, child.width, child.height, window, direction, rtl)
                layout(window.width, window.height) { if (originReady) child.place(position.x, position.y) }
            }
        }
    }
}
