package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ShortUrlTest {
    @Test fun rawGithub() = assertEquals("igareck/vpn-configs › WHITE.txt",
        ShortUrl.of("https://raw.githubusercontent.com/igareck/vpn-configs/main/lists/WHITE.txt"))
    @Test fun githubRepo() = assertEquals("a/b", ShortUrl.of("https://github.com/a/b"))
    @Test fun plainHostAndTail() = assertEquals("example.com › sub.txt", ShortUrl.of("https://www.example.com/x/y/sub.txt?token=1"))
    @Test fun hostOnly() = assertEquals("example.com", ShortUrl.of("http://example.com/"))
    @Test fun oneSegment() = assertEquals("example.com/list", ShortUrl.of("https://example.com/list"))
}
