package com.phoenix.app

import com.phoenix.app.HealthDecision.Action
import com.phoenix.app.HealthDecision.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class HealthDecisionTest {
    private fun s(
        autoReconnect: Boolean = true, noNetwork: Boolean = false, directOffline: Boolean? = false, provisionalDoubt: Boolean = false,
        modePending: Boolean = false, pinned: Boolean = false, modeSettling: Boolean = false, netSettling: Boolean = false,
        netFlapping: Boolean = false, recentFailover: Boolean = false,
    ) = Snapshot(autoReconnect, noNetwork, directOffline, { provisionalDoubt }, modePending, { pinned }, modeSettling, netSettling, netFlapping, recentFailover)

    @Test fun deadServerOnLiveNetworkFailsOver() = assertEquals(Action.BLAME_AND_FAILOVER, HealthDecision.decide(s()))

    @Test fun autoReconnectOffComesFirst() = assertEquals(Action.BROKEN_NO_RECONNECT, HealthDecision.decide(s(autoReconnect = false, noNetwork = true)))

    @Test fun noNetworkBeatsEverythingElse() = assertEquals(Action.NO_NETWORK, HealthDecision.decide(s(noNetwork = true, directOffline = null, pinned = true)))

    @Test fun directMeasureOnlyWhenNeeded() {
        assertEquals(Action.MEASURE_DIRECT, HealthDecision.decide(s(directOffline = null)))
        assertEquals(Action.NO_INTERNET, HealthDecision.decide(s(directOffline = true, pinned = true, netSettling = true)))
    }

    @Test fun deferralsKeepTheirOrder() {
        assertEquals(Action.DEFER_PROVISIONAL, HealthDecision.decide(s(provisionalDoubt = true, modePending = true, pinned = true)))
        assertEquals(Action.DEFER_MODE_PENDING, HealthDecision.decide(s(modePending = true, pinned = true, netSettling = true)))
        assertEquals(Action.DEFER_PINNED, HealthDecision.decide(s(pinned = true, modeSettling = true)))
        assertEquals(Action.DEFER_MODE_SETTLING, HealthDecision.decide(s(modeSettling = true, netSettling = true)))
        assertEquals(Action.DEFER_NET_SETTLING, HealthDecision.decide(s(netSettling = true, netFlapping = true)))
        assertEquals(Action.DEFER_NET_FLAPPING, HealthDecision.decide(s(netFlapping = true, recentFailover = true)))
    }

    @Test fun recentFailoverBlamesButWaits() = assertEquals(Action.BLAME_THEN_WAIT, HealthDecision.decide(s(recentFailover = true)))

    /** Журнал 20.11 (Chicago): Wi-Fi ↔ mobile каждые 3–8 мин, связь пропала — вину на сервер не вешаем. */
    @Test fun scenarioNetworkHoppingDoesNotBlameServer() = assertEquals(Action.DEFER_NET_FLAPPING, HealthDecision.decide(s(netFlapping = true)))

    /** Журнал 21.11: смена сервера через секунды после смены сети — вина сети. */
    @Test fun scenarioFreshNetworkChange() = assertEquals(Action.DEFER_NET_SETTLING, HealthDecision.decide(s(netSettling = true)))

    @Test fun lazyChecksRunOnlyWhenReached() {
        var doubtCalls = 0
        var pinCalls = 0
        fun snap(offline: Boolean?, noNet: Boolean = false) = Snapshot(true, noNet, offline, { doubtCalls++; false }, false, { pinCalls++; false }, false, false, false, false)
        HealthDecision.decide(snap(null))
        HealthDecision.decide(snap(true))
        HealthDecision.decide(snap(false, noNet = true))
        assertEquals(0, doubtCalls)
        assertEquals(0, pinCalls)
        HealthDecision.decide(snap(false))
        assertEquals(1, doubtCalls)
        assertEquals(1, pinCalls)
    }
}
