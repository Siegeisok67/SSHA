package dev.ssha.hotm

import at.hannibal2.skyhanni.deps.moulconfig.annotations.Accordion
import at.hannibal2.skyhanni.deps.moulconfig.annotations.ConfigEditorBoolean
import at.hannibal2.skyhanni.deps.moulconfig.annotations.ConfigEditorButton
import at.hannibal2.skyhanni.deps.moulconfig.annotations.ConfigEditorText
import at.hannibal2.skyhanni.deps.moulconfig.annotations.ConfigOption
import at.hannibal2.skyhanni.deps.moulconfig.observer.GetSetter
import at.hannibal2.skyhanni.deps.moulconfig.observer.Property
import com.google.gson.annotations.Expose

class GrindingMenuConfig internal constructor(
    private val settings: GrindingSettingsData, private val save: () -> Unit, private val move: (GrindingMaterial) -> Unit,
    private val resetPosition: (GrindingMaterial) -> Unit, private val reset: (GrindingMaterial) -> Unit, private val notify: (String) -> Unit,
    powderSettings: PowderSettingsData, movePowder: () -> Unit, resetPowderPosition: () -> Unit, resetPowder: () -> Unit,
) {
    private fun widget(material: GrindingMaterial) = GrindingWidgetMenuConfig(settings[material], save,
        { move(material) }, { resetPosition(material) }, { reset(material) }, notify)

    @Expose @JvmField @ConfigOption(name = "Gold", desc = "Enchanted gold blocks/hour from raw and enchanted sack gains.") @Accordion
    val gold = widget(GrindingMaterial.GOLD)
    @Expose @JvmField @ConfigOption(name = "Diamond", desc = "Enchanted diamond blocks/hour from raw and enchanted sack gains.") @Accordion
    val diamond = widget(GrindingMaterial.DIAMOND)
    @Expose @JvmField @ConfigOption(name = "Mithril", desc = "Refined mithril/hour equivalent: 160 enchanted mithril per refined mithril.") @Accordion
    val mithril = widget(GrindingMaterial.MITHRIL)
    @Expose @JvmField @ConfigOption(name = "Titanium", desc = "Refined titanium/hour equivalent: 16 enchanted titanium per refined titanium.") @Accordion
    val titanium = widget(GrindingMaterial.TITANIUM)
    @Expose @JvmField @ConfigOption(name = "Umber", desc = "Refined umber/hour equivalent: 160 enchanted umber per refined umber.") @Accordion
    val umber = widget(GrindingMaterial.UMBER)
    @Expose @JvmField @ConfigOption(name = "Tungsten", desc = "Refined tungsten/hour equivalent: 160 enchanted tungsten per refined tungsten.") @Accordion
    val tungsten = widget(GrindingMaterial.TUNGSTEN)
    @Expose @JvmField @ConfigOption(name = "Glacite Jewels", desc = "Sack gains plus inventory drops in mineshafts and walker areas. Three jewels per Bejeweled Handle for key crafting.") @Accordion
    val jewels = widget(GrindingMaterial.JEWELS)
    @Expose @JvmField @ConfigOption(name = "Mithril Powder", desc = "Dwarven Mines powder/hour, active time, event gains, and estimated Bazaar value from enchanted mithril/titanium and observed event drops. Not realized sale profit; no forge-time speculation.") @Accordion
    val powder = PowderWidgetMenuConfig(powderSettings, save, movePowder, resetPowderPosition, resetPowder)
}

class PowderWidgetMenuConfig internal constructor(
    settings: PowderSettingsData, save: () -> Unit, move: () -> Unit, resetPosition: () -> Unit, reset: () -> Unit,
) {
    private fun bound(get: () -> Boolean, set: (Boolean) -> Unit, save: () -> Unit) = Property.wrap(object : GetSetter<Boolean> {
        override fun get(): Boolean = get()
        override fun set(value: Boolean) { set(value); save() }
    })
    @Expose @JvmField @ConfigOption(name = "Enabled", desc = "Off by default. Track actual SkyHanni mithril powder gain events while in the Dwarven Mines; spending/refunds are not gains.") @ConfigEditorBoolean
    val enabled = bound({ settings.enabled }, { settings.enabled = it }, save)
    @Expose @JvmField @ConfigOption(name = "Include Commission Powder", desc = "Off by default. Include explicitly identified commission powder rewards in session powder and powder/hour. Mining and reward-event powder remain tracked.") @ConfigEditorBoolean
    val includeCommissionPowder = bound({ settings.includeCommissionPowder }, { settings.includeCommissionPowder = it }, save)
    @Expose @JvmField @ConfigOption(name = "Smooth Rate", desc = "Smooth hourly rates after 60 seconds of active time; idle pauses after 20 seconds.") @ConfigEditorBoolean
    val smoothRate = bound({ settings.smoothRate }, { settings.smoothRate = it }, save)
    @Expose @JvmField @ConfigOption(name = "Show Bazaar Value", desc = "Gross instant-sell estimate for compacted enchanted materials and observed event drops, before tax/costs. Missing prices are marked partial; powder has no coin value.") @ConfigEditorBoolean
    val showProfit = bound({ settings.showProfit }, { settings.showProfit = it }, save)
    @Expose @JvmField @ConfigOption(name = "Show Materials", desc = "Show session raw-equivalent mithril and titanium gains.") @ConfigEditorBoolean
    val showMaterials = bound({ settings.showMaterials }, { settings.showMaterials = it }, save)
    @Expose @JvmField @ConfigOption(name = "Show Events", desc = "Show Goblin Raid, Raffle, Mithril Gourmand rewards and powder during 2x Powder. Better Together/Gone with the Wind do not classify mining gains as event rewards.") @ConfigEditorBoolean
    val showEvents = bound({ settings.showEvents }, { settings.showEvents = it }, save)
    @JvmField @ConfigOption(name = "Move Widget", desc = "Move and resize this separate widget.") @ConfigEditorButton(buttonText = "Move")
    val move = Runnable { move() }
    @JvmField @ConfigOption(name = "Reset Position", desc = "Reset this widget's position and scale.") @ConfigEditorButton(buttonText = "Reset")
    val resetPosition = Runnable { resetPosition() }
    @JvmField @ConfigOption(name = "Reset Session", desc = "Clear powder, event gains, materials, estimated value, and active time.") @ConfigEditorButton(buttonText = "Reset")
    val resetSession = Runnable { reset() }
}

