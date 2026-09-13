package com.piru.app.engine

import com.piru.app.data.DoseEntry
import com.piru.app.data.Substance
import com.piru.app.models.DurationProfile
import com.piru.app.models.PKModel
import com.piru.app.models.RouteOfAdministration
import com.piru.app.models.SubstanceCategory

/**
 * Per-dose timeline state - port of `ActiveSubstanceState`. Carries everything the
 * curve renderer needs after PK/duration resolution so renderers don't touch the DB.
 */
data class ActiveSubstanceState(
    val substanceName: String,
    val colorHex: String,
    val doseTimestamp: Long, // epoch millis
    val amount: Double,
    val unit: String,
    val route: String, // display name
    val onsetEndMinutes: Double,
    val comeupEndMinutes: Double,
    val peakEndMinutes: Double,
    val offsetEndMinutes: Double,
    val afterglowEndMinutes: Double?,
    val totalMinutes: Double,
    val doseIntensity: Double = 1.0,
    val doseMagnitude: Double = 1.0,
    val heavyThresholdMagnitude: Double? = null,
    val tachyphylaxis: Double = 0.0,
    val bodyWeightKg: Double = PKModel.REFERENCE_BODY_WEIGHT_KG,
    val zeroOrder: PKModel.ZeroOrderKinetics? = null,
    val comeupSpreadMinutes: Double? = null,
    val peakSpreadMinutes: Double? = null,
    val offsetSpreadMinutes: Double? = null,
) {
    /** `isDepot` gate lives on the builder side; kept here for parity naming. */
    companion object {
        const val UNKNOWN_INTENSITY = 0.60
        const val MINIMUM_INTENSITY = 0.05

        /** intensity = amount/heavy bounded [0.05, 1]; null range → neutral 0.60. */
        fun computeDoseIntensity(amount: Double, range: com.piru.app.data.DoseRange?): Double {
            val ref = heavyReference(range) ?: return UNKNOWN_INTENSITY
            return minOf(1.0, maxOf(MINIMUM_INTENSITY, amount / ref))
        }

        /** unclamped magnitude for stacked superposition. */
        fun computeDoseMagnitude(amount: Double, range: com.piru.app.data.DoseRange?): Double {
            val ref = heavyReference(range) ?: return UNKNOWN_INTENSITY
            return maxOf(MINIMUM_INTENSITY, amount / ref)
        }

        /** Where a PUBLISHED heavy bound sits on the magnitude scale (1.0) or null. */
        fun heavyThresholdMagnitude(range: com.piru.app.data.DoseRange?): Double? {
            val heavy = range?.heavy ?: return null
            return if (heavy > 0) 1.0 else null
        }

        fun heavyReference(range: com.piru.app.data.DoseRange?): Double? {
            range ?: return null
            range.heavy?.takeIf { it > 0 }?.let { return it }
            range.strong?.max?.takeIf { it > 0 }?.let { return it }
            range.common?.max?.takeIf { it > 0 }?.let { return it * 1.5 }
            range.light?.max?.takeIf { it > 0 }?.let { return it * 3 }
            range.threshold?.takeIf { it > 0 }?.let { return it * 10 }
            return null
        }

        /**
         * Build timeline state from a logged dose. Port of
         * `ActiveSubstanceState.from(entry:colorHex:)`. Returns null for doses with
         * no honest acute curve (depot, unmodeled form, missing duration data) -
         * those fall through to a timestamp marker.
         */
        fun from(
            entry: DoseEntry,
            substance: Substance?,
            colorHex: String,
            weightKg: Double,
        ): ActiveSubstanceState? {
            substance ?: return null
            if (entry.isDepot()) return null
            val productDuration: DurationProfile? = null // per-product envelope table not wired yet
            if (productDuration == null && entry.namesUnmodeledForm()) return null
            val duration = productDuration ?: substance.timelineDurationFor(entry) ?: return null
            val doseRange = substance.doseRangeFor(entry.route)
            val intensity = computeDoseIntensity(entry.amount, doseRange)
            val magnitude = computeDoseMagnitude(entry.amount, doseRange)
            val zero = if (com.piru.app.data.SubstanceStoreHolder.ready)
                com.piru.app.data.SubstanceStoreHolder.store.zeroOrderKineticsFor(substance.name, weightKg) else null
            return build(
                name = substance.displayTitle, colorHex = colorHex, timestamp = entry.timestamp,
                amount = entry.amount, unit = entry.unit, routeDisplayName = entry.route.displayName,
                duration = duration, category = substance.category, doseIntensity = intensity,
                doseMagnitude = magnitude, heavyThresholdMagnitude = heavyThresholdMagnitude(doseRange),
                tachyphylaxis = substance.category.acuteToleranceFactor, weightKg = weightKg,
                zeroOrderKinetics = zero,
            )
        }

        /** Designated-builder port of the iOS `init?(name:…)` with the zero-order override path. */
        fun build(
            name: String, colorHex: String, timestamp: Long, amount: Double, unit: String,
            routeDisplayName: String, duration: DurationProfile?, category: SubstanceCategory?,
            doseIntensity: Double = 1.0, doseMagnitude: Double? = null,
            heavyThresholdMagnitude: Double? = null, tachyphylaxis: Double = 0.0,
            weightKg: Double = PKModel.REFERENCE_BODY_WEIGHT_KG,
            zeroOrderKinetics: PKModel.ZeroOrderKinetics? = null,
        ): ActiveSubstanceState? {
            duration ?: return null
            val filled = category?.let { duration.fillingMissingPhases(it) } ?: duration
            val b = filled.phaseBoundaries
            val zeroBound = TimelineCurveModel.zeroOrderBoundaries(zeroOrderKinetics, amount, unit)
            return ActiveSubstanceState(
                substanceName = name, colorHex = colorHex, doseTimestamp = timestamp,
                amount = amount, unit = unit, route = routeDisplayName,
                onsetEndMinutes = zeroBound?.onsetEnd ?: b.onsetEnd,
                comeupEndMinutes = zeroBound?.comeupEnd ?: b.comeupEnd,
                peakEndMinutes = zeroBound?.peakEnd ?: b.peakEnd,
                offsetEndMinutes = zeroBound?.offsetEnd ?: b.offsetEnd,
                afterglowEndMinutes = if (zeroBound != null) null else (if (filled.afterglow != null) b.afterglowEnd else null),
                totalMinutes = zeroBound?.total ?: filled.estimatedTotalMinutes,
                doseIntensity = doseIntensity,
                doseMagnitude = doseMagnitude ?: UNKNOWN_INTENSITY,
                heavyThresholdMagnitude = heavyThresholdMagnitude,
                tachyphylaxis = tachyphylaxis, bodyWeightKg = weightKg, zeroOrder = zeroOrderKinetics,
                comeupSpreadMinutes = filled.comeup?.let { it.max - it.min },
                peakSpreadMinutes = filled.peak?.let { it.max - it.min },
                offsetSpreadMinutes = filled.offset?.let { it.max - it.min },
            )
        }
    }
}

