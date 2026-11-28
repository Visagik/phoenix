package com.phoenix.app

import org.junit.Assert.assertTrue
import org.junit.Test

/** Сценарий: notebooklm.google (без .com) открыт в браузере — проверка региона, DIAG и правила обхода должны знать его так же, как остальные адреса NotebookLM. */
class GoogleHostsUnifiedTest {
    @Test fun everyGoogleAiHostIsKnownToTheRegionGate() {
        for (h in GoogleAi.sites) assertTrue("GeoGate не знает $h", GeoGate.applies("https://$h/"))
    }
}
