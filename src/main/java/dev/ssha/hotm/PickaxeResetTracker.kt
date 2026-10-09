package dev.ssha.hotm

internal class PickaxeResetTracker {
    private var cooling = false
    private var ability: String? = null
    private var usedAt: Long? = null
    var readyAt: Long? = null
        private set
    var observedCooldownMillis: Long? = null
        private set

    fun observe(rows: List<String>, now: Long): Boolean {
        val row = rows.map { it.replace(Regex("§."), "").trim() }.firstOrNull {
            it.substringBefore(':') in abilities
        } ?: return false
        val name = row.substringBefore(':')
        if (ability != null && ability != name) reset()
        ability = name
        val value = row.substringAfter(':').trim()
        if (value.equals("Available", true) || value.equals("Ready", true)) return ready(now)
        val remaining = durationMillis(value) ?: return false
        if (remaining == 0L) return ready(now)
        cooling = true
        readyAt = now + remaining
        usedAt?.let { observedCooldownMillis = now - it + remaining }
        return false
    }

    fun message(message: String, now: Long): Boolean {
        val text = message.replace(Regex("§."), "").trim()
        Regex("^You used your (.+?) Pickaxe Ability!$").matchEntire(text)?.let {
            if (it.groupValues[1] in abilities) {
                ability = it.groupValues[1]
                usedAt = now
                cooling = true
                readyAt = null
            }
        }
        if (Regex("(?i)^Your (?:Pickaxe Ability|(?:Mining Speed Boost|Pickobulus|Gemstone Infusion|Maniac Miner|Vein Seeker)(?: Pickaxe Ability)?) (?:is (?:now )?(?:available|ready)|(?:has been |was )reset)[!.]$").matches(text)) {
            return ready(now)
        }
        return false
    }

    private fun ready(now: Long): Boolean {
        val notify = cooling
        if (notify) usedAt?.let { observedCooldownMillis = (now - it).coerceAtLeast(0L) }
        cooling = false
        usedAt = null
        readyAt = now
        return notify
    }
    fun reset() { cooling = false; ability = null; usedAt = null; readyAt = null; observedCooldownMillis = null }

    companion object {
        private val abilities = setOf("Mining Speed Boost", "Pickobulus", "Gemstone Infusion", "Maniac Miner", "Vein Seeker")
        fun durationMillis(value: String): Long? {
            Regex("^(\\d+):(\\d{2})$").matchEntire(value)?.let {
                val seconds = it.groupValues[2].toLong()
                return if (seconds < 60L) (it.groupValues[1].toLong() * 60L + seconds) * 1_000L else null
            }
            Regex("(?i)^(?:(\\d+)m\\s*)?(?:(\\d+(?:\\.\\d+)?)s)?$").matchEntire(value)?.let {
                if (it.groupValues[1].isEmpty() && it.groupValues[2].isEmpty()) return null
                return ((it.groupValues[1].toLongOrNull() ?: 0L) * 60_000L +
                    (it.groupValues[2].toDoubleOrNull() ?: 0.0) * 1_000L).toLong()
            }
            return null
        }
    }
}
