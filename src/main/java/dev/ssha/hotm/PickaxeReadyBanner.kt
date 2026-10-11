package dev.ssha.hotm

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component

/**
 * A one-shot green banner. SkyHanni titles render at a fixed large size, so SSHA draws its own
 * smaller one. Showing it again while visible does not restart it.
 */
internal class PickaxeReadyBanner(private val durationMs: Long = 3_000L) {
    companion object {
        const val TEXT = "Pickaxe Ability Ready"
        private const val SCALE = 1.5f
        private const val GREEN = 0xFF55FF55.toInt()
    }

    private var until = Long.MIN_VALUE

    fun show(now: Long) {
        if (now >= until) until = now + durationMs
    }

    fun visible(now: Long): Boolean = now < until

    fun render(graphics: GuiGraphicsExtractor, now: Long) {
        if (!visible(now)) return
        val minecraft = Minecraft.getInstance()
        val text = Component.literal(TEXT)
        val halfWidth = minecraft.font.width(text) / 2
        val pose = graphics.pose()
        pose.pushMatrix()
        try {
            pose.translate(minecraft.window.guiScaledWidth / 2f, minecraft.window.guiScaledHeight * 0.25f)
            pose.scale(SCALE)
            graphics.text(minecraft.font, text, -halfWidth, 0, GREEN, true)
        } finally {
            pose.popMatrix()
        }
    }
}
