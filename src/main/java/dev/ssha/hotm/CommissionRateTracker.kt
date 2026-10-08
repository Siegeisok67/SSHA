package dev.ssha.hotm

import java.util.Locale

/** Session-only statistics. All timestamps use the same monotonic millisecond clock. */
internal class CommissionRateTracker {
    companion object { const val IDLE_TIMEOUT_MS = 90_000L }
    var completed: Long = 0L
        private set
    var activeMillis: Long = 0L
        private set
    private var lastTick: Long? = null
    private var lastActivity: Long? = null
    private var previousRows: Map<String, String> = emptyMap()
    private val lastKnownRows = mutableMapOf<String, String>()
    private val countedNames = mutableSetOf<String>()

    fun tick(now: Long) {
        val tick = lastTick
        val activity = lastActivity
        if (tick != null && activity != null) {
            val end = minOf(now, activity + IDLE_TIMEOUT_MS)
            if (end > tick) activeMillis += end - tick
        }
        lastTick = now
    }

    private fun progress(now: Long) {
        tick(now)
        lastActivity = now
    }

    fun isPaused(now: Long): Boolean = lastActivity?.let { now - it >= IDLE_TIMEOUT_MS } ?: true
    fun perHour(): Double? = if (activeMillis > 0L && completed > 0L) completed * 3_600_000.0 / activeMillis else null

    fun observe(rows: List<String>, now: Long) {
        tick(now)
        val current = rows.mapNotNull { row ->
            val text = row.replace(Regex("§."), "").trim()
            if (!text.contains(':')) return@mapNotNull null
            val name = canonical(text.substringBefore(':'))
            if (name == "COMMISSIONS") return@mapNotNull null
            name to text.substringAfter(':').trim()
        }.toMap()
        current.forEach { (name, value) ->
            val previous = previousRows[name]
            if (previous == null) {
                val lastKnown = lastKnownRows[name]
                val old = lastKnown?.let(::progressValue)
                val next = progressValue(value)
                // A new attempt is a lower baseline or a previously DONE row returning active.
                // A first tab sample arriving after chat must not undo completion deduplication.
                if (!isDone(value) && (lastKnown?.let(::isDone) == true ||
                    (old != null && next != null && next < old))) countedNames.remove(name)
                return@forEach
            }
            if (isDone(value) && !isDone(previous)) {
                complete(name, now)
            } else if (!isDone(value)) {
                val old = progressValue(previous)
                val next = progressValue(value)
                if (isDone(previous) || (old != null && next != null && next < old)) countedNames.remove(name)
                if (old != null && next != null && next > old) progress(now)
            }
        }
        // New/vanished rows are baselines, not progress or completions.
        previousRows = current
        lastKnownRows.putAll(current)
    }

    fun completionMessage(message: String, now: Long): Boolean {
        val text = message.replace(Regex("§."), "").trim()
        val match = Regex("(?i)^(.+?)\\s+Commission Complete!").find(text) ?: return false
        return complete(canonical(match.groupValues[1]), now)
    }

    private fun complete(name: String, now: Long): Boolean {
        if (!countedNames.add(name)) return false
        progress(now)
        completed++
        return true
    }

    fun suspend(now: Long) {
        tick(now)
        lastActivity = null
        previousRows = emptyMap()
    }

    fun reset() {
        completed = 0L
        activeMillis = 0L
        lastTick = null
        lastActivity = null
        previousRows = emptyMap()
        countedNames.clear()
        lastKnownRows.clear()
    }

    private fun canonical(name: String): String = name.trim().uppercase(Locale.ROOT)
    private fun isDone(value: String): Boolean = Regex("(?i)\\b(?:DONE|COMPLETE)\\b").containsMatchIn(value)
    private fun progressValue(value: String): Double? =
        Regex("^([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(?:%|/)").find(value)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
}
