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
    val popupText: String get() = when (this) {
        GOLD, DIAMOND -> "Compact to blocks!"
        JEWELS -> "Craft Bejeweled Handles"
        else -> "Refine materials"
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

internal data class GrindingSettingsData(
    var gold: GrindingWidgetSettings = GrindingWidgetSettings(compactAt = GrindingMaterial.GOLD.defaultThreshold),
    var diamond: GrindingWidgetSettings = GrindingWidgetSettings(compactAt = GrindingMaterial.DIAMOND.defaultThreshold, position = HudPositionData(8, 200)),
    var mithril: GrindingWidgetSettings = GrindingWidgetSettings(compactAt = GrindingMaterial.MITHRIL.defaultThreshold, position = HudPositionData(8, 250)),
    var titanium: GrindingWidgetSettings = GrindingWidgetSettings(compactAt = GrindingMaterial.TITANIUM.defaultThreshold, position = HudPositionData(8, 300)),
    var umber: GrindingWidgetSettings = GrindingWidgetSettings(compactAt = GrindingMaterial.UMBER.defaultThreshold, position = HudPositionData(8, 350)),
    var tungsten: GrindingWidgetSettings = GrindingWidgetSettings(compactAt = GrindingMaterial.TUNGSTEN.defaultThreshold, position = HudPositionData(8, 400)),
    var jewels: GrindingWidgetSettings = GrindingWidgetSettings(compactAt = 3L, position = HudPositionData(8, 450)),
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
    fun reset() {
        pending.clear(); blockedUntil = Long.MIN_VALUE
        totalRaw = 0L; activeMillis = 0L; lastTick = null; lastGain = null; lastActivityAt = null
        latestCommittedAt = Long.MIN_VALUE; storedRaw = null; previousStoredRaw = null; warningLatched = false; warningThreshold = Long.MIN_VALUE; thresholdCrossingPending = false; rate.reset()
    }
}
