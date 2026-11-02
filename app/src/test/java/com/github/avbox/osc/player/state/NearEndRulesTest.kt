package com.github.avbox.osc.player.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearEndRulesTest {

    @Test
    fun disabledEtNeverAdvances() {
        assertFalse(NearEndRules.shouldAdvance(100_000L, 120_000L, 0))
        assertFalse(NearEndRules.shouldAdvance(100_000L, 120_000L, -1))
    }

    @Test
    fun zeroPositionOrDurationNeverAdvances() {
        assertFalse(NearEndRules.shouldAdvance(0L, 120_000L, 30))
        assertFalse(NearEndRules.shouldAdvance(100_000L, 0L, 30))
    }

    @Test
    fun withinWindowAdvances() {
        assertTrue(NearEndRules.shouldAdvance(110_000L, 120_000L, 30))
    }

    @Test
    fun boundaryIsInclusive() {
        assertTrue(NearEndRules.shouldAdvance(90_000L, 120_000L, 30))
    }

    @Test
    fun outsideWindowDoesNotAdvance() {
        assertFalse(NearEndRules.shouldAdvance(89_999L, 120_000L, 30))
    }
}
