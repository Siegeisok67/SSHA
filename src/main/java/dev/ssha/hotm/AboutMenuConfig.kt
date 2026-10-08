package dev.ssha.hotm

import at.hannibal2.skyhanni.deps.moulconfig.annotations.ConfigEditorButton
import at.hannibal2.skyhanni.deps.moulconfig.annotations.ConfigEditorDropdown
import at.hannibal2.skyhanni.deps.moulconfig.annotations.ConfigOption
import at.hannibal2.skyhanni.deps.moulconfig.observer.GetSetter
import at.hannibal2.skyhanni.deps.moulconfig.observer.Property
import at.hannibal2.skyhanni.utils.OSUtils
import net.fabricmc.loader.api.FabricLoader
import com.google.gson.annotations.Expose

internal fun installedVersion(): String = FabricLoader.getInstance().getModContainer("ssha_hotm_addon")
    .map { it.metadata.version.friendlyString }.orElse("development")

enum class UpdateStream {
    RELEASES, ALL;
    override fun toString() = if (this == RELEASES) "Stable releases" else "All releases (incl. pre-release)"
}

class AboutMenuConfig internal constructor(settings: AddonSettingsData, save: () -> Unit) {
    @JvmField @ConfigOption(name = "Current Version", desc = "Installed version is shown in the menu title. Independent Siege's SkyHanni Addons; not an official SkyHanni or Skyblocker release.")
    @ConfigEditorButton(buttonText = "Releases")
    val version = Runnable { OSUtils.openBrowser("https://github.com/Siegeisok67/SSHA/releases") }

    @Expose @JvmField @ConfigOption(name = "Update Stream", desc = "Choose which release list to open below. Updates are downloaded manually; SSHA does not replace jars automatically.")
    @ConfigEditorDropdown
    val stream: Property<UpdateStream> = Property.wrap(object : GetSetter<UpdateStream> {
        override fun get() = settings.updateStream
        override fun set(value: UpdateStream) { settings.updateStream = value; save() }
    })

    @JvmField @ConfigOption(name = "Check for Updates", desc = "Open the selected GitHub release stream. Replace the previous SSHA jar; do not install two versions.")
    @ConfigEditorButton(buttonText = "Open")
    val updates = Runnable {
        OSUtils.openBrowser("https://github.com/Siegeisok67/SSHA/releases" + if (settings.updateStream == UpdateStream.RELEASES) "/latest" else "")
    }

    @JvmField @ConfigOption(name = "Credits", desc = "Created by Siege (Siegeisok67). SkyHanni provides event APIs and MoulConfig. Skyblocker inspired the compact teal HUD. SkyOcean inspired the sack-value workflow. Their source/assets are not bundled.")
    @ConfigEditorButton(buttonText = "Source")
    val source = Runnable { OSUtils.openBrowser("https://github.com/Siegeisok67/SSHA") }

    @JvmField @ConfigOption(name = "Feedback & Bugs", desc = "Report issues with your Minecraft, SSHA and SkyHanni versions and relevant screenshots. Do not include tokens or private logs.")
    @ConfigEditorButton(buttonText = "Issues")
    val issues = Runnable { OSUtils.openBrowser("https://github.com/Siegeisok67/SSHA/issues") }
}
