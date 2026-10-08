package dev.ssha.hotm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HotmParserFixtureTest {
    @Test
    fun `screenshot locked Tier 6 progress bar captures progress toward tier six`() {
        val lines = listOf(
            "§eTier 6",
            "§7Progress through your Heart of the",
            "§7Mountain by gaining §5HOTM Exp§7.",
            "",
            "§7Progress: §e12%",
            "§a━━━━━━━━§f━━━━━━━━━━━━━━━━ §e12,000/100k",
            "",
            "§7Rewards:",
            "§5+2 Token of the Mountain",
            "§9+1 Forge Slot",
            "§6+New Forgeable Items",
            "§b+110 SkyBlock XP",
            "",
            "§cLOCKED",
        )
        assertEquals(HotmProgress.Observation(5, 88_000L), HotmProgress.readObservation(lines))
    }

    @Test
    fun `menu uses unlocked tiers to ignore future locked progress and hover cannot revert rank`() {
        val tierSix = "Tier 6" to listOf("Progress: 12%", "━━━━━━━━ 12,000/100k", "LOCKED")
        val menu = (1..5).map { "Tier $it" to listOf("Rewards:", "UNLOCKED") } + listOf(
            tierSix,
            "Tier 7" to listOf("Progress: 8%", "12,000/150k", "LOCKED"),
            "Mining Speed" to listOf("Level 1/50"),
        )
        val addon = HotmAddon()
        val expected = HotmProgress.Observation(5, 88_000L)
        assertEquals(expected, addon.recordMenu(menu))
        assertEquals(expected, addon.recordHoveredTooltip(tierSix.first, listOf(tierSix.first) + tierSix.second))
        assertEquals(expected, addon.recordHoveredTooltip("Tier 1", listOf("Tier 1", "UNLOCKED")))
        assertEquals(expected, HotmProgress.readMenuObservation(menu.reversed()))
        assertEquals(118L, HotmProgress.commissionsRemaining(88_000L, 750L))
    }

    @Test
    fun `progress bars support Roman tiers abbreviation decimals and plain fractions`() {
        listOf("12,000/100k", "12k / 100K", "12.000k/0.1m", "12,000/100,000").forEach { fraction ->
            assertEquals(
                HotmProgress.Observation(5, 88_000L),
                HotmProgress.readObservation(listOf("Tier VI", "Progress: 12%", "━━ $fraction", "LOCKED")),
                fraction,
            )
        }
        listOf("100,001/100k", "12,000/0", "12,000/999999999999999999999999999999b").forEach { fraction ->
            assertEquals(null, HotmProgress.readObservation(listOf("Tier 6", "Progress: 12%", fraction, "LOCKED")))
        }
        assertEquals(null, HotmProgress.readObservation(listOf("Tier 6", "12,000/100k", "LOCKED")))
        assertEquals(null, HotmProgress.readObservation(listOf("Tier 6", "Progress: 12%", "Rewards:", "+110 SkyBlock XP", "LOCKED")))
    }

    @Test
    fun `max tier is determined from unlocked menu status not zero progress`() {
        assertEquals(HotmProgress.Observation(10, 0L), HotmProgress.readMenuObservation(listOf("Tier X" to listOf("UNLOCKED"))))
        assertEquals(HotmProgress.Observation(5, null), HotmProgress.readMenuObservation(listOf("Tier V" to listOf("UNLOCKED"))))
        assertEquals(null, HotmProgress.readObservation(listOf("Tier 1", "Progress: 100%", "3,000/3k", "LOCKED")))
    }

    @Test
    fun `tier selector title alone is not the player's tier`() {
        assertEquals(null, HotmProgress.readObservation(listOf("Heart of the Mountain 1", "Rewards", "UNLOCKED")))
    }

    @Test
    fun `menu with tier one and locked requirements reads only current progress`() {
        val progress = "§aHeart of the Mountain" to listOf("§7Tier: §eVI", "§7Progress to Tier VII: §b8%", "§b12,000 / 150,000 HOTM XP")
        val items = listOf(
            "Heart of the Mountain 1" to listOf("UNLOCKED"),
            "Heart of the Mountain II" to listOf("UNLOCKED"),
            "Mining Speed" to listOf("Level 1/50", "Requires Heart of the Mountain 1"),
            "Heart of the Mountain X" to listOf("Reach Heart of the Mountain X in the", "Dwarven Mines to unlock this tier!"),
            progress,
        )
        val expected = HotmProgress.Observation(6, 138_000L)
        assertEquals(expected, HotmProgress.readMenuObservation(items))
        assertEquals(expected, HotmProgress.readMenuObservation(items.reversed()))
        assertEquals(expected, HotmProgress.readItemObservation(progress.first, listOf(progress.first) + progress.second))
    }

    @Test
    fun `Arabic and Roman next-tier headings identify current tier not destination`() {
        listOf("7", "VII").forEach { nextTier ->
            assertEquals(
                HotmProgress.Observation(6, 138_000L),
                HotmProgress.readObservation(listOf("Heart of the Mountain $nextTier", "Progress to Tier $nextTier: 8%", "12,000 / 150,000 HOTM XP")),
            )
        }
        assertEquals(null, HotmProgress.readObservation(listOf("Heart of the Mountain 1", "Progress to Tier VII: 8%", "12,000 / 150,000 HOTM XP")))
        assertEquals(null, HotmProgress.readObservation(listOf("Heart of the Mountain", "Tier: V", "Progress to Tier VII: 8%", "12,000 / 150,000 HOTM XP")))
    }

    @Test
    fun `requirements perks and unsupported numerals never provide current tier`() {
        assertEquals(
            HotmProgress.Observation(null, 138_000L),
            HotmProgress.readObservation(listOf("Heart of the Mountain", "Requires Heart of the Mountain 1", "Reach Heart of the Mountain X", "Level 1/50", "12,000 / 150,000 HOTM XP")),
        )
        listOf("11", "100", "IIII", "XI").forEach { tier ->
            assertEquals(null, HotmProgress.readObservation(listOf("Heart of the Mountain", "Current Tier: $tier")))
        }
        assertEquals(null, HotmProgress.readObservation(listOf("Heart of the Mountain X", "UNLOCKED")))
    }

    @Test
    fun `conflicting items do not select first or splice unrelated tier and XP`() {
        val first = "Heart of the Mountain" to listOf("Tier: VI", "12,000 / 150,000 HOTM XP")
        val conflicting = "Heart of the Mountain" to listOf("Tier: V", "12,000 / 100,000 HOTM XP")
        assertEquals(null, HotmProgress.readMenuObservation(listOf(first, conflicting)))
        assertEquals(null, HotmProgress.readMenuObservation(listOf(
            "Heart of the Mountain" to listOf("Tier: VI"),
            "Heart of the Mountain" to listOf("12,000 / 150,000 HOTM XP"),
        )))
    }

    @Test
    fun `widget parser strips colors and finished rows`() {
        assertEquals(
            listOf("Mithril Miner: 2/10", "Goblin Slayer: 1/5"),
            HotmProgress.activeCommissions(listOf("§eMithril Miner: §a2/10", "Goblin Slayer: 1/5", "Crystal Collector: DONE")),
        )
    }
}
