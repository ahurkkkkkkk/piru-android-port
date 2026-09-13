package com.piru.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.piru.app.models.RouteOfAdministration
import java.util.UUID

/**
 * One logged dose. Port of the DoseEntry SwiftData model (fields kept where they
 * affect PK curves, the journal, or identity).
 */
data class DoseEntry(
    val id: String = UUID.randomUUID().toString(),
    val substance: String,
    val amount: Double,
    val unit: String = "mg",
    val route: RouteOfAdministration = RouteOfAdministration.Oral,
    val saltForm: String? = null,
    val isomer: String? = null,
    val releaseForm: String? = null,
    val productName: String? = null,
    val substanceUID: String? = null,
    val displayNameSnapshot: String? = null,
    /** epoch millis */
    val timestamp: Long = System.currentTimeMillis(),
    val notes: String? = null,
    val tags: String? = null,
    val isBackgroundMed: Boolean = false,
    val hadGrapefruit: Boolean? = null,
    val isApproximate: Boolean = false,
    val volumeML: Double? = null,
    val abv: Double? = null,
    val drinkName: String? = null,
    val sessionId: String? = null,
) {
    /** "identityKey": PSID family + form facets, or lowercased name. */
    fun identityKey(): String {
        val base = substanceUID ?: substance.lowercase()
        val facets = listOfNotNull(isomer?.uppercase(), releaseForm?.uppercase(), saltForm).joinToString("|")
        return if (facets.isEmpty()) base else "$base|$facets"
    }
}

data class DailyDoseItem(
    val id: String = UUID.randomUUID().toString(),
    val substance: String,
    val substanceUID: String? = null,
    val amount: Double,
    val unit: String,
    val route: String = "oral",
    /** minutes-from-midnight reminder times */
    val reminderMinutes: List<Int> = emptyList(),
    val isQuiet: Boolean = false,
    val isAsNeeded: Boolean = false,
    val sortOrder: Int = 0,
) {
    companion object {
        fun encodeTimes(times: List<Int>) = times.joinToString(",")
        fun decodeTimes(s: String?) = s?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
    }
}

data class Routine(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val itemIds: List<String> = emptyList(),
    val reminderMinutes: List<Int> = emptyList(),
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
)

/**
 * User-facing writable store: dose log, sessions, colors, daily meds, routines,
 * favorites/recents, profile, source order, tolerance snapshots.
 * Plain SQLite (mirrors the iOS SwiftData store + piru-user-prefs.sqlite).
 *
 * All upserts use `INSERT OR REPLACE` / `INSERT OR IGNORE` (not `ON CONFLICT`),
 * and all values bind typed through ContentValues so API 26 (SQLite 3.19) works
 * and numeric columns sort numerically.
 */
