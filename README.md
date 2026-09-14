# Piru for Android

Android port of [kageroumado/piru](https://github.com/kageroumado/piru) - an iOS
dose journal + pharmacopeia (1,689 substances, all local, every claim cited).
Kotlin + Jetpack Compose, minSdk 28.

## Build

```
# toolchain: JDK 17+ (this build: Microsoft OpenJDK 21), Android SDK (platform 35, build-tools 35.0.0)
gradle :app:assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk (~77 MB debug, unshrunk)
gradle :app:testDebugUnitTest    # 11 JVM engine tests (PK/PD/curves/zero-order)
```

Install: `adb install app-debug.apk`. The 19 MB bundled substance database
(`piru-substances.sqlite`, fetched from the upstream release by `app/src/main/assets/`)
is copied out of assets on first launch.

## What was ported (engine parity)

The iOS app keeps its math in `Shared/Engines/`; those were ported 1:1 to Kotlin:

| iOS (Swift) | Android (Kotlin) | Scope |
|---|---|---|
| `PKModel.swift` | `models/PkModel.kt` | Bateman one-compartment PK, fraction-remaining, Tmax, Newton `estimateKa`, zero-order (ethanol) kinetics, Hill occupancy; `PDModel` leaky integrator, smoothstep gate, response gauge, Gaddum summation |
| `TimelineCurveModel.swift` | `engine/TimelineCurveModel.kt` | phase-shaped effect curves (generalized-Gaussian crest + dome, two-anchor closed-form fit), tachyphylaxis gate, stacked-redose Hill merge, renderedTail window sizing, heavy-threshold band |
| `SubstanceStore.swift` | `data/SubstanceStore.kt` | same two-DB layout, source-priority resolution (user-reorderable, fails-open for prose), windowed dose/duration per (route, salt, isomer, phase), in-memory rank search (exact→prefix→contains→fuzzy Levenshtein), alias/stub resolution, zero-order + half-life + binding reads, interaction rules, enzyme edges, product strengths |
| `ToleranceStore.swift` / `ReceptorClasses.swift` | `engine/ToleranceEngine.kt` + `ReceptorClass` | 30-min occupancy replay over 365 days: acute/adaptive/deep/synthesis leaky layers per mechanism class with the exact iOS constants (τ tables), escalation×chronicity deep gate, regularity factor, medians, recovery bisection |
| `Interactions.swift` | `engine/Interactions.kt` | class-pair rules (99), substance/category class overrides, relevance gating (presence smoothstep × curve overlap sampling), enzyme-capped unsafe layer |
| `BodyLevelsManager.swift` | `engine/BodyLevels.kt` | per-substance body-load trail sampled on a day grid, `fractionRemainingInBody` replay |
| `ActiveSubstanceCalculator.swift` | `engine/ActiveSubstanceCalculator.kt` | "still in your body now" readout (unit-family grouping, 3% floor, supplement exclusion) |
| `TimelineGraphView.swift` | `ui/TimelineGraphView.kt` | Compose Canvas renderer: stacked/overlaid curves, dose markers/bubbles, now-line + dots, tick grid, tap-to-scrub |

Models: `RouteOfAdministration`, `DoseUnit` (mass-family conversion + alias fold incl.
both µ sign spellings), `ByVolumeDosing` (0.789 g/mL, 14 g standard drink),
`DurationProfile` (phase boundaries + `fillingMissingPhases` synthesis),
`SubstanceCategory` (28 categories with iOS colors, tachyphylaxis factors, phase shapes).

## UI (Compose)

Five-tab shell like iOS (Journal / Library / Tools / Insights / Search) with the
floating "Log a dose" action:

- **Journal** - day-grouped session cards with timeline thumbnails, Active Now hero
  (curve strip + % in body), My Meds completion-ring checklist with take-today logging.
- **Quick Log** (bottom sheet) - search-first staging with recent chips, branded
  strength chips (Concerta 18/27/36/54), route/unit editors, dose-level readout
  (threshold/light/common/strong/heavy), alcohol by-volume mode (mL × ABV → g +
  standard drinks), Now / −30 min / −1 h staging, batch commit with session clustering.
- **Library** - category family cards (iOS gradient + accent colors) → substance
  lists → detail sheet: description, dose ladder per route, duration phases,
  mechanism, combinations-to-watch from the rule DB, aliases, chemistry.
- **Insights** - Usage bar chart, body-load-over-time graph (normalized per series),
  tolerance gauges (severity split bar Tachyphylaxis|Tolerance|Deep + response
  fraction + recovery forecast via the PDModel decay inverse), My Meds manager.
- **Tools** - half-life curve calculator (interactive dose/half-life sliders),
  source-priority reorder/enable, About.
- **Reminders** - AlarmManager exact alarms per daily-item time + POST_NOTIFICATIONS
  + boot reschedule.

## Upgrades over the original iOS app

These exist only in this Android port:

1. **Condition search (DSM-lite)** - every indication label on every substance is indexed and
   searchable: search "anxiety", tap, and read what the condition *is* (curated explainers served
   from the API) alongside every substance labeled for it.
2. **Used-for / Effects / Contraindications sections** on each substance card - on/off-label uses,
   side effects by category, and boxed warnings (with "boxed" flags), all source-cited from the DB.
3. **AI translation (user's own Gemini key)** - one tap translates a substance's "Used for" labels
   to Persian / Arabic / Turkish / Spanish / Chinese. The key lives only on the phone and only label
   texts are sent to Google; results are cached.
4. **Accounts + cloud sync** (ahura.site/piru-api) - your dose journal, meds and colors sync across
   phones and survive app deletion; sign up with email/password or log in; push+pull with auto-sync
   after each dose logged.
5. **Friend circle + adherence leaderboard** - add friends by 6-char codes; a weekly leaderboard
   ranks who takes their meds on time the most.
6. **Darooyab.ir lookup** built into every substance card (Persian drug info) - proxied through the
   server, which also crawls and caches darooyab's catalogue continuously.
7. **Body-weight-aware zero-order alcohol scaling** surfaced in the PK calculator.

## Honest gaps vs iOS (later phases)

- Apple Health / Health Connect vitals overlay (heart rate on session timelines).
- Widgets, Live Activity, watch app (no Android equivalents wired yet).
- Alcohol by *volume* logging stores volume/ABV; volume-unit dose conversion from
  the `by_volume_dosing` DB table not wired (falls back to grams ladder).
- Taper interventions, reports/PDF/Markdown export, label-scan camera, NDC/GS1
  barcode resolution, session notes, location, grapefruit/CYP2D6 context flags,
  depot ester `ester_pk` terminal-slope override (uses the 21-day default),
  `product_durations` brand envelopes, custom-substance editor, color picker.
- `tolerance_modulation` and metabolite contributors (DB currently has 0 / small
  edge rows) are stubbed.
- iOS's PK-confidence tiers and `pk_reference_name` single-hop borrowing: Android
  uses the substance's own `pk_routes` oral row.

## Credits

- Android port by **ahura** - [github.com/ahurkkkkkkk](https://github.com/ahurkkkkkkk)
- Original iOS app made by **Lily** - [github.com/pharmacykitty](https://github.com/pharmacykitty)
  (continued by [kageroumado](https://github.com/kageroumado))
- Upstream source: [github.com/kageroumado/piru](https://github.com/kageroumado/piru)

The same credits ship inside the app: Tools tab → About.

## License

GPL-3.0, same as upstream (this is a derivative work).
