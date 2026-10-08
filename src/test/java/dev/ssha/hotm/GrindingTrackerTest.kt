package dev.ssha.hotm

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

class GrindingTrackerTest {
    @Test
    fun `material factors and product conversion thresholds are distinct`() {
        assertEquals(25_600L, GrindingMaterial.GOLD.rawPerProduct)
        assertEquals(25_600L, GrindingMaterial.DIAMOND.rawPerProduct)
        assertEquals(25_600L, GrindingMaterial.MITHRIL.rawPerProduct)
        assertEquals(2_560L, GrindingMaterial.TITANIUM.rawPerProduct)
        assertEquals(25_600L, GrindingMaterial.UMBER.rawPerProduct)
        assertEquals(25_600L, GrindingMaterial.TUNGSTEN.rawPerProduct)
        assertEquals(3L, GrindingMaterial.JEWELS.defaultThreshold)
        assertEquals(160L, GrindingMaterial.GOLD.weight("ENCHANTED_GOLD"))
        assertEquals(0L, GrindingMaterial.GOLD.weight("ENCHANTED_GOLD_INGOT"))
    }

    @Test
    fun `pending positive changes commit only after quarantine and crafted products do not count`() {
        val tracker = GrindingTracker(GrindingMaterial.GOLD)
        tracker.changes(mapOf("GOLD_INGOT" to 160L, "ENCHANTED_GOLD" to 1L, "ENCHANTED_GOLD_BLOCK" to 1L), 1_000L)
        assertEquals(0L, tracker.totalRaw)
        tracker.tick(1_999L)
        assertEquals(0L, tracker.totalRaw)
        tracker.tick(2_000L)
        assertEquals(320L, tracker.totalRaw)
        assertEquals(1_000L, tracker.activeMillis)
    }

    @Test
    fun `negative sack deltas discard pending gain and incomplete removals invalidate`() {
        val tracker = GrindingTracker(GrindingMaterial.DIAMOND)
        tracker.changes(mapOf("DIAMOND" to 100L), 0L)
        tracker.changes(mapOf("ENCHANTED_DIAMOND" to -1L), 500L)
        tracker.tick(20_000L)
        assertEquals(0L, tracker.totalRaw)
        tracker.changes(mapOf("DIAMOND" to 100L), 21_000L)
        tracker.changes(mapOf("OTHER" to 1L), 21_500L, incompleteRemovals = true)
        tracker.tick(40_000L)
        assertEquals(0L, tracker.totalRaw)
    }

    @Test
    fun `stored raw equivalent includes enchanted and compacted contents`() {
        val gold = GrindingTracker(GrindingMaterial.GOLD)
        gold.stored(raw = 100L, enchanted = 10L, compacted = 2L)
        assertEquals(100L + 1600L + 51_200L, gold.storedRaw)
        gold.stored(raw = null, enchanted = 1L, compacted = 0L)
        assertEquals(160L, gold.storedRaw)
        gold.stored(raw = null, enchanted = null, compacted = null)
        assertNull(gold.storedRaw)
    }

    @Test
    fun `compact popup is default off only on fresh threshold crossing and rearms`() {
        val tracker = GrindingTracker(GrindingMaterial.GOLD)
        val options = GrindingWidgetSettings(enabled = true, compactAt = 20_160L)
        tracker.stored(20_159L, 0L)
        assertFalse(tracker.shouldWarn(options))
        options.compactPopup = true
        assertFalse(tracker.shouldWarn(options))
        tracker.stored(20_159L, 0L)
        tracker.stored(20_160L, 0L)
        assertTrue(tracker.shouldWarn(options))
        assertFalse(tracker.shouldWarn(options))
        tracker.stored(10L, 0L)
        assertFalse(tracker.shouldWarn(options))
        tracker.stored(20_160L, 0L)
        assertFalse(tracker.shouldWarn(options, allowWarning = false))
        tracker.stored(20_159L, 0L)
        tracker.stored(20_160L, 0L)
        assertTrue(tracker.shouldWarn(options))
    }

    @Test
    fun `daily commission bonus and commission baseline are not event xp`() {
        val tracker = HotmXpRateTracker()
        tracker.observation(HotmProgress.Observation(6, 138_000L))
        tracker.completion(0L)
        assertTrue(tracker.chat("+1,650 HOTM Experience", 1_000L, 750L, LocalDate.of(2026, 10, 8)))
        assertEquals(1_650L, tracker.observedXp)
        assertEquals(1, tracker.dailyBonuses)
        assertEquals(900L, tracker.dailyBonusXp)
        assertEquals(HotmProgress.Observation(6, 136_350L), run {
            tracker.applyLiveRewardToBaseline(1_650L)
            HotmProgress.advance(HotmProgress.Observation(6, 138_000L), 1_650L)
        })
        assertEquals(1_650L, tracker.lastReward?.xp)
        assertTrue(tracker.lastReward?.commission == true)
        assertFalse(tracker.lastReward?.event == true)
    }

    @Test
    fun `menu observations and matching chat do not double count while unknown remainder is not event`() {
        val tracker = HotmXpRateTracker()
        tracker.observation(HotmProgress.Observation(6, 138_000L), 0L)
        tracker.chat("+750 HOTM Experience", 1_000L, 750L, claiming = true)
        tracker.observation(HotmProgress.Observation(6, 137_250L), 2_000L)
        assertEquals(750L, tracker.observedXp)
        val reward = tracker.chat("+1,200 HOTM Experience", 3_000L, 750L, claiming = false)
        assertTrue(reward)
        assertFalse(tracker.lastReward?.commission ?: true)
        assertFalse(tracker.lastReward?.event ?: true)
    }

    @Test
    fun `powder tracker uses active time and reports missing Bazaar items honestly`() {
        val tracker = MithrilPowderTracker()
        tracker.gainPowder(100L, 0L, duringEvent = true)
        tracker.tick(60_000L)
        assertEquals(20_000L, tracker.activeMillis)
        assertEquals(100L, tracker.powderDuringEvents)
        tracker.sack(mapOf("ENCHANTED_MITHRIL" to 1L), 61_000L, false)
        tracker.tick(71_000L)
        tracker.inventory("TITANIUM_ORE", 160L, 72_000L, eventDrop = false)
        tracker.tick(82_000L)
        assertEquals(160L, tracker.mithrilRaw)
        assertEquals(160L, tracker.titaniumRaw)
        val value = tracker.valuation { if (it == "REFINED_MITHRIL") 1000.0 else null }
        assertEquals(6.25, value.coins, 0.0001)
        assertTrue("REFINED_TITANIUM" in value.missing)
    }

    @Test
    fun `chest reward parsing only accepts known event rewards`() {
        assertEquals("PREHISTORIC_EGG" to 2L, GrindingIntegration.parsePowderChestReward("    Prehistoric Egg x2"))
        assertEquals("GEMSTONE_POWDER" to 150L, GrindingIntegration.parsePowderChestReward("    Gemstone Powder x150"))
        assertNull(GrindingIntegration.parsePowderChestReward("    HOTM Experience"))
        assertNull(GrindingIntegration.parsePowderChestReward("    Random Valuable Item x4"))
        assertTrue(GrindingIntegration.jewelIsland(at.hannibal2.skyhanni.data.IslandType.MINESHAFT))
        assertTrue(GrindingIntegration.jewelIsland(at.hannibal2.skyhanni.data.IslandType.DWARVEN_MINES))
        assertFalse(GrindingIntegration.jewelIsland(at.hannibal2.skyhanni.data.IslandType.CRYSTAL_HOLLOWS))
    }
}
