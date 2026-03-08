package com.example.arspatialpinning.platform.ar

import android.content.Context
import com.example.arspatialpinning.common.AppError
import com.example.arspatialpinning.common.AppResult
import com.google.ar.core.ArCoreApk

class ArAvailabilityChecker(
    private val context: Context
) {
    fun checkAvailability(): AppResult<Unit> {
        return when (ArCoreApk.getInstance().checkAvailability(context)) {
            ArCoreApk.Availability.SUPPORTED_INSTALLED,
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> AppResult.Success(Unit)

            ArCoreApk.Availability.UNKNOWN_CHECKING,
            ArCoreApk.Availability.UNKNOWN_ERROR,
            ArCoreApk.Availability.UNKNOWN_TIMED_OUT -> AppResult.Failure(
                AppError.ArUnavailable("Unable to confirm ARCore availability.")
            )

            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> AppResult.Failure(
                AppError.ArUnavailable("This device does not support ARCore.")
            )
        }
    }
}
