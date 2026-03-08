package com.example.arspatialpinning.platform.rayneo

import android.app.Application
import com.example.arspatialpinning.common.AppError
import com.example.arspatialpinning.common.AppResult
import com.example.arspatialpinning.common.Logger
import com.rayneo.audio.AudioPolicyController
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RayneoAudioModeControllerTest {

    @Before
    fun reset() {
        AudioPolicyController.prepareShouldSucceed = true
        AudioPolicyController.prepareCalls = 0
        AudioPolicyController.restoreCalls = 0
    }

    @Test
    fun `prepareForVideoRecording succeeds through RayNeo policy class and restore is invoked`() {
        val app = RuntimeEnvironment.getApplication() as Application
        val controller = RayneoAudioModeControllerImpl(app, NoOpLogger)

        val prepare = controller.prepareForVideoRecording()
        controller.restoreDefaultAudioPolicy()

        assertTrue(prepare is AppResult.Success)
        assertTrue(AudioPolicyController.prepareCalls > 0)
        assertTrue(AudioPolicyController.restoreCalls > 0)
    }

    @Test
    fun `prepareForVideoRecording failure propagates E-REC-006`() {
        val app = RuntimeEnvironment.getApplication() as Application
        AudioPolicyController.prepareShouldSucceed = false
        val controller = RayneoAudioModeControllerImpl(app, NoOpLogger)

        val prepare = controller.prepareForVideoRecording()

        assertTrue(prepare is AppResult.Failure)
        assertTrue((prepare as AppResult.Failure).error is AppError.RayneoAudioPolicySetupFailed)
    }

    private data object NoOpLogger : Logger {
        override fun d(tag: String, message: String) = Unit
        override fun e(tag: String, message: String, throwable: Throwable?) = Unit
    }
}
