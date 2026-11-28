package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Test

class CertFingerprintTest {
    @Test fun knownVectorsAndStableOutput() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", CertFingerprint.sha256(ByteArray(0)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", CertFingerprint.sha256("abc".toByteArray()))
        assertEquals(64, CertFingerprint.sha256(byteArrayOf(1, 2, 3)).length)
    }
}
