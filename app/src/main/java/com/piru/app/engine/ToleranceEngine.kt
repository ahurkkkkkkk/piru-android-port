package com.piru.app.engine

import com.piru.app.data.DoseEntry
import com.piru.app.data.Substance
import com.piru.app.data.SubstanceStoreHolder
import com.piru.app.models.DoseUnit
import com.piru.app.models.PKModel
import com.piru.app.models.PDModel
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Mechanism classes the tolerance engine tracks - port of
 * Piru/Data/Pharmacology/ReceptorClasses.swift with the exact constants (τ in
 * minutes; h = 60, d = 1440, mo = 30·d = 43_200).
 */
enum class ReceptorClass(
    val rawValue: String,
    val gaugeCap: Double?,
    val acuteShiftMax: Double, val tauAcute: Double,
    val adaptiveShiftMax: Double, val tauAdaptive: Double,
    val deepShiftMax: Double, val tauDeep: Double,
    val synthesisShiftMax: Double, val tauSynthesis: Double,
    val classVdPerKg: Double?,
) {
    Psychedelic5HT2A("psychedelic_5ht2a", null,
        1.2, 18 * 60.0, 2.5, 3.5 * 1440.0, 0.0, 3.0 * 43_200.0, 0.0, 3.0 * 43_200.0, 4.0),
    MuOpioid("mu_opioid", null,
        0.3, 4 * 60.0, 1.8, 20 * 1440.0, 1.1, 6.0 * 43_200.0, 0.0, 3.0 * 43_200.0, 3.5),
    CatecholamineStimulant("catecholamine_stimulant", 0.5,
        0.8, 9 * 60.0, 0.4, 12 * 1440.0, 1.6, 9.0 * 43_200.0, 0.0, 3.0 * 43_200.0, 4.0),
    SerotonergicReleaser("serotonergic_releaser", 0.5,
        0.5, 12 * 60.0, 1.0, 4 * 1440.0, 0.0, 3.0 * 43_200.0, 1.0, 14 * 1440.0, 6.5),
    GABA("gaba", null,
        0.4, 6 * 60.0, 2.0, 14 * 1440.0, 0.0, 3.0 * 43_200.0, 0.0, 3.0 * 43_200.0, 1.1),
    Alpha2Delta("alpha2delta", 0.5,
        0.3, 6 * 60.0, 0.8, 10 * 1440.0, 0.0, 3.0 * 43_200.0, 0.0, 3.0 * 43_200.0, 0.5),
    NMDAAntagonist("nmda", null,
        0.4, 4 * 60.0, 1.0, 3 * 1440.0, 0.0, 3.0 * 43_200.0, 0.0, 3.0 * 43_200.0, 2.5),
    CannabinoidCB1("cb1", null,
        0.3, 6 * 60.0, 1.2, 4 * 1440.0, 0.0, 3.0 * 43_200.0, 0.0, 3.0 * 43_200.0, 3.4),
    Adenosine("adenosine", null, 0.0, 4 * 60.0, 1.0, 5 * 1440.0, 0.0, 0.0, 0.0, 0.0, null),
    Nicotinic("nicotinic", null, 0.8, 2 * 60.0, 0.4, 1 * 1440.0, 0.0, 0.0, 0.0, 0.0, null),
    Alpha2Agonist("alpha2", null, 0.0, 4 * 60.0, 0.2, 7 * 1440.0, 0.0, 0.0, 0.0, 0.0, null),
    BetaBlocker("beta", null, 0.0, 4 * 60.0, 0.15, 7 * 1440.0, 0.0, 0.0, 0.0, 0.0, null),
    Unknown("unknown", null, 0.0, 4 * 60.0, 0.7, 7 * 1440.0, 0.0, 0.0, 0.0, 0.0, null);

    val tauChronic = 21 * 1440.0

    companion object {
        const val DEEP_MAG_THRESHOLD = 0.5
        const val DEEP_MAG_WIDTH = 1.0
        const val DEEP_CHRONIC_THRESHOLD = 0.25
        const val DEEP_CHRONIC_WIDTH = 0.35

        /**
         * Classify a DB target string + action. Ordered substring match on the RAW
         * string (port of classifyTarget). Mechanism-direction gate: a target counts
         * only when its action can drive the class's tolerance layer.
         */
        fun classify(target: String, action: String?): Pair<ReceptorClass, Boolean>? {
            val t = target.lowercase()
            val a = action?.lowercase()
            fun ok(vararg acts: String) = a == null || acts.any { a.contains(it) }
            return when {
                t.contains("5-ht2a") -> Psychedelic5HT2A to ok("agonist", "partial")
                t.contains("μ-opioid") || t.contains("mu-opioid") || t.contains("mor") -> MuOpioid to ok("agonist", "partial")
                t.contains("sert") -> SerotonergicReleaser to ok("releasing")
                t.contains("dat") || t.contains("net") -> CatecholamineStimulant to ok("releasing", "reuptake")
                t.contains("gaba-a") -> GABA to ok("pam", "agonist", "modulator")
                t.contains("adrenergic") && t.contains("α2") -> Alpha2Agonist to ok("agonist", "partial")
                t.contains("adrenergic") && t.contains("β") -> BetaBlocker to ok("antagonist")
                t.contains("α2δ") || t.contains("2δ") || t.contains("voltage-dependent calcium channel") ->
                    Alpha2Delta to ok("modulator", "channelblocker", "channel blocker", "partial")
                t.contains("nmda") -> NMDAAntagonist to ok("antagonist", "blocker", "nam")
                t.contains("cb1") || t.contains("cannabinoid") -> CannabinoidCB1 to ok("agonist", "partial")
                t.contains("adenosine") -> Adenosine to ok("antagonist")
                t.contains("nachr") || t.contains("nicotinic") || t.contains("acetylcholine") ->
                    Nicotinic to ok("agonist", "partial")
                else -> null
            }
        }

        fun fromCategory(cat: com.piru.app.models.SubstanceCategory): ReceptorClass? = when (cat) {
            com.piru.app.models.SubstanceCategory.Stimulant,
            com.piru.app.models.SubstanceCategory.Eugeroic -> CatecholamineStimulant
            com.piru.app.models.SubstanceCategory.Opioid -> MuOpioid
            com.piru.app.models.SubstanceCategory.Benzodiazepine -> GABA
            com.piru.app.models.SubstanceCategory.Psychedelic -> Psychedelic5HT2A
            com.piru.app.models.SubstanceCategory.Dissociative -> NMDAAntagonist
            com.piru.app.models.SubstanceCategory.Empathogen -> SerotonergicReleaser
            com.piru.app.models.SubstanceCategory.Cannabinoid -> CannabinoidCB1
            com.piru.app.models.SubstanceCategory.Gabapentinoid -> Alpha2Delta
            else -> null
        }
    }
}

