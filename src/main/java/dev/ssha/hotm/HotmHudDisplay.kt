package dev.ssha.hotm

import net.minecraft.network.chat.Component
import java.util.Locale

/** Text layout only; rendering and spacing use SkyHanni's commission-widget renderer. */
internal object HotmHudDisplay {
    fun lines(
        settings: AddonSettingsData,
        commissionWidget: List<Component> = emptyList(),
        rate: CommissionRateTracker? = null,
        now: Long = 0L,
    ): List<Component> = buildList {
        fun row(text: String) { add(Component.literal(text)) }

        val mode = if (settings.manualProgress) " §8(Manual)" else ""
        row("§e§lHOTM:$mode")
        settings.hotmTier?.let { tier ->
            val next = if (tier < 10) " §7→ §a${tier + 1}" else " §7(Max)"
            row(" §fTier: §a$tier$next")
        }
        if (settings.hotmTier == 10) {
            row(" §aMax tier reached!")
        } else {
            val xp = settings.xpToNextTier
            if (xp == null) {
                row(" §7Open §e/hotm §7to capture XP")
            } else {
                row(" §fXP left: §b${xp.prettyNumber()}")
                HotmProgress.commissionsRemaining(xp, settings.commissionXp)?.let { count ->
                    row(" §fCommissions left: §a~${count.prettyNumber()}")
                }
                row(" §fXP/commission: §e${settings.commissionXp.prettyNumber()}")
            }
        }
        if (settings.rateEnabled && rate != null) {
            val value = rate.perHour()?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
            val state = if (rate.isPaused(now)) " §8(Paused)" else ""
            row(" §fCommissions/h: §a$value$state")
        }
        if (settings.extraEventXp > 0L) row(" §fEvent/mineshaft XP: §a+${settings.extraEventXp.prettyNumber()}")

        val activeRows = commissionWidget.filter { component ->
            val text = component.string.replace(Regex("§."), "").trim()
            val progress = text.substringAfter(':', "")
            text.contains(':') && !text.substringBefore(':').equals("Commissions", ignoreCase = true) &&
                !progress.contains("DONE", ignoreCase = true) && !progress.contains("COMPLETE", ignoreCase = true)
        }
        if (activeRows.isNotEmpty()) {
            // Keep the server/SkyHanni colors, indentation, and Component styles intact.
            val header = commissionWidget.firstOrNull {
                it.string.replace(Regex("§."), "").trim().equals("Commissions:", ignoreCase = true)
            }
            add(header ?: Component.literal("§e§lCommissions:"))
            addAll(activeRows)
        } else if (commissionWidget.isEmpty() && settings.activeCommissions.isNotEmpty()) {
            row("§e§lCommissions:")
            settings.activeCommissions.forEach { commission ->
                val name = commission.substringBefore(':')
                val progress = commission.substringAfter(':', "").trim()
                row(" §f$name: §e$progress")
            }
        }
    }

    private fun Long.prettyNumber(): String = String.format(Locale.US, "%,d", this)
}
