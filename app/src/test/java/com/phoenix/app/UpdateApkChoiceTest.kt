package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Самообновление берёт APK своего варианта (full или Lite), а не первый попавшийся. */
class UpdateApkChoiceTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val base = "https://github.com/Visagik/phoenix/releases/download/v3.9.68/"

    private fun asset(name: String, size: Long, sha: String) =
        """{"name":"$name","size":$size,"digest":"sha256:$sha","browser_download_url":"$base$name"}"""

    private fun release(vararg assets: String) = """{"tag_name":"v3.9.68","assets":[${assets.joinToString(",")}]}"""

    private val full = asset("phoenix-3.9.68-debug.apk", 111, a)
    private val lite = asset("phoenix-lite-3.9.68-debug.apk", 222, b)

    @Test fun fullSkipsLiteListedFirst() {
        val r = UpdateCheck.parse(release(lite, full), lite = false)!!
        assertEquals(base + "phoenix-3.9.68-debug.apk", r.apkUrl)
        assertEquals(a, r.apkSha256)
        assertEquals(111L, r.apkSize)
    }

    @Test fun fullTakesFullListedFirst() {
        val r = UpdateCheck.parse(release(full, lite), lite = false)!!
        assertEquals(base + "phoenix-3.9.68-debug.apk", r.apkUrl)
        assertEquals(a, r.apkSha256)
    }

    @Test fun fullWithOnlyLiteHasNoApk() {
        val r = UpdateCheck.parse(release(lite), lite = false)!!
        assertEquals("", r.apkUrl)
        assertEquals("", r.apkSha256)
        assertEquals(0L, r.apkSize)
    }

    @Test fun singleFullAsBefore() {
        val r = UpdateCheck.parse(release(full), lite = false)!!
        assertEquals(base + "phoenix-3.9.68-debug.apk", r.apkUrl)
        assertEquals(a, r.apkSha256)
        assertEquals(111L, r.apkSize)
    }

    @Test fun liteTakesItsOwnFileWithItsShaAndSize() {
        val r = UpdateCheck.parse(release(full, lite), lite = true)!!
        assertEquals(base + "phoenix-lite-3.9.68-debug.apk", r.apkUrl)
        assertEquals(b, r.apkSha256)
        assertEquals(222L, r.apkSize)
    }

    @Test fun liteWithOnlyFullHasNoApk() {
        assertEquals("", UpdateCheck.parse(release(full), lite = true)!!.apkUrl)
    }

    @Test fun liteInPathButNotInFileNameIsFull() {
        val url = "https://github.com/Visagik/phoenix-lite/releases/download/v3.9.68-lite/phoenix-3.9.68-debug.apk"
        val json = """{"tag_name":"v3.9.68","assets":[{"size":5,"digest":"sha256:$a","browser_download_url":"$url"}]}"""
        assertEquals(url, UpdateCheck.parse(json, lite = false)!!.apkUrl)
        assertEquals("", UpdateCheck.parse(json, lite = true)!!.apkUrl)
        assertTrue(UpdateCheck.isOwnApk(url, lite = false))
    }

    @Test fun fileNameCaseIsIgnored() {
        assertTrue(UpdateCheck.isOwnApk(base + "Phoenix-LITE-1.apk", lite = true))
        assertTrue(!UpdateCheck.isOwnApk(base + "Phoenix-LITE-1.apk", lite = false))
    }
}
