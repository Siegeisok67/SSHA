package dev.ssha.hotm

import at.hannibal2.skyhanni.deps.moulconfig.Config
import at.hannibal2.skyhanni.deps.moulconfig.annotations.Accordion
import at.hannibal2.skyhanni.deps.moulconfig.annotations.Category
import at.hannibal2.skyhanni.deps.moulconfig.annotations.ConfigEditorBoolean
import at.hannibal2.skyhanni.deps.moulconfig.annotations.ConfigEditorButton
import at.hannibal2.skyhanni.deps.moulconfig.annotations.ConfigEditorDropdown
import at.hannibal2.skyhanni.deps.moulconfig.annotations.ConfigEditorText
import at.hannibal2.skyhanni.deps.moulconfig.annotations.ConfigOption
import at.hannibal2.skyhanni.deps.moulconfig.common.text.StructuredText
import at.hannibal2.skyhanni.deps.moulconfig.gui.MoulConfigEditor
import at.hannibal2.skyhanni.deps.moulconfig.observer.GetSetter
import at.hannibal2.skyhanni.deps.moulconfig.observer.Property
import at.hannibal2.skyhanni.deps.moulconfig.processor.ConfigProcessorDriver
import at.hannibal2.skyhanni.deps.moulconfig.processor.MoulConfigProcessor
import at.hannibal2.skyhanni.utils.ChatUtils
import at.hannibal2.skyhanni.utils.ConfigUtils
import com.google.gson.annotations.Expose

internal object AddonConfigMenu {
    fun open(
        settings: AddonSettingsData,
        save: () -> Unit,
        moveWidget: () -> Unit,
        resetPosition: () -> Unit,
        resetRate: () -> Unit,
        recheck: () -> Unit,
        moveGrinding: (GrindingMaterial) -> Unit,
        resetGrindingPosition: (GrindingMaterial) -> Unit,
        resetGrinding: (GrindingMaterial) -> Unit,
        movePowder: () -> Unit,
        resetPowderPosition: () -> Unit,
        resetPowder: () -> Unit,
        checkForUpdates: () -> Unit,
        installAvailableUpdate: () -> Unit,
    ) {
        val config = AddonMenuConfig(settings, save, moveWidget, resetPosition, resetRate, recheck,
            { message -> ChatUtils.chat(message) }, moveGrinding, resetGrindingPosition, resetGrinding,
            movePowder, resetPowderPosition, resetPowder, checkForUpdates, installAvailableUpdate)
        val processor = config.createProcessor()
        ConfigUtils.openEditor(MoulConfigEditor(processor))
    }
}

/** Public fields are required by MoulConfig's annotation-driven editor. */
class AddonMenuConfig internal constructor(
    settings: AddonSettingsData,
    private val save: () -> Unit,
    moveWidget: () -> Unit,
    resetPosition: () -> Unit,
    resetRate: () -> Unit,
    recheck: () -> Unit,
    notify: (String) -> Unit,
    moveGrinding: (GrindingMaterial) -> Unit = {},
    resetGrindingPosition: (GrindingMaterial) -> Unit = {},
    resetGrinding: (GrindingMaterial) -> Unit = {},
    movePowder: () -> Unit = {},
    resetPowderPosition: () -> Unit = {},
    resetPowder: () -> Unit = {},
    checkForUpdates: () -> Unit = {},
    installAvailableUpdate: () -> Unit = {},
) : Config() {
    @Expose @JvmField
    @Category(name = "About", desc = "SSHA version, stable updates, credits and project links.")
    val about = AboutMenuConfig().apply {
        setUpdateAction(checkForUpdates)
        setInstallAction(installAvailableUpdate)
    }

    @Expose @JvmField
    @Category(name = "Mining", desc = "Siege's SkyHanni Addons — HOTM and commissions")
    val mining = MiningMenuConfig(settings, save, moveWidget, resetPosition, resetRate, recheck, notify)

    @Expose @JvmField
    @Category(name = "Grinding", desc = "Separate compact material widgets. All trackers and popups start disabled.")
    val grinding = GrindingMenuConfig(settings.grinding, save, moveGrinding, resetGrindingPosition, resetGrinding, notify,
        settings.powder, movePowder, resetPowderPosition, resetPowder)

    internal fun createProcessor(): MoulConfigProcessor<AddonMenuConfig> = MoulConfigProcessor.withDefaults(this).also {
        ConfigProcessorDriver(it).processConfig(this)
    }

    override fun getTitle(): StructuredText = StructuredText.of("§bSSHA ${installedVersion()} §7by §eSiege §7— SkyHanni + Skyblocker-inspired QOL")
    override fun saveNow() = save()
}

