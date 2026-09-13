package com.piru.app.models

/** Port of Shared/RouteOfAdministration.swift (rawValues preserved verbatim). */
enum class RouteOfAdministration(val rawValue: String, val displayName: String) {
    Oral("oral", "Oral"),
    Sublingual("sublingual", "Sublingual"),
    Buccal("buccal", "Buccal"),
    Insufflation("insufflation", "Insufflation"),
    Inhalation("inhalation", "Inhalation"),
    Intravenous("intravenous", "Intravenous"),
    Intramuscular("intramuscular", "Intramuscular"),
    Subcutaneous("subcutaneous", "Subcutaneous"),
    Transdermal("transdermal", "Transdermal"),
    Rectal("rectal", "Rectal"),
    Other("other", "Other");

    companion object {
        /** Mirror of `from(string:)` - lowercase + trim, aliases folded. */
        fun fromString(s: String): RouteOfAdministration {
            val k = s.trim().lowercase()
            return when (k) {
                "oral", "oral_ir", "oral_er", "oral (benzedrex)", "oral (pure)" -> Oral
                "sublingual" -> Sublingual
                "buccal", "buccally", "pouch", "snus" -> Buccal
                "insufflated", "insufflation", "insufflated (pure)", "intranasal", "nasal" -> Insufflation
                "inhaled", "inhalation", "smoked", "vapourized", "vaporized" -> Inhalation
                "intravenous", "iv" -> Intravenous
                "intramuscular", "im" -> Intramuscular
                "subcutaneous", "sc", "subq" -> Subcutaneous
                "transdermal", "topical" -> Transdermal
                "rectal", "plugged" -> Rectal
                else -> Other
            }
        }

        /** Rank by enum order (oral first) for route lists. */
        fun rank(r: RouteOfAdministration): Int = r.ordinal
    }
}

/** Port of Piru/Domain/DoseUnit.swift - mass family only convertible. */
object DoseUnit {
    private val TO_MG = mapOf("µg" to 0.001, "mg" to 1.0, "g" to 1000.0)

    /** Canonical fold of input spellings (both MICRO SIGN U+00B5 and GREEK MU U+03BC accepted). */
    fun canonical(raw: String): String? {
        val k = raw.trim().lowercase()
        return when (k) {
            "µg", "μg", "mcg", "ug", "microgram", "micrograms", "mcgs", "ugs" -> "µg"
            "mg", "mgs", "milligram", "milligrams" -> "mg"
            "g", "gs", "gram", "grams", "gm" -> "g"
            else -> null // bare spellings ONLY: "mg (salt)", "µg/kg", "mcg/hr" must fail
        }
    }

    /** Convert [amount] from [from] to [to]; null when either side is not a mass unit. */
    fun convert(amount: Double, from: String, to: String): Double? {
        if (from.trim().lowercase() == to.trim().lowercase()) return amount // identity for any unit (mL/IU included)
        val cf = canonical(from) ?: return null
        val ct = canonical(to) ?: return null
        if (cf == ct) return amount
        return amount * (TO_MG[cf] ?: return null) / (TO_MG[ct] ?: return null)
    }

    /** True when [unit] is mass-convertible (unit family "mass"). */
    fun isMassUnit(unit: String): Boolean = convert(1.0, unit, "mg") != null
}

/** Port of Shared/ByVolumeDosing.swift constants we need. */
object ByVolumeDosing {
    const val ETHANOL_DENSITY_G_PER_ML = 0.789
    const val US_STANDARD_DRINK_GRAMS = 14.0

    fun grams(volumeML: Double, abv: Double): Double {
        if (!volumeML.isFinite() || !abv.isFinite() || volumeML <= 0 || abv <= 0) return 0.0
        return volumeML * (abv / 100) * ETHANOL_DENSITY_G_PER_ML
    }

    fun standardDrinks(grams: Double): Double =
        if (!grams.isFinite() || grams <= 0) 0.0 else grams / US_STANDARD_DRINK_GRAMS
}

/** Port of Piru/Domain/DurationProfile.swift. */
data class DurationRange(val min: Double, val max: Double) {
    val midpoint: Double get() = (min + max) / 2
}

data class DurationProfile(
    val onset: DurationRange? = null,
    val comeup: DurationRange? = null,
    val peak: DurationRange? = null,
    val offset: DurationRange? = null,
    val afterglow: DurationRange? = null,
    val total: DurationRange? = null,
) {
    data class Boundaries(
        val onsetEnd: Double, val comeupEnd: Double, val peakEnd: Double,
        val offsetEnd: Double, val afterglowEnd: Double,
    )

    /** Phase boundaries as running sums of midpoints. */
    val phaseBoundaries: Boundaries
        get() {
            val onsetEnd = onset?.midpoint ?: 0.0
            val comeupEnd = onsetEnd + (comeup?.midpoint ?: 0.0)
            val peakEnd = comeupEnd + (peak?.midpoint ?: 0.0)
            val offsetEnd = peakEnd + (offset?.midpoint ?: 0.0)
            val afterglowEnd = offsetEnd + (afterglow?.midpoint ?: 0.0)
            return Boundaries(onsetEnd, comeupEnd, peakEnd, offsetEnd, afterglowEnd)
        }

    /** Offset end is the floor; a shorter explicit total is ignored (incoherent data). */
    val estimatedTotalMinutes: Double
        get() = maxOf(total?.midpoint ?: 0.0, phaseBoundaries.offsetEnd)

    /**
     * Fill missing comeup/peak/offset from the category's synthesized phase shape so a
     * curve can be drawn; complete profiles are untouched. Port of `fillingMissingPhases`.
     */
    fun fillingMissingPhases(category: SubstanceCategory): DurationProfile {
        val totalMin = total?.midpoint ?: return this
        if (totalMin <= 0) return this
        val shape = category.synthesizedPhaseShape
        val onsetMid = onset?.midpoint ?: totalMin * shape.onset
        val presentSum = (comeup?.midpoint ?: 0.0) + (peak?.midpoint ?: 0.0) + (offset?.midpoint ?: 0.0)
        val budget = totalMin - onsetMid - presentSum
        val needs = comeup == null || peak == null || offset == null
        if (!needs || budget <= totalMin * 0.1) return this
        val wC = if (comeup == null) shape.comeup else 0.0
        val wP = if (peak == null) shape.peak else 0.0
        val wO = if (offset == null) shape.offset else 0.0
        val wSum = wC + wP + wO
        if (wSum <= 0) return this
        fun filled(w: Double) = DurationRange(budget * w / wSum, budget * w / wSum)
        return copy(
            onset = onset ?: DurationRange(onsetMid, onsetMid),
            comeup = comeup ?: filled(wC).takeIf { wC > 0 },
            peak = peak ?: filled(wP).takeIf { wP > 0 },
            offset = offset ?: filled(wO).takeIf { wO > 0 },
        )
    }
}
