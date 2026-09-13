package com.piru.app.engine

import com.piru.app.data.DoseEntry
import com.piru.app.data.SubstanceStoreHolder
import kotlin.math.max
import kotlin.math.min

/**
 * Polydrug interaction checking — port of Piru/Data/Services/Interactions.swift
 * (class-pair rules + enzyme layer + relevance gating).
 */
object Interactions {

    enum class Severity(val ordinalRank: Int) { CAUTION(0), UNSAFE(1), DANGEROUS(2) }

    data class Result(
        val severity: Severity,
        val substanceA: String,
        val substanceB: String,
        val description: String,
        val ruleKey: String,
        val overlapFactor: Double,
        val doseFactor: Double,
    ) {
        val relevance: Double get() = overlapFactor * doseFactor
        val displayScore: Double get() = (severity.ordinalRank + 1) * relevance
        val isLowRelevance: Boolean get() = relevance < LOW_RELEVANCE_THRESHOLD
    }

    private val PERSISTENT_CLASSES = setOf("maoi", "lithium", "ssri", "snri", "tca")
    private const val LOW_RELEVANCE_THRESHOLD = 0.25
    private const val OVERLAP_SUPPRESS_THRESHOLD = 0.05
    private const val TRIVIAL_MAGNITUDE = 0.05

    data class Rule(val a: String, val b: String, val severity: Severity, val note: String?)

    private class RuleStore {
        val rules = HashMap<String, Rule>() // sorted "a|b"
        val substanceOverride = HashMap<String, List<String>>()
        val categoryOverride = HashMap<String, String>()
        var loaded = false
    }

    private val store = RuleStore()

    private fun loadIfNeeded() {
        if (store.loaded || !SubstanceStoreHolder.ready) return
        synchronized(store) {
            if (store.loaded) return
            val s = SubstanceStoreHolder.store
            for (r in s.rules()) {
                val key = ruleKey(r.classA, r.classB)
                val sev = when (r.severity) { 1 -> Severity.UNSAFE; 2 -> Severity.DANGEROUS; else -> Severity.CAUTION }
                store.rules[key] = Rule(r.classA, r.classB, sev, r.note)
            }
            for ((cat, cls) in s.categoryInteractionClasses()) store.categoryOverride[cat.lowercase()] = cls
            store.loaded = true
        }
    }

    private fun ruleKey(a: String, b: String) = listOf(a.lowercase(), b.lowercase()).sorted().joinToString("|")

    /**
     * Evaluate warnings for a (possibly prospective) substance against active entries.
     * `check(substance:activeEntries:)` port; warnings sorted by displayScore desc.
     */
    fun check(
        substance: String,
        activeEntries: List<DoseEntry>,
        prospective: ActiveSubstanceState? = null,
    ): List<Result> {
        loadIfNeeded()
        if (!SubstanceStoreHolder.ready) return emptyList()
        val s = SubstanceStoreHolder.store
        val newClasses = s.drugClasses(substance)
        if (newClasses.isEmpty()) return emptyList()
        val byPair = HashMap<String, Result>()

        for (entry in activeEntries) {
            if (entry.substance.equals(substance, ignoreCase = true)) continue
            val otherClasses = s.drugClasses(entry.substance)
            if (otherClasses.isEmpty()) continue
            val worst = worstRule(newClasses, otherClasses) ?: continue
            val activeState = activeStateFor(entry)
            val gate = gatePair(prospective, activeState, worst.second, newClasses + otherClasses)
            if (gate.suppress) continue
            val result = Result(
                worst.second.severity, substance, entry.substance, worst.second.note ?: "",
                ruleKey(worst.second.a, worst.second.b), gate.overlap, gate.dose,
            )
            val key = ruleKey(worst.second.a, worst.second.b)
            val existing = byPair[key]
            if (existing == null || result.displayScore > existing.displayScore) byPair[key] = result
        }

        // Enzyme layer (metabolic modulation): capped at UNSAFE, never downgrades DANGEROUS.
        for (edge in s.enzymeEdges((listOf(substance) + activeEntries.map { it.substance }))) {
            val key = "enzyme:" + ruleKey(edge.modulatorClass, edge.substrateClass)
            val existing = byPair[ruleKey(edge.modulatorClass, edge.substrateClass)]
            if (existing != null && existing.severity.ordinalRank > Severity.UNSAFE.ordinalRank) continue
            byPair[key] = Result(
                Severity.UNSAFE, edge.modulator, edge.substrate,
                edge.note, "enzyme:${edge.modulatorID}|${edge.enzyme}",
                1.0, 1.0,
            )
        }
        return byPair.values.sortedByDescending { it.displayScore }
    }

