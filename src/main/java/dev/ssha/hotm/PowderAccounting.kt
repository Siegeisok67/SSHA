package dev.ssha.hotm

import at.hannibal2.skyhanni.data.MiningEventsApi.MiningEventType

internal class PowderAccounting {
    data class Gain(val amount: Long, val eventAmount: Long, val commissionAmount: Long)
    private data class Delta(val amount: Long, val at: Long, val doublePowder: Boolean, val claiming: Boolean)
    private data class Reward(var amount: Long, val at: Long, val commission: Boolean)
    private val pending = ArrayDeque<Delta>()
    private val rewards = ArrayDeque<Reward>()
    private var commissionUntil = Long.MIN_VALUE
    private var eventUntil = Long.MIN_VALUE
    private var claimMenuUntil = Long.MIN_VALUE

    fun delta(amount: Long, now: Long, event: MiningEventType?) {
        if (amount > 0L) pending.addLast(Delta(amount, now, event == MiningEventType.DOUBLE_POWDER, now <= claimMenuUntil))
    }

    fun message(message: String, now: Long) {
        val text = message.replace(Regex("§."), "").trim()
        if (Regex("(?i)^(?:commission (?:rewards|claimed)|you (?:claimed|completed) (?:a |your )?commission)").containsMatchIn(text)) {
            commissionUntil = now + 5_000L
        }
        if (Regex("(?i)(?:goblin raid|raffle|mithril gourmand).*(?:ended|rewards?|received|earned)").containsMatchIn(text)) {
            eventUntil = now + 5_000L
        }
        val match = Regex("(?i)^\\+?([0-9][0-9,]*)\\s*(?:᠅\\s*)?Mithril Powder[!.]?$").matchEntire(text) ?: return
        val amount = match.groupValues[1].replace(",", "").toLongOrNull()?.takeIf { it > 0L } ?: return
        when {
            now <= commissionUntil -> rewards.addLast(Reward(amount, now, true))
            now <= eventUntil -> rewards.addLast(Reward(amount, now, false))
        }
    }

    fun claiming(now: Long) { commissionUntil = now + 5_000L; claimMenuUntil = now + 1_000L }
    fun eventEnded(type: MiningEventType, now: Long) {
        if (type in rewardEvents) eventUntil = now + 5_000L
    }

    fun drain(now: Long, includeCommissions: Boolean, accept: (Gain, Long) -> Unit) {
        // The server may send powder totals before the reward text. Briefly wait for both.
        while (pending.isNotEmpty() && now - pending.first().at >= 1_000L) {
            val delta = pending.removeFirst()
            var remaining = delta.amount
            var commission = 0L
            var event = 0L
            rewards.filter { kotlin.math.abs(it.at - delta.at) <= 5_000L }.forEach { reward ->
                val matched = minOf(remaining, reward.amount)
                if (reward.commission) commission += matched else event += matched
                reward.amount -= matched
                remaining -= matched
            }
            rewards.removeAll { it.amount == 0L }
            if (delta.claiming) { commission += remaining; remaining = 0L }
            val counted = delta.amount - if (includeCommissions) 0L else commission
            val eventAmount = event + if (delta.doublePowder) remaining else 0L
            if (counted > 0L) accept(Gain(counted, eventAmount, if (includeCommissions) commission else 0L), delta.at)
        }
        rewards.removeAll { now - it.at > 5_000L }
    }

    fun reset() { pending.clear(); rewards.clear(); commissionUntil = Long.MIN_VALUE; eventUntil = Long.MIN_VALUE; claimMenuUntil = Long.MIN_VALUE }
    companion object {
        val rewardEvents = setOf(MiningEventType.GOBLIN_RAID, MiningEventType.RAFFLE, MiningEventType.MITHRIL_GOURMAND)
        fun relevant(type: MiningEventType?): Boolean = type == MiningEventType.DOUBLE_POWDER || type in rewardEvents
    }
}
