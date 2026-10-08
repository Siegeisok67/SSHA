package dev.ssha.hotm

/** HOTM arithmetic and text parsing for client-visible game data. */
object HotmProgress {
    private const val TIER_NUMBER = "(10|[1-9]|VIII|VII|VI|IV|IX|III|II|V|X|I)"
    private val tierItemPattern = Regex("(?i)^(?:(?:HOTM|HEART OF THE MOUNTAIN)(?:\\s+(?:TIER|LEVEL))?|TIER)\\s*[:#]?\\s+$TIER_NUMBER$")

    data class Observation(val tier: Int?, val xpToNextTier: Long?)

    /** An XP decrease is commission XP only when a completion message confirmed it. */
    fun eventXpForReduction(xpReduction: Long, completedCommissions: Int, xpPerCommission: Long): Long {
        if (xpReduction <= 0L) return 0L
        if (completedCommissions <= 0 || xpPerCommission <= 0L) return xpReduction
        val expectedCommissionXp = if (xpPerCommission > Long.MAX_VALUE / completedCommissions) {
            Long.MAX_VALUE
        } else {
            xpPerCommission * completedCommissions
        }
        val commissionXp = minOf(xpReduction, expectedCommissionXp)
        return (xpReduction - commissionXp).coerceAtLeast(0L)
    }

    /** Always rounds up without floating-point precision loss. */
    fun commissionsRemaining(xpToNextTier: Long, xpPerCommission: Long): Long? {
        if (xpToNextTier <= 0L) return 0L
        if (xpPerCommission <= 0L) return null
        return 1L + (xpToNextTier - 1L) / xpPerCommission
    }

    // XP earned within each tier to reach the next one, not cumulative profile XP.
    // Source: https://hypixelskyblock.minecraft.wiki/w/Heart_of_the_Mountain
    private val nextTierXp = listOf(3_000L, 9_000L, 25_000L, 60_000L, 100_000L, 150_000L, 210_000L, 290_000L, 400_000L)

    fun manualObservation(tier: Int, earnedXp: Long): Observation {
        require(tier in 1..10) { "HOTM tier must be between 1 and 10." }
        val required = nextTierXp.getOrNull(tier - 1) ?: 0L
        require(earnedXp in 0L..required) {
            if (tier == 10) "HOTM 10 is max tier; enter 0 XP."
            else "XP within HOTM $tier must be between 0 and $required; use your current tier, not lifetime XP."
        }
        return Observation(tier, required - earnedXp)
    }

    /** Only compare samples within a known, unchanged tier for event attribution. */
    fun xpReduction(previous: Observation, next: Observation): Long =
        if (previous.tier != null && previous.tier == next.tier &&
            previous.xpToNextTier != null && next.xpToNextTier != null
        ) (previous.xpToNextTier - next.xpToNextTier).coerceAtLeast(0L) else 0L

    /** Parse one item only: a selector's name or an unlock requirement is not the player's tier. */
    fun readObservation(lines: List<String>): Observation? =
        lines.firstOrNull()?.let { readItemObservation(it, lines.drop(1)) }

    fun isProgressItemName(name: String): Boolean = name.cleanText().equals("Heart of the Mountain", ignoreCase = true) ||
        tierItemPattern.matches(name.cleanText())

    private fun itemTier(name: String): Int? = tierItemPattern.matchEntire(name.cleanText())
        ?.groupValues?.get(1)?.toTierOrNull()

    fun readMenuObservation(items: List<Pair<String, List<String>>>): Observation? {
        val highestUnlockedTier = items.mapNotNull { (name, lore) ->
            itemTier(name)?.takeIf { lore.any { it.cleanText().equals("UNLOCKED", ignoreCase = true) } }
        }.maxOrNull()
        val observations = items.mapNotNull { (name, lore) ->
            val candidate = readItemObservation(name, lore) ?: return@mapNotNull null
            // Higher locked icons describe future goals, not the player's current tier.
            if (highestUnlockedTier != null && itemTier(name) != null && candidate.tier != highestUnlockedTier) null else candidate
        }
        val complete = observations.filter { it.tier != null && it.xpToNextTier != null }.distinct()
        if (complete.size == 1) return complete.single()
        if (complete.size > 1) return null
        if (highestUnlockedTier != null) return Observation(highestUnlockedTier, if (highestUnlockedTier == 10) 0L else null)
        // No unlocked rows yet: only the nearest locked progress target can be current.
        val lockedProgress = items.filter { (name, lore) ->
            itemTier(name) != null && lore.any { it.cleanText().equals("LOCKED", ignoreCase = true) }
        }.mapNotNull { (name, lore) -> readItemObservation(name, lore)?.takeIf { it.xpToNextTier != null } }
        if (lockedProgress.isNotEmpty()) return lockedProgress.minBy { checkNotNull(it.tier) }
        // Do not join a rank from one item to XP from another.
        return observations.distinct().singleOrNull()
    }

