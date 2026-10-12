package dev.ssha.hotm

import at.hannibal2.skyhanni.deps.moulconfig.annotations.ConfigEditorButton
import at.hannibal2.skyhanni.deps.moulconfig.annotations.ConfigEditorInfoText
import at.hannibal2.skyhanni.deps.moulconfig.annotations.ConfigOption
import at.hannibal2.skyhanni.utils.OSUtils
import net.fabricmc.loader.api.FabricLoader

internal fun installedVersion(): String = FabricLoader.getInstance().getModContainer("ssha_hotm_addon")
    .map { it.metadata.version.friendlyString }.orElse("development")

internal const val RELEASES_PAGE = "https://github.com/Siegeisok67/SSHA/releases/latest"
internal const val SOURCE_PAGE = "https://github.com/Siegeisok67/SSHA"
internal const val ISSUES_PAGE = "https://github.com/Siegeisok67/SSHA/issues"

/** About actions shown directly in the About category. The installed version is in the menu title. */
class AboutMenuConfig internal constructor() {
    @JvmField
    @ConfigOption(
        name = "About SSHA",
        desc = "Siege's SkyHanni Addons. Independent of SkyHanni and Skyblocker; your installed version is shown in the menu title.",
    )
    @ConfigEditorInfoText
    val intro: String = ""

    @JvmField
    @ConfigOption(
        name = "Update Status",
        desc = "Installed version against the latest known stable release. Red means outdated, green means current. Press Update to refresh.",
    )
    @ConfigEditorInfoText
    var updateStatus: String = "§7Not checked yet."

    @JvmField
    @ConfigOption(
        name = "Check & Install Update",
        desc = "Check the latest stable release. If it is newer, it is downloaded and verified, then installs when Minecraft exits. Pre-releases are ignored and nothing runs in the background.",
    )
    @ConfigEditorButton(buttonText = "Update")
    val updates = Runnable { checkForUpdates() }

    @JvmField
    @ConfigOption(name = "Show Changelog", desc = "Open the latest SSHA GitHub release notes in your browser.")
    @ConfigEditorButton(buttonText = "Changelog")
    val changelog = Runnable { OSUtils.openBrowser(RELEASES_PAGE) }

    @JvmField
    @ConfigOption(name = "Manually download", desc = "Open the GitHub releases page to download the jar yourself.")
    @ConfigEditorButton(buttonText = "Download")
    val manualDownload = Runnable { OSUtils.openBrowser(RELEASES_PAGE) }

    @JvmField
    @ConfigOption(name = "Release Notes", desc = "Open SSHA's latest GitHub release in your browser.")
    @ConfigEditorButton(buttonText = "Notes")
    val releaseNotes = Runnable { OSUtils.openBrowser(RELEASES_PAGE) }

    @JvmField
    @ConfigOption(name = "Source Code", desc = "Open the SSHA repository on GitHub.")
    @ConfigEditorButton(buttonText = "Source")
    val source = Runnable { OSUtils.openBrowser(SOURCE_PAGE) }

    @JvmField
    @ConfigOption(name = "Feedback & Bugs", desc = "Report bugs with your version and a screenshot.")
    @ConfigEditorButton(buttonText = "Issues")
    val issues = Runnable { OSUtils.openBrowser(ISSUES_PAGE) }

    private var checkForUpdates: () -> Unit = { OSUtils.openBrowser(RELEASES_PAGE) }

    internal fun setUpdateAction(action: () -> Unit) { checkForUpdates = action }
    internal fun setUpdateStatusText(text: String) { updateStatus = text }
}
