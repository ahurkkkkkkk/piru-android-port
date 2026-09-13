package com.piru.app.engine

import com.piru.app.data.DoseEntry
import com.piru.app.data.Substance
import com.piru.app.models.DurationProfile
import com.piru.app.models.PKModel
import com.piru.app.models.RouteOfAdministration
import kotlin.math.ln

/**
 * Resolves the one-compartment oral PK parameters a dose needs - half-life and
 * (ke, ka) - from a substance model and an acute duration profile.
 * Port of Piru/Data/Pharmacology/PKResolver.swift.
 */
object PKResolver {
    data class Params(val halfLifeMinutes: Double, val ke: Double, val ka: Double)

    fun halfLifeMinutes(substance: Substance?): Double? {
        val hl = substance?.halfLifeMinutes ?: return null
        return if (hl > 0) hl else null
    }

    /** Depot terminal half-life (minutes) for a depot dose, else null. */
    fun depotHalfLifeMinutes(entry: DoseEntry): Double? {
        if (!entry.isDepot()) return null
        return 21.0 * 24 * 60 // defaultDepotHalfLifeDays
    }

    fun rateConstants(halfLifeMinutes: Double, duration: DurationProfile?): Pair<Double, Double> {
        val ke = PKModel.keFromHalfLifeMinutes(halfLifeMinutes)
        duration ?: return ke to PKModel.defaultKa(ke)
        val timeToPeak = (duration.onset?.midpoint ?: 0.0) + (duration.comeup?.midpoint ?: 0.0)
        val ka = if (timeToPeak > 0) PKModel.estimateKa(timeToPeak, ke) else PKModel.defaultKa(ke)
        return ke to ka
    }

    fun params(substance: Substance?, entryName: String, duration: DurationProfile?): Params? {
        val halfLife = halfLifeMinutes(substance) ?: return null
        val (ke, ka) = rateConstants(halfLife, duration)
        return Params(halfLife, ke, ka)
    }

    fun params(substance: Substance?, entryName: String, route: RouteOfAdministration): Params? =
        params(substance, entryName, substance?.durationFor(route))
}

/** Port of the body-load trail math (`BodyLevelsManager.sample` + series assembly). */
object BodyLevelsCalculator {

    data class BodyLoadDose(
        val seriesIndex: Int,
        val amount: Double,
        val timestampMillis: Long,
        val ke: Double,
        val ka: Double,
    )

    data class Series(
        val id: Int,
        val displayName: String,
        val colorHex: String,
        val unit: String,
        val peak: Double,
        val values: List<Double>, // one per sample
    )

    data class Trail(val dates: List<Long>, val series: List<Series>) {
        val isEmpty: Boolean get() = series.isEmpty() || dates.isEmpty()
        companion object { val EMPTY = Trail(emptyList(), emptyList()) }
    }

    /**
     * Build the trail: one series per (name|unit-family), sampled on an ascending
     * grid; per-sample value = Σ dose.amount·fractionRemainingInBody.
     */
    fun trailFor(
        entries: List<DoseEntry>,
        colorHexFor: (String) -> String,
        weightKg: Double,
        fromMillis: Long,
        toMillis: Long,
        sampleCount: Int,
    ): Trail {
        val store = if (com.piru.app.data.SubstanceStoreHolder.ready) com.piru.app.data.SubstanceStoreHolder.store
        else return Trail.EMPTY
        val dates = ArrayList<Long>(sampleCount)
        for (i in 0 until sampleCount) {
            dates.add(fromMillis + ((toMillis - fromMillis) * i / (sampleCount - 1)))
        }
        // unit family: mass→"mass", else the unit itself (iOS unitFamily)
        fun unitFamily(u: String): String =
            if (com.piru.app.models.DoseUnit.convert(1.0, u, "mg") != null) "mass" else u

        // Group by (name, family); the established unit is whichever arrived first.
        val groupOrder = ArrayList<String>()
        val groups = HashMap<String, MutableList<DoseEntry>>()
        val groupUnit = HashMap<String, String>()
        val halfLifeCache = HashMap<Long, Double>() // substanceId → hl (name resolved once each)
        for (e in entries.sortedBy { it.timestamp }) {
            val substance = store.lookup(e.substance)
            if (substance?.category == com.piru.app.models.SubstanceCategory.Supplement) continue
            if (e.isDepot()) continue
            if (e.namesUnmodeledForm()) continue
            val key = "${e.substance.lowercase()}|${unitFamily(e.unit)}"
            val bucket = groups[key]
            if (bucket == null) { groups[key] = arrayListOf(e); groupUnit[key] = e.unit; groupOrder.add(key) }
            else {
                // only add if amount convertible into the established unit (same family ⇒ safe)
                bucket.add(e)
            }
        }
        if (groupOrder.isEmpty()) return Trail.EMPTY

        val doses = ArrayList<BodyLoadDose>()
        for ((idx, key) in groupOrder.withIndex()) {
            val bucket = groups[key]!!
            for (e in bucket) {
                val substance = store.lookup(e.substance)
                val duration = substance?.durationFor(e.route)
                val (ke, ka) = PKResolver.rateConstants(
                    PKResolver.halfLifeMinutes(substance) ?: continue, duration
                )
                val amount = com.piru.app.models.DoseUnit.convert(e.amount, e.unit, groupUnit[key]!!) ?: e.amount
                doses.add(BodyLoadDose(idx, amount, e.timestamp, ke, ka))
            }
        }

        val perSeries = Array(groupOrder.size) { DoubleArray(sampleCount) }
        for (dose in doses) {
            val start = lowerBound(dates, dose.timestampMillis)
            if (start >= dates.size) continue
            for (i in start until dates.size) {
                val elapsed = (dates[i] - dose.timestampMillis) / 60_000.0
                perSeries[dose.seriesIndex][i] += dose.amount * PKModel.fractionRemainingInBody(elapsed, dose.ke, dose.ka)
            }
        }
        val series = ArrayList<Series>()
        for ((idx, key) in groupOrder.withIndex()) {
            val firstEntry = groups[key]!!.first()
            val name = store.lookup(firstEntry.substance)?.displayTitle ?: firstEntry.substance
            val vals = perSeries[idx].toList()
            val peak = vals.maxOrNull() ?: 0.0
            if (peak <= 0) continue
            series.add(Series(idx, name, colorHexFor(firstEntry.substance), groupUnit[key]!!, peak, vals))
        }
        return Trail(dates, series.sortedByDescending { it.peak })
    }

    private fun lowerBound(dates: List<Long>, target: Long): Int {
        var lo = 0; var hi = dates.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (dates[mid] < target) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
