package com.piru.app.engine

import com.piru.app.models.PKModel
import com.piru.app.models.ByVolumeDosing
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Pure curve-synthesis math behind the timeline graph - 1:1 port of
 * `Shared/Engines/TimelineCurveModel.swift`. Everything is a pure function of its
 * inputs: the phase-shaped effect curves, the Hill redose merge, amplitude
 * compression, tail scanning, and lane/tick layout.
 */
object TimelineCurveModel {

    const val MAX_DISPLAY_MINUTES = 48 * 60.0
    const val TARGET_TICK_COUNT: Int = 8
    private const val COMEUP_FLOOR_MINUTES: Double = 8.0

    /** Choose a clean tick interval yielding ~8 labels for the span. */
    fun intervalForSpan(span: Double): Double {
        val candidates = doubleArrayOf(15.0, 30.0, 60.0, 120.0, 240.0, 480.0, 720.0, 1440.0)
        val ideal = span / TARGET_TICK_COUNT
        return candidates.firstOrNull { it >= ideal } ?: 1440.0
    }

    // MARK: - Intensity (mechanistic phase curve)

    /** Normalized [0,1] effect intensity at [minutes] past the dose. */
    fun intensity(minutes: Double, s: ActiveSubstanceState): Double {
        val shape = effectShape(minutes, s)
        return if (shape <= 0) 0.0 else shape * toleranceGate(minutes, s)
    }

    fun zeroOrderKinetics(s: ActiveSubstanceState): Pair<PKModel.ZeroOrderKinetics, Double>? =
        zeroOrderKinetics(s.zeroOrder, s.amount, s.unit)

    fun zeroOrderKinetics(
        kinetics: PKModel.ZeroOrderKinetics?, amount: Double, unit: String,
    ): Pair<PKModel.ZeroOrderKinetics, Double>? {
        kinetics ?: return null
        val mg = zeroOrderDoseMilligrams(amount, unit) ?: return null
        return kinetics to mg
    }

    /** Dose-scaled phase boundaries for zero-order substances (alcohol), else null. */
    fun zeroOrderBoundaries(
        kinetics: PKModel.ZeroOrderKinetics?, amount: Double, unit: String,
    ): ZeroBoundaries? {
        val pair = zeroOrderKinetics(kinetics, amount, unit) ?: return null
        val (k, doseMg) = pair
        val peak = PKModel.zeroOrderPeakMinutes(doseMg, k)
        val clear = PKModel.zeroOrderClearMinutes(doseMg, k)
        if (peak <= 0 || clear <= peak) return null
        val peakEnd = min(peak * 1.15, (peak + clear) / 2)
        return ZeroBoundaries(peak * 0.35, peak * 0.85, peakEnd, clear, clear)
    }

    data class ZeroBoundaries(
        val onsetEnd: Double, val comeupEnd: Double, val peakEnd: Double,
        val offsetEnd: Double, val total: Double,
    )

    /** The logged amount in ethanol-mg; null for volume units and non-mass spellings. */
    fun zeroOrderDoseMilligrams(amount: Double, unit: String): Double? {
        if (!amount.isFinite() || amount <= 0) return null
        return when (unit.trim().lowercase()) {
            "g", "gram", "grams" -> amount * 1_000
            "mg", "milligram", "milligrams" -> amount
            "drink", "drinks", "unit", "units", "standard drink", "standard drinks" ->
                amount * ByVolumeDosing.US_STANDARD_DRINK_GRAMS * 1_000
            else -> null
        }
    }

    /** Subjective effect-strength curve [0,1] built from the dose's duration phases. */
    fun effectShape(minutes: Double, s: ActiveSubstanceState): Double {
        if (minutes < 0) return 0.0
        zeroOrderKinetics(s)?.let { (k, doseMg) ->
            PKModel.zeroOrderShape(doseMg, minutes, k)?.let { return it }
        }
        return EffectCurveParams(s).valueAt(minutes)
    }

    /** All fit parameters for one dose's effect curve (one closed-form solve per side). */
    class EffectCurveParams(s: ActiveSubstanceState) {
        val mu: Double
        private val sigmaUp: Double
        private val exponentUp: Double
        private val domeScaleUp: Double
        private val sigmaDown: Double
        private val exponentDown: Double
        private val domeScaleDown: Double

