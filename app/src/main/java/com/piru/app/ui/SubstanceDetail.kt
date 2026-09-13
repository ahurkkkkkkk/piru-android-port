package com.piru.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.piru.app.data.SubstanceStoreHolder
import com.piru.app.engine.Interactions
import com.piru.app.engine.TimelineCurveModel
import com.piru.app.engine.ActiveSubstanceState
import com.piru.app.models.RouteOfAdministration
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Substance detail (bottom sheet) — port of SubstanceDetailView's section stack
 * (header, dose/duration ladder, effects, overview, pharmacology, interactions).
 */
@Composable
fun SubstanceDetailSheet(state: PiruState, name: String, onLogDose: () -> Unit) {
    val store = if (SubstanceStoreHolder.ready) SubstanceStoreHolder.store else null
    Column(
        Modifier.fillMaxWidth().heightIn(max = 620.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        if (store == null) { Text("Loading…"); return }
        val substance = remember(name) { store.lookup(name) }
        if (substance == null) { Text("$name — not in the library (custom substance)."); return }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(substance.displayTitle, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    substance.category.label + (substance.formula?.let { " · $it" } ?: ""),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onLogDose, shape = MaterialTheme.shapes.medium) { Text("Log a dose") }
        }
        Spacer(Modifier.height(8.dp))
        substance.description?.let {
            Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(12.dp))
        }
        // Dose & duration
        Text("Dose & duration", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        substance.routes.filter { it.doses.hasAny() }.take(4).forEach { r ->
            val d = r.doses
            Spacer(Modifier.height(6.dp))
            Text(r.route.displayName, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Column {
                d.threshold?.let { ladderRow("Threshold", it, it, d.unit) }
                d.light?.let { ladderRow("Light", it.min, it.max, d.unit) }
                d.common?.let { ladderRow("Common", it.min, it.max, d.unit) }
                d.strong?.let { ladderRow("Strong", it.min, it.max, d.unit) }
                d.heavy?.let { ladderRow("Heavy", it, it, d.unit, danger = true) }
            }
            r.duration?.let { prof ->
                val b = prof.phaseBoundaries
                Text(
                    "onset ~${b.onsetEnd.round()}m · comeup ~${(b.comeupEnd - b.onsetEnd).round()}m · " +
                        "peak ~${(b.peakEnd - b.comeupEnd).round()}m · offset ~${(b.offsetEnd - b.peakEnd).round()}m",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (substance.halfLifeMinutes != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Half-life ~${hlLabel(substance.halfLifeMinutes!!)}",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Mechanism
        substance.mechanism?.let {
            Spacer(Modifier.height(14.dp))
            Text("Mechanism", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(it, fontSize = 13.sp)
        }
        // Interactions (class rules vs a hypothetical logged mix — static display of dangerous pairs)
        val classes = remember(substance) { runCatching { store.drugClasses(substance.name) }.getOrDefault(emptyList()) }
        if (classes.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("Combinations to watch", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            val dangerous = remember(classes) {
                runCatching {
                    store.rules().filter { it.classA in classes || it.classB in classes }
                        .sortedByDescending { it.severity }
                }.getOrDefault(emptyList()).distinctBy { listOf(it.classA, it.classB).sorted() }
            }
            dangerous.take(5).forEach { r ->
                val sev = when (r.severity) { 2 -> "Dangerous" to MaterialTheme.colorScheme.error; 1 -> "Unsafe" to Color(0xFFFF9F0A); else -> "Caution" to Color(0xFFFFD93D) }
                Spacer(Modifier.height(4.dp))
                Row {
                    Text("• ", color = sev.second)
                    Text(
                        "${r.classA.replaceFirstChar { it.uppercase(Locale.US) }} + ${r.classB.replaceFirstChar { it.uppercase(Locale.US) }}",
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("(${sev.first})", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                r.note?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        // Aliases
        if (substance.aliases.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("Also known as", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(substance.aliases.take(18).joinToString(", "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Chemistry
        Spacer(Modifier.height(14.dp))
        Text("Chemistry", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Column {
            substance.cas?.let { infoRow("CAS", it) }
            substance.molecularWeight?.let { infoRow("Molar mass", "%.2f g/mol".format(it)) }
            substance.regulatoryStatus?.takeIf { it.isNotBlank() }?.let { infoRow("Regulatory", it) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun Double.round() = this.roundToInt()

@Composable
private fun ladderRow(level: String, lo: Double, hi: Double, unit: String, danger: Boolean = false) {
    Row {
        Text(level, fontSize = 12.sp, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(90.dp))
        Text(
            if (lo == hi) "${lo.g()} $unit" else "${lo.g()}–${hi.g()} $unit",
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun infoRow(label: String, value: String) {
    Row {
        Text("$label", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(90.dp))
        Text(value, fontSize = 12.sp)
    }
}

private fun hlLabel(minutes: Double): String = when {
    minutes >= 1440 -> "${(minutes / 1440 * 10).roundToInt() / 10.0} days"
    minutes >= 60 -> "${(minutes / 60 * 10).roundToInt() / 10.0} hours"
    else -> "${minutes.round()} min"
}
