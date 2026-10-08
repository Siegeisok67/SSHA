package dev.ssha.hotm

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

class HotmXpRateTrackerTest {
    private val today = LocalDate.of(2026, 10, 8)

    @Test
    fun `completion reward immediately advances cached progress without reopening hotm`() {
        val settings = AddonSettingsData(hotmTier = 6, xpToNextTier = 138_000L)
        settings.applyLiveReward(HotmXpRateTracker.Reward(750L, commission = true, event = false))
        assertEquals(HotmProgress.Observation(6, 137_250L), HotmProgress.Observation(settings.hotmTier, settings.xpToNextTier))
        settings.applyLiveReward(HotmXpRateTracker.Reward(138_000L, commission = true, event = false))
        assertEquals(HotmProgress.Observation(7, 209_250L), HotmProgress.Observation(settings.hotmTier, settings.xpToNextTier))
        assertEquals(0L, settings.extraEventXp)
    }

    @Test
    fun `daily rewards use greater than three hundred excess after a pending commission`() {
        val tracker = HotmXpRateTracker()
        tracker.completion(0L)
        assertTrue(tracker.chat("+1,051 HOTM Experience", 100L, 750L, today))
        assertEquals(301L, tracker.dailyBonusXp)
        assertEquals(1, tracker.dailyBonuses)
        assertTrue(tracker.lastReward!!.commission)
        assertFalse(tracker.lastReward!!.event)
    }

    @Test
    fun `unknown xp is observed and applied without guessing commission or event attribution`() {
        val tracker = HotmXpRateTracker()
        assertTrue(tracker.chat("+950 HOTM Experience", 100L, 750L, today))
        assertEquals(0, tracker.dailyBonuses)
        assertFalse(tracker.lastReward!!.commission)
        assertFalse(tracker.lastReward!!.event)
        assertEquals(950L, tracker.observedXp)
        val settings = AddonSettingsData(hotmTier = 6, xpToNextTier = 138_000L)
        settings.applyLiveReward(tracker.lastReward!!)
        assertEquals(137_050L, settings.xpToNextTier)
    }

    @Test
    fun `mining event residual beyond configured commission reward is counted separately`() {
        val tracker = HotmXpRateTracker()
        assertTrue(tracker.chat("+950 HOTM Experience", 1_000L, 750L, today, activeEvent = true))
        assertTrue(tracker.lastReward!!.commission)
        assertEquals(200L, tracker.lastReward!!.eventXp)
    }

    @Test
    fun `stale completion cannot attribute unrelated xp`() {
        val tracker = HotmXpRateTracker()
        tracker.completion(0L)
        assertTrue(tracker.chat("+750 HOTM Experience", 10 * 60_000L + 1L, 750L, today))
        assertFalse(tracker.lastReward!!.commission)
        assertFalse(tracker.lastReward!!.event)
    }

    @Test
    fun `menu progress and chat updates within next five seconds reconcile once`() {
        val tracker = HotmXpRateTracker()
        tracker.observation(HotmProgress.Observation(6, 138_000L), 0L)
        tracker.observation(HotmProgress.Observation(6, 136_500L), 1_000L)
        assertEquals(1_500L, tracker.observedXp)
        tracker.chat("+1,500 HOTM Experience", 2_000L, 750L, today)
        assertEquals(1_500L, tracker.observedXp)
        tracker.chat("+200 HOTM Experience", 3_000L, 750L, today)
        assertEquals(1_700L, tracker.observedXp)
    }

    @Test
    fun `tier crossing counts actual XP and tier decrease establishes fresh baseline`() {
        val tracker = HotmXpRateTracker()
        tracker.observation(HotmProgress.Observation(6, 100L), 0L)
        tracker.observation(HotmProgress.Observation(7, 208_900L), 10_000L)
        assertEquals(1_200L, tracker.observedXp)
        tracker.observation(HotmProgress.Observation(5, 80_000L), 20_000L)
        assertEquals(1_200L, tracker.observedXp)
    }
}