class MiningMenuConfig internal constructor(
    settings: AddonSettingsData, save: () -> Unit, moveWidget: () -> Unit,
    resetPosition: () -> Unit, resetRate: () -> Unit, recheck: () -> Unit, notify: (String) -> Unit,
) {
    @Expose @JvmField
    @ConfigOption(name = "HOTM Widget", desc = "Tier progress, commission estimates, and active-time commissions per hour.")
    @Accordion
    val widget = WidgetMenuConfig(settings, save, moveWidget, resetPosition, resetRate, recheck, notify)
}

class WidgetMenuConfig internal constructor(
    private val settings: AddonSettingsData,
    private val save: () -> Unit,
    moveWidget: () -> Unit,
    resetPositionAction: () -> Unit,
    resetRateAction: () -> Unit,
    recheckAction: () -> Unit,
    private val notify: (String) -> Unit,
) {
    private fun <T> bound(get: () -> T, set: (T) -> Unit): Property<T> = Property.wrap(object : GetSetter<T> {
        override fun get(): T = get()
        override fun set(value: T) { set(value); save() }
    })

    @Expose @JvmField
    @ConfigOption(name = "Enabled", desc = "Show the HOTM widget. Your saved position and values are retained when disabled.")
    @ConfigEditorBoolean
    val enabled: Property<Boolean> = bound({ settings.hudEnabled }, { settings.hudEnabled = it })

    @Expose @JvmField
    @ConfigOption(name = "Pickaxe Ability Reset Title", desc = "Off by default. Show Pickaxe Ability Reset when the server cooldown changes to Available/Ready or explicitly resets it. Reads your actual remaining cooldown; never assumes a fixed duration or announces readiness from an estimated timer alone.")
    @ConfigEditorBoolean
    val pickaxeResetTitle: Property<Boolean> = bound({ settings.pickaxeResetTitle }, { settings.pickaxeResetTitle = it })

    @Expose @JvmField
    @ConfigOption(name = "Hourly Averages", desc = "Commissions/h uses partial progress and pauses after 20 seconds idle. HOTM XP/h measures actual received XP over eligible mining session time, including travel/claim/idle time to avoid inflated rates. XP timing starts on progress, first reward or /hotm capture; requires 60 seconds. Leaving mining/disconnecting excludes time.")
    @ConfigEditorBoolean
    val rateEnabled: Property<Boolean> = bound({ settings.rateEnabled }, { settings.rateEnabled = it })

    @Expose @JvmField
    @ConfigOption(name = "Smooth Hourly Averages", desc = "Use a 90-second time-based smoothing filter after the 60-second warmup to reduce sudden rate swings.")
    @ConfigEditorBoolean
    val smoothRates: Property<Boolean> = bound({ settings.smoothRates }, { settings.smoothRates = it })

    @Expose @JvmField
    @ConfigOption(name = "Commission Finish Estimates", desc = "Off by default. Estimate each commission's remaining time from its percentage gain over active time. Requires at least 10 seconds and observed progress; luck and travel can change the estimate.")
    @ConfigEditorBoolean
    val commissionEta: Property<Boolean> = bound({ settings.commissionEta }, { settings.commissionEta = it })

    @Expose @JvmField
    @ConfigOption(name = "XP per Commission", desc = "Estimated reward used for the remaining commission count (1 to 1,000,000,000). Apply using the button below.")
    @ConfigEditorText
    var commissionXp: String = settings.commissionXp.toString()

    @Expose @JvmField
    @ConfigOption(name = "Manual HOTM Tier", desc = "Your currently unlocked HOTM tier, not the locked tier you are working toward.")
    @ConfigEditorDropdown
    var tier: MenuHotmTier = MenuHotmTier.entries[(settings.hotmTier ?: 1).coerceIn(1, 10) - 1]

    @Expose @JvmField
    @ConfigOption(name = "Manual Earned XP", desc = "XP earned within your current tier, not lifetime XP. Apply manual values below to pause automatic capture.")
    @ConfigEditorText
    var earnedXp: String = settings.hotmTier?.let { current ->
        val required = HotmProgress.manualObservation(current, 0L).xpToNextTier
        settings.xpToNextTier?.let { remaining -> (checkNotNull(required) - remaining).coerceAtLeast(0L).toString() }
    } ?: "0"

    @JvmField
    @ConfigOption(name = "Apply Commission Reward", desc = "Validate and save XP per commission without changing capture mode.")
    @ConfigEditorButton(buttonText = "Apply")
    val applyReward = Runnable {
        val value = commissionXp.replace(",", "").trim().toLongOrNull()
        if (value == null || value !in 1L..1_000_000_000L) notify("§cXP per commission must be 1 to 1,000,000,000.")
        else { settings.commissionXp = value; save(); notify("§aCommission reward saved.") }
    }

    @JvmField
    @ConfigOption(name = "Apply Manual Progress", desc = "Save the selected unlocked tier and earned XP. Menu capture cannot overwrite manual mode.")
    @ConfigEditorButton(buttonText = "Apply")
    val applyManual = Runnable {
        val xp = earnedXp.replace(",", "").trim().toLongOrNull()
        if (xp == null) notify("§cEarned XP must be a whole number.")
        else try {
            settings.setManualProgress(tier.ordinal + 1, xp)
            save()
            notify("§aManual progress saved. Use Recheck to return to automatic capture.")
        } catch (exception: IllegalArgumentException) {
            notify("§c${exception.message}")
        }
    }

    @JvmField
    @ConfigOption(name = "Move Widget", desc = "Open SkyHanni's position editor. Drag to move, scroll to resize; close to save.")
    @ConfigEditorButton(buttonText = "Move")
    val move = Runnable { moveWidget() }

    @JvmField
    @ConfigOption(name = "Reset Position", desc = "Return the widget to its original top-left position and scale.")
    @ConfigEditorButton(buttonText = "Reset")
    val resetPosition = Runnable { resetPositionAction() }

    @JvmField
    @ConfigOption(name = "Recheck All Data", desc = "Clear stale captures, leave manual mode, and open /hotm to read fresh tier and XP. Commissions refresh from the live tab widget.")
    @ConfigEditorButton(buttonText = "Recheck")
    val recheck = Runnable { recheckAction() }

    @JvmField
    @ConfigOption(name = "Reset Hourly Average", desc = "Clear completed commissions and active time to start a new measurement.")
    @ConfigEditorButton(buttonText = "Reset")
    val resetRate = Runnable { resetRateAction() }

    @JvmField
    @ConfigOption(name = "Reset Event XP", desc = "Clear the estimated mining-event/mineshaft XP counter.")
    @ConfigEditorButton(buttonText = "Reset")
    val resetEvents = Runnable { settings.extraEventXp = 0L; save() }

    @JvmField
    @ConfigOption(name = "Reset All Tracking", desc = "Clear tier/XP captures, event XP, commissions, and hourly statistics. Keeps widget position, enable switches, and commission reward.")
    @ConfigEditorButton(buttonText = "Reset")
    val resetAll = Runnable {
        settings.resumeAutomaticProgress()
        settings.extraEventXp = 0L
        settings.activeCommissions = emptyList()
        resetRateAction()
        save()
        notify("§aTracking reset. Use Recheck to read fresh HOTM data.")
    }
}

enum class MenuHotmTier {
    TIER_1, TIER_2, TIER_3, TIER_4, TIER_5, TIER_6, TIER_7, TIER_8, TIER_9, TIER_10;
    override fun toString(): String = "HOTM ${ordinal + 1}"
}
