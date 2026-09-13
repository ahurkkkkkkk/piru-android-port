package com.piru.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.piru.app.engine.DoseMarker
import com.piru.app.engine.TimelineCurveModel
import com.piru.app.engine.ActiveSubstanceState
import kotlin.math.max
import kotlin.math.min

/**
 * Port of `Shared/TimelineGraphView.swift` - per-substance effect curves stacked or
 * overlaid, now-line, dose markers on the baseline, tap-to-scrub readout.
 */
@Composable
fun TimelineGraphView(
    states: List<ActiveSubstanceState>,
    markers: List<DoseMarker> = emptyList(),
    currentTimeMillis: Long = System.currentTimeMillis(),
    modifier: Modifier = Modifier,
    stackRedoses: Boolean = true,
    dayBounded: Boolean = false,
    compact: Boolean = false,
    nowDot: Boolean = true,
    scrubBinding: ScrubBinding? = null,
) {
    if (states.isEmpty() && markers.isEmpty()) {
        Canvas(modifier) {}
        return
    }
    val earliest = (states.minOfOrNull { it.doseTimestamp } ?: currentTimeMillis)
        .let { e -> markers.minOfOrNull { it.timestamp }?.let { min(e, it) } ?: e }
    val yNorm: Double
    val groups: List<List<ActiveSubstanceState>>
    if (stackRedoses) {
        groups = TimelineCurveModel.stackedGroups(states)
        yNorm = min(1.0 / TimelineCurveModel.peakCurveValue(states, groups, earliest, true), 20.0)
    } else {
        groups = emptyList()
        yNorm = min(1.0 / TimelineCurveModel.peakCurveValue(states, emptyList(), earliest, false), 20.0)
    }
    val displayCap = if (dayBounded) 24 * 60.0 else TimelineCurveModel.MAX_DISPLAY_MINUTES
    val dataTail = TimelineCurveModel.renderedTail(0.04, false, states, groups, earliest, yNorm, stackRedoses, displayCap)
    val spanMinutes = max(dataTail, 60.0)
    val scrub = scrubBinding

    Canvas(
        modifier.pointerInput(states, markers) {
            detectTapGestures { pos ->
                if (!compact) {
                    val t = earliest / 60_000.0 + pos.x / size.width * spanMinutes
                    scrub?.let {
                        it.value = ScrubState(
                            millis = (t * 60_000).toLong(),
                            x = pos.x,
                            labels = states.map { s ->
                                val local = t - (s.doseTimestamp - earliest) / 60_000.0
                                s.substanceName to if (local >= 0) TimelineCurveModel.intensity(local, s) else 0.0
                            }.distinctBy { it.first },
                        )
                    }
                }
            }
        }
    ) {
        val w = size.width
        val h = size.height
        val bottom = h - if (compact) 2.dp.toPx() else 8.dp.toPx()
        // baseline
        drawLine(Color.White.copy(alpha = 0.12f), Offset(0f, bottom), Offset(w, bottom), 1.dp.toPx())
        fun xFor(minutesSinceEarliest: Double) =
            (minutesSinceEarliest / spanMinutes * w).toFloat().coerceIn(0f, w)

        if (stackRedoses) {
            for (group in groups) {
                val path = Path()
                val steps = 200
                var started = false
                for (i in 0..steps) {
                    val t = i.toDouble() / steps * spanMinutes
                    val v = TimelineCurveModel.stackedIntensity(t, group, earliest) * yNorm
                    val x = xFor(t)
                    val y = bottom - v.toFloat().coerceIn(0f, 1f) * (h - 4.dp.toPx())
                    if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                }
                val hex = group.first().colorHex
                val color = hexColor(hex)
                drawPath(path, color, alpha = 0.22f, style = Stroke(width = 1.6.dp.toPx()))
            }
        } else {
            for (s in states) {
                val path = Path()
                val steps = 160
                val offset = (s.doseTimestamp - earliest) / 60_000.0
                var started = false
                for (i in 0..steps) {
                    val t = i.toDouble() / steps * spanMinutes
                    val local = t - offset
                    val v = if (local >= 0) TimelineCurveModel.intensity(local, s) * TimelineCurveModel.heightScale(s) * yNorm else 0.0
                    val x = xFor(t)
                    val y = bottom - v.toFloat().coerceIn(0f, 1f) * (h - 4.dp.toPx())
                    if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                }
                drawPath(path, hexColor(s.colorHex), alpha = 0.22f, style = Stroke(width = 1.6.dp.toPx()))
                // dose bubble at onset
                drawCircle(hexColor(s.colorHex), radius = (if (compact) 2f else 3.2f) * 3f, center = Offset(xFor(offset), bottom))
            }
        }
        // markers (duration-less doses): baseline dots
        for (m in markers) {
            val offset = (m.timestamp - earliest) / 60_000.0
            drawCircle(hexColor(m.colorHex), radius = if (compact) 3f else 4.5f, center = Offset(xFor(offset), bottom))
        }
        // now-line
        if (nowDot) {
            val nowMin = (currentTimeMillis - earliest) / 60_000.0
            if (nowMin in 0.0..spanMinutes) {
                val x = xFor(nowMin)
                drawLine(Color.White.copy(alpha = 0.9f), Offset(x, 0f), Offset(x, bottom), 1.4.dp.toPx())
                if (!stackRedoses) {
                    for (s in states) {
                        val local = nowMin - (s.doseTimestamp - earliest) / 60_000.0
                        if (local >= 0) {
                            val v = TimelineCurveModel.intensity(local, s) * TimelineCurveModel.heightScale(s) * yNorm
                            drawCircle(hexColor(s.colorHex), radius = 3f, center = Offset(x, bottom - v.toFloat().coerceIn(0f, 1f) * (h - 4.dp.toPx())))
                        }
                    }
                }
            }
        }
        // hour gridlines + labels (non-compact)
        if (!compact) {
            val interval = TimelineCurveModel.intervalForSpan(spanMinutes)
            var t = 0.0
            while (t <= spanMinutes) {
                val x = xFor(t)
                drawLine(Color.White.copy(alpha = 0.05f), Offset(x, 0f), Offset(x, bottom), 1f)
                t += interval
            }
        }
    }
}

/** Simple mutable scrub state holder for the graph. */
class ScrubBinding { var value by mutableStateOf<ScrubState?>(null) }
data class ScrubState(val millis: Long, val x: Float, val labels: List<Pair<String, Double>>)

fun hexColor(hex: String): Color {
    val clean = hex.removePrefix("#")
    val v = clean.toLongOrNull(16) ?: return Color.Gray
    return if (clean.length == 6) Color(0xFF000000 or v) else Color(v.toInt())
}
