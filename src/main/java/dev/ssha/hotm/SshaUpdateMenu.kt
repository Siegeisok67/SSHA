package dev.ssha.hotm

import at.hannibal2.skyhanni.utils.OSUtils

/** State behind the About page's SkyHanni-style version header. HotmAddon keeps it in sync. */
internal object SshaUpdateMenu {
    var installed: String = "development"
    var latest: String? = null
    var checking: Boolean = false
    var onCheck: () -> Unit = {}
    var onInstall: () -> Unit = {}
    var onManualDownload: () -> Unit = { OSUtils.openBrowser(RELEASES_PAGE) }
    var onChangelog: () -> Unit = { OSUtils.openBrowser(RELEASES_PAGE) }

    fun hasUpdate(): Boolean {
        val current = latest ?: return false
        return isNewerRelease(current, installed)
    }

    /** Big header text: red installed ➔ green latest, green when current, quiet until checked. */
    fun versionText(): String = when {
        checking -> "§7Checking…"
        latest == null -> "§f$installed"
        hasUpdate() -> "§c$installed➜ §a$latest"
        else -> "§a$installed"
    }

    fun primaryLabel(): String = when {
        checking -> "Checking…"
        latest == null -> "Check for Updates"
        hasUpdate() -> "Install Update"
        else -> "Up to date"
    }

    /** The button below the version text only ever checks; installs go through chat. */
    fun checkLabel(): String = if (checking) "Checking…" else "Check for Updates"
}