class GrindingWidgetMenuConfig internal constructor(
    private val settings: GrindingWidgetSettings, private val save: () -> Unit,
    move: () -> Unit, resetPositionAction: () -> Unit, resetAction: () -> Unit,
    private val notify: (String) -> Unit,
) {
    private fun bound(get: () -> Boolean, set: (Boolean) -> Unit): Property<Boolean> = Property.wrap(object : GetSetter<Boolean> {
        override fun get(): Boolean = get()
        override fun set(value: Boolean) { set(value); save() }
    })

    @Expose @JvmField @ConfigOption(name = "Enabled", desc = "Off by default. Start this material's separate widget and session tracker.") @ConfigEditorBoolean
    val enabled = bound({ settings.enabled }, { settings.enabled = it })
    @Expose @JvmField @ConfigOption(name = "Compact Popup", desc = "Off by default. Show a client-side reminder once stored raw-equivalent reaches your threshold. Rearms below the threshold; unknown sack totals never trigger.") @ConfigEditorBoolean
    val compactPopup = bound({ settings.compactPopup }, { settings.compactPopup = it })
    @Expose @JvmField @ConfigOption(name = "Warn At Raw Materials", desc = "Positive raw-equivalent amount (up to 1,000,000,000,000). Each enchanted item counts as 160 raw. Default 20,160 for minerals, 3 for jewels; sack sizes/upgrades vary. Apply below.") @ConfigEditorText
    var compactAt: String = settings.compactAt.toString()
    @JvmField @ConfigOption(name = "Apply Popup Threshold", desc = "Validate and save this material's warning threshold.") @ConfigEditorButton(buttonText = "Apply")
    val applyThreshold = Runnable {
        val amount = compactAt.replace(",", "").trim().toLongOrNull()
        if (amount == null || amount !in 1L..1_000_000_000_000L) notify("§cThreshold must be 1 to 1,000,000,000,000 raw materials.")
        else { settings.compactAt = amount; save(); notify("§aCompact popup threshold saved.") }
    }
    @Expose @JvmField @ConfigOption(name = "Smooth Rate", desc = "90-second smoothing after 60 seconds of active time. Pauses after 20 seconds without gains. All rates are session-only.") @ConfigEditorBoolean
    val smoothRate = bound({ settings.smoothRate }, { settings.smoothRate = it })
    @Expose @JvmField @ConfigOption(name = "Sack Overflow Warning", desc = "On by default for enabled trackers. Warn once when an item reaches its own sack limit. Open each sack to read stacked/upgraded capacity from its lore; rearms below the limit.") @ConfigEditorBoolean
    val overflowWarning = bound({ settings.overflowWarning }, { settings.overflowWarning = it })
    @Expose @JvmField @ConfigOption(name = "Show Stored Materials", desc = "Show known raw-equivalent holdings and individual raw/enchanted/product sack limits. Open relevant sacks to establish totals; unopened sacks are not assumed empty.") @ConfigEditorBoolean
    val showStored = bound({ settings.showStored }, { settings.showStored = it })
    @Expose @JvmField @ConfigOption(name = "Show Session Details", desc = "Off by default to keep the widget small. Show gained raw-equivalent and active time.") @ConfigEditorBoolean
    val showSession = bound({ settings.showSession }, { settings.showSession = it })
    @JvmField @ConfigOption(name = "Move Widget", desc = "Drag and scroll in SkyHanni's position editor; close to save.") @ConfigEditorButton(buttonText = "Move")
    val move = Runnable { move() }
    @JvmField @ConfigOption(name = "Reset Position", desc = "Reset only this widget's position and scale.") @ConfigEditorButton(buttonText = "Reset")
    val resetPosition = Runnable { resetPositionAction() }
    @JvmField @ConfigOption(name = "Reset Session", desc = "Clear only this material's hourly/session measurement. Keeps settings and position.") @ConfigEditorButton(buttonText = "Reset")
    val resetSession = Runnable { resetAction() }
}
