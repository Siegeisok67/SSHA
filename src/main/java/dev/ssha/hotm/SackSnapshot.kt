package dev.ssha.hotm

import java.util.Locale

internal class SackSnapshot {
    data class Entry(val amount: Long, val limit: Long)
    private val entries = mutableMapOf<String, Entry>()
    operator fun get(id: String): Entry? = entries[id]
    fun read(id: String, lore: List<String>) {
        parse(lore)?.let { entries[id] = it }
    }
    fun change(id: String, delta: Long) {
        entries[id]?.let { entries[id] = it.copy(amount = (it.amount + delta).coerceAtLeast(0L)) }
    }
    fun invalidateUnknown(knownIds: Set<String>) { entries.keys.removeIf { it !in knownIds } }
    fun clear() { entries.clear(); warned.clear() }
    fun overflow(id: String, enabled: Boolean): Boolean {
        val entry = entries[id] ?: return false
        if (!enabled || entry.amount < entry.limit) { warned.remove(id); return false }
        return warned.add(id)
    }
    private val warned = mutableSetOf<String>()

    companion object {
        private val stored = Regex("(?i)^\\s*Stored:\\s*([\\d,.]+[kmb]?)\\s*/\\s*([\\d,.]+[kmb]?)\\s*$")
        fun parse(lore: List<String>): Entry? = lore.firstNotNullOfOrNull { line ->
            val match = stored.matchEntire(line.replace(Regex("§."), "")) ?: return@firstNotNullOfOrNull null
            val amount = number(match.groupValues[1]) ?: return@firstNotNullOfOrNull null
            val limit = number(match.groupValues[2])?.takeIf { it > 0L } ?: return@firstNotNullOfOrNull null
            Entry(amount, limit)
        }
        private fun number(text: String): Long? {
            val clean = text.replace(",", "").lowercase(Locale.ROOT)
            val multiplier = when (clean.lastOrNull()) { 'k' -> 1_000; 'm' -> 1_000_000; 'b' -> 1_000_000_000; else -> 1 }
            val value = (if (multiplier == 1) clean else clean.dropLast(1)).toDoubleOrNull() ?: return null
            val amount = value * multiplier
            return amount.takeIf { it.isFinite() && it >= 0 && it < Long.MAX_VALUE.toDouble() }?.let { kotlin.math.round(it).toLong() }
        }
    }
}