class UserDatabase(context: Context) : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object { const val DB_NAME = "piru-user.sqlite"; const val DB_VERSION = 1 }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE dose_entries(
              id TEXT PRIMARY KEY, substance TEXT NOT NULL, amount REAL NOT NULL, unit TEXT NOT NULL,
              route TEXT NOT NULL, salt_form TEXT, isomer TEXT, release_form TEXT, product_name TEXT,
              substance_uid TEXT, display_name TEXT, timestamp INTEGER NOT NULL, notes TEXT, tags TEXT,
              is_background INTEGER NOT NULL DEFAULT 0, had_grapefruit INTEGER, is_approximate INTEGER NOT NULL DEFAULT 0,
              volume_ml REAL, abv REAL, drink_name TEXT, session_id TEXT)
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_entry_ts ON dose_entries(timestamp)")
        db.execSQL("CREATE INDEX idx_entry_id ON dose_entries(id)")
        db.execSQL("CREATE INDEX idx_entry_session ON dose_entries(session_id)")
        db.execSQL(
            """
            CREATE TABLE sessions(
              id TEXT PRIMARY KEY, started_at INTEGER NOT NULL, ended_at INTEGER,
              name TEXT, summary TEXT, notes_json TEXT, is_background INTEGER NOT NULL DEFAULT 0)
            """.trimIndent()
        )
        db.execSQL("CREATE TABLE substance_colors(substance TEXT PRIMARY KEY, hex TEXT NOT NULL)")
        db.execSQL("CREATE TABLE favorites(key TEXT PRIMARY KEY, substance TEXT NOT NULL, substance_uid TEXT, isomer TEXT, release_form TEXT, salt_form TEXT, added_at INTEGER)")
        db.execSQL("CREATE TABLE recents(key TEXT PRIMARY KEY, substance TEXT NOT NULL, substance_uid TEXT, isomer TEXT, release_form TEXT, salt_form TEXT, last_used INTEGER)")
        db.execSQL(
            """
            CREATE TABLE daily_items(
              id TEXT PRIMARY KEY, substance TEXT NOT NULL, substance_uid TEXT, amount REAL NOT NULL,
              unit TEXT NOT NULL, route TEXT NOT NULL, times TEXT, is_quiet INTEGER NOT NULL DEFAULT 0,
              is_prn INTEGER NOT NULL DEFAULT 0, sort_order INTEGER NOT NULL DEFAULT 0)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE routines(id TEXT PRIMARY KEY, name TEXT NOT NULL, item_ids TEXT, times TEXT,
              enabled INTEGER NOT NULL DEFAULT 1, sort_order INTEGER NOT NULL DEFAULT 0)
            """.trimIndent()
        )
        db.execSQL("CREATE TABLE custom_substances(name TEXT PRIMARY KEY, category TEXT, default_unit TEXT, hex TEXT, half_life_min REAL, notes TEXT)")
        db.execSQL("CREATE TABLE source_order(slug TEXT PRIMARY KEY, priority INTEGER NOT NULL, enabled INTEGER NOT NULL DEFAULT 1)")
        db.execSQL(
            """
            CREATE TABLE tolerance_state(target TEXT PRIMARY KEY, s_acute REAL NOT NULL, s_adaptive REAL NOT NULL,
              s_deep REAL NOT NULL, s_synthesis REAL NOT NULL, chronic REAL NOT NULL, last_updated INTEGER NOT NULL)
            """.trimIndent()
        )
        db.execSQL("CREATE TABLE med_taken(item_id TEXT NOT NULL, day TEXT NOT NULL, timestamp INTEGER NOT NULL, PRIMARY KEY(item_id, day))")
        db.execSQL("CREATE TABLE settings(key TEXT PRIMARY KEY, value TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { /* additive versions later */ }

    // ---- dose entries ----

    fun insertEntry(e: DoseEntry) {
        writableDatabase.insert("dose_entries", null, entryValues(e))
    }

    fun updateEntry(e: DoseEntry) {
        writableDatabase.update("dose_entries", entryValues(e), "id=?", arrayOf(e.id))
    }

    fun deleteEntry(id: String) {
        writableDatabase.delete("dose_entries", "id=?", arrayOf(id))
    }

    private fun entryValues(e: DoseEntry) = ContentValues().apply {
        put("id", e.id); put("substance", e.substance); put("amount", e.amount); put("unit", e.unit)
        put("route", e.route.rawValue); put("salt_form", e.saltForm); put("isomer", e.isomer)
        put("release_form", e.releaseForm); put("product_name", e.productName); put("substance_uid", e.substanceUID)
        put("display_name", e.displayNameSnapshot); put("timestamp", e.timestamp); put("notes", e.notes); put("tags", e.tags)
        put("is_background", if (e.isBackgroundMed) 1 else 0)
        put("had_grapefruit", e.hadGrapefruit?.let { if (it) 1 else 0 })
        put("is_approximate", if (e.isApproximate) 1 else 0)
        put("volume_ml", e.volumeML); put("abv", e.abv); put("drink_name", e.drinkName); put("session_id", e.sessionId)
    }

    fun allEntries(): List<DoseEntry> = readableDatabase.rawQuery(
        "SELECT id, substance, amount, unit, route, salt_form, isomer, release_form, product_name," +
            " substance_uid, display_name, timestamp, notes, tags, is_background, had_grapefruit," +
            " is_approximate, volume_ml, abv, drink_name, session_id FROM dose_entries ORDER BY timestamp ASC", null
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    DoseEntry(
                        id = c.getString(0), substance = c.getString(1), amount = c.getDouble(2), unit = c.getString(3),
                        route = RouteOfAdministration.fromString(c.getString(4)),
                        saltForm = c.getNullable(5), isomer = c.getNullable(6), releaseForm = c.getNullable(7),
                        productName = c.getNullable(8), substanceUID = c.getNullable(9), displayNameSnapshot = c.getNullable(10),
                        timestamp = c.getLong(11), notes = c.getNullable(12), tags = c.getNullable(13),
                        isBackgroundMed = c.getInt(14) == 1,
                        hadGrapefruit = if (c.isNull(15)) null else c.getInt(15) == 1,
                        isApproximate = c.getInt(16) == 1,
                        volumeML = if (c.isNull(17)) null else c.getDouble(17),
                        abv = if (c.isNull(18)) null else c.getDouble(18),
                        drinkName = c.getNullable(19), sessionId = c.getNullable(20),
                    )
                )
            }
        }
    }

    fun entryCountAndNewest(): Pair<Int, Long?> {
        readableDatabase.rawQuery("SELECT count(*), max(timestamp) FROM dose_entries", null).use {
            if (it.moveToFirst()) {
                val n = it.getInt(0); val t = if (it.isNull(1)) null else it.getLong(1)
                return n to t
            }
        }
        return 0 to null
    }

    // ---- colors / favorites / recents ----

    fun setColor(substance: String, hex: String) {
        writableDatabase.insertWithOnConflict(
            "substance_colors", null,
            ContentValues().apply { put("substance", substance.lowercase()); put("hex", hex) },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun allColors(): Map<String, String> = readableDatabase.rawQuery("SELECT substance, hex FROM substance_colors", null).use { c ->
        buildMap { while (c.moveToNext()) put(c.getString(0).lowercase(), c.getString(1)) }
    }

    fun favorites(): List<String> = readableDatabase.rawQuery("SELECT key FROM favorites ORDER BY added_at DESC", null).use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0)) }
    }

    fun isFavorite(key: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM favorites WHERE key=?", arrayOf(key)).use { it.moveToFirst() }

    fun toggleFavorite(key: String, substance: String, uid: String?, isomer: String?, releaseForm: String?, saltForm: String?) {
        if (isFavorite(key)) {
            writableDatabase.delete("favorites", "key=?", arrayOf(key))
        } else {
            writableDatabase.insert(
                "favorites", null,
                ContentValues().apply {
                    put("key", key); put("substance", substance); put("substance_uid", uid)
                    put("isomer", isomer); put("release_form", releaseForm); put("salt_form", saltForm)
                    put("added_at", System.currentTimeMillis())
                },
            )
        }
    }

    fun recents(limit: Int = 12): List<Pair<String, String>> =
        readableDatabase.rawQuery("SELECT key, substance FROM recents ORDER BY last_used DESC LIMIT $limit", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
        }

    fun touchRecent(entry: DoseEntry) {
        writableDatabase.insertWithOnConflict(
            "recents", null,
            ContentValues().apply {
                put("key", entry.identityKey()); put("substance", entry.substance); put("substance_uid", entry.substanceUID)
                put("isomer", entry.isomer); put("release_form", entry.releaseForm); put("salt_form", entry.saltForm)
                put("last_used", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    // ---- profile / settings ----

    fun weightKg(): Double? = settingDouble("weight_kg")
    fun setWeightKg(v: Double) = setSetting("weight_kg", v.toString())

    fun setSetting(key: String, value: String) {
        writableDatabase.insertWithOnConflict(
            "settings", null,
            ContentValues().apply { put("key", key); put("value", value) },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun getSetting(key: String): String? = readableDatabase.rawQuery("SELECT value FROM settings WHERE key=?", arrayOf(key)).use {
        if (it.moveToFirst()) it.getString(0) else null
    }

    fun settingDouble(key: String): Double? = getSetting(key)?.toDoubleOrNull()
    fun settingInt(key: String): Int? = getSetting(key)?.toIntOrNull()
    fun settingBool(key: String): Boolean = getSetting(key) == "1"

    // ---- source order ----

    fun setSourceOrder(order: List<Pair<String, Boolean>>) { // slug -> enabled, priority order
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("source_order", null, null)
            order.forEachIndexed { i, (slug, enabled) ->
                writableDatabase.insert(
                    "source_order", null,
                    ContentValues().apply { put("slug", slug); put("priority", i); put("enabled", if (enabled) 1 else 0) },
                )
            }
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    /** Returns null when user hasn't customized (falls back to DB defaults). */
    fun sourceOrder(): List<Pair<String, Boolean>>? {
        val rows = readableDatabase.rawQuery("SELECT slug, enabled FROM source_order ORDER BY priority ASC", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to (c.getInt(1) == 1)) }
        }
        return rows.ifEmpty { null }
    }

    // ---- tolerance snapshots (disposable cache, mirrors ToleranceState) ----

    fun saveToleranceSnapshot(target: String, sAcute: Double, sAdaptive: Double, sDeep: Double, sSynthesis: Double, chronic: Double) {
        writableDatabase.insertWithOnConflict(
            "tolerance_state", null,
            ContentValues().apply {
                put("target", target); put("s_acute", sAcute); put("s_adaptive", sAdaptive)
                put("s_deep", sDeep); put("s_synthesis", sSynthesis); put("chronic", chronic)
                put("last_updated", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun loadToleranceSnapshots(): Map<String, DoubleArray> =
        readableDatabase.rawQuery("SELECT target,s_acute,s_adaptive,s_deep,s_synthesis,chronic FROM tolerance_state", null).use { c ->
            buildMap {
                while (c.moveToNext()) put(c.getString(0),
                    doubleArrayOf(c.getDouble(1), c.getDouble(2), c.getDouble(3), c.getDouble(4), c.getDouble(5)))
            }
        }

    // ---- sessions ----

    /** Create-if-missing; only refresh the time bounds. A user-set name is never clobbered. */
    fun upsertSession(id: String, startedAt: Long, endedAt: Long?, name: String?, isBackground: Boolean) {
        val cv = ContentValues().apply {
            put("id", id); put("started_at", startedAt); put("ended_at", endedAt)
            put("name", name); put("is_background", if (isBackground) 1 else 0)
        }
        writableDatabase.insertWithOnConflict("sessions", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        writableDatabase.update(
            "sessions", ContentValues().apply { put("started_at", startedAt); put("ended_at", endedAt) },
            "id=?", arrayOf(id),
        )
    }

    fun sessionName(id: String): String? =
        readableDatabase.rawQuery("SELECT name FROM sessions WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) it.getNullable(0) else null }

    fun updateEntrySession(entryId: String, sessionId: String?) {
        writableDatabase.update("dose_entries", ContentValues().apply { put("session_id", sessionId) }, "id=?", arrayOf(entryId))
    }

    // ---- daily meds / routines ----

    fun upsertDailyItem(item: DailyDoseItem) {
        writableDatabase.insertWithOnConflict(
            "daily_items", null,
            ContentValues().apply {
                put("id", item.id); put("substance", item.substance); put("substance_uid", item.substanceUID)
                put("amount", item.amount); put("unit", item.unit); put("route", item.route)
                put("times", DailyDoseItem.encodeTimes(item.reminderMinutes))
                put("is_quiet", if (item.isQuiet) 1 else 0); put("is_prn", if (item.isAsNeeded) 1 else 0)
                put("sort_order", item.sortOrder)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun deleteDailyItem(id: String) { writableDatabase.delete("daily_items", "id=?", arrayOf(id)) }

    fun allDailyItems(): List<DailyDoseItem> = readableDatabase.rawQuery(
        "SELECT id,substance,substance_uid,amount,unit,route,times,is_quiet,is_prn,sort_order FROM daily_items ORDER BY sort_order ASC", null
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                DailyDoseItem(c.getString(0), c.getString(1), c.getNullable(2), c.getDouble(3), c.getString(4),
                    c.getString(5), DailyDoseItem.decodeTimes(c.getNullable(6)), c.getInt(7) == 1, c.getInt(8) == 1, c.getInt(9))
            )
        }
    }

    fun markTaken(itemId: String, day: String) {
        writableDatabase.insertWithOnConflict(
            "med_taken", null,
            ContentValues().apply { put("item_id", itemId); put("day", day); put("timestamp", System.currentTimeMillis()) },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    fun takenToday(itemIds: List<String>, day: String): Set<String> {
        if (itemIds.isEmpty()) return emptySet()
        val placeholders = itemIds.joinToString(",") { "?" }
        return readableDatabase.rawQuery(
            "SELECT item_id FROM med_taken WHERE day=? AND item_id IN ($placeholders)",
            (listOf(day) + itemIds).toTypedArray()
        ).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
    }

    fun upsertRoutine(r: Routine) {
        writableDatabase.insertWithOnConflict(
            "routines", null,
            ContentValues().apply {
                put("id", r.id); put("name", r.name); put("item_ids", r.itemIds.joinToString(","))
                put("times", DailyDoseItem.encodeTimes(r.reminderMinutes))
                put("enabled", if (r.enabled) 1 else 0); put("sort_order", r.sortOrder)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun allRoutines(): List<Routine> = readableDatabase.rawQuery(
        "SELECT id,name,item_ids,times,enabled,sort_order FROM routines ORDER BY sort_order ASC", null
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                Routine(c.getString(0), c.getString(1),
                    c.getNullable(2)?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
                    DailyDoseItem.decodeTimes(c.getNullable(3)), c.getInt(4) == 1, c.getInt(5))
            )
        }
    }

    fun deleteRoutine(id: String) { writableDatabase.delete("routines", "id=?", arrayOf(id)) }
}

fun Cursor.getNullable(i: Int): String? = if (isNull(i)) null else getString(i)
