package dev.ssha.hotm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class HotmProgressTest {
    @Test
    fun `commission xp reductions are not inferred as event xp when completion is confirmed`() {
        assertEquals(0L, HotmProgress.eventXpForReduction(750L, 1, 750L))
        assertEquals(250L, HotmProgress.eventXpForReduction(1_000L, 1, 750L))
        assertEquals(0L, HotmProgress.eventXpForReduction(1_500L, 2, 750L))
        assertEquals(500L, HotmProgress.eventXpForReduction(2_000L, 2, 750L))
        assertEquals(1_000L, HotmProgress.eventXpForReduction(1_000L, 0, 750L))
        assertEquals(0L, HotmProgress.eventXpForReduction(500L, 1, 750L))
        assertEquals(0L, HotmProgress.eventXpForReduction(Long.MAX_VALUE, Int.MAX_VALUE, Long.MAX_VALUE))
    }

    @Test
    fun `manual XP means earned XP within the stated current tier`() {
        val observation = HotmProgress.manualObservation(6, 12_000L)
        assertEquals(HotmProgress.Observation(6, 138_000L), observation)
        assertEquals(184L, HotmProgress.commissionsRemaining(checkNotNull(observation.xpToNextTier), 750L))
        val requirements = listOf(3_000L, 9_000L, 25_000L, 60_000L, 100_000L, 150_000L, 210_000L, 290_000L, 400_000L)
        requirements.forEachIndexed { index, xp ->
            assertEquals(HotmProgress.Observation(index + 1, xp), HotmProgress.manualObservation(index + 1, 0L))
            assertEquals(HotmProgress.Observation(index + 1, 0L), HotmProgress.manualObservation(index + 1, xp))
        }
        assertEquals(HotmProgress.Observation(10, 0L), HotmProgress.manualObservation(10, 0L))
        listOf(0 to 0L, 11 to 0L, 6 to -1L, 6 to 150_001L, 6 to 209_000L, 10 to 1L).forEach { (tier, xp) ->
            assertThrows(IllegalArgumentException::class.java) { HotmProgress.manualObservation(tier, xp) }
        }
    }

    @Test
    fun `XP progress parser requires explicitly displayed next tier value from a HOTM item`() {
        assertEquals(
            HotmProgress.Observation(tier = 6, xpToNextTier = 1_234L),
            HotmProgress.readObservation(listOf("Heart of the Mountain", "Tier 6", "1,234 XP Remaining")),
        )
        assertEquals(
            HotmProgress.Observation(tier = 8, xpToNextTier = null),
            HotmProgress.readObservation(listOf("Heart of the Mountain", "Current Tier: VIII")),
        )
        assertEquals(null, HotmProgress.readObservation(listOf("Heart of the Mountain", "Current XP: 15,000")))
        assertEquals(
            HotmProgress.Observation(tier = null, xpToNextTier = 1_234L),
            HotmProgress.readObservation(listOf("Heart of the Mountain", "XP Progress: 1,766 / 3,000")),
        )
        assertEquals(
            HotmProgress.Observation(tier = null, xpToNextTier = 2_000L),
            HotmProgress.readObservation(listOf("Heart of the Mountain", "2,000 XP to next tier")),
        )
        assertEquals(
            HotmProgress.Observation(tier = 6, xpToNextTier = 2_000L),
            HotmProgress.readObservation(listOf("Heart of the Mountain", "Heart of the Mountain Tier 6", "2,000 XP to next tier")),
        )
        assertEquals(
            HotmProgress.Observation(tier = null, xpToNextTier = 1_234L),
            HotmProgress.readObservation(listOf("Heart of the Mountain", "1,766 / 3,000 HOTM XP")),
        )
        assertEquals(null, HotmProgress.readObservation(listOf("Mining Speed", "Level 1/50", "XP Progress: 1,766 / 3,000")))
    }

    @Test
    fun `progress advancement and tier crossing use the full next-tier table`() {
        assertEquals(HotmProgress.Observation(7, 210_000L), HotmProgress.advance(HotmProgress.Observation(6, 138_000L), 138_000L))
        assertEquals(HotmProgress.Observation(7, 208_900L), HotmProgress.advance(HotmProgress.Observation(6, 100L), 1_200L))
        assertEquals(1_200L, HotmProgress.totalXpGain(HotmProgress.Observation(6, 100L), HotmProgress.Observation(7, 208_900L)))
    }

    @Test
    fun `commission estimate always rounds upward`() {
        assertEquals(3L, HotmProgress.commissionsRemaining(2_001L, 1_000L))
        assertEquals(1L, HotmProgress.commissionsRemaining(1L, 750L))
        assertEquals(0L, HotmProgress.commissionsRemaining(0L, 750L))
        assertEquals(null, HotmProgress.commissionsRemaining(1L, 0L))
    }

    @Test
    fun `only active unfinished commission widget rows are retained`() {
        assertEquals(
            listOf("Mithril Miner: 2/10", "Goblin Slayer: 0/10", "Crystal Collector: 3/5"),
            HotmProgress.activeCommissions(
                listOf("Commissions:", "Mithril Miner: 2/10", "Goblin Slayer: 0/10", "Crystal Collector: 3/5", "Complete Quest: DONE"),
            ),
        )
    }
}
