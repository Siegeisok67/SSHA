package dev.ssha.hotm

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CommissionRateTrackerTest {
    @Test
    fun `first sample is a baseline and unchanged rows do not start clock`() {
        val tracker = CommissionRateTracker()
        tracker.observe(listOf("Commissions:", "Mithril Miner: 10%"), 0L)
        tracker.observe(listOf("Mithril Miner: 10%"), 600_000L)
        assertTrue(tracker.isPaused(600_000L))
        assertEquals(0L, tracker.activeMillis)
        assertEquals(null, tracker.perHour())
    }

    @Test
    fun `timer clips idle gap at exactly ninety seconds and resumes without gap`() {
        val tracker = CommissionRateTracker()
        tracker.observe(listOf("Mithril Miner: 0%"), 0L)
        tracker.observe(listOf("Mithril Miner: 1%"), 1_000L)
        tracker.completionMessage("MITHRIL MINER Commission Complete! Visit the King", 31_000L)
        tracker.tick(121_000L)
        assertTrue(tracker.isPaused(121_000L))
        assertEquals(120_000L, tracker.activeMillis)
        assertEquals(30.0, tracker.perHour())
        tracker.tick(900_000L)
        assertEquals(120_000L, tracker.activeMillis)
        tracker.observe(listOf("Mithril Miner: 0%"), 900_000L)
        tracker.observe(listOf("Mithril Miner: 2%"), 901_000L)
        assertFalse(tracker.isPaused(901_000L))
        tracker.tick(911_000L)
        assertEquals(130_000L, tracker.activeMillis)
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
    fun `new rows disappearing rows decreases and first done sample are not completion evidence`() {
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
    fun `chat before first tab sample and temporary widget disappearance cannot double count`() {
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
    fun `disconnect excludes offline time and reset clears entire session`() {
        val tracker = CommissionRateTracker()
        tracker.completionMessage("Mithril Miner Commission Complete!", 0L)
        tracker.suspend(1_000L)
        tracker.tick(600_000L)
        assertEquals(1_000L, tracker.activeMillis)
        tracker.reset()
        assertEquals(0L, tracker.activeMillis)
        assertEquals(0L, tracker.completed)
        assertEquals(null, tracker.perHour())
        assertTrue(tracker.isPaused(600_000L))
    }
}