        companion object {
            const val crestHi = 0.92
            const val footLo = 0.03
            const val tailLo = 0.03
            const val domeSag = 0.10
            const val peakBias = 0.40
            const val exponentFloor = 1.6
            const val riseCeiling = 1_000.0
            const val fallCeiling = 6.0

            /** Bounded crest sag: 1 at crest, easing toward 1−domeSag with zero slope. */
            fun dome(u: Double): Double = 1 - domeSag * (1 - exp(-0.5 * u * u))

            /** Two-anchor closed-form solve for (σ, p). */
            fun fit(near: Double, far: Double, hi: Double, lo: Double, ceiling: Double, keepFarOnClamp: Boolean): Pair<Double, Double> {
                val rNear = max(near, 1e-3)
                val rFar = max(far, rNear * 1.005)
                val hNear = -2 * ln(min(hi, 0.995))
                val hFar = -2 * ln(min(max(lo, 1e-6), 0.9))
                val exact = ln(hFar / hNear) / ln(rFar / rNear)
                val p = min(max(exact, exponentFloor), ceiling)
                val sigma = if (p != exact && keepFarOnClamp) rFar / hFar.pow(1 / p) else rNear / hNear.pow(1 / p)
                return sigma to p
            }
        }

        init {
            val a = s.onsetEndMinutes
            val c = max(s.peakEndMinutes, a + 2)
            val d = max(s.offsetEndMinutes, c + 1)
            val b = effectiveComeupEnd(s, a, c)
            val bAnchor = b + (s.comeupSpreadMinutes ?: 0.0) / 2
            val cAnchor = max(c, bAnchor + 1e-3)
            val dAnchor = max(d + (s.offsetSpreadMinutes ?: 0.0) / 2, cAnchor + 1)
            mu = bAnchor + peakBias * (cAnchor - bAnchor)
            val rUp = max(mu - bAnchor, 1e-3)
            val rDown = max(cAnchor - mu, 1e-3)
            val domeHalf = (s.peakSpreadMinutes ?: 0.0) / 2
            domeScaleUp = rUp + domeHalf
            domeScaleDown = rDown + domeHalf
            val (su, eu) = fit(
                rUp, mu - a,
                crestHi / dome(rUp / domeScaleUp),
                footLo / dome((mu - a) / domeScaleUp),
                riseCeiling, keepFarOnClamp = false,
            )
            sigmaUp = su; exponentUp = eu
            val (sd, ed) = fit(
                rDown, dAnchor - mu,
                crestHi / dome(rDown / domeScaleDown),
                tailLo / dome((dAnchor - mu) / domeScaleDown),
                fallCeiling, keepFarOnClamp = true,
            )
            sigmaDown = sd; exponentDown = ed
        }

        /** The falling-limb (σ,p) pair exposed for extent solves. */
        internal fun downLimb(): Pair<Double, Double> = sigmaDown to exponentDown

        fun valueAt(minutes: Double): Double {
            val fromCrest = minutes - mu
            val z: Double; val u: Double; val p: Double
            if (fromCrest < 0) {
                z = -fromCrest / sigmaUp; u = -fromCrest / domeScaleUp; p = exponentUp
            } else {
                z = fromCrest / sigmaDown; u = fromCrest / domeScaleDown; p = exponentDown
            }
            val core = if (z > 0) exp(-0.5 * z.pow(p)) else 1.0
            return core * dome(u)
        }
    }

    /** The come-up boundary the rising shoulder is fit to (synthesized when data has none). */
    fun effectiveComeupEnd(s: ActiveSubstanceState, onsetEnd: Double, peakEnd: Double): Double {
        val explicit = s.comeupEndMinutes - onsetEnd
        val gap = peakEnd - onsetEnd
        val floor = min(COMEUP_FLOOR_MINUTES, max(onsetEnd, 0.0))
        val window = if (explicit > 1) explicit else min(max(onsetEnd * 0.6, floor), gap * 0.5)
        return onsetEnd + max(window, 1e-3)
    }

