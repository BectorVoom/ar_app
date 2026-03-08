package com.example.arspatialpinning.platform.rayneo

import android.content.Context
import com.example.arspatialpinning.common.AppError
import com.example.arspatialpinning.common.AppResult
import com.example.arspatialpinning.common.Logger
import java.lang.reflect.InvocationTargetException

interface RayneoAudioModeController {
    fun prepareForVideoRecording(): AppResult<Unit>
    fun restoreDefaultAudioPolicy()
}

class RayneoAudioModeControllerImpl(
    private val context: Context,
    private val logger: Logger
) : RayneoAudioModeController {

    private var prepared: Boolean = false

    override fun prepareForVideoRecording(): AppResult<Unit> {
        val success = AUDIO_POLICY_CLASS_CANDIDATES.any { className ->
            invokePrepareForVideoRecording(className)
        }

        if (!success) {
            prepared = false
            return AppResult.Failure(AppError.RayneoAudioPolicySetupFailed())
        }

        prepared = true
        return AppResult.Success(Unit)
    }

    override fun restoreDefaultAudioPolicy() {
        if (!prepared) {
            return
        }

        AUDIO_POLICY_CLASS_CANDIDATES.forEach { className ->
            restoreDefaultPolicy(className)
        }

        prepared = false
    }

    private fun invokePrepareForVideoRecording(className: String): Boolean {
        return try {
            val clazz = Class.forName(className)
            val instance = buildAudioPolicyInstance(clazz) ?: return false
            val method = clazz.methods.firstOrNull { method ->
                method.name == METHOD_PREPARE_VIDEO_RECORDING &&
                    method.parameterTypes.isEmpty()
            } ?: return false
            val result = method.invoke(instance)
            result as? Boolean ?: true
        } catch (error: ClassNotFoundException) {
            logger.d(TAG, "RayNeo audio policy class not found for $className")
            false
        } catch (error: SecurityException) {
            logger.d(TAG, "RayNeo audio prepare blocked for $className: ${error.message}")
            false
        } catch (error: InvocationTargetException) {
            logger.d(TAG, "RayNeo audio prepare invocation failed for $className: ${error.targetException?.message ?: error.message}")
            false
        } catch (error: IllegalAccessException) {
            logger.d(TAG, "RayNeo audio prepare inaccessible for $className: ${error.message}")
            false
        } catch (error: IllegalArgumentException) {
            logger.d(TAG, "RayNeo audio prepare invalid for $className: ${error.message}")
            false
        } catch (error: IllegalStateException) {
            logger.d(TAG, "RayNeo audio prepare state failure for $className: ${error.message}")
            false
        } catch (error: LinkageError) {
            logger.d(TAG, "RayNeo audio prepare linkage failure for $className: ${error.message}")
            false
        } catch (error: RuntimeException) {
            // Reflection target implementations may throw vendor runtime exceptions.
            logger.d(TAG, "RayNeo audio prepare runtime failure for $className: ${error.message}")
            false
        }
    }

    private fun restoreDefaultPolicy(className: String) {
        try {
            val clazz = Class.forName(className)
            val instance = buildAudioPolicyInstance(clazz) ?: return
            val method = clazz.methods.firstOrNull { method ->
                method.name == METHOD_RESTORE_DEFAULT_POLICY &&
                    method.parameterTypes.isEmpty()
            } ?: return
            method.invoke(instance)
        } catch (error: ClassNotFoundException) {
            logger.d(TAG, "RayNeo audio policy class not found for $className")
        } catch (error: SecurityException) {
            logger.e(TAG, "RayNeo audio restore blocked for $className: ${error.message}", error)
        } catch (error: InvocationTargetException) {
            logger.e(TAG, "RayNeo audio restore invocation failed for $className: ${error.targetException?.message ?: error.message}", error.targetException ?: error)
        } catch (error: IllegalAccessException) {
            logger.e(TAG, "RayNeo audio restore inaccessible for $className: ${error.message}", error)
        } catch (error: IllegalArgumentException) {
            logger.e(TAG, "RayNeo audio restore invalid for $className: ${error.message}", error)
        } catch (error: IllegalStateException) {
            logger.e(TAG, "RayNeo audio restore state failure for $className: ${error.message}", error)
        } catch (error: LinkageError) {
            logger.e(TAG, "RayNeo audio restore linkage failure for $className: ${error.message}", error)
        } catch (error: RuntimeException) {
            // Reflection target implementations may throw vendor runtime exceptions.
            logger.e(TAG, "RayNeo audio restore runtime failure for $className: ${error.message}", error)
        }
    }

    private fun buildAudioPolicyInstance(clazz: Class<*>): Any? {
        val getInstanceWithContext = clazz.methods.firstOrNull { method ->
            method.name == METHOD_GET_INSTANCE &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes.firstOrNull() == Context::class.java
        }
        if (getInstanceWithContext != null) {
            return getInstanceWithContext.invoke(null, context)
        }

        val getInstanceNoArgs = clazz.methods.firstOrNull { method ->
            method.name == METHOD_GET_INSTANCE &&
                method.parameterTypes.isEmpty()
        }
        if (getInstanceNoArgs != null) {
            return getInstanceNoArgs.invoke(null)
        }

        return null
    }

    private companion object {
        const val TAG = "RayneoAudioModeCtrl"
        const val METHOD_GET_INSTANCE = "getInstance"
        const val METHOD_PREPARE_VIDEO_RECORDING = "prepareForVideoRecording"
        const val METHOD_RESTORE_DEFAULT_POLICY = "restoreDefaultAudioPolicy"
        val AUDIO_POLICY_CLASS_CANDIDATES = listOf(
            "com.rayneo.audio.RayneoAudioModeController",
            "com.rayneo.audio.AudioPolicyController",
            "com.rayneo.sdk.audio.AudioPolicyController"
        )
    }
}
