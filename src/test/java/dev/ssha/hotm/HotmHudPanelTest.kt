package dev.ssha.hotm

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HotmHudPanelTest {
    private fun width(component: Component): Int = component.string.replace(Regex("§."), "").length * 6
    private fun plan(lines: List<Component>) = HotmHudPanel.plan(lines, 9, measure = ::width)

    @Test
    fun `panel uses teal bold heading rounded dark background and book progress rows`() {
        val settings = AddonSettingsData(hotmTier = 5, xpToNextTier = 88_000L)
        val lines = HotmHudDisplay.lines(settings, listOf(Component.literal("Commissions:"), Component.literal(" Mithril Miner: 12%")))
        val panel = plan(lines)
        val text = panel.operations.filterIsInstance<HotmHudPanel.Operation.Text>()
        val fills = panel.operations.filterIsInstance<HotmHudPanel.Operation.Fill>()
        assertEquals("HOTM & Commissions", text.first().text.string)
        assertEquals(HotmHudPanel.ACCENT, text.first().color)
        assertTrue(text.first().text.style.isBold)
        assertFalse(text.first().shadow)
        assertEquals(HotmHudPanel.Operation.Fill(1, 0, panel.width - 2, panel.height, HotmHudPanel.BACKGROUND), fills.first())
        assertEquals(0, panel.operations.filterIsInstance<HotmHudPanel.Operation.Book>().size)
        assertTrue(text.any { it.text.string == "Mithril Miner §712.0%" && it.x == 6 })
        assertTrue(text.any { it.text.string.contains("88,000") })
        assertTrue(text.any { it.text.string.contains("~118") })
        assertFalse(fills.any { it.height > 1 && it.color != HotmHudPanel.BACKGROUND && it.width > 1 })
        assertInside(panel)
    }

    @Test
    fun `percent and fraction inputs give bounded bars and unknown text is not fake progress`() {
        assertEquals(12.0, HotmHudPanel.commission("§fMithril Miner: §a12%")?.percent)
        assertEquals(25.0, HotmHudPanel.commission("Mithril Miner: 2,500 / 10,000")?.percent)
        assertEquals(100.0, HotmHudPanel.commission("Goblin Slayer: DONE")?.percent)
        assertEquals(100.0, HotmHudPanel.commission("Goblin Slayer: 110%")?.percent)
        assertEquals(null, HotmHudPanel.commission("Goblin Slayer: 1/0")?.percent)
        assertEquals(null, HotmHudPanel.commission("Goblin Slayer: Collect crystals")?.percent)
        assertEquals(null, HotmHudPanel.commission("Commissions:"))
        assertEquals(null, HotmHudPanel.commission("invalid"))
        val panel = plan(listOf(Component.literal("HOTM:"), Component.literal("Commissions:"), Component.literal("Goblin Slayer: 2/10")))
        assertTrue(panel.operations.any { it is HotmHudPanel.Operation.Text && it.text.string == "Goblin Slayer §720.0%" })
        assertInside(panel)
    }

    @Test
    fun `manual missing XP max tier and paused hourly state remain visible`() {
        val settings = AddonSettingsData()
        settings.setManualProgress(6, 12_000L)
        val rate = CommissionRateTracker()
        rate.completionMessage("Mithril Miner Commission Complete!", 0L)
        rate.tick(90_000L)
        val panel = plan(HotmHudDisplay.lines(settings, rate = rate, now = 90_000L))
        val texts = panel.operations.filterIsInstance<HotmHudPanel.Operation.Text>().map { it.text.string }
        assertEquals("HOTM (Manual)", texts.first())
        assertTrue(texts.any { it.contains("138,000") })
        assertTrue(texts.any { it.contains("Collecting") && it.contains("Paused") })
        val missing = plan(HotmHudDisplay.lines(AddonSettingsData()))
        assertTrue(missing.operations.any { it is HotmHudPanel.Operation.Text && it.text.string.contains("/hotm") })
        val max = plan(HotmHudDisplay.lines(AddonSettingsData(hotmTier = 10, xpToNextTier = 0L)))
        assertTrue(max.operations.any { it is HotmHudPanel.Operation.Text && it.text.string.contains("Max tier") })
        assertInside(panel)
        assertInside(missing)
        assertInside(max)
    }

    @Test
    fun `long names values and multiple commissions fit measured editor bounds`() {
        val lines = listOf(Component.literal("HOTM:"), Component.literal("Commissions:")) + (1..6).map {
            Component.literal("Very Long Gemstone Commission Number $it: ${if (it % 2 == 0) "0.12%" else "100,000,000/200,000,000"}")
        }
        val panel = plan(lines)
        assertEquals(0, panel.operations.filterIsInstance<HotmHudPanel.Operation.Book>().size)
        assertInside(panel)
        val unknown = plan(listOf(Component.literal("HOTM:"), Component.literal("Commissions:"), Component.literal("Quest: Collect crystals")))
        assertFalse(unknown.operations.any { it is HotmHudPanel.Operation.Fill && it.color == HotmHudPanel.progressColor(0.0) })
        assertInside(unknown)
    }

    private fun assertInside(panel: HotmHudPanel.Plan) {
        assertTrue(panel.width > 0 && panel.height > 0)
        panel.operations.forEach { operation ->
            when (operation) {
                is HotmHudPanel.Operation.Fill -> {
                    assertTrue(operation.width >= 0 && operation.height >= 0, operation.toString())
                    assertTrue(operation.x >= 0 && operation.y >= 0 && operation.x + operation.width <= panel.width && operation.y + operation.height <= panel.height, operation.toString())
                }
                is HotmHudPanel.Operation.Text -> assertTrue(operation.x + width(operation.text) <= panel.width && operation.y + 9 <= panel.height, operation.toString())
                is HotmHudPanel.Operation.Book -> assertTrue(operation.x + 16 <= panel.width && operation.y + 16 <= panel.height, operation.toString())
            }
        }
    }
}
