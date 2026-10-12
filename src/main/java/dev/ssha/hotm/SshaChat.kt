package dev.ssha.hotm

import at.hannibal2.skyhanni.utils.ChatUtils
import at.hannibal2.skyhanni.utils.SimpleTimeMark
import at.hannibal2.skyhanni.utils.chat.TextHelper.onClick
import at.hannibal2.skyhanni.utils.chat.TextHelper.onHover
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component

/**
 * SSHA's own chat sender. SkyHanni's ChatUtils stamps every message with a gold
 * [SkyHanni] prefix, so plain and clickable messages go through here with a
 * purple-gradient SSHA tag instead. Click actions still reuse SkyHanni's registry.
 */
internal object SshaChat {
    /** Purple gradient sender tag: dark purple brackets around a light-dark SSHA. */
    const val PREFIX = "§5[§dS§5S§dH§5A§5]§r "

    fun chat(message: String) {
        ChatUtils.chat("$PREFIX$message", prefix = false)
    }

    fun clickable(
        message: String,
        hover: String = "§eClick here!",
        oneTimeClick: Boolean = false,
        onClick: () -> Unit,
    ) {
        val text = Component.literal(PREFIX)
            .append(Component.literal(message).withStyle(ChatFormatting.YELLOW))
        text.onClick(SimpleTimeMark.farFuture(), oneTimeClick, onClick)
        text.onHover(hover)
        ChatUtils.chat(text, prefix = false)
    }
}
