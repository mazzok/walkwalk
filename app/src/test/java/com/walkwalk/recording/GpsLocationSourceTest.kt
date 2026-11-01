package com.walkwalk.recording

import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class GpsLocationSourceTest {
    private val manager = ApplicationProvider.getApplicationContext<android.app.Application>()
        .getSystemService(LocationManager::class.java)

    @Test
    fun `fused provider is used so a first fix arrives indoors like in other map apps`() {
        shadowOf(manager).setProviderEnabled(LocationManager.FUSED_PROVIDER, true)
        assertEquals(LocationManager.FUSED_PROVIDER, GpsLocationSource.providerOf(manager))
    }

    @Test
    fun `falls back to the GPS chip on phones without a fused provider`() {
        shadowOf(manager).removeProvider(LocationManager.FUSED_PROVIDER)
        assertEquals(LocationManager.GPS_PROVIDER, GpsLocationSource.providerOf(manager))
    }
}