    /** Acute-tolerance (tachyphylaxis) multiplier on the descending limb. */
    fun toleranceGate(minutes: Double, s: ActiveSubstanceState): Double {
        val kappa = s.tachyphylaxis
        if (kappa <= 0) return 1.0
        val peakEnd = s.peakEndMinutes
        val end = max(s.totalMinutes, peakEnd + 1)
        if (minutes <= peakEnd) return 1.0
        val x = min(1.0, (minutes - peakEnd) / (end - peakEnd))
        val smooth = x * x * (3 - 2 * x)
        return max(0.0, 1 - kappa * smooth)
    }

    /** Minutes after dose where the curve returns to baseline (draw end). */
    fun curveExtent(s: ActiveSubstanceState): Double {
        zeroOrderKinetics(s)?.let { (k, doseMg) ->
            val clear = PKModel.zeroOrderClearMinutes(doseMg, k)
            if (clear > 0) return min(max(clear * 1.04, 1.0), MAX_DISPLAY_MINUTES)
        }
        val params = EffectCurveParams(s)
        val (sd, ed) = params.downLimb()
        val end = params.mu + sd * (-2 * ln(0.015)).pow(1 / ed)
        return min(max(end, 1.0), MAX_DISPLAY_MINUTES)
    }

    /** When the curve stops registering beside a peer of magnitude [peerMagnitude]. */
    fun visibleExtent(s: ActiveSubstanceState, peerMagnitude: Double, threshold: Double = 0.02): Double {
        val full = curveExtent(s)
        if (zeroOrderKinetics(s) != null) return full
        val magnitude = max(s.doseMagnitude, 0.0)
        val peer = max(peerMagnitude, magnitude)
        if (magnitude <= 0 || peer <= 0 || threshold <= 0) return full
        val params = EffectCurveParams(s)
        val cutoff = threshold * peer / magnitude
        if (cutoff >= 1) return min(max(params.mu, 1.0), full)
        val (sd, ed) = params.downLimb()
        val t = params.mu + sd * (-2 * ln(cutoff)).pow(1 / ed)
        return min(max(t, 1.0), full)
    }

    // MARK: - Stacked merge (Hill link)

    const val HILL_EC50: Double = 0.78
    const val HILL_EXPONENT: Double = 1.4

    /** Saturating dose→height link C^h/(EC50^h + C^h). */
    fun hill(magnitude: Double): Double {
        if (magnitude <= 0) return 0.0
        val m = magnitude.pow(HILL_EXPONENT)
        return m / (HILL_EC50.pow(HILL_EXPONENT) + m)
    }

    /** Groups by (substance, route), first-dose order, so routes never merge. */
    fun stackedGroups(states: List<ActiveSubstanceState>): List<List<ActiveSubstanceState>> {
        val order = ArrayList<String>()
        val buckets = HashMap<String, MutableList<ActiveSubstanceState>>()
        for (s in states) {
            val key = s.substanceName.lowercase() + "|" + s.route.lowercase()
            val bucket = buckets[key]
            if (bucket == null) buckets[key] = arrayListOf(s).also { order.add(key) }
            else bucket.add(s)
        }
        return order.mapNotNull { buckets[it] }
    }

    /** Combined intensity of a group at global minutes since [earliestDoseMillis]. */
    fun stackedIntensity(globalMinutes: Double, group: List<ActiveSubstanceState>, earliestDoseMillis: Long): Double {
        var sum = 0.0
        for (dose in group) {
            val offset = (dose.doseTimestamp - earliestDoseMillis) / 60_000.0
            val local = globalMinutes - offset
            if (local < 0) continue
            sum += dose.doseMagnitude * intensity(local, dose)
        }
        return hill(sum)
    }

    fun stackedGroupRange(group: List<ActiveSubstanceState>, earliestDoseMillis: Long): Pair<Double, Double> {
        var start = Double.MAX_VALUE
        var end = 0.0
        for (dose in group) {
            val offset = (dose.doseTimestamp - earliestDoseMillis) / 60_000.0
            start = min(start, offset)
            end = max(end, offset + curveExtent(dose))
        }
        return start to end
    }

    /** Single-dose height via the same Hill link (parity with the stacked path). */
    fun heightScale(s: ActiveSubstanceState): Double = max(0.0001, hill(s.doseMagnitude))