    private fun worstRule(classesA: List<String>, classesB: List<String>): Pair<List<String>, Rule>? {
        var best: Rule? = null
        var bestClasses: List<String>? = null
        for (a in classesA) for (b in classesB) {
            val r = store.rules[ruleKey(a, b)] ?: continue
            if (best == null || r.severity.ordinalRank > best!!.severity.ordinalRank) { best = r; bestClasses = listOf(a, b) }
        }
        best ?: return null
        return bestClasses!! to best!!
    }

    private class Gate(val suppress: Boolean, val overlap: Double, val dose: Double)

    private fun gatePair(
        prospective: ActiveSubstanceState?, active: ActiveSubstanceState?,
        rule: Rule, allClasses: List<String>,
    ): Gate {
        val persistent = allClasses.any { PERSISTENT_CLASSES.contains(it.lowercase()) } ||
            rule.a.lowercase() in PERSISTENT_CLASSES || rule.b.lowercase() in PERSISTENT_CLASSES
        if (persistent) return Gate(false, 1.0, 1.0)
        val doseFactor = presenceOr1(prospective) * presenceOr1(active)
        val doseConfidentLow = (prospective != null && prospective.doseMagnitude <= TRIVIAL_MAGNITUDE) ||
            (active != null && active.doseMagnitude <= TRIVIAL_MAGNITUDE)
        var overlap = 1.0
        if (prospective != null && active != null) {
            overlap = effectOverlap(prospective, active)
        }
        val overlapConfidentLow = overlap < OVERLAP_SUPPRESS_THRESHOLD
        return Gate(overlapConfidentLow || doseConfidentLow, overlap, doseFactor)
    }

    private fun presenceOr1(s: ActiveSubstanceState?): Double {
        s ?: return 1.0
        val t = min(1.0, max(0.0, (s.doseMagnitude - 0.04) / (0.25 - 0.04)))
        return t * t * (3 - 2 * t)
    }

    /** Sampled overlap of the two curves' effect shapes (64 samples). */
    private fun effectOverlap(a: ActiveSubstanceState, b: ActiveSubstanceState): Double {
        val later = max(a.doseTimestamp, b.doseTimestamp)
        val endA = a.doseTimestamp + max(a.offsetEndMinutes, a.totalMinutes) * 60_000
        val endB = b.doseTimestamp + max(b.offsetEndMinutes, b.totalMinutes) * 60_000
        val windowEnd = min(endA, endB)
        if (windowEnd <= later) return 0.0
        val span = windowEnd - later
        var peak = 0.0
        for (i in 0..64) {
            val t = later + span * i / 64
            val sa = TimelineCurveModel.effectShape((t - a.doseTimestamp) / 60_000.0, a)
            val sb = TimelineCurveModel.effectShape((t - b.doseTimestamp) / 60_000.0, b)
            peak = max(peak, sa * sb)
        }
        return peak
    }

    private fun activeStateFor(entry: DoseEntry): ActiveSubstanceState? {
        if (!SubstanceStoreHolder.ready) return null
        val substance = SubstanceStoreHolder.store.lookup(entry.substance) ?: return null
        return ActiveSubstanceState.from(entry, substance, "888888", 60.0)
    }

    /** "Active entries" window (iOS activeEntries(from:)): unknown 24h, else duration/half-life. */
    fun activeEntries(entries: List<DoseEntry>, nowMillis: Long): List<DoseEntry> {
        val store2 = if (SubstanceStoreHolder.ready) SubstanceStoreHolder.store else return entries.filter {
            nowMillis - it.timestamp < 86_400_000
        }
        return entries.filter { e ->
            val window = store2.lookup(e.substance)?.let { s ->
                val minutes = s.timelineDurationFor(e)?.estimatedTotalMinutes
                    ?: s.halfLifeMinutes?.let { hl -> hl * 5 }
                    ?: 1440.0
                minutes * 60_000
            } ?: 86_400_000.0
            nowMillis - e.timestamp <= window
        }
    }
}
