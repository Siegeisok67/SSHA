package dev.ssha.hotm

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class HotmHudDisplayTest {
    @Test
    fun `HOTM values use compact labeled rows without changing arithmetic`() {
        val settings = AddonSettingsData(hotmTier = 5, xpToNextTier = 88_000L, extraEventXp = 1_250L)
        assertEquals(
            listOf(
                "§e§lHOTM:",
                " §fTier: §a5 §7→ §a6",
                " §fXP left: §b88,000",
                " §fCommissions left: §a~118",
                " §fXP/commission: §e750",
                " §fEvent/mineshaft XP: §a+1,250",
            ),
            HotmHudDisplay.lines(settings).map { it.string },
        )
    }

    @Test
    fun `commission widget header indentation and component styles are preserved`() {
        val header = Component.literal("Commissions:").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)
        val active = Component.literal(" Mithril Miner: ").withStyle(ChatFormatting.WHITE)
            .append(Component.literal("12%").withStyle(ChatFormatting.GREEN))
        val other = Component.literal(" Goblin Slayer: 2/10").withStyle(ChatFormatting.AQUA)
        val done = Component.literal(" Gemstone Collector: DONE").withStyle(ChatFormatting.GREEN)
        val complete = Component.literal(" Ice Walker Slayer: COMPLETE")
        val rows = HotmHudDisplay.lines(AddonSettingsData(), listOf(header, active, other, done, complete))
        assertSame(header, rows[2])
        assertSame(active, rows[3])
        assertSame(other, rows[4])
        assertEquals(5, rows.size)
        assertEquals(active.siblings.single().style, rows[3].siblings.single().style)
    }

    @Test
    fun `manual mode is a small title marker instead of a full instruction row`() {
        val settings = AddonSettingsData()
        settings.setManualProgress(6, 12_000L)
        val rows = HotmHudDisplay.lines(settings).map { it.string }
        assertEquals("§e§lHOTM: §8(Manual)", rows.first())
        assertEquals(" §fXP left: §b138,000", rows[2])
        assertEquals(" §fCommissions left: §a~184", rows[3])
        assertFalse(rows.any { it.contains("/ssha auto") })
    }

    @Test
    fun `missing XP and max tier remain clear without misleading commission estimates`() {
        assertEquals(listOf("§e§lHOTM:", " §7Open §e/hotm §7to capture XP"), HotmHudDisplay.lines(AddonSettingsData()).map { it.string })
        val max = HotmHudDisplay.lines(AddonSettingsData(hotmTier = 10, xpToNextTier = 0L)).map { it.string }
        assertEquals(listOf("§e§lHOTM:", " §fTier: §a10 §7(Max)", " §aMax tier reached!"), max)
        val threshold = HotmHudDisplay.lines(AddonSettingsData(hotmTier = 5, xpToNextTier = 0L)).map { it.string }
        assertFalse(threshold.any { it.contains("Max") })
    }

    @Test
    fun `fallback commission rows omit bullets and never truncate the active list`() {
        val commissions = (1..5).map { "Commission $it: $it/10" }
        val rows = HotmHudDisplay.lines(AddonSettingsData(activeCommissions = commissions)).map { it.string }
        assertEquals("§e§lCommissions:", rows[2])
        assertEquals(commissions.mapIndexed { index, _ -> " §fCommission ${index + 1}: §e${index + 1}/10" }, rows.drop(3))
        assertFalse(rows.any { it.contains("•") })
    }

    @Test
    fun `hourly rate is shown with idle pause state and can be disabled`() {
        val rate = CommissionRateTracker()
        rate.completionMessage("Mithril Miner Commission Complete!", 0L)
        rate.tick(60_000L)
        val settings = AddonSettingsData()
        assertEquals(" §fCommissions/h: §a60.0", HotmHudDisplay.lines(settings, rate = rate, now = 60_000L)[2].string)
        rate.tick(600_000L)
        assertEquals(" §fCommissions/h: §a40.0 §8(Paused)", HotmHudDisplay.lines(settings, rate = rate, now = 600_000L)[2].string)
        settings.rateEnabled = false
        assertFalse(HotmHudDisplay.lines(settings, rate = rate).any { it.string.contains("Commissions/h") })
    }

    @Test
    fun `finished live widget rows do not revive stale saved commissions`() {
        val settings = AddonSettingsData(activeCommissions = listOf("Mithril Miner: 12%"))
        val rows = HotmHudDisplay.lines(settings, listOf(Component.literal("Commissions:"), Component.literal("Mithril Miner: DONE")))
        assertFalse(rows.any { it.string.contains("Mithril Miner") || it.string.contains("Commissions:") })
    }
}
