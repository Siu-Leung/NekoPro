package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdatePolicyTest {

    @Test
    fun semVerParsesStandardAndPrefixedFormats() {
        assertEquals(SemVer(1, 2, 1), SemVer.parseOrNull("Neko-Pro-1.2.1"))
        assertEquals(SemVer(1, 2, 10), SemVer.parseOrNull("Neko Pro v1.2.10"))
        assertEquals(SemVer(1, 3, 0, "rc.1"), SemVer.parseOrNull("v1.3.0-rc.1"))
        assertNull(SemVer.parseOrNull("preview"))
    }

    @Test
    fun updateAvailableHandlesMultiDigitPatchAndPreventsDowngrade() {
        val v1210 = SemVer.parseOrNull("v1.2.10")!!
        val v121 = SemVer.parseOrNull("v1.2.1")!!
        val v122Rc1 = SemVer.parseOrNull("v1.2.2-rc.1")!!
        val v122 = SemVer.parseOrNull("v1.2.2")!!

        // Previously broken by substring contains(): "Neko Pro v1.2.10".contains("1.2.1") was true.
        assertTrue(AppUpdatePolicy.isUpdateAvailable("Neko-Pro-1.2.1", v1210))
        assertFalse(AppUpdatePolicy.isUpdateAvailable("Neko-Pro-1.2.1", v121))
        // Newer local build must not prompt to downgrade to an older published release.
        assertFalse(AppUpdatePolicy.isUpdateAvailable("Neko-Pro-1.2.2", v121))
        // Stable release is newer than pre-release of the same version.
        assertTrue(AppUpdatePolicy.isUpdateAvailable("Neko-Pro-1.2.2-rc.1", v122))
        assertFalse(AppUpdatePolicy.isUpdateAvailable("Neko-Pro-1.2.2", v122Rc1))
    }

    @Test
    fun parseLatestReleaseValidatesUrlAndFallsBackSafely() {
        val valid = AppUpdatePolicy.parseLatestRelease(
            """
            {
              "tag_name": "v1.2.10",
              "name": "Neko Pro v1.2.10",
              "html_url": "https://github.com/Siu-Leung/NekoPro/releases/tag/v1.2.10",
              "draft": false
            }
            """.trimIndent()
        )
        assertEquals("v1.2.10", valid.tagName)
        assertEquals("Neko Pro v1.2.10", valid.displayName)
        assertEquals("https://github.com/Siu-Leung/NekoPro/releases/tag/v1.2.10", valid.releaseUrl)
        assertEquals(SemVer(1, 2, 10), valid.version)

        val untrustedUrl = AppUpdatePolicy.parseLatestRelease(
            """
            {
              "tag_name": "v1.2.10",
              "name": "Neko Pro v1.2.10",
              "html_url": "javascript:alert(1)"
            }
            """.trimIndent()
        )
        assertEquals(AppUpdatePolicy.RELEASES_PAGE_URL, untrustedUrl.releaseUrl)
    }

    @Test(expected = IllegalStateException::class)
    fun parseLatestReleaseRejectsGitHubRateLimitErrorPayload() {
        AppUpdatePolicy.parseLatestRelease("""{"message":"API rate limit exceeded"}""")
    }

    @Test(expected = IllegalStateException::class)
    fun parseLatestReleaseRejectsDraft() {
        AppUpdatePolicy.parseLatestRelease(
            """{"tag_name":"v1.3.0","name":"Neko Pro v1.3.0","draft":true}"""
        )
    }

    @Test
    fun allowedReleaseUrlRejectsSchemesAndForeignRepos() {
        assertTrue(AppUpdatePolicy.isAllowedReleaseUrl("https://github.com/Siu-Leung/NekoPro/releases/tag/v1.2.2"))
        assertFalse(AppUpdatePolicy.isAllowedReleaseUrl("http://github.com/Siu-Leung/NekoPro/releases/tag/v1.2.2"))
        assertFalse(AppUpdatePolicy.isAllowedReleaseUrl("https://evil.example/Siu-Leung/NekoPro/releases/tag/v1.2.2"))
        assertFalse(AppUpdatePolicy.isAllowedReleaseUrl("intent://github.com/Siu-Leung/NekoPro/releases/tag/v1.2.2"))
    }
}
