package com.piru.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import com.piru.app.engine.BodyLevelsCalculator
import com.piru.app.engine.ReceptorClass
import com.piru.app.engine.ToleranceEngine
import com.piru.app.data.DailyDoseItem
import com.piru.app.models.RouteOfAdministration
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.background

/**
 * Insights tab - Usage stats, "In your body" body-load graph, and tolerance gauges.
 * Port of InsightsView + InYourBodyView + ToleranceToolView (glance cards → detail).
 */
@Composable
fun InsightsScreen(state: PiruState, now: Long) {
    var detail by remember { mutableStateOf<String?>(null) }
    if (detail == "bodyload") { BodyLoadScreen(state, now) { detail = null }; return }
    if (detail == "tolerance") { ToleranceScreen(state, now) { detail = null }; return }
    if (detail == "meds") { MyMedsScreen(state) { detail = null }; return }

    val dayStart = state.startOfDay(-29, now)
    val monthEntries = remember(state.entries, dayStart) { state.entriesForRange(dayStart, now) }
    val perDay = monthEntries.groupBy { state.startOfDay(from = it.timestamp) }

    LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
        item {
            Text("Insights", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp))
        }
        item {
            PiruCard {
                Text("Usage", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("${monthEntries.size} doses · ${"%.1f".format(monthEntries.size / 30.0)}/day",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                BarChart(perDay.values.map { it.size }.reversed().take(14))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("last 14 days", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { }) { Text("Details", fontSize = 11.sp) }
                }
            }
        }
        item {
            PiruCard {
                Row(Modifier.fillMaxWidth().clickableOn { detail = "bodyload" }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("In your body", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text("How much of each substance is still circulating", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("›", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            PiruCard {
                Row(Modifier.fillMaxWidth().clickableOn { detail = "tolerance" }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Tolerance", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text("Mechanism-class gauges + recovery forecasts", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("›", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            PiruCard {
                Row(Modifier.fillMaxWidth().clickableOn { detail = "meds" }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("My Meds", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text("Daily routines + reminders", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("›", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Spacer(Modifier.height(90.dp)) }
    }
}

fun Modifier.clickableOn(onClick: () -> Unit): Modifier = clickable(onClick = onClick)

@Composable
fun BarChart(values: List<Int>, height: Dp = 56.dp) {
    if (values.isEmpty()) return
    val maxV = values.max().coerceAtLeast(1)
    val barColor = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(height)) {
        val gap = 3.dp.toPx()
        val barW = (size.width - gap * (values.size - 1)) / values.size
        values.forEachIndexed { i, v ->
            val h = (size.height * v / maxV).toFloat()
            drawRoundRect(
                barColor.copy(alpha = 0.75f),
                Offset(i * (barW + gap), size.height - h.toFloat()),
                androidx.compose.ui.geometry.Size(barW, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx(), 2.dp.toPx()),
            )
        }
    }
}

@Composable
fun BodyLoadScreen(state: PiruState, now: Long, onBack: () -> Unit) {
    val from = state.startOfDay(-29, now)
    val entries = remember(state.entries) { state.entriesForRange(from, now) }
    val trail = remember(entries) {
        BodyLevelsCalculator.trailFor(entries, state::colorHex, state.weightKg, from, now, 60)
    }
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Insights") }
            Text("In your body over time", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        if (trail.isEmpty) {
            EmptyHint("No substance with PK data logged in the last 30 days.")
        } else {
            Box(Modifier.fillMaxWidth().height(240.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    // one normalized line per series, sized by peak
                    val w = size.width; val h = size.height
                    trail.series.take(8).forEachIndexed { _, s ->
                        val path = Path()
                        val n = s.values.size
                        for (i in 0 until n) {
                            val frac = if (s.peak > 0) s.values[i] / s.peak else 0.0
                            val x = i.toFloat() / (n - 1).toFloat() * w
                            val y = (h - frac.toFloat() * (h - 10.dp.toPx())).toFloat()
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(path, hexColor(s.colorHex), style = Stroke(width = 2.dp.toPx()))
                    }
                    // now line
                    drawLine(Color.White.copy(alpha = 0.9f), Offset(w - 1f, 0f), Offset(w - 1f, h), 1.5.dp.toPx())
                }
            }
            Spacer(Modifier.height(8.dp))
            trail.series.take(8).forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                    Box(Modifier.size(8.dp).background(hexColor(s.colorHex), RoundedCornerShape(4.dp)))
                    Spacer(Modifier.width(8.dp))
                    Text(s.displayName, modifier = Modifier.weight(1f), fontSize = 13.sp)
                    val lastVal = s.values.lastOrNull() ?: 0.0
                    Text("${"%.1f".format(lastVal)} ${s.unit} now", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Gauge buckets - port of ToleranceBucket thresholds. */
private fun bucketLabel(severity: Double): String = when {
    severity < 0.10 -> "No tolerance"
    severity < 0.30 -> "Mild"
    severity < 0.50 -> "Moderate"
    severity < 0.70 -> "High"
    else -> "Very high"
}

@Composable
fun ToleranceScreen(state: PiruState, now: Long, onBack: () -> Unit) {
    LaunchedEffect(state.entries) { state.refresh() }
    val result = remember(state.entries, now) {
        runCatching {
            if (com.piru.app.data.SubstanceStoreHolder.ready)
                ToleranceEngine.simulate(state.entries, state.weightKg, now) else emptyMap()
        }.getOrDefault(emptyMap())
    }
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Insights") }
            Text("Tolerance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        if (result.isEmpty()) {
            EmptyHint("Log doses with receptor data to see tolerance gauges.")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(result.values.sortedByDescending { it.severity }, key = { it.mechanism.name }) { t ->
                    if (t.severity < 0.10) return@items
                    PiruCard {
                        Text(t.mechanism.name.replace("_", " ").replaceFirstChar { it.uppercase(Locale.US) },
                            fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Spacer(Modifier.height(6.dp))
                        SeverityBar(t)
                        Spacer(Modifier.height(4.dp))
                        val rf = t.responseFraction
                        Text(
                            "${bucketLabel(t.severity)} · you'd feel ~${(rf * 100).roundToInt()}% of your usual dose",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val rec = com.piru.app.models.PDModel.shiftDecayMinutes(
                            listOf(t.sAcute to t.mechanism.tauAcute, t.sAdaptive to t.mechanism.tauAdaptive,
                                t.sDeep to t.mechanism.tauDeep, t.sSynthesis to t.mechanism.tauSynthesis),
                            targetShift = 1.05,
                        )
                        rec?.let { mins ->
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Recover to near-naïve in ${daysOrHours(mins)} if you stop now",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(90.dp)) }
            }
        }
    }
}

private fun daysOrHours(minutes: Double): String {
    val d = minutes / 1440.0
    return if (d >= 2) "${d.roundToInt()} days" else "${(minutes / 60).roundToInt()} hours"
}

/** Severity split bar: Tachyphylaxis | Tolerance | Deep (by ln-shift share). */
@Composable
fun SeverityBar(t: ToleranceEngine.ClassTolerance) {
    val total = (t.sAcute + t.sAdaptive + t.sDeep).coerceAtLeast(1e-9)
    val wAcute = t.sAcute / total
    val wAdaptive = (t.sAdaptive + t.sSynthesis) / total
    val wDeep = t.sDeep / total
    Row(Modifier.fillMaxWidth().height(10.dp)) {
        Box(Modifier.weight(max(0.001f, wAcute.toFloat())).background(Color(0xFFFFD93D)))
        Box(Modifier.weight(max(0.001f, wAdaptive.toFloat())).background(MaterialTheme.colorScheme.primary))
        Box(Modifier.weight(max(0.001f, wDeep.toFloat())).background(MaterialTheme.colorScheme.error))
    }
}

/** My Meds management - port of MyMedsHubView (add/edit daily items). */
@Composable
fun MyMedsScreen(state: PiruState, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var items by remember { mutableStateOf(state.repo.dailyItems()) }
    var showAdd by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("1") }
    var unit by remember { mutableStateOf("mg") }
    var hour by remember { mutableStateOf(9) }
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Insights") }
            Text("My Meds", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Button(onClick = { showAdd = true }) { Text("+ Add") }
        }
        if (items.isEmpty()) EmptyHint("No daily meds. Add one to get reminders.")
        LazyColumn(Modifier.fillMaxSize()) {
            items(items, key = { it.id }) { item ->
                PiruCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.substance, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text("${item.amount} ${item.unit} · ${item.route} · ${if (item.reminderMinutes.isEmpty()) "anytime" else item.reminderMinutes.joinToString { "%02d:%02d".format(it / 60, it % 60) }}",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = {
                            state.repo.deleteDailyItem(item.id)
                            items = state.repo.dailyItems()
                        }) { Text("Delete", color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                    }
                }
            }
        }
    }
    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Daily med") },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Substance") }, singleLine = true)
                    Spacer(Modifier.height(6.dp))
                    Row {
                        OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("Amount") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.width(120.dp), singleLine = true)
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(value = unit, onValueChange = { unit = it }, label = { Text("Unit") }, modifier = Modifier.width(90.dp), singleLine = true)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Daily at $hour:00", fontSize = 12.sp)
                    Slider(value = hour.toFloat(), onValueChange = { hour = it.roundToInt() }, valueRange = 0f..23f)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) {
                        state.repo.saveDailyItem(
                            DailyDoseItem(substance = name.trim(), amount = amount.toDoubleOrNull() ?: 1.0,
                                unit = unit.ifBlank { "mg" }, route = "oral", reminderMinutes = listOf(hour * 60))
                        )
                        com.piru.app.notifications.RoutineScheduler.rescheduleAll(context.applicationContext)
                        items = state.repo.dailyItems()
                        showAdd = false
                    }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } },
        )
    }
}
