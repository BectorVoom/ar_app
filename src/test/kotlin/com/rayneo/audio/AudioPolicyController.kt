package com.rayneo.audio

import android.content.Context

class AudioPolicyController private constructor() {

    fun prepareForVideoRecording(): Boolean {
        prepareCalls += 1
        return prepareShouldSucceed
    }

    fun restoreDefaultAudioPolicy() {
        restoreCalls += 1
    }

    companion object {
        @JvmStatic
        var prepareShouldSucceed: Boolean = true

        @JvmStatic
        var prepareCalls: Int = 0

        @JvmStatic
        var restoreCalls: Int = 0

        @JvmStatic
        fun getInstance(context: Context): AudioPolicyController {
            return AudioPolicyController()
        }
    }
}
