@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.piru.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotMutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.piru.app.MainActivity
import com.piru.app.data.AppRepository
import com.piru.app.data.SubstanceStoreHolder
import kotlinx.coroutines.*
import java.util.Calendar

/**
 * Root scaffold - 5-tab shell (Journal / Library / Tools / Insights / Search) plus the
 * Quick Log bottom sheet, port of ContentView.swift's tab structure.
 */
@Composable
fun PiruAppRoot(activity: MainActivity) {
    val repo = activity.repo
    var tab by remember { mutableIntStateOf(0) }
    var quickLogOpen by remember { mutableStateOf(false) }
    var quickLogPrefill by remember { mutableStateOf<Pair<String, Double>?>(null) }
    var detailName by remember { mutableStateOf<String?>(null) }
    val state = remember { PiruState(repo) }
    val scope = rememberCoroutineScope()
    val viewTick = remember { mutableStateOf(System.currentTimeMillis()) }

    // Poll the store readiness + refresh whenever the generation changes.
    LaunchedEffect(state) {
        state.attach()
        while (isActive) {
            state.refresh()
            delay(5_000)
        }
    }
    // Keep "now" fresh for the now-line / active windows (1-min cadence).
    LaunchedEffect(Unit) {
        while (true) {
            viewTick.value = System.currentTimeMillis()
            delay(60_000)
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(rememberVectorPainter(Icons.Outlined.Home), null) }, label = { Text("Journal", fontSize = 11.sp) },
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(rememberVectorPainter(Icons.Outlined.List), null) }, label = { Text("Library", fontSize = 11.sp) },
                )
                NavigationBarItem(
                    selected = tab == 2, onClick = { tab = 2 },
                    icon = { Icon(rememberVectorPainter(Icons.Outlined.Build), null) }, label = { Text("Tools", fontSize = 11.sp) },
                )
                NavigationBarItem(
                    selected = tab == 3, onClick = { tab = 3 },
                    icon = { Icon(rememberVectorPainter(Icons.Outlined.Star), null) }, label = { Text("Insights", fontSize = 11.sp) },
                )
                NavigationBarItem(
                    selected = tab == 4, onClick = { tab = 4 },
                    icon = { Icon(rememberVectorPainter(Icons.Outlined.Search), null) }, label = { Text("Search", fontSize = 11.sp) },
                )
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { quickLogPrefill = null; quickLogOpen = true },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Log a dose") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val now = viewTick.value
            when (tab) {
                0 -> JournalScreen(state, now, onOpenSubstance = { detailName = it })
                1 -> LibraryScreen(state, onOpenSubstance = { detailName = it })
                2 -> ToolsScreen(state)
                3 -> InsightsScreen(state, now)
                4 -> SearchScreen(state, onOpenSubstance = { detailName = it })
            }
        }
    }

    detailName?.let { name ->
        var openLog by remember(name) { mutableStateOf(false) }
        if (openLog) {
            quickLogPrefill = name to 0.0
            quickLogOpen = true
        }
        ModalBottomSheet(onDismissRequest = { detailName = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            SubstanceDetailSheet(state, name, onLogDose = { detailName = null; openLog = true })
        }
    }

    if (quickLogOpen) {
        ModalBottomSheet(
            onDismissRequest = { quickLogOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            QuickLogSheet(
                state = state,
                prefill = quickLogPrefill,
                onDone = { scope.launch { state.refresh() }; quickLogOpen = false },
            )
        }
    }
}

/** Shared observable app state: dose log snapshot + derived reads. */
class PiruState(val repo: AppRepository) {
    var entries by mutableStateOf<List<com.piru.app.data.DoseEntry>>(emptyList())
    var weightKg by mutableStateOf(60.0)
    var storeReady by mutableStateOf(false)
    var lastGen by mutableIntStateOf(0)

    private var scope: CoroutineScope? = null

    fun attach() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        repo.onChange { scope?.launch(Dispatchers.Main) { refresh() } }
    }

    suspend fun refresh() {
        if (!SubstanceStoreHolder.ready) { storeReady = false; return }
        storeReady = true
        if (repo.storeGeneration != lastGen || entries.isEmpty()) {
            lastGen = repo.storeGeneration
            entries = repo.entries()
        }
        weightKg = repo.weightKg()
    }

    fun colorHex(name: String) = repo.colorHex(name)

    fun entriesForRange(from: Long, to: Long) = entries.filter { it.timestamp in from..to }

    fun startOfDay(offsetDays: Int = 0, from: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = from
            add(Calendar.DAY_OF_YEAR, offsetDays)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
fun PiruCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
    ) { Column(Modifier.padding(14.dp), content = content) }
}

@Composable
fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize().padding(48.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}
