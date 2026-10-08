package dev.ssha.hotm

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CommissionRateTrackerTest {
    @Test
    fun `first row and unchanged rows never start timer`() {
        val tracker = CommissionRateTracker()
        tracker.observe(listOf("Commissions:", "Mithril Miner: 10%"), 0L)
        tracker.observe(listOf("Mithril Miner: 10%"), 600_000L)
        assertTrue(tracker.isPaused(600_000L))
        assertEquals(0L, tracker.activeMillis)
        assertNull(tracker.perHour())
    }

    @Test
    fun `timer clips idle gap at twenty seconds and resumes only on progress`() {
        val tracker = CommissionRateTracker()
        tracker.observe(listOf("Mithril Miner: 0%"), 0L)
        tracker.observe(listOf("Mithril Miner: 1%"), 1_000L)
        tracker.completionMessage("MITHRIL MINER Commission Complete! Visit the King", 11_000L)
        tracker.tick(31_000L)
        assertTrue(tracker.isPaused(31_000L))
        assertEquals(30_000L, tracker.activeMillis)
        assertEquals(120.0, tracker.perHour()!!, 0.0001)
        tracker.observe(listOf("Mithril Miner: 0%"), 900_000L)
        assertTrue(tracker.isPaused(900_000L))
        tracker.observe(listOf("Mithril Miner: 2%"), 901_000L)
        assertFalse(tracker.isPaused(901_000L))
        tracker.tick(911_000L)
        assertEquals(40_000L, tracker.activeMillis)
    }

    @Test
    fun `done transitions and chat count each commission only once in either order`() {
        val tracker = CommissionRateTracker()
        tracker.observe(listOf("Mithril Miner: 95%", "Goblin Slayer: 9/10"), 0L)
        assertTrue(tracker.completionMessage("§a§lMITHRIL MINER §r§eCommission Complete! Visit the King", 1_000L))
        assertFalse(tracker.completionMessage("MITHRIL MINER Commission Complete!", 1_001L))
        tracker.observe(listOf("Mithril Miner: DONE", "Goblin Slayer: COMPLETE"), 2_000L)
        assertFalse(tracker.completionMessage("Goblin Slayer Commission Complete!", 2_001L))
        assertEquals(2L, tracker.completed)
        tracker.observe(listOf("Mithril Miner: 0%", "Goblin Slayer: 0/10"), 3_000L)
        tracker.observe(listOf("Mithril Miner: DONE", "Goblin Slayer: 1/10"), 4_000L)
        assertEquals(3L, tracker.completed)
    }

    @Test
    fun `new disappearing decreasing and first done samples do not activate timer`() {
        val tracker = CommissionRateTracker()
        tracker.observe(listOf("Mithril Miner: DONE"), 0L)
        tracker.observe(emptyList(), 1_000L)
        tracker.observe(listOf("Goblin Slayer: 9/10"), 2_000L)
        tracker.observe(listOf("Goblin Slayer: 0/10"), 3_000L)
        assertEquals(0L, tracker.completed)
        assertEquals(0L, tracker.activeMillis)
        tracker.observe(listOf("Goblin Slayer: 1/10"), 4_000L)
        tracker.tick(5_000L)
        assertEquals(1_000L, tracker.activeMillis)
    }

    @Test
    fun `temporary widget disappearance cannot double count`() {
        val tracker = CommissionRateTracker()
        tracker.completionMessage("Mithril Miner Commission Complete!", 0L)
        tracker.observe(listOf("Mithril Miner: 99%"), 1_000L)
        tracker.observe(listOf("Mithril Miner: DONE"), 2_000L)
        assertEquals(1L, tracker.completed)
        tracker.observe(emptyList(), 3_000L)
        tracker.observe(listOf("Mithril Miner: 99%"), 4_000L)
        tracker.observe(listOf("Mithril Miner: DONE"), 5_000L)
        assertEquals(2L, tracker.completed)
        tracker.observe(emptyList(), 6_000L)
        tracker.observe(listOf("Mithril Miner: DONE"), 7_000L)
        assertEquals(2L, tracker.completed)
    }

    @Test
    fun `partial progress average and ETA use observed percentage and active duration`() {
        val tracker = CommissionRateTracker()
        tracker.observe(listOf("Mithril Miner: 0%"), 0L)
        tracker.observe(listOf("Mithril Miner: 1%"), 1_000L)
        for (second in 11..61 step 10) tracker.observe(listOf("Mithril Miner: ${second}%"), second * 1_000L)
        assertEquals(60_000L, tracker.activeMillis)
        assertEquals(0.61, tracker.observedWork, 0.0001)
        assertEquals(36.6, tracker.averagePerHour(false)!!, 0.0001)
        assertNotNull(tracker.averagePerHour(true))
        assertEquals("~0m 38s", tracker.estimates()["MITHRIL MINER"])
        tracker.tick(81_000L)
        assertEquals("Collecting…", tracker.estimates()["MITHRIL MINER"])
    }

    @Test
    fun `disconnect excludes offline time and reset clears entire session`() {
        val tracker = CommissionRateTracker()
        tracker.completionMessage("Mithril Miner Commission Complete!", 0L)
        tracker.suspend(1_000L)
        tracker.tick(600_000L)
        assertEquals(1_000L, tracker.activeMillis)
        tracker.reset()
        assertEquals(0L, tracker.activeMillis)
        assertEquals(0L, tracker.completed)
        assertNull(tracker.perHour())
        assertTrue(tracker.isPaused(600_000L))
    }
}