/**
 * PD tolerance replay - port of Piru/Data/Tolerance/ToleranceStore.swift using the
 * PDModel math. Given the dose log, replays receptor occupancy over 30-min steps
 * for up to a year and integrates the three right-shift layers per mechanism class.
 */
object ToleranceEngine {

    const val TIMESTEP_MIN = 30.0
    const val LOOKBACK_DAYS = 365.0
    const val MIN_MEANINGFUL_OCC = 0.05
    const val CONTRIB_RELEVANCE_FLOOR = 0.05

    data class Contributor(
        val substanceName: String,
        val prefactorNanomolar: Double,
        val halfMaxNanomolar: Double,
        val onsetMinutes: Double,
        val expiryMinutes: Double,
        val escalation: Double,
        val intrinsicEfficacy: Double,
        val ke: Double,
        val ka: Double,
        val isMetabolite: Boolean = false,
    )

    data class ClassTolerance(
        val mechanism: ReceptorClass,
        val sAcute: Double,
        val sAdaptive: Double,
        val sDeep: Double,
        val sSynthesis: Double,
        val chronicExposure: Double,
        val representativeOccupancy: Double,
        val occupancyNow: Double,
        val contributors: List<Contributor>,
    ) {
        val shiftFactor: Double get() = exp(sAcute + sAdaptive + sDeep + sSynthesis)
        val responseFraction: Double
            get() = PDModel.responseFraction(shiftFactor, representativeOccupancy, mechanism.gaugeCap)
        val severity: Double get() = 1.0 - responseFraction
    }