/** Port of `DoseEntry.namesUnmodeledForm`: XR/DEP forms have no per-form data authored. */
fun DoseEntry.namesUnmodeledForm(): Boolean = releaseForm == "XR" || releaseForm == "DEP"

/** Port of `PKResolver.isDepot(entry:)`. */
fun DoseEntry.isDepot(): Boolean {
    if (releaseForm == "DEP") return true
    if (route != RouteOfAdministration.Intramuscular && route != RouteOfAdministration.Subcutaneous) return false
    val uid = substanceUID ?: return false
    val salt = saltForm ?: return false
    return if (com.piru.app.data.SubstanceStoreHolder.ready)
        com.piru.app.data.SubstanceStoreHolder.store.isEster(salt, uid) else false
}

/** The substance timeline duration for a logged dose (salt/isomer aware, honest gate). */
fun Substance.timelineDurationFor(entry: DoseEntry): DurationProfile? {
    if (durationImplausible) return null
    val r = routes.firstOrNull { it.route == entry.route } ?: routes.firstOrNull { it.route == defaultRoute } ?: return null
    val variantKey = (entry.saltForm ?: "null") + "|" + (entry.isomer ?: "null")
    val raw = if (entry.saltForm == null && entry.isomer == null) r.duration
    else r.variantDurations[variantKey] ?: r.duration
    return raw?.takeIf { it.estimatedTotalMinutes > 0 }
}

/**
 * Split dose entries into (curve states, timestamp markers) - port of
 * `ActiveSubstanceState.timeline(for:colors:)`. Supplements without an acute
 * profile are dropped entirely (no honest curve, no honest marker).
 */
fun timelineFor(
    entries: List<DoseEntry>, colorFor: (DoseEntry) -> String, weightKg: Double,
): Pair<List<ActiveSubstanceState>, List<DoseMarker>> {
    val store = if (com.piru.app.data.SubstanceStoreHolder.ready) com.piru.app.data.SubstanceStoreHolder.store else return emptyList<ActiveSubstanceState>() to emptyList()
    val states = ArrayList<ActiveSubstanceState>()
    val markers = ArrayList<DoseMarker>()
    for (e in entries) {
        val substance = store.lookup(e.substance)
        val hex = colorFor(e)
        val state = ActiveSubstanceState.from(e, substance, hex, weightKg)
        if (state != null) { states.add(state); continue }
        if (substance?.category == SubstanceCategory.Supplement) continue
        markers.add(
            DoseMarker(
                substanceName = substance?.displayTitle ?: e.substance,
                timestamp = e.timestamp, colorHex = hex, amount = e.amount, unit = e.unit,
            )
        )
    }
    return states to markers
}

data class DoseMarker(
    val substanceName: String,
    val timestamp: Long,
    val colorHex: String,
    val amount: Double,
    val unit: String,
)
