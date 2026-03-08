package com.example.arspatialpinning.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.WindowMetricsCalculator
import com.example.arspatialpinning.app.navigation.AppNavHost
import com.example.arspatialpinning.platform.media.SharedRecordingSideEffect

@Composable
fun ArSpatialPinningApp(
    appContainer: AppContainer
) {
    MaterialTheme {
        val context = LocalContext.current
        val activity = context.findActivity()
        val lifecycleOwner = LocalLifecycleOwner.current
        val containerSize = LocalWindowInfo.current.containerSize
        val recordingStateHolder = remember { appContainer.sharedRecordingStateHolder }
        val recordingUiState by recordingStateHolder.uiState.collectAsStateWithLifecycle()

        val downloadDestinationLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { result ->
            recordingStateHolder.onDownloadDestinationSelected(result.data?.data)
        }
        val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { granted ->
            recordingStateHolder.onRecordAudioPermissionResult(granted)
        }
        val mediaProjectionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { result ->
            recordingStateHolder.onMediaProjectionConsentResult(
                resultCode = result.resultCode,
                data = result.data
            )
        }

        val hasRecordAudioPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        LaunchedEffect(hasRecordAudioPermission) {
            recordingStateHolder.onRecordAudioPermissionStateObserved(hasRecordAudioPermission)
        }

        LaunchedEffect(containerSize, activity) {
            val bounds = activity?.let {
                WindowMetricsCalculator.getOrCreate()
                    .computeMaximumWindowMetrics(it)
                    .bounds
            } ?: Rect(0, 0, 1080, 1920)
            recordingStateHolder.onMaximumWindowBoundsChanged(bounds)
        }

        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> recordingStateHolder.onAppResumed()
                    Lifecycle.Event.ON_PAUSE -> recordingStateHolder.onAppPaused()
                    Lifecycle.Event.ON_STOP -> recordingStateHolder.onAppStopped()
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                recordingStateHolder.release()
            }
        }

        LaunchedEffect(Unit) {
            recordingStateHolder.sideEffects.collect { sideEffect ->
                when (sideEffect) {
                    SharedRecordingSideEffect.RequestRecordAudioPermission -> {
                        recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }

                    is SharedRecordingSideEffect.RequestMediaProjectionConsent -> {
                        mediaProjectionLauncher.launch(sideEffect.intent)
                    }

                    is SharedRecordingSideEffect.LaunchDownloadDestinationPicker -> {
                        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "video/mp4"
                            putExtra(Intent.EXTRA_TITLE, sideEffect.suggestedFileName)
                        }
                        downloadDestinationLauncher.launch(intent)
                    }

                    is SharedRecordingSideEffect.ShowSnackbar -> {
                        Toast.makeText(context, sideEffect.message, Toast.LENGTH_SHORT).show()
                        recordingStateHolder.clearTransientMessage()
                    }
                }
            }
        }

        AppNavHost(
            appContainer = appContainer,
            sharedRecordingUiState = recordingUiState,
            onRecordClick = recordingStateHolder::onRecordClick,
            onStopRecordClick = recordingStateHolder::onStopRecordClick,
            onDownloadRecordingClick = recordingStateHolder::onDownloadRecordingClick
        )
    }
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
