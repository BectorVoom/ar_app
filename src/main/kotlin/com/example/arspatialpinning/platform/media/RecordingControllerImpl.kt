package com.example.arspatialpinning.platform.media

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import com.example.arspatialpinning.common.AppError
import com.example.arspatialpinning.common.AppResult
import com.example.arspatialpinning.common.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max
import kotlin.math.min

class RecordingControllerImpl(
    private val context: Context,
    private val logger: Logger
) : RecordingController {
    override var onProjectionStopped: (() -> Unit)? = null

    private val mediaProjectionManager: MediaProjectionManager by lazy {
        context.getSystemService(MediaProjectionManager::class.java)
    }
    private val mediaStoreVideoWriter = MediaStoreVideoWriter(context.contentResolver)
    private val recordedFileValidator = RecordedFileValidator(context, context.contentResolver)
    private val mutex = Mutex()
    private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var activeSession: ActiveSession? = null

    override fun createConsentIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mediaProjectionManager.createScreenCaptureIntent(
                MediaProjectionConfig.createConfigForUserChoice()
            )
        } else {
            mediaProjectionManager.createScreenCaptureIntent()
        }
    }

    override suspend fun startRecording(
        consentResultCode: Int,
        consentData: Intent,
        maximumWindowBounds: Rect
    ): AppResult<Unit> = mutex.withLock {
        if (activeSession != null) {
            return@withLock AppResult.Failure(AppError.RecordingFailure("Recording is already active."))
        }

        val captureSize = computeCaptureSize(maximumWindowBounds)
        val densityDpi = context.resources.displayMetrics.densityDpi

        var pendingOutput: MediaStoreVideoWriter.PendingOutput? = null
        var recorder: MediaRecorder? = null
        var projection: MediaProjection? = null
        var callback: MediaProjection.Callback? = null
        var virtualDisplay: VirtualDisplay? = null

        try {
            // Foreground service must be active before obtaining MediaProjection (Android 14+).
            RecordingService.start(context)

            projection = mediaProjectionManager.getMediaProjection(consentResultCode, consentData)
                ?: return@withLock AppResult.Failure(AppError.MediaProjectionDenied())

            pendingOutput = try {
                mediaStoreVideoWriter.createPendingOutput()
            } catch (t: Throwable) {
                logger.e(TAG, "Failed to create MediaStore output.", t)
                return@withLock AppResult.Failure(AppError.OutputCreationFailed())
            }

            recorder = createRecorder(
                outputFileDescriptor = pendingOutput.fileDescriptor,
                width = captureSize.width,
                height = captureSize.height
            )

            recorder.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    callbackScope.launch {
                        logger.d(TAG, "Max recording duration reached.")
                        stopRecording()
                        onProjectionStopped?.invoke()
                    }
                }
            }
            recorder.setOnErrorListener { _, _, _ ->
                callbackScope.launch {
                    logger.e(TAG, "Recorder reported fatal error.")
                    stopRecording()
                    onProjectionStopped?.invoke()
                }
            }

            callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    callbackScope.launch {
                        logger.d(TAG, "MediaProjection stopped by the system.")
                        stopRecording()
                        onProjectionStopped?.invoke()
                    }
                }
            }
            projection.registerCallback(callback, Handler(Looper.getMainLooper()))

            virtualDisplay = projection.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                captureSize.width,
                captureSize.height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.surface,
                null,
                null
            )

            recorder.start()

            activeSession = ActiveSession(
                outputUri = pendingOutput.uri,
                outputPfd = pendingOutput.fileDescriptor,
                mediaProjection = projection,
                projectionCallback = callback,
                virtualDisplay = virtualDisplay,
                mediaRecorder = recorder
            )

            logger.d(TAG, "Recording started at ${captureSize.width}x${captureSize.height}.")
            AppResult.Success(Unit)
        } catch (t: Throwable) {
            logger.e(TAG, "Failed to start recording.", t)
            safelyRelease(
                mediaRecorder = recorder,
                virtualDisplay = virtualDisplay,
                mediaProjection = projection,
                projectionCallback = callback,
                outputPfd = pendingOutput?.fileDescriptor
            )
            pendingOutput?.uri?.let(mediaStoreVideoWriter::deleteOutput)
            RecordingService.stop(context)
            AppResult.Failure(AppError.RecorderStartFailed())
        }
    }

    override suspend fun stopRecording(): AppResult<Unit> = mutex.withLock {
        val current = activeSession ?: return@withLock AppResult.Success(Unit)
        activeSession = null

        var stopSucceeded = true
        try {
            current.mediaRecorder.stop()
        } catch (t: Throwable) {
            stopSucceeded = false
            logger.e(TAG, "Recorder stop failed.", t)
        }

        safelyRelease(
            mediaRecorder = current.mediaRecorder,
            virtualDisplay = current.virtualDisplay,
            mediaProjection = current.mediaProjection,
            projectionCallback = current.projectionCallback,
            outputPfd = current.outputPfd
        )

        val result = if (stopSucceeded) {
            when (val validation = recordedFileValidator.validate(current.outputUri)) {
                is AppResult.Success -> {
                    mediaStoreVideoWriter.finalizeOutput(current.outputUri)
                    AppResult.Success(Unit)
                }

                is AppResult.Failure -> {
                    mediaStoreVideoWriter.deleteOutput(current.outputUri)
                    validation
                }
            }
        } else {
            mediaStoreVideoWriter.deleteOutput(current.outputUri)
            AppResult.Failure(AppError.RecorderStopFailed())
        }

        RecordingService.stop(context)
        result
    }

    override fun release() {
        runBlocking {
            stopRecording()
        }
        callbackScope.coroutineContext.cancel()
    }

    private fun createRecorder(
        outputFileDescriptor: ParcelFileDescriptor,
        width: Int,
        height: Int
    ): MediaRecorder {
        return MediaRecorder(context).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoFrameRate(VIDEO_FRAME_RATE)
            setVideoEncodingBitRate(VIDEO_BIT_RATE)
            setVideoSize(width, height)
            setMaxDuration(MAX_DURATION_MS)
            setOutputFile(outputFileDescriptor.fileDescriptor)
            prepare()
        }
    }

    private fun safelyRelease(
        mediaRecorder: MediaRecorder?,
        virtualDisplay: VirtualDisplay?,
        mediaProjection: MediaProjection?,
        projectionCallback: MediaProjection.Callback?,
        outputPfd: ParcelFileDescriptor?
    ) {
        try {
            mediaRecorder?.reset()
        } catch (_: Throwable) {
        }
        try {
            mediaRecorder?.release()
        } catch (_: Throwable) {
        }
        try {
            virtualDisplay?.release()
        } catch (_: Throwable) {
        }
        try {
            if (projectionCallback != null) {
                mediaProjection?.unregisterCallback(projectionCallback)
            }
        } catch (_: Throwable) {
        }
        try {
            mediaProjection?.stop()
        } catch (_: Throwable) {
        }
        try {
            outputPfd?.close()
        } catch (_: Throwable) {
        }
    }

    private fun computeCaptureSize(maximumWindowBounds: Rect): CaptureSize {
        val sourceWidth = maximumWindowBounds.width().coerceAtLeast(2)
        val sourceHeight = maximumWindowBounds.height().coerceAtLeast(2)
        val portraitWidth = min(sourceWidth, sourceHeight)
        val portraitHeight = max(sourceWidth, sourceHeight)

        val scale = min(
            1f,
            min(
                MAX_CAPTURE_WIDTH.toFloat() / portraitWidth.toFloat(),
                MAX_CAPTURE_HEIGHT.toFloat() / portraitHeight.toFloat()
            )
        )

        var width = (portraitWidth * scale).toInt().roundDownEven().roundDownTo16()
        var height = (portraitHeight * scale).toInt().roundDownEven().roundDownTo16()

        width = width.coerceAtLeast(16).coerceAtMost(MAX_CAPTURE_WIDTH)
        height = height.coerceAtLeast(16).coerceAtMost(MAX_CAPTURE_HEIGHT)

        return CaptureSize(width = width, height = height)
    }

    private fun Int.roundDownEven(): Int = if (this % 2 == 0) this else this - 1

    private fun Int.roundDownTo16(): Int = this - (this % 16)

    private data class ActiveSession(
        val outputUri: Uri,
        val outputPfd: ParcelFileDescriptor,
        val mediaProjection: MediaProjection,
        val projectionCallback: MediaProjection.Callback,
        val virtualDisplay: VirtualDisplay?,
        val mediaRecorder: MediaRecorder
    )

    private data class CaptureSize(
        val width: Int,
        val height: Int
    )

    private companion object {
        private const val TAG = "RecordingController"
        private const val VIRTUAL_DISPLAY_NAME = "ARSpatialPinningRecording"
        private const val VIDEO_FRAME_RATE = 30
        private const val VIDEO_BIT_RATE = 8_000_000
        private const val MAX_DURATION_MS = 10 * 60 * 1000
        private const val MAX_CAPTURE_WIDTH = 1080
        private const val MAX_CAPTURE_HEIGHT = 1920
    }
}
