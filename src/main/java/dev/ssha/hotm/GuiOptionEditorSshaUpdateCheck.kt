package dev.ssha.hotm

import at.hannibal2.skyhanni.config.core.elements.GuiElementButton
import at.hannibal2.skyhanni.deps.moulconfig.common.RenderContext
import at.hannibal2.skyhanni.deps.moulconfig.common.text.StructuredText
import at.hannibal2.skyhanni.deps.moulconfig.gui.CloseEventListener
import at.hannibal2.skyhanni.deps.moulconfig.gui.GuiOptionEditor
import at.hannibal2.skyhanni.deps.moulconfig.gui.KeyboardEvent
import at.hannibal2.skyhanni.deps.moulconfig.gui.MouseEvent
import at.hannibal2.skyhanni.deps.moulconfig.processor.ProcessedOption

/**
 * SkyHanni-style About header: big red ➔ green versions. When an update is known, Install Update,
 * Manually download and Show Changelog sit beside the version in that order. Check for Updates
 * sits below the version text and is always available.
 */
class GuiOptionEditorSshaUpdateCheck(option: ProcessedOption) : GuiOptionEditor(option), CloseEventListener {

    private val install = GuiElementButton().apply { text = "Install Update" }
    private val download = GuiElementButton().apply { text = "Manually download" }
    private val changelog = GuiElementButton().apply { text = "Show Changelog" }
    private val check = GuiElementButton()

    private companion object {
        const val INSTALL_Y = 10
        const val DOWNLOAD_Y = 30
        const val CHANGELOG_Y = 50
        const val CHECK_Y = 38
        const val HEIGHT = 74
    }

    override fun render(context: RenderContext, x: Int, y: Int, width: Int) {
        val fr = context.minecraft.defaultFontRenderer

        context.pushMatrix()
        context.translate(x.toFloat() + 10, y.toFloat())
        val adjustedWidth = width - 20
        val update = SshaUpdateMenu.hasUpdate()

        if (update) {
            install.width = install.getWidth(context)
            install.render(context, getInstallPosition(adjustedWidth), INSTALL_Y)
            download.width = download.getWidth(context)
            download.render(context, getDownloadPosition(adjustedWidth), DOWNLOAD_Y)
            changelog.width = changelog.getWidth(context)
            changelog.render(context, getChangelogPosition(adjustedWidth), CHANGELOG_Y)
        }

        val widthRemaining = if (update) {
            adjustedWidth - maxOf(install.width, download.width, changelog.width) - 10
        } else adjustedWidth

        context.scale(2F, 2F)
        context.drawStringCenteredScaledMaxWidth(
            SshaUpdateMenu.versionText().asStructuredText(),
            fr,
            widthRemaining / 4F,
            10F,
            true,
            widthRemaining / 2,
            -1,
        )

        context.popMatrix()
        check.text = SshaUpdateMenu.checkLabel()
        check.width = check.getWidth(context)
        // Below the version text; x is untranslated here so it lines up with the row.
        check.render(context, x + 10, y + CHECK_Y)
    }

    private fun getInstallPosition(width: Int) = width - install.width
    private fun getDownloadPosition(width: Int) = width - download.width
    private fun getChangelogPosition(width: Int) = width - changelog.width
    override fun getHeight(): Int = HEIGHT

    private var wasMouseButtonDown = false
    private var wasInsideInstall = false
    private var wasInsideDownload = false
    private var wasInsideChangelog = false
    private var wasInsideCheck = false

    override fun onAfterClose() {
        wasMouseButtonDown = false
        wasInsideInstall = false
        wasInsideDownload = false
        wasInsideChangelog = false
        wasInsideCheck = false
    }

    override fun mouseInput(
        x: Int,
        y: Int,
        width: Int,
        mouseX: Int,
        mouseY: Int,
        mouseEvent: MouseEvent,
    ): Boolean {
        val event = mouseEvent as? MouseEvent.Click ?: return false
        if (event.mouseButton != 0) return false

        fun isInside(width: Int, height: Int, def: GuiElementButton): Boolean {
            val inX = (mouseX - width - x) in (0..def.width)
            val inY = (mouseY - height - y) in (0..def.height)
            return inX && inY
        }

        val isMouseButtonDown = event.mouseState
        val update = SshaUpdateMenu.hasUpdate()
        val isInsideInstall = update && isInside(getInstallPosition(width - 20), height = INSTALL_Y, install)
        val isInsideDownload = update && isInside(getDownloadPosition(width - 20), height = DOWNLOAD_Y, download)
        val isInsideChangelog = update && isInside(getChangelogPosition(width - 20), height = CHANGELOG_Y, changelog)
        // The check button renders untranslated at x + 10, so its exact hit box needs the +10.
        val isInsideCheck = isInside(10, CHECK_Y, check)

        // We want to trigger the action when the user releases their mouse button, because this is
        // the standard expectation for most UIs
        val installClicked =
            wasInsideInstall && isInsideInstall && wasMouseButtonDown && !isMouseButtonDown
        val downloadClicked =
            wasInsideDownload && isInsideDownload && wasMouseButtonDown && !isMouseButtonDown
        val changelogClicked =
            wasInsideChangelog && isInsideChangelog && wasMouseButtonDown && !isMouseButtonDown
        val checkClicked =
            wasInsideCheck && isInsideCheck && wasMouseButtonDown && !isMouseButtonDown

        wasMouseButtonDown = isMouseButtonDown
        wasInsideInstall = isInsideInstall
        wasInsideDownload = isInsideDownload
        wasInsideChangelog = isInsideChangelog
        wasInsideCheck = isInsideCheck

        return when {
            installClicked -> {
                SshaUpdateMenu.onInstall()
                true
            }

            downloadClicked -> {
                SshaUpdateMenu.onManualDownload()
                true
            }

            changelogClicked -> {
                SshaUpdateMenu.onChangelog()
                true
            }

            checkClicked -> {
                if (!SshaUpdateMenu.checking) SshaUpdateMenu.onCheck()
                true
            }

            else -> false
        }
    }

    override fun keyboardInput(event: KeyboardEvent): Boolean {
        return false
    }

    override fun fulfillsSearch(word: String): Boolean {
        return super.fulfillsSearch(word) || word in "install" || word in "download" || word in "update" || word in "check"
    }

    private fun String.asStructuredText() = StructuredText.of(this)
}