    /** XP capture remains explicit; thresholds are used only for manual input. */
    fun readItemObservation(itemName: String, lines: List<String>): Observation? {
        val name = itemName.cleanText()
        val normalized = (listOf(itemName) + lines).map { it.cleanText() }.filter { it.isNotEmpty() }
        val summaryItem = name.equals("Heart of the Mountain", ignoreCase = true)
        val selectorTier = itemTier(name)
        val lockedTierItem = selectorTier != null && normalized.any { it.equals("LOCKED", ignoreCase = true) }
        if (!summaryItem && selectorTier == null) return null
        val currentTierPattern = Regex(
            "(?i)^(?:(?:YOUR|CURRENT)\\s+(?:(?:HOTM|HEART OF THE MOUNTAIN)\\s*)?(?:TIER|LEVEL)?|(?:HOTM|HEART OF THE MOUNTAIN)(?:\\s+(?:TIER|LEVEL))?|(?:TIER|LEVEL))\\s*[:#]?\\s*$TIER_NUMBER$",
        )
        val explicitTier = normalized.filterNot { it.equals(name, ignoreCase = true) }.firstNotNullOfOrNull { line ->
            currentTierPattern.matchEntire(line)?.groupValues?.get(1)?.toTierOrNull()
        }
        val nextTier = normalized.firstNotNullOfOrNull { line ->
            Regex("(?i)^PROGRESS TO (?:HOTM\\s+)?(?:TIER|LEVEL)\\s+$TIER_NUMBER\\s*:")
                .find(line)?.groupValues?.get(1)?.toTierOrNull()
        }
        // LOCKED Tier N shows XP toward unlocking N, not XP earned after reaching N.
        if (!summaryItem && !lockedTierItem && (nextTier == null || nextTier != selectorTier)) return null
        if (lockedTierItem && nextTier != null && nextTier != selectorTier) return null
        val tier = explicitTier ?: (nextTier ?: selectorTier?.takeIf { lockedTierItem })?.minus(1)?.takeIf { it in 1..9 }
        val amount = "([0-9][0-9,]*(?:\\.[0-9]+)?[kKmMbB]?)"
        val remainingPatterns = listOf(
            Regex("(?i)$amount\\s*(?:HOTM\\s*)?XP\\s*(?:TO|UNTIL|REMAINING|NEEDED|LEFT|FOR NEXT TIER|TO NEXT TIER)"),
            Regex("(?i)(?:XP\\s*(?:TO|UNTIL)|(?:XP\\s*)?REMAINING|(?:XP\\s*)?NEEDED|(?:XP\\s*)?LEFT|TO NEXT TIER|TO NEXT LEVEL)\\s*[:=]?\\s*$amount(?:\\s*XP)?"),
        )
        val fractionPatterns = listOf(
            Regex("(?i)(?:XP|EXPERIENCE|PROGRESS)[^0-9]*$amount\\s*/\\s*$amount"),
            Regex("(?i)$amount\\s*/\\s*$amount\\s*(?:HOTM\\s*)?XP"),
        )
        val barFractionPattern = Regex("(?i)^[^\\p{L}\\p{N}]*$amount\\s*/\\s*$amount\\s*$")
        val hasProgressHeading = normalized.any { Regex("(?i)^PROGRESS(?:\\s+TO\\s+.+)?:").containsMatchIn(it) }
        val xpRemaining = normalized.firstNotNullOfOrNull { line ->
            remainingPatterns.firstNotNullOfOrNull { pattern ->
                pattern.find(line)?.groupValues?.get(1)?.toLongNumberOrNull()
            } ?: fractionPatterns.firstNotNullOfOrNull { pattern ->
                pattern.find(line)?.let { match ->
                    val current = match.groupValues[1].toLongNumberOrNull()
                    val required = match.groupValues[2].toLongNumberOrNull()
                    if (current != null && required != null && required > 0L && current <= required) required - current else null
                }
            } ?: if (hasProgressHeading) {
                barFractionPattern.matchEntire(line)?.let { match ->
                    val current = match.groupValues[1].toLongNumberOrNull()
                    val required = match.groupValues[2].toLongNumberOrNull()
                    if (current != null && required != null && required > 0L && current <= required) required - current else null
                }
            } else null
        }
        if (lockedTierItem && (tier == null || xpRemaining == null)) return null
        if (explicitTier != null && nextTier != null && explicitTier != nextTier - 1) return null
        return if (tier != null || xpRemaining != null) Observation(tier, xpRemaining) else null
    }

    private fun String.cleanText(): String = replace(Regex("§."), "").trim()

    private fun String.toTierOrNull(): Int? = toIntOrNull()?.takeIf { it in 1..10 }
        ?: listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X")
            .indexOf(uppercase()).takeIf { it >= 0 }?.plus(1)

    private fun String.toLongNumberOrNull(): Long? {
        val text = replace(",", "").trim().lowercase()
        val multiplier = when (text.lastOrNull()) {
            'k' -> 1_000L
            'm' -> 1_000_000L
            'b' -> 1_000_000_000L
            else -> 1L
        }
        val number = (if (multiplier == 1L) text else text.dropLast(1)).toBigDecimalOrNull() ?: return null
        return try {
            number.multiply(multiplier.toBigDecimal()).longValueExact()
        } catch (_: ArithmeticException) {
            null
        }
    }

    fun isHotmInventoryTitle(title: String): Boolean =
        title.contains("heart of the mountain", ignoreCase = true) || title.contains("hotm", ignoreCase = true)

    /** Input is SkyHanni's COMMISSIONS tab widget, not arbitrary tab-list text. */
    fun activeCommissions(tabLines: List<String>): List<String> = tabLines.mapNotNull { line ->
        val normalized = line.replace(Regex("§."), "").trim()
        val colon = normalized.indexOf(':')
        if (colon <= 0) return@mapNotNull null
        val name = normalized.substring(0, colon).trim()
        val progress = normalized.substring(colon + 1).trim()
        if (name.equals("Commissions", ignoreCase = true)) return@mapNotNull null
        if (progress.contains("DONE", ignoreCase = true) || progress.contains("COMPLETE", ignoreCase = true)) return@mapNotNull null
        // The SkyHanni commission widget is already a scoped list; commission labels are not
        // guaranteed to contain a numeric progress fraction or the word "Commission".
        "$name: $progress"
    }.distinct()
}
