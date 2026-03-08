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
import android.os.SystemClock
import com.example.arspatialpinning.common.AppError
import com.example.arspatialpinning.common.AppResult
import com.example.arspatialpinning.common.Logger
import com.example.arspatialpinning.domain.model.RecordedVideoArtifact
import com.example.arspatialpinning.platform.rayneo.RayneoAudioModeController
import com.example.arspatialpinning.platform.rayneo.RayneoDeviceDetector
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

class RecordingControllerImpl(
    private val context: Context,
    private val logger: Logger,
    private val rayneoDeviceDetector: RayneoDeviceDetector,
    private val rayneoAudioModeController: RayneoAudioModeController,
    private val mediaStoreVideoWriter: MediaStoreVideoWriter =
        MediaStoreVideoWriter(context.contentResolver),
    private val recordedFileValidator: RecordedFileValidator =
        RecordedFileValidator(context, context.contentResolver)
) : RecordingController {
    override var onProjectionStopped: (() -> Unit)? = null

    private val mediaProjectionManager: MediaProjectionManager by lazy {
        context.getSystemService(MediaProjectionManager::class.java)
    }
    private val mutex = Mutex()
    private val controllerDispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "RecordingControllerThread").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var activeSession: ActiveSession? = null

    override fun createConsentIntent(): AppResult<Intent> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            createConsentIntentApi34()
        } else {
            createConsentIntentLegacy()
        }
    }

    override suspend fun startRecording(
        consentResultCode: Int,
        consentData: Intent,
        maximumWindowBounds: Rect
    ): AppResult<Unit> = withContext(controllerDispatcher) {
        mutex.withLock {
            if (activeSession != null) {
                return@withLock AppResult.Failure(AppError.RecordingFailure("Recording is already active."))
            }

            val captureSize = computeCaptureSize(maximumWindowBounds)
            val densityDpi = context.resources.displayMetrics.densityDpi

            when (val serviceStart = startRecordingService()) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> return@withLock serviceStart
            }
            when (val serviceReady = awaitRecordingServiceReady()) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> {
                    stopRecordingService()
                    return@withLock serviceReady
                }
            }

            val projection = when (val projectionResult = acquireProjection(consentResultCode, consentData)) {
                is AppResult.Failure -> {
                    stopRecordingService()
                    return@withLock projectionResult
                }

                is AppResult.Success -> projectionResult.value
            }

            val pendingOutput = when (val outputResult = mediaStoreVideoWriter.createPendingOutput()) {
                is AppResult.Failure -> {
                    releaseSessionResources(
                        mediaRecorder = null,
                        virtualDisplay = null,
                        mediaProjection = projection,
                        projectionCallback = null,
                        outputPfd = null
                    )
                    stopRecordingService()
                    return@withLock outputResult
                }

                is AppResult.Success -> outputResult.value
            }

            val rayneoPolicyPrepared = when (val policyResult = prepareRayneoPolicyIfNeeded()) {
                is AppResult.Failure -> {
                    cleanupStartFailure(
                        mediaRecorder = null,
                        virtualDisplay = null,
                        mediaProjection = projection,
                        projectionCallback = null,
                        outputPfd = pendingOutput.fileDescriptor,
                        pendingOutputUri = pendingOutput.uri,
                        restoreRayneoPolicy = false
                    )
                    return@withLock policyResult
                }

                is AppResult.Success -> policyResult.value
            }

            val recorder = when (
                val recorderResult = createRecorder(
                    outputFileDescriptor = pendingOutput.fileDescriptor,
                    width = captureSize.width,
                    height = captureSize.height
                )
            ) {
                is AppResult.Failure -> {
                    cleanupStartFailure(
                        mediaRecorder = null,
                        virtualDisplay = null,
                        mediaProjection = projection,
                        projectionCallback = null,
                        outputPfd = pendingOutput.fileDescriptor,
                        pendingOutputUri = pendingOutput.uri,
                        restoreRayneoPolicy = rayneoPolicyPrepared
                    )
                    return@withLock recorderResult
                }

                is AppResult.Success -> recorderResult.value
            }

            configureRecorderCallbacks(recorder)

            when (val prepareResult = prepareRecorder(recorder)) {
                is AppResult.Failure -> {
                    cleanupStartFailure(
                        mediaRecorder = recorder,
                        virtualDisplay = null,
                        mediaProjection = projection,
                        projectionCallback = null,
                        outputPfd = pendingOutput.fileDescriptor,
                        pendingOutputUri = pendingOutput.uri,
                        restoreRayneoPolicy = rayneoPolicyPrepared
                    )
                    return@withLock prepareResult
                }

                is AppResult.Success -> Unit
            }

            val callback = projectionCallback()
            when (val registerResult = registerProjectionCallback(projection, callback)) {
                is AppResult.Failure -> {
                    cleanupStartFailure(
                        mediaRecorder = recorder,
                        virtualDisplay = null,
                        mediaProjection = projection,
                        projectionCallback = null,
                        outputPfd = pendingOutput.fileDescriptor,
                        pendingOutputUri = pendingOutput.uri,
                        restoreRayneoPolicy = rayneoPolicyPrepared
                    )
                    return@withLock registerResult
                }

                is AppResult.Success -> Unit
            }

            val virtualDisplay = when (
                val displayResult = createVirtualDisplay(
                    projection = projection,
                    recorder = recorder,
                    captureSize = captureSize,
                    densityDpi = densityDpi
                )
            ) {
                is AppResult.Failure -> {
                    cleanupStartFailure(
                        mediaRecorder = recorder,
                        virtualDisplay = null,
                        mediaProjection = projection,
                        projectionCallback = callback,
                        outputPfd = pendingOutput.fileDescriptor,
                        pendingOutputUri = pendingOutput.uri,
                        restoreRayneoPolicy = rayneoPolicyPrepared
                    )
                    return@withLock displayResult
                }

                is AppResult.Success -> displayResult.value
            }

            when (val startResult = startRecorder(recorder)) {
                is AppResult.Failure -> {
                    cleanupStartFailure(
                        mediaRecorder = recorder,
                        virtualDisplay = virtualDisplay,
                        mediaProjection = projection,
                        projectionCallback = callback,
                        outputPfd = pendingOutput.fileDescriptor,
                        pendingOutputUri = pendingOutput.uri,
                        restoreRayneoPolicy = rayneoPolicyPrepared
                    )
                    return@withLock startResult
                }

                is AppResult.Success -> Unit
            }

            activeSession = ActiveSession(
                outputUri = pendingOutput.uri,
                outputDisplayName = pendingOutput.displayName,
                outputPfd = pendingOutput.fileDescriptor,
                mediaProjection = projection,
                projectionCallback = callback,
                virtualDisplay = virtualDisplay,
                mediaRecorder = recorder,
                rayneoPolicyPrepared = rayneoPolicyPrepared,
                startedAtElapsedRealtimeMs = SystemClock.elapsedRealtime()
            )

            logger.d(TAG, "Recording started at ${captureSize.width}x${captureSize.height}.")
            AppResult.Success(Unit)
        }
    }

    override suspend fun stopRecording(): AppResult<RecordedVideoArtifact?> = withContext(controllerDispatcher) {
        mutex.withLock {
            val current = activeSession ?: return@withLock AppResult.Success(null)
            activeSession = null

            val stopResult = try {
                stopActiveRecorder(current)
            } catch (error: CancellationException) {
                throw error
            } catch (error: IllegalStateException) {
                logger.e(TAG, "Failed while stopping active recording.", error)
                AppResult.Failure(AppError.RecorderStopFailed())
            } catch (error: RuntimeException) {
                // Defensive: OEM media stack can surface runtime faults despite boundary mapping.
                logger.e(TAG, "Failed while stopping active recording.", error)
                AppResult.Failure(AppError.RecorderStopFailed())
            } finally {
                releaseSessionResources(
                    mediaRecorder = current.mediaRecorder,
                    virtualDisplay = current.virtualDisplay,
                    mediaProjection = current.mediaProjection,
                    projectionCallback = current.projectionCallback,
                    outputPfd = current.outputPfd
                )
            }
            val artifactResult: AppResult<RecordedVideoArtifact?> = when (stopResult) {
                is AppResult.Failure -> {
                    deleteOutput(current.outputUri)
                    stopResult
                }

                is AppResult.Success -> finalizeValidatedRecording(current)
            }

            if (current.rayneoPolicyPrepared) {
                rayneoAudioModeController.restoreDefaultAudioPolicy()
            }
            stopRecordingService()
            artifactResult
        }
    }

    override fun release() {
        runBlocking {
            stopRecording()
        }
        callbackScope.coroutineContext.cancel()
        controllerDispatcher.close()
    }

    private fun createConsentIntentLegacy(): AppResult<Intent> {
        return try {
            AppResult.Success(mediaProjectionManager.createScreenCaptureIntent())
        } catch (error: SecurityException) {
            logger.e(TAG, "Failed to create media projection consent intent.", error)
            AppResult.Failure(AppError.MediaProjectionConsentIntentFailed())
        } catch (error: IllegalStateException) {
            logger.e(TAG, "Failed to create media projection consent intent.", error)
            AppResult.Failure(AppError.MediaProjectionConsentIntentFailed())
        } catch (error: RuntimeException) {
            logger.e(TAG, "Failed to create media projection consent intent.", error)
            AppResult.Failure(AppError.MediaProjectionConsentIntentFailed())
        }
    }

    private fun createConsentIntentApi34(): AppResult<Intent> {
        val directAttempt = try {
            AppResult.Success(mediaProjectionManager.createScreenCaptureIntent())
        } catch (error: SecurityException) {
            logger.e(TAG, "Falling back to explicit media projection consent config.", error)
            AppResult.Failure(AppError.MediaProjectionConsentIntentFailed())
        } catch (error: IllegalStateException) {
            logger.e(TAG, "Falling back to explicit media projection consent config.", error)
            AppResult.Failure(AppError.MediaProjectionConsentIntentFailed())
        } catch (error: RuntimeException) {
            logger.e(TAG, "Falling back to explicit media projection consent config.", error)
            AppResult.Failure(AppError.MediaProjectionConsentIntentFailed())
        }
        if (directAttempt is AppResult.Success) {
            return directAttempt
        }

        return try {
            AppResult.Success(
                mediaProjectionManager.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForDefaultDisplay()
                )
            )
        } catch (error: SecurityException) {
            logger.e(TAG, "Failed to create fallback media projection consent intent.", error)
            AppResult.Failure(AppError.MediaProjectionConsentIntentFailed())
        } catch (error: IllegalStateException) {
            logger.e(TAG, "Failed to create fallback media projection consent intent.", error)
            AppResult.Failure(AppError.MediaProjectionConsentIntentFailed())
        } catch (error: RuntimeException) {
            logger.e(TAG, "Failed to create fallback media projection consent intent.", error)
            AppResult.Failure(AppError.MediaProjectionConsentIntentFailed())
        }
    }

    private fun acquireProjection(consentResultCode: Int, consentData: Intent): AppResult<MediaProjection> {
        return try {
            val projection = mediaProjectionManager.getMediaProjection(consentResultCode, consentData)
            if (projection == null) {
                AppResult.Failure(AppError.MediaProjectionDenied())
            } else {
                AppResult.Success(projection)
            }
        } catch (error: SecurityException) {
            logger.e(TAG, "Failed to acquire MediaProjection.", error)
            AppResult.Failure(AppError.MediaProjectionDenied())
        } catch (error: IllegalArgumentException) {
            logger.e(TAG, "Failed to acquire MediaProjection.", error)
            AppResult.Failure(AppError.MediaProjectionDenied())
        } catch (error: IllegalStateException) {
            logger.e(TAG, "Failed to acquire MediaProjection.", error)
            AppResult.Failure(AppError.RecorderStartFailed())
        }
    }

    private fun prepareRayneoPolicyIfNeeded(): AppResult<Boolean> {
        if (!rayneoDeviceDetector.isX3Device()) {
            return AppResult.Success(false)
        }

        return when (val prepare = rayneoAudioModeController.prepareForVideoRecording()) {
            is AppResult.Success -> AppResult.Success(true)
            is AppResult.Failure -> {
                if (prepare.error is AppError.RayneoAudioPolicySetupFailed) {
                    prepare
                } else {
                    AppResult.Failure(AppError.RayneoAudioPolicySetupFailed())
                }
            }
        }
    }

    private fun createRecorder(
        outputFileDescriptor: ParcelFileDescriptor,
        width: Int,
        height: Int
    ): AppResult<MediaRecorder> {
        return try {
            val recorder = MediaRecorder(context).apply {
                setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setVideoFrameRate(VIDEO_FRAME_RATE)
                setVideoEncodingBitRate(VIDEO_BIT_RATE)
                setVideoSize(width, height)
                setMaxDuration(MAX_DURATION_MS)
                setOutputFile(outputFileDescriptor.fileDescriptor)
            }
            AppResult.Success(recorder)
        } catch (error: IllegalArgumentException) {
            logger.e(TAG, "Failed to configure MediaRecorder.", error)
            AppResult.Failure(AppError.RecorderStartFailed())
        } catch (error: IllegalStateException) {
            logger.e(TAG, "Failed to configure MediaRecorder.", error)
            AppResult.Failure(AppError.RecorderStartFailed())
        } catch (error: RuntimeException) {
            logger.e(TAG, "Failed to configure MediaRecorder.", error)
            AppResult.Failure(AppError.RecorderStartFailed())
        }
    }

    private fun configureRecorderCallbacks(recorder: MediaRecorder) {
        recorder.setOnInfoListener { _, what, _ ->
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                callbackScope.launch {
                    logger.d(TAG, "Max recording duration reached.")
                    onProjectionStopped?.invoke()
                }
            }
        }
        recorder.setOnErrorListener { _, _, _ ->
            callbackScope.launch {
                logger.e(TAG, "Recorder reported fatal error.")
                onProjectionStopped?.invoke()
            }
        }
    }

    private fun prepareRecorder(recorder: MediaRecorder): AppResult<Unit> {
        return try {
            recorder.prepare()
            AppResult.Success(Unit)
        } catch (error: IOException) {
            logger.e(TAG, "MediaRecorder.prepare() failed.", error)
            AppResult.Failure(AppError.RecorderStartFailed())
        } catch (error: IllegalStateException) {
            logger.e(TAG, "MediaRecorder.prepare() failed.", error)
            AppResult.Failure(AppError.RecorderStartFailed())
        } catch (error: RuntimeException) {
            logger.e(TAG, "MediaRecorder.prepare() failed.", error)
            AppResult.Failure(AppError.RecorderStartFailed())
        }
    }

    private fun projectionCallback(): MediaProjection.Callback {
        return object : MediaProjection.Callback() {
            override fun onStop() {
                callbackScope.launch {
                    logger.d(TAG, "MediaProjection stopped by the system.")
                    onProjectionStopped?.invoke()
                }
            }
        }
    }

    private fun registerProjectionCallback(
        projection: MediaProjection,
        callback: MediaProjection.Callback
    ): AppResult<Unit> {
        return try {
            projection.registerCallback(callback, Handler(Looper.getMainLooper()))
            AppResult.Success(Unit)
        } catch (error: IllegalStateException) {
            logger.e(TAG, "Failed to register MediaProjection callback.", error)
            AppResult.Failure(AppError.RecorderStartFailed())
        } catch (error: SecurityException) {
            logger.e(TAG, "Failed to register MediaProjection callback.", error)
            AppResult.Failure(AppError.RecorderStartFailed())
        } catch (error: RuntimeException) {
            logger.e(TAG, "Failed to register MediaProjection callback.", error)
            AppResult.Failure(AppError.RecorderStartFailed())
        }
    }

    private fun createVirtualDisplay(
        projection: MediaProjection,
        recorder: MediaRecorder,
        captureSize: CaptureSize,
        densityDpi: Int
    ): AppResult<VirtualDisplay> {
        return try {
            val virtualDisplay = projection.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                captureSize.width,
                captureSize.height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.surface,
                null,
                null
            )
            if (virtualDisplay == null) {
                AppResult.Failure(AppError.RecorderStartFailed())
            } else {
                AppResult.Success(virtualDisplay)
            }
        } catch (error: IllegalStateException) {
            logger.e(TAG, "Failed to create virtual display.", error)
            AppResult.Failure(AppError.RecorderStartFailed())
        } catch (error: SecurityException) {
            logger.e(TAG, "Failed to create virtual display.", error)
            AppResult.Failure(AppError.RecorderStartFailed())
        } catch (error: RuntimeException) {
            logger.e(TAG, "Failed to create virtual display.", error)
            AppResult.Failure(AppError.RecorderStartFailed())
        }
    }

    private fun startRecorder(recorder: MediaRecorder): AppResult<Unit> {
        return try {
            recorder.start()
            AppResult.Success(Unit)
        } catch (error: IllegalStateException) {
            logger.e(TAG, "MediaRecorder.start() failed.", error)
            AppResult.Failure(AppError.RecorderStartFailed())
        } catch (error: RuntimeException) {
            logger.e(TAG, "MediaRecorder.start() failed.", error)
            AppResult.Failure(AppError.RecorderStartFailed())
        }
    }

    private suspend fun stopActiveRecorder(session: ActiveSession): AppResult<Unit> {
        val elapsedMs = SystemClock.elapsedRealtime() - session.startedAtElapsedRealtimeMs
        if (elapsedMs < MIN_RECORDER_STOP_DELAY_MS) {
            delay(MIN_RECORDER_STOP_DELAY_MS - elapsedMs)
        }
        return stopRecorderWithTimeout(session.mediaRecorder)
    }

    private fun stopRecorderWithTimeout(mediaRecorder: MediaRecorder): AppResult<Unit> {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "MediaRecorderStopThread").apply { isDaemon = true }
        }

        return try {
            val stopFuture = executor.submit<Boolean> {
                try {
                    mediaRecorder.stop()
                    true
                } catch (error: IllegalStateException) {
                    logger.e(TAG, "Recorder stop call threw.", error)
                    false
                } catch (error: RuntimeException) {
                    logger.e(TAG, "Recorder stop call threw.", error)
                    false
                }
            }

            try {
                val stopped = stopFuture.get(STOP_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                if (stopped) {
                    AppResult.Success(Unit)
                } else {
                    AppResult.Failure(AppError.RecorderStopFailed())
                }
            } catch (timeout: TimeoutException) {
                logger.e(TAG, "Recorder stop timed out.", timeout)
                stopFuture.cancel(true)
                AppResult.Failure(AppError.RecorderStopFailed())
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                logger.e(TAG, "Recorder stop interrupted.", error)
                AppResult.Failure(AppError.RecorderStopFailed())
            } catch (error: ExecutionException) {
                logger.e(TAG, "Recorder stop execution failed.", error)
                AppResult.Failure(AppError.RecorderStopFailed())
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun finalizeValidatedRecording(session: ActiveSession): AppResult<RecordedVideoArtifact> {
        return when (val validation = recordedFileValidator.validate(session.outputUri)) {
            is AppResult.Failure -> {
                deleteOutput(session.outputUri)
                validation
            }

            is AppResult.Success -> {
                when (val finalize = mediaStoreVideoWriter.finalizeOutput(session.outputUri)) {
                    is AppResult.Success -> AppResult.Success(
                        RecordedVideoArtifact(
                            sourceUri = session.outputUri,
                            displayName = session.outputDisplayName
                        )
                    )

                    is AppResult.Failure -> {
                        deleteOutput(session.outputUri)
                        finalize
                    }
                }
            }
        }
    }

    private fun cleanupStartFailure(
        mediaRecorder: MediaRecorder?,
        virtualDisplay: VirtualDisplay?,
        mediaProjection: MediaProjection?,
        projectionCallback: MediaProjection.Callback?,
        outputPfd: ParcelFileDescriptor?,
        pendingOutputUri: Uri,
        restoreRayneoPolicy: Boolean
    ) {
        releaseSessionResources(
            mediaRecorder = mediaRecorder,
            virtualDisplay = virtualDisplay,
            mediaProjection = mediaProjection,
            projectionCallback = projectionCallback,
            outputPfd = outputPfd
        )
        deleteOutput(pendingOutputUri)
        if (restoreRayneoPolicy) {
            rayneoAudioModeController.restoreDefaultAudioPolicy()
        }
        stopRecordingService()
    }

    private fun releaseSessionResources(
        mediaRecorder: MediaRecorder?,
        virtualDisplay: VirtualDisplay?,
        mediaProjection: MediaProjection?,
        projectionCallback: MediaProjection.Callback?,
        outputPfd: ParcelFileDescriptor?
    ) {
        safelyResetRecorder(mediaRecorder)
        safelyReleaseRecorder(mediaRecorder)
        safelyReleaseVirtualDisplay(virtualDisplay)
        safelyUnregisterProjectionCallback(mediaProjection, projectionCallback)
        safelyStopProjection(mediaProjection)
        safelyCloseOutput(outputPfd)
    }

    private fun safelyResetRecorder(mediaRecorder: MediaRecorder?) {
        if (mediaRecorder == null) {
            return
        }
        try {
            mediaRecorder.reset()
        } catch (error: IllegalStateException) {
            logger.d(TAG, "MediaRecorder reset failed: ${error.message}")
        } catch (error: RuntimeException) {
            logger.d(TAG, "MediaRecorder reset failed: ${error.message}")
        }
    }

    private fun safelyReleaseRecorder(mediaRecorder: MediaRecorder?) {
        if (mediaRecorder == null) {
            return
        }
        try {
            mediaRecorder.release()
        } catch (error: RuntimeException) {
            logger.d(TAG, "MediaRecorder release failed: ${error.message}")
        }
    }

    private fun safelyReleaseVirtualDisplay(virtualDisplay: VirtualDisplay?) {
        if (virtualDisplay == null) {
            return
        }
        try {
            virtualDisplay.release()
        } catch (error: RuntimeException) {
            logger.d(TAG, "VirtualDisplay release failed: ${error.message}")
        }
    }

    private fun safelyUnregisterProjectionCallback(
        mediaProjection: MediaProjection?,
        projectionCallback: MediaProjection.Callback?
    ) {
        if (mediaProjection == null || projectionCallback == null) {
            return
        }
        try {
            mediaProjection.unregisterCallback(projectionCallback)
        } catch (error: IllegalStateException) {
            logger.d(TAG, "MediaProjection callback unregister failed: ${error.message}")
        } catch (error: RuntimeException) {
            logger.d(TAG, "MediaProjection callback unregister failed: ${error.message}")
        }
    }

    private fun safelyStopProjection(mediaProjection: MediaProjection?) {
        if (mediaProjection == null) {
            return
        }
        try {
            mediaProjection.stop()
        } catch (error: IllegalStateException) {
            logger.d(TAG, "MediaProjection stop failed: ${error.message}")
        } catch (error: RuntimeException) {
            logger.d(TAG, "MediaProjection stop failed: ${error.message}")
        }
    }

    private fun safelyCloseOutput(outputPfd: ParcelFileDescriptor?) {
        if (outputPfd == null) {
            return
        }
        try {
            outputPfd.close()
        } catch (error: IOException) {
            logger.d(TAG, "Output descriptor close failed: ${error.message}")
        }
    }

    private fun deleteOutput(uri: Uri) {
        when (val deleteResult = mediaStoreVideoWriter.deleteOutput(uri)) {
            is AppResult.Success -> Unit
            is AppResult.Failure -> logger.e(TAG, "Failed to delete recording output: $uri", null)
        }
    }

    private fun stopRecordingService() {
        try {
            RecordingService.stop(context)
        } catch (error: IllegalStateException) {
            logger.d(TAG, "Recording service stop failed: ${error.message}")
        } catch (error: RuntimeException) {
            logger.d(TAG, "Recording service stop failed: ${error.message}")
        }
    }

    private fun startRecordingService(): AppResult<Unit> {
        return try {
            RecordingService.start(context)
            AppResult.Success(Unit)
        } catch (error: SecurityException) {
            logger.e(TAG, "Recording service start failed.", error)
            AppResult.Failure(AppError.RecorderStartFailed())
        } catch (error: IllegalStateException) {
            logger.e(TAG, "Recording service start failed.", error)
            AppResult.Failure(AppError.RecorderStartFailed())
        } catch (error: RuntimeException) {
            logger.e(TAG, "Recording service start failed.", error)
            AppResult.Failure(AppError.RecorderStartFailed())
        }
    }

    private suspend fun awaitRecordingServiceReady(): AppResult<Unit> {
        repeat(FOREGROUND_SERVICE_READY_MAX_ATTEMPTS) {
            if (RecordingService.isForegroundReady()) {
                return AppResult.Success(Unit)
            }
            delay(FOREGROUND_SERVICE_READY_POLL_MS)
        }
        logger.e(TAG, "Recording service did not enter foreground in time.", null)
        return AppResult.Failure(AppError.RecorderStartFailed())
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
        val outputDisplayName: String,
        val outputPfd: ParcelFileDescriptor,
        val mediaProjection: MediaProjection,
        val projectionCallback: MediaProjection.Callback,
        val virtualDisplay: VirtualDisplay,
        val mediaRecorder: MediaRecorder,
        val rayneoPolicyPrepared: Boolean,
        val startedAtElapsedRealtimeMs: Long
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
        private const val MIN_RECORDER_STOP_DELAY_MS = 1_000L
        private const val STOP_CALL_TIMEOUT_MS = 4_000L
        private const val FOREGROUND_SERVICE_READY_MAX_ATTEMPTS = 20
        private const val FOREGROUND_SERVICE_READY_POLL_MS = 50L
    }
}
