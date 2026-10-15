package com.mapmyroad.ui.update

import com.mapmyroad.ui.AppGateState
import com.mapmyroad.ui.resolveAppGate
import org.junit.Assert.assertEquals
import org.junit.Test

/** UI_UX_GUIDELINES.md section 8 (update screen) and section 6 (no interruptions during a trip). */
class UpdateGateTest {
    @Test
    fun updateRequired_whenNoTripIsActive_showsTheUpdateScreen() {
        assertEquals(AppGateState.UpdateRequired, resolveAppGate(onboardingCompleted = true, updateRequired = true, isTripActive = false))
        assertEquals(AppGateState.UpdateRequired, resolveAppGate(onboardingCompleted = false, updateRequired = true, isTripActive = false))
    }

    @Test
    fun updateRequired_whileATripIsActive_isDeferredUntilTheTripEnds() {
        assertEquals(AppGateState.Main, resolveAppGate(onboardingCompleted = true, updateRequired = true, isTripActive = true))
        assertEquals(AppGateState.UpdateRequired, resolveAppGate(onboardingCompleted = true, updateRequired = true, isTripActive = false))
    }

    @Test
    fun noUpdateRequired_keepsTheOnboardingGate() {
        assertEquals(AppGateState.Main, resolveAppGate(onboardingCompleted = true, updateRequired = false, isTripActive = false))
        assertEquals(AppGateState.Onboarding, resolveAppGate(onboardingCompleted = false, updateRequired = false, isTripActive = false))
        assertEquals(AppGateState.Main, resolveAppGate(onboardingCompleted = true, updateRequired = false, isTripActive = true))
    }

    @Test
    fun playStoreLinks_pointAtThePackage() {
        assertEquals("market://details?id=com.mapmyroad", PlayStoreLinks.marketUri("com.mapmyroad"))
        assertEquals("https://play.google.com/store/apps/details?id=com.mapmyroad", PlayStoreLinks.webUrl("com.mapmyroad"))
    }
}
