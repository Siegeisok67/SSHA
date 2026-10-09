package dev.ssha.hotm

import at.hannibal2.skyhanni.deps.moulconfig.annotations.ConfigEditorButton
import at.hannibal2.skyhanni.deps.moulconfig.annotations.ConfigOption
import at.hannibal2.skyhanni.utils.OSUtils
import net.fabricmc.loader.api.FabricLoader

internal fun installedVersion(): String = FabricLoader.getInstance().getModContainer("ssha_hotm_addon")
    .map { it.metadata.version.friendlyString }.orElse("development")

internal const val RELEASES_PAGE = "https://github.com/Siegeisok67/SSHA/releases/latest"

class AboutMenuConfig internal constructor() {
    @JvmField @ConfigOption(name = "Current Version", desc = "Installed version is shown in the menu title. Independent Siege's SkyHanni Addons; not an official SkyHanni or Skyblocker release.")
    @ConfigEditorButton(buttonText = "Releases")
    val version = Runnable { OSUtils.openBrowser("https://github.com/Siegeisok67/SSHA/releases") }

    @JvmField @ConfigOption(name = "Check for Updates", desc = "Check the latest stable SSHA release. When a newer version is available, install it and restart Minecraft manually.")
    @ConfigEditorButton(buttonText = "Check")
    val updates = Runnable { checkForUpdates() }

    private var checkForUpdates: () -> Unit = { OSUtils.openBrowser(RELEASES_PAGE) }

    internal fun setUpdateAction(action: () -> Unit) { checkForUpdates = action }

    @JvmField @ConfigOption(name = "Install Update", desc = "Download and verify the latest stable release. The replacement is installed when Minecraft exits; then relaunch your game from its launcher.")
    @ConfigEditorButton(buttonText = "Install")
    val installUpdate = Runnable { installAvailableUpdate() }

    private var installAvailableUpdate: () -> Unit = { OSUtils.openBrowser(RELEASES_PAGE) }

    internal fun setInstallAction(action: () -> Unit) { installAvailableUpdate = action }

    @JvmField @ConfigOption(name = "Credits", desc = "Created by Siege (Siegeisok67). SkyHanni provides event APIs and MoulConfig. Skyblocker inspired the compact teal HUD. SkyOcean inspired the sack-value workflow. Their source/assets are not bundled.")
    @ConfigEditorButton(buttonText = "Source")
    val source = Runnable { OSUtils.openBrowser("https://github.com/Siegeisok67/SSHA") }

    @JvmField @ConfigOption(name = "Feedback & Bugs", desc = "Report issues with your Minecraft, SSHA and SkyHanni versions and relevant screenshots. Do not include tokens or private logs.")
    @ConfigEditorButton(buttonText = "Issues")
    val issues = Runnable { OSUtils.openBrowser("https://github.com/Siegeisok67/SSHA/issues") }
}
