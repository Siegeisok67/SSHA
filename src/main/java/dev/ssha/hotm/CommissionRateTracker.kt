package dev.ssha.hotm

import java.util.Locale

/** Session-only statistics. All timestamps use the same monotonic millisecond clock. */
internal class CommissionRateTracker {
    companion object { const val IDLE_TIMEOUT_MS = 20_000L }
    var completed: Long = 0L
        private set
    var activeMillis: Long = 0L
        private set
    private var lastTick: Long? = null
    private var lastActivity: Long? = null
    private var lastWorkAt: Long? = null
    private var previousRows: Map<String, String> = emptyMap()
    private val lastKnownRows = mutableMapOf<String, String>()
    private val countedNames = mutableSetOf<String>()
    private val attemptProgress = mutableMapOf<String, Double>()
    var observedWork: Double = 0.0
        private set
    private val smoothed = SmoothedHourlyRate()
    private val etaStart = mutableMapOf<String, Long>()
    private val etaWork = mutableMapOf<String, Double>()
    private val latestFraction = mutableMapOf<String, Double>()
    private val lastProgressAt = mutableMapOf<String, Long>()
    private var nowMillis = 0L

    fun estimates(): Map<String, String> = previousRows.keys.associateWith { name ->
        val elapsed = activeMillis - (etaStart[name] ?: activeMillis)
        val gained = etaWork[name] ?: 0.0
        val fraction = latestFraction[name]
        val sinceProgress = nowMillis - (lastProgressAt[name] ?: nowMillis)
        if (elapsed < 10_000L || gained <= 0.0 || fraction == null || sinceProgress >= IDLE_TIMEOUT_MS) "Collecting…"
        else {
            val seconds = ((1.0 - fraction).coerceAtLeast(0.0) * elapsed / gained / 1_000.0).toLong()
            if (seconds >= 3_600L) String.format(Locale.US, "~%dh %02dm", seconds / 3_600, seconds / 60 % 60)
            else String.format(Locale.US, "~%dm %02ds", seconds / 60, seconds % 60)
        }
    }

    fun tick(now: Long) {
        advanceClock(now)
        smoothed.update(observedWork, activeMillis)
    }

    private fun advanceClock(now: Long) {
        nowMillis = now
        val tick = lastTick
        val activity = lastActivity
        if (tick != null && activity != null) {
            val end = minOf(now, activity + IDLE_TIMEOUT_MS)
            if (end > tick) activeMillis += end - tick
        }
        lastTick = now
    }

    private fun progress(now: Long) {
        val priorTick = lastTick
        if (priorTick != null && lastActivity != null) {
            val end = minOf(now, lastActivity!! + IDLE_TIMEOUT_MS)
            if (end > priorTick) activeMillis += end - priorTick
        }
        lastTick = now
        lastActivity = now
        lastWorkAt = now
        smoothed.update(observedWork, activeMillis)
    }

    fun rewardActivity(now: Long, completion: Boolean) {
        if (completion) progress(now) else tick(now)
    }

