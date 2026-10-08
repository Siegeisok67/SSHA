package dev.ssha.hotm

import at.hannibal2.skyhanni.deps.moulconfig.gui.editors.GuiOptionEditorButton
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AddonConfigMenuTest {
    @Test
    fun `menu toggles apply fields and buttons invoke the intended actions`() {
        val settings = AddonSettingsData(hotmTier = 6, xpToNextTier = 138_000L, extraEventXp = 50L)
        var saves = 0
        var moves = 0
        var positions = 0
        var rateResets = 0
        var rechecks = 0
        val messages = mutableListOf<String>()
        val config = AddonMenuConfig(settings, { saves++ }, { moves++ }, { positions++ }, { rateResets++ }, { rechecks++ }, { messages.add(it) })
        val widget = config.mining.widget
        val processor = config.createProcessor()
        fun click(field: String) {
            val option = checkNotNull(processor.getOptionFromField(WidgetMenuConfig::class.java.getField(field)))
            (option.editor as GuiOptionEditorButton).onClick()
        }
        assertEquals("12000", widget.earnedXp)
        assertEquals(MenuHotmTier.TIER_6, widget.tier)
        widget.enabled.set(false)
        widget.rateEnabled.set(false)
        assertFalse(settings.hudEnabled)
        assertFalse(settings.rateEnabled)
        assertEquals(2, saves)
        widget.commissionXp = "1,000"
        click("applyReward")
        assertEquals(1_000L, settings.commissionXp)
        widget.earnedXp = "13,000"
        click("applyManual")
        assertTrue(settings.manualProgress)
        assertEquals(137_000L, settings.xpToNextTier)
        click("move")
        click("resetPosition")
        click("resetRate")
        click("recheck")
        assertEquals(1, moves)
        assertEquals(1, positions)
        assertEquals(1, rateResets)
        assertEquals(1, rechecks)
        click("resetEvents")
        assertEquals(0L, settings.extraEventXp)
        settings.extraEventXp = 500L
        click("resetAll")
        assertFalse(settings.manualProgress)
        assertEquals(null, settings.hotmTier)
        assertEquals(null, settings.xpToNextTier)
        assertEquals(0L, settings.extraEventXp)
        assertEquals(2, rateResets)
        assertEquals(1_000L, settings.commissionXp)
        assertFalse(settings.hudEnabled)
    }

    @Test
    fun `invalid menu inputs do not save or change settings`() {
        val settings = AddonSettingsData()
        settings.setManualProgress(6, 12_000L)
        var saves = 0
        val messages = mutableListOf<String>()
        val widget = AddonMenuConfig(settings, { saves++ }, {}, {}, {}, {}, { messages.add(it) }).mining.widget
        listOf("0", "-1", "1000000001", "nope").forEach {
            widget.commissionXp = it
            widget.applyReward.run()
        }
        listOf("-1", "150001", "nope").forEach {
            widget.earnedXp = it
            widget.applyManual.run()
        }
        assertEquals(0, saves)
        assertEquals(750L, settings.commissionXp)
        assertEquals(138_000L, settings.xpToNextTier)
        assertEquals(7, messages.size)
        assertTrue(messages.all { it.startsWith("§c") })
    }

    @Test
    fun `bundled SkyHanni config processor discovers widget accordion and controls`() {
        val config = AddonMenuConfig(AddonSettingsData(), {}, {}, {}, {}, {}, {})
        val processor = config.createProcessor()
        assertTrue(processor.isFinalized)
        listOf("enabled", "rateEnabled", "tier", "earnedXp", "commissionXp", "applyReward", "applyManual", "move", "recheck", "resetAll", "resetRate", "resetEvents", "resetPosition").forEach { field ->
            val option = processor.getOptionFromField(WidgetMenuConfig::class.java.getField(field))
            assertNotNull(option, field)
            assertNotNull(checkNotNull(option).editor, "$field editor")
        }
    }
}
