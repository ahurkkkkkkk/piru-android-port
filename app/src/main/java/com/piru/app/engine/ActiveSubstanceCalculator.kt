package com.piru.app.engine

import com.piru.app.data.DoseEntry
import com.piru.app.data.SubstanceStoreHolder
import com.piru.app.models.DoseUnit
import com.piru.app.models.PKModel
import com.piru.app.models.SubstanceCategory

/**
 * "Still in your body right now" readout - port of
 * Piru/Utilities/ActiveSubstanceCalculator.compute.
 */
object ActiveSubstanceCalculator {

    data class DoseInfo(val amount: Double, val remaining: Double, val timestamp: Long)

    data class ActiveSubstance(
        val name: String,
        val unit: String,
        val colorHex: String,
        val halfLifeMinutes: Double,
        val totalDosed: Double,
        val totalRemaining: Double,
        val doses: List<DoseInfo>,
    ) {
        val id get() = "$name|$unit"
        val eliminatedFraction get() = if (totalDosed > 0) 1 - totalRemaining / totalDosed else 0.0
    }

    fun compute(entries: List<DoseEntry>, colorFor: (String) -> String, nowMillis: Long): List<ActiveSubstance> {
        if (!SubstanceStoreHolder.ready) return emptyList()
        val store = SubstanceStoreHolder.store
        data class Group(
            val name: String, val unit: String, val halfLife: Double,
            val doses: MutableList<DoseInfo>, var totalDosed: Double, var totalRemaining: Double,
        )
        val grouped = LinkedHashMap<String, Group>()
        fun unitFamily(u: String): String = if (DoseUnit.convert(1.0, u, "mg") != null) "mass" else u

        for (entry in entries) {
            val substance = store.lookup(entry.substance)
            if (substance?.category == SubstanceCategory.Supplement) continue
            val depot = entry.isDepot()
            if (!depot && entry.namesUnmodeledForm()) continue
            val halfLife = PKResolver.halfLifeMinutes(substance) ?: (if (depot) 21.0 * 24 * 60 else null) ?: continue
            val elapsed = (nowMillis - entry.timestamp) / 60_000.0
            if (elapsed < 0) continue
            val duration = if (depot) null else substance?.timelineDurationFor(entry)
            val (ke, ka) = PKResolver.rateConstants(halfLife, duration)
            val remaining = entry.amount * PKModel.fractionRemainingInBody(elapsed, ke, ka)
            val fraction = if (entry.amount > 0) remaining / entry.amount else 0.0
            if (fraction <= 0.03) continue
            val name = substance?.displayTitle ?: entry.substance
            val key = "$name|${unitFamily(entry.unit)}"
            val existing = grouped[key]
            if (existing != null) {
                val amount = DoseUnit.convert(entry.amount, entry.unit, existing.unit) ?: entry.amount
                val converted = DoseUnit.convert(remaining, entry.unit, existing.unit) ?: remaining
                existing.doses.add(DoseInfo(amount, converted, entry.timestamp))
                existing.totalDosed += amount
                existing.totalRemaining += converted
            } else {
                grouped[key] = Group(name, entry.unit, halfLife,
                    mutableListOf(DoseInfo(entry.amount, remaining, entry.timestamp)),
                    entry.amount, remaining)
            }
        }
        return grouped.values.map { g ->
            ActiveSubstance(
                name = g.name, unit = g.unit, colorHex = colorFor(g.name),
                halfLifeMinutes = g.halfLife, totalDosed = g.totalDosed,
                totalRemaining = g.totalRemaining, doses = g.doses.sortedByDescending { it.timestamp },
            )
        }.sortedBy { it.eliminatedFraction }
    }
}

/** Session clustering - port of the iOS SessionClustering grouping window. */
object SessionClustering {
    /** Gap threshold (minutes) beyond which doses form a new session. */
    const val SESSION_GAP_MINUTES = 60 * 8.0

    data class Cluster(val id: String, val startMillis: Long, val endMillis: Long, val entries: List<DoseEntry>)

    fun cluster(entries: List<DoseEntry>): List<Cluster> {
        val sorted = entries.sortedBy { it.timestamp }
        val clusters = ArrayList<Cluster>()
        var current = ArrayList<DoseEntry>()
        for (e in sorted) {
            if (current.isNotEmpty()) {
                val last = current.last()
                if ((e.timestamp - last.timestamp) / 60_000.0 > SESSION_GAP_MINUTES) {
                    clusters.add(clusterOf(current)); current = ArrayList()
                }
            }
            current.add(e)
        }
        if (current.isNotEmpty()) clusters.add(clusterOf(current))
        return clusters
    }

    private fun clusterOf(list: List<DoseEntry>) = Cluster(
        id = list.first().id, startMillis = list.first().timestamp,
        endMillis = list.last().timestamp, entries = list,
    )

    /** A cluster is "active" while any dose still has body presence. */
    fun isActive(cluster: Cluster, active: List<ActiveSubstanceCalculator.ActiveSubstance>): Boolean {
        if (System.currentTimeMillis() - cluster.endMillis < 3_600_000) return true
        val names = cluster.entries.map { it.substance.lowercase() }.toSet()
        return active.any { it.name.lowercase() in names && it.eliminatedFraction < 0.97 }
    }
}
