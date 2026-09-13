package com.piru.app.models

import androidx.compose.ui.graphics.Color

private fun catColor(rgb: Int): Color = Color(rgb or (0xFF shl 24).toInt())

/** Synthesized phase shape weights (onset, comeup, peak, offset). */
data class PhaseShape(val onset: Double, val comeup: Double, val peak: Double, val offset: Double)

private val DEFAULT_SHAPE = PhaseShape(0.08, 0.20, 0.25, 0.55)

/**
 * Substance category — port of Piru/Domain/SubstanceCategory.swift.
 * [rawValue] matches the DB `categories.category` strings exactly.
 * Colors ported from Shared/Assets.xcassets/category/<name>/accent.colorset.
 */
enum class SubstanceCategory(
    val rawValue: String,
    val label: String,
    val lightColor: Color,
    val darkColor: Color,
    /** Acute tolerance (tachyphylaxis) factor applied to the descending limb. */
    val acuteToleranceFactor: Double,
    /** Synthesized phase shape weights when the source profile is incomplete. */
    val synthesizedPhaseShape: PhaseShape,
) {
    Stimulant("Stimulant", "Stimulant", catColor(0xCA7805), catColor(0xCE7B00), 0.75, PhaseShape(0.06, 0.15, 0.20, 0.65)),
    Psychedelic("Psychedelic", "Psychedelic", catColor(0xB368E7), catColor(0x8639B6), 0.0, PhaseShape(0.08, 0.20, 0.30, 0.50)),
    Dissociative("Dissociative", "Dissociative", catColor(0x4494C8), catColor(0x0B689A), 0.25, PhaseShape(0.06, 0.17, 0.27, 0.56)),
    Dysdelic("Dysdelic", "Dysdelic", catColor(0xB574AE), catColor(0x874A82), 0.0, PhaseShape(0.08, 0.20, 0.30, 0.50)),
    Deliriant("Deliriant", "Deliriant", catColor(0x958D6A), catColor(0x6A6242), 0.0, PhaseShape(0.08, 0.20, 0.30, 0.50)),
    Opioid("Opioid", "Opioid", catColor(0xEE5342), catColor(0xBD1711), 0.0, PhaseShape(0.06, 0.16, 0.24, 0.60)),
    Benzodiazepine("Benzodiazepine", "Benzodiazepine", catColor(0x3E81FE), catColor(0x1359D3), 0.0, PhaseShape(0.07, 0.18, 0.30, 0.52)),
    Gabapentinoid("GABAergic", "Gabapentinoid", catColor(0x7B7EF9), catColor(0x5350C7), 0.0, PhaseShape(0.07, 0.18, 0.30, 0.52)),
    Empathogen("Empathogen", "Empathogen", catColor(0xF14E60), catColor(0xBD0537), 0.70, PhaseShape(0.07, 0.18, 0.27, 0.55)),
    Cannabinoid("Cannabinoid", "Cannabinoid", catColor(0x43A047), catColor(0x097319), 0.0, PhaseShape(0.06, 0.18, 0.26, 0.56)),
    Nootropic("Nootropic", "Nootropic", catColor(0x4497AD), catColor(0x096C81), 0.0, DEFAULT_SHAPE),
    Ampakine("AMPAkine", "AMPAkine", catColor(0x659B46), catColor(0x3B6F19), 0.0, DEFAULT_SHAPE),
    Eugeroic("Eugeroic", "Eugeroic", catColor(0xB3832B), catColor(0x986900), 0.20, PhaseShape(0.08, 0.15, 0.35, 0.50)),
    Depressant("Depressant", "Depressant", catColor(0x798CB4), catColor(0x506187), 0.0, PhaseShape(0.07, 0.18, 0.30, 0.52)),
    OrexinAntagonist("OrexinAntagonist", "Orexin Antagonist", catColor(0x8A83CC), catColor(0x60589D), 0.0, PhaseShape(0.07, 0.18, 0.30, 0.52)),
    Antidepressant("Antidepressant", "Antidepressant", catColor(0xA98804), catColor(0xCDA500), 0.0, DEFAULT_SHAPE),
    Antipsychotic("Antipsychotic", "Antipsychotic", catColor(0x2C9D97), catColor(0x008782), 0.0, DEFAULT_SHAPE),
    Analgesic("Analgesic", "Analgesic", catColor(0x9E8868), catColor(0x735E3F), 0.0, PhaseShape(0.06, 0.16, 0.24, 0.60)),
    Antihistamine("Antihistamine", "Antihistamine", catColor(0xB27B8F), catColor(0x855165), 0.0, DEFAULT_SHAPE),
    Cardiovascular("Cardiovascular", "Cardiovascular", catColor(0xE06357), catColor(0xAF332D), 0.0, DEFAULT_SHAPE),
    Antimicrobial("Antimicrobial", "Antimicrobial", catColor(0x5496A3), catColor(0x276B77), 0.0, DEFAULT_SHAPE),
    Gastrointestinal("Gastrointestinal", "Gastrointestinal", catColor(0xBD7F1E), catColor(0xAE7101), 0.0, DEFAULT_SHAPE),
    Respiratory("Respiratory", "Respiratory", catColor(0x5394BA), catColor(0x26688D), 0.0, DEFAULT_SHAPE),
    Endocrine("Endocrine", "Endocrine", catColor(0xAB74D1), catColor(0x7E48A2), 0.0, DEFAULT_SHAPE),
    Immunological("Immunological", "Immunological", catColor(0x508BED), catColor(0x245EBD), 0.0, DEFAULT_SHAPE),
    Supplement("Supplement", "Supplement", catColor(0x4E9F59), catColor(0x1E722F), 0.0, DEFAULT_SHAPE),
    Peptide("Peptide", "Peptide", catColor(0x6291C0), catColor(0x386592), 0.0, DEFAULT_SHAPE),
    Anticonvulsant("Anticonvulsant", "Anticonvulsant", catColor(0x9481C4), catColor(0x6A5696), 0.0, DEFAULT_SHAPE),
    Other("Other", "Other", catColor(0x8B8B90), catColor(0x616165), 0.0, DEFAULT_SHAPE);

    companion object {
        /** DB strings are the rawValue; unknown → null (callers map to Other when needed). */
        fun fromRaw(raw: String?): SubstanceCategory? =
            raw?.let { r -> entries.firstOrNull { it.rawValue.equals(r, ignoreCase = true) } }

        /** Categories shown in the Library browse families. */
        val browsable: List<SubstanceCategory> = entries.filter { it != Other }
    }
}
