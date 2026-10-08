package dev.ssha.hotm

import at.hannibal2.skyhanni.config.core.config.Position
import at.hannibal2.skyhanni.config.core.config.gui.GuiPositionEditor
import at.hannibal2.skyhanni.data.GuiEditManager
import at.hannibal2.skyhanni.utils.RenderUtils.renderRenderables
import at.hannibal2.skyhanni.utils.compat.DrawContextUtils
import at.hannibal2.skyhanni.utils.renderables.Renderable
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component

internal class HotmHudIntegration(
    private val settings: () -> AddonSettingsData,
    private val saveSettings: () -> Unit,
    private val lines: () -> List<Component>,
) {
    private val position = settings().hudPosition.let { Position(it.x, it.y, it.scale, false, false) }
    private var openRequested = false
    private var cachedLines: List<Component> = emptyList()
    private var cachedRenderables: List<Renderable> = emptyList()

    private fun renderables(): List<Renderable> {
        val current = lines()
        if (current != cachedLines) {
            cachedLines = current.map { it.copy() }
            cachedRenderables = current.map { checkNotNull(Renderable.fromAny(it)) }
        }
        return cachedRenderables
    }

    fun register() {
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen is GuiPositionEditor) {
                ScreenEvents.afterExtract(screen).register { _, graphics, _, _, _ -> render(graphics, inEditor = true) }
                ScreenEvents.remove(screen).register { persistPosition() }
            }
        }
    }

    fun resetPosition() {
        position.moveTo(8, 8)
        position.scale = 1f
        persistPosition()
    }

    fun requestEditor() {
        // Opening on the next tick prevents chat's submit handler from closing the new screen.
        openRequested = true
    }

    fun tick(minecraft: Minecraft) {
        if (!openRequested) return
        openRequested = false
        registerBounds()
        minecraft.gui.setScreen(GuiPositionEditor(listOf(position), 2))
    }

    private fun registerBounds() {
        val text = renderables()
        GuiEditManager.add(position, "SSHA HOTM Tracker", text.maxOfOrNull { it.width } ?: 1, text.sumOf { it.height })
    }

    fun render(graphics: GuiGraphicsExtractor, inEditor: Boolean = false) {
        val minecraft = Minecraft.getInstance()
        if (!inEditor && (!settings().hudEnabled || minecraft.player == null || GuiEditManager.isInGui())) return
        DrawContextUtils.setContext(graphics)
        try {
            // Same renderer and spacing as TabWidgetDisplay.COMMISSIONS.
            position.renderRenderables(renderables(), extraSpace = -2, posLabel = "SSHA HOTM Tracker")
        } finally {
            DrawContextUtils.clearContext()
        }
    }

    private fun persistPosition() {
        val stored = settings().hudPosition
        stored.x = position.x
        stored.y = position.y
        stored.scale = position.scale
        saveSettings()
    }
}

internal data class HudPositionData(var x: Int = 8, var y: Int = 8, var scale: Float = 1f)
