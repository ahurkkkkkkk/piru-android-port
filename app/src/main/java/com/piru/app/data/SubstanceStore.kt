package com.piru.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.piru.app.models.DurationProfile
import com.piru.app.models.DurationRange
import com.piru.app.models.PKModel
import com.piru.app.models.RouteOfAdministration
import com.piru.app.models.SubstanceCategory
import com.piru.app.models.ByVolumeDosing
import java.io.File

/**
 * Read-only substance library backed by the bundled `piru-substances.sqlite`
 * (copied from assets on first launch, like the iOS SubstanceDBUpdater/Bundle path).
 *
 * Mirrors the iOS architecture:
 *  - startup in-memory indexes: name→id, alias→id, aliasOwners
 *  - source-priority resolution over `sources.default_priority` (user-reorderable via
 *    the writable `source_order` table)
 *  - `durations`/`dose_ranges` resolved by per-(route,salt,isomer[,phase]) windowed queries
 *  - batch-resolved read models cached; invalidated when source order or the DB changes
 */
class SubstanceStore private constructor(
    private val db: SQLiteDatabase,
    private val userDb: UserDatabase,
) {
    companion object {
        @Volatile private var instance: SubstanceStore? = null

        fun get(context: Context): SubstanceStore {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val appContext = context.applicationContext
                val dbFile = File(appContext.getDatabasePath("piru-substances.sqlite").parentFile, "piru-substances.sqlite")
                dbFile.parentFile?.mkdirs()
                if (!dbFile.exists()) {
                    // atomic copy: tmp then rename, so a kill mid-first-launch can't
                    // leave a truncated DB that the exists() check would accept.
                    val tmp = File(dbFile.parentFile, "piru-substances.tmp")
                    appContext.assets.open("piru-substances.sqlite").use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (!tmp.renameTo(dbFile)) { tmp.copyTo(dbFile, overwrite = true); tmp.delete() }
                }
                val store = SubstanceStore(
                    SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY),
                    UserDatabase(appContext),
                )
                store.loadStartup()
                instance = store
                return store
            }
        }
    }

    // ---- startup indexes ----

    data class CoreRow(
        val id: Long, val canonicalName: String, val displayName: String, val substanceUID: String?,
        val isStub: Boolean, val popularity: Int, val molecularWeight: Double?, val formula: String?,
        val cas: String?, val regulatoryStatus: String?, val durationImplausible: Boolean, val pkReferenceName: String?,
    )

    private val coreById = HashMap<Long, CoreRow>()
    private val nameIndex = HashMap<String, Long>()        // canonical_name lowercase -> id (first wins)
    private val aliasIndex = HashMap<String, Long>()       // alias_normalized -> id (first wins)
    private val aliasOwners = HashMap<String, MutableList<Long>>() // normalized -> all owners (for multi-owner aliases)
    private val aliasDisplay = HashMap<String, String>()   // normalized alias -> display alias (first wins)
    private val aliasesOfSubstance = HashMap<Long, MutableList<String>>()
    private val sourcesById = HashMap<Long, Pair<String, String>>() // id -> (slug, display_name)
    private val sourcesBySlug = HashMap<String, Long>()

    @Volatile private var enabledSlugs: List<String> = emptyList()      // priority order
    @Volatile private var priorityCaseSQL: String = "999"
    @Volatile private var enabledSourceList: String = "''"
    @Volatile private var startupLoaded = false

    /** Cache of fully-resolved substance records keyed by `id|sourceSignature`. */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Substance>()

    private fun signature(): String = enabledSlugs.joinToString(",")

    fun loadStartup() {
        // sources
        db.rawQuery("SELECT id, slug, display_name, default_priority, default_enabled FROM sources ORDER BY default_priority ASC", null).use { c ->
            val defaults = ArrayList<Pair<String, Boolean>>()
            while (c.moveToNext()) {
                val id = c.getLong(0)
                sourcesById[id] = c.getString(1) to c.getString(2)
                sourcesBySlug[c.getString(1)] = id
                if (c.getInt(4) == 1) defaults.add(c.getString(1) to true)
            }
            enabledSlugs = userDb.sourceOrder()?.mapNotNull { if (it.second) it.first else null } ?: defaults.map { it.first }
            rebuildPrioritySQL()
        }
        db.rawQuery(
            "SELECT id, canonical_name, display_name, substance_uid, is_stub, popularity, molecular_weight," +
                " formula, cas, regulatory_status, duration_implausible, pk_reference_name FROM substances", null
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                coreById[id] = CoreRow(
                    id, c.getString(1), (c.getString(2)?.takeIf { it.isNotBlank() } ?: c.getString(1)),
                    c.getString(3), c.getInt(4) == 1, if (c.isNull(5)) 0 else c.getInt(5),
                    if (c.isNull(6)) null else c.getDouble(6), c.getString(7), c.getString(8), c.getString(9),
                    !c.isNull(10) && c.getInt(10) == 1, c.getString(11),
                )
                val key = c.getString(1).lowercase()
                nameIndex.putIfAbsent(key, id)
            }
        }
        db.rawQuery("SELECT substance_id, alias, alias_normalized FROM aliases ORDER BY COALESCE(brand_rank, 9), alias", null).use { c ->
            while (c.moveToNext()) {
                val sid = c.getLong(0)
                val alias = c.getString(1)
                val norm = (c.getString(2) ?: alias).lowercase()
                aliasIndex.putIfAbsent(norm, sid)
                aliasOwners.getOrPut(norm) { ArrayList() }.add(sid)
                aliasDisplay.putIfAbsent(norm, alias)
                aliasesOfSubstance.getOrPut(sid) { ArrayList() }.add(alias)
            }
        }
        startupLoaded = true
    }

    private fun rebuildPrioritySQL() {
        if (enabledSlugs.isEmpty()) {
            priorityCaseSQL = "999"
            enabledSourceList = "''"
            return
        }
        priorityCaseSQL = "CASE src.slug " + enabledSlugs.mapIndexed { i, s -> "WHEN '${s.replace("'", "''")}' THEN $i" }
            .joinToString(" ") + " ELSE 999 END"
        enabledSourceList = enabledSlugs.joinToString(", ") { "'${it.replace("'", "''")}'" }
    }

    fun setSourceOrder(order: List<Pair<String, Boolean>>) {
        userDb.setSourceOrder(order)
        db.rawQuery("SELECT slug FROM sources ORDER BY default_priority", null).use { c -> /* validate no-op */ }
        // reload enabled list from persisted
        val persisted = userDb.sourceOrder() ?: return
        enabledSlugs = persisted.mapNotNull { if (it.second) it.first else null }
        rebuildPrioritySQL()
        cache.clear()
    }

    fun sourceCatalog(): List<Triple<String, String, Boolean>> {
        val order = userDb.sourceOrder()
        val slugs = ArrayList<Pair<String, String>>() // slug -> display
        db.rawQuery("SELECT slug, display_name FROM sources ORDER BY default_priority ASC", null).use { c ->
            while (c.moveToNext()) slugs.add(c.getString(0) to c.getString(1))
        }
        val enabled = order?.toMap() ?: enabledSlugs.associateWith { true }
        return slugs.map { (slug, disp) -> Triple(slug, disp, enabled[slug] ?: enabledSlugs.contains(slug)) }
    }

    // ---- lookups ----

    fun substanceID(nameOrAlias: String): Long? {
        val key = nameOrAlias.trim().lowercase()
        val byName = nameIndex[key]
        val byAlias = aliasIndex[key]
        // prefer first non-stub among [name, alias, any owner]
        val owners = aliasOwners[key] ?: emptyList()
        (listOfNotNull(byName, byAlias) + owners).firstOrNull { coreById[it]?.isStub == false }?.let { return it }
        return byName ?: byAlias
    }

    fun core(id: Long): CoreRow? = coreById[id]

    fun displayName(id: Long): String? = coreById[id]?.displayName

    /** Full read model (cached). */
    fun lookup(nameOrAlias: String): Substance? {
        val id = substanceID(nameOrAlias) ?: return null
        return substanceByID(id)
    }

    fun substanceByID(id: Long): Substance? {
        val c = coreById[id] ?: return null
        val sig = signature()
        val key = "$id|$sig"
        cache[key]?.let { return it }
        val s = buildSubstance(c) ?: return null
        cache[key] = s
        return s
    }

    /** Search: exact → prefix → contains → fuzzy over in-memory indexes (iOS rankedSearch). */
    fun search(query: String, limit: Int = 50): List<SubstanceSearchHit> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val seen = HashSet<Long>()
        val hits = ArrayList<SubstanceSearchHit>()
        fun add(id: Long, matchedAlias: String? = null): Boolean {
            if (!seen.add(id)) return false
            val core = coreById[id] ?: return false
            val s = substanceByID(id) ?: return false
            hits.add(SubstanceSearchHit(s, matchedAlias, core.popularity, seen.size))
            return true
        }
        nameIndex[q]?.let { add(it) }
        aliasIndex[q]?.let { add(it, aliasDisplay[q]) }
        // prefix names
        if (hits.size < limit) {
            nameIndex.keys.filter { it.startsWith(q) && it != q }.sorted().forEach { if (hits.size >= limit) return@forEach; add(nameIndex[it]!!) }
            // prefix aliases ordered by (alias key length, key) per id shortest-first
            if (hits.size < limit) {
                val perId = HashMap<Long, String>()
                aliasIndex.entries.filter { it.key.startsWith(q) && it.key != q }.forEach { (k, id) ->
                    if (!seen.contains(id)) {
                        val cur = perId[id]
                        if (cur == null || k.length < cur.length || (k.length == cur.length && k < cur)) perId[id] = k
                    }
                }
                perId.entries.sortedWith(compareBy({ it.value.length }, { it.value })).forEach {
                    if (hits.size >= limit) return@forEach
                    add(it.key, it.value)
                }
            }
        }
        // contains names, then aliases (unseen only)
        if (hits.size < limit) {
            nameIndex.entries.filter { it.key.contains(q) && !seen.contains(it.value) }.sortedBy { it.key.length }
                .forEach { if (hits.size >= limit) return@forEach; add(it.value) }
            aliasIndex.entries.filter { it.key.contains(q) && !seen.contains(it.value) }.sortedBy { it.key.length }
                .forEach { if (hits.size >= limit) return@forEach; add(it.value) }
        }
        // fuzzy (Levenshtein) only when short
        if (hits.size < limit && q.length >= 4) {
            val cap = maxOf(1, (q.length * 0.3).toInt())
            val fuzzy = nameIndex.entries
                .filter { !seen.contains(it.value) && kotlin.math.abs(it.key.length - q.length) <= cap }
                .mapNotNull { (k, v) ->
                    val d = levenshteinBounded(k, q, cap) ?: return@mapNotNull null
                    v to d
                }
                .sortedBy { it.second }
            fuzzy.forEach { if (hits.size >= limit) return@forEach; add(it.first) }
        }
        return hits.sortedBy { it.index }
    }

    data class SubstanceSearchHit(val substance: Substance, val matchedAlias: String?, val popularity: Int, val index: Int)

    private fun levenshteinBounded(a: String, b: String, cap: Int): Int? {
        val la = a.length; val lb = b.length
        if (kotlin.math.abs(la - lb) > cap) return null
        val prev = IntArray(lb + 1) { it }
        val cur = IntArray(lb + 1)
        for (i in 1..la) {
            cur[0] = i
            var rowMin = i
            for (j in 1..lb) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                rowMin = minOf(rowMin, cur[j])
            }
            if (rowMin > cap) return null
            System.arraycopy(cur, 0, prev, 0, lb + 1)
        }
        return if (prev[lb] <= cap) prev[lb] else null
    }

    /** All substances for the Library browse (batch, resolved on demand by the UI). */
    fun allCore(): List<CoreRow> = coreById.values.sortedBy { it.canonicalName.lowercase() }

    fun coreCount(): String = coreById.size.toString()

    /** Per-category substance counts in one query (same ordering rule as categoryFor). */
    fun categoryCounts(): Map<String, Int> {
        val sql = """
            SELECT COALESCE(top.cat,'Other'), count(*) FROM substances s
             LEFT JOIN (
               SELECT c.substance_id AS sid, c.category AS cat,
                      ROW_NUMBER() OVER (PARTITION BY c.substance_id
                          ORDER BY ((c.category = 'Other' OR c.category IS NULL) AND src.slug != 'piru-curated') ASC,
                                   (c.category IS NULL) ASC, $priorityCaseSQL ASC) AS rn
                 FROM categories c JOIN sources src ON src.id = c.source_id
                WHERE src.slug IN ($enabledSourceList)
             ) top ON top.sid = s.id AND top.rn = 1
             WHERE s.is_stub = 0
             GROUP BY COALESCE(top.cat,'Other')
        """.trimIndent()
        return try {
            db.rawQuery(sql, null).use { c ->
                buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) }
            }
        } catch (e: SQLiteException) { emptyMap() }
    }

    data class CategoryRow(val id: Long, val name: String)

        /** Substance names in a category (single correlated query mirroring categoryFor's ordering). */
    fun substancesInCategory(cat: SubstanceCategory): List<CategoryRow> {
        val safeCat = cat.rawValue.replace("'", "''")
        val sql = """
            SELECT s.id, COALESCE(s.display_name, s.canonical_name) FROM substances s
             WHERE s.is_stub = 0
               AND (
                 SELECT c.category FROM categories c JOIN sources src ON src.id = c.source_id
                  WHERE c.substance_id = s.id AND src.slug IN ($enabledSourceList)
                  ORDER BY ((c.category = 'Other' OR c.category IS NULL) AND src.slug != 'piru-curated') ASC,
                           (c.category IS NULL) ASC, $priorityCaseSQL ASC LIMIT 1
               ) = '$safeCat'
             ORDER BY COALESCE(s.display_name, s.canonical_name) COLLATE NOCASE
        """.trimIndent()
        return try {
            db.rawQuery(sql, null).use { c -> buildList { while (c.moveToNext()) add(CategoryRow(c.getLong(0), c.getString(1))) } }
        } catch (e: SQLiteException) { emptyList() }
    }


    /** Split a DailyMed-style "Indications & Usage" blob into individual "For ..." phrases. */
    private fun splitIndication(text: String): List<String> {
        val t = text.trim()
        // strip the label header if present
        val body = t.substringAfter("Usage", t).trim()
        if (body.length <= 90 && !body.contains(". For ")) return listOf(body)
        val parts = Regex("""(?<=\.)\s*[Ff]or\s""").split(body).ifEmpty { listOf(body) }
        return parts.map { piece ->
            var p = piece.trim()
            // "IR tablet, ODT, oral solution: Treatment of ..." -> keep the indication after the colon
            val colon = p.indexOf(": ")
            if (colon in 4..60 && p.substring(0, colon).none { it == '.' }) p = p.substring(colon + 2).trim()
            if (!p.startsWith("For ") && !p.startsWith("Treatment") && !p.startsWith("Management") &&
                !p.startsWith("Relief") && !p.startsWith("Prophylaxis") && p.length > 60) p = "For " + p
            p
        }
            .filter { it.length in 8..140 && !it.startsWith("Usage") }
            .distinct()
            .ifEmpty { listOf(body.trimEnd('.').take(120)) }
    }

    /** Searchable condition index: returns (condition, substanceDisplayName) pairs for typeahead. */
    fun conditionIndex(): List<Pair<String, String>> {
        if (conditionIndexCache != null && !sourceOrderChangedSinceCache()) return conditionIndexCache!!
        val sql = """
            SELECT i.text, COALESCE(s.display_name, s.canonical_name)
            FROM indications i
            JOIN substances s ON s.id = i.substance_id
            WHERE s.is_stub = 0
        """.trimIndent()
        val out = try {
            db.rawQuery(sql, null).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val text = c.getString(0)
                        for (piece in splitIndication(text)) add(piece to c.getString(1))
                    }
                }
            }
        } catch (e: SQLiteException) { emptyList() }
        conditionIndexCache = out
        cachedSignature = signature()
        return out
    }

    private var conditionIndexCache: List<Pair<String, String>>? = null
    private var cachedSignature: String = ""
    private fun sourceOrderChangedSinceCache(): Boolean = signature() != cachedSignature

    /** All indications for a substance, split into "For ..." phrases (detail view). */
    fun indicationsFor(sid: Long): List<Indication> {
        val sql = """
            SELECT i.text, i.citation_id
            FROM indications i JOIN sources src ON src.id = i.source_id
            WHERE i.substance_id = ? AND src.slug IN ($enabledSourceList)
            ORDER BY $priorityCaseSQL ASC
        """.trimIndent()
        return try {
            db.rawQuery(sql, arrayOf(sid.toString())).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val text = c.getString(0)
                        val cit = if (c.isNull(1)) null else c.getLong(1)
                        splitIndication(text).forEach { add(Indication(it, cit)) }
                    }
                }
            }
        } catch (e: SQLiteException) { emptyList() }
    }

    /** Contraindications for a substance. */
    fun contraindicationsFor(sid: Long): List<Contraindication> {
        val sql = """
            SELECT c.text, c.flag, c.is_boxed_warning
            FROM contraindications c JOIN sources src ON src.id = c.source_id
            WHERE c.substance_id = ? AND src.slug IN ($enabledSourceList)
            ORDER BY c.is_boxed_warning DESC, c.text
        """.trimIndent()
        return try {
            db.rawQuery(sql, arrayOf(sid.toString())).use { c ->
                buildList {
                    while (c.moveToNext()) add(Contraindication(c.getString(0), c.getString(1), c.getInt(2) == 1))
                }
            }
        } catch (e: SQLiteException) { emptyList() }
    }

    /** Side effects for a substance. */
    fun effectsFor(sid: Long): List<Effect> {
        val sql = """
            SELECT e.text, e.kind, e.effect_category, e.vocab_id
            FROM effects e JOIN sources src ON src.id = e.source_id
            WHERE e.substance_id = ? AND src.slug IN ($enabledSourceList)
            ORDER BY e.effect_category, e.text
        """.trimIndent()
        return try {
            db.rawQuery(sql, arrayOf(sid.toString())).use { c ->
                buildList {
                    while (c.moveToNext()) add(Effect(c.getString(0), c.getString(1), c.getString(2), c.getString(3)))
                }
            }
        } catch (e: SQLiteException) { emptyList() }
    }

    /** Off-target bindings for a substance. */
    fun offTargetsFor(sid: Long): List<OffTarget> {
        val sql = """
            SELECT o.target, o.ki_or_ic50_nm, o.concern_level, o.clinical_consequence
            FROM off_targets o JOIN sources src ON src.id = o.source_id
            WHERE o.substance_id = ? AND src.slug IN ($enabledSourceList)
            ORDER BY o.concern_level DESC, o.target
        """.trimIndent()
        return try {
            db.rawQuery(sql, arrayOf(sid.toString())).use { c ->
                buildList {
                    while (c.moveToNext()) add(OffTarget(
                        c.getString(0),
                        if (c.isNull(1)) null else c.getDouble(1),
                        c.getString(2),
                        c.getString(3)
                    ))
                }
            }
        } catch (e: SQLiteException) { emptyList() }
    }


    /** Substances whose indications include this condition text (split-aware). */
    fun substancesForCondition(text: String): List<ConditionSub> {
        val sql = "SELECT s.id, COALESCE(s.display_name, s.canonical_name), i.text" +
            " FROM indications i JOIN substances s ON s.id = i.substance_id" +
            " WHERE i.text LIKE ? AND s.is_stub = 0 ORDER BY COALESCE(s.display_name, s.canonical_name) COLLATE NOCASE"
        return try {
            db.rawQuery(sql, arrayOf("%" + text.trim().take(80).replace("%","").replace("'","") + "%")).use { c ->
                buildList {
                    val seen = HashSet<Long>()
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        if (!seen.add(id)) continue
                        add(ConditionSub(id, c.getString(1)))
                    }
                }
            }
        } catch (e: SQLiteException) { emptyList() }
    }

    data class ConditionSub(val id: Long, val name: String)

    // ---- resolution ----

    private fun buildSubstance(c: CoreRow): Substance {
        val routes = routesFor(c.id)
        val halfLife = halfLifeMinutes(c.id)
        val (desc, mech) = prose(c.id)
        val zero = zeroOrderRow(c.id)
        val pk = pkParams(c.id, c)
        val category = categoryFor(c.id)
        val tags = tagsFor(c.id)
        val refDose = referenceDoseMg(routes)
        return Substance(
            id = c.id, name = c.canonicalName, displayName = c.displayName, substanceUID = c.substanceUID,
            category = category, routes = routes,
            aliases = (aliasesOfSubstance[c.id] ?: emptyList()).filter { !it.equals(c.canonicalName, ignoreCase = true) },
            description = desc, mechanism = mech, halfLifeMinutes = halfLife, formula = c.formula,
            molecularWeight = c.molecularWeight, cas = c.cas, regulatoryStatus = c.regulatoryStatus,
            isStub = c.isStub, popularity = c.popularity, pk = pk, zeroOrder = zero,
            durationImplausible = c.durationImplausible, tags = tags, referenceDoseMg = refDose,
            doseRangesByRoute = routes.associate { it.route.rawValue to it.doses },
        )
    }


    private fun routesFor(sid: Long): List<RouteData> {
        // one row per (route,salt,isomer) winning-source ladder; therapeutic dose_context ranked last
        val dosesSql = """
            SELECT route, salt_form, isomer, isomer_display_name, salt_rank, elemental_fraction, unit,
                   threshold, dose_context, light_lower, light_upper, common_lower, common_upper,
                   strong_lower, strong_upper, heavy
              FROM (
                SELECT d.route, d.salt_form, d.isomer, d.isomer_display_name, d.salt_rank, d.elemental_fraction,
                       d.unit, d.threshold, d.dose_context, d.light_lower, d.light_upper, d.common_lower,
                       d.common_upper, d.strong_lower, d.strong_upper, d.heavy,
                       ROW_NUMBER() OVER (PARTITION BY d.route, d.salt_form, d.isomer
                           ORDER BY (d.dose_context = 'therapeutic') ASC, ${fieldRank("doses", "d")}) AS rn
                  FROM dose_ranges d JOIN sources src ON src.id = d.source_id
                  ${fieldJoin("doses", "d")}
                 WHERE d.substance_id = ? AND src.slug IN ($enabledSourceList)
              ) WHERE rn = 1
        """.trimIndent()
        val rows = db.rawQuery(dosesSql, arrayOf(sid.toString())).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        DoseRow(
                            route = c.getString(0), saltForm = c.getString(1), isomer = c.getString(2),
                            isomerDisplay = c.getString(3), saltRank = if (c.isNull(4)) null else c.getInt(4),
                            elementalFraction = if (c.isNull(5)) null else c.getDouble(5),
                            unit = c.getString(6).ifBlank { "mg" },
                            doseRange = DoseRange(
                                unit = c.getString(6).ifBlank { "mg" },
                                threshold = if (c.isNull(7)) null else c.getDouble(7),
                                light = rangeFrom(c, 9, 10), common = rangeFrom(c, 11, 12), strong = rangeFrom(c, 13, 14),
                                heavy = if (c.isNull(15)) null else c.getDouble(15),
                                saltForm = c.getString(1), saltRank = if (c.isNull(4)) null else c.getInt(4),
                                elementalFraction = if (c.isNull(5)) null else c.getDouble(5),
                                isomer = c.getString(2), isomerDisplayName = c.getString(3), doseContext = c.getString(8),
                            ),
                        )
                    )
                }
            }
        }
        // durations per (route,salt,isomer,phase) independent top-1; key keeps the RAW route string
        val dursSql = """
            SELECT route, salt_form, isomer, phase, min_minutes, max_minutes
              FROM (
                SELECT du.route, du.salt_form, du.isomer, du.phase, du.min_minutes, du.max_minutes,
                       ROW_NUMBER() OVER (PARTITION BY du.route, du.phase, du.salt_form, du.isomer
                           ORDER BY ${fieldRank("durations", "du")}) AS rn
                  FROM durations du JOIN sources src ON src.id = du.source_id
                  ${fieldJoin("durations", "du")}
                 WHERE du.substance_id = ? AND src.slug IN ($enabledSourceList)
              ) WHERE rn = 1
        """.trimIndent()
        val durMap = HashMap<String, MutableMap<String, DurationRange>>()
        db.rawQuery(dursSql, arrayOf(sid.toString())).use { c ->
            while (c.moveToNext()) {
                val key = formKey(c.getString(0), c.getString(1), c.getString(2))
                durMap.getOrPut(key) { HashMap() }[c.getString(3)] =
                    DurationRange(c.getDouble(4), c.getDouble(5))
            }
        }
        // fold into routes: group variants per route (iOS makeRoute)
        val byRoute = LinkedHashMap<RouteOfAdministration, MutableList<DoseRow>>()
        for (r in rows) {
            val route = RouteOfAdministration.fromString(r.route)
            byRoute.getOrPut(route) { ArrayList() }.add(r)
        }
        // duration-only routes (raw route string keys) appended too
        for (key in durMap.keys) {
            val raw = key.split("|").first()
            val route = RouteOfAdministration.fromString(raw)
            if (!byRoute.containsKey(route)) byRoute.getOrPut(route) { ArrayList() }.add(DoseRow(raw, null, null, null, null, null, "mg", null))
        }
        return byRoute.entries.sortedBy { RouteOfAdministration.rank(it.key) }.mapNotNull { (route, variantRows) ->
            // keep: isomer families all variants; salt-only keeps salt != null; else base only
            val variants = variantRows.filter { it.doseRange != null }
                .sortedWith(compareBy({ it.isomer != null }, { it.saltRank ?: Int.MAX_VALUE }, { it.saltForm ?: "" }, { it.isomer ?: "" }))
            val primary = (variants.firstOrNull { it.isomer == null && it.saltForm == null } ?: variants.firstOrNull())?.doseRange
            // durations: keyed by raw route string (may be an alias like "intranasal")
            val rawStrings = (variantRows.map { it.route } + (if (route == RouteOfAdministration.Oral) listOf("oral") else emptyList())).distinct()
            val durationKeys = rawStrings.map { formKey(it, null, null) }
            val duration = durationKeys.firstNotNullOfOrNull { durMap[it]?.let { p -> durationFrom(p) } }
            val variantDurations = HashMap<String, DurationProfile>()
            for (raw in rawStrings) {
                for ((key, phases) in durMap) {
                    val parts = key.split("|")
                    if (parts[0] == raw && (parts[1] != "null" || parts[2] != "null")) {
                        durationFrom(phases)?.let { variantDurations[saltIsomerKey(parts.getOrNull(1), parts.getOrNull(2))] = it }
                    }
                }
            }
            val unit = primary?.unit ?: variants.firstOrNull()?.doseRange?.unit ?: "mg"
            if (primary == null && duration == null) null
            else RouteData(route, unit, primary ?: DoseRange(unit = unit), duration, variants.mapNotNull { it.doseRange }, variantDurations)
        }
    }

    private fun formKey(route: String, salt: String?, isomer: String?): String = "$route|$salt|$isomer"
    private fun saltIsomerKey(salt: String?, isomer: String?): String = "$salt|$isomer"

    private data class DoseRow(
        val route: String, val saltForm: String?, val isomer: String?, val isomerDisplay: String?,
        val saltRank: Int?, val elementalFraction: Double?, val unit: String, val doseRange: DoseRange?,
    )

    private fun durationFrom(phases: Map<String, DurationRange>): DurationProfile? {
        if (phases.isEmpty()) return null
        return DurationProfile(
            onset = phases["onset"], comeup = phases["comeup"], peak = phases["peak"],
            offset = phases["offset"], afterglow = phases["afterglow"], total = phases["total"],
        )
    }

    private fun rangeFrom(c: android.database.Cursor, lo: Int, hi: Int): DurationRange? {
        if (c.isNull(lo) || c.isNull(hi)) return null
        val a = c.getDouble(lo); val b = c.getDouble(hi)
        return if (a <= b) DurationRange(a, b) else null
    }

    fun halfLifeMinutes(sid: Long): Double? {
        val sql = "SELECT h.half_life_minutes FROM half_lives h JOIN sources src ON src.id = h.source_id" +
            " WHERE h.substance_id = ? AND src.slug IN ($enabledSourceList) ORDER BY $priorityCaseSQL ASC LIMIT 1"
        return try {
            db.rawQuery(sql, arrayOf(sid.toString())).use { if (it.moveToFirst()) it.getDouble(0).takeIf { d -> d > 0 } else null }
        } catch (e: SQLiteException) { null }
    }

    private fun categoryFor(sid: Long): SubstanceCategory {
        val sql = "SELECT c.category FROM categories c JOIN sources src ON src.id = c.source_id" +
            " WHERE c.substance_id = ? AND src.slug IN ($enabledSourceList)" +
            " ORDER BY ((c.category = 'Other' OR c.category IS NULL) AND src.slug != 'piru-curated') ASC," +
            " (c.category IS NULL) ASC, $priorityCaseSQL ASC LIMIT 1"
        val raw = try { db.rawQuery(sql, arrayOf(sid.toString())).use { if (it.moveToFirst()) it.getString(0) else null } } catch (e: SQLiteException) { null }
        return SubstanceCategory.fromRaw(raw) ?: SubstanceCategory.Other
    }

    private fun tagsFor(sid: Long): List<String> = try {
        db.rawQuery("SELECT DISTINCT t.tag FROM tags t WHERE t.substance_id = ? AND COALESCE(t.hidden,0)=0", arrayOf(sid.toString())).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
    } catch (e: SQLiteException) { emptyList() }

    private fun prose(sid: Long): Pair<String?, String?> {
        // descriptions: text/language/source_id - strict (enabled sources) then fail-open retry.
        val desc = resolveProse(sid, "descriptions", "text", "descriptions", lang = true)
        // mechanisms_summary: summary column, no field-priority rows yet; source-priority + language.
        val mech = resolveProse(sid, "mechanisms_summary", "summary", null, lang = true)
        return desc to mech
    }

    /** Prose resolvers fail open: filtered query, then the same query without the source filter. */
    private fun resolveProse(sid: Long, table: String, textCol: String, fieldPriority: String?, lang: Boolean): String? {
        val langFilter = if (lang) " AND t.language IN ('en','und')" else ""
        val join = fieldPriority?.let { fieldJoin(it, "t") } ?: ""
        val rank = if (fieldPriority != null) fieldRank(fieldPriority, "t") else priorityCaseSQL
        val strict = "SELECT t.$textCol FROM $table t JOIN sources src ON src.id = t.source_id $join" +
            " WHERE t.substance_id = ? AND src.slug IN ($enabledSourceList)$langFilter ORDER BY $rank ASC LIMIT 1"
        try {
            db.rawQuery(strict, arrayOf(sid.toString())).use { if (it.moveToFirst()) return it.getString(0) }
        } catch (e: SQLiteException) { /* fall through to relaxed */ }
        // relaxed: drop the source filter - but priorityCaseSQL references src.slug, so in
        // the relaxed query the join must survive with the alias.
        val relaxed = "SELECT t.$textCol FROM $table t JOIN sources src ON src.id = t.source_id $join" +
            " WHERE t.substance_id = ?$langFilter ORDER BY $rank ASC LIMIT 1"
        return try {
            db.rawQuery(relaxed, arrayOf(sid.toString())).use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (e: SQLiteException) { null }
    }

    private fun pkParams(sid: Long, c: CoreRow): PKParams? {
        val sql = "SELECT route, bioavailability_pct, tmax_min, half_life_min, vd_l_per_kg," +
            " protein_binding_pct FROM pk_routes WHERE substance_id = ?"
        data class Row6(
            val route: String?, val f: Double?, val tmax: Double?, val hl: Double?, val vd: Double?, val pb: Double?,
        )
        return try {
            db.rawQuery(sql, arrayOf(sid.toString())).use { cur ->
                val rows = ArrayList<Row6>()
                while (cur.moveToNext()) {
                    rows.add(
                        Row6(
                            cur.getString(0),
                            if (cur.isNull(1)) null else cur.getDouble(1),
                            if (cur.isNull(2)) null else cur.getDouble(2),
                            if (cur.isNull(3)) null else cur.getDouble(3),
                            if (cur.isNull(4)) null else cur.getDouble(4),
                            if (cur.isNull(5)) null else cur.getDouble(5),
                        )
                    )
                }
                val primary = rows.firstOrNull {
                    it.route?.let { r -> RouteOfAdministration.fromString(r) } == RouteOfAdministration.Oral
                } ?: rows.firstOrNull() ?: return@use null
                PKParams(
                    halfLifeMin = primary.hl?.takeIf { it > 0 },
                    bioavailabilityFraction = primary.f?.let { it / 100.0 },
                    vdLPerKg = primary.vd?.takeIf { it > 0 },
                    molarMass = c.molecularWeight,
                    proteinBindingPct = primary.pb,
                    tmaxMin = primary.tmax?.takeIf { it > 0 },
                    clearance = null,
                )
            }
        } catch (e: SQLiteException) { null }
    }

    private fun zeroOrderRow(sid: Long): Triple<Double, Double, Double>? {
        val sql = "SELECT z.vmax_mg_per_min, z.vmax_reference_weight_kg, z.ka_per_min FROM (" +
            " SELECT zo.*, ROW_NUMBER() OVER (PARTITION BY zo.substance_id ORDER BY $priorityCaseSQL ASC) AS rn" +
            " FROM zero_order_kinetics zo JOIN sources src ON src.id = zo.source_id" +
            " WHERE zo.substance_id = ? AND src.slug IN ($enabledSourceList)) z WHERE z.rn = 1 LIMIT 1"
        return try {
            db.rawQuery(sql, arrayOf(sid.toString())).use { c ->
                if (c.moveToFirst()) {
                    val v = c.getDouble(0); val w = c.getDouble(1); val k = c.getDouble(2)
                    if (v > 0 && w > 0 && k > 0) Triple(v, w, k) else null
                } else null
            }
        } catch (e: SQLiteException) { null }
    }

    /** Zero-order kinetics resolved for a substance name at the given weight (alcohol path). */
    fun zeroOrderKineticsFor(nameOrAlias: String, weightKg: Double): PKModel.ZeroOrderKinetics? {
        val id = substanceID(nameOrAlias) ?: return null
        val row = zeroOrderRow(id) ?: return null
        val s = substanceByID(id)
        val f = s?.pk?.bioavailabilityFraction ?: 1.0
        return PKModel.zeroOrderKinetics(row.first, row.second, row.third, f, weightKg)
    }

    /** F/ka combined with bioavailability (per iOS: table carries no F). */
    private fun referenceDoseMg(routes: List<RouteData>): Double? {
        // racemic-preferred, oral-first
        val rac = routes.firstOrNull { it.route == RouteOfAdministration.Oral && it.doses.hasAny() && it.doses.isomer == null }
            ?: routes.firstOrNull { it.doses.hasAny() && it.doses.isomer == null }
        val fallback = rac ?: routes.firstOrNull { it.doses.hasAny() } ?: return null
        val r = fallback.doses
        return r.heavy ?: r.strong?.max ?: r.common?.max
    }

    // ---- source-priority SQL fragments (port of the iOS template) ----

    private fun fieldJoin(field: String, alias: String): String =
        "LEFT JOIN source_field_priority sfp ON sfp.source_id = $alias.source_id AND sfp.field = '$field'"

    private fun fieldRank(field: String, alias: String): String =
        "COALESCE(sfp.priority, $priorityCaseSQL) ASC, $priorityCaseSQL ASC"

    // ---- product strengths ----

    fun productStrengths(product: String): ProductStrengths? {
        val norm = product.trim().lowercase()
        val key = aliasIndex[norm]?.let { null } ?: norm // product_strengths keyed by product_normalized
        return db.rawQuery(
            "SELECT product, strengths_mg, form FROM product_strengths WHERE product_normalized = ? OR lower(product) = ? LIMIT 1",
            arrayOf(norm, norm)
        ).use { c ->
            if (c.moveToFirst()) ProductStrengths(
                c.getString(0),
                c.getString(1).split(",").mapNotNull { it.trim().toDoubleOrNull() },
                if (c.isNull(2)) null else c.getString(2),
            ) else null
        }
    }

    fun productStrengthsByUID(uid: String): List<ProductStrengths> {
        // brand aliases grouped via substance_uid
        val sql = "SELECT ps.product, ps.strengths_mg, ps.form FROM product_strengths ps" +
            " JOIN substances s ON s.id = ps.substance_id WHERE s.substance_uid = ?"
        return try {
            db.rawQuery(sql, arrayOf(uid)).use { c ->
                buildList {
                    while (c.moveToNext()) ProductStrengths(
                        c.getString(0), c.getString(1).split(",").mapNotNull { it.trim().toDoubleOrNull() },
                        if (c.isNull(2)) null else c.getString(2)
                    ).let { add(it) }
                }
            }
        } catch (e: SQLiteException) { emptyList() }
    }

    // ---- interactions ----

    fun drugClasses(name: String): List<String> {
        val key = name.trim().lowercase()
        // override map (per-substance, includes alias expansion)
        val override = queryOverrideClasses(key)
        if (override.isNotEmpty()) return override
        val id = substanceID(name) ?: return emptyList()
        val canonical = coreById[id]?.canonicalName?.lowercase()
        queryOverrideClasses(canonical ?: return emptyList())?.takeIf { it.isNotEmpty() }?.let { return it }
        val cat = categoryFor(id).rawValue
        val cls = db.rawQuery("SELECT drug_class FROM category_interaction_classes WHERE category = ?", arrayOf(cat)).use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "other"
        return listOf(cls)
    }

    private fun queryOverrideClasses(key: String): List<String> {
        return try {
            db.rawQuery(
                "SELECT sic.drug_class FROM substance_interaction_classes sic WHERE lower(sic.name) = ? ORDER BY sic.rank ASC",
                arrayOf(key)
            ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        } catch (e: SQLiteException) { emptyList() }
    }

    data class InteractionRule(val classA: String, val classB: String, val severity: Int, val note: String?)

    fun rules(): List<InteractionRule> = try {
        db.rawQuery("SELECT class_a, class_b, severity, note FROM interaction_rules", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(InteractionRule(c.getString(0), c.getString(1), severityFrom(c.getString(2)), c.getString(3)))
                }
            }
        }
    } catch (e: SQLiteException) { emptyList() }

    private fun severityFrom(s: String?): Int = when (s?.lowercase()) {
        "caution" -> 0; "unsafe" -> 1; "dangerous" -> 2; else -> 0
    }

    fun pkInteractionFindings(sid: Long, otherSids: Map<Long, String>): List<PKFinding> {
        val sql = "SELECT with_substance, mechanism, clinical_effect FROM drug_interactions_pk WHERE substance_id = ?"
        return try {
            db.rawQuery(sql, arrayOf(sid.toString())).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val with = c.getString(0)
                        // counterpart names split by '/' and checked against logged substances
                        val counterparts = with.split("/").map { it.trim() }.filter { it.isNotEmpty() }
                        val matched = counterparts.any { cp -> substanceID(cp)?.let { otherSids.containsKey(it) } == true }
                        if (!matched) continue
                        add(PKFinding(with, if (c.isNull(1)) null else c.getString(1), if (c.isNull(2)) null else c.getString(2)))
                    }
                }
            }
        } catch (e: SQLiteException) { emptyList() }
    }

    data class PKFinding(val withSubstance: String, val mechanism: String?, val clinicalEffect: String?)

    /** Category→class map (category_interaction_classes) for the interaction checker. */
    fun categoryInteractionClasses(): Map<String, String> = try {
        db.rawQuery("SELECT category, drug_class FROM category_interaction_classes", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
        }
    } catch (e: SQLiteException) { emptyMap() }

    /**
     * Enzyme edges among a set of names (port of MetabolicModulation.checkerEffects):
     * substance-scoped enzyme_modulators matched to substrates via metabolism rows,
     * plus context modulators (grapefruit ⇒ CYP3A4 substrates) when the user logged
     * the grapefruit flag. Severity capped at unsafe by the caller.
     */
    data class EnzymeEdge(
        val modulator: String, val modulatorID: String, val modulatorClass: String,
        val substrate: String, val substrateClass: String, val enzyme: String,
        val direction: String, val note: String,
    )

    fun enzymeEdges(names: List<String>): List<EnzymeEdge> {
        val out = ArrayList<EnzymeEdge>()
        val sids = HashMap<Long, String>() // substance id -> display
        for (n in names.distinct()) {
            val id = substanceID(n) ?: continue
            sids[id] = lookup(n)?.displayTitle ?: n
        }
        if (sids.isEmpty()) return out
        val placeholders = sids.keys.joinToString(",")
        // 1. substance modulators (in the logged set) → substrates (in the logged set)
        try {
            val sql = """
                SELECT em.modulator_id, em.substance_id, em.enzyme, em.direction, em.user_note,
                       m2.substance_id AS substrate_sid
                  FROM enzyme_modulators em
                  JOIN metabolism m2 ON m2.enzyme LIKE '%' || em.enzyme || '%'
                 WHERE em.substance_id IN ($placeholders) AND em.direction = 'inhibits'
                   AND m2.substance_id IN ($placeholders)
                   AND m2.substance_id != em.substance_id
                   AND m2.fraction_of_clearance_pct >= 20
            """.trimIndent()
            val args = (sids.keys.map { it.toString() } + sids.keys.map { it.toString() }).toTypedArray()
            db.rawQuery(sql, args).use { c ->
                while (c.moveToNext()) {
                    val modSid = c.getLong(1)
                    val subSid = c.getLong(5)
                    if (subSid !in sids) continue
                    val modClass = drugClasses(sids[modSid]!!).firstOrNull { it != "other" } ?: continue
                    val subClass = drugClasses(sids[subSid]!!).firstOrNull { it != "other" } ?: continue
                    out.add(
                        EnzymeEdge(
                            sids[modSid]!!, c.getString(0), modClass, sids[subSid]!!, subClass,
                            c.getString(2), c.getString(3), c.getString(4) ?: "",
                        )
                    )
                }
            }
        } catch (e: SQLiteException) { /* enzyme layer is additive */ }
        return out
    }

    /** Context modulators (e.g. grapefruit: substance_id IS NULL) → substrates among [names]. */
    fun contextEnzymeEdges(names: List<String>, contextId: String): List<EnzymeEdge> {
        val out = ArrayList<EnzymeEdge>()
        val sids = HashMap<Long, String>()
        for (n in names.distinct()) {
            val id = substanceID(n) ?: continue
            sids[id] = lookup(n)?.displayTitle ?: n
        }
        if (sids.isEmpty()) return out
        val placeholders = sids.keys.joinToString(",")
        try {
            val sql = """
                SELECT em.enzyme, em.direction, em.user_note, m2.substance_id AS substrate_sid
                  FROM enzyme_modulators em
                  JOIN metabolism m2 ON m2.enzyme LIKE '%' || em.enzyme || '%'
                 WHERE em.modulator_id = ? AND em.substance_id IS NULL AND em.direction = 'inhibits'
                   AND m2.substance_id IN ($placeholders)
                   AND m2.fraction_of_clearance_pct >= 20
            """.trimIndent()
            db.rawQuery(sql, (listOf(contextId) + sids.keys.map { it.toString() }).toTypedArray()).use { c ->
                while (c.moveToNext()) {
                    val subSid = c.getLong(3)
                    val subClass = drugClasses(sids[subSid]!!).firstOrNull { it != "other" } ?: continue
                    out.add(
                        EnzymeEdge(
                            c.getString(0).ifBlank { contextId }, contextId, contextId,
                            sids[subSid]!!, subClass, c.getString(0), c.getString(1), c.getString(2) ?: "",
                        )
                    )
                }
            }
        } catch (e: SQLiteException) { }
        return out
    }

    fun citationsFor(sid: Long): List<Citation> = try {
        db.rawQuery(
            "SELECT DISTINCT c.title, c.url, c.doi, c.pmid FROM citations c" +
                " WHERE c.id IN (SELECT citation_id FROM substance_citations WHERE substance_id = ?" +
                " UNION SELECT citation_id FROM dose_ranges WHERE substance_id = ? AND citation_id IS NOT NULL" +
                " UNION SELECT citation_id FROM durations WHERE substance_id = ? AND citation_id IS NOT NULL" +
                " UNION SELECT citation_id FROM half_lives WHERE substance_id = ? AND citation_id IS NOT NULL)" +
                " ORDER BY c.title LIMIT 60",
            arrayOf(sid.toString(), sid.toString(), sid.toString(), sid.toString())
        ).use { c -> buildList { while (c.moveToNext()) add(Citation(c.getString(0), c.getString(1), c.getString(2), c.getString(3))) } }
    } catch (e: SQLiteException) { emptyList() }

    data class Citation(val title: String?, val url: String?, val doi: String?, val pmid: String?)

    /** Tolerance info for detail display (build_rate / reset / half-life, editorial only). */
    fun toleranceInfo(sid: Long): ToleranceInfo? = try {
        val sql = "SELECT half_life_days, full_reset_days, build_rate FROM tolerance t JOIN sources src ON src.id = t.source_id" +
            " WHERE t.substance_id = ? AND src.slug IN ($enabledSourceList) ORDER BY $priorityCaseSQL ASC LIMIT 1"
        db.rawQuery(sql, arrayOf(sid.toString())).use { c ->
            if (c.moveToFirst()) ToleranceInfo(c.getDouble(0), c.getDouble(1), c.getString(2)) else null
        }
    } catch (e: SQLiteException) { null }

    data class ToleranceInfo(val halfLifeDays: Double, val fullResetDays: Double, val buildRate: String?)

    /** Contraindications + boxed warnings for substance detail. */
    fun contraindications(sid: Long): List<Contra> = try {
        val sql = """
            SELECT c.text, c.flag, MAX(c.is_boxed_warning) FROM contraindications c
             WHERE c.substance_id = ? GROUP BY COALESCE(c.flag, c.text)
             ORDER BY MAX(c.is_boxed_warning) DESC, c.text LIMIT 20
        """.trimIndent()
        db.rawQuery(sql, arrayOf(sid.toString())).use { c ->
            buildList { while (c.moveToNext()) add(Contra(c.getString(0), c.getString(1), c.getInt(2) == 1)) }
        }
    } catch (e: SQLiteException) { emptyList() }

    data class Contra(val text: String?, val flag: String?, val boxed: Boolean)

    /** Class targets (bindings) for the tolerance engine: list of (target, action, ki_nm/ec50_nm/ic50_nm). */
    data class TargetBinding(val target: String, val action: String?, val halfMaxNanomolar: Double)

    fun targetsFor(sid: Long): List<TargetBinding> {
        return try {
            db.rawQuery(
                "SELECT target, action, ki_nm, ec50_nm, ic50_nm FROM bindings WHERE substance_id = ?",
                arrayOf(sid.toString())
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val ki = if (c.isNull(2)) null else c.getDouble(2)
                        val ec = if (c.isNull(3)) null else c.getDouble(3)
                        val ic = if (c.isNull(4)) null else c.getDouble(4)
                        val halfMax = ki ?: ec ?: ic ?: continue
                        if (halfMax > 0) add(TargetBinding(c.getString(0), if (c.isNull(1)) null else c.getString(1), halfMax))
                    }
                }
            }
        } catch (e: SQLiteException) { emptyList() }
    }

    fun classRepresentatives(): List<String> = try {
        db.rawQuery("SELECT DISTINCT substance_id FROM class_representatives", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
    } catch (e: SQLiteException) { emptyList() }

    /** Injectable-ester identity: does (saltForm, parentUID) match a non-base forms row? */
    fun isEster(saltForm: String?, parentUID: String?): Boolean {
        if (saltForm.isNullOrBlank() || parentUID.isNullOrBlank()) return false
        val s = saltForm.lowercase()
        return try {
            db.rawQuery(
                "SELECT 1 FROM substance_forms WHERE substance_uid = ? AND salt != '0'" +
                    " AND (lower(salt) = ? OR lower(display_name) LIKE ?) LIMIT 1",
                arrayOf(parentUID, s, "%" + s + "%")
            ).use { it.moveToFirst() }
        } catch (e: SQLiteException) { false }
    }

    fun intrinsicEfficacy(sid: Long): Double? = try {
        db.rawQuery("SELECT efficacy FROM intrinsic_efficacy WHERE substance_id = ? LIMIT 1", arrayOf(sid.toString()))
            .use { if (it.moveToFirst()) it.getDouble(0) else null }
    } catch (e: SQLiteException) { null }
}

/** Late-bound holder so engine code can reach the store without a Context. */
object SubstanceStoreHolder {
    private var _store: SubstanceStore? = null
    val store: SubstanceStore
        get() = _store ?: error("SubstanceStore not initialized")
    val ready: Boolean get() = _store != null
    fun set(store: SubstanceStore) { _store = store }
}