    /** Highest curve peak across all substances/groups (y-axis normalization). */
    fun peakCurveValue(
        states: List<ActiveSubstanceState>, stackedGroups: List<List<ActiveSubstanceState>>,
        earliestDoseMillis: Long, stackRedoses: Boolean,
    ): Double {
        if (stackRedoses) {
            var maxV = 0.0
            for (group in stackedGroups) {
                val (s, e) = stackedGroupRange(group, earliestDoseMillis)
                if (e <= s) continue
                val steps = 48
                for (i in 0..steps) {
                    val t = s + i.toDouble() / steps * (e - s)
                    maxV = max(maxV, stackedIntensity(t, group, earliestDoseMillis))
                }
            }
            return max(maxV, 0.0001)
        }
        return max(states.maxOfOrNull { heightScale(it) } ?: 1.0, 0.0001)
    }

    // MARK: - Tail scan (window sizing)

    /**
     * Latest minute at which the tallest drawn curve still exceeds [threshold] of
     * full graph height. When [framing] is true, never-decaying persistent curves
     * are dropped so a daily maintenance med can't drag the default frame.
     */
    fun renderedTail(
        threshold: Double, framing: Boolean, states: List<ActiveSubstanceState>,
        groups: List<List<ActiveSubstanceState>>, earliestDoseMillis: Long, yNorm: Double,
        stackRedoses: Boolean, displayCap: Double,
    ): Double {
        val curves = ArrayList<(Double) -> Double>()
        var upper = 1.0
        if (stackRedoses) {
            for (group in groups) {
                for (dose in group) {
                    val offset = (dose.doseTimestamp - earliestDoseMillis) / 60_000.0
                    upper = max(upper, offset + curveExtent(dose))
                }
                curves.add { t -> stackedIntensity(t, group, earliestDoseMillis) * yNorm }
            }
        } else {
            for (s in states) {
                val offset = (s.doseTimestamp - earliestDoseMillis) / 60_000.0
                val scale = heightScale(s)
                upper = max(upper, offset + curveExtent(s))
                curves.add { t ->
                    val local = t - offset
                    if (local >= 0) intensity(local, s) * scale * yNorm else 0.0
                }
            }
        }
        upper = min(upper, displayCap)
        var contributing: List<(Double) -> Double> = curves
        if (framing) {
            val transient = curves.filter { it(upper) < threshold }
            if (transient.isNotEmpty()) contributing = transient
        }
        val steps = 240
        var lastActive = 0.0
        for (i in 0..steps) {
            val t = i.toDouble() / steps * upper
            var h = 0.0
            for (curve in contributing) {
                h = max(h, curve(t))
                if (h >= threshold) break
            }
            if (h >= threshold) lastActive = t
        }
        return max(lastActive, 1.0)
    }

    /** Heavy-dose threshold height for single-substance graphs, else null. */
    fun heavyThresholdHeight(
        states: List<ActiveSubstanceState>, groups: List<List<ActiveSubstanceState>>,
        stackRedoses: Boolean, yNormalization: Double,
    ): Double? {
        val first = states.firstOrNull() ?: return null
        val name = first.substanceName.lowercase()
        if (!states.all { it.substanceName.lowercase() == name }) return null
        val thresholds = states.mapNotNull { it.heavyThresholdMagnitude }
        val threshold = thresholds.firstOrNull() ?: return null
        if (threshold <= 0 || !thresholds.all { abs(it - threshold) < 1e-9 }) return null
        val height = if (stackRedoses) {
            if (groups.size != 1) return null
            hill(threshold) * yNormalization
        } else {
            if (states.size != 1) return null
            val magnitude = max(first.doseMagnitude, 0.0001)
            threshold / magnitude
        }
        return if (height > 0 && height <= 0.98) height else null
    }

    /** Amplitude γ-compression exponent. */
    const val AMPLITUDE_GAMMA: Double = 0.5

    /** Compress low amplitudes so faint doses stay legible beside heavy ones. */
    fun compressedAmplitude(amplitude: Double): Double =
        min(max(amplitude, 0.0), 1.0).pow(AMPLITUDE_GAMMA)
}
