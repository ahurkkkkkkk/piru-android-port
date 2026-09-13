package com.piru.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.piru.app.data.SubstanceStoreHolder
import com.piru.app.models.SubstanceCategory

/**
 * Library tab — category family cards (port of LibraryBrowseView): each card is a
 * category-tinted gradient hero that pushes into the substance list for that family.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(state: PiruState, onOpenSubstance: (String) -> Unit) {
    var selected by remember { mutableStateOf<SubstanceCategory?>(null) }
    val store = if (SubstanceStoreHolder.ready) SubstanceStoreHolder.store else null

    if (selected == null) {
        val counts = remember(store) {
            runCatching {
                store?.categoryCounts() ?: emptyMap()
            }.getOrDefault(emptyMap())
        }
        LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
            item {
                Text("The library", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                Text(
                    "${store?.coreCount() ?: "1,689"} substances · every claim cited to its source",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, 0.dp),
                )
            }
            items(SubstanceCategory.browsable.chunked(2)) { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    row.forEach { cat ->
                        val count = counts[cat.rawValue] ?: 0
                        FamilyCard(cat, count, Modifier.weight(1f).height(110.dp).padding(4.dp)) { selected = cat }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            item { Spacer(Modifier.height(90.dp)) }
        }
    } else {
        val cat = selected!!
        val subs = remember(cat, store) { runCatching { store?.substancesInCategory(cat) ?: emptyList() }.getOrDefault(emptyList()) }
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                TextButton(onClick = { selected = null }) { Text("‹ Library") }
                Text(cat.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(subs) { hit ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenSubstance(hit.name) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(8.dp).background(hexColor(state.colorHex(hit.name)), shape = RoundedCornerShape(4.dp)))
                        Spacer(Modifier.width(10.dp))
                        Text(hit.name, fontSize = 14.sp)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                }
                item { Spacer(Modifier.height(90.dp)) }
            }
        }
    }
}

@Composable
fun FamilyCard(cat: SubstanceCategory, count: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val base = if (androidx.compose.foundation.isSystemInDarkTheme()) cat.darkColor else cat.lightColor
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.linearGradient(listOf(base.copy(alpha = 0.85f), base.copy(alpha = 0.25f)))
            ).padding(14.dp),
        ) {
            Column {
                Text(cat.label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.weight(1f))
                Text("$count substances", color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp)
            }
        }
    }
}

/** Search tab — ranked search over name/alias/brand (port of the dock's search + SubstanceSearchResultsList). */
@Composable
fun SearchScreen(state: PiruState, onOpenSubstance: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val store = if (SubstanceStoreHolder.ready) SubstanceStoreHolder.store else null
    val hits = remember(query) { query.takeIf { it.isNotBlank() && store != null }?.let { store!!.search(it, 40) } ?: emptyList() }
    Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = { Text("Search 1,689 substances, brands, aliases…") },
            singleLine = true, shape = RoundedCornerShape(14.dp),
        )
        if (query.isBlank()) {
            EmptyHint("Search by substance name, brand (“Concerta”, “Vyvanse”), or alias.")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(hits) { hit ->
                    val s = hit.substance
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenSubstance(s.displayTitle) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(10.dp).background(hexColor(state.colorHex(s.name)), shape = RoundedCornerShape(5.dp)))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.displayTitle, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text(
                                s.category.label + hit.matchedAlias?.let { " · “$it”" }.orEmpty(),
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                }
                item { Spacer(Modifier.height(90.dp)) }
            }
        }
    }
}
