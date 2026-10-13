package dev.ssha.hotm

import at.hannibal2.skyhanni.deps.moulconfig.gui.editors.GuiOptionEditorButton
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AddonConfigMenuTest {
    @Test
    fun `chat sender is a purple gradient SSHA tag never SkyHanni`() {
        assertFalse(SshaChat.PREFIX.contains("SkyHanni"))
        assertTrue(SshaChat.PREFIX.startsWith("§5["))
        assertTrue(SshaChat.PREFIX.contains("§d"))
        assertEquals("SSHA", SshaChat.PREFIX.replace(Regex("§."), "").trim().removeSurrounding("[", "]"))
    }

    @Test
    fun `update status shows red-to-green versions once checked`() {
        val addon = HotmAddon()
        assertTrue(addon.updateStatusText("1.4.4", null).contains("press Update"))
        assertEquals("§c1.4.4 §7➔ §a1.4.7", addon.updateStatusText("1.4.4", "1.4.7"))
        assertEquals("§a1.4.4 §7(up to date)", addon.updateStatusText("1.4.4", "1.4.4"))
    }

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
    fun `about version header routes its primary action through the injected update flow`() {
        var checks = 0
        AddonMenuConfig(AddonSettingsData(), {}, {}, {}, {}, {}, {},
            checkForUpdates = { checks++ })
        SshaUpdateMenu.onCheck()
        assertEquals(1, checks)
    }

    @Test
    fun `about install button runs the injected install flow`() {
        var installs = 0
        AddonMenuConfig(AddonSettingsData(), {}, {}, {}, {}, {}, {}, installUpdate = { installs++ })
        SshaUpdateMenu.onInstall()
        assertEquals(1, installs)
    }

    @Test
    fun `version header shows SkyHanni-style big text states`() {
        SshaUpdateMenu.checking = false
        SshaUpdateMenu.installed = "1.4.7"
        SshaUpdateMenu.latest = null
        assertEquals("§f1.4.7", SshaUpdateMenu.versionText())
        assertEquals("Check for Updates", SshaUpdateMenu.primaryLabel())
        assertEquals("Check for Updates", SshaUpdateMenu.checkLabel())
        assertFalse(SshaUpdateMenu.hasUpdate())
        SshaUpdateMenu.latest = "9.9.9"
        assertTrue(SshaUpdateMenu.hasUpdate())
        assertEquals("§c1.4.7➜ §a9.9.9", SshaUpdateMenu.versionText())
        assertEquals("Install Update", SshaUpdateMenu.primaryLabel())
        assertEquals("Check for Updates", SshaUpdateMenu.checkLabel())
        SshaUpdateMenu.latest = "1.4.7"
        assertFalse(SshaUpdateMenu.hasUpdate())
        assertEquals("§a1.4.7", SshaUpdateMenu.versionText())
        assertEquals("Up to date", SshaUpdateMenu.primaryLabel())
        SshaUpdateMenu.checking = true
        assertEquals("§7Checking…", SshaUpdateMenu.versionText())
        assertEquals("Checking…", SshaUpdateMenu.primaryLabel())
        assertEquals("Checking…", SshaUpdateMenu.checkLabel())
        SshaUpdateMenu.checking = false
        SshaUpdateMenu.latest = null
    }

    @Test
    fun `reset all saved grinding data button runs the injected reset`() {
        var resets = 0
        val config = AddonMenuConfig(AddonSettingsData(), {}, {}, {}, {}, {}, {}, resetAllGrinding = { resets++ })
        config.grinding.resetAllSaved.run()
        assertEquals(1, resets)
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
        listOf("currentVersion", "releaseNotes", "issues").forEach { field ->
            val option = processor.getOptionFromField(AboutMenuConfig::class.java.getField(field))
            assertNotNull(option, "About $field")
            assertNotNull(checkNotNull(option).editor)
        }
        listOf("source").forEach { field ->
            val option = processor.getOptionFromField(ExtraMenuConfig::class.java.getField(field))
            assertNotNull(option, "Extra $field")
            assertNotNull(checkNotNull(option).editor)
        }
        val versionOption = checkNotNull(processor.getOptionFromField(AboutMenuConfig::class.java.getField("currentVersion")))
        assertTrue(versionOption.editor is GuiOptionEditorSshaUpdateCheck)
        assertEquals(74, versionOption.editor.height)
        listOf("enabled", "overflowWarning", "showStored", "showSession", "smoothRate", "move").forEach { field ->
            assertNotNull(processor.getOptionFromField(GrindingWidgetMenuConfig::class.java.getField(field)), field)
        }
        assertNull(AboutMenuConfig::class.java.fields.firstOrNull { it.name == "stream" })
        listOf("enabled", "rateEnabled", "tier", "earnedXp", "commissionXp", "applyReward", "applyManual", "move", "recheck", "resetAll", "resetRate", "resetEvents", "resetPosition").forEach { field ->
            val option = processor.getOptionFromField(WidgetMenuConfig::class.java.getField(field))
            assertNotNull(option, field)
            assertNotNull(checkNotNull(option).editor, "$field editor")
        }
    }
}
