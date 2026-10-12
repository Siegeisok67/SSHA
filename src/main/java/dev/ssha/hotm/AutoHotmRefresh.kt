package dev.ssha.hotm

/**
 * Schedules a silent `/hotm` read so HOTM XP and commissions left stay current after a claim.
 * A request never sends while another GUI is open, so a commission menu is not interrupted.
 */
internal class AutoHotmRefresh(
    private val minIntervalMs: Long = 3_000L,
    private val readTimeoutMs: Long = 5_000L,
    private val staleMs: Long = 60_000L,
) {
    companion object {
        private val rewardPattern = Regex("(?i)commission rewards?|hotm experience|hotm xp|heart of the mountain experience")

        /** Claim-time reward text only. "Commission Complete! Visit the King..." is not a reward. */
        fun isRewardMessage(text: String): Boolean =
            rewardPattern.containsMatchIn(text) && !text.contains("visit", ignoreCase = true)
    }

    private var dueAt: Long? = null
    private var lastSentAt: Long? = null
    private var awaitingSince: Long? = null

    /** Latest request wins. Use delayMs to let the server finish updating after a claim. */
    fun request(now: Long, delayMs: Long) {
        dueAt = now + delayMs
    }

    /** True when the caller should send /hotm now. */
    fun poll(now: Long, guiOpen: Boolean): Boolean {
        val due = dueAt ?: return false
        if (now < due) return false
        if (guiOpen) {
            if (now - due >= staleMs) dueAt = null
            return false
        }
        val last = lastSentAt
        if (last != null && now - last < minIntervalMs) {
            dueAt = last + minIntervalMs
            return false
        }
        dueAt = null
        lastSentAt = now
        awaitingSince = now
        return true
    }

    /**
     * Called each tick while a HOTM menu is open. Returns true when that menu was opened by our
     * request and has been read, so the caller should close it.
     */
    fun menuOpened(now: Long, read: Boolean): Boolean {
        // A fresh HOTM menu is already on screen, so a pending read is redundant.
        dueAt = null
        val since = awaitingSince ?: return false
        if (!read && now - since < readTimeoutMs) return false
        awaitingSince = null
        return true
    }

    /** True while a read we sent is still waiting for its menu. */
    fun awaitingMenu(): Boolean = awaitingSince != null

    /** True when /hotm was sent within [windowMs]: a visible menu this fresh is ours, not the player's. */
    fun sentWithin(now: Long, windowMs: Long): Boolean {
        val last = lastSentAt ?: return false
        return now - last in 0L..windowMs
    }

    /** Stop waiting for a menu that never opened. */
    fun expire(now: Long) {
        val since = awaitingSince ?: return
        if (now - since >= readTimeoutMs) awaitingSince = null
    }
}
