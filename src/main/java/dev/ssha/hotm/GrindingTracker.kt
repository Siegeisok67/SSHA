package dev.ssha.hotm

import java.util.Locale

internal enum class GrindingMaterial(
    val displayName: String,
    val rawId: String,
    val enchantedId: String?,
    val productId: String,
    val rawPerProduct: Long,
    val rateLabel: String,
) {
    GOLD("Gold", "GOLD_INGOT", "ENCHANTED_GOLD", "ENCHANTED_GOLD_BLOCK", 25_600L, "E. gold blocks/h"),
    DIAMOND("Diamond", "DIAMOND", "ENCHANTED_DIAMOND", "ENCHANTED_DIAMOND_BLOCK", 25_600L, "E. diamond blocks/h"),
    MITHRIL("Mithril", "MITHRIL_ORE", "ENCHANTED_MITHRIL", "REFINED_MITHRIL", 25_600L, "Refined mithril/h"),
    TITANIUM("Titanium", "TITANIUM_ORE", "ENCHANTED_TITANIUM", "REFINED_TITANIUM", 2_560L, "Refined titanium/h"),
    UMBER("Umber", "UMBER", "ENCHANTED_UMBER", "REFINED_UMBER", 25_600L, "Refined umber/h"),
    TUNGSTEN("Tungsten", "TUNGSTEN", "ENCHANTED_TUNGSTEN", "REFINED_TUNGSTEN", 25_600L, "Refined tungsten/h"),
    JEWELS("Glacite Jewels", "GLACITE_JEWEL", null, "BEJEWELED_HANDLE", 3L, "Glacite jewels/h");

    val defaultThreshold: Long get() = if (this == JEWELS) 3L else 20_160L
    val enchantingId: String get() = when (this) {
        GOLD -> "ENCHANTED_GOLD_BLOCK"
        DIAMOND -> "ENCHANTED_DIAMOND_BLOCK"
        JEWELS -> "BEJEWELED_HANDLE"
        else -> productId
    }
    /** What this material is compacted into, shown in the popup. */
    val compactTarget: String get() = when (this) {
        GOLD -> "Enchanted Gold Block"
        DIAMOND -> "Enchanted Diamond Block"
        MITHRIL -> "Enchanted Mithril"
        TITANIUM -> "Enchanted Titanium"
        UMBER -> "Enchanted Umber"
        TUNGSTEN -> "Enchanted Tungsten"
        JEWELS -> "Bejeweled Handle"
    }
    val popupText: String get() = when (this) {
        JEWELS -> "Forge Bejewled Handles"
        else -> "Compact to $compactTarget"
    }
    fun weight(id: String): Long = when (id) {
        rawId -> 1L
        enchantedId -> 160L
        else -> 0L
    }
}

internal data class GrindingWidgetSettings(
    var enabled: Boolean = false,
    var compactPopup: Boolean = false,
    var compactAt: Long = 20_160L,
    var smoothRate: Boolean = true,
    var showStored: Boolean = true,
    var showSession: Boolean = false,
    var overflowWarning: Boolean = true,
    var position: HudPositionData = HudPositionData(8, 150),
)

internal data class RareSackSettings(
    var enabled: Boolean = false,
    var position: HudPositionData = HudPositionData(8, 550),
)

/** Positive sack events only; never infer gains from a cached sack total. */
internal class RareSackTracker {
    companion object {
        const val MINERAL = "REFINED_MINERAL"
        const val GEMSTONE = "GLOSSY_GEMSTONE"
    }
    var minerals: Long = 0L
        private set
    var gemstones: Long = 0L
        private set
    private var startedAt: Long? = null

    fun changes(deltas: Map<String, Long>, now: Long, active: Boolean, incompleteRemovals: Boolean = false) {
        // A mixed sack packet can be a transfer or crafting operation, not a mining drop.
        if (!active || incompleteRemovals || deltas.values.any { it < 0L }) return
        val mineral = deltas[MINERAL]?.coerceAtLeast(0L) ?: 0L
        val gemstone = deltas[GEMSTONE]?.coerceAtLeast(0L) ?: 0L
        if (mineral == 0L && gemstone == 0L) return
        if (startedAt == null) startedAt = now
        minerals += mineral
        gemstones += gemstone
    }

