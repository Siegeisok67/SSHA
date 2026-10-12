package dev.ssha.hotm

import at.hannibal2.skyhanni.data.model.TabWidget
import at.hannibal2.skyhanni.utils.SkyBlockUtils
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
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.inventory.ContainerInput
import org.slf4j.LoggerFactory
import moe.nea.libautoupdate.CurrentVersion
import moe.nea.libautoupdate.PotentialUpdate
import moe.nea.libautoupdate.UpdateContext
import moe.nea.libautoupdate.UpdateTarget
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

class HotmAddon : ClientModInitializer {
    private var lastHoveredTooltip: List<String> = emptyList()
    private var lastHotmMenu: List<Pair<String, List<String>>> = emptyList()
    private lateinit var hudIntegration: HotmHudIntegration
    private lateinit var grindingIntegration: GrindingIntegration
    private val rateTracker = CommissionRateTracker()
    private val xpRateTracker = HotmXpRateTracker()
    private val pickaxeResetTracker = PickaxeResetTracker()
    private val autoRefresh = AutoHotmRefresh()
    private val pickaxeBanner = PickaxeReadyBanner()
    private val updateBanner = UpdateStatusBanner()
    private var commissionsScreenOpen = false
    // Silent-read menu that the Gui.setScreen mixin kept off screen; closed once its data arrives.
    private var hiddenMenu: AbstractContainerScreen<*>? = null
    private var settingsRequested = false
    private var nextStatsSaveAt = 0L
    private val logger = LoggerFactory.getLogger("SSHA Update")
    private var pendingUpdate: PotentialUpdate? = null
    private var updateCheckInProgress = false
    private var updateInstallInProgress = false
    private var loginUpdateChecked = false
    private var lastKnownLatestVersion: String? = null
    private var lastRateEnabled = true
    private var commissionsWasActive = false
    // The silent-read menu after we turned to page 2 for tier 5+ XP; null until that turn happens.
    private var pageTurnedFrom: List<Pair<String, List<String>>>? = null
    private var pageTurnedAt = 0L

