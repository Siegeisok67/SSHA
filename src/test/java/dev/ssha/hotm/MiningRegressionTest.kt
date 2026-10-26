package dev.ssha.hotm

import at.hannibal2.skyhanni.data.IslandType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MiningRegressionTest {
    @Test
    fun `rare sack tracker counts only active positive deltas and resets between profiles`() {
        val tracker = RareSackTracker()
        tracker.changes(mapOf(RareSackTracker.MINERAL to 12L), 0L, active = false)
        tracker.changes(mapOf(RareSackTracker.MINERAL to 3L), 100L, active = true)
        tracker.changes(mapOf(RareSackTracker.GEMSTONE to 2L), 200L, active = true)
        assertEquals(3L, tracker.minerals)
        assertEquals(2L, tracker.gemstones)
        assertEquals(null, tracker.perHour(tracker.minerals, 59_000L))
        assertEquals(180.0, tracker.perHour(tracker.minerals, 60_100L))
        tracker.reset()
        assertEquals(0L, tracker.minerals)
        assertEquals(0L, tracker.gemstones)
    }

    @Test
    fun `Cole tracker rejects sack transfers with removals even when target items increase`() {
        val tracker = RareSackTracker()
        tracker.changes(mapOf(RareSackTracker.MINERAL to 4L, "MITHRIL_ORE" to -1L), 100L, active = true)
        tracker.changes(mapOf(RareSackTracker.GEMSTONE to 2L), 200L, active = true, incompleteRemovals = true)
        assertEquals(0L, tracker.minerals)
        assertEquals(0L, tracker.gemstones)
        tracker.changes(mapOf(RareSackTracker.MINERAL to 1L), 300L, active = true)
        assertEquals(1L, tracker.minerals)
    }

    @Test
    fun `powder chest reward stays in session if leaving area during reconciliation`() {
        val tracker = MithrilPowderTracker()
        tracker.eventDrop("PREHISTORIC_EGG", 2L, 0L)
        tracker.suspend(100L)
        assertEquals(2_000.0, tracker.valuation { if (it == "PREHISTORIC_EGG") 1_000.0 else null }.coins)
    }

    @Test
    fun `Cole profit uses session gains and valid instant sell prices without inventing missing value`() {
        val tracker = RareSackTracker()
        assertEquals(RareSackTracker.Valuation(0.0, 0.0), tracker.valuation { null })
        tracker.changes(mapOf(RareSackTracker.MINERAL to 3L, RareSackTracker.GEMSTONE to 2L), 100L, active = true)
        assertEquals(RareSackTracker.Valuation(600.0, 1_000.0), tracker.valuation {
            if (it == RareSackTracker.MINERAL) 200.0 else 500.0
        })
        val partial = tracker.valuation { if (it == RareSackTracker.MINERAL) 200.0 else null }
        assertEquals(600.0, partial.knownTotal)
        assertTrue(partial.partial)
        assertEquals(null, tracker.valuation { Double.NaN }.minerals)
        tracker.reset()
        assertEquals(RareSackTracker.Valuation(0.0, 0.0), tracker.valuation { null })
    }

    @Test
    fun `lobby and other islands never display mining HUD`() {
        assertFalse(MiningArea.allowed(false, IslandType.DWARVEN_MINES, "Glacite Tunnels"))
        assertFalse(MiningArea.allowed(true, IslandType.NONE, null))
        assertFalse(MiningArea.allowed(true, IslandType.HUB, "Village"))
        listOf(IslandType.DWARVEN_MINES, IslandType.CRYSTAL_HOLLOWS, IslandType.MINESHAFT).forEach {
            assertTrue(MiningArea.allowed(true, it, null))
        }
    }

    @Test
    fun `sack lore reads stacked raw and enchanted capacities plus trusted zero`() {
        assertEquals(SackSnapshot.Entry(28_183L, 221_800L), SackSnapshot.parse(listOf("§7Stored: §e28,183§7/221.8k")))
        assertEquals(SackSnapshot.Entry(0L, 80_600L), SackSnapshot.parse(listOf("§7Stored: §80§7/80.6k")))
        assertNull(SackSnapshot.parse(listOf("Capacity: unknown")))
        val sack = SackSnapshot()
        sack.read("MITHRIL_ORE", listOf("Stored: 100/221.8k"))
        sack.change("MITHRIL_ORE", 50L)
        assertEquals(150L, sack["MITHRIL_ORE"]?.amount)
        assertEquals(221_800L, sack["MITHRIL_ORE"]?.limit)
        assertNull(sack["ENCHANTED_MITHRIL"])
        assertTrue(GrindingWidgetSettings().overflowWarning)
    }

    @Test
    fun `fresh sack lore beats cached api values and api covers missing lore`() {
        // A just-opened sack is ground truth; a stale cached API value must not shadow it.
        assertEquals(5_000L, GrindingIntegration.resolveStoredAmount(5_000L, 1_000L, 3_000L, 2_000L))
        assertEquals(3_000L, GrindingIntegration.resolveStoredAmount(null, Long.MIN_VALUE, 3_000L, 2_000L))
        assertNull(GrindingIntegration.resolveStoredAmount(null, Long.MIN_VALUE, null, 2_000L))
        val tracker = GrindingTracker(GrindingMaterial.MITHRIL)
        tracker.stored(
            GrindingIntegration.resolveStoredAmount(5_000L, 1_000L, 3_000L, 2_000L),
            GrindingIntegration.resolveStoredAmount(null, Long.MIN_VALUE, 320L, 2_000L),
            GrindingIntegration.resolveStoredAmount(null, Long.MIN_VALUE, null, 2_000L),
        )
        assertEquals(5_000L + 320L * 160L, tracker.storedRaw)
    }

    @Test
    fun `stale sack lore yields to the live api instead of shadowing it forever`() {
        // Lore read at t=0 is stale ten minutes later: the live API (21) must win over 233,301.
        assertEquals(21L, GrindingIntegration.resolveStoredAmount(233_301L, 0L, 21L, 10 * 60_000L))
        // Unknown lore age keeps the lore-wins fallback; every real open is timestamped.
        assertEquals(233_301L, GrindingIntegration.resolveStoredAmount(233_301L, Long.MIN_VALUE, 21L, 1_000L))
        // Without an API value, even stale lore still covers wiped or never-opened sacks.
        assertEquals(233_301L, GrindingIntegration.resolveStoredAmount(233_301L, 0L, null, 10 * 60_000L))
        assertEquals(5_000L, GrindingIntegration.resolveStoredAmount(5_000L, 0L, 3_000L, GrindingIntegration.LORE_FRESH_MS))
    }

    @Test
    fun `finishing the last commission triggers a refresh even when the widget disappears`() {
        // Widget still visible and one fewer commission: refresh (original behavior).
        assertTrue(AutoHotmRefresh.commissionsFinished(2, 1, widgetActive = true, inMiningArea = true))
        // Last commission done and the widget is gone: this used to skip the refresh entirely.
        assertTrue(AutoHotmRefresh.commissionsFinished(1, 0, widgetActive = false, inMiningArea = true))
        // Nothing finished, or the count grew: no refresh.
        assertFalse(AutoHotmRefresh.commissionsFinished(1, 1, widgetActive = true, inMiningArea = true))
        assertFalse(AutoHotmRefresh.commissionsFinished(1, 2, widgetActive = false, inMiningArea = true))
        // Leaving the mining area hides the widget; that is not a finished commission.
        assertFalse(AutoHotmRefresh.commissionsFinished(1, 0, widgetActive = false, inMiningArea = false))
    }

    @Test
    fun `auto read waits for a readable state instead of being consumed`() {
        val refresh = AutoHotmRefresh()
        refresh.request(0L, 0L)
        // Outside SkyBlock the request must survive, not vanish with no /hotm sent.
        assertFalse(refresh.poll(1_000L, guiOpen = false, enabled = false))
        assertTrue(refresh.poll(2_000L, guiOpen = false, enabled = true))
        assertFalse(refresh.poll(3_000L, guiOpen = false, enabled = true))
    }

    @Test
    fun `a page turn gives page two its own read window`() {
        val refresh = AutoHotmRefresh(readTimeoutMs = 5_000L)
        refresh.request(0L, 0L)
        assertTrue(refresh.poll(1_000L, guiOpen = false, enabled = true))
        refresh.restartWait(4_000L)
        // Without the restart this read gives up at 6,000 ms, before page two has loaded.
        assertFalse(refresh.menuOpened(6_000L, read = false))
        assertTrue(refresh.menuOpened(9_000L, read = false))
    }

    @Test
    fun `page one tier 5 read never lowers a known tier 6`() {
        // Page 1 lists tiers 1-5 only, so a silent read at tier 6 reads 5. That floor must not lower the tier.
        val settings = AddonSettingsData(hotmTier = 6, xpToNextTier = 146_710L)
        settings.applyAutomaticObservation(HotmProgress.Observation(5, null), 1_000L)
        assertEquals(6, settings.hotmTier)
        assertEquals(146_710L, settings.xpToNextTier)
        settings.applyAutomaticObservation(HotmProgress.Observation(6, 146_710L), 2_000L)
        assertEquals(6, settings.hotmTier)
    }

    @Test
    fun `HOTM 10 is complete without a page turn`() {
        assertFalse(HotmProgress.needsSecondPageForXp(HotmProgress.Observation(10, null)))
        assertTrue(HotmProgress.readComplete(HotmProgress.Observation(10, null)))
    }

    @Test
    fun `page two yellow in-progress row gives tier 6 and XP left from the screenshot layout`() {
        // Tier 6 is green (UNLOCKED). Tier 7 is the yellow row being worked (3,290 of 150k).
        val menu = listOf(
            "Tier 6" to listOf("Rewards", "UNLOCKED"),
            "Tier 7" to listOf("Progress: 2.2%", "3,290/150k", "Rewards", "LOCKED"),
            "Tier 8" to listOf("Requires Tier 7", "LOCKED"),
            "Heart of the Mountain" to listOf("Token of the Mountain: 0"),
        )
        assertEquals(HotmProgress.Observation(6, 146_710L), HotmProgress.readMenuObservation(menu))
    }

    @Test
    fun `overflow warns once at capacity rearms and respects toggle`() {
        val sacks = SackSnapshot()
        sacks.read("MITHRIL_ORE", listOf("Stored: 221,799/221.8k"))
        assertFalse(sacks.overflow("MITHRIL_ORE", true))
        sacks.change("MITHRIL_ORE", 1L)
        assertTrue(sacks.overflow("MITHRIL_ORE", true))
        assertFalse(sacks.overflow("MITHRIL_ORE", true))
        sacks.change("MITHRIL_ORE", -1L)
        assertFalse(sacks.overflow("MITHRIL_ORE", true))
        sacks.change("MITHRIL_ORE", 2L)
        assertFalse(sacks.overflow("MITHRIL_ORE", false))
        assertTrue(sacks.overflow("MITHRIL_ORE", true))
        assertFalse(sacks.overflow("UNOPENED_ITEM", true))
    }

    @Test
    fun `material gains commit in one second and pause exactly twenty seconds after gain`() {
        val tracker = GrindingTracker(GrindingMaterial.MITHRIL)
        tracker.changes(mapOf("MITHRIL_ORE" to 100L), 0L)
        tracker.tick(999L)
        assertEquals(0L, tracker.totalRaw)
        tracker.tick(1_000L)
        assertEquals(100L, tracker.totalRaw)
        tracker.changes(mapOf("MITHRIL_ORE" to 100L), 2_000L)
        tracker.tick(3_000L)
        tracker.tick(22_000L)
        assertTrue(tracker.paused(22_000L))
        assertEquals(22_000L, tracker.activeMillis)
        tracker.tick(900_000L)
        assertEquals(22_000L, tracker.activeMillis)
        tracker.changes(mapOf("MITHRIL_ORE" to 10L), 901_000L)
        tracker.tick(902_000L)
        assertEquals(23_000L, tracker.activeMillis)
    }

    @Test
    fun `event ended before reward and reward before ended classify powder once`() {
        val tracker = MithrilPowderTracker()
        val accounting = PowderAccounting()
        accounting.delta(19_850L, 1_000L, null)
        accounting.message("RAFFLE ENDED!", 1_050L)
        accounting.message("+19,850 Mithril Powder", 1_100L)
        accounting.drain(2_000L, false) { gain, at -> tracker.gainPowder(gain, at) }
        assertEquals(19_850L, tracker.powder)
        assertEquals(19_850L, tracker.powderDuringEvents)
        accounting.delta(100L, 2_000L, null)
        accounting.drain(3_000L, false) { gain, at -> tracker.gainPowder(gain, at) }
        assertEquals(19_950L, tracker.powder)
        assertEquals(19_850L, tracker.powderDuringEvents)
    }

    @Test
    fun `powder material gains have no second quarantine and value smoothing limits decay`() {
        val tracker = MithrilPowderTracker()
        tracker.sack(mapOf("MITHRIL_ORE" to 160L), 0L, false)
        tracker.tick(1_000L)
        assertEquals(160L, tracker.mithrilRaw)
        val rate = SmoothedHourlyRate()
        rate.update(1_000.0, 60_000L)
        val initial = checkNotNull(rate.value(1_000.0, 60_000L, true))
        rate.update(1_000.0, 80_000L)
        val smooth = checkNotNull(rate.value(1_000.0, 80_000L, true))
        val raw = checkNotNull(rate.value(1_000.0, 80_000L, false))
        assertTrue(smooth < initial && smooth > raw)
        assertTrue(smooth > initial * 0.94)
    }
}