    fun perHour(count: Long, now: Long): Double? = startedAt?.let { start ->
        (now - start).takeIf { it >= 60_000L }?.let { count.toDouble() * 3_600_000.0 / it }
    }

    data class Valuation(val minerals: Double?, val gemstones: Double?) {
        val knownTotal: Double get() = (minerals ?: 0.0) + (gemstones ?: 0.0)
        val partial: Boolean get() = minerals == null || gemstones == null
    }

    /** Market estimate for items gained in this session, not realized sale profit. */
    fun valuation(instantSell: (String) -> Double?): Valuation {
        fun coins(id: String, count: Long): Double? {
            if (count == 0L) return 0.0
            val price = instantSell(id)?.takeIf { it.isFinite() && it > 0.0 } ?: return null
            return count * price
        }
        return Valuation(coins(MINERAL, minerals), coins(GEMSTONE, gemstones))
    }

    fun reset() { minerals = 0L; gemstones = 0L; startedAt = null }
}

internal data class GrindingSettingsData(
    var gold: GrindingWidgetSettings = GrindingWidgetSettings(compactAt = GrindingMaterial.GOLD.defaultThreshold),
    var diamond: GrindingWidgetSettings = GrindingWidgetSettings(compactAt = GrindingMaterial.DIAMOND.defaultThreshold, position = HudPositionData(8, 200)),
    var mithril: GrindingWidgetSettings = GrindingWidgetSettings(compactAt = GrindingMaterial.MITHRIL.defaultThreshold, position = HudPositionData(8, 250)),
    var titanium: GrindingWidgetSettings = GrindingWidgetSettings(compactAt = GrindingMaterial.TITANIUM.defaultThreshold, position = HudPositionData(8, 300)),
    var umber: GrindingWidgetSettings = GrindingWidgetSettings(compactAt = GrindingMaterial.UMBER.defaultThreshold, position = HudPositionData(8, 350)),
    var tungsten: GrindingWidgetSettings = GrindingWidgetSettings(compactAt = GrindingMaterial.TUNGSTEN.defaultThreshold, position = HudPositionData(8, 400)),
    var jewels: GrindingWidgetSettings = GrindingWidgetSettings(compactAt = 3L, position = HudPositionData(8, 450)),
    var rareSacks: RareSackSettings = RareSackSettings(),
) {
    operator fun get(material: GrindingMaterial): GrindingWidgetSettings = when (material) {
        GrindingMaterial.GOLD -> gold
        GrindingMaterial.DIAMOND -> diamond
        GrindingMaterial.MITHRIL -> mithril
        GrindingMaterial.TITANIUM -> titanium
        GrindingMaterial.UMBER -> umber
        GrindingMaterial.TUNGSTEN -> tungsten
        GrindingMaterial.JEWELS -> jewels
    }
}

/** One saved tracker: [total] is raw sack-equivalent units, [eventTotal] only used by powder. */
internal data class SavedTracker(
    var total: Long = 0L,
    var eventTotal: Long = 0L,
    var activeMillis: Long = 0L,
)

/** Count gains only when a net sack addition survives the compaction/reconciliation window. */
internal class GrindingTracker(val material: GrindingMaterial) {
    companion object { const val RECONCILE_MS = 1_000L; const val IDLE_MS = CommissionRateTracker.IDLE_TIMEOUT_MS }
    private data class Change(val amount: Long, val time: Long)
    private val pending = ArrayDeque<Change>()
    private var blockedUntil = Long.MIN_VALUE
    private var lastTick: Long? = null
    private var lastGain: Long? = null
    private var lastActivityAt: Long? = null
    private var warningLatched = false
    private var warningThreshold = Long.MIN_VALUE
    private var previousStoredRaw: Long? = null
    private var thresholdCrossingPending = false
    private val rate = SmoothedHourlyRate()
    var totalRaw: Long = 0L
        private set
    var activeMillis: Long = 0L
        private set
    var storedRaw: Long? = null
        private set
    var latestCommittedAt: Long = Long.MIN_VALUE
        private set

