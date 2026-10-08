package dev.ssha.hotm

import com.google.gson.GsonBuilder
import com.mojang.brigadier.CommandDispatcher
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.network.chat.Component
import com.mojang.brigadier.exceptions.CommandSyntaxException
import java.lang.reflect.Proxy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

class HotmSettingsTest {
    @TempDir
    lateinit var configDirectory: Path

    @Test
    fun `public command syntax accepts manual progress alongside commission reward`() {
        val dispatcher = CommandDispatcher<FabricClientCommandSource>()
        dispatcher.register(HotmAddon().createCommand())
        val source = Proxy.newProxyInstance(
            FabricClientCommandSource::class.java.classLoader,
            arrayOf(FabricClientCommandSource::class.java),
        ) { _, method, _ -> error("Command parsing must not call ${method.name}") } as FabricClientCommandSource
        listOf("ssha set progress 6 12000", "ssha set hotmxp 750", "ssha status", "ssha auto", "ssha tooltip", "ssha menu", "ssha gui").forEach { command ->
            val parsed = dispatcher.parse(command, source)
            assertTrue(parsed.exceptions.isEmpty(), command)
            assertFalse(parsed.reader.canRead(), command)
            assertTrue(parsed.context.command != null, command)
        }
        listOf("ssha set progress 0 12000", "ssha set progress 11 12000", "ssha set progress 6 -1").forEach { command ->
            val parsed = dispatcher.parse(command, source)
            assertTrue(parsed.exceptions.isNotEmpty() || parsed.reader.canRead(), command)
        }
    }

    @Test
    fun `commands execute with user values save reload and preserve manual override`() {
        val gson = GsonBuilder().setPrettyPrinting().create()
        val file = configDirectory.resolve("ssha-hotm-addon.json")
        var settings = AddonSettingsData(progressFormatVersion = 1)
        val messages = mutableListOf<String>()
        val source = Proxy.newProxyInstance(
            FabricClientCommandSource::class.java.classLoader,
            arrayOf(FabricClientCommandSource::class.java),
        ) { _, method, args ->
            if (method.name == "sendFeedback" || method.name == "sendError") {
                messages.add((checkNotNull(args)[0] as Component).string)
                null
            } else error("Unexpected source call: ${method.name}")
        } as FabricClientCommandSource
        val dispatcher = CommandDispatcher<FabricClientCommandSource>()
        val addon = HotmAddon()
        var guiRequests = 0
        var settingsRequests = 0
        dispatcher.register(addon.createCommand(
            settings = { settings },
            saveSettings = { file.writeText(gson.toJson(settings)) },
            openGui = { guiRequests++ },
            openSettings = { settingsRequests++ },
        ))
        assertEquals(1, dispatcher.execute("ssha set progress 6 12000", source))
        assertTrue(messages.any { it.contains("HotM 6") && it.contains("138,000 XP") && it.contains("184 commissions") })
        settings = gson.fromJson(file.readText(), AddonSettingsData::class.java)
        settings.prepareAfterLoad()
        assertTrue(settings.manualProgress)
        assertFalse(settings.applyAutomaticObservation(HotmProgress.Observation(1, 3_000L), 1_000L))
        assertEquals(1, dispatcher.execute("ssha set hotmxp 1000", source))
        assertEquals(1, dispatcher.execute("ssha", source))
        assertEquals(1, settingsRequests)
        assertEquals(1, dispatcher.execute("ssha status", source))
        assertTrue(messages.last().contains("138 commissions"))
        assertThrows(CommandSyntaxException::class.java) { dispatcher.execute("ssha set progress 6 209000", source) }
        assertEquals(138_000L, settings.xpToNextTier)
        assertEquals(1, dispatcher.execute("ssha set progress 6 13000", source))
        assertEquals(137_000L, settings.xpToNextTier)
        assertEquals(1, dispatcher.execute("ssha tooltip", source))
        assertTrue(messages.last().contains("hover"))
        assertEquals(1, dispatcher.execute("ssha menu", source))
        assertTrue(messages.last().contains("No hovering needed"))
        val screenshotItem = "Tier 6" to listOf("Progress: 12%", "━━ 12,000/100k", "Rewards:", "+110 SkyBlock XP", "LOCKED")
        addon.recordMenu(listOf(screenshotItem))
        assertEquals(1, dispatcher.execute("ssha menu", source))
        assertTrue(messages.any { it.contains("12,000/100k") })
        assertTrue(messages.last().contains("tier=5") && messages.last().contains("88000"))
        addon.recordHoveredTooltip(screenshotItem.first, screenshotItem.second)
        assertEquals(1, dispatcher.execute("ssha tooltip", source))
        assertTrue(messages.last().contains("LOCKED"))
        assertEquals(1, dispatcher.execute("ssha auto", source))
        assertFalse(settings.manualProgress)
        assertEquals(null, settings.hotmTier)
        assertEquals(1_000L, settings.commissionXp)
        assertEquals(1, dispatcher.execute("ssha gui", source))
        assertEquals(1, guiRequests)
    }

