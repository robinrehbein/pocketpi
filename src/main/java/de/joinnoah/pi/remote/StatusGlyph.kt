package de.joinnoah.pi.remote

import android.animation.ValueAnimator
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal enum class StatusGlyphKind {
    TASK_PENDING, TASK_RUNNING, DONE, FAILED, STOPPED, TOOL_RUNNING, SESSION_RUNNING, COMPACTING, WAITING, NEEDS_INPUT
}

internal fun statusGlyphFrame(kind: StatusGlyphKind, elapsedMillis: Long): Int = when (kind) {
    StatusGlyphKind.TASK_RUNNING, StatusGlyphKind.TOOL_RUNNING -> ((elapsedMillis.coerceAtLeast(0) / 400) % 2).toInt()
    StatusGlyphKind.SESSION_RUNNING -> ((elapsedMillis.coerceAtLeast(0) / 100) % 8).toInt()
    StatusGlyphKind.COMPACTING -> ((elapsedMillis.coerceAtLeast(0) / 80) % 10).toInt()
    else -> 0
}

// pi-tui Loader defaults: ⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏, 80 ms per frame.
private val compactionBrailleCells = intArrayOf(0x0b, 0x19, 0x39, 0x38, 0x3c, 0x34, 0x26, 0x27, 0x07, 0x0f)
internal fun compactionDotCells(frame: Int): Int =
    compactionBrailleCells[Math.floorMod(frame, compactionBrailleCells.size)]

private val sessionSnakeCells = intArrayOf(4, 5, 1, 3, 2, 10, 8, 12)

/** Static session glyph (three quadrants, like ▛): reads as "busy" and differs from every snake frame and STOPPED. */
internal const val SESSION_RUNNING_STATIC_CELLS = 7

/** Quadrant bitmask for SESSION_RUNNING: bit 0 top-left, 1 top-right, 2 bottom-left, 3 bottom-right. */
internal fun sessionRunningCells(frame: Int, animated: Boolean): Int =
    if (animated) sessionSnakeCells[Math.floorMod(frame, sessionSnakeCells.size)] else SESSION_RUNNING_STATIC_CELLS

@Composable
internal fun StatusGlyph(
    kind: StatusGlyphKind,
    color: Color,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    animate: Boolean = true,
) {
    val cycle = when (kind) {
        StatusGlyphKind.TASK_RUNNING, StatusGlyphKind.TOOL_RUNNING, StatusGlyphKind.SESSION_RUNNING, StatusGlyphKind.COMPACTING -> 800
        else -> 0
    }
    val context = LocalContext.current
    fun systemAnimationsEnabled(): Boolean = ValueAnimator.areAnimatorsEnabled() &&
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    var animationsEnabled by remember(context) { mutableStateOf(systemAnimationsEnabled()) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                animationsEnabled = systemAnimationsEnabled()
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer,
        )
        animationsEnabled = systemAnimationsEnabled()
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    val animated = animate && animationsEnabled && cycle > 0
    val elapsed = if (animated) {
        key(kind) {
            val transition = rememberInfiniteTransition(label = "status glyph")
            transition.animateFloat(
                initialValue = 0f, targetValue = cycle.toFloat(),
                animationSpec = infiniteRepeatable(tween(cycle, easing = LinearEasing), RepeatMode.Restart),
                label = "status frame",
            ).value.toLong()
        }
    } else 0L
    val frame = statusGlyphFrame(kind, elapsed)
    val descriptionModifier = if (contentDescription == null) Modifier else Modifier.semantics {
        this.contentDescription = contentDescription
    }
    // size() after the caller's modifier: a caller size/constraint wins, 16.dp is only the default.
    Canvas(modifier.size(16.dp).then(descriptionModifier)) {
        val unit = size.minDimension
        val center = Offset(unit / 2, unit / 2)
        val stroke = unit / 10
        val radius = unit * 0.32f
        when (kind) {
            StatusGlyphKind.TASK_PENDING, StatusGlyphKind.WAITING -> drawCircle(
                color, if (kind == StatusGlyphKind.WAITING) unit * 0.42f else radius,
                center, style = Stroke(stroke),
            )
            StatusGlyphKind.DONE -> drawCircle(color, radius, center)
            StatusGlyphKind.FAILED -> {
                drawCircle(color, radius, center, style = Stroke(stroke))
                val d = radius * 0.48f
                drawLine(color, center - Offset(d, d), center + Offset(d, d), stroke)
                drawLine(color, center + Offset(-d, d), center + Offset(d, -d), stroke)
            }
            StatusGlyphKind.STOPPED -> drawRect(color, Offset(unit * 0.25f, unit * 0.25f), Size(unit * 0.5f, unit * 0.5f))
            StatusGlyphKind.NEEDS_INPUT -> {
                val path = Path().apply {
                    moveTo(center.x, unit * 0.12f)
                    lineTo(unit * 0.88f, center.y)
                    lineTo(center.x, unit * 0.88f)
                    lineTo(unit * 0.12f, center.y)
                    close()
                }
                drawPath(path, color)
            }
            StatusGlyphKind.TOOL_RUNNING -> {
                val left = unit * 0.2f
                val top = unit * if (frame == 0) 0.2f else 0.5f
                drawRect(color, Offset(left, top), Size(unit * 0.6f, unit * 0.3f))
            }
            StatusGlyphKind.TASK_RUNNING -> {
                drawCircle(color, radius, center, style = Stroke(stroke))
                drawArc(color, if (frame == 0) 90f else -90f, 180f, true,
                    topLeft = Offset(center.x - radius, center.y - radius), size = Size(radius * 2, radius * 2))
            }
            StatusGlyphKind.COMPACTING -> {
                val cells = compactionDotCells(frame)
                // Braille dots 1–3 occupy the left column; dots 4–6 the right.
                for (dot in 0..5) if (cells and (1 shl dot) != 0) {
                    drawCircle(color, unit * 0.075f,
                        Offset(unit * if (dot < 3) 0.32f else 0.68f,
                            unit * (0.2f + (dot % 3) * 0.3f)))
                }
            }
            StatusGlyphKind.SESSION_RUNNING -> {
                // Atlas: ▖▌▘▀▝▐▗▄ (quadrants and halves); static: ▛.
                val cells = sessionRunningCells(frame, animated)
                val start = unit * 0.2f
                val half = unit * 0.3f
                for (i in 0..3) if (cells and (1 shl i) != 0) {
                    drawRect(color, Offset(start + (i % 2) * half, start + (i / 2) * half), Size(half, half))
                }
            }
        }
    }
}
