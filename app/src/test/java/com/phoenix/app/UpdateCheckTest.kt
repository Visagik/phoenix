package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {
    @Test fun newerVersionIsDetected() {
        assertTrue(UpdateCheck.isNewer("v3.9.65", "3.9.64"))
        assertTrue(UpdateCheck.isNewer("3.10.0", "3.9.64"))
        assertTrue(UpdateCheck.isNewer("v4.0", "3.9.64"))
        assertTrue(UpdateCheck.isNewer("3.9.64.1", "3.9.64"))
    }

    @Test fun sameOrOlderVersionIsNotNewer() {
        assertFalse(UpdateCheck.isNewer("v3.9.64", "3.9.64"))
        assertFalse(UpdateCheck.isNewer("v3.9.63", "3.9.64"))
        assertFalse(UpdateCheck.isNewer("3.9", "3.9.0"))
        assertFalse(UpdateCheck.isNewer("v3.9.9", "3.9.10"))
    }

    @Test fun unreadableVersionNeverCallsForUpdate() {
        assertFalse(UpdateCheck.isNewer("nightly", "3.9.64"))
        assertFalse(UpdateCheck.isNewer("v3.9.65", "?"))
        assertFalse(UpdateCheck.isNewer("", ""))
    }

    @Test fun suffixAfterDashIsIgnored() {
        assertTrue(UpdateCheck.isNewer("v3.9.65-debug", "3.9.64"))
        assertEquals(listOf(3, 9, 64), UpdateCheck.numbers("v3.9.64-debug"))
    }

    @Test fun releaseJsonIsParsed() {
        val json = """{"url":"x","tag_name":"v3.9.65","html_url":"https://github.com/Visagik/phoenix/releases/tag/v3.9.65",
            "assets":[{"name":"p.apk","browser_download_url":"https://github.com/Visagik/phoenix/releases/download/v3.9.65/phoenix-3.9.65-debug.apk"}],
            "body":"Строка 1\nСтрока 2 \"в кавычках\""}"""
        val r = UpdateCheck.parse(json)
        assertNotNull(r)
        assertEquals("v3.9.65", r!!.tag)
        assertEquals("https://github.com/Visagik/phoenix/releases/tag/v3.9.65", r.url)
        assertTrue(r.apkUrl.endsWith("phoenix-3.9.65-debug.apk"))
        assertTrue(r.notes.contains("Строка 2 \"в кавычках\""))
        assertTrue(r.notes.contains('\n'))
    }

    @Test fun badOrForeignJsonIsRejected() {
        assertNull(UpdateCheck.parse("{}"))
        assertNull(UpdateCheck.parse("<html>rate limit</html>"))
        assertNull(UpdateCheck.parse("""{"message":"Not Found"}"""))
    }

    @Test fun foreignReleaseLinkIsReplacedWithOurPage() {
        val r = UpdateCheck.parse("""{"tag_name":"v9.9.9","html_url":"https://evil.example/x"}""")
        assertEquals(UpdateCheck.PAGE_URL, r!!.url)
        assertEquals("", r.apkUrl)
    }

    @Test fun sameVersionIsNotAnnouncedTwice() {
        assertTrue(UpdateCheck.shouldNotify("v3.9.65", "3.9.64", ""))
        assertFalse(UpdateCheck.shouldNotify("v3.9.65", "3.9.64", "v3.9.65"))
        assertTrue(UpdateCheck.shouldNotify("v3.9.66", "3.9.64", "v3.9.65"))
        assertFalse(UpdateCheck.shouldNotify("v3.9.64", "3.9.64", ""))
    }

    @Test fun apkChecksumAndSizeAreTakenFromTheSameAsset() {
        val a = "a".repeat(64); val b = "b".repeat(64)
        val json = """{"tag_name":"v3.9.65","assets":[
            {"name":"other.apk","size":111,"digest":"sha256:$a","browser_download_url":"https://github.com/Visagik/phoenix/releases/download/v3.9.65/other.apk"},
            {"name":"p.apk","uploader":{"login":"x"},"size":222,"digest":"sha256:$b","browser_download_url":"https://github.com/Visagik/phoenix/releases/download/v3.9.65/p.apk"}]}"""
        val r = UpdateCheck.parse(json)!!
        assertTrue(r.apkUrl.endsWith("other.apk")) // берётся первый APK, значит и его сумма
        assertEquals(a, r.apkSha256)
        assertEquals(111L, r.apkSize)
    }

    @Test fun releaseWithoutDigestStillParses() {
        val r = UpdateCheck.parse("""{"tag_name":"v3.9.65","assets":[{"name":"p.apk","size":5,"browser_download_url":"https://github.com/x/y/p.apk"}]}""")!!
        assertEquals("", r.apkSha256)
        assertEquals(5L, r.apkSize)
        assertFalse(UpdateCheck.digestMatches("", "abc")) // без суммы ставить нельзя
    }

    @Test fun digestComparisonIgnoresCase() {
        assertTrue(UpdateCheck.digestMatches("ABCDEF", "abcdef"))
        assertFalse(UpdateCheck.digestMatches("abc", "abd"))
    }

    @Test fun onlyGithubHostsAreTrustedForDownload() {
        assertTrue(UpdateCheck.trustedDownloadUrl("https://github.com/Visagik/phoenix/releases/download/v1/a.apk"))
        assertTrue(UpdateCheck.trustedDownloadUrl("https://objects.githubusercontent.com/x"))
        assertTrue(UpdateCheck.trustedDownloadUrl("https://release-assets.githubusercontent.com/x"))
        assertFalse(UpdateCheck.trustedDownloadUrl("http://github.com/a.apk"))
        assertFalse(UpdateCheck.trustedDownloadUrl("https://github.com.evil.example/a.apk"))
        assertFalse(UpdateCheck.trustedDownloadUrl("https://evilgithubusercontent.com/a.apk"))
        assertFalse(UpdateCheck.trustedDownloadUrl("https://example.com/a.apk"))
        assertFalse(UpdateCheck.trustedDownloadUrl("file:///sdcard/a.apk"))
    }

    @Test fun onlyOurRepoReleasesOnGithubCom() {
        assertFalse(UpdateCheck.trustedDownloadUrl("https://github.com/evil/repo/releases/download/v1/a.apk"))
        assertFalse(UpdateCheck.trustedDownloadUrl("https://github.com/Visagik/phoenix/issues/1"))
        assertFalse(UpdateCheck.trustedDownloadUrl("https://user:pw@github.com/Visagik/phoenix/releases/download/v1/a.apk"))
        assertFalse(UpdateCheck.trustedDownloadUrl("https://raw.githubusercontent.com/x/y/z/a.apk"))
    }

    @Test fun neededSpaceCoversFileAndSessionCopy() {
        assertEquals(2 * 67_000_000L + 20_000_000L, UpdateCheck.neededBytes(67_000_000L))
        assertEquals(2 * 100_000_000L + 20_000_000L, UpdateCheck.neededBytes(0L))
    }
}
