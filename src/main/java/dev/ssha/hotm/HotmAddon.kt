package dev.ssha.hotm

import at.hannibal2.skyhanni.data.ProfileStorageData
import at.hannibal2.skyhanni.data.model.TabWidget
import com.google.gson.GsonBuilder
import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.LongArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory
import java.io.File
import java.util.Locale

class HotmAddon : ClientModInitializer {
    private var lastHoveredTooltip: List<String> = emptyList()
    private var lastHotmMenu: List<Pair<String, List<String>>> = emptyList()
    private lateinit var hudIntegration: HotmHudIntegration
    private lateinit var grindingIntegration: GrindingIntegration
    private val rateTracker = CommissionRateTracker()
    private val xpRateTracker = HotmXpRateTracker()
    private var settingsRequested = false
    private var lastRateEnabled = true
    private var commissionsWasActive = false
    private fun nowMillis(): Long = System.nanoTime() / 1_000_000L

    internal fun recordMenu(items: List<Pair<String, List<String>>>): HotmProgress.Observation? {
        lastHotmMenu = items.filter { HotmProgress.isProgressItemName(it.first) }
        return HotmProgress.readMenuObservation(lastHotmMenu)
    }

    internal fun recordHoveredTooltip(name: String, lines: List<String>): HotmProgress.Observation? {
        lastHoveredTooltip = listOf(name) + lines
        // Use the whole menu's unlock status even when a future locked tier is hovered.
        val items = lastHotmMenu.filterNot { it.first == name } + (name to lines)
        return HotmProgress.readMenuObservation(items)
    }

