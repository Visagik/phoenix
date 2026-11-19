package com.phoenix.app

import com.phoenix.app.Onboarding.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingTest {
    private fun env(
        vpn: Boolean = false, notifRel: Boolean = true, notif: Boolean = false, battery: Boolean = false,
        exactRel: Boolean = true, exact: Boolean = false, autoRel: Boolean = false, auto: Boolean = false, servers: Boolean = false,
    ) = Onboarding.Env(vpn, notifRel, notif, battery, exactRel, exact, autoRel, auto, servers)

    @Test fun freshPhoneGetsEverythingExceptAutostartOnPlainAndroid() {
        assertEquals(
            listOf(Step.WELCOME, Step.VPN, Step.NOTIFICATIONS, Step.BACKGROUND, Step.ALARMS, Step.SERVERS),
            Onboarding.steps(0, env()),
        )
    }

    @Test fun autostartOnlyOnOemPhones() {
        assertTrue(Step.AUTOSTART in Onboarding.steps(0, env(autoRel = true)))
        assertTrue(Step.AUTOSTART !in Onboarding.steps(0, env(autoRel = false)))
    }

    @Test fun grantedRightsAreSkipped() {
        val s = Onboarding.steps(0, env(vpn = true, notif = true, battery = true, exact = true))
        assertEquals(listOf(Step.WELCOME, Step.SERVERS), s)
    }

    @Test fun oldAndroidSkipsNotificationsAndAlarms() {
        val s = Onboarding.steps(0, env(notifRel = false, exactRel = false))
        assertTrue(Step.NOTIFICATIONS !in s && Step.ALARMS !in s)
    }

    @Test fun passedVersionShowsNothing() {
        assertEquals(emptyList<Step>(), Onboarding.steps(Onboarding.VERSION, env()))
    }

    @Test fun existingUserWithServersIsNotInterrupted() {
        assertEquals(emptyList<Step>(), Onboarding.steps(0, env(servers = true)))
    }

    @Test fun nothingToAskMeansNoOnboarding() {
        assertEquals(emptyList<Step>(), Onboarding.steps(0, env(vpn = true, notif = true, battery = true, exact = true, servers = true)))
    }

    @Test fun forcedRunIgnoresSavedVersionButStillSkipsGranted() {
        val s = Onboarding.steps(Onboarding.VERSION, env(vpn = true, notif = true, battery = true, exact = true, servers = true), force = true)
        assertEquals(listOf(Step.WELCOME), s)
    }

    @Test fun welcomeIsNeverSatisfied() {
        assertTrue(!Onboarding.satisfied(Step.WELCOME, env(vpn = true, notif = true, battery = true, exact = true, servers = true)))
    }
}
