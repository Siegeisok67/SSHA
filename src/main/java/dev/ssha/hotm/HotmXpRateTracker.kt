package dev.ssha.hotm

import java.time.LocalDate
import java.time.ZoneOffset

internal class HotmXpRateTracker {
    data class Reward(val xp: Long, val commission: Boolean, val event: Boolean, val eventXp: Long = if (event) xp else 0L)
    var observedXp: Long = 0L
        private set
    var dailyBonuses: Int = 0
        private set
    var dailyBonusXp: Long = 0L
        private set
    var hasObservedXp: Boolean = false
        private set
    var lastReward: Reward? = null
        private set
    private var baseline: HotmProgress.Observation? = null
    private var lastMenuObservation: HotmProgress.Observation? = null
    private var chatSinceBaseline = 0L
    private var menuCredit = 0L
    private var menuCreditAt = Long.MIN_VALUE
    private var commissionRewardUntil = Long.MIN_VALUE
    private var eventRewardUntil = Long.MIN_VALUE
    private val pendingClaims = ArrayDeque<Long>()
    private var bonusDate: LocalDate? = null
    private companion object { const val CLAIM_EXPIRY_MS = 10 * 60_000L; const val MAX_PENDING_CLAIMS = 100 }
    private val measuredRate = SmoothedHourlyRate()
    var measurementMillis: Long = 0L
        private set
    private var lastTick: Long? = null
    private var measuring = false
    private var wasEligible = false

    fun tick(now: Long, eligible: Boolean) {
        val previous = lastTick
        if (measuring && eligible && wasEligible && previous != null && now > previous) measurementMillis += now - previous
        lastTick = now
        wasEligible = eligible
        measuredRate.update(observedXp.toDouble(), measurementMillis)
    }

    fun start(now: Long) {
        if (!measuring) { measuring = true; lastTick = now }
    }

    fun completion(now: Long) {
        if (now < 0L) return
        expireClaims(now)
        if (pendingClaims.size >= MAX_PENDING_CLAIMS) pendingClaims.removeFirst()
        pendingClaims.addLast(now)
    }

    fun chat(
        message: String,
        now: Long,
        baseReward: Long,
        date: LocalDate = LocalDate.now(ZoneOffset.UTC),
        claiming: Boolean = false,
    ): Boolean {
        lastReward = null
        expireClaims(now)
        if (date != bonusDate) { bonusDate = date; dailyBonuses = 0; dailyBonusXp = 0L }
        val text = message.replace(Regex("§."), "").trim()
        if (Regex("(?i)commission.*(?:rewards|claimed)|(?:rewards|claimed).*commission").containsMatchIn(text) &&
            !text.contains("visit", true)) commissionRewardUntil = now + 5_000L
        if (Regex("(?i)(?:goblin raid|raffle|mithril gourmand|mining event|mineshaft|corpse).*(?:rewards?|received|earned|ended)").containsMatchIn(text)) {
            eventRewardUntil = now + 5_000L
        }
        val match = Regex("(?i)^\\+?([0-9][0-9,]*(?:\\.[0-9]+)?)\\s+(?:HOTM|HEART OF THE MOUNTAIN)\\s+(?:EXPERIENCE|EXP|XP)[!.]?$")
            .matchEntire(text) ?: return false
        val xp = match.groupValues[1].replace(",", "").toBigDecimalOrNull()?.let {
            try { it.longValueExact() } catch (_: ArithmeticException) { null }
        }?.takeIf { it > 0L } ?: return false
        start(now)
        if (now - menuCreditAt !in 0L..5_000L) menuCredit = 0L
        val alreadySeen = minOf(menuCredit, xp)
        menuCredit -= alreadySeen
        observedXp += xp - alreadySeen
        hasObservedXp = true
        if (baseline != null) {
            chatSinceBaseline += xp - alreadySeen
            baseline = HotmProgress.advance(checkNotNull(baseline), xp - alreadySeen)
        }
        // Exact base/daily reward amounts plus an unclaimed completion are supporting evidence,
        // not a blanket 60-second window that mislabels unrelated mining-event rewards.
        val excess = xp - baseReward
        val expectedReward = xp >= baseReward
        val correlatedCompletion = pendingClaims.isNotEmpty() && expectedReward
        // Never treat the mere presence of a mining event as evidence that an XP reward
        // came from it. Only an explicit nearby event/mineshaft reward message qualifies.
        val eventContext = now <= eventRewardUntil
        val commissionContext = !eventContext && (
            claiming || now <= commissionRewardUntil || correlatedCompletion
        )
        if (commissionContext && correlatedCompletion) pendingClaims.removeFirst()
        if (commissionContext && (correlatedCompletion || claiming || now <= commissionRewardUntil) && excess > 300L && dailyBonuses < 4) {
            dailyBonuses++
            dailyBonusXp += excess
        }
        val eventXp = when {
            eventContext -> xp
            else -> 0L
        }
        lastReward = Reward(xp - alreadySeen, commissionContext, eventContext, minOf(eventXp, xp - alreadySeen))
        commissionRewardUntil = Long.MIN_VALUE
        eventRewardUntil = Long.MIN_VALUE
        // The chat line is an authoritative HOTM XP delta even when its source is unknown.
        // Attribution affects the event/daily counters, never whether live progress is applied.
        return true
    }

    fun observation(next: HotmProgress.Observation, now: Long = 0L): HotmProgress.Observation {
        if (next.tier == null || next.xpToNextTier == null) return next
        start(now)
        // Minecraft can keep the same old lore for many ticks after a live reward.
        // Do not let repeated reads overwrite the reward-adjusted baseline.
        if (next == lastMenuObservation) return baseline ?: next
        val previous = lastMenuObservation
        if (previous?.tier != null && next.tier < previous.tier) {
            clearBaseline()
        } else if (previous != null) {
            val gain = HotmProgress.totalXpGain(previous, next)
            val unseen = (gain - chatSinceBaseline).coerceAtLeast(0L)
            chatSinceBaseline = (chatSinceBaseline - gain).coerceAtLeast(0L)
            if (unseen > 0L) {
                observedXp += unseen
                menuCredit += unseen
                menuCreditAt = now
                hasObservedXp = true
            }
        }
        lastMenuObservation = next
        baseline = HotmProgress.advance(next, chatSinceBaseline)
        return checkNotNull(baseline)
    }

    private fun expireClaims(now: Long) {
        while (pendingClaims.isNotEmpty() && now >= pendingClaims.first() && now - pendingClaims.first() > CLAIM_EXPIRY_MS) {
            pendingClaims.removeFirst()
        }
    }

    fun clearBaseline() { baseline = null; lastMenuObservation = null; chatSinceBaseline = 0L; menuCredit = 0L; menuCreditAt = Long.MIN_VALUE }
    fun average(smooth: Boolean): Double? = measuredRate.value(observedXp.toDouble(), measurementMillis, smooth)

    fun reset() {
        observedXp = 0L; hasObservedXp = false; dailyBonuses = 0; dailyBonusXp = 0L; bonusDate = null
        commissionRewardUntil = Long.MIN_VALUE; eventRewardUntil = Long.MIN_VALUE; pendingClaims.clear()
        lastReward = null; clearBaseline(); measuredRate.reset()
        measurementMillis = 0L; lastTick = null; measuring = false; wasEligible = false
    }
}