    override fun onInitializeClient() {
        AddonSettings.load()
        lastRateEnabled = AddonSettings.data.rateEnabled
        hudIntegration = HotmHudIntegration({ AddonSettings.data }, { AddonSettings.save() }, ::hudLines, rateTracker::estimates)
        hudIntegration.register()
        grindingIntegration = GrindingIntegration({ AddonSettings.data }, { AddonSettings.save() }, ::nowMillis,
            { rateTracker.reset(); xpRateTracker.reset(); AddonSettings.data.resumeAutomaticProgress(); AddonSettings.data.activeCommissions = emptyList(); AddonSettings.save() },
            ::onGameMessage)
        grindingIntegration.registerWidgets()
        ItemTooltipCallback.EVENT.register { stack, _, _, tooltip ->
            val screen = Minecraft.getInstance().gui.screen() as? AbstractContainerScreen<*> ?: return@register
            if (!HotmProgress.isHotmInventoryTitle(screen.title.string)) return@register
            recordMenu(menuItems(screen))
            recordHoveredTooltip(stack.hoverName.string, tooltip.map { it.string })?.let(::applyObservation)
        }
        ClientTickEvents.END_CLIENT_TICK.register(::onClientTick)
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(createCommand())
        }
        HudElementRegistry.attachElementBefore(
            VanillaHudElements.CHAT,
            Identifier.fromNamespaceAndPath("ssha_hotm_addon", "hotm_tracker"),
            { graphics, _ -> hudIntegration.render(graphics); grindingIntegration.render(graphics) },
        )
    }

    private fun onClientTick(minecraft: Minecraft) {
        hudIntegration.tick(minecraft)
        grindingIntegration.tick(minecraft)
        val now = nowMillis()
        SkyblockerCompatibility.update(AddonSettings.data.hudEnabled && minecraft.player != null)
        if (settingsRequested) {
            settingsRequested = false
            AddonConfigMenu.open(AddonSettings.data, { AddonSettings.save() },
                moveWidget = { hudIntegration.requestEditor() }, resetPosition = { hudIntegration.resetPosition() },
                resetRate = { rateTracker.reset(); xpRateTracker.reset(); commissionsWasActive = false }, recheck = { requestRecheck(minecraft) },
                moveGrinding = grindingIntegration::move, resetGrindingPosition = grindingIntegration::resetPosition,
                resetGrinding = grindingIntegration::reset, movePowder = grindingIntegration::movePowder,
                resetPowderPosition = grindingIntegration::resetPowderPosition, resetPowder = grindingIntegration::resetPowder)
        }
        if (AddonSettings.data.rateEnabled != lastRateEnabled) {
            rateTracker.suspend(now)
            lastRateEnabled = AddonSettings.data.rateEnabled
            xpRateTracker.clearBaseline()
        }
        if (minecraft.player == null || !MiningArea.current() || !AddonSettings.data.rateEnabled) {
            if (commissionsWasActive) rateTracker.suspend(now)
            commissionsWasActive = false
        } else if (TabWidget.COMMISSIONS.isActive) {
            rateTracker.observe(TabWidget.COMMISSIONS.lines.map { it.string }, now)
            commissionsWasActive = true
        } else if (commissionsWasActive) {
            rateTracker.suspend(now)
            commissionsWasActive = false
        } else {
            rateTracker.tick(now)
        }
        xpRateTracker.update(rateTracker.activeMillis, rateTracker.observedWork, AddonSettings.data.commissionXp)
        val commissions = HotmProgress.activeCommissions(
            if (TabWidget.COMMISSIONS.isActive) TabWidget.COMMISSIONS.lines.map { it.string } else emptyList(),
        )
        if (commissions != AddonSettings.data.activeCommissions) {
            AddonSettings.data.activeCommissions = commissions
            AddonSettings.save()
        }

        val screen = minecraft.gui.screen() as? AbstractContainerScreen<*> ?: return
        if (!HotmProgress.isHotmInventoryTitle(screen.title.string)) return
        recordMenu(menuItems(screen))?.let(::applyObservation)
    }

    private fun menuItems(screen: AbstractContainerScreen<*>): List<Pair<String, List<String>>> =
        screen.menu.slots.asSequence()
            .map { it.item }
            .filterNot { it.isEmpty }
            .map { stack ->
                stack.hoverName.string to stack.get(DataComponents.LORE)?.lines.orEmpty().map { it.string }
            }
            .toList()

    private fun onGameMessage(message: String) {
        val now = nowMillis()
        val data = AddonSettings.data
        val completion = data.rateEnabled && MiningArea.current() && rateTracker.completionMessage(message, now)
        if (completion) xpRateTracker.completion(now)
        val screen = Minecraft.getInstance().gui.screen() as? AbstractContainerScreen<*>
        val claiming = screen?.title?.string?.contains("Commissions", true) == true ||
            message.replace(Regex("§."), "").contains("Visit the King to claim your rewards", true)
        if (xpRateTracker.chat(
                message, now, data.commissionXp, claiming = claiming,
                activeEvent = at.hannibal2.skyhanni.data.MiningEventsApi.getActiveEvent() != null,
            )) {
            xpRateTracker.lastReward?.let { reward ->
                if (!data.manualProgress) {
                    data.applyLiveReward(reward)
                    xpRateTracker.applyLiveRewardToBaseline(reward.xp)
                    AddonSettings.save()
                }
                rateTracker.rewardActivity(now, completion)
            }
        }
    }

    private fun applyObservation(observation: HotmProgress.Observation) {
        if (!AddonSettings.data.manualProgress) xpRateTracker.observation(observation, nowMillis())
        if (AddonSettings.data.applyAutomaticObservation(observation, System.currentTimeMillis())) AddonSettings.save()
    }

    internal fun createCommand(
        settings: () -> AddonSettingsData = { AddonSettings.data },
        saveSettings: () -> Unit = { AddonSettings.save() },
        openGui: () -> Unit = { hudIntegration.requestEditor() },
        openSettings: () -> Unit = { settingsRequested = true },
    ): LiteralArgumentBuilder<FabricClientCommandSource> {
        val command = LiteralArgumentBuilder.literal<FabricClientCommandSource>("ssha")
        command.executes {
            openSettings()
            1
        }
        command.then(LiteralArgumentBuilder.literal<FabricClientCommandSource>("status").executes { context ->
            context.source.sendFeedback(Component.literal(statusText(settings())))
            1
        })
        command.then(
            LiteralArgumentBuilder.literal<FabricClientCommandSource>("set")
                .then(
                    LiteralArgumentBuilder.literal<FabricClientCommandSource>("hotmxp")
                        .then(
                            RequiredArgumentBuilder.argument<FabricClientCommandSource, Long>(
                                "xp",
                                LongArgumentType.longArg(1L, 1_000_000_000L),
                            ).executes { context ->
                                val xp = LongArgumentType.getLong(context, "xp")
                                settings().commissionXp = xp
                                saveSettings()
                                context.source.sendFeedback(Component.literal("§aHOTM XP per commission set to ${xp.prettyNumber()}."))
                                1
                            },
                        ),
                ),
        )
        command.then(
            LiteralArgumentBuilder.literal<FabricClientCommandSource>("set")
                .then(
                    LiteralArgumentBuilder.literal<FabricClientCommandSource>("progress")
                        .then(
                            RequiredArgumentBuilder.argument<FabricClientCommandSource, Int>("tier", IntegerArgumentType.integer(1, 10))
                                .then(
                                    RequiredArgumentBuilder.argument<FabricClientCommandSource, Long>("earnedXp", LongArgumentType.longArg(0L))
                                        .executes { context ->
                                            val tier = IntegerArgumentType.getInteger(context, "tier")
                                            val earnedXp = LongArgumentType.getLong(context, "earnedXp")
                                            try {
                                                settings().setManualProgress(tier, earnedXp)
                                            } catch (exception: IllegalArgumentException) {
                                                throw SimpleCommandExceptionType(Component.literal(checkNotNull(exception.message))).create()
                                            }
                                            saveSettings()
                                            context.source.sendFeedback(Component.literal(statusText(settings())))
                                            context.source.sendFeedback(Component.literal("§aManual progress saved; automatic capture is paused. Use /ssha auto to resume."))
                                            1
                                        },
                                ),
                        ),
                ),
        )
        command.then(
            LiteralArgumentBuilder.literal<FabricClientCommandSource>("auto")
                .executes { context ->
                    settings().resumeAutomaticProgress()
                    saveSettings()
                    context.source.sendFeedback(Component.literal("§aAutomatic capture enabled. Open /hotm; tier progress is read without hovering."))
                    1
                },
        )
        command.then(
            LiteralArgumentBuilder.literal<FabricClientCommandSource>("tooltip")
                .executes { context ->
                    if (lastHoveredTooltip.isEmpty()) {
                        context.source.sendFeedback(Component.literal("§eOpen /hotm, hover the progress item, then run /ssha tooltip."))
                    } else {
                        context.source.sendFeedback(Component.literal("§6[SSHA] Last hovered /hotm item (copy these lines for diagnostics):"))
                        lastHoveredTooltip.distinct().forEach { line ->
                            context.source.sendFeedback(Component.literal(line))
                        }
                    }
                    1
                },
        )
        command.then(
            LiteralArgumentBuilder.literal<FabricClientCommandSource>("menu")
                .executes { context ->
                    if (lastHotmMenu.isEmpty()) {
                        context.source.sendFeedback(Component.literal("§eOpen /hotm once, close it, then run /ssha menu. No hovering needed."))
                    } else {
                        context.source.sendFeedback(Component.literal("§6[SSHA] Last /hotm menu tier items (raw text; no hovering needed):"))
                        lastHotmMenu.forEach { (name, lore) ->
                            context.source.sendFeedback(Component.literal("§6--- $name ---"))
                            lore.forEach { line -> context.source.sendFeedback(Component.literal(line)) }
                        }
                        context.source.sendFeedback(Component.literal("§6Capture result: ${HotmProgress.readMenuObservation(lastHotmMenu) ?: "No supported XP progress found"}"))
                    }
                    1
                },
        )
        command.then(
            LiteralArgumentBuilder.literal<FabricClientCommandSource>("gui")
                .executes {
                    openGui()
                    1
                },
        )
        command.then(
            LiteralArgumentBuilder.literal<FabricClientCommandSource>("hud")
                .then(
                    RequiredArgumentBuilder.argument<FabricClientCommandSource, Boolean>("enabled", BoolArgumentType.bool())
                        .executes { context ->
                            settings().hudEnabled = BoolArgumentType.getBool(context, "enabled")
                            saveSettings()
                            context.source.sendFeedback(
                                Component.literal("§aHOTM tracker HUD ${if (settings().hudEnabled) "enabled" else "disabled"}."),
                            )
                            1
                        },
                ),
        )
        command.then(
            LiteralArgumentBuilder.literal<FabricClientCommandSource>("clear-events")
                .executes { context ->
                    settings().extraEventXp = 0L
                    saveSettings()
                    context.source.sendFeedback(Component.literal("§aCleared tracked mining-event / mineshaft XP."))
                    1
                },
        )
        return command
    }

    private fun statusText(settings: AddonSettingsData): String {
        val tier = settings.hotmTier?.let { "HotM $it" } ?: "Open /hotm to read your HotM tier"
        val xp = settings.xpToNextTier?.let { "${it.prettyNumber()} XP to next tier" } ?: "XP progress not captured yet"
        val commissions = settings.xpToNextTier?.let { HotmProgress.commissionsRemaining(it, settings.commissionXp) }
        val count = when {
            settings.xpToNextTier == null -> "Open /hotm; use /ssha menu if XP is missing"
            settings.hotmTier == 10 -> "max tier"
            commissions == null -> "Set /ssha set hotmxp <xp>"
            else -> "~$commissions commission${if (commissions == 1L) "" else "s"} left"
        }
        val mode = if (settings.manualProgress) " §7[manual]" else ""
        return "§6[SSHA] §e$tier$mode §7| §b$xp §7| §d$count §7| §6${settings.extraEventXp.prettyNumber()} event XP tracked"
    }

    private fun requestRecheck(minecraft: Minecraft) {
        AddonSettings.data.resumeAutomaticProgress()
        xpRateTracker.clearBaseline()
        AddonSettings.data.activeCommissions = emptyList()
        rateTracker.suspend(nowMillis())
        commissionsWasActive = false
        lastHoveredTooltip = emptyList()
        lastHotmMenu = emptyList()
        val screen = minecraft.gui.screen() as? AbstractContainerScreen<*>
        if (screen != null && HotmProgress.isHotmInventoryTitle(screen.title.string)) {
            recordMenu(menuItems(screen))?.let(::applyObservation)
        } else {
            minecraft.player?.connection?.sendCommand("hotm")
        }
        AddonSettings.save()
    }

    private fun hudLines(): List<Component> = HotmHudDisplay.lines(
        AddonSettings.data,
        if (TabWidget.COMMISSIONS.isActive) TabWidget.COMMISSIONS.lines else emptyList(),
        rateTracker,
        nowMillis(),
        xpRateTracker,
    )
}

