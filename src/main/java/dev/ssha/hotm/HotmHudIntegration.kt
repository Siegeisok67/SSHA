package dev.ssha.hotm

import at.hannibal2.skyhanni.config.core.config.Position
import at.hannibal2.skyhanni.config.core.config.gui.GuiPositionEditor
import at.hannibal2.skyhanni.data.GuiEditManager
import at.hannibal2.skyhanni.data.GuiEditManager.getAbsX
import at.hannibal2.skyhanni.data.GuiEditManager.getAbsY
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

internal class HotmHudIntegration(
    private val settings: () -> AddonSettingsData,
    private val saveSettings: () -> Unit,
    private val lines: () -> List<Component>,
    private val estimates: () -> Map<String, String> = { emptyMap() },
    private val enabled: () -> Boolean = { settings().hudEnabled && MiningArea.current() },
    private val editorEnabled: () -> Boolean = { settings().hudEnabled },
    private val storedPosition: () -> HudPositionData = { settings().hudPosition },
    private val widgetName: String = "SSHA HOTM Tracker",
    private val compactTitle: String? = null,
) {
    companion object { private val widgets = mutableListOf<HotmHudIntegration>() }
    init { widgets.add(this) }

    private val position = storedPosition().let { Position(it.x, it.y, it.scale, false, false) }
    private var openRequested = false
    private var ownedEditor: GuiPositionEditor? = null
    private var cachedLines: List<Component> = emptyList()
    private var cachedPlan: HotmHudPanel.Plan? = null
    private var cachedEstimates: Map<String, String> = emptyMap()
    private val bookIcon by lazy { ItemStack(Items.BOOK) }

    private fun panel(): HotmHudPanel.Plan {
        val current = lines()
        val currentEstimates = if (settings().commissionEta) estimates() else emptyMap()
        if (current != cachedLines || currentEstimates != cachedEstimates || cachedPlan == null) {
            cachedLines = current.map { it.copy() }
            cachedEstimates = currentEstimates
            val font = Minecraft.getInstance().font
            cachedPlan = if (compactTitle == null) HotmHudPanel.plan(current, font.lineHeight, currentEstimates) { font.width(it) }
            else HotmHudPanel.compact(compactTitle, current, font.lineHeight) { font.width(it) }
        }
        return checkNotNull(cachedPlan)
    }

    fun register() {
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen is GuiPositionEditor) {
                ScreenEvents.afterExtract(screen).register { _, graphics, _, _, _ ->
                    if (screen === ownedEditor || editorEnabled()) render(graphics, inEditor = true)
                }
                ScreenEvents.remove(screen).register { persistPosition(); if (screen === ownedEditor) ownedEditor = null }
            }
        }
    }

    fun resetPosition(x: Int = 8, y: Int = 8) {
        position.moveTo(x, y)
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
        val editable = widgets.filter { it === this || it.editorEnabled() }
        editable.forEach { it.registerBounds() }
        val editor = GuiPositionEditor(editable.map { it.position }, 2)
        editable.forEach { it.ownedEditor = editor }
        minecraft.gui.setScreen(editor)
    }

    private fun registerBounds() {
        val panel = panel()
        GuiEditManager.add(position, widgetName, panel.width, panel.height)
    }

    fun render(graphics: GuiGraphicsExtractor, inEditor: Boolean = false) {
        val minecraft = Minecraft.getInstance()
        if (!inEditor && (!enabled() || minecraft.player == null || GuiEditManager.isInGui())) return
        val panel = panel()
        registerBounds()
        val pose = graphics.pose()
        pose.pushMatrix()
        try {
            pose.translate(position.getAbsX().toFloat(), position.getAbsY().toFloat())
            pose.scale(position.effectiveScale)
            panel.operations.forEach { operation ->
                when (operation) {
                    is HotmHudPanel.Operation.Fill -> graphics.fill(
                        operation.x, operation.y, operation.x + operation.width, operation.y + operation.height, operation.color,
                    )
                    is HotmHudPanel.Operation.Text -> graphics.text(
                        minecraft.font, operation.text, operation.x, operation.y, operation.color, operation.shadow,
                    )
                    is HotmHudPanel.Operation.Book -> graphics.item(bookIcon, operation.x, operation.y)
                }
            }
        } finally {
            pose.popMatrix()
        }
    }

    private fun persistPosition() {
        val stored = storedPosition()
        stored.x = position.x
        stored.y = position.y
        stored.scale = position.scale
        saveSettings()
    }
}

internal data class HudPositionData(var x: Int = 8, var y: Int = 8, var scale: Float = 1f)
