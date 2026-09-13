package com.piru.app.data

import com.piru.app.models.DurationProfile
import com.piru.app.models.DurationRange
import com.piru.app.models.RouteOfAdministration
import com.piru.app.models.SubstanceCategory

/** One dose ladder (threshold → heavy) for a substance+route(+form), one source's values. */
data class DoseRange(
    val unit: String = "mg",
    val threshold: Double? = null,
    val light: DurationRange? = null,
    val common: DurationRange? = null,
    val strong: DurationRange? = null,
    val heavy: Double? = null,
    val saltForm: String? = null,
    val saltRank: Int? = null,
    val elementalFraction: Double? = null,
    val isomer: String? = null,
    val isomerDisplayName: String? = null,
    val doseContext: String? = null,
) {
    fun hasAny(): Boolean = threshold != null || light != null || common != null || strong != null || heavy != null
}

/** One route of a substance, with resolved dose + duration for the default form. */
data class RouteData(
    val route: RouteOfAdministration,
    val unit: String,
    val doses: DoseRange,
    val duration: DurationProfile?,
    /** salt/isomer variants, ordered by (isomer, salt_rank, salt, isomer) like iOS makeRoute. */
    val variants: List<DoseRange> = emptyList(),
    /** Per-variant durations keyed "salt|isomer" (NULLs as literal "null" from SQL fold). */
    val variantDurations: Map<String, DurationProfile> = emptyMap(),
)

/** PK numbers for the tolerance/body-level engines (from pk_routes primary route). */
data class PKParams(
    val halfLifeMin: Double?,
    val bioavailabilityFraction: Double?,
    val vdLPerKg: Double?,
    val molarMass: Double?,
    val proteinBindingPct: Double?,
    val tmaxMin: Double?,
    val clearance: Double?,
)

/** The substance read model consumed across the app (subset of iOS SubstanceReadModel). */
data class Substance(
    val id: Long,
    val name: String,             // canonical_name
    val displayName: String,      // display_name (title)
    val substanceUID: String?,
    val category: SubstanceCategory,
    val routes: List<RouteData>,
    val aliases: List<String>,
    val description: String?,
    val mechanism: String?,
    val halfLifeMinutes: Double?,
    val formula: String?,
    val molecularWeight: Double?,
    val cas: String?,
    val regulatoryStatus: String?,
    val isStub: Boolean,
    val popularity: Int,
    val pk: PKParams?,
    /** Zero-order kinetics row (vmax/refWeight/ka), if this substance has one (alcohol). */
    val zeroOrder: Triple<Double, Double, Double>?, // vmax_mg_per_min, ref_weight_kg, ka_per_min
    val durationImplausible: Boolean = false,
    /** tags from DB for the "inhalation" default-route fallback. */
    val tags: List<String> = emptyList(),
    /** Reference dose in mg used for tolerance escalation. */
    val referenceDoseMg: Double? = null,
    val doseRangesByRoute: Map<String, DoseRange> = emptyMap(),
) {
    /** Route ladder lookup with the iOS fallback chain (exact route → default route → any populated). */
    fun doseRangeFor(route: RouteOfAdministration): DoseRange? {
        routes.firstOrNull { it.route == route && it.doses.hasAny() }?.let { return it.doses }
        routes.firstOrNull { it.route == defaultRoute && it.doses.hasAny() }?.let { return it.doses }
        return routes.firstOrNull { it.doses.hasAny() }?.doses
    }

    fun durationFor(
        route: RouteOfAdministration, saltForm: String? = null, isomer: String? = null,
    ): DurationProfile? {
        val r = routes.firstOrNull { it.route == route } ?: routes.firstOrNull { it.route == defaultRoute } ?: return null
        if (saltForm == null && isomer == null) return r.duration
        return r.variants.firstOrNull { it.saltForm == saltForm && it.isomer == isomer }?.let { vr ->
            // variant-specific duration is looked up by the store; fall back to the route default
            vr.let { r.duration }
        } ?: r.duration
    }

    /** Timeline duration — the honest acute profile; null when implausible/missing (port of `timelineDuration`). */
    fun timelineDuration(route: RouteOfAdministration, saltForm: String?, isomer: String?): DurationProfile? {
        if (durationImplausible) return null
        return durationFor(route, saltForm, isomer)?.takeIf { it.estimatedTotalMinutes > 0 }
    }

    val defaultRoute: RouteOfAdministration
        get() = routes.minByOrNull { RouteOfAdministration.rank(it.route) }?.route
            ?: if (tags.any { it.equals("inhalation", ignoreCase = true) }) RouteOfAdministration.Inhalation else RouteOfAdministration.Oral

    val unit: String get() = routes.firstOrNull { it.route == defaultRoute }?.unit
        ?: routes.firstOrNull()?.unit ?: "mg"

    /** Display title: the composite name (e.g. "Esketamine"). */
    val displayTitle: String get() = displayName.ifBlank { name }
}

/** Product strengths for branded chips (Concerta → 18/27/36/54 mg). */
data class ProductStrengths(val product: String, val strengthsMg: List<Double>, val form: String?)
