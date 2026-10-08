package dev.ssha.hotm

import kotlin.math.exp

internal class SmoothedHourlyRate {
    companion object { const val WARMUP_MS = 60_000L }
    private var value: Double? = null
    private var lastActiveMillis = 0L

    fun update(total: Double, activeMillis: Long) {
        val delta = (activeMillis - lastActiveMillis).coerceAtLeast(0L)
        lastActiveMillis = activeMillis
        if (activeMillis < WARMUP_MS || total <= 0.0) return
        val raw = total * 3_600_000.0 / activeMillis
        value = value?.let { it + (raw - it) * (1.0 - exp(-delta / 90_000.0)) } ?: raw
    }

    fun value(total: Double, activeMillis: Long, smooth: Boolean): Double? {
        if (total <= 0.0) return null
        if (activeMillis < WARMUP_MS) {
            if (smooth) return value
            return null
        }
        return if (smooth) value ?: total * 3_600_000.0 / activeMillis else total * 3_600_000.0 / activeMillis
    }

    fun reset() { value = null; lastActiveMillis = 0L }
}
