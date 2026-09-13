package com.piru.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.piru.app.engine.*
import java.text.SimpleDateFormat
import kotlinx.coroutines.launch
import java.util.Date
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max

/**
 * Journal tab - dose history grouped by day (MyMeds checklist + Active Now hero +
 * per-day cards with a timeline thumbnail), port of EntryListView.swift.
 */
@Composable
fun JournalScreen(state: PiruState, now: Long, onOpenSubstance: (String) -> Unit) {
    LaunchedEffect(now) { state.refresh() }
    if (!state.storeReady) { LoadingScreen(); return }
    val entries = state.entries
    if (entries.isEmpty()) {
        EmptyHint("No doses logged yet. Tap “Log a dose” to start your journal.♡")
        return
    }
    val active = remember(entries, now, state.weightKg) {
        ActiveSubstanceCalculator.compute(entries, state::colorHex, now)
    }
    val meds = remember { state.repo.dailyItems().filter { it.substance.isNotBlank() } }
    val taken = remember(meds, now) { state.repo.takenToday(meds.map { it.id }) }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Spacer(Modifier.height(8.dp))
            MyMedsCard(state, meds, taken)
        }
        item {
            ActiveNowCard(state, entries, active, now) {
                // timeline handled by session card; nothing extra
            }
        }
        val days = entries.groupBy { state.startOfDay(from = it.timestamp) }
            .toSortedMap(compareByDescending { it })
        for ((dayStart, dayEntries) in days) {
            item { SectionTitle(dayLabel(dayStart, now, state)) }
            val clusters = SessionClustering.cluster(dayEntries)
            items(clusters, key = { it.id }) { cluster ->
                SessionCard(state, cluster, active, now, onOpenSubstance)
            }
        }
        item { Spacer(Modifier.height(90.dp)) }
    }
}

private fun dayLabel(dayStart: Long, now: Long, state: PiruState): String {
    val fmt = SimpleDateFormat("EEEE, MMM d", Locale.getDefault())
    return when (dayStart) {
        state.startOfDay(0, now) -> "Today · " + fmt.format(Date(dayStart))
        state.startOfDay(-1, now) -> "Yesterday · " + fmt.format(Date(dayStart))
        else -> fmt.format(Date(dayStart))
    }
}

