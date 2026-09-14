package com.piru.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.piru.app.data.DoseEntry
import com.piru.app.data.SubstanceStoreHolder
import com.piru.app.models.ByVolumeDosing
import com.piru.app.models.DoseUnit
import com.piru.app.models.RouteOfAdministration
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.Dp

/**
 * Quick Log - search-first dose staging with a tray, amount/route/unit editing,
 * branded strength chips, and alcohol by-volume mode. Port of the QuickLog flow
 * (QuickLogView + QuickLogDock + StagedDoseEditor).
 */

data class StagedDose(
    var substance: String,
    var amount: Double,
    var unit: String,
    var route: RouteOfAdministration,
    var saltForm: String? = null,
    var isomer: String? = null,
    var releaseForm: String? = null,
    var productName: String? = null,
    var substanceUID: String? = null,
    var displayName: String? = null,
    var notes: String? = null,
    var isApproximate: Boolean = false,
    var volumeML: Double? = null,
    var abv: Double? = null,
    var drinkName: String? = null,
) {
    val identityKey get() = (substanceUID ?: substance.lowercase()) +
        (listOfNotNull(isomer?.uppercase(), releaseForm?.uppercase(), saltForm).takeIf { it.isNotEmpty() }?.joinToString("|", prefix = "|") ?: "")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickLogSheet(state: PiruState, prefill: Pair<String, Double>?, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val store = runCatching { SubstanceStoreHolder.store }.getOrNull()
    val tray = remember { mutableStateListOf<StagedDose>() }
    var query by remember { mutableStateOf("") }
    var whenMinutes by remember { mutableStateOf<Int?>(null) } // minutes-from-now offset; null = now
    var editedKeys by remember { mutableStateOf(setOf<String>()) } // identities the user has edited (don't overwrite with prefill)
    val results = remember(query, store) { query.takeIf { it.isNotBlank() && store != null }?.let { store!!.search(it, 12) } ?: emptyList() }
    val recents = remember(tray.size) { runCatching { state.repo.userDb.recents(8) }.getOrDefault(emptyList()) }

    fun stage(s: com.piru.app.data.SubstanceStore.SubstanceSearchHit) {
        val sub = s.substance
        // merge into an existing staged chip of the same identity+route
        val existing = tray.firstOrNull { it.substance.equals(sub.name, ignoreCase = true) && it.route == sub.defaultRoute }
        if (existing != null && existing.substance !in editedKeys) {
            existing.amount += defaultAmount(sub)
            return
        }
        tray.add(
            StagedDose(
                substance = sub.displayTitle, amount = defaultAmount(sub), unit = sub.unit,
                route = sub.defaultRoute, substanceUID = sub.substanceUID,
                displayName = sub.displayTitle,
            )
        )
    }

    // prefill from a substance tap in the detail card
    LaunchedEffect(prefill, store) {
        if (prefill != null && store != null) {
            val hit = store.search(prefill.first, 1).firstOrNull()
            if (hit != null && tray.none { it.substance.equals(hit.substance.name, true) }) {
                val zeroOrder = runCatching { store.zeroOrderKineticsFor(hit.substance.name, state.weightKg) }.getOrNull()
                tray.add(
                    StagedDose(
                        substance = hit.substance.displayTitle,
                        amount = if (prefill.second > 0) prefill.second else defaultAmount(hit.substance),
                        unit = hit.substance.unit, route = hit.substance.defaultRoute,
                        substanceUID = hit.substance.substanceUID,
                        // zero-order (alcohol) substances land in by-volume mode with a beer default
                        volumeML = if (zeroOrder != null) 330.0 else null,
                        abv = if (zeroOrder != null) 5.0 else null,
                    )
                )
            }
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text("Log", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Substance - name, brand, or alias") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Close, "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp).clickable { query = "" }) },
            shape = RoundedCornerShape(14.dp),
        )
        Spacer(Modifier.height(6.dp))
        if (results.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(12.dp), tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text("Substances", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp))
                    Column(Modifier.padding(vertical = 4.dp)) {
                        results.forEach { hit ->
                            Row(
                                Modifier.fillMaxWidth().clickable { stage(hit); query = "" }.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(hexColor(state.colorHex(hit.substance.name))))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(hit.substance.displayTitle, fontSize = 14.sp)
                                    hit.matchedAlias?.let {
                                        Text("as “$it” · ${hit.substance.category.label}", fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Icon(Icons.Filled.Star, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            }
        }

        // Condition search results
        val conditionHits = remember(query) {
            query.takeIf { it.isNotBlank() }?.let { runCatching { SubstanceStoreHolder.store.conditionIndex() }.getOrDefault(emptyList()).filter { (cond, _) ->
                cond.lowercase().contains(query.lowercase())
            }?.map { (cond, sub) -> com.piru.app.data.Condition(cond, sub) }?.take(10) } ?: emptyList()
        }

        if (conditionHits.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(12.dp), tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text("Conditions", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp))
                    Column(Modifier.padding(vertical = 4.dp)) {
                        conditionHits.forEach { cond ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    query = cond.text
                                }.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.Search, "Condition",
                                    Modifier.size(20.dp), tint = MaterialTheme.colorScheme.secondary)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(cond.text, fontSize = 14.sp)
                                    Text(cond.substanceDisplayName, fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        } else if (query.isBlank() && recents.isNotEmpty()) {
            Text("Recent", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
                recents.forEach { (_, name) ->
                    val title = store?.lookup(name)?.displayTitle ?: name
                    AssistChip(
                        onClick = {
                            store?.let { s -> s.lookup(name)?.let { hit -> stage(com.piru.app.data.SubstanceStore.SubstanceSearchHit(hit, null, hit.popularity, 0)) } }
                        },
                        label = { Text(title, fontSize = 12.sp) },
                        leadingIcon = { Box(Modifier.size(8.dp).clip(CircleShape).background(hexColor(state.colorHex(name)))) },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (tray.isEmpty()) {
            Text("Tap a substance above to add it to the tray.", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 20.dp))
        }
        LazyColumn(Modifier.heightIn(max = 300.dp)) {
            items(tray.toList(), key = { it.identityKey + "@" + System.identityHashCode(it) }) { dose ->
                StagedDoseEditor(state, dose, edited = dose.identityKey in editedKeys,
                    onEdited = { editedKeys = editedKeys + dose.identityKey },
                    onDelete = { tray.remove(dose) })
            }
        }
        Spacer(Modifier.height(8.dp))
        // When selector
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = whenMinutes == null, onClick = { whenMinutes = null },
                label = { Text("Now", fontSize = 12.sp) },
            )
            Spacer(Modifier.width(6.dp))
            FilterChip(
                selected = whenMinutes == 30, onClick = { whenMinutes = 30 },
                label = { Text("30 min ago", fontSize = 12.sp) },
            )
            Spacer(Modifier.width(6.dp))
            FilterChip(
                selected = whenMinutes == 60, onClick = { whenMinutes = 60 },
                label = { Text("1 h ago", fontSize = 12.sp) },
            )
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                if (tray.isEmpty()) return@Button
                val nowMillis = System.currentTimeMillis()
                val entries = tray.map { d ->
                    DoseEntry(
                        substance = d.substance, amount = d.amount, unit = d.unit, route = d.route,
                        saltForm = d.saltForm, isomer = d.isomer, releaseForm = d.releaseForm,
                        productName = d.productName, substanceUID = d.substanceUID,
                        displayNameSnapshot = d.displayName,
                        timestamp = nowMillis - (whenMinutes?.times(60_000L) ?: 0L),
                        notes = d.notes, isApproximate = d.isApproximate,
                        volumeML = d.volumeML, abv = d.abv, drinkName = d.drinkName,
                    )
                }
                scope.launch {
                    entries.forEach { state.repo.logEntry(it) }
                    state.repo.assignSessions(entries)
                }
                onDone()
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
            enabled = tray.isNotEmpty() && store != null,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Text("Log ${tray.size} dose${if (tray.size > 1) "s" else ""}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** Default dose suggestion: the common midpoint, or 1 unit if no ladder. */
private fun defaultAmount(s: com.piru.app.data.Substance): Double {
    val range = s.doseRangeFor(s.defaultRoute) ?: return 1.0
    range.common?.let { return ((it.min + it.max) / 2).roundToNearest() }
    range.light?.let { return ((it.min + it.max) / 2).roundToNearest() }
    range.threshold?.let { return it.roundToNearest() }
    return 1.0
}

private fun Double.roundToNearest(): Double = if (this >= 100) (this / 5).roundToInt() * 5.0 else (this * 10).roundToInt() / 10.0

@Composable
fun StagedDoseEditor(state: PiruState, dose: StagedDose, edited: Boolean, onEdited: () -> Unit, onDelete: () -> Unit) {
    val store = SubstanceStoreHolder.store
    val substance = remember(dose.substance) { store?.lookup(dose.substance) }
    // by-volume mode keys off the zero-order kinetics row (ethanol in the DB), not the label
    val isAlcohol = remember(dose.substance) {
        runCatching { store?.zeroOrderKineticsFor(dose.substance, 60.0) != null }.getOrDefault(false)
    }
    var showRoutes by remember(dose) { mutableStateOf(false) }
    var showUnit by remember(dose) { mutableStateOf(false) }
    var amountText by remember(dose) { mutableStateOf(dose.amount.toString()) }
    var notes by remember(dose) { mutableStateOf(dose.notes ?: "") }

    PiruCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(hexColor(state.colorHex(dose.substance))))
            Spacer(Modifier.width(8.dp))
            Text(dose.displayName ?: dose.substance, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.Delete, "Remove", Modifier.size(18.dp).clickable(onClick = onDelete),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        // chips: route + unit + level readout
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            AssistChip(onClick = { showRoutes = true }, label = { Text(dose.route.displayName, fontSize = 12.sp) })
            Spacer(Modifier.width(6.dp))
            AssistChip(onClick = { showUnit = true }, label = { Text(dose.unit, fontSize = 12.sp) })
            Spacer(Modifier.width(10.dp))
            substance?.let { s ->
                val range = s.doseRangeFor(dose.route)
                val level = doseLevel(dose.amount, range)
                if (level != null) {
                    Text(level, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (isAlcohol && dose.unit.equals("g", true)) {
            // alcohol: by-volume mode (ABV × volume → grams ethanol)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = (dose.volumeML ?: 0.0).trimStr(),
                    onValueChange = { v ->
                        dose.volumeML = v.toDoubleOrNull()
                        val g = ByVolumeDosing.grams(dose.volumeML ?: 0.0, dose.abv ?: 5.0)
                        dose.amount = max(0.0, g); amountText = g.roundToInt().toString()
                    },
                    label = { Text("mL", fontSize = 12.sp) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.width(110.dp),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = (dose.abv ?: 5.0).trimStr(), onValueChange = {
                        dose.abv = it.toDoubleOrNull() ?: 5.0
                        val g = ByVolumeDosing.grams(dose.volumeML ?: 0.0, dose.abv ?: 5.0)
                        dose.amount = max(0.0, g); amountText = g.roundToInt().toString()
                    },
                    label = { Text("% ABV", fontSize = 12.sp) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.width(110.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text("${dose.amount.roundToInt()} g · ${"%.1f".format(ByVolumeDosing.standardDrinks(dose.amount))} drinks",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            // stepper + amount
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = {
                    onEdited(); dose.amount = max(0.0, dose.amount - increment(dose)); amountText = dose.amount.toString()
                }) { Text("−", fontSize = 18.sp) }
                OutlinedTextField(
                    value = amountText, onValueChange = {
                        amountText = it
                        it.toDoubleOrNull()?.let { v -> onEdited(); dose.amount = v }
                    },
                    modifier = Modifier.width(130.dp), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    textStyle = MaterialTheme.typography.titleMedium.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                )
                FilledTonalIconButton(onClick = {
                    onEdited(); dose.amount += increment(dose); amountText = dose.amount.toString()
                }) { Text("+", fontSize = 18.sp) }
                Spacer(Modifier.width(8.dp))
                // strength chips for branded products
                substance?.let { s ->
                    store?.productStrengths(s.name)?.strengthsMg?.take(4)?.forEach { mg ->
                        FilterChip(
                            selected = dose.amount == mg,
                            onClick = { dose.amount = mg; amountText = mg.toString() },
                            label = { Text("${mg.g()} mg", fontSize = 11.sp) },
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                }
            }
        }
        OutlinedTextField(
            value = notes, onValueChange = { notes = it; dose.notes = it },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            placeholder = { Text("Note (optional)", fontSize = 12.sp) }, singleLine = true,
            shape = RoundedCornerShape(12.dp),
        )
    }

    if (showRoutes) {
        AlertDialog(onDismissRequest = { showRoutes = false }, title = { Text("Route") }, text = {
            Column {
                RouteOfAdministration.entries.forEach { r ->
                    Row(Modifier.fillMaxWidth().clickable {
                        dose.route = r; showRoutes = false
                    }.padding(vertical = 10.dp)) {
                        Text(r.displayName, color = if (r == dose.route) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (r == dose.route) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { showRoutes = false }) { Text("Close") } })
    }
    if (showUnit) {
        AlertDialog(onDismissRequest = { showUnit = false }, title = { Text("Unit") }, text = {
            Column {
                listOf("µg", "mg", "g", "mL", "IU", "drink").forEach { u ->
                    Row(Modifier.fillMaxWidth().clickable {
                        DoseUnit.convert(dose.amount, dose.unit, u)?.let { dose.amount = it; amountText = it.roundToInt().toString() }
                        dose.unit = u; showUnit = false
                    }.padding(vertical = 10.dp)) { Text(u) }
                }
            }
        }, confirmButton = { TextButton(onClick = { showUnit = false }) { Text("Close") } })
    }
}

private fun increment(dose: StagedDose): Double = when (dose.unit.lowercase()) {
    "µg", "ug", "mcg" -> 50.0
    "mg" -> 10.0
    "g" -> 0.5
    "ml" -> 5.0
    "iu" -> 100.0
    else -> 1.0
}

private fun doseLevel(amount: Double, range: com.piru.app.data.DoseRange?): String? {
    range ?: return null
    val thr = range.threshold
    val light = range.light
    val common = range.common
    val strong = range.strong
    val heavy = range.heavy
    return when {
        thr != null && amount < thr -> "sub-threshold"
        light != null && amount <= light.max -> "light"
        common != null && amount <= common.max -> "common"
        strong != null && amount <= strong.max -> "strong"
        heavy != null && amount > heavy -> "heavy"
        else -> null
    }
}

private fun Double.trimStr(): String = if (this == toLong().toDouble()) toLong().toString() else this.toString()
