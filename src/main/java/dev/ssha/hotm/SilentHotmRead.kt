package dev.ssha.hotm

import net.minecraft.client.gui.screens.Screen

/** Lets the Gui.setScreen mixin hand menus from our silent /hotm read to HotmAddon instead of showing them. */
object SilentHotmRead {
    private var claimer: ((Screen) -> Boolean)? = null

    fun install(claimer: (Screen) -> Boolean) {
        this.claimer = claimer
    }

    /** True when [screen] was opened by a silent read and must not be shown. */
    @JvmStatic fun claim(screen: Screen): Boolean = claimer?.invoke(screen) ?: false
}