@Composable
fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text("Loading the pharmacopeia…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}

// MARK: - My Meds card (port of MyMedsCard: today's checklist w/ completion ring)

@Composable
fun MyMedsCard(state: PiruState, meds: List<com.piru.app.data.DailyDoseItem>, taken: Set<String>) {
    if (meds.isEmpty()) return
    val nonQuiet = meds.filter { !it.isQuiet }
    if (nonQuiet.isEmpty()) return
    val done = nonQuiet.count { it.id in taken }
    PiruCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CompletionRing(done, nonQuiet.size)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("My Meds", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("$done of ${nonQuiet.size} taken today", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (done < nonQuiet.size) {
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                Button(
                    onClick = {
                        nonQuiet.filter { it.id !in taken }.forEach { item ->
                            if (item.isAsNeeded) return@forEach
                            state.repo.markTaken(item.id)
                            scope.launch {
                                val entry = com.piru.app.data.DoseEntry(
                                    substance = item.substance, amount = item.amount, unit = item.unit,
                                    route = com.piru.app.models.RouteOfAdministration.fromString(item.route),
                                    substanceUID = item.substanceUID, isBackgroundMed = true,
                                )
                                state.repo.logEntry(entry)
                                state.repo.assignSessions(listOf(entry))
                            }
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                ) { Text("Take All", fontSize = 13.sp) }
            }
        }
        Spacer(Modifier.height(8.dp))
        nonQuiet.forEach { item ->
            val isTaken = item.id in taken
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                    if (!isTaken) { state.repo.markTaken(item.id) }
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(22.dp).clip(CircleShape)
                        .background(if (isTaken) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .let { m -> m },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (isTaken) "✓" else "",
                        color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.substance, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        color = if (isTaken) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    Text(
                        "${item.amount.g() ?: item.amount} ${item.unit} · ${item.route}" +
                            (item.reminderMinutes.firstOrNull()?.let { " · ${minLabel(it)}" } ?: ""),
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

fun Double.g(): String? = if (this == toLong().toDouble()) toLong().toString() else this.toString()
fun minLabel(minutes: Int): String =
    String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60)

@Composable
fun CompletionRing(done: Int, total: Int) {
    val ringColor = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(30.dp)) {
        val stroke = 3.5.dp.toPx()
        drawArc(
            color = Color.White.copy(alpha = 0.15f), startAngle = -90f, sweepAngle = 360f, useCenter = false,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
            topLeft = Offset(stroke / 2, stroke / 2),
            size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
        )
        if (total > 0) {
            val sweep = 360f * done / total
            drawArc(
                color = ringColor, startAngle = -90f, sweepAngle = sweep, useCenter = false,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                topLeft = Offset(stroke / 2, stroke / 2),
                size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
            )
        }
    }
}

// MARK: - Active Now hero (port of ActiveNowCard)

@Composable
fun ActiveNowCard(state: PiruState, entries: List<com.piru.app.data.DoseEntry>, active: List<ActiveSubstanceCalculator.ActiveSubstance>, now: Long, onViewTimeline: () -> Unit) {
    if (active.isEmpty()) return
    PiruCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text("Active now", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "${active.size} substance${if (active.size > 1) "s" else ""} still in your body",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val last = entries.maxByOrNull { it.timestamp }
            last?.let { e ->
                val elapsedMin = (now - e.timestamp) / 60_000.0
                Text(
                    elapsedLabel(elapsedMin), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // the day's timeline as a hero strip (port of ActiveNowWindowGraph)
        val dayStart = state.startOfDay(0, now)
        val dayEntries = state.entriesForRange(dayStart, now)
        if (dayEntries.isNotEmpty()) {
            val states = remember(dayEntries, state.weightKg) {
                com.piru.app.engine.timelineFor(dayEntries, { e -> state.colorHex(e.substance) }, state.weightKg)
            }
            Box(Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(12.dp))) {
                TimelineGraphView(
                    states.first, states.second, now, Modifier.fillMaxSize(),
                    dayBounded = true, compact = false, stackRedoses = true,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        // substance rows: name · still-active % · eliminated
        Column {
            active.take(4).forEach { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(hexColor(s.colorHex)))
                    Spacer(Modifier.width(8.dp))
                    Text(s.name, modifier = Modifier.weight(1f), fontSize = 13.sp)
                    val pct = max(0, (100 - s.eliminatedFraction * 100).toInt())
                    Text("$pct% in body", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

fun elapsedLabel(minutes: Double): String {
    val m = minutes.toLong()
    return when {
        m < 1 -> "just now"
        m < 60 -> "${m}m ago"
        else -> "${m / 60}h ${m % 60}m ago"
    }
}

// MARK: - Per-day session cards (port of SessionCardView)

@Composable
fun SessionCard(
    state: PiruState, cluster: SessionClustering.Cluster, active: List<ActiveSubstanceCalculator.ActiveSubstance>, now: Long,
    onOpenSubstance: (String) -> Unit,
) {
    val names = cluster.entries.map { it.substance }.distinct()
    val isActive = active.any { a -> names.any { it.equals(a.name, ignoreCase = true) } }
    PiruCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(cluster.startMillis)),
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${cluster.entries.size} dose${if (cluster.entries.size > 1) "s" else ""}",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            if (isActive) {
                SuggestionChip(onClick = {}, label = { Text("Active", fontSize = 10.sp) })
            }
        }
        Spacer(Modifier.height(6.dp))
        // mini timeline thumbnail (day-bounded)
        val pair = remember(cluster, state.weightKg) {
            com.piru.app.engine.timelineFor(cluster.entries, { e -> state.colorHex(e.substance) }, state.weightKg)
        }
        if (pair.first.isNotEmpty() || pair.second.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(8.dp))) {
                TimelineGraphView(pair.first, pair.second, now, Modifier.fillMaxSize(), compact = true, dayBounded = false, stackRedoses = true, nowDot = false)
            }
            Spacer(Modifier.height(6.dp))
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            names.take(6).forEach { n ->
                val hex = state.colorHex(n)
                AssistChip(
                    onClick = { onOpenSubstance(n) },
                    label = { Text(n, fontSize = 11.sp) },
                    leadingIcon = { Box(Modifier.size(8.dp).clip(CircleShape).background(hexColor(hex))) },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        cluster.entries.forEach { e ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(e.timestamp)),
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(40.dp),
                )
                Box(Modifier.size(8.dp).clip(CircleShape).background(hexColor(state.colorHex(e.substance))))
                Spacer(Modifier.width(8.dp))
                Text("${e.displayNameSnapshot ?: e.substance}", fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))
                Text("${e.amount.g() ?: e.amount} ${e.unit} ${e.route.displayName.lowercase()}",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