internal data class AddonSettingsData(
    var updateStream: UpdateStream = UpdateStream.RELEASES,
    var progressFormatVersion: Int = 0,
    var manualProgress: Boolean = false,
    var commissionXp: Long = 750L,
    var hudEnabled: Boolean = true,
    var rateEnabled: Boolean = true,
    var smoothRates: Boolean = true,
    var commissionEta: Boolean = false,
    var hudPosition: HudPositionData = HudPositionData(),
    var grinding: GrindingSettingsData = GrindingSettingsData(),
    var powder: PowderSettingsData = PowderSettingsData(),
    var eventAttributionVersion: Int = 0,
    var hotmTier: Int? = null,
    var xpToNextTier: Long? = null,
    var extraEventXp: Long = 0L,
    var activeCommissions: List<String> = emptyList(),
    var pendingCommissionCompletions: Int = 0,
    var lastCommissionCompletionAtMs: Long = 0L,
) {
    fun setManualProgress(tier: Int, earnedXp: Long) {
        val observation = HotmProgress.manualObservation(tier, earnedXp)
        hotmTier = observation.tier
        xpToNextTier = observation.xpToNextTier
        manualProgress = true
        progressFormatVersion = 1
        clearPendingCommissions()
    }

    fun resumeAutomaticProgress() {
        manualProgress = false
        hotmTier = null
        xpToNextTier = null
        clearPendingCommissions()
    }

    fun applyAutomaticObservation(observation: HotmProgress.Observation, now: Long): Boolean {
        if (manualProgress) return false
        val previous = HotmProgress.Observation(hotmTier, xpToNextTier)
        if (observation.tier != null && observation.tier != hotmTier) {
            // A new tier invalidates the old XP sample; never combine different tiers.
            xpToNextTier = null
            clearPendingCommissions()
        }
        // Menu deltas cannot reliably distinguish base rewards, daily bonuses, and events.
        // Only explicitly identified live event reward messages add to extraEventXp.
        observation.tier?.let { hotmTier = it }
        observation.xpToNextTier?.let { xpToNextTier = it }
        return previous != HotmProgress.Observation(hotmTier, xpToNextTier)
    }

    fun applyLiveReward(reward: HotmXpRateTracker.Reward) {
        if (manualProgress) return
        val current = HotmProgress.Observation(hotmTier, xpToNextTier)
        val advanced = HotmProgress.advance(current, reward.xp)
        if (current != advanced) { hotmTier = advanced.tier; xpToNextTier = advanced.xpToNextTier }
        if (reward.eventXp > 0L) extraEventXp += reward.eventXp
    }

    fun prepareAfterLoad() {
        GrindingMaterial.entries.forEach { material -> grinding[material].compactAt = grinding[material].compactAt.coerceIn(1L, 1_000_000_000_000L) }
        if (eventAttributionVersion < 1) { extraEventXp = 0L; eventAttributionVersion = 1 }
        commissionXp = commissionXp.coerceAtLeast(1L)
        pendingCommissionCompletions = pendingCommissionCompletions.coerceIn(0, 100)
        if (progressFormatVersion < 1) {
            // Old captures mixed unrelated menu items. Keep preferences, not unreliable progress.
            resumeAutomaticProgress()
            progressFormatVersion = 1
        }
    }

    private fun clearPendingCommissions() {
        pendingCommissionCompletions = 0
        lastCommissionCompletionAtMs = 0L
    }
}

private object AddonSettings {
    private val logger = LoggerFactory.getLogger("SSHA HOTM Tracker")
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val file = File(Minecraft.getInstance().gameDirectory, "config/ssha-hotm-addon.json")
    var data = AddonSettingsData(progressFormatVersion = 1)

    fun load() {
        if (!file.exists()) return
        try {
            gson.fromJson(file.readText(), AddonSettingsData::class.java)?.let { loaded ->
                data = loaded
                data.prepareAfterLoad()
            }
        } catch (exception: Exception) {
            logger.error("Could not load HOTM tracker settings from {}", file.absolutePath, exception)
        }
    }

    fun save() {
        try {
            checkNotNull(file.parentFile).mkdirs()
            file.writeText(gson.toJson(data))
        } catch (exception: Exception) {
            logger.error("Could not save HOTM tracker settings to {}", file.absolutePath, exception)
        }
    }
}

private fun Long.prettyNumber(): String = String.format(Locale.US, "%,d", this)
