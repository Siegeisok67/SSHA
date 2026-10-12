package dev.ssha.hotm

import com.google.gson.GsonBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionStatsPersistenceTest {
    @Test
    fun `session stats survive a JSON round trip and restore into the trackers`() {
        val settings = AddonSettingsData(
            commissionsCompleted = 3L,
            commissionActiveMillis = 120_000L,
            commissionWork = 1.5,
            hotmXpObserved = 4_500L,
            hotmXpMeasuredMillis = 60_000L,
        )
        val gson = GsonBuilder().create()
        val restored = gson.fromJson(gson.toJson(settings), AddonSettingsData::class.java)

        val rate = CommissionRateTracker()
        rate.restore(restored.commissionsCompleted, restored.commissionActiveMillis, restored.commissionWork)
        assertEquals(3L, rate.completed)
        assertEquals(120_000L, rate.activeMillis)
        assertEquals(1.5, rate.observedWork, 0.0001)
        assertEquals(90.0, rate.perHour()!!, 0.0001)

        val xp = HotmXpRateTracker()
        xp.restore(restored.hotmXpObserved, restored.hotmXpMeasuredMillis)
        assertEquals(4_500L, xp.observedXp)
        assertEquals(60_000L, xp.measurementMillis)
        assertTrue(xp.hasObservedXp)
        assertEquals(270_000.0, xp.average(false)!!, 0.0001)
    }
}