    @Test
    fun `HUD position and scale persist alongside manual progress`() {
        val settings = AddonSettingsData(hudPosition = HudPositionData(-120, 80, 1.4f), hudEnabled = false, rateEnabled = false)
        settings.setManualProgress(6, 12_000L)
        val gson = GsonBuilder().create()
        val file = configDirectory.resolve("ssha-hotm-addon.json")
        file.writeText(gson.toJson(settings))
        val restored = gson.fromJson(file.readText(), AddonSettingsData::class.java)
        restored.prepareAfterLoad()
        assertEquals(HudPositionData(-120, 80, 1.4f), restored.hudPosition)
        assertFalse(restored.hudEnabled)
        assertFalse(restored.rateEnabled)
        assertEquals(6, restored.hotmTier)
        assertEquals(138_000L, restored.xpToNextTier)
    }

    @Test
    fun `manual override survives JSON save reload and blocks automatic samples`() {
        val settings = AddonSettingsData(commissionXp = 1_000L, pendingCommissionCompletions = 2)
        settings.setManualProgress(6, 12_000L)
        val gson = GsonBuilder().setPrettyPrinting().create()
        val file = configDirectory.resolve("ssha-hotm-addon.json")
        file.writeText(gson.toJson(settings))
        val restored = gson.fromJson(file.readText(), AddonSettingsData::class.java)
        restored.prepareAfterLoad()
        assertTrue(restored.manualProgress)
        assertEquals(6, restored.hotmTier)
        assertEquals(138_000L, restored.xpToNextTier)
        assertEquals(1_000L, restored.commissionXp)
        assertEquals(0, restored.pendingCommissionCompletions)
        assertFalse(restored.applyAutomaticObservation(HotmProgress.Observation(1, 1_234L), 1_000L))
        assertEquals(6, restored.hotmTier)
        assertEquals(138_000L, restored.xpToNextTier)
        restored.setManualProgress(6, 13_000L)
        assertEquals(137_000L, restored.xpToNextTier)
        assertEquals(0L, restored.extraEventXp)
    }

    @Test
    fun `invalid manual input leaves previous progress intact`() {
        val settings = AddonSettingsData()
        settings.setManualProgress(6, 12_000L)
        val previous = settings.copy()
        assertThrows(IllegalArgumentException::class.java) { settings.setManualProgress(6, 209_000L) }
        assertEquals(previous, settings)
    }

    @Test
    fun `resuming automatic capture clears manual baseline and retains preferences`() {
        val settings = AddonSettingsData(commissionXp = 900L, extraEventXp = 500L)
        settings.setManualProgress(6, 12_000L)
        settings.resumeAutomaticProgress()
        assertFalse(settings.manualProgress)
        assertEquals(null, settings.hotmTier)
        assertEquals(null, settings.xpToNextTier)
        assertEquals(900L, settings.commissionXp)
        assertTrue(settings.applyAutomaticObservation(HotmProgress.Observation(6, 130_000L), 1_000L))
        assertEquals(500L, settings.extraEventXp)
    }

    @Test
    fun `old unreliable captured rank is discarded without resetting commission reward`() {
        val restored = GsonBuilder().create().fromJson(
            """{"commissionXp":1000,"hotmTier":1,"xpToNextTier":1234,"hudEnabled":true}""",
            AddonSettingsData::class.java,
        )
        restored.prepareAfterLoad()
        assertEquals(null, restored.hotmTier)
        assertEquals(null, restored.xpToNextTier)
        assertEquals(1_000L, restored.commissionXp)
        assertEquals(1, restored.progressFormatVersion)
        assertEquals(HudPositionData(), restored.hudPosition)
    }

    @Test
    fun `only same-tier reductions count toward event XP`() {
        val settings = AddonSettingsData(hotmTier = 6, xpToNextTier = 138_000L)
        settings.applyAutomaticObservation(HotmProgress.Observation(6, 137_000L), 1_000L)
        assertEquals(0L, settings.extraEventXp)
        settings.pendingCommissionCompletions = 1
        settings.lastCommissionCompletionAtMs = 1_000L
        settings.applyAutomaticObservation(HotmProgress.Observation(6, 136_000L), 2_000L)
        assertEquals(0L, settings.extraEventXp)
        settings.applyAutomaticObservation(HotmProgress.Observation(7, 130_000L), 3_000L)
        assertEquals(0L, settings.extraEventXp)
        settings.applyAutomaticObservation(HotmProgress.Observation(null, 120_000L), 4_000L)
        assertEquals(0L, settings.extraEventXp)
        settings.applyAutomaticObservation(HotmProgress.Observation(8, null), 5_000L)
        assertEquals(null, settings.xpToNextTier)
    }
}
