package dev.ssha.hotm

import at.hannibal2.skyhanni.data.IslandType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MiningRegressionTest {
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
        tracker.gainPowder(19_850L, 1_000L, false)
        tracker.eventMessage("RAFFLE ENDED!", 1_050L)
        assertEquals(19_850L, tracker.powder)
        assertEquals(19_850L, tracker.powderDuringEvents)
        tracker.eventMessage("RAFFLE rewards received", 1_100L)
        assertEquals(19_850L, tracker.powderDuringEvents)
        tracker.gainPowder(100L, 2_000L, false)
        assertEquals(19_950L, tracker.powderDuringEvents)
        tracker.gainPowder(10L, 10_000L, false)
        assertEquals(19_960L, tracker.powder)
        assertEquals(19_950L, tracker.powderDuringEvents)
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
