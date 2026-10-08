package dev.ssha.hotm

object SkyblockerCompatibility {
    @Volatile private var active = false
    internal fun update(enabled: Boolean) { active = enabled }
    @JvmStatic fun suppressCommissions(): Boolean = active && MiningArea.current()
}
