package com.example.arspatialpinning.platform.ar

import android.content.Context
import com.example.arspatialpinning.common.AppError
import com.example.arspatialpinning.domain.model.ArAvailability
import com.google.ar.core.ArCoreApk

class ArAvailabilityChecker(
    private val context: Context
) {
    fun checkAvailability(): ArAvailability {
        return when (ArCoreApk.getInstance().checkAvailability(context)) {
            ArCoreApk.Availability.UNKNOWN_CHECKING -> ArAvailability.Checking
            ArCoreApk.Availability.SUPPORTED_INSTALLED -> ArAvailability.Supported
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> ArAvailability.InstallOrUpdateRequired
            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> ArAvailability.Unsupported
            ArCoreApk.Availability.UNKNOWN_ERROR,
            ArCoreApk.Availability.UNKNOWN_TIMED_OUT -> ArAvailability.Error
        }
    }

    fun toBlockingError(availability: ArAvailability): AppError? {
        return when (availability) {
            ArAvailability.Unsupported -> AppError.ArUnsupported()
            ArAvailability.InstallOrUpdateRequired -> AppError.ArCoreInstallOrUpdateRequired()
            ArAvailability.Error -> AppError.Unexpected("Unable to confirm ARCore availability.")
            else -> null
        }
    }
}
