package com.piru.app.models

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * One-compartment oral PK math + zero-order (capacity-limited) elimination.
 * 1:1 port of Shared/Engines/PKModel.swift.
 */
object PKModel {

    /** Normalized one-compartment oral PK concentration at time [t] (minutes), raw unnormalized. */
    fun concentration(tMinutes: Double, ke: Double, ka: Double): Double {
        if (tMinutes < 0 || ka <= 0 || ke <= 0) return 0.0
        // ka ≈ ke singularity (L'Hopital limit)
        if (abs(ka - ke) < 1e-10) return ke * tMinutes * exp(-ke * tMinutes)
        return max(0.0, (ka / (ka - ke)) * (exp(-ke * tMinutes) - exp(-ka * tMinutes)))
    }

    /** Fraction of original dose still in the body (gut depot + central compartment) at [t]. */
    fun fractionRemainingInBody(tMinutes: Double, ke: Double, ka: Double): Double {
        if (tMinutes < 0 || ka <= 0 || ke <= 0) return 1.0
        if (abs(ka - ke) < 1e-10) return (1 + ke * tMinutes) * exp(-ke * tMinutes)
        val result = (ka * exp(-ke * tMinutes) - ke * exp(-ka * tMinutes)) / (ka - ke)
        return min(1.0, max(0.0, result))
    }

    /** Time of peak concentration (Tmax) in minutes. */
    fun tmax(ke: Double, ka: Double): Double {
        if (ka <= ke || ka <= 0 || ke <= 0) return 0.0
        if (abs(ka - ke) < 1e-10) return 1.0 / ke
        return ln(ka / ke) / (ka - ke)
    }

    /** Peak concentration value (unnormalized). */
    fun cmax(ke: Double, ka: Double): Double = concentration(tmax(ke, ka), ke, ka)

    /** ke from half-life in minutes. */
    fun keFromHalfLifeMinutes(halfLifeMinutes: Double): Double =
        if (halfLifeMinutes > 0) ln(2.0) / halfLifeMinutes else 0.0

    /** Estimate ka from time-to-peak and ke (Newton), defaultKa on failure. */
    fun estimateKa(timeToPeak: Double, ke: Double): Double {
        if (timeToPeak <= 0 || ke <= 0) return defaultKa(ke)
        var ka = 4 * ke
        for (i in 0 until 50) {
            if (ka <= ke) return defaultKa(ke)
            val f = ln(ka / ke) / (ka - ke) - timeToPeak
            val denom = (ka - ke) * (ka - ke)
            val df = ((ka - ke) / ka - ln(ka / ke)) / denom
            if (abs(df) <= 1e-15) break
            val kaNew = ka - f / df
            // convergence test against the pre-step ka, exactly as the Swift original
            if (abs(kaNew - ka) < 1e-6) { ka = max(ke * 1.01, kaNew); break }
            ka = max(ke * 1.01, kaNew)
        }
        return ka
    }

    /** Default ka = 4x elimination rate. */
    fun defaultKa(ke: Double): Double = 4 * ke

    /** Minutes at which concentration drops to [fraction] of cmax on the descending side. */
    fun timeToFraction(fraction: Double, ke: Double, ka: Double, maxMinutes: Double = 50_000.0): Double {
        val peak = cmax(ke, ka)
        if (peak <= 0) return 0.0
        val target = peak * fraction
        var lo = tmax(ke, ka)
        var hi = maxMinutes
        for (i in 0 until 100) {
            val mid = (lo + hi) / 2
            if (concentration(mid, ke, ka) > target) lo = mid else hi = mid
            if (hi - lo < 0.1) break
        }
        return hi
    }

    // MARK: - Zero-order (capacity-limited) elimination - the alcohol shape

    data class ZeroOrderKinetics(
        val bioavailability: Double,
        /** Maximal elimination rate at this person's body weight, mg/min. */
        val vmaxMgPerMin: Double,
        /** First-order absorption rate constant, per minute. */
        val ka: Double,
    )

    const val REFERENCE_BODY_WEIGHT_KG = 60.0
    private const val MIN_MODELED_WEIGHT_KG = 20.0
    private const val MAX_MODELED_WEIGHT_KG = 300.0

    /** Stored zero-order params scaled to one person's weight. */
    fun zeroOrderKinetics(
        vmaxMgPerMin: Double,
        referenceWeightKg: Double,
        kaPerMin: Double,
        bioavailability: Double,
        weightKg: Double,
    ): ZeroOrderKinetics {
        val clamped = if (weightKg.isFinite())
            min(max(weightKg, MIN_MODELED_WEIGHT_KG), MAX_MODELED_WEIGHT_KG) else REFERENCE_BODY_WEIGHT_KG
        val reference = if (referenceWeightKg > 0) referenceWeightKg else REFERENCE_BODY_WEIGHT_KG
        return ZeroOrderKinetics(bioavailability, vmaxMgPerMin / reference * clamped, kaPerMin)
    }

    /** Body content (mg) at [t]: M(t) = F·D·(1 − e^(−ka·t)) − Vmax·t, floored at 0. */
    fun zeroOrderBodyContent(doseMg: Double, tMinutes: Double, k: ZeroOrderKinetics): Double {
        if (doseMg <= 0 || tMinutes < 0 || k.bioavailability <= 0 || k.vmaxMgPerMin <= 0 || k.ka <= 0) return 0.0
        val fd = k.bioavailability * doseMg
        val absorbed = fd * (1 - exp(-k.ka * tMinutes))
        return max(0.0, absorbed - k.vmaxMgPerMin * tMinutes)
    }