    fun changes(deltas: Map<String, Long>, now: Long, incompleteRemovals: Boolean = false) {
        val removed = deltas.any { (id, amount) -> amount < 0L && (material.weight(id) > 0L || id == material.enchantingId) }
        val added = deltas.entries.sumOf { (id, amount) ->
            if (amount > 0L && id != material.enchantingId) amount * material.weight(id) else 0L
        }
        // Negative sack deltas mean withdrawal OR compaction. If message has incomplete removals,
        // invalidate any gains still waiting. A positive block/handle/refined output is never input.
        if (incompleteRemovals || removed) {
            pending.clear()
            blockedUntil = now + RECONCILE_MS
            tick(now)
            return
        }
        if (added > 0L && now >= blockedUntil) pending.addLast(Change(added, now))
        tick(now)
    }

    fun tick(now: Long) {
        // Keep the clock behind uncommitted changes so accepted gains retain their actual time.
        while (pending.isNotEmpty() && now - pending.first().time >= RECONCILE_MS) {
            val gain = pending.removeFirst()
            advance(gain.time)
            lastActivityAt = gain.time
            lastGain = gain.time
            latestCommittedAt = gain.time
            totalRaw += gain.amount
        }
        advance(pending.firstOrNull()?.time?.coerceAtMost(now) ?: now)
        rate.update(totalRaw.toDouble(), activeMillis)
    }

    private fun advance(now: Long) {
        val previous = lastTick
        val activity = lastActivityAt
        if (previous != null && activity != null) {
            val end = minOf(now, activity + IDLE_MS)
            if (end > previous) activeMillis += end - previous
        }
        lastTick = maxOf(lastTick ?: now, now)
    }

    fun stored(
        raw: Long?,
        enchanted: Long? = if (material.enchantedId == null) 0L else null,
        compacted: Long? = 0L,
        compactAt: Long = material.defaultThreshold,
    ) {
        if (warningThreshold != compactAt) {
            warningThreshold = compactAt
            warningLatched = false
            thresholdCrossingPending = false
        }
        previousStoredRaw = storedRaw
        storedRaw = if (raw != null || enchanted != null || compacted != null && compacted > 0L) {
            (raw ?: 0L).coerceAtLeast(0L) + (enchanted ?: 0L).coerceAtLeast(0L) * 160L +
                (compacted ?: 0L).coerceAtLeast(0L) * material.rawPerProduct
        } else null
        val previous = previousStoredRaw
        val current = storedRaw
        if (previous != null && current != null && previous < compactAt && current >= compactAt) thresholdCrossingPending = true
        if (current != null && current < compactAt) {
            thresholdCrossingPending = false
            warningLatched = false
        }
    }

    fun shouldWarn(settings: GrindingWidgetSettings, allowWarning: Boolean = true): Boolean {
        if (warningThreshold != settings.compactAt) {
            warningThreshold = settings.compactAt
            warningLatched = false
            thresholdCrossingPending = false
        }
        if (!settings.compactPopup) { warningLatched = false; return false }
        val stored = storedRaw ?: return false
        if (stored < settings.compactAt) { warningLatched = false; return false }
        if (warningLatched || !allowWarning || !thresholdCrossingPending) return false
        warningLatched = true
        thresholdCrossingPending = false
        return true
    }

    fun perHour(smooth: Boolean): Double? = rate.value(totalRaw.toDouble(), activeMillis, smooth)
    fun paused(now: Long): Boolean = lastGain?.let { now - it >= IDLE_MS } ?: true
    fun activeTime(): String {
        val seconds = activeMillis / 1_000L
        return String.format(Locale.US, "%02d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60)
    }
    fun suspend(now: Long) { tick(now); pending.clear(); lastGain = null; lastActivityAt = null }
    /** Reloads saved totals; the rate window restarts so the first hourly value is not inflated. */
    fun restore(total: Long, active: Long) {
        totalRaw = total
        activeMillis = active
        rate.reset()
    }
    fun reset() {
        pending.clear(); blockedUntil = Long.MIN_VALUE
        totalRaw = 0L; activeMillis = 0L; lastTick = null; lastGain = null; lastActivityAt = null
        latestCommittedAt = Long.MIN_VALUE; storedRaw = null; previousStoredRaw = null; warningLatched = false; warningThreshold = Long.MIN_VALUE; thresholdCrossingPending = false; rate.reset()
    }
}
