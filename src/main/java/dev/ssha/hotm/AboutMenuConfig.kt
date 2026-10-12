package dev.ssha.hotm

import at.hannibal2.skyhanni.deps.moulconfig.annotations.ConfigEditorButton
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
    @Suppress("unused")
    @JvmField
    @ConfigOption(name = "Current Version", desc = "This is the SSHA version you are currently running")
    @SshaVersionDisplay
    @field:Transient
    var currentVersion: Unit? = null

    @JvmField
    @ConfigOption(name = "Release Notes", desc = "Open SSHA's latest GitHub release in your browser.")
    @ConfigEditorButton(buttonText = "Notes")
    val releaseNotes = Runnable { OSUtils.openBrowser(RELEASES_PAGE) }

    @JvmField
    @ConfigOption(name = "Feedback & Bugs", desc = "Report bugs with your version and a screenshot.")
    @ConfigEditorButton(buttonText = "Issues")
    val issues = Runnable { OSUtils.openBrowser(ISSUES_PAGE) }
}
