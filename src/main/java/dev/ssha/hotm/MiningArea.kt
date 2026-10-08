package dev.ssha.hotm

import at.hannibal2.skyhanni.data.IslandType
import at.hannibal2.skyhanni.utils.SkyBlockUtils

internal object MiningArea {
    fun current(): Boolean = allowed(SkyBlockUtils.inSkyBlock, SkyBlockUtils.currentIsland, SkyBlockUtils.graphArea)

    fun allowed(inSkyblock: Boolean, island: IslandType, area: String?): Boolean = inSkyblock && (
        island in setOf(IslandType.DWARVEN_MINES, IslandType.CRYSTAL_HOLLOWS, IslandType.MINESHAFT) ||
            area in setOf("Glacite Tunnels", "Great Glacite Lake", "Glacite Mineshaft")
        )
}