    /** Minutes to peak body content; 0 when the dose is too small to out-pace elimination. */
    fun zeroOrderPeakMinutes(doseMg: Double, k: ZeroOrderKinetics): Double {
        if (doseMg <= 0 || k.ka <= 0 || k.bioavailability <= 0) return 0.0
        val fd = k.bioavailability * doseMg
        val ratio = k.vmaxMgPerMin / (fd * k.ka)
        if (ratio <= 0 || ratio >= 1) return 0.0
        return -ln(ratio) / k.ka
    }

    /** Minutes until body content returns to ~0 (dose-scaled curve width). */
    fun zeroOrderClearMinutes(doseMg: Double, k: ZeroOrderKinetics): Double {
        val peak = zeroOrderPeakMinutes(doseMg, k)
        if (peak <= 0) return 0.0
        var lo = peak
        var hi = peak + k.bioavailability * doseMg / k.vmaxMgPerMin + 60
        for (i in 0 until 60) {
            val mid = (lo + hi) / 2
            if (zeroOrderBodyContent(doseMg, mid, k) > 0) lo = mid else hi = mid
        }
        return hi
    }

    /** Normalized [0,1] effect shape (≡ BAC/peakBAC); null for a sub-peak dose. */
    fun zeroOrderShape(doseMg: Double, tMinutes: Double, k: ZeroOrderKinetics): Double? {
        if (tMinutes < 0) return null
        val peakT = zeroOrderPeakMinutes(doseMg, k)
        if (peakT <= 0) return null
        val peak = zeroOrderBodyContent(doseMg, peakT, k)
        if (peak <= 0) return null
        return min(1.0, max(0.0, zeroOrderBodyContent(doseMg, tMinutes, k) / peak))
    }

    // MARK: - Receptor occupancy (Hill / Langmuir isotherm)

    fun occupancy(concentration: Double, halfMax: Double, hillCoefficient: Double = 1.0): Double {
        if (concentration <= 0 || halfMax <= 0 || hillCoefficient <= 0) return 0.0
        if (hillCoefficient == 1.0) return concentration / (halfMax + concentration)
        val cH = concentration.pow(hillCoefficient)
        val kH = halfMax.pow(hillCoefficient)
        return cH / (kH + cH)
    }
}

/**
 * Pharmacodynamic tolerance dynamics - port of Shared/Engines/PDModel.swift.
 */
object PDModel {

    /** Leaky-integrator closed-form step toward shiftMax·occupancy·drive with time-constant tau. */
    fun stepShift(
        current: Double, shiftMax: Double, occupancy: Double, drive: Double,
        dtMinutes: Double, tauMinutes: Double,
    ): Double {
        if (dtMinutes <= 0 || tauMinutes <= 0) return current
        val target = max(0.0, shiftMax) * min(1.0, max(0.0, occupancy)) * max(0.0, drive)
        val decay = exp(-dtMinutes / tauMinutes)
        return target + (current - target) * decay
    }

    /** Smoothstep gate ∈ [0,1]. */
    fun smoothstepGate(value: Double, threshold: Double, width: Double): Double {
        if (width <= 0) return if (value >= threshold) 1.0 else 0.0
        val t = min(1.0, max(0.0, (value - threshold) / width))
        return t * t * (3 - 2 * t)
    }

    /** Response fraction ∈ [0,1] under right-shift S at representative occupancy. */
    fun responseFraction(shiftFactor: Double, representativeOccupancy: Double, occupancyCap: Double? = null): Double {
        if (shiftFactor <= 0) return 1.0
        val capped = if (occupancyCap != null) min(occupancyCap, representativeOccupancy) else representativeOccupancy
        val occ = min(0.999_999, max(0.0, capped))
        val ratio = occ / (1 - occ)
        return min(1.0, max(0.0, (ratio + 1) / (ratio + shiftFactor)))
    }

    /** Minutes until S decays to targetShift with no further occupancy (null if below 1). */
    fun shiftDecayMinutes(layers: List<Pair<Double, Double>>, targetShift: Double): Double? {
        val initialSum = layers.sumOf { max(0.0, it.first) }
        val currentShift = exp(initialSum)
        if (currentShift <= targetShift) return 0.0
        if (targetShift < 1) return null
        fun shiftAt(minutes: Double) = exp(layers.sumOf { it.first * exp(-minutes / max(1.0, it.second)) })
        var low = 0.0
        var high = 1.0
        while (shiftAt(high) > targetShift && high < 2_628_000) high *= 2
        if (shiftAt(high) > targetShift) return high
        for (i in 0 until 60) {
            val mid = (low + high) / 2
            if (shiftAt(mid) > targetShift) low = mid else high = mid
        }
        return (low + high) / 2
    }

    /** Gaddum competitive summation of occupancies at one shared target. */
    fun competitiveOccupancy(occupancies: List<Double>): Double {
        var sumRatio = 0.0
        for (o in occupancies) {
            val clamped = min(1.0, max(0.0, o))
            if (clamped >= 1) return 1.0
            sumRatio += clamped / (1 - clamped)
        }
        return sumRatio / (1 + sumRatio)
    }
}
