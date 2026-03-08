package com.example.arspatialpinning.platform.rayneo

import com.example.arspatialpinning.common.Logger
import java.lang.reflect.InvocationTargetException

interface RayneoDeviceDetector {
    fun isX3Device(): Boolean
}

class RayneoDeviceDetectorImpl(
    private val logger: Logger
) : RayneoDeviceDetector {
    override fun isX3Device(): Boolean {
        DEVICE_UTIL_CLASS_NAMES.forEach { className ->
            val detected = detectX3ByClass(className)
            if (detected != null) {
                return detected
            }
        }
        return false
    }

    private fun detectX3ByClass(className: String): Boolean? {
        return try {
            val clazz = Class.forName(className)
            val method = clazz.methods.firstOrNull { method ->
                method.name == METHOD_IS_X3_DEVICE &&
                    method.parameterTypes.isEmpty()
            } ?: return null
            method.invoke(null) as? Boolean
        } catch (error: ClassNotFoundException) {
            logger.d(TAG, "RayNeo DeviceUtil class not found for $className")
            null
        } catch (error: SecurityException) {
            logger.d(TAG, "RayNeo DeviceUtil lookup blocked for $className: ${error.message}")
            null
        } catch (error: InvocationTargetException) {
            logger.d(TAG, "RayNeo DeviceUtil invocation failed for $className: ${error.targetException?.message ?: error.message}")
            null
        } catch (error: IllegalAccessException) {
            logger.d(TAG, "RayNeo DeviceUtil invocation inaccessible for $className: ${error.message}")
            null
        } catch (error: IllegalArgumentException) {
            logger.d(TAG, "RayNeo DeviceUtil invocation invalid for $className: ${error.message}")
            null
        } catch (error: IllegalStateException) {
            logger.d(TAG, "RayNeo DeviceUtil invocation failed for $className: ${error.message}")
            null
        } catch (error: LinkageError) {
            logger.d(TAG, "RayNeo DeviceUtil linkage failed for $className: ${error.message}")
            null
        } catch (error: RuntimeException) {
            // Reflection target implementations may throw vendor runtime exceptions.
            logger.d(TAG, "RayNeo DeviceUtil runtime failure for $className: ${error.message}")
            null
        }
    }

    private companion object {
        const val TAG = "RayneoDeviceDetector"
        const val METHOD_IS_X3_DEVICE = "isX3Device"
        val DEVICE_UTIL_CLASS_NAMES = listOf(
            "com.rayneo.utils.DeviceUtil",
            "com.rayneo.device.DeviceUtil",
            "com.rayneo.sdk.utils.DeviceUtil"
        )
    }
}