    private companion object {
        const val PAGE_LOAD_MS = 1_500L
    }
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
        // Session stats survive restarts and updates: restore them, then copy them back on every save.
        rateTracker.restore(
            AddonSettings.data.commissionsCompleted,
            AddonSettings.data.commissionActiveMillis,
            AddonSettings.data.commissionWork,
        )
        xpRateTracker.restore(AddonSettings.data.hotmXpObserved, AddonSettings.data.hotmXpMeasuredMillis)
        AddonSettings.beforeSave = {
            AddonSettings.data.commissionsCompleted = rateTracker.completed
            AddonSettings.data.commissionActiveMillis = rateTracker.activeMillis
            AddonSettings.data.commissionWork = rateTracker.observedWork
            AddonSettings.data.hotmXpObserved = xpRateTracker.observedXp
            AddonSettings.data.hotmXpMeasuredMillis = xpRateTracker.measurementMillis
            if (::grindingIntegration.isInitialized) grindingIntegration.persistTrackers(AddonSettings.data)
        }
        lastRateEnabled = AddonSettings.data.rateEnabled
        hudIntegration = HotmHudIntegration({ AddonSettings.data }, { AddonSettings.save() }, ::hudLines, rateTracker::estimates)
        hudIntegration.register()
        grindingIntegration = GrindingIntegration({ AddonSettings.data }, { AddonSettings.save() }, ::nowMillis,
            {
                rateTracker.reset()
                xpRateTracker.reset()
                pickaxeResetTracker.reset()
                AddonSettings.data.resumeAutomaticProgress()
                AddonSettings.data.activeCommissions = emptyList()
                AddonSettings.save()
            })
        grindingIntegration.registerWidgets()
        LiveGameMessages.listen { message ->
            if (SkyBlockUtils.inSkyBlock) {
                message.lines().forEach { line ->
                    onGameMessage(line)
                    grindingIntegration.onGameMessage(line)
                    if (AddonSettings.data.pickaxeResetTitle && MiningArea.current()) {
                        if (pickaxeResetTracker.message(line, nowMillis())) showPickaxeReset()
                    }
                }
            }
        }
        ItemTooltipCallback.EVENT.register { stack, _, _, tooltip ->
            val screen = Minecraft.getInstance().gui.screen() as? AbstractContainerScreen<*> ?: return@register
            if (!HotmProgress.isHotmInventoryTitle(screen.title.string)) return@register
            recordMenu(menuItems(screen))
            recordHoveredTooltip(stack.hoverName.string, tooltip.map { it.string })?.let(::applyObservation)
        }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> onServerJoin() }
        ClientTickEvents.END_CLIENT_TICK.register(::onClientTick)
        // Saved on quit as well as every 10 seconds, so a restart or update never loses tracker totals.
        ClientLifecycleEvents.CLIENT_STOPPING.register { AddonSettings.save() }
        SilentHotmRead.install(::claimSilentMenu)
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(createCommand())
        }
        HudElementRegistry.attachElementBefore(
            VanillaHudElements.CHAT,
            Identifier.fromNamespaceAndPath("ssha_hotm_addon", "hotm_tracker"),
            { graphics, _ ->
                hudIntegration.render(graphics)
                grindingIntegration.render(graphics)
                pickaxeBanner.render(graphics, nowMillis())
                updateBanner.render(graphics, nowMillis())
            },
        )
    }

    private fun onClientTick(minecraft: Minecraft) {
        hudIntegration.tick(minecraft)
        grindingIntegration.tick(minecraft)
        val now = nowMillis()
        if (now >= nextStatsSaveAt) {
            nextStatsSaveAt = now + 10_000L
            AddonSettings.save()
        }
        SkyblockerCompatibility.update(AddonSettings.data.hudEnabled && minecraft.player != null)
        if (!AddonSettings.data.pickaxeResetTitle || minecraft.player == null || !MiningArea.current()) pickaxeResetTracker.reset()
        else if (TabWidget.PICKAXE_COOLDOWN.isActive &&
            pickaxeResetTracker.observe(TabWidget.PICKAXE_COOLDOWN.lines.map { it.string }, now)) showPickaxeReset()
        if (settingsRequested) {
            settingsRequested = false
            AddonConfigMenu.open(AddonSettings.data, { AddonSettings.save() },
                moveWidget = { hudIntegration.requestEditor() }, resetPosition = { hudIntegration.resetPosition() },
                resetRate = { rateTracker.reset(); xpRateTracker.reset(); commissionsWasActive = false }, recheck = { requestRecheck(minecraft) },
                moveGrinding = grindingIntegration::move, resetGrindingPosition = grindingIntegration::resetPosition,
                resetGrinding = grindingIntegration::reset, movePowder = grindingIntegration::movePowder,
                resetPowderPosition = grindingIntegration::resetPowderPosition, resetPowder = grindingIntegration::resetPowder,
                checkForUpdates = ::checkForUpdates, resetAllGrinding = grindingIntegration::resetAllSaved,
                updateStatus = updateStatusText())
        }
        if (AddonSettings.data.rateEnabled != lastRateEnabled) {
            rateTracker.suspend(now)
            lastRateEnabled = AddonSettings.data.rateEnabled
            xpRateTracker.reset()
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
        val xpEligible = minecraft.player != null && MiningArea.current() && AddonSettings.data.rateEnabled
        if (xpEligible && !rateTracker.isPaused(now)) xpRateTracker.start(now)
        // Paused commission time must not count toward the XP/h denominator, or the average drifts down.
        xpRateTracker.tick(now, xpEligible && !rateTracker.isPaused(now))
        val commissions = HotmProgress.activeCommissions(
            if (TabWidget.COMMISSIONS.isActive) TabWidget.COMMISSIONS.lines.map { it.string } else emptyList(),
        )
        val previousCommissions = AddonSettings.data.activeCommissions
        // A collected commission leaves the tab widget; re-read /hotm so the commissions-left estimate catches up.
        if (TabWidget.COMMISSIONS.isActive && commissions.size < previousCommissions.size) autoRefresh.request(now, 300L)
        if (commissions != previousCommissions) {
            AddonSettings.data.activeCommissions = commissions
            AddonSettings.save()
        }

        val container = minecraft.gui.screen() as? AbstractContainerScreen<*>
        // Commission claims happen in the King's menu; re-read /hotm once that menu closes.
        val commissionsOpen = container?.title?.string?.contains("Commissions", true) == true
        if (commissionsScreenOpen && !commissionsOpen) autoRefresh.request(now, 300L)
        commissionsScreenOpen = commissionsOpen
        autoRefresh.expire(now)
        // Manual progress means automatic capture is paused, so no silent /hotm reads until /ssha auto resumes it.
        if (!AddonSettings.data.manualProgress && autoRefresh.poll(now, guiOpen = minecraft.gui.screen() != null || hiddenMenu != null) && SkyBlockUtils.inSkyBlock) {
            minecraft.player?.connection?.sendCommand("hotm")
        }

        // A hidden silent-read menu never reached the screen; finish reading it, then close it.
        hiddenMenu?.let { hidden ->
            val items = menuItems(hidden)
            val hiddenObservation = recordMenu(items)?.also(::applyObservation)
            if (turnToXpPage(minecraft, hidden, items, hiddenObservation, now)) return@let
            if (autoRefresh.menuOpened(now, HotmProgress.readComplete(hiddenObservation))) {
                hiddenMenu = null
                pageTurnedFrom = null
                minecraft.player?.closeContainer()
            }
        }

        val menu = container ?: return
        if (!HotmProgress.isHotmInventoryTitle(menu.title.string)) return
        val observation = recordMenu(menuItems(menu))?.also(::applyObservation)
        // Close only the menu our silent read opened; a menu the player opened stays open.
        if (hiddenMenu == null && autoRefresh.menuOpened(now, HotmProgress.readComplete(observation))) minecraft.player?.closeContainer()
    }

    /** Every login surfaces a known pending update without another network check. Only the first
     * login per session hits the network, and it stays silent unless an update is actually found. */
    private fun onServerJoin() {
        pendingUpdate?.let { announcePendingUpdate(it); return }
        if (loginUpdateChecked) return
        loginUpdateChecked = true
        checkForUpdates(quiet = true)
    }

    /** Red installed → green latest, mirroring SkyHanni's About page; unknown until checked. */
    private fun updateStatusText(): String = updateStatusText(installedVersion(), lastKnownLatestVersion)

    internal fun updateStatusText(installed: String, latest: String?): String {
        latest ?: return "§7Installed: §f$installed §8— press Update to check for a newer release."
        return if (isNewerRelease(latest, installed)) "§c$installed §7➔ §a$latest"
        else "§a$installed §7(up to date)"
    }

    private fun checkForUpdates(quiet: Boolean = false) {
        if (updateCheckInProgress) {
            if (!quiet) notify("§eSSHA update check is already running.")
            return
        }
        val context = updateContext()
        if (context == null) {
            if (!quiet) notify("§eThe in-game updater requires SSHA installed as a JAR. Download updates manually from GitHub Releases.")
            return
        }
        pendingUpdate = null
        updateCheckInProgress = true
        if (!quiet) {
            updateBanner.show(nowMillis(), "Checking for SSHA updates…", "Installed: ${installedVersion()}", UpdateStatusBanner.GRAY)
            notify("§7Checking the latest stable SSHA release…")
        }
        setUpdateConnectionTimeouts()
        val future = runCatching { context.checkUpdate("full").orTimeout(20, TimeUnit.SECONDS) }.getOrElse { failure ->
            updateCheckInProgress = false
            logger.warn("SSHA update check failed to start", failure)
            if (!quiet) notify("§cSSHA update check could not start. You can download releases manually from GitHub.")
            return
        }
        future.whenComplete { update, error -> Minecraft.getInstance().execute {
            updateCheckInProgress = false
            if (error != null) {
                logger.warn("SSHA update check failed", error)
                if (!quiet) {
                    updateBanner.show(nowMillis(), "SSHA update check failed", "Installed version unchanged. Try again later.", UpdateStatusBanner.RED)
                    notify("§cSSHA update check failed. You can download releases manually from GitHub.")
                }
                return@execute
            }
            if (update == null || !update.isUpdateAvailable) {
                pendingUpdate = null
                lastKnownLatestVersion = installedVersion()
                if (!quiet) {
                    updateBanner.show(nowMillis(), "SSHA ${installedVersion()} is up to date", "No newer verified stable release.", UpdateStatusBanner.GREEN)
                    notify("§aSSHA is up to date, or no verified stable JAR release is available.")
                }
                return@execute
            }
            pendingUpdate = update
            lastKnownLatestVersion = targetVersion(update)
            updateBanner.show(nowMillis(), "SSHA ${installedVersion()} → ${targetVersion(update)} available", "Downloading and verifying…", UpdateStatusBanner.YELLOW)
            if (quiet) {
                // Logins announce; they never install without being asked.
                announcePendingUpdate(update)
            } else {
                // Check and install are one action: a verified newer release installs right away.
                notify("§eSSHA ${targetVersion(update)} is available. Installing it now.")
                installAvailableUpdate()
            }
        } }
    }

    private fun announcePendingUpdate(update: PotentialUpdate) {
        notify("§eSSHA ${targetVersion(update)} is available.")
        SshaChat.clickable(
            "§e§lClick here to install it.",
            hover = "§eDownload and verify the release; restart Minecraft manually to finish installation.",
            oneTimeClick = true,
            onClick = ::installAvailableUpdate,
        )
    }

    private fun installAvailableUpdate() {
        val update = pendingUpdate
        if (updateInstallInProgress) {
            notify("§eSSHA update installation is already running.")
            return
        }
        if (update == null || !update.isUpdateAvailable) {
            notify("§eCheck for updates first. Install Update is enabled only after a newer stable release is found.")
            return
        }
        if (!isNewerRelease(update.update.versionNumber?.asString ?: "", installedVersion())) {
            pendingUpdate = null
            notify("§eThat release is no longer newer. Check for updates again.")
            return
        }
        updateInstallInProgress = true
        notify("§7Downloading and verifying SSHA ${update.update.versionName}…")
        setUpdateConnectionTimeouts()
        val installFuture = runCatching { update.launchUpdate() }.getOrElse { failure ->
            updateInstallInProgress = false
            logger.warn("SSHA update install failed to start", failure)
            notify("§cSSHA update could not be installed. The current version remains active; try the GitHub release page.")
            return
        }
        installFuture.whenComplete { _, error -> Minecraft.getInstance().execute {
            updateInstallInProgress = false
            if (error != null) {
                logger.warn("SSHA update install failed", error)
                updateBanner.show(nowMillis(), "SSHA update failed", "The current version stays active.", UpdateStatusBanner.RED)
                notify("§cSSHA update could not be installed. The current version remains active; try the GitHub release page.")
            } else {
                pendingUpdate = null
                updateBanner.show(nowMillis(), "SSHA ${targetVersion(update)} is ready", "Restart Minecraft to finish installing.", UpdateStatusBanner.GREEN)
                notify("§aSSHA update downloaded and verified. Exit Minecraft to install it, then relaunch from your launcher.")
                SshaChat.clickable(
                    "§a§lClick here to restart your game!",
                    hover = "§eCloses Minecraft so the verified update can install. Relaunch from your launcher.",
                    oneTimeClick = true,
                    onClick = ::closeGameForRestart,
                )
            }
        } }
    }

    private fun targetVersion(update: PotentialUpdate): String =
        update.update.versionNumber?.asString?.trimStart('v', 'V') ?: update.update.versionName

    private fun closeGameForRestart() {
        Minecraft.getInstance().stop()
    }

    private fun updateContext(): UpdateContext? {
        val location = runCatching { HotmAddon::class.java.protectionDomain?.codeSource?.location?.toURI() }.getOrNull()
            ?: return null
        val installedJar = runCatching { File(location) }.getOrNull()?.takeIf {
            it.isFile && it.extension.equals("jar", ignoreCase = true)
        } ?: return null
        val target = UpdateTarget.deleteAndSaveInTheSameFolder(HotmAddon::class.java)
        if ((target as? moe.nea.libautoupdate.DeleteAndSaveInSameFolderUpdateTarget)?.file != installedJar) return null
        return UpdateContext(
            SshaReleaseUpdateSource(),
            target,
            object : CurrentVersion {
            private val installed = releaseVersion(installedVersion())
            override fun display(): String = installedVersion()
            override fun isOlderThan(element: com.google.gson.JsonElement?): Boolean {
                val latest = element?.takeIf { it.isJsonPrimitive }?.asString ?: return false
                val current = installed?.joinToString(".") ?: return false
                return isNewerRelease(latest, current)
            }
            },
            "ssha_hotm_addon",
        )
    }

    private fun autoReadPrompt(runAuto: () -> Unit) {
        SshaChat.clickable(
            "§eClick to run §6/ssha auto §eand read HOTM now.",
            hover = "§7Turns automatic capture back on and reads /hotm once.",
            onClick = runAuto,
        )
    }

    private fun notify(message: String) {
        SshaChat.chat(message)
    }

    private fun showPickaxeReset() {
        pickaxeBanner.show(nowMillis())
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
        val screen = Minecraft.getInstance().gui.screen() as? AbstractContainerScreen<*>
        receiveHotmMessage(message, nowMillis(), AddonSettings.data,
            screen?.title?.string?.contains("Commissions", true) == true, MiningArea.current(), AddonSettings::save)
        // Live reward chat is applied immediately; a silent menu read then reconciles the exact total.
        if (AutoHotmRefresh.isRewardMessage(message)) autoRefresh.request(nowMillis(), 500L)
    }

    internal fun receiveHotmMessage(message: String, now: Long, data: AddonSettingsData,
        claiming: Boolean, inMiningArea: Boolean, save: () -> Unit) {
        val completion = data.rateEnabled && inMiningArea && rateTracker.completionMessage(message, now)
        if (completion) xpRateTracker.completion(now)
        xpRateTracker.tick(now, data.rateEnabled && inMiningArea)
        if (xpRateTracker.chat(
                message, now, data.commissionXp, claiming = claiming,
            )) {
            xpRateTracker.lastReward?.let { reward ->
                if (!data.manualProgress) {
                    data.applyLiveReward(reward)
                    save()
                }
                rateTracker.rewardActivity(now, completion)
            }
        }
    }

    /**
     * Silent reads only: at tier 5+ the XP is on page 2, so turn the page once and wait briefly for its items.
     * Returns true while the menu should stay open. The player's own /hotm menu is never paged.
     */
    private fun turnToXpPage(
        minecraft: Minecraft,
        menu: AbstractContainerScreen<*>,
        items: List<Pair<String, List<String>>>,
        observation: HotmProgress.Observation?,
        now: Long,
    ): Boolean {
        val before = pageTurnedFrom
        if (before != null) return items == before && now - pageTurnedAt < PAGE_LOAD_MS
        if (!HotmProgress.needsSecondPageForXp(observation)) return false
        val nextPage = menu.menu.slots.firstOrNull {
            !it.item.isEmpty && HotmProgress.isNextPageItemName(it.item.hoverName.string)
        } ?: return false
        val player = minecraft.player ?: return false
        // Right-click, as the Next Page button expects; a left-click does not turn the page.
        minecraft.gameMode?.handleContainerInput(menu.menu.containerId, nextPage.index, 1, ContainerInput.PICKUP, player)
        pageTurnedFrom = items
        pageTurnedAt = now
        return true
    }

    /** Called from the Gui.setScreen mixin: hides the HOTM menu our silent read opened instead of showing it. */
    private fun claimSilentMenu(screen: Screen): Boolean {
        val container = screen as? AbstractContainerScreen<*> ?: return false
        if (!autoRefresh.awaitingMenu() || hiddenMenu != null) return false
        if (!HotmProgress.isHotmInventoryTitle(container.title.string)) return false
        hiddenMenu = container
        return true
    }

    private fun applyObservation(observation: HotmProgress.Observation) {
        val reconciled = if (!AddonSettings.data.manualProgress) xpRateTracker.observation(observation, nowMillis()) else observation
        if (AddonSettings.data.applyAutomaticObservation(reconciled, System.currentTimeMillis())) AddonSettings.save()
    }

    internal fun createCommand(
        settings: () -> AddonSettingsData = { AddonSettings.data },
        saveSettings: () -> Unit = { AddonSettings.save() },
        openGui: () -> Unit = { hudIntegration.requestEditor() },
        openSettings: () -> Unit = { settingsRequested = true },
        readHotm: () -> Unit = { autoRefresh.request(nowMillis(), 0L) },
        offerAutoRead: (() -> Unit) -> Unit = { autoReadPrompt(it) },
    ): LiteralArgumentBuilder<FabricClientCommandSource> {
        val command = LiteralArgumentBuilder.literal<FabricClientCommandSource>("ssha")
        val runAuto: () -> Unit = {
            settings().resumeAutomaticProgress()
            saveSettings()
            readHotm()
        }
        command.executes {
            openSettings()
            1
        }
        command.then(LiteralArgumentBuilder.literal<FabricClientCommandSource>("status").executes { context ->
            context.source.sendFeedback(Component.literal(statusText(settings())))
            if (settings().hotmTier == null || settings().xpToNextTier == null) offerAutoRead(runAuto)
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
                    runAuto()
                    context.source.sendFeedback(Component.literal("§aAutomatic capture enabled. Reading /hotm now; the menu opens and closes by itself."))
                    1
                },
        )
        command.then(
            LiteralArgumentBuilder.literal<FabricClientCommandSource>("tooltip")
                .executes { context ->
                    if (lastHoveredTooltip.isEmpty()) {
                        context.source.sendFeedback(Component.literal("§eOpen /hotm, hover the progress item, then run /ssha tooltip."))
                        offerAutoRead(runAuto)
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
                        offerAutoRead(runAuto)
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
            LiteralArgumentBuilder.literal<FabricClientCommandSource>("sacks")
                .executes { context ->
                    if (!::grindingIntegration.isInitialized) {
                        context.source.sendFeedback(Component.literal("§eGrinding trackers are not ready yet."))
                    } else {
                        context.source.sendFeedback(Component.literal("§6[SSHA] Sack state (copy these lines for diagnostics):"))
                        grindingIntegration.sackDiagnostics().forEach { line ->
                            context.source.sendFeedback(Component.literal(line))
                        }
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
    var progressFormatVersion: Int = 0,
    var manualProgress: Boolean = false,
    var commissionXp: Long = 750L,
    var hudEnabled: Boolean = true,
    var rateEnabled: Boolean = true,
    var smoothRates: Boolean = true,
    var commissionEta: Boolean = false,
    var pickaxeResetTitle: Boolean = false,
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
    var commissionsCompleted: Long = 0L,
    var commissionActiveMillis: Long = 0L,
    var commissionWork: Double = 0.0,
    var hotmXpObserved: Long = 0L,
    var hotmXpMeasuredMillis: Long = 0L,
    // Saved tracker totals per SkyBlock profile name; see GrindingIntegration.persistTrackers.
    var savedTrackers: MutableMap<String, MutableMap<String, SavedTracker>> = mutableMapOf(),
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
            // Adopt the new tier, but never discard a known XP sample for a tier-only
            // disagreement: page 1 and page 2 can briefly disagree (display buffs, layout
            // differences), and nuking here is what reset XP left back to the capture prompt.
            // A real tier-up is corrected as soon as an XP-bearing read arrives.
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
        GrindingMaterial.entries.forEach { material ->
            grinding[material].compactAt = grinding[material].compactAt.coerceIn(1L, 1_000_000_000_000L)
        }
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
    // Copies live session stats into [data] before each write.
    var beforeSave: () -> Unit = {}

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
            beforeSave()
            checkNotNull(file.parentFile).mkdirs()
            file.writeText(gson.toJson(data))
        } catch (exception: Exception) {
            logger.error("Could not save HOTM tracker settings to {}", file.absolutePath, exception)
        }
    }
}

private fun Long.prettyNumber(): String = String.format(Locale.US, "%,d", this)
