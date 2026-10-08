package dev.ssha.hotm

internal data class PowderSettingsData(
    var enabled: Boolean = false,
    var smoothRate: Boolean = true,
    var showProfit: Boolean = true,
    var showMaterials: Boolean = true,
    var showEvents: Boolean = true,
    var position: HudPositionData = HudPositionData(8, 500),
)

internal class MithrilPowderTracker {
    private val mithril = GrindingTracker(GrindingMaterial.MITHRIL)
    private val titanium = GrindingTracker(GrindingMaterial.TITANIUM)
    private val powderRate = SmoothedHourlyRate()
    private val profitRate = SmoothedHourlyRate()
    private var creditedThrough: Long? = null
    private var lastActivity: Long? = null
    private val drops = mutableMapOf<String, Long>()
    private val miningDrops = mutableMapOf<String, Long>()
    private var eventRewardUntil = Long.MIN_VALUE
    private data class PowderGain(val amount: Long, val at: Long, var event: Boolean)
    private val recentPowder = ArrayDeque<PowderGain>()
    private val pendingProfit = mutableListOf<Pair<Long, Map<String, Long>>>()
    var powder: Long = 0L
        private set
    var powderDuringEvents: Long = 0L
        private set
    var activeMillis: Long = 0L
        private set
    val mithrilRaw: Long get() = mithril.totalRaw
    val titaniumRaw: Long get() = titanium.totalRaw

    fun gainPowder(amount: Long, now: Long, duringEvent: Boolean) {
        if (amount <= 0L) return
        activity(now)
        powder += amount
        val event = duringEvent || now <= eventRewardUntil
        recentPowder.addLast(PowderGain(amount, now, event))
        while (recentPowder.isNotEmpty() && now - recentPowder.first().at > 5_000L) recentPowder.removeFirst()
        if (event) powderDuringEvents += amount
    }
    fun sack(deltas: Map<String, Long>, now: Long, incompleteRemovals: Boolean) {
        mithril.changes(deltas, now, incompleteRemovals)
        titanium.changes(deltas, now, incompleteRemovals)
    }
    fun inventory(id: String, amount: Long, now: Long, eventDrop: Boolean) {
        if (amount <= 0L) return
        when {
            GrindingMaterial.MITHRIL.weight(id) > 0L || GrindingMaterial.TITANIUM.weight(id) > 0L -> {
                val change = mapOf(id to amount)
                mithril.changes(change, now)
                titanium.changes(change, now)
            }
            eventDrop && id !in setOf("REFINED_MITHRIL", "REFINED_TITANIUM") -> {
                drops[id] = (drops[id] ?: 0L) + amount
                activity(now)
            }
            else -> {
                miningDrops[id] = (miningDrops[id] ?: 0L) + amount
                activity(now)
            }
        }
    }
    fun eventDrop(id: String, amount: Long, now: Long) {
        if (amount <= 0L) return
        pendingProfit.add(now to mapOf(id to amount))
        activity(now)
    }
    fun eventMessage(message: String, now: Long) {
        val event = Regex("(?i)(?:goblin raid|raffle|mithril gourmand|mining event|2x powder|double powder).*(?:ended|rewards?|received|earned)")
        if (!event.containsMatchIn(message)) return
        eventRewardUntil = now + 5_000L
        // Reward totals can arrive before the event-ended chat/handler. Reclassify only
        // recent deltas, never add powder again or multiply already-buffed API amounts.
        recentPowder.filter { !it.event && now - it.at in 0L..5_000L }.forEach {
            powderDuringEvents += it.amount
            it.event = true
        }
    }
    private fun activity(now: Long) { tick(now); lastActivity = now }
    fun tick(now: Long) {
        while (pendingProfit.isNotEmpty() && now - pendingProfit.first().first >= GrindingTracker.RECONCILE_MS) {
            val (_, batch) = pendingProfit.removeAt(0)
            batch.forEach { (id, amount) -> drops[id] = (drops[id] ?: 0L) + amount }
        }
        creditTime(now)
        val before = mithrilRaw + titaniumRaw
        mithril.tick(now); titanium.tick(now)
        if (mithrilRaw + titaniumRaw > before) {
            val gainedAt = maxOf(mithril.latestCommittedAt, titanium.latestCommittedAt)
            lastActivity = maxOf(lastActivity ?: gainedAt, gainedAt)
            creditTime(now)
        }
        powderRate.update(powder.toDouble(), activeMillis)
    }
    private fun creditTime(now: Long) {
        val activity = lastActivity ?: return
        val start = maxOf(activity, creditedThrough ?: activity)
        val end = minOf(now, activity + CommissionRateTracker.IDLE_TIMEOUT_MS)
        if (end > start) {
            activeMillis += end - start
            creditedThrough = end
        }
    }
    data class Valuation(val coins: Double, val missing: Set<String>)
    fun valuation(price: (String) -> Double?): Valuation {
        val quantities = (drops.keys + miningDrops.keys).associateWith { ((drops[it] ?: 0L) + (miningDrops[it] ?: 0L)).toDouble() }.toMutableMap()
        if (mithrilRaw > 0L) quantities["REFINED_MITHRIL"] = mithrilRaw / 25_600.0
        if (titaniumRaw > 0L) quantities["REFINED_TITANIUM"] = titaniumRaw / 2_560.0
        var coins = 0.0
        val missing = mutableSetOf<String>()
        quantities.forEach { (id, amount) ->
            val value = price(id)?.takeIf { it.isFinite() && it > 0.0 }
            if (value == null) missing.add(id) else coins += amount * value
        }
        return Valuation(coins, missing)
    }
    fun powderPerHour(smooth: Boolean): Double? = powderRate.value(powder.toDouble(), activeMillis, smooth)
    fun profitPerHour(coins: Double, smooth: Boolean): Double? {
        profitRate.update(coins, activeMillis)
        return profitRate.value(coins, activeMillis, smooth)
    }
    fun activeTime(): String {
        val seconds = activeMillis / 1_000L
        return "%02d:%02d:%02d".format(java.util.Locale.US, seconds / 3_600, seconds / 60 % 60, seconds % 60)
    }
    fun paused(now: Long): Boolean = lastActivity?.let { now - it >= CommissionRateTracker.IDLE_TIMEOUT_MS } ?: true
    fun suspend(now: Long) { tick(now); pendingProfit.clear(); recentPowder.clear(); eventRewardUntil = Long.MIN_VALUE; lastActivity = null; mithril.suspend(now); titanium.suspend(now) }
    fun reset() {
        mithril.reset(); titanium.reset(); powderRate.reset(); profitRate.reset(); drops.clear(); miningDrops.clear(); pendingProfit.clear(); recentPowder.clear(); eventRewardUntil = Long.MIN_VALUE
        powder = 0L; powderDuringEvents = 0L; activeMillis = 0L; creditedThrough = null; lastActivity = null
    }
}