    fun isPaused(now: Long): Boolean = lastWorkAt?.let { now - it >= IDLE_TIMEOUT_MS } ?: true
    fun perHour(): Double? = if (activeMillis > 0L && completed > 0L) completed * 3_600_000.0 / activeMillis else null
    fun averagePerHour(smooth: Boolean): Double? = smoothed.value(observedWork, activeMillis, smooth)
    fun averageCompletionsPerHour(smooth: Boolean): Double? = averagePerHour(smooth)
    fun activeTime(): String {
        val seconds = activeMillis / 1_000L
        return String.format(Locale.US, "%02d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60)
    }

    fun observe(rows: List<String>, now: Long) {
        advanceClock(now)
        val current = rows.mapNotNull { row ->
            val text = row.replace(Regex("§."), "").trim()
            if (!text.contains(':')) return@mapNotNull null
            val name = canonical(text.substringBefore(':'))
            if (name == "COMMISSIONS") return@mapNotNull null
            name to text.substringAfter(':').trim()
        }.toMap()
        current.forEach { (name, value) ->
            progressValue(value)?.let { latestFraction[name] = it }
            val previous = previousRows[name]
            if (previous == null) {
                etaStart[name] = activeMillis
                etaWork[name] = 0.0
                // Use the current fraction as the attempt baseline, but only count progress
                // observed from this point onward in the hourly-work numerator.
                attemptProgress.putIfAbsent(name, progressValue(value) ?: 0.0)
                val lastKnown = lastKnownRows[name]
                val old = lastKnown?.let(::progressValue)
                val next = progressValue(value)
                if (!isDone(value) && (lastKnown?.let(::isDone) == true ||
                    (old != null && next != null && next < old))) {
                    countedNames.remove(name)
                    attemptProgress[name] = next ?: 0.0
                    etaStart[name] = activeMillis
                    etaWork[name] = 0.0
                    lastProgressAt[name] = now
                }
                return@forEach
            }
            if (isDone(value) && !isDone(previous)) {
                complete(name, now)
            } else if (!isDone(value)) {
                val old = progressValue(previous)
                val next = progressValue(value)
                if (isDone(previous) || (old != null && next != null && next < old)) {
                    countedNames.remove(name)
                    attemptProgress[name] = next ?: 0.0
                    etaStart[name] = activeMillis
                    etaWork[name] = 0.0
                    lastProgressAt[name] = now
                }
                if (old != null && next != null && next > old && name !in countedNames) {
                    if (isPaused(now)) {
                        etaStart[name] = activeMillis
                        etaWork[name] = 0.0
                    }
                    val gained = next - old
                    val previousWork = attemptProgress[name] ?: old
                    val updatedWork = maxOf(previousWork, next)
                    observedWork += (updatedWork - previousWork).coerceAtLeast(0.0)
                    etaWork[name] = (etaWork[name] ?: 0.0) + gained
                    attemptProgress[name] = updatedWork
                    progress(now)
                    lastProgressAt[name] = now
                    smoothed.update(observedWork, activeMillis)
                }
            }
        }
        previousRows = current
        lastKnownRows.putAll(current)
        smoothed.update(observedWork, activeMillis)
    }

    fun completionMessage(message: String, now: Long): Boolean {
        val text = message.replace(Regex("§."), "").trim()
        val match = Regex("(?i)^(.+?)\\s+Commission Complete!").find(text) ?: return false
        val name = canonical(match.groupValues[1])
        return complete(name, now)
    }

    private fun complete(name: String, now: Long): Boolean {
        if (!countedNames.add(name)) return false
        val previousWork = attemptProgress[name] ?: 0.0
        observedWork += (1.0 - previousWork).coerceAtLeast(0.0)
        attemptProgress[name] = 1.0
        progress(now)
        completed++
        return true
    }

    fun suspend(now: Long) {
        tick(now)
        lastActivity = null
        lastWorkAt = null
        previousRows = emptyMap()
    }

    fun reset() {
        completed = 0L
        activeMillis = 0L
        lastTick = null
        lastActivity = null
        lastWorkAt = null
        previousRows = emptyMap()
        countedNames.clear()
        lastKnownRows.clear()
        attemptProgress.clear()
        observedWork = 0.0
        smoothed.reset()
        etaStart.clear()
        etaWork.clear()
        latestFraction.clear()
        lastProgressAt.clear()
        nowMillis = 0L
    }

    private fun canonical(name: String): String = name.trim().uppercase(Locale.ROOT)
    private fun isDone(value: String): Boolean = Regex("(?i)\\b(?:DONE|COMPLETE)\\b").containsMatchIn(value)
    private fun progressValue(value: String): Double? = HotmHudPanel.commission("Commission: $value")?.percent?.div(100.0)
}
