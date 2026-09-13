package com.piru.app

import com.piru.app.models.PKModel
import com.piru.app.models.PDModel
import com.piru.app.engine.TimelineCurveModel
import com.piru.app.engine.ActiveSubstanceState
import com.piru.app.models.DurationProfile
import com.piru.app.models.DurationRange
import com.piru.app.models.SubstanceCategory
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

class EngineSmokeTest {

    @Test fun keFromHalfLife() {
        val ke = PKModel.keFromHalfLifeMinutes(360.0)
        assertTrue(abs(ke - ln(2.0) / 360.0) < 1e-12)
    }

    @Test fun fractionRemainingAtZeroIsOne() {
        val (ke, ka) = PKModel.keFromHalfLifeMinutes(120.0) to PKModel.defaultKa(PKModel.keFromHalfLifeMinutes(120.0))
        assertEquals(1.0, PKModel.fractionRemainingInBody(0.0, ke, ka), 1e-9)
    }

    @Test fun singularityKaEqualsKe() {
        val ke = 0.005; val ka = 0.005
        val expected = (1 + ke * 60) * exp(-ke * 60)
        assertEquals(expected, PKModel.fractionRemainingInBody(60.0, ke, ka), 1e-9)
    }

    @Test fun tmaxMatchesClosedForm() {
        val ke = ln(2.0) / 240.0
        val ka = 6 * ke
        assertEquals(ln(ka / ke) / (ka - ke), PKModel.tmax(ke, ka), 1e-9)
    }

    @Test fun estimateKaRoundTripsTmax() {
        val ke = ln(2.0) / 240.0
        val target = 45.0 // minutes
        val ka = PKModel.estimateKa(target, ke)
        assertEquals(target, PKModel.tmax(ke, ka), 0.5)
    }

    @Test fun zeroOrderEthanolPeakAndClear() {
        val k = PKModel.zeroOrderKinetics(7_000.0 / 60.0 / 6.0, 60.0, 0.5, 0.9, 60.0)
        // 70 g ethanol: peak within an hour, clear roughly ~6 h
        val peak = PKModel.zeroOrderPeakMinutes(70_000.0, k)
        assertTrue(peak in 10.0..240.0)
        val clear = PKModel.zeroOrderClearMinutes(70_000.0, k)
        assertTrue(clear > peak)
        val shapeAtPeak = PKModel.zeroOrderShape(70_000.0, peak, k)!!
        assertEquals(1.0, shapeAtPeak, 1e-6)
    }

    @Test fun occupancyHillSaturates() {
        assertEquals(0.5, PKModel.occupancy(10.0, 10.0, 1.0), 1e-9)
        assertTrue(PKModel.occupancy(1000.0, 10.0, 1.0) > 0.98)
    }

    @Test fun gaddumSingleLigandReducesToO() {
        val o = 0.3
        assertEquals(o, PDModel.competitiveOccupancy(listOf(o)), 1e-9)
    }

    @Test fun responseFractionAtNaiveIsOne() {
        assertEquals(1.0, PDModel.responseFraction(1.0, 0.6, null), 1e-9)
    }

    @Test fun effectCurvePassesAnchors() {
        val duration = DurationProfile(
            onset = DurationRange(30.0, 30.0), comeup = DurationRange(30.0, 30.0),
            peak = DurationRange(120.0, 120.0), offset = DurationRange(240.0, 240.0),
            total = DurationRange(420.0, 420.0),
        )
        val s = ActiveSubstanceState.build("X", "FF0000", 0L, 10.0, "mg", "Oral", duration,
            SubstanceCategory.Stimulant, doseMagnitude = 0.5)!!
        // at comeup end, crest hi = 0.92 for the rising anchor (tachyphylaxis applies after peak only)
        // just check monotone rise to crest then monotone fall
        val crestMin = TimelineCurveModel.effectShape((s.peakEndMinutes + s.comeupEndMinutes) / 2, s)
        assertTrue(crestMin > 0.85)
        assertTrue(TimelineCurveModel.effectShape(s.onsetEndMinutes, s) < crestMin)
        assertTrue(TimelineCurveModel.effectShape(s.offsetEndMinutes, s) < 0.10)
    }

    @Test fun amplitudeCompressionMonotone() {
        val lo = TimelineCurveModel.compressedAmplitude(0.1)
        val hi = TimelineCurveModel.compressedAmplitude(1.0)
        assertTrue(lo > 0.1 && lo < hi && hi == 1.0)
    }
}
