package com.piru.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.piru.app.data.SubstanceStoreHolder
import com.piru.app.models.PKModel
import kotlin.math.ln
import kotlin.math.roundToInt
import androidx.compose.ui.geometry.Offset

/**
 * Tools tab - half-life calculator + body-weight setting + source priority + about.
 * Port of Piru/Views/Tools (HalfLifeCalculatorView, SteadyStateView, SourcePriorityView).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(state: PiruState) {
    var tab by remember { mutableIntStateOf(0) }
    val titles = listOf("PK Calculator", "Sources", "About")
    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 12.dp) {
            titles.forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, fontSize = 13.sp) })
            }
        }
        when (tab) {
            0 -> HalfLifeTool(state)
            1 -> SourcesTool(state)
            else -> AboutTool()
        }
    }
}

@Composable
private fun HalfLifeTool(state: PiruState) {
    var amount by remember { mutableStateOf(100.0) }
    var halfLifeH by remember { mutableStateOf(6.0) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("How much is left in your body?", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("Dose: ${amount.roundToInt()} mg", fontSize = 13.sp)
        Slider(amount.toFloat().coerceIn(1f, 1000f), { amount = it.toDouble() }, valueRange = 1f..1000f)
        Text("Half-life: ${"%.1f".format(halfLifeH)} h", fontSize = 13.sp)
        Slider(halfLifeH.toFloat().coerceIn(0.1f, 72f), { halfLifeH = it.toDouble() }, valueRange = 0.1f..72f)
        Spacer(Modifier.height(10.dp))
        val ke = PKModel.keFromHalfLifeMinutes(halfLifeH * 60)
        val ka = PKModel.defaultKa(ke)
        val lineColor = MaterialTheme.colorScheme.primary
        Box(Modifier.fillMaxWidth().height(140.dp)) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height
                val tmax = PKModel.tmax(ke, ka).toFloat()
                val totalMin = halfLifeH * 60 * 5
                val path = androidx.compose.ui.graphics.Path()
                val steps = 120
                for (i in 0..steps) {
                    val t = i.toDouble() / steps * totalMin
                    val frac = PKModel.fractionRemainingInBody(t, ke, ka)
                    val x = (i.toFloat() / steps.toFloat()) * w
                    val y = h - frac.toFloat() * (h - 6.dp.toPx())
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path = path, color = lineColor, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
                drawLine(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.4f),
                    Offset(tmax / totalMin.toFloat() * w, 0f), Offset(tmax / totalMin.toFloat() * w, h), 1f)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Peak (Tmax) ≈ ${tmaxLabel(PKModel.tmax(ke, ka))} · at 24h ${
            (PKModel.fractionRemainingInBody(1440.0, ke, ka) * 100).roundToInt()
        }% remains", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text("Body weight", fontWeight = FontWeight.SemiBold)
        var weight by remember { mutableStateOf(state.weightKg.toFloat()) }
        Slider(weight, { weight = it }, valueRange = 30f..200f, onValueChangeFinished = {
            state.repo.setWeightKg(weight.toDouble())
        })
        Text("${"%.0f".format(weight)} kg (used for zero-order clearance scaling)", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun tmaxLabel(minutes: Double): String = when {
    minutes >= 60 -> "${minutes / 60} h"
    else -> "${minutes.roundToInt()} min"
}

@Composable
private fun SourcesTool(state: PiruState) {
    val store = if (SubstanceStoreHolder.ready) SubstanceStoreHolder.store else null
    var catalog by remember(store) { mutableStateOf(store?.sourceCatalog() ?: emptyList()) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Source priority", fontWeight = FontWeight.SemiBold)
        Text(
            "Each dose range, duration, and fact resolves from the highest-priority enabled source. Toggle to enable/disable; ↑ to promote.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        catalog.forEachIndexed { i, (slug, disp, enabled) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = enabled,
                    onCheckedChange = { on ->
                        val list = catalog.map { t -> t.first to if (t.first == slug) on else t.third }
                        store?.setSourceOrder(list)
                        catalog = store?.sourceCatalog() ?: catalog
                        scope.launch { state.refresh() }
                    },
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(disp, fontSize = 14.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    if (i > 0) {
                        val mut = catalog.toMutableList()
                        val flags = catalog.associate { it.first to it.third }
                        val tmp = mut[i - 1]
                        mut[i - 1] = mut[i]
                        mut[i] = tmp
                        store?.setSourceOrder(mut.map { it.first to flags.getValue(it.first) })
                        catalog = store?.sourceCatalog() ?: catalog
                    }
                }, enabled = i > 0) { Text("↑", fontSize = 14.sp) }
            }
        }
    }
}

@Composable
private fun AboutTool() {
    val link = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Piru for Android", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("A dose journal and a pharmacopeia - what you took, and what it's still doing.♡",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))
        Text("1,689 substances modeled with one-compartment PK, zero-order alcohol kinetics, " +
            "phase-shaped effect curves, and mechanism-class tolerance dynamics - a direct Kotlin port " +
            "of the iOS engines (PKModel / TimelineCurveModel / PDModel / Interactions).", fontSize = 12.sp)
        Spacer(Modifier.height(18.dp))
        Text("Credits", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Row { Text("Android port by ", fontSize = 13.sp); LinkRow("ahura", "https://github.com/ahurkkkkkkk") }
        Spacer(Modifier.height(8.dp))
        Row { Text("Original iOS app made by ", fontSize = 13.sp); LinkRow("Lily", "https://github.com/pharmacykitty") }
        Row { Text("- continued by ", fontSize = 13.sp); LinkRow("kageroumado", "https://github.com/kageroumado") }
        Spacer(Modifier.height(10.dp))
        Text("Source (upstream iOS): github.com/kageroumado/piru",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("This Android build reproduces the Piru iOS app's offline substance database and PK math; " +
            "Health Connect, widgets, and watch support are Android-side gaps for later phases.",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LinkRow(label: String, url: String) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Text(
        label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable {
            runCatching {
                ctx.startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(url),
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        },
    )
}
