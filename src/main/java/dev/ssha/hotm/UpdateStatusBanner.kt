package dev.ssha.hotm

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component

/**
 * Large on-screen update status: a headline (for example "SSHA 1.4.1 -> 1.4.2 available")
 * with a smaller detail line below. Each new status replaces the previous one.
 */
internal class UpdateStatusBanner(private val durationMs: Long = 10_000L) {
    companion object {
        const val GREEN = 0xFF55FF55.toInt()
        const val YELLOW = 0xFFFFFF55.toInt()
        const val RED = 0xFFFF5555.toInt()
        const val GRAY = 0xFFAAAAAA.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val TITLE_SCALE = 2.5f
    }

    private var until = Long.MIN_VALUE
    private var title = ""
    private var detail = ""
    private var titleColor = GRAY

    fun show(now: Long, title: String, detail: String, color: Int) {
        this.title = title
        this.detail = detail
        titleColor = color
        until = now + durationMs
    }

    fun visible(now: Long): Boolean = now < until

    fun render(graphics: GuiGraphicsExtractor, now: Long) {
        if (!visible(now)) return
        val minecraft = Minecraft.getInstance()
        val font = minecraft.font
        val x = minecraft.window.guiScaledWidth / 2f
        val y = minecraft.window.guiScaledHeight * 0.18f
        val headline = Component.literal(title)
        val pose = graphics.pose()
        pose.pushMatrix()
        try {
            pose.translate(x, y)
            pose.scale(TITLE_SCALE)
            graphics.text(font, headline, -font.width(headline) / 2, 0, titleColor, true)
        } finally {
            pose.popMatrix()
        }
        val line = Component.literal(detail)
        pose.pushMatrix()
        try {
            pose.translate(x, y + font.lineHeight * TITLE_SCALE + 6f)
            graphics.text(font, line, -font.width(line) / 2, 0, WHITE, true)
        } finally {
            pose.popMatrix()
        }
    }
}
