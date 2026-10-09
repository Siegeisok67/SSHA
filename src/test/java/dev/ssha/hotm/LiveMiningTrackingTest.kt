package dev.ssha.hotm

import at.hannibal2.skyhanni.data.MiningEventsApi.MiningEventType
import com.google.gson.GsonBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class LiveMiningTrackingTest {
    @Test
    fun `passive events and active reward events do not label ordinary mining powder`() {
        for (type in MiningEventType.entries) {
            val accounting = PowderAccounting()
            accounting.delta(100L, 0L, type)
            accounting.eventEnded(type, 50L)
            val gains = mutableListOf<PowderAccounting.Gain>()
            accounting.drain(1_000L, false) { gain, _ -> gains.add(gain) }
            assertEquals(100L, gains.sumOf { it.amount })
            assertEquals(if (type == MiningEventType.DOUBLE_POWDER) 100L else 0L, gains.sumOf { it.eventAmount }, type.name)
        }
        assertFalse(PowderAccounting.relevant(MiningEventType.BETTER_TOGETHER))
        assertFalse(PowderAccounting.relevant(MiningEventType.GONE_WITH_THE_WIND))
    }

    @Test
    fun `exact reward amount splits merged delta and commission powder is optional`() {
        for (include in listOf(false, true)) {
            val accounting = PowderAccounting()
            accounting.message("Commission Rewards", 0L)
            accounting.message("+400 Mithril Powder", 10L)
            accounting.delta(500L, 50L, MiningEventType.DOUBLE_POWDER)
            val gains = mutableListOf<PowderAccounting.Gain>()
            accounting.drain(1_050L, include) { gain, _ -> gains.add(gain) }
            assertEquals(if (include) 500L else 100L, gains.sumOf { it.amount })
            assertEquals(100L, gains.sumOf { it.eventAmount })
            assertEquals(if (include) 400L else 0L, gains.sumOf { it.commissionAmount })
            accounting.drain(10_000L, include) { _, _ -> fail("No duplicate gain") }
        }
    }

    @Test
    fun `only actual end rewards from rewarding events enter event powder subtotal`() {
        val accounting = PowderAccounting()
        accounting.message("GOBLIN RAID ENDED!", 0L)
        accounting.message("+5,000 Mithril Powder", 10L)
        accounting.delta(5_100L, 50L, null)
        var gain: PowderAccounting.Gain? = null
        accounting.drain(1_050L, false) { value, _ -> gain = value }
        assertEquals(PowderAccounting.Gain(5_100L, 5_000L, 0L), gain)
    }

    @Test
    fun `new switches default off and persist through settings`() {
        val settings = AddonSettingsData()
        assertFalse(settings.pickaxeResetTitle)
        assertFalse(settings.powder.includeCommissionPowder)
        var saves = 0
        val menu = AddonMenuConfig(settings, { saves++ }, {}, {}, {}, {}, {})
        menu.mining.widget.pickaxeResetTitle.set(true)
        menu.grinding.powder.includeCommissionPowder.set(true)
        assertEquals(2, saves)
        val gson = GsonBuilder().create()
        val restored = gson.fromJson(gson.toJson(settings), AddonSettingsData::class.java)
        assertTrue(restored.pickaxeResetTitle)
        assertTrue(restored.powder.includeCommissionPowder)
        val processor = menu.createProcessor()
        assertNotNull(processor.getOptionFromField(WidgetMenuConfig::class.java.getField("pickaxeResetTitle")))
        assertNotNull(processor.getOptionFromField(PowderWidgetMenuConfig::class.java.getField("includeCommissionPowder")))
    }

    @Test
    fun `pickaxe title fires once after server ready and reads modified cooldown`() {
        val tracker = PickaxeResetTracker()
        assertFalse(tracker.observe(listOf("Mining Speed Boost: Available"), 0L))
        assertFalse(tracker.message("You used your Mining Speed Boost Pickaxe Ability!", 1_000L))
        assertFalse(tracker.observe(listOf("Mining Speed Boost: 1m 30s"), 1_000L))
        assertEquals(90_000L, tracker.observedCooldownMillis)
        assertEquals(91_000L, tracker.readyAt)
        assertFalse(tracker.observe(listOf("Mining Speed Boost: 30s"), 61_000L))
        assertTrue(tracker.observe(listOf("Mining Speed Boost: Available"), 91_000L))
        assertFalse(tracker.observe(listOf("Mining Speed Boost: Available"), 92_000L))
        assertFalse(tracker.message("You used your Pickobulus Pickaxe Ability!", 100_000L))
        assertTrue(tracker.message("Your Pickaxe Ability was reset!", 110_000L))
        assertFalse(tracker.message("Your Pickaxe Ability was reset!", 110_001L))
        assertFalse(tracker.message("You used your Mining Speed Boost Pickaxe Ability!", 120_000L))
        assertTrue(tracker.message("Your Mining Speed Boost Pickaxe Ability is now available!", 180_000L))
        assertEquals(90_000L, PickaxeResetTracker.durationMillis("1:30"))
    }

    @Test
    fun `live server reward changes HUD XP and commissions left without menu capture`() {
        val settings = AddonSettingsData(hotmTier = 6, xpToNextTier = 138_000L)
        val addon = HotmAddon()
        var saves = 0
        LiveGameMessages.listen { addon.receiveHotmMessage(it, 1_000L, settings, true, true) { saves++ } }
        LiveGameMessages.receive("    §b+1,650.0 HOTM Experience")
        val lines = HotmHudDisplay.lines(settings).map { it.string }
        assertTrue(lines.any { it.contains("136,350") })
        assertTrue(lines.any { it.contains("~182") })
        assertEquals(1, saves)
        assertEquals(136_350L, settings.xpToNextTier)
    }

    @Test
    fun `reward at tier boundary never yields negative remaining XP`() {
        assertEquals(HotmProgress.Observation(7, 209_250L), HotmProgress.advance(HotmProgress.Observation(6, 0L), 750L))
    }

    @Test
    fun `chat then menu then next chat never double counts or loses live rewards`() {
        val tracker = HotmXpRateTracker()
        val old = HotmProgress.Observation(6, 138_000L)
        tracker.observation(old, 0L)
        tracker.chat("+750 HOTM Experience", 1_000L, 750L)
        assertEquals(HotmProgress.Observation(6, 137_250L), tracker.observation(old, 1_100L))
        tracker.observation(HotmProgress.Observation(6, 137_250L), 2_000L)
        tracker.chat("+750 HOTM Experience", 3_000L, 750L)
        tracker.observation(HotmProgress.Observation(6, 136_500L), 4_000L)
        assertEquals(1_500L, tracker.observedXp)
        assertEquals(HotmProgress.Observation(6, 136_500L), tracker.observation(HotmProgress.Observation(6, 136_500L), 4_100L))
    }

    @Test
    fun `partially refreshed menu cannot undo two already observed rewards`() {
        val tracker = HotmXpRateTracker()
        tracker.observation(HotmProgress.Observation(6, 138_000L), 0L)
        tracker.chat("+750 HOTM Experience", 1_000L, 750L)
        tracker.chat("+750 HOTM Experience", 1_100L, 750L)
        assertEquals(HotmProgress.Observation(6, 136_500L), tracker.observation(HotmProgress.Observation(6, 137_250L), 1_200L))
        assertEquals(HotmProgress.Observation(6, 136_500L), tracker.observation(HotmProgress.Observation(6, 136_500L), 1_300L))
        assertEquals(1_500L, tracker.observedXp)
    }

    @Test
    fun `menu first then matching chat does not advance the HUD twice`() {
        val tracker = HotmXpRateTracker()
        tracker.observation(HotmProgress.Observation(6, 138_000L), 0L)
        val fresh = HotmProgress.Observation(6, 137_250L)
        tracker.observation(fresh, 1_000L)
        tracker.chat("+750 HOTM Experience", 1_100L, 750L)
        assertEquals(0L, tracker.lastReward?.xp)
        assertEquals(fresh, tracker.observation(fresh, 1_200L))
        assertEquals(750L, tracker.observedXp)
    }

    @Test
    fun `XP per hour measures elapsed mining time including claim and travel instead of partial commission time`() {
        val tracker = HotmXpRateTracker()
        tracker.observation(HotmProgress.Observation(6, 138_000L), 0L)
        tracker.tick(0L, true)
        tracker.chat("+750 HOTM Experience", 30_000L, 750L)
        tracker.tick(60_000L, true)
        assertEquals(45_000.0, tracker.average(false))
        tracker.tick(120_000L, true)
        assertEquals(22_500.0, tracker.average(false))
        tracker.tick(121_000L, false)
        tracker.tick(900_000L, false)
        tracker.tick(901_000L, true)
        assertEquals(120_000L, tracker.measurementMillis)
        tracker.tick(961_000L, true)
        assertEquals(15_000.0, tracker.average(false))
        assertTrue(HotmXpRateTracker().average(false) == null)
    }
}
