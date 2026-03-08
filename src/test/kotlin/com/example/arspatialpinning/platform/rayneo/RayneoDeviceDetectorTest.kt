package com.example.arspatialpinning.platform.rayneo

import com.example.arspatialpinning.common.Logger
import com.rayneo.utils.DeviceUtil
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RayneoDeviceDetectorTest {

    @Test
    fun `isX3Device delegates to RayNeo DeviceUtil path`() {
        val detector = RayneoDeviceDetectorImpl(NoOpLogger)

        DeviceUtil.x3Device = true
        assertTrue(detector.isX3Device())

        DeviceUtil.x3Device = false
        assertFalse(detector.isX3Device())
    }

    private data object NoOpLogger : Logger {
        override fun d(tag: String, message: String) = Unit
        override fun e(tag: String, message: String, throwable: Throwable?) = Unit
    }
}