    /** Run the replay for one (substance, log-of-that-substance) partition or the whole log. */
    fun simulate(
        entries: List<DoseEntry>,
        weightKg: Double,
        nowMillis: Long,
    ): Map<ReceptorClass, ClassTolerance> {
        if (!SubstanceStoreHolder.ready || entries.isEmpty()) return emptyMap()
        val store = SubstanceStoreHolder.store
        val cutoff = nowMillis - (LOOKBACK_DAYS * 86_400_000).toLong()
        val inWindow = entries.filter { it.timestamp in cutoff..nowMillis }.sortedBy { it.timestamp }
        if (inWindow.isEmpty()) return emptyMap()
        val start = inWindow.first().timestamp
        val totalMinutes = (nowMillis - start) / 60_000.0

        // build contributors per class
        val contributorsByClass = HashMap<ReceptorClass, MutableList<Contributor>>()
        val peaksByClass = HashMap<ReceptorClass, MutableList<Double>>()
        for (e in inWindow) {
            val substance = store.lookup(e.substance) ?: continue
            val amountMg = DoseUnit.convert(e.amount, e.unit, "mg") ?: continue
            val targets = store.targetsFor(substance.id)
            val pk = substance.pk
            val vd = pk?.vdLPerKg ?: ReceptorClass.fromCategory(substance.category)?.classVdPerKg ?: continue
            val mw = pk?.molarMass ?: substance.molecularWeight ?: continue
            val f = pk?.bioavailabilityFraction ?: 1.0
            val hl = substance.halfLifeMinutes ?: continue
            val fu = 1.0 - ((pk?.proteinBindingPct ?: 0.0) / 100.0).coerceIn(0.0, 0.999)
            val ke = PKModel.keFromHalfLifeMinutes(hl)
            if (ke <= 0) continue
            val ka = pk?.tmaxMin?.let { PKModel.estimateKa(it, ke) } ?: PKModel.defaultKa(ke)
            val tmax = PKModel.tmax(ke, ka)
            val vdl = vd * weightKg.coerceIn(20.0, 300.0)
            val pref = fu * (f * amountMg / vdl) / 1000.0 / mw * 1e9
            val onset = (e.timestamp - start) / 60_000.0
            val eff = store.intrinsicEfficacy(substance.id) ?: 1.0
            val escalation = substance.referenceDoseMg?.takeIf { it > 0 }?.let { amountMg / it } ?: 0.0
            for (t in targets) {
                val (cls, drives) = ReceptorClass.classify(t.target, t.action) ?: continue
                if (!drives) continue
                val peakOcc = PKModel.occupancy(
                    pref * PKModel.concentration(tmax, ke, ka), t.halfMaxNanomolar
                )
                if (peakOcc < MIN_MEANINGFUL_OCC) continue
                val cThr = (1e-9 / (1 - 1e-9)) * t.halfMaxNanomolar
                val coeff = if (absSafe(ka - ke) > 1e-12) ka / (ka - ke) else 1.0
                val expiry = onset + max(1.5 * tmax, runCatching { ln(pref * coeff / cThr) / ke }.getOrElse { tmax * 4 })
                peaksByClass.getOrPut(cls) { ArrayList() }.add(peakOcc)
                contributorsByClass.getOrPut(cls) { ArrayList() }.add(
                    Contributor(e.substance, pref, t.halfMaxNanomolar, onset, expiry, escalation, eff, ke, ka)
                )
            }
        }

        val result = HashMap<ReceptorClass, ClassTolerance>()
        for ((cls, contributors) in contributorsByClass) {
            // merge intervals; fine-step grid, leaky layers
            val sAcute = DoubleArray(1)
            var sAdaptive = 0.0; var sDeep = 0.0; var sSynthesis = 0.0
            var chronic = 0.0
            val repOcc = peaksByClass[cls]?.let { peaks ->
                peaks.sorted().let { p -> p[p.size / 2] }
            } ?: 0.5
            // regularity factor
            val onsets = contributors.filter { !it.isMetabolite }.map { it.onsetMinutes }.distinct().sorted()
            val regularity = if (onsets.size < 3) 1.0 else {
                val gaps = onsets.windowed(2) { (a, b) -> b - a }
                val mean = gaps.average()
                val std = kotlin.math.sqrt(gaps.map { (it - mean) * (it - mean) }.average())
                0.7 + 0.3 / (1 + (if (mean > 0) std / mean else 1.0))
            }
            var t = 0.0
            val step = TIMESTEP_MIN
            while (t < totalMinutes) {
                val dt = min(step, totalMinutes - t)
                val mid = t + dt / 2
                // occupancy over the cell from active contributors
                var sumR = 0.0
                var effSum = 0.0
                var maxEsc = 0.0
                var anyActive = false
                for (c in contributors) {
                    if (mid < c.onsetMinutes || mid > c.expiryMinutes) continue
                    val local = mid - c.onsetMinutes
                    val occ = occFrom(c, local)
                    if (occ <= 0) continue
                    val ratio = occ / (1 - min(occ, 0.999999))
                    sumR += ratio
                    effSum += c.intrinsicEfficacy * ratio
                    maxEsc = max(maxEsc, c.escalation)
                    anyActive = true
                }
                val occupancy = if (sumR > 0) sumR / (1 + sumR) else 0.0
                val efficacyDrive = if (sumR > 0) effSum / sumR else 1.0
                chronic = PDModel.stepShift(chronic, 1.0, occupancy, 1.0, dt, cls.tauChronic)
                val magnitude = PDModel.smoothstepGate(maxEsc, ReceptorClass.DEEP_MAG_THRESHOLD, ReceptorClass.DEEP_MAG_WIDTH)
                val chronicity = PDModel.smoothstepGate(chronic, ReceptorClass.DEEP_CHRONIC_THRESHOLD, ReceptorClass.DEEP_CHRONIC_WIDTH)
                sAcute[0] = PDModel.stepShift(sAcute[0], cls.acuteShiftMax, occupancy, 1.0, dt, cls.tauAcute)
                sAdaptive = PDModel.stepShift(sAdaptive, cls.adaptiveShiftMax, occupancy, 1.0 * efficacyDrive * regularity, dt, cls.tauAdaptive)
                sDeep = PDModel.stepShift(sDeep, cls.deepShiftMax, occupancy, magnitude * chronicity, dt, cls.tauDeep)
                sSynthesis = PDModel.stepShift(sSynthesis, cls.synthesisShiftMax, occupancy, efficacyDrive, dt, cls.tauSynthesis)
                t += dt
            }
            val occupancyNow = if (anyActive(totalMinutes, contributors)) {
                var d = 0.0
                for (c in contributors) {
                    val local = totalMinutes - c.onsetMinutes
                    if (local >= 0 && local <= c.expiryMinutes - c.onsetMinutes) d += occFrom(c, local) / (1 - min(occFrom(c, local), 0.999999))
                }
                d / (1 + d)
            } else 0.0
            result[cls] = ClassTolerance(cls, sAcute[0], sAdaptive, sDeep, sSynthesis, chronic, repOcc, occupancyNow, contributors)
        }
        return result
    }

    private fun anyActive(mid: Double, contributors: List<Contributor>) =
        contributors.any { it.onsetMinutes <= mid && mid <= it.expiryMinutes }

    private fun absSafe(x: Double) = if (x < 0) -x else x

    /** nM concentration → occupancy from a contributor at local minutes. */
    private fun occFrom(c: Contributor, local: Double): Double {
        if (local < 0) return 0.0
        val conc = c.prefactorNanomolar * PKModel.concentration(local, c.ke, c.ka)
        return PKModel.occupancy(conc, c.halfMaxNanomolar)
    }
}
