package dev.ssha.hotm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReleaseUpdatesTest {
    @Test
    fun `stable semantic numeric versions compare without prerelease ambiguity`() {
        assertEquals(listOf(1, 0, 0), releaseVersion("v1"))
        assertEquals(listOf(1, 2, 0), releaseVersion("1.2"))
        assertEquals(listOf(1, 2, 3), releaseVersion("1.2.3"))
        assertEquals(null, releaseVersion("1.2.3-beta"))
        assertEquals(null, releaseVersion("release-1.2.3"))
        assertTrue(isNewerRelease("1.10.0", "1.9.99"))
        assertFalse(isNewerRelease("1.9.9", "1.9.10"))
        assertFalse(isNewerRelease("1.2.0", "1.2.0"))
    }

    @Test
    fun `release source requires exact official jar and valid sha256 digest`() {
        val source = SshaReleaseUpdateSource()
        val digest = "sha256:" + "a".repeat(64)
        val valid = Release(
            tagName = "v1.2.0",
            name = "SSHA 1.2.0",
            assets = listOf(
                ReleaseAsset(
                    "ssha_1.2.0.jar",
                    "https://github.com/Siegeisok67/SSHA/releases/download/v1.2.0/ssha_1.2.0.jar",
                    digest,
                ),
            ),
        )
        val update = source.releaseUpdate(valid)
        assertEquals("v1.2.0", update?.versionNumber?.asString)
        assertEquals("a".repeat(64), update?.sha256)
        assertEquals(null, source.releaseUpdate(valid.copy(prerelease = true)))
        assertEquals(null, source.releaseUpdate(valid.copy(assets = listOf(
            ReleaseAsset("ssha_1.2.0-source.zip", "https://github.com/Siegeisok67/SSHA/releases/download/v1.2.0/ssha_1.2.0-source.zip", digest),
        ))))
        assertEquals(null, source.releaseUpdate(valid.copy(assets = listOf(
            ReleaseAsset("ssha_1.2.0.jar", "https://example.com/ssha_1.2.0.jar", digest),
        ))))
        assertEquals(null, source.releaseUpdate(valid.copy(assets = listOf(
            ReleaseAsset("ssha_1.2.0.jar", "https://github.com/Siegeisok67/SSHA/releases/download/v1.2.0/ssha_1.2.0.jar", "sha256:bad"),
        ))))
        assertEquals(null, source.releaseUpdate(valid.copy(assets = listOf(
            ReleaseAsset("ssha_1.2.0.jar", "https://github.com/Siegeisok67/SSHA/releases/download/v1.2.0/ssha_1.2.0.jar", null),
        ))))
        assertEquals(null, source.releaseUpdate(valid.copy(assets = listOf(
            ReleaseAsset("ssha_1.2.0.jar", "https://github.com/Siegeisok67/SSHA/releases/download/v1.2.0/ssha_1.2.0.jar", "sha256:bad"),
        ))))
    }
}
