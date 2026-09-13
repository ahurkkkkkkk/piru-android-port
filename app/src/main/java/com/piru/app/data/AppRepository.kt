package com.piru.app.data

import com.piru.app.data.DoseEntry
import com.piru.app.models.RouteOfAdministration
import com.piru.app.models.SubstanceCategory
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** App-level repository bridging the two stores + observable state. */
class AppRepository(val userDb: UserDatabase) {

    /** Monotonic generation bumped on every dose-log mutation (cache invalidation). */
    @Volatile var storeGeneration: Int = 0; private set

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    fun onChange(l: () -> Unit) { listeners.add(l) }
    fun removeChange(l: () -> Unit) { listeners.remove(l) }
    private fun notifyChanged() {
        storeGeneration++
        listeners.forEach { runCatching { it() } }
    }

    suspend fun entries(): List<DoseEntry> = userDb.allEntries()
    suspend fun logEntry(e: DoseEntry) {
        userDb.insertEntry(e)
        userDb.touchRecent(e)
        notifyChanged()
    }

    suspend fun updateEntry(e: DoseEntry) { userDb.updateEntry(e); notifyChanged() }
    suspend fun deleteEntry(id: String) { userDb.deleteEntry(id); notifyChanged() }

    /** Assign sessions to a batch of just-logged doses (port of SessionClustering at log time). */
    suspend fun assignSessions(entries: List<DoseEntry>) {
        val all = userDb.allEntries()
        val clusters = com.piru.app.engine.SessionClustering.cluster(all)
        for (c in clusters) {
            userDb.upsertSession(c.id, c.startMillis, c.endMillis, null, false)
            for (e in c.entries) userDb.updateEntrySession(e.id, c.id)
        }
        notifyChanged()
    }

    fun entryCountAndNewest(): Pair<Int, Long?> = userDb.entryCountAndNewest()

    /** Color resolution — port of SubstancePalette.hex: user map → deterministic preset. */
    fun colorHex(nameOrAlias: String): String {
        val store = runCatching { SubstanceStoreHolder.store }.getOrNull()
        val canonical = (store?.lookup(nameOrAlias)?.displayTitle ?: nameOrAlias).lowercase()
        userDb.allColors()[canonical]?.let { return it }
        return PRESET[deterministicIndex(canonical)]
    }

    private fun deterministicIndex(key: String): Int {
        var h: Int = 0x811c9dc5.toInt() // FNV-1a offset basis 2166136261 as Int bits
        for (b in key.toByteArray()) { h = h xor b.toInt(); h *= 0x01000193 }
        return (h and 0x7fffffff) % PRESET.size
    }

    companion object {
        val PRESET = listOf(
            "007AFF", "5AC8FA", "34C759", "30D158", "FF9F0A", "FF375F", "FF2D55", "BF5AF2",
            "5856D6", "AF52DE", "FF6B6B", "4ECDC4", "FFD93D", "6BCB77", "4D96FF", "FF8C42",
            "C77DFF", "80ED99", "7209B7", "F72585", "4361EE", "4CC9F0", "F77F00", "D62828",
            "02C39A", "00A8E8", "7AE582", "F8B500", "E55600", "5F27CD", "00B894", "FD79A8",
            "6C5CE7", "FDCB6E", "E17055", "00CEC9", "81ECEC", "FAB1A0", "74B9FF", "A29BFE",
            "55E6C1", "FF7F50", "48C9B0", "5DADE2", "F4D03F", "EB984E",
        )
    }

    // ---- daily meds / routines ----

    fun saveDailyItem(item: DailyDoseItem) { userDb.upsertDailyItem(item); notifyChanged() }
    fun deleteDailyItem(id: String) { userDb.deleteDailyItem(id); notifyChanged() }
    fun dailyItems(): List<DailyDoseItem> = userDb.allDailyItems()
    fun routines(): List<Routine> = userDb.allRoutines()
    fun saveRoutine(r: Routine) { userDb.upsertRoutine(r); notifyChanged() }
    fun deleteRoutine(id: String) { userDb.deleteRoutine(id); notifyChanged() }
    fun takenToday(itemIds: List<String>): Set<String> =
        userDb.takenToday(itemIds, dayKey(System.currentTimeMillis()))

    fun markTaken(itemId: String) { userDb.markTaken(itemId, dayKey(System.currentTimeMillis())); notifyChanged() }

    fun dayKey(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getDefault() }.format(millis)

    /** Hour bucket for time-of-day grouping (Morning/Afternoon/Evening/Night/Anytime). */
    fun timeBucket(hour: Int?): String = when {
        hour == null -> "Anytime"
        hour in 5..11 -> "Morning"
        hour in 12..16 -> "Afternoon"
        hour in 17..21 -> "Evening"
        else -> "Night"
    }

    // ---- profile ----

    fun weightKg(): Double = userDb.weightKg() ?: com.piru.app.models.PKModel.REFERENCE_BODY_WEIGHT_KG
    fun setWeightKg(v: Double) = userDb.setWeightKg(v)
}
