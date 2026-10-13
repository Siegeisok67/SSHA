package dev.ssha.hotm

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AutoHotmRefreshTest {
    @Test
    fun `claim reward text triggers refresh but completion prompts do not`() {
        assertTrue(AutoHotmRefresh.isRewardMessage("+750 HotM Experience"))
        assertTrue(AutoHotmRefresh.isRewardMessage("Commission Rewards"))
        assertFalse(AutoHotmRefresh.isRewardMessage("CITRINE GEMSTONE COLLECTOR Commission Complete! Visit the King to claim your rewards!"))
        assertFalse(AutoHotmRefresh.isRewardMessage("Goblin Raid ended!"))
    }

    @Test
    fun `request sends once after its delay`() {
        val refresh = AutoHotmRefresh()
        refresh.request(0L, 1_500L)
        assertFalse(refresh.poll(1_499L, guiOpen = false))
        assertTrue(refresh.poll(1_500L, guiOpen = false))
        assertFalse(refresh.poll(1_600L, guiOpen = false))
    }

    @Test
    fun `never sends while another GUI is open and sends after it closes`() {
        val refresh = AutoHotmRefresh()
        refresh.request(0L, 0L)
        assertFalse(refresh.poll(100L, guiOpen = true))
        assertTrue(refresh.poll(5_000L, guiOpen = false))
    }

    @Test
    fun `repeated requests within the interval are deferred, not duplicated`() {
        val refresh = AutoHotmRefresh()
        refresh.request(0L, 0L)
        assertTrue(refresh.poll(0L, guiOpen = false))
        refresh.request(1_000L, 0L)
        assertFalse(refresh.poll(1_000L, guiOpen = false))
        assertFalse(refresh.poll(2_999L, guiOpen = false))
        assertTrue(refresh.poll(3_000L, guiOpen = false))
    }

    @Test
    fun `closes only a menu it opened and only after it was read`() {
        val refresh = AutoHotmRefresh()
        refresh.request(0L, 0L)
        assertTrue(refresh.poll(0L, guiOpen = false))
        assertFalse(refresh.menuOpened(100L, read = false))
        assertTrue(refresh.menuOpened(200L, read = true))
        // Once closed, a menu opened by the player is left alone.
        assertFalse(refresh.menuOpened(300L, read = true))
    }

    @Test
    fun `closes an unreadable owned menu at timeout before expiring a menu that never opened`() {
        val refresh = AutoHotmRefresh()
        refresh.request(0L, 0L)
        assertTrue(refresh.poll(0L, guiOpen = false))
        assertFalse(refresh.menuOpened(4_999L, read = false))
        // The controller processes the open menu before expire(now); timeout must still claim closure.
        assertTrue(refresh.menuOpened(5_000L, read = false))
        assertFalse(refresh.awaitingMenu())

        refresh.request(10_000L, 0L)
        assertFalse(refresh.menuOpened(10_000L, read = true))
        assertFalse(refresh.poll(10_000L, guiOpen = false))
    }

    @Test
    fun `late HOTM menu is claimable after read timer expires and gets fresh wait`() {
        val refresh = AutoHotmRefresh()
        refresh.request(0L, 0L)
        assertTrue(refresh.poll(0L, guiOpen = false))
        assertFalse(refresh.expire(5_000L, ownershipWindowMs = 10_000L))
        assertFalse(refresh.awaitingMenu())
        assertTrue(refresh.claimMenu(6_000L, windowMs = 10_000L))
        assertFalse(refresh.menuOpened(10_999L, read = false))
        assertTrue(refresh.menuOpened(11_000L, read = false))
        assertFalse(refresh.claimMenu(11_001L, windowMs = 10_000L))
    }

    @Test
    fun `expires a read that never opens a menu after the ownership window`() {
        val refresh = AutoHotmRefresh()
        refresh.request(0L, 0L)
        assertTrue(refresh.poll(0L, guiOpen = false))
        assertFalse(refresh.expire(5_000L, ownershipWindowMs = 10_000L))
        assertFalse(refresh.claimMenu(10_001L, windowMs = 10_000L))
        assertTrue(refresh.expire(10_001L, ownershipWindowMs = 10_000L))
        assertFalse(refresh.menuOpened(11_000L, read = false))
    }
}
