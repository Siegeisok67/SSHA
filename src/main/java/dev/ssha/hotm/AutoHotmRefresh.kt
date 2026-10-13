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

        /**
         * True when a commission finished and XP left needs a fresh /hotm read. The tab widget
         * can vanish once the last commission completes, so a missing widget counts as finished
         * when we were tracking commissions inside the mining area.
         */
        fun commissionsFinished(
            previousCount: Int,
            currentCount: Int,
            widgetActive: Boolean,
            inMiningArea: Boolean,
        ): Boolean {
            if (previousCount <= currentCount) return false
            if (widgetActive) return true
            return inMiningArea && currentCount == 0
        }
    }

    private var dueAt: Long? = null
    private var lastSentAt: Long? = null
    private var awaitingSince: Long? = null
    private var activeRead = false
    private var menuClaimed = false

    /** Latest request wins. Use delayMs to let the server finish updating after a claim. */
    fun request(now: Long, delayMs: Long) {
        dueAt = now + delayMs
    }

    /**
     * True when the caller should send /hotm now. [enabled] false (not in SkyBlock, or manual
     * progress) leaves the request pending instead of consuming it, so it runs once a read is possible.
     */
    fun poll(now: Long, guiOpen: Boolean, enabled: Boolean = true): Boolean {
        val due = dueAt ?: return false
        if (!enabled) return false
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
        activeRead = true
        menuClaimed = false
        return true
    }

    /**
     * Called each tick while a HOTM menu is open. Returns true when that menu was opened by our
     * request and has been read, so the caller should close it.
     */
    fun menuOpened(now: Long, read: Boolean): Boolean {
        // A fresh HOTM menu is already on screen, so a pending read is redundant.
        dueAt = null
        if (!activeRead) return false
        val since = awaitingSince ?: return false
        if (!read && now - since < readTimeoutMs) return false
        awaitingSince = null
        activeRead = false
        menuClaimed = false
        return true
    }

    /**
     * Claim the first HOTM menu opened shortly after our command. The server can deliver it after
     * the initial read wait expired; seeing it starts a fresh menu/page read window.
     */
    fun claimMenu(now: Long, windowMs: Long): Boolean {
        if (!activeRead || !sentWithin(now, windowMs)) return false
        if (menuClaimed) return true
        menuClaimed = true
        awaitingSince = now
        return true
    }

    /** A page turn is part of the same read: give the new page its own full wait from [now]. */
    fun restartWait(now: Long) {
        if (awaitingSince != null) awaitingSince = now
    }

    /** True while a read we sent is still waiting for its menu. */
    fun awaitingMenu(): Boolean = awaitingSince != null

    /** True when /hotm was sent within [windowMs]: a visible menu this fresh is ours, not the player's. */
    fun sentWithin(now: Long, windowMs: Long): Boolean {
        val last = lastSentAt ?: return false
        return now - last in 0L..windowMs
    }

    /**
     * Let a late server menu still be claimed for [ownershipWindowMs] after /hotm was sent.
     * Once claimed, only menuOpened may finish it; before claim, the shorter read timeout just
     * drops the wait timer, not ownership of the expected menu.
     * @return true once the request's ownership window expires without a menu.
     */
    fun expire(now: Long, ownershipWindowMs: Long = 10_000L): Boolean {
        if (!activeRead || menuClaimed) return false
        val since = awaitingSince
        if (since != null && now - since >= readTimeoutMs) awaitingSince = null
        if (sentWithin(now, ownershipWindowMs)) return false
        activeRead = false
        awaitingSince = null
        return true
    }
}
