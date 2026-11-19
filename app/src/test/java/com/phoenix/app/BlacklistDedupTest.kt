package com.phoenix.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class BlacklistDedupTest {
    @Test fun duplicateWithAndWithoutCommentBecomesOneEntryWithComment() {
        val f = File.createTempFile("blk", ".txt")
        try {
            f.writeText("https://a.example/x\nhttps://b.example/y # причина\nhttps://a.example/x # по умолчанию\n")
            val l = Blacklist(f).load()
            assertEquals(listOf("https://a.example/x", "https://b.example/y"), l.map { it.url })
            assertEquals("по умолчанию", l[0].reason)
        } finally { f.delete() }
    }

    @Test fun addHealsFileOfDuplicates() {
        val f = File.createTempFile("blk", ".txt")
        try {
            f.writeText("https://a.example/x\nhttps://a.example/x # c\n")
            Blacklist(f).add("https://c.example/z", "новая")
            assertEquals(2, f.readLines().count { it.startsWith("https://a.example/x") || it.startsWith("https://c.example/z") })
            assertEquals(1, f.readLines().count { it.startsWith("https://a.example/x") })
        } finally { f.delete() }
    }
}
