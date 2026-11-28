package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceBlocksLinkTest {
    @Test fun labelsFollowBuiltInServices() {
        assertEquals(CheckServices.ALL.map { it.id }, ServiceBlocks.LABELS)
    }

    @Test fun everyTableRowCoversEveryService() {
        for (cc in listOf("RU", "CN", "TR", "AE", "IN", "DE", "??")) {
            assertEquals(cc, ServiceBlocks.LABELS.size, ServiceBlocks.status(cc).size)
        }
    }

    @Test fun claudeFromRussiaIsBlocked() {
        val i = ServiceBlocks.LABELS.indexOf("CL")
        assertTrue(i >= 0)
        assertEquals("blocked", ServiceBlocks.status("RU")[i])
    }
}
