package dev.ssha.hotm

import com.google.gson.Gson
import com.google.gson.JsonPrimitive
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import moe.nea.libautoupdate.JsonUpdateSource
import moe.nea.libautoupdate.UpdateData
import moe.nea.libautoupdate.UpdateUtils
import java.net.URI
import java.net.URLConnection
import java.util.concurrent.CompletableFuture

internal class SshaReleaseUpdateSource : JsonUpdateSource() {
    override fun checkUpdate(updateStream: String): CompletableFuture<UpdateData?> {
        if (updateStream != "full") return CompletableFuture.completedFuture(null)
        val releaseType = object : TypeToken<Release>() {}.type
        return UpdateUtils.httpGet<Release?>(
            "https://api.github.com/repos/Siegeisok67/SSHA/releases/latest",
            Gson(),
            releaseType,
        ).thenApply(::releaseUpdate)
    }

    internal fun releaseUpdate(release: Release?): UpdateData? {
        release ?: return null
        if (release.draft || release.prerelease) return null
        val tag = release.tagName ?: return null
        val version = releaseVersion(tag) ?: return null
        val expectedAsset = "ssha_${version.joinToString(".")}.jar"
        val asset = release.assets?.firstOrNull { it.name == expectedAsset } ?: return null
        val download = asset.browserDownloadUrl ?: return null
        if (!isOfficialAssetUrl(download, tag, expectedAsset)) return null
        val sha256 = asset.digest
            ?.takeIf { it.startsWith("sha256:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.takeIf { it.matches(Regex("(?i)[0-9a-f]{64}")) }
            ?: return null

        return UpdateData(
            release.name?.takeIf(String::isNotBlank) ?: tag,
            JsonPrimitive(tag),
            sha256,
            download,
        )
    }

    private fun isOfficialAssetUrl(download: String, tag: String, asset: String): Boolean = try {
        val uri = URI(download)
        uri.scheme == "https" && uri.host.equals("github.com", ignoreCase = true) &&
            uri.rawPath == "/Siegeisok67/SSHA/releases/download/${encodePathSegment(tag)}/$asset" &&
            uri.rawQuery == null && uri.rawFragment == null
    } catch (_: Exception) {
        false
    }

    private fun encodePathSegment(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
}

internal data class Release(
    @SerializedName("tag_name") val tagName: String? = null,
    val name: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<ReleaseAsset>? = null,
)

internal data class ReleaseAsset(
    val name: String? = null,
    @SerializedName("browser_download_url") val browserDownloadUrl: String? = null,
    val digest: String? = null,
)

internal fun releaseVersion(version: String): List<Int>? {
    val match = Regex("^[vV]?(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?$").matchEntire(version.trim())
        ?: return null
    return (1..3).map { index ->
        val component = match.groupValues[index].ifEmpty { "0" }
        component.toIntOrNull() ?: return null
    }
}

internal fun isNewerRelease(latestVersion: String, installedVersion: String): Boolean {
    val latest = releaseVersion(latestVersion) ?: return false
    val installed = releaseVersion(installedVersion) ?: return false
    for (index in installed.indices) {
        val difference = latest[index] - installed[index]
        if (difference != 0) return difference > 0
    }
    return false
}

internal fun setUpdateConnectionTimeouts() {
    UpdateUtils.patchConnection { connection: URLConnection ->
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
    }
}
