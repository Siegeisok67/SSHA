package dev.ssha.hotm

import at.hannibal2.skyhanni.api.HotmApi
import at.hannibal2.skyhanni.data.IslandType
import at.hannibal2.skyhanni.data.ProfileStorageData
import at.hannibal2.skyhanni.utils.InventoryUtils
import at.hannibal2.skyhanni.data.MiningEventsApi
import at.hannibal2.skyhanni.events.mining.PowderEvent
import at.hannibal2.skyhanni.features.inventory.bazaar.BazaarApi.getBazaarData
import at.hannibal2.skyhanni.utils.NeuInternalName.Companion.toInternalName
import at.hannibal2.skyhanni.api.event.HandleEvent
import at.hannibal2.skyhanni.api.event.SkyHanniEvents
import at.hannibal2.skyhanni.data.ItemAddManager
import at.hannibal2.skyhanni.data.SackApi
import at.hannibal2.skyhanni.data.title.TitleManager
import at.hannibal2.skyhanni.events.ItemAddEvent
import at.hannibal2.skyhanni.events.ProfileJoinEvent
import at.hannibal2.skyhanni.events.ProfileDataReadyEvent
import at.hannibal2.skyhanni.events.SackChangeEvent
import at.hannibal2.skyhanni.events.SackOpenEvent
import at.hannibal2.skyhanni.events.InventoryUpdatedEvent
import at.hannibal2.skyhanni.events.InventoryOpenEvent
import at.hannibal2.skyhanni.events.mining.MiningEventEvent
import at.hannibal2.skyhanni.utils.ItemUtils.getInternalName
import at.hannibal2.skyhanni.utils.ItemUtils.getCleanLore
import at.hannibal2.skyhanni.skyhannimodule.SkyHanniModule
import at.hannibal2.skyhanni.utils.SkyBlockUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@SkyHanniModule
internal class GrindingIntegration(
    private val settings: () -> AddonSettingsData,
    private val save: () -> Unit,
    private val now: () -> Long,
    private val resetHotmSession: () -> Unit,
) {
    private val trackers = GrindingMaterial.entries.associateWith(::GrindingTracker)
    private val widgets = GrindingMaterial.entries.associateWith { material ->
        HotmHudIntegration(settings, save, { lines(material) },
            enabled = { settings().grinding[material].enabled && MiningArea.current() },
            editorEnabled = { settings().grinding[material].enabled },
            storedPosition = { settings().grinding[material].position },
            widgetName = "SSHA ${material.displayName} Grinding", compactTitle = material.displayName)
    }
    private val sacks = SackSnapshot()
    private val craftedProducts = mutableMapOf<GrindingMaterial, Long>()
    private val powderTracker = MithrilPowderTracker()
    private val powderAccounting = PowderAccounting()
    private val powderWidget = HotmHudIntegration(settings, save, ::powderLines,
        enabled = { settings().powder.enabled && powderArea() }, editorEnabled = { settings().powder.enabled }, storedPosition = { settings().powder.position },
        widgetName = "SSHA Mithril Powder Grinding", compactTitle = "Mithril Powder")
    private var powderEnabled = false
    private var registered = false
    private var lastSnapshotAt = Long.MIN_VALUE
    private var lastWarningSnapshotAt = Long.MIN_VALUE
    private var inSkyblock = false
    private var lastIsland = IslandType.NONE
    private var profileReadyPrevious = false
    private var insidePowderChestMessage = false
    private val enabled = mutableMapOf<GrindingMaterial, Boolean>()
    private val popupAt = mutableMapOf<GrindingMaterial, Long>()

    fun registerWidgets() { widgets.values.forEach { it.register() }; powderWidget.register() }

    fun tick(minecraft: Minecraft) {
        // Register after SkyHanni handlers are initialized. Its @HandleEvent registration
        // removes cached EventHandlers for affected event types, so do not dirty predicates here.
        if (!registered) {
            // Existing event handlers may already be cached. Unregister invalidates those
            // handler snapshots before this instance is added to their listener collections.
            SkyHanniEvents.unregister(this)
            SkyHanniEvents.register(this)
            registered = true
        }
        val time = now()
        val active = minecraft.player != null && MiningArea.current()
        if (SkyBlockUtils.currentIsland != lastIsland) {
            trackers.values.forEach { it.suspend(time) }
            powderTracker.suspend(time)
            powderAccounting.reset()
            lastIsland = SkyBlockUtils.currentIsland
            lastSnapshotAt = Long.MIN_VALUE
            lastWarningSnapshotAt = Long.MIN_VALUE
            insidePowderChestMessage = false
        }
        val snapshot = active && (lastSnapshotAt == Long.MIN_VALUE || time - lastSnapshotAt >= 1_000L)
        val warningSnapshot = active && (lastWarningSnapshotAt == Long.MIN_VALUE || time - lastWarningSnapshotAt >= 1_000L)
        val profileReady = ProfileStorageData.sackProfiles != null
        if (profileReady && !profileReadyPrevious) {
            trackers.values.forEach { it.reset() }
            popupAt.clear()
            powderTracker.reset()
        powderAccounting.reset()
        }
        profileReadyPrevious = profileReady
        val amounts = if (snapshot && profileReady) SackApi.sackData.entries.associate { (id, item) ->
            id.asString() to item.amount.toLong().takeIf { item.statusIsCorrectOrAlright() }
        } else emptyMap()
        if (snapshot) lastSnapshotAt = time
        if (warningSnapshot) lastWarningSnapshotAt = time
        GrindingMaterial.entries.forEach { material ->
            val config = settings().grinding[material]
            val tracker = checkNotNull(trackers[material])
            val trackerEnabled = config.enabled || config.compactPopup
            if (enabled[material] != trackerEnabled || (!active && inSkyblock)) tracker.suspend(time)
            enabled[material] = trackerEnabled
            if (active && trackerEnabled) tracker.tick(time)
            if (snapshot && (trackerEnabled || tracker.storedRaw != null)) tracker.stored(
                sacks[material.rawId]?.amount ?: amounts[material.rawId],
                material.enchantedId?.let { sacks[it]?.amount ?: amounts[it] },
                sacks[material.enchantingId]?.amount ?: amounts[material.enchantingId], config.compactAt,
            )
            val committedAt = tracker.latestCommittedAt
            val eligible = warningSnapshot && active
            if (config.compactPopup && tracker.shouldWarn(config, eligible)) {
                popupAt[material] = committedAt
                TitleManager.sendTitle<GrindingTitle>(material.popupText,
                    subtitleText = if (material in setOf(GrindingMaterial.GOLD, GrindingMaterial.DIAMOND)) ""
                    else "${material.displayName}: ${format(checkNotNull(tracker.storedRaw))} raw-equivalent",
                    duration = 3.seconds)
            }
            listOfNotNull(material.rawId, material.enchantedId, material.enchantingId).forEach { id ->
                if (active && trackerEnabled && sacks.overflow(id, config.overflowWarning)) {
                    TitleManager.sendTitle<GrindingTitle>("Sack overflow!", subtitleText = material.displayName, duration = 3.seconds)
                }
            }
            checkNotNull(widgets[material]).tick(minecraft)
        }
        val trackPowder = active && (settings().powder.enabled || settings().grinding.mithril.enabled || settings().grinding.titanium.enabled) && powderArea()
        if (powderEnabled && !trackPowder) { powderTracker.suspend(time); powderAccounting.reset() }
        if (trackPowder) {
            powderAccounting.drain(time, settings().powder.includeCommissionPowder) { gain, at -> powderTracker.gainPowder(gain, at) }
            powderTracker.tick(time)
        }
        powderEnabled = trackPowder
        powderWidget.tick(minecraft)
        inSkyblock = active
    }

    @HandleEvent(onlyOnSkyblock = true)
    fun onSackChange(event: SackChangeEvent) {
        val time = now()
        val deltas = event.sackChanges.groupBy { it.internalName.asString() }
            .mapValues { (_, changes) -> changes.sumOf { it.delta.toLong() } }
        if (event.otherItemsAdded || event.otherItemsRemoved) sacks.invalidateUnknown(deltas.keys)
        deltas.forEach { (id, delta) -> sacks.change(id, delta) }
        GrindingMaterial.entries.forEach { material ->
            val config = settings().grinding[material]
            val inArea = if (material == GrindingMaterial.JEWELS) {
                jewelIsland(SkyBlockUtils.currentIsland) || SkyBlockUtils.graphArea in setOf("Glacite Tunnels", "Great Glacite Lake")
            } else MiningArea.current()
            if ((config.enabled || config.compactPopup) && inArea) {
                checkNotNull(trackers[material]).changes(deltas, time, event.otherItemsRemoved)
            }
        }
        if ((settings().powder.enabled || settings().grinding.mithril.enabled || settings().grinding.titanium.enabled) && powderArea()) {
            powderTracker.sack(deltas, time, event.otherItemsRemoved)
        }
        lastSnapshotAt = Long.MIN_VALUE
        lastWarningSnapshotAt = Long.MIN_VALUE
    }

    @HandleEvent(onlyOnSkyblock = true)
    fun onItemAdd(event: ItemAddEvent) {
        if (MiningArea.current() && event.source == ItemAddManager.Source.ITEM_ADD &&
            !InventoryUtils.inInventory() && event.amount > 0) {
            GrindingMaterial.entries.filter { it != GrindingMaterial.JEWELS }.forEach { material ->
                val config = settings().grinding[material]
                if ((config.enabled || config.compactPopup) && material.weight(event.internalName.asString()) > 0L) {
                    checkNotNull(trackers[material]).changes(mapOf(event.internalName.asString() to event.amount.toLong()), now())
                }
            }
        }
        if ((settings().powder.enabled || settings().grinding.mithril.enabled || settings().grinding.titanium.enabled) && powderArea() && event.source == ItemAddManager.Source.ITEM_ADD &&
            !InventoryUtils.inInventory() && event.amount > 0) {
            val itemId = event.internalName.asString()
            if (itemId in miningMaterialIds) {
                powderTracker.inventory(itemId, event.amount.toLong(), now(), eventDrop = false)
            }
        }
        if ((!settings().grinding.jewels.enabled && !settings().grinding.jewels.compactPopup) ||
            event.source != ItemAddManager.Source.ITEM_ADD || event.internalName.asString() != GrindingMaterial.JEWELS.rawId || event.amount <= 0) return
        // SkyHanni filters sack withdrawals and menu movements. Also reject container pickups
        // and non-grinding areas rather than counting trades/chests as jewel drops.
        if (InventoryUtils.inInventory()) return
        val island = SkyBlockUtils.currentIsland
        if (!jewelIsland(island) && SkyBlockUtils.graphArea !in setOf("Glacite Tunnels", "Great Glacite Lake")) return
        checkNotNull(trackers[GrindingMaterial.JEWELS]).changes(mapOf("GLACITE_JEWEL" to event.amount.toLong()), now())
    }

    @HandleEvent
    fun onProfileJoin(event: ProfileJoinEvent) {
        if (event.name.isEmpty()) return
        trackers.values.forEach { it.reset() }
        popupAt.clear()
        sacks.clear(); craftedProducts.clear()
        powderTracker.reset()
        powderAccounting.reset()
        profileReadyPrevious = false
        lastSnapshotAt = Long.MIN_VALUE
        lastWarningSnapshotAt = Long.MIN_VALUE
        resetHotmSession()
    }

    @HandleEvent
    fun onProfileDataReady(event: ProfileDataReadyEvent) {
        trackers.values.forEach { it.reset() }
        popupAt.clear()
        sacks.clear(); craftedProducts.clear()
        powderTracker.reset()
        powderAccounting.reset()
        profileReadyPrevious = true
        lastSnapshotAt = Long.MIN_VALUE
        lastWarningSnapshotAt = Long.MIN_VALUE
    }

    fun onGameMessage(text: String) {
        if (!SkyBlockUtils.inSkyBlock) return
        val screen = Minecraft.getInstance().gui.screen() as? net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<*>
        if (screen?.title?.string?.contains("Commissions", true) == true) powderAccounting.claiming(now())
        powderAccounting.message(text, now())
        Regex("^You Supercrafted (.+?)(?: x([0-9][0-9,]*))?!$").matchEntire(text.trim())?.let { match ->
            val name = match.groupValues[1].trim()
            val material = GrindingMaterial.entries.firstOrNull { it.productId.replace('_', ' ').equals(name, true) }
            if (material != null && settings().grinding[material].enabled && MiningArea.current()) {
                val amount = match.groupValues[2].replace(",", "").toLongOrNull() ?: 1L
                craftedProducts[material] = (craftedProducts[material] ?: 0L) + amount
            }
        }
        when {
            text.trim() == "CHEST LOCKPICKED" || text.trim() == "LOOT CHEST COLLECTED" -> {
                insidePowderChestMessage = SkyBlockUtils.currentIsland == IslandType.CRYSTAL_HOLLOWS
                return
            }
            insidePowderChestMessage && text.contains("▬▬▬▬▬▬▬▬▬▬") -> {
                insidePowderChestMessage = false
                return
            }
            !insidePowderChestMessage -> return
        }
        val reward = parsePowderChestReward(text) ?: return
        if (settings().powder.enabled && powderArea()) {
            powderTracker.eventDrop(reward.first, reward.second, now())
        }
    }

    @HandleEvent(onlyOnSkyblock = true)
    fun onPowderGain(event: PowderEvent.Gain) {
        if (settings().powder.enabled && powderArea() && event.powder == HotmApi.PowderType.MITHRIL) {
            // API reports the actual gain, including buffs: never multiply 2x powder again.
            val screen = Minecraft.getInstance().gui.screen() as? net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<*>
            if (screen?.title?.string?.contains("Commissions", true) == true) powderAccounting.claiming(now())
            powderAccounting.delta(event.amount, now(), MiningEventsApi.getActiveEvent()?.type)
        }
    }

    @HandleEvent
    fun onMiningEventEnded(event: MiningEventEvent.Ended) {
        powderAccounting.eventEnded(event.event.type, now())
    }

    @HandleEvent
    fun onSackOpen(event: SackOpenEvent) = readSack(event.inventoryOpenEvent)

    @HandleEvent
    fun onInventoryUpdated(event: InventoryUpdatedEvent) {
        if (event.inventoryName.endsWith(" Sack")) readSack(event)
    }

    private fun readSack(event: InventoryOpenEvent) {
        val items = event.inventoryItems.values.toList()
        Minecraft.getInstance().execute {
            items.forEach { item -> sacks.read(item.getInternalName().asString(), item.getCleanLore()) }
            lastSnapshotAt = Long.MIN_VALUE
            lastWarningSnapshotAt = Long.MIN_VALUE
        }
    }

    fun movePowder() = powderWidget.requestEditor()
    fun resetPowderPosition() = powderWidget.resetPosition(8, 500)
    fun resetPowder() { powderTracker.reset(); powderAccounting.reset() }
    fun render(graphics: GuiGraphicsExtractor) { widgets.values.forEach { it.render(graphics) }; powderWidget.render(graphics) }

    private fun powderArea(): Boolean = MiningArea.current()
    private var lastValueAt = Long.MIN_VALUE
    private var cachedValue = MithrilPowderTracker.Valuation(0.0, emptySet())
    private fun powderLines(): List<Component> = buildList {
        val config = settings().powder
        fun row(text: String) { add(Component.literal(text)) }
        fun number(value: Double?) = value?.let { String.format(Locale.US, "%,.0f", it) } ?: "Collecting…"
        row("§fPowder/h: §a${number(powderTracker.powderPerHour(config.smoothRate))}")
        row("§7Powder: §a${format(powderTracker.powder)} §8| §7${powderTracker.activeTime()}${if (powderTracker.paused(now())) " §8Ⅱ" else ""}")
        if (config.showMaterials) row("§7Mithril: §b${format(powderTracker.mithrilRaw)} §8| §7Titanium: §f${format(powderTracker.titaniumRaw)}")
        if (config.showEvents) {
            val event = MiningEventsApi.getActiveEvent()?.type?.takeIf(PowderAccounting::relevant)?.name?.replace('_', ' ') ?: "None"
            row("§7Event: §e$event")
            row("§7Powder during events: §a${format(powderTracker.powderDuringEvents)}")
        }
        if (config.showProfit) {
            val time = now()
            if (lastValueAt == Long.MIN_VALUE || time - lastValueAt >= 1_000L) {
                cachedValue = powderTracker.valuation { id ->
                    id.toInternalName().getBazaarData()?.instantSellPrice
                }
                lastValueAt = time
            }
            val incomplete = if (cachedValue.missing.isNotEmpty()) " §8(partial BZ)" else ""
            row("§7Est. BZ value: §6${number(cachedValue.coins)}$incomplete")
            row("§7Est. Profit/h: §6${number(powderTracker.profitPerHour(cachedValue.coins, config.smoothRate))}")
        }
    }
    fun move(material: GrindingMaterial) { checkNotNull(widgets[material]).requestEditor() }
    fun resetPosition(material: GrindingMaterial) {
        checkNotNull(widgets[material]).resetPosition(8, 150 + material.ordinal * 50)
    }
    fun reset(material: GrindingMaterial) { checkNotNull(trackers[material]).reset(); popupAt.remove(material); craftedProducts.remove(material); lastSnapshotAt = Long.MIN_VALUE; lastWarningSnapshotAt = Long.MIN_VALUE }

    private fun lines(material: GrindingMaterial): List<Component> = buildList {
        val tracker = checkNotNull(trackers[material])
        val config = settings().grinding[material]
        val rawRate = tracker.perHour(config.smoothRate)
        val divisor = if (material == GrindingMaterial.JEWELS) 1L else material.rawPerProduct
        val value = rawRate?.let { String.format(Locale.US, "%.2f", it / divisor) } ?: "Collecting…"
        add(Component.literal("§f${material.rateLabel}: §a$value${if (tracker.paused(now())) " §8Ⅱ" else ""}"))
        if (material == GrindingMaterial.JEWELS && rawRate != null) {
            add(Component.literal("§7Handles/h: §b${String.format(Locale.US, "%.2f", rawRate / 3L)}"))
        }
        if (config.showStored) add(Component.literal("§7Stored (known): §e${tracker.storedRaw?.let(::format) ?: "Open sacks"}"))
        val productPrice = material.productId.toInternalName().getBazaarData()?.instantSellPrice?.takeIf { it.isFinite() && it > 0.0 }
        val productRate = rawRate?.div(material.rawPerProduct)
        val profit = if (productPrice != null && productRate != null) String.format(Locale.US, "%,.0f", productPrice * productRate) else "Collecting…"
        add(Component.literal("§7Est. Profit/h: §6$profit"))
        if (config.showStored) {
            val limits = listOfNotNull(
                sacks[material.rawId]?.let { "Raw ${format(it.limit)}" },
                material.enchantedId?.let { sacks[it] }?.let { "E. ${format(it.limit)}" },
                sacks[material.enchantingId]?.let { "Product ${format(it.limit)}" },
            )
            add(Component.literal("§7Sack Limit: §e${limits.joinToString(" / ").ifEmpty { "Open sacks" }}"))
        }
        if (config.showSession) {
            val products = tracker.totalRaw.toDouble() / material.rawPerProduct
            val coins = productPrice?.let { String.format(Locale.US, "%,.0f", it * products) } ?: "No BZ price"
            add(Component.literal("§7Product equiv.: §b${String.format(Locale.US, "%.2f", products)} §8| §6$coins coins"))
            craftedProducts[material]?.let { made ->
                val madeCoins = productPrice?.let { String.format(Locale.US, "%,.0f", it * made) } ?: "No BZ price"
                add(Component.literal("§7Crafted: §b${format(made)} §8| §6$madeCoins coins"))
            }
            add(Component.literal("§7Session: §e${format(tracker.totalRaw)} §8| §7${tracker.activeTime()}"))
        }
    }

    private enum class GrindingTitle { COMPACT }
    companion object {
        internal fun jewelIsland(island: IslandType): Boolean = island == IslandType.MINESHAFT || island == IslandType.DWARVEN_MINES
        private val miningMaterialIds = setOf(
            "MITHRIL_ORE", "ENCHANTED_MITHRIL", "TITANIUM_ORE", "ENCHANTED_TITANIUM",
            "UMBER", "ENCHANTED_UMBER", "TUNGSTEN", "ENCHANTED_TUNGSTEN",
        )
        private fun format(value: Long): String = String.format(Locale.US, "%,d", value)
        private val chestRewardPattern = Regex("^ {4}(.+?)(?: x([0-9][0-9,]*))?$")
        private val eventDropNames = setOf(
            "PREHISTORIC_EGG", "SLUDGE_JUICE", "OIL_BARREL", "JUNGLE_HEART", "TREASURITE", "YOGGIE",
            "GOBLIN_EGG", "GOBLIN_EGG_GREEN", "GOBLIN_EGG_BLUE", "GOBLIN_EGG_RED", "GOBLIN_EGG_YELLOW",
            "CONTROL_SWITCH", "ELECTRON_TRANSMITTER", "FTX_3070", "ROBOTRON_REFLECTOR", "SUPERLITE_MOTOR", "SYNTHETIC_HEART",
            "ROUGH_RUBY_GEM", "FLAWED_RUBY_GEM", "FINE_RUBY_GEM", "FLAWLESS_RUBY_GEM",
            "ROUGH_JADE_GEM", "FLAWED_JADE_GEM", "FINE_JADE_GEM", "FLAWLESS_JADE_GEM",
            "ROUGH_AMBER_GEM", "FLAWED_AMBER_GEM", "FINE_AMBER_GEM", "FLAWLESS_AMBER_GEM",
            "ROUGH_SAPPHIRE_GEM", "FLAWED_SAPPHIRE_GEM", "FINE_SAPPHIRE_GEM", "FLAWLESS_SAPPHIRE_GEM",
            "ROUGH_AMETHYST_GEM", "FLAWED_AMETHYST_GEM", "FINE_AMETHYST_GEM", "FLAWLESS_AMETHYST_GEM",
            "ROUGH_TOPAZ_GEM", "FLAWED_TOPAZ_GEM", "FINE_TOPAZ_GEM", "FLAWLESS_TOPAZ_GEM",
            "WISHING_COMPASS", "ASCENSION_ROPE", "PICKONIMBUS", "ESSENCE_GOLD", "ESSENCE_DIAMOND", "GEMSTONE_POWDER",
        )
        internal fun parsePowderChestReward(message: String): Pair<String, Long>? {
            val match = chestRewardPattern.matchEntire(message) ?: return null
            val name = match.groupValues[1].replace(Regex("§."), "").trim()
                .replace(Regex("^[^\\p{L}]+"), "").trim()
            val id = when (name) {
                "Gemstone Powder" -> "GEMSTONE_POWDER"
                "Prehistoric Egg" -> "PREHISTORIC_EGG"
                "Control Switch" -> "CONTROL_SWITCH"
                "Robotron Reflector" -> "ROBOTRON_REFLECTOR"
                "Green Goblin Egg" -> "GOBLIN_EGG_GREEN"
                "Blue Goblin Egg" -> "GOBLIN_EGG_BLUE"
                "Red Goblin Egg" -> "GOBLIN_EGG_RED"
                "Yellow Goblin Egg" -> "GOBLIN_EGG_YELLOW"
                "FTX 3070" -> "FTX_3070"
                "Pickonimbus 2000" -> "PICKONIMBUS"
                "Gold Essence" -> "ESSENCE_GOLD"
                "Diamond Essence" -> "ESSENCE_DIAMOND"
                else -> name.uppercase(Locale.ROOT).replace(' ', '_')
            }
            if (id !in eventDropNames) return null
            val amount = match.groupValues[2].takeIf { it.isNotEmpty() }?.replace(",", "")?.toLongOrNull() ?: 1L
            return id to amount
        }
    }
}
