# Detailed Design Specification
## Android AR Spatial Image Pinning App

**Document Version:** 1.8  
**Document Status:** Updated to Eliminate Render-Readiness Contract Gaps and Match Latest Product Overrides  
**Language:** English  
**Target OS Support:** Android 12L / API 32  
**Implementation Stack:** Kotlin, Jetpack Compose, SceneView, ARCore  

---

## 1. Purpose

This document defines the detailed design for an Android application that allows a user to select a single PNG or JPEG image, preview and place that image at an arbitrary real-world position in an AR scene, manipulate that image, and record the AR screen session with microphone audio.

This revision incorporates the second round of implementation feedback and focuses on **root-cause elimination**, not only wording cleanup. The previous version still left two critical failure paths open:

1. the selected image could be accepted as data but never become a confirmed visible AR renderable, and  
2. the recording design and policy text were inconsistent with the latest requirement that platform-default MediaProjection capture behavior is acceptable.

This revision therefore strengthens:
- the render-pipeline contract from file selection to visible preview to final placement,
- the visibility and scene-attachment rules for preview/placed nodes,
- the recording lifecycle robustness rules,
- the capture-start and saved-file validation rules for recording, and
- the automated and manual test design required to prove these behaviors on device.

### 1.1 v8 Update Notes (Authoritative Overrides)

This v8 document reflects the latest implemented code, the latest product override that AR-window-only recording is not required, and supersedes conflicting older statements in this file.

1. Recording scope policy is updated:
   - recording is initiated from the AR route and tied to AR route lifecycle,
   - platform-default MediaProjection capture behavior is accepted,
   - API 32/33 full-display capture is acceptable,
   - Android 14+ app-window or full-display capture is acceptable depending on user choice,
   - recording is **not** capability-gated on app-window-sharing availability.

2. File picker reliability is hardened for real-device providers:
   - URI open fallback order:
     - `openInputStream(uri)`
     - `openAssetFileDescriptor(uri, "r")`
     - `openFileDescriptor(uri, "r")`
     - `openTypedAssetFileDescriptor(uri, mimeType, null)` with MIME candidates (`image/png`, `image/jpeg`, `image/jpg`, `image/*`, `*/*`),
   - temporary read-permission guard around load flow,
   - no long-term URI permission retention across restarts.

3. `Place` enablement remains strict and unchanged:
   - requires AR ready + selected image + `RenderAssetState.Ready` + `PreviewRenderState.Visible` + stable hit + waiting placement mode.

4. State model alignment with code:
   - `recordingCapability` is removed from `ArUiState`,
   - `canRecord` depends on `recordingState is Idle`,
   - `Select Image` is disabled while recording is preparing/active/finalizing via `recordingState.blocksImageSelection`.

5. New test focus in v8:
   - URI typed-stream fallback candidate generation,
   - fallback ordering behavior for stream open attempts,
   - real-device validation for the previously reported `Unable to open the selected file` path,
   - prevention of metadata-only or dimension-only render success,
   - stale asset-handle rejection after image replacement, and
   - proof that preview visibility is derived from controller debug render truth rather than optimistic state transitions.

6. Render-readiness contract is hardened at the type/interface level:
   - `SelectedImage` is metadata-only and must not contain `Bitmap` or any heavy render object,
   - `PreparedRenderAsset` is an opaque controller-issued proof that a texture/material/preview-node bundle already exists inside `ArSceneController`,
   - `RenderAssetState.Ready` is invalid unless the controller has registered that opaque asset handle, and
   - final placement is performed from `PreparedRenderAsset`, not by reopening `SelectedImage`.

7. Preview visibility truth is identity-bound:
   - `DebugRenderStatus` must include the prepared/preview/placed asset handle IDs,
   - `PreviewRenderState.Visible` is valid only for the same prepared asset handle that is currently attached, visible, and receiving pose updates,
   - stale debug status from a previous image selection must not keep **Place** enabled for a replaced image.

Note: Any remaining legacy text in this file that claims AR-only recording capability gating should be interpreted as historical context and overridden by this section.

---

## 2. Review Summary

The previous version corrected several first-pass issues, but it still permitted implementation failure in the two places that matter most for the product requirement: **visible AR placement** and **usable recording output**.

### 2.1 Root-Cause Findings

1. **Renderable readiness still had a weak type contract**  
   The document text said that bitmap decode, texture upload, material creation, and preview-node creation had to succeed, but the domain model still allowed `SelectedImage` to carry a `Bitmap` in UI state while `PreparedRenderAsset` carried only dimensions. This meant an implementation could incorrectly treat dimensions or decoded metadata as render success, emit `RenderAssetState.Ready`, and still have no controller-owned render bundle available for preview or placement.

2. **Preview visibility was specified as behavior but not identity-bound truth**  
   The design said that a preview should appear and that debug status should exist, but it still did not bind preview visibility to the same prepared asset handle that had actually been attached, made visible, and pose-updated in the scene. As a result, an implementation could compute `PreviewRenderState.Visible` from optimistic state transitions or stale debug data from a previously selected image.

3. **The geometry/visibility contract was still too weak**  
   The prior version did not fix the render pivot and anti-hidden-placement policy strongly enough. A quad could still be implemented with an unsuitable pivot or coplanar pose and remain visually absent or unreliable during placement.

4. **The test design checked flow but not rendering truth**  
   The tests covered file selection and button transitions, but they did not require proof that the preview and placed image were actually rendered in the AR scene. This allowed a false pass where state changed but nothing visible appeared.

5. **The recording requirement was internally inconsistent with the supported OS policy**  
   The document still claimed an AR-screen-only recording requirement while keeping the feature available on Android 12L / 13 behavior. Official Android guidance states that app-window screen sharing is available starting in Android 14 QPR2, while MediaProjection otherwise captures the device display or an app window depending on platform support. Therefore, an AR-route-only guarantee cannot be truthfully promised on API 32/33 with this MediaProjection-based design. [R5][R13]

6. **The recording start contract proved consent, not successful capture**  
   The prior version required consent, service startup, and recorder start, but it did not require evidence that capture had actually begun, resized to the true captured region when needed, and produced a valid file.

7. **The design could not verify the user-selected capture target identity**  
   On app-screen-sharing capable versions, MediaProjection provides resize and visibility callbacks for the captured content, but not an API that returns the identity of the chosen app window. The design therefore cannot strictly enforce 窶徼his app window窶・by code alone; it must combine pre-consent instruction, lifecycle constraints, and runtime callbacks to minimize wrong-target capture. [R5][R13]

### 2.2 Review Result

Version 1.7 was **not sufficient** as an implementation-ready detailed design for the stated product goal. The remaining gaps were behavioral and test-design defects, not editorial issues.

### 2.3 Design Direction of This Revision

This revision makes the following hard changes:

- harden the render-asset contract so `Ready` means a controller-registered texture/material/preview-node bundle really exists,
- remove `Bitmap` ownership from UI state and keep heavy render objects controller-private,
- make **visible preview readiness** a precondition for **Place**,
- require preview visibility to be derived from identity-bound controller debug truth,
- disable AR depth usage for this feature to avoid unnecessary visibility ambiguity,
- require controller-level observability for preview and placed node attachment and visibility,
- align recording policy with platform-default MediaProjection behavior while preserving AR-route lifecycle ownership,
- add capture-active and saved-file validation rules that reject empty or invalid output, and
- expand the test plan so that rendering truth and recording truth are both verified.

## 3. Design Basis and External References

The design is based on the following technology assumptions and public references:

- **ARCore** is used as the AR engine and provides session management, hit testing, tracking, planes, anchors, and installation/runtime availability contracts. [R1]
- **SceneView** is used as the rendering and AR integration layer for Android/Compose. [R2]
- **Jetpack Compose Navigation** is used for screen transitions in a single-activity architecture. [R3]
- **ActivityResultContracts.OpenDocument** is used for file picker based image selection (PNG/JPEG). [R4]
- **MediaProjection** is used for screen capture consent and virtual display creation. [R5]
- **MediaRecorder** is used to encode captured video and microphone audio into an MP4 file. [R6]
- **Jetpack Compose** is the primary UI framework. [R7]
- **Storage Access Framework** rules govern temporary document access and the optional use of persistable URI permissions. [R8]
- **WindowMetrics** is used to derive the maximum display bounds for MediaProjection sizing. [R9]
- **Foreground service type requirements** for `mediaProjection` are part of the design basis because the app targets the latest stable SDK. [R10]
- **Notification permission behavior** on Android 13+ is part of the design basis for the recording service notification policy. [R11]
- **ARCore focus mode guidance** is part of the design basis for session configuration. [R12]

---

## 4. Scope

### 4.1 In Scope

- Start menu screen
- Navigation from start menu to AR screen
- PNG/JPEG file selection using the system file picker
- Placement of one PNG/JPEG image into AR space
- Image scale, rotation, reposition, and delete operations
- Session-scoped anchor management
- AR-route-scoped screen recording with microphone audio using platform-default MediaProjection behavior
- Error handling for invalid file selection, render preparation failure, unavailable AR support, permission denial, and recording failure

### 4.2 Out of Scope

- Persistent world anchor storage
- Cloud anchors
- Multi-image placement
- Formats other than PNG/JPEG
- User login or cloud sync
- Image editing such as crop/filter/background removal
- Cross-session restoration of image placement
- Post-processing/editing of recorded video files

---

## 5. Platform and SDK Policy

### 5.1 Supported Android Version

The application runtime policy remains:

- **minSdk = 32**
- **compileSdk = latest stable SDK available at implementation time**
- **targetSdk = latest stable SDK available at implementation time**
- **Runtime compatibility promise for AR placement = API 32 and above**

Recording policy in v7:

- recording starts only from the AR route and follows AR route lifecycle constraints,
- platform-default MediaProjection capture behavior is accepted,
- API 32/33 full-display capture is acceptable,
- Android 14+ app-window or full-display capture is acceptable depending on user choice,
- recording remains available whenever AR screen is operational and `recordingState == Idle` (subject to permissions/consent flow).

### 5.2 Device Capability Requirements

A device is considered supported for **AR placement** only if all of the following are true:

- ARCore is supported by the device
- Camera is available
- Google Play Services for AR is installed or installable

A device is considered supported for recording if all of the following are true:

- the AR placement requirements above are satisfied,
- microphone permission can be granted,
- a MediaProjection session can be created.

If AR placement requirements are unavailable, the AR screen remains non-operational and presents a blocking message.

If AR placement works but recording prerequisites are not yet met (permission/consent), the AR screen remains usable and the recording flow is retriable.

Note:
- because the manifest marks AR as required, normal Play-distributed installs should already be filtered to compatible devices,
- the runtime unsupported-device path remains in the design for sideload, stale compatibility information, and external-install cases.

---

## 6. Resolved Design Decisions

This section fixes all functional and technical items that were previously undefined or underdefined.

### 6.1 Application Structure

- The app is implemented as a **single-activity application**.
- `MainActivity` hosts a Compose `NavHost`.
- Two navigation destinations exist:
  - `start`
  - `ar`

Navigation route IDs are implemented as string constants rather than sealed route objects, to keep integration with Navigation Compose straightforward.

### 6.2 Screen Orientation

- The application is **portrait-only**.
- The activity is locked to portrait orientation.
- Orientation changes do not trigger AR scene recreation.

Rationale:
- simpler MediaProjection configuration
- stable gesture behavior
- lower lifecycle complexity for SceneView and recording

### 6.3 Placement Method

- An image must be selected before placement is allowed.
- Placement is based on a **center-screen reticle**.
- The reticle continuously follows a **stabilized** center hit result.
- File acceptance alone is insufficient. Before placement can be enabled, the selected image must also reach **render-asset ready** state:
  - bitmap decode/validation succeeded,
  - texture upload succeeded,
  - material instance creation succeeded, and
  - preview node creation succeeded.
- When a selected image exists, render-asset preparation has succeeded, and a stable valid hit exists, the application renders a live **placement preview** of the selected image in AR space at the candidate pose.
- The preview uses the same aspect ratio, base size, pivot, and orientation rules as the final placed image.
- **Place** is enabled only while the preview is confirmed visible.
- Supported trackables:
  - horizontal up-facing planes
  - vertical planes
- Candidate hit selection rule:
  - choose the nearest supported center hit,
  - require the same trackable and approximately the same pose to remain valid for **3 consecutive frames** before the hit is considered stable,
  - reset stability when tracking state changes or the candidate trackable changes.

This design intentionally avoids direct tap-to-place because the application also needs stable two-finger transform gestures, and the center-reticle model reduces gesture ambiguity.

### 6.4 Initial Image Orientation

The selected image is rendered as a flat rectangular quad. PNG transparency is preserved when present; JPEG is rendered as opaque.

At placement time:
- the anchor position is taken from the current center hit,
- the quad is positioned from a **bottom-center local pivot** so that the visible image stands on, rather than intersects through, the candidate pose,
- the quad is made **upright in world space**,
- the quad is rotated around the world Y axis so that its front initially faces the user,
- the quad is offset slightly toward the user along the horizontal camera-to-anchor direction (**0.01 m**) to avoid coplanar hiding or z-fighting at the hit pose.

Important clarification:
- the image is **anchored by the hit position**, but it is **not forced to lie flush on the detected plane surface**,
- even when placed using a vertical plane hit, the image remains an upright facing object rather than a wall-aligned poster.

This behavior is selected because the requirement is 窶徭patially fixed AR placement窶・ not 窶徭urface-stuck poster placement窶・

### 6.5 Placement Count

- Only **one image** may exist at any time.
- If a new image is selected while an image already exists:
  - the currently placed image is removed,
  - the existing anchor is released,
  - transform state resets to default,
  - the new image becomes the active selected image,
  - the screen enters `WaitingForPlacement`.
- No replacement confirmation dialog is shown.

### 6.6 Transform Behavior

- **Scale:** pinch gesture
- **Rotation:** two-finger rotate gesture
- **Reposition:** explicit mode entered through a button
- **Delete:** explicit button action

Free single-finger drag is not used for repositioning because it is error-prone in handheld AR and conflicts with camera motion interpretation.

### 6.7 Scale Range

- Default image height: **0.30 m**
- Width: derived from source image aspect ratio
- Allowed user scale range: **0.25x to 4.00x** relative to default size

### 6.8 Rotation Range

- Rotation axis: **world Y axis only**
- Range: normalized to `0f .. <360f`
- Pitch and roll are not user-editable

### 6.9 Session Persistence

- Image placement state exists in memory only
- No anchor, transform, or selected image state is persisted to disk
- Process death or app restart resets the AR scene completely

### 6.10 Recording Behavior

Recording is available only on the AR screen and is bound to AR route lifecycle.

Recording captures:
- platform-default MediaProjection capture result (full-display or app-window depending on OS/user choice),
- microphone audio

Product rule:
- recording start is allowed only while the AR route is active and `RESUMED`,
- leaving the AR route or losing the AR screen foreground state stops recording.

Capture-scope rule:
- API 32/33 full-display capture is acceptable,
- Android 14+ app-window/full-display user choice is acceptable,
- implementation does not capability-gate on app-window-sharing availability.

Recording configuration:
- container: **MP4**
- video codec: **H.264 / AVC**
- audio codec: **AAC**
- frame rate: **30 fps**
- nominal bitrate: **8 Mbps**
- nominal size: portrait capture, capped to **1080 x 1920** while preserving the actual capture aspect within the cap
- maximum duration: **10 minutes**

Recording output:
- MediaStore collection: video external collection
- relative path: `Movies/ARSpatialPinning`
- file name pattern: `ar_recording_yyyyMMdd_HHmmss.mp4`

Projection sizing rule:
- derive the initial source bounds from **maximum window metrics**,
- round the final width and height down to **even integers**,
- if encoder compatibility issues are observed on target hardware, round down further to the nearest multiple of 16.

Foreground-service rule:
- because the app targets the latest stable SDK, the recording path includes a dedicated foreground service for MediaProjection on Android 14+,
- the service is started only for the active recording session,
- the service owns the ongoing notification required by the platform,
- the service is stopped immediately after recording finalization or failure cleanup completes.

Important corrections:
- the output MediaStore entry is created **after** MediaProjection consent is granted,
- recorder initialization is finalized only after consent succeeds,
- incomplete files must be deleted if start-up fails,
- MediaProjection consent data is **never cached** across sessions,
- each recording session performs exactly one `getMediaProjection()` and one `createVirtualDisplay()` call for the consent grant that started that session,
- recording start is allowed only while the AR screen is in the foreground `RESUMED` state,
- recording start is considered successful only after the capture session reports active content (see Section 12.8),
- recording finalization is considered successful only after saved-file validation confirms a usable MP4 (see Section 12.8).

### 6.11 File Picker Behavior

- File selection uses `ActivityResultContracts.OpenDocument` with MIME filters `image/png` and `image/jpeg`. [R4]
- Accepted filename extensions are `.png`, `.jpg`, and `.jpeg`.
- The selected `Uri` is used only within the current session.
- Load flow uses temporary read-permission guard semantics:
  - if persistable read permission is available, it may be acquired and then explicitly released in `finally`,
  - no URI permission is retained across app restarts or session restoration. [R8]
- URI opening uses fallback sequence for provider compatibility:
  - `openInputStream`
  - `openAssetFileDescriptor`
  - `openFileDescriptor`
  - `openTypedAssetFileDescriptor` with MIME candidate fallback
- Successful file selection must lead to one of two explicit outcomes only:
  - **RenderAssetReady**
  - **RenderAssetError**
- Silent fallback to metadata-only success is prohibited.

### 6.12 Permission Strategy

Required runtime permissions:
- `android.permission.CAMERA`
- `android.permission.RECORD_AUDIO`

Conditionally declared permissions:
- `android.permission.FOREGROUND_SERVICE`
- `android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION`
- `android.permission.POST_NOTIFICATIONS` (declared because targetSdk is current, but not required for recording success)

Consent flows:
- camera permission: requested on entry to the AR screen if not already granted
- microphone permission: requested when user taps **Record** and permission is missing
- MediaProjection consent: requested only after microphone permission is granted and recording start is requested
- notification permission: not requested as a blocker for recording; if denied on Android 13+, the foreground-service notice may be hidden from the notification drawer but the service may still run

Corrected enablement policy:
- **Record** is enabled whenever recording state is `Idle` and the AR screen is operational,
- if microphone permission is missing, tapping **Record** initiates the permission request instead of failing silently,
- while recording is active, preparing, or finalizing, **Select Image** is disabled to prevent file-picker navigation during capture.

### 6.13 AR Session Configuration

- Plane finding mode: horizontal + vertical
- Light estimation: disabled
- Depth: **disabled**
- Instant placement: disabled
- Cloud anchors: disabled
- Update mode: latest camera image
- Focus mode: **use the ARCore default session focus mode**; do not force `AUTO` globally

Rationale:
- the placed image uses an unlit material and does not consume AR lighting outputs,
- disabling light estimation removes unnecessary per-frame work and avoids avoidable configuration risk,
- this feature does not require AR depth for believable occlusion, but it does require reliable preview visibility,
- disabling depth removes one more cause of hidden preview and placed content and simplifies validation,
- ARCore guidance favors the default session focus mode for tracking performance,
- `AUTO` focus is reserved for scenarios that specifically require it.

### 6.14 Rendering Strategy

- The selected image is rendered with an **unlit** material
- PNG transparency is preserved when present; JPEG is rendered without alpha
- The material is **double-sided**
- The image does not cast or receive shadows
- The preview node and the placed node must each be explicit scene-graph objects owned by `ArSceneController`
- A selected image with a valid stable hit must be visibly rendered as an AR placement preview before final placement is confirmed
- Visibility is not inferred from state transitions alone; it is reported explicitly through controller-observable render state
- A render failure must surface as `E-FILE-003` rather than silently falling back to invisible behavior

### 6.15 Error UX

- Blocking conditions are shown as persistent inline panels
- Non-blocking failures are shown through snackbars
- System-owned dialogs are limited to runtime permissions and MediaProjection consent
- Preview/render preparation failure is shown immediately after file selection and clears the **Place** action until corrected

### 6.16 AR Route Session Scope

To remove ambiguity, the term **session** is fixed as follows:

- the AR session lifetime is the lifetime of the `ar` navigation destination while it is in the foreground,
- leaving the `ar` route clears `selectedImage`, `placedImage`, current reticle/hit state, and AR controller resources,
- returning to the `ar` route starts a new empty AR session,
- recording is always stopped and finalized before navigation away from the AR route completes.

This prevents stale SAF URIs, stale anchors, and partially-owned platform objects from surviving route recreation.


---

## 7. System Architecture

### 7.1 Architectural Style

The application uses a **layered MVVM architecture** with explicit controller boundaries for AR scene control and recording.

### 7.2 Layers

1. **Presentation Layer**
   - Compose screens
   - ViewModels
   - UI state and event reducers

2. **Application/Coordinator Layer**
   - Placement coordinator
   - Recording coordinator
   - Permission orchestration

3. **Platform Integration Layer**
   - SceneView/ARCore adapter
   - MediaProjection/MediaRecorder adapter
   - File reader and image validator

4. **Session State Layer**
   - In-memory state only

### 7.3 High-Level Component Diagram

```text
MainActivity
 笏披楳 AppNavHost
     笏懌楳 StartScreen
     笏披楳 ArScreen
         笏懌楳 ArViewModel
         笏懌楳 ArSceneContainer
         笏懌楳 PermissionGateway
         笏懌楳 FilePickerGateway
         笏懌楳 PlacementCoordinator
         笏懌楳 RecordingCoordinator
         笏披楳 SnackbarHost
```

### 7.4 Package Structure

```text
com.example.arspatialpinning
├─ app
│  ├─ MainActivity.kt
│  └─ AppContainer.kt
├─ feature
│  ├─ start
│  │  └─ StartScreen.kt
│  └─ ar
│     ├─ ArScreen.kt
│     ├─ ArViewModel.kt
│     ├─ ArUiState.kt
│     ├─ ArUiEvent.kt
│     ├─ ArSideEffect.kt
│     └─ component
│        ├─ ArToolbar.kt
│        ├─ ArControls.kt
│        ├─ BlockingPanel.kt
│        ├─ ReticleOverlay.kt
│        ├─ RecordingOverlay.kt
│        └─ TransformGestureOverlay.kt
├─ domain
│  ├─ model
│  │  ├─ SelectedImage.kt
│  │  ├─ PreparedRenderAsset.kt
│  │  ├─ PlacementTransform.kt
│  │  ├─ PlacedImageState.kt
│  │  ├─ PlacementMode.kt
│  │  ├─ RenderAssetState.kt
│  │  ├─ PreviewRenderState.kt
│  │  ├─ RecordingState.kt
│  │  ├─ HitTestUiModel.kt
│  │  └─ DebugRenderStatus.kt
│  └─ usecase
│     ├─ LoadImageUseCase.kt
│     ├─ PlaceImageUseCase.kt
│     ├─ ReplaceImageUseCase.kt
│     ├─ DeleteImageUseCase.kt
│     ├─ EnterRepositionModeUseCase.kt
│     ├─ ConfirmRepositionUseCase.kt
│     ├─ RequestRecordingUseCase.kt
│     ├─ StartRecordingUseCase.kt
│     └─ StopRecordingUseCase.kt
├─ platform
│  ├─ ar
│  │  ├─ ArSceneController.kt
│  │  ├─ ArSceneControllerImpl.kt
│  │  ├─ ArAvailabilityChecker.kt
│  │  ├─ HitTestResult.kt
│  │  ├─ PinnedImageNode.kt
│  │  ├─ TextureLoader.kt
│  │  └─ DebugRenderStatusTracker.kt
│  ├─ media
│  │  ├─ RecordingController.kt
│  │  ├─ RecordingControllerImpl.kt
│  │  ├─ RecordedFileValidator.kt
│  │  ├─ RecordingService.kt
│  │  ├─ RecordingNotificationFactory.kt
│  │  └─ MediaStoreVideoWriter.kt
│  └─ file
│     ├─ ImageUriReader.kt
│     ├─ ImageValidator.kt
│     ├─ UriReadPermissionGuard.kt
│     └─ UriStreamOpener.kt
└─ common
   ├─ AppError.kt
   ├─ Result.kt
   ├─ DispatcherProvider.kt
   └─ Logger.kt
```

---

## 8. Navigation Design

### 8.1 Route Definition

```kotlin
object Routes {
    const val START = "start"
    const val AR = "ar"
}
```

### 8.2 Navigation Rules

- App launch destination: `start`
- `start -> ar`: when user taps **Start AR Session**
- `ar -> start`: system back or the top app bar back button

### 8.3 Framework

- Navigation Compose with a single `NavHost` is used. [R3]

---

## 9. Screen Design

## 9.1 Start Screen

### 9.1.1 Purpose

Entry point into the AR feature.

### 9.1.2 UI Elements

- App title
- Short feature description
- Primary button: **Start AR Session**

### 9.1.3 User Actions

| Action | Result |
|---|---|
| Tap Start AR Session | Navigate to `ar` |

---

## 9.2 AR Screen

### 9.2.1 Purpose

Main operational screen for AR placement, manipulation, and recording.

### 9.2.2 Layout

The top app bar back button is behaviorally identical to system back.


**Normal Mode**

```text
+--------------------------------------------------+
| Top App Bar                                      |
| Back   Title                    Recording Badge  |
+--------------------------------------------------+
|                                                  |
|                AR Scene Surface                  |
|                                                  |
|          (reticle shown when applicable)         |
|                                                  |
+--------------------------------------------------+
| Blocking Panel / Status Banner / Snackbar Area   |
+--------------------------------------------------+
| [Select Image] [Place] [Reposition] [Delete]       |
| [Record / Stop]                                  |
+--------------------------------------------------+
```

**Reposition Mode**

```text
+--------------------------------------------------+
| Top App Bar                                      |
+--------------------------------------------------+
|                AR Scene Surface                  |
|            center reticle active                 |
+--------------------------------------------------+
| Reposition the image to the current reticle hit  |
+--------------------------------------------------+
| [Confirm Reposition] [Cancel] [Delete]           |
| [Record / Stop]                                  |
+--------------------------------------------------+
```

### 9.2.3 Button Enablement Rules

| Button | Enabled When |
|---|---|
| Select Image | recording state is `Idle` |
| Place | AR ready AND selected image exists AND render asset state is `Ready` AND preview render state is `Visible` for the same `assetHandleId` AND valid stable center hit exists AND placement mode is `WaitingForPlacement` |
| Reposition | placed image exists AND placement mode is `Placed` |
| Confirm Reposition | placement mode is `Repositioning` AND valid center hit exists |
| Cancel | placement mode is `Repositioning` |
| Delete | placed image exists |
| Record | AR ready AND recording state is `Idle` |
| Stop | recording state is `Active` |

### 9.2.4 Visual States

1. **Camera permission missing**  
   Blocking panel shown. AR scene surface is not created.

2. **ARCore availability/install required**  
   Blocking panel shown with retry/install guidance.

3. **AR initializing**  
   Loading indicator overlay.

4. **No image selected**  
   AR feed visible, placement disabled.

5. **Image selected, render preparing**  
   Center reticle may be shown, but **Place** remains disabled until render-asset preparation succeeds.

6. **Image selected, waiting for valid hit**  
   Center reticle shown with surface detection guidance. When a stable valid hit exists and the render asset is ready, a visible preview of the selected image is shown in AR space at the candidate placement pose.

7. **Placed**  
   Transform gestures enabled. Temporary AR tracking loss does not clear placement state; the image remains logically placed and resumes normal rendering when tracking recovers.

8. **Repositioning**  
   Existing image alpha = 50%. Reticle active. Transform gestures disabled.

9. **Recording preparing/finalizing**  
   Record is disabled while recording state is `Preparing` or `Finalizing`. `Select Image` is disabled while recording is `Preparing`, `Active`, or `Finalizing`.

10. **Recording active**  
   Red badge and elapsed timer shown.

---

## 10. State Model

### 10.1 UI State Definition

```kotlin
data class ArUiState(
    val hasCameraPermission: Boolean = false,
    val hasRecordAudioPermission: Boolean = false,
    val arAvailability: ArAvailability = ArAvailability.Unknown,
    val isArReady: Boolean = false,
    val isCameraTracking: Boolean = false,
    val selectedImage: SelectedImage? = null,
    val renderAssetState: RenderAssetState = RenderAssetState.None,
    val previewRenderState: PreviewRenderState = PreviewRenderState.HiddenNoSelection,
    val placedImage: PlacedImageState? = null,
    val placementMode: PlacementMode = PlacementMode.WaitingForPlacement,
    val currentHit: HitTestUiModel = HitTestUiModel(),
    val recordingState: RecordingState = RecordingState.Idle,
    val debugRenderStatus: DebugRenderStatus = DebugRenderStatus(),
    val blockingMessage: String? = null,
    val transientMessage: String? = null
)
```

### 10.1.1 Supporting Types

```kotlin
enum class ArAvailability {
    Unknown,
    Checking,
    Supported,
    Unsupported
}
```

```kotlin
sealed interface RenderAssetState {
    data object None : RenderAssetState
    data object Preparing : RenderAssetState
    data class Ready(val asset: PreparedRenderAsset) : RenderAssetState
    data class Error(val reason: String) : RenderAssetState
}
```

```kotlin
sealed interface PreviewRenderState {
    data object HiddenNoSelection : PreviewRenderState
    data object HiddenPreparing : PreviewRenderState
    data object HiddenNoTracking : PreviewRenderState
    data object HiddenNoStableHit : PreviewRenderState
    data class Visible(val assetHandleId: String) : PreviewRenderState
    data class Error(val reason: String) : PreviewRenderState
}
```

```kotlin
sealed interface RecordingState {
    data object Idle : RecordingState
    data object Preparing : RecordingState
    data class Active(val startedAtMillis: Long) : RecordingState
    data object Finalizing : RecordingState
    data class Failed(val message: String) : RecordingState

    val blocksImageSelection: Boolean
        get() = this is Preparing || this is Active || this is Finalizing
}
```

```kotlin
data class HitTestUiModel(
    val hasValidHit: Boolean = false,
    val stabilizationFrames: Int = 0,
    val hasStableHit: Boolean = false,
    val trackableId: String? = null
)
```

```kotlin
enum class PlacementMode {
    WaitingForPlacement,
    Placed,
    Repositioning
}
```

### 10.2 Placement Mode State Machine

```text
WaitingForPlacement
 -> (renderAssetReady + previewVisible + stableHit + Place) -> Placed
 -> (replace image) -> WaitingForPlacement
 -> (render/preview error) -> WaitingForPlacement

Placed
 -> (Reposition tapped) -> Repositioning
 -> (Delete tapped) -> WaitingForPlacement
 -> (replace image) -> WaitingForPlacement
 -> (temporary tracking loss) -> Placed

Repositioning
 -> (valid stable hit + Confirm) -> Placed
 -> (Cancel) -> Placed
 -> (Delete tapped) -> WaitingForPlacement
 -> (replace image) -> WaitingForPlacement
```

### 10.3 Recording State Machine

```text
Idle
 -> (Record tapped; permission+consent flow starts) -> Preparing

Preparing
 -> (start success) -> Active
 -> (start failure / consent denied) -> Failed

Active
 -> (Stop tapped / 10 min / projection onStop / route exit / fatal error / window-size change) -> Finalizing

Finalizing
 -> (finalize success + file validation success) -> Idle
 -> (finalize failure) -> Failed

Failed
 -> (message consumed or retry path) -> Idle
```
---

## 11. Domain Model

### 11.1 SelectedImage

```kotlin
data class SelectedImage(
    val uri: Uri,
    val format: ImageFormat,
    val mimeType: String,
    val widthPx: Int,
    val heightPx: Int,
    val selectionRevision: Long
)

enum class ImageFormat {
    Png,
    Jpeg
}
```

### 11.2 PreparedRenderAsset

```kotlin
data class PreparedRenderAsset(
    val assetHandleId: String,
    val selectionRevision: Long,
    val widthMeters: Float,
    val heightMeters: Float,
    val aspectRatio: Float
)
```

`PreparedRenderAsset` is not a geometry-only DTO. It is an opaque proof that `ArSceneController` has already registered a prepared texture/material/preview-node bundle for `assetHandleId`. Constructing this object from dimensions alone is prohibited.

### 11.3 PlacementTransform

```kotlin
data class PlacementTransform(
    val scale: Float = 1f,
    val rotationYDegrees: Float = 0f
)
```

### 11.4 PlacedImageState

```kotlin
data class PlacedImageState(
    val anchorId: String,
    val widthMeters: Float,
    val heightMeters: Float,
    val transform: PlacementTransform = PlacementTransform()
)
```

### 11.5 RecordingState

```kotlin
sealed interface RecordingState {
    data object Idle : RecordingState
    data object Preparing : RecordingState
    data class Active(val startedAtMillis: Long) : RecordingState
    data object Finalizing : RecordingState
    data class Failed(val message: String) : RecordingState
}
```

### 11.6 DebugRenderStatus

```kotlin
data class DebugRenderStatus(
    val preparedAssetHandleId: String? = null,
    val previewAssetHandleId: String? = null,
    val previewNodeExists: Boolean = false,
    val previewNodeAttached: Boolean = false,
    val previewNodeVisible: Boolean = false,
    val placedAssetHandleId: String? = null,
    val placedNodeExists: Boolean = false,
    val placedNodeAttached: Boolean = false,
    val previewPoseUpdateFrameCount: Long = 0L,
    val previewPoseUpdatedForAssetHandleId: String? = null
)
```

This type exists to make render truth observable in tests and logs without exposing raw SceneView or ARCore objects outside the controller boundary.

---

## 12. Detailed Functional Design

## 12.1 Startup and AR Availability Flow

### 12.1.1 Sequence

```text
MainActivity.onCreate
 -> setContent(AppNavHost)
 -> StartScreen rendered
 -> user taps Start AR Session
 -> navigate("ar")
 -> AR screen checks CAMERA permission
 -> AR availability/install check executed
 -> if available, initialize SceneView/AR session
```

### 12.1.2 ARCore Availability Rules

The application performs ARCore availability warm-up as early as possible and performs installation checks before SceneView initialization.

Behavior:
- On app startup, call `ArCoreApk.checkAvailabilityAsync()` once to warm the cached availability result. [R1]
- On AR screen entry, read the latest availability result.
- If availability is `UNKNOWN_CHECKING`, show an initializing state and continue polling or waiting for the async callback rather than treating it as unsupported. [R1]
- If ARCore support is unavailable: show blocking error and disable AR functions.
- If ARCore is supported, call `ArCoreApk.requestInstall(activity, userRequestedInstall)` from the lifecycle path that creates the AR session, before creating the session. [R1]
- If `requestInstall()` returns `INSTALL_REQUESTED`, suspend session creation and wait for the activity to resume.
- On the next eligible lifecycle pass, call `requestInstall()` again with `userRequestedInstall = false`.
- If installation succeeds: create the AR session and initialize the scene.
- If installation fails or the user declines: remain blocked on the AR screen.

### 12.1.3 Failure Cases

- AR unsupported: blocking panel
- Camera permission denied: blocking panel with retry
- ARCore availability still checking: non-blocking initializing state
- ARCore install/update rejected: blocking panel with retry action

### 12.1.4 Install Request Guard

The AR lifecycle owner maintains a `userRequestedInstall` boolean:
- initial value = `true`,
- if `requestInstall()` returns `INSTALL_REQUESTED`, set it to `false`,
- on the next eligible lifecycle pass, call `requestInstall()` again,
- never loop installation prompts within a single resume cycle. [R1]

---

## 12.2 Image Selection Flow

### 12.2.1 Trigger

User taps **Select Image**.

### 12.2.2 Implementation

- `rememberLauncherForActivityResult(OpenDocument())` is used with `arrayOf("image/png", "image/jpeg")`. [R4]
- The returned URI is validated by `ImageValidator`.
- Validation reads MIME type and image header.
- PNG files are validated by PNG signature.
- JPEG files are validated by JPEG SOI/EOI markers and successful bounds decode.
- Bounds decode runs first.
- Full decode runs only if the asset passes validation and memory constraints.
- `SelectedImage` is created from validated metadata only and must not retain a decoded `Bitmap` in UI state.
- The controller prepares the sampled bitmap/texture/material/preview-node bundle once and reuses it for placement preview and final placement; the image stream must not be re-decoded on every frame or re-opened at place time.
- After validation, the controller must immediately enter one of the following terminal preparation outcomes:
  - `RenderAssetState.Ready`
  - `RenderAssetState.Error`
- `RenderAssetState.Ready` is allowed only if the controller has registered a controller-private prepared render bundle keyed by `PreparedRenderAsset.assetHandleId`.

### 12.2.3 Validation Rules

The selected asset is accepted only if all checks pass:

- MIME type is `image/png` or `image/jpeg`, or the stream header matches PNG/JPEG signature
- Width and height are greater than zero
- ContentResolver can open the stream
- Decoded size stays within memory budget after sampling
- texture upload succeeds
- material instance creation succeeds
- preview node creation succeeds
- preview node is attached to the scene in hidden state
- a new `assetHandleId` is registered inside `ArSceneController` for this exact selection revision

Dimension-only, metadata-only, or bitmap-only success is explicitly rejected.

### 12.2.4 Memory Budget Rule

- Maximum uploaded bitmap target: **4096 x 4096 px**
- Larger sources are downsampled before texture upload

### 12.2.5 Replacement Rule

If an image is already placed when a new image is selected:

1. remove node from scene,
2. release old anchor,
3. clear old transform,
4. set new image as active selection,
5. prepare the new render asset immediately,
6. enter `WaitingForPlacement`.

### 12.2.6 Failure Rule

If decode, texture upload, material creation, preview-node creation, hidden attach, or asset-handle registration fails:
- `renderAssetState = Error`,
- `previewRenderState = Error`,
- **Place** remains disabled,
- show `E-FILE-003`,
- clear any stale prepared asset handle from the controller,
- do not keep a metadata-only, bitmap-only, or dimension-only successful-selection state.

## 12.3 Scene Initialization and Frame Loop

### 12.3.1 SceneView Integration

`ArSceneContainer` hosts SceneView and exposes an `ArSceneController` interface to the ViewModel layer. SceneView is the only component allowed to directly own renderable, node, preview-node, and anchor objects. [R2]

### 12.3.2 Session Config

```kotlin
session.configure(
    session.config.apply {
        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
        lightEstimationMode = Config.LightEstimationMode.DISABLED
        depthMode = Config.DepthMode.DISABLED
        instantPlacementMode = Config.InstantPlacementMode.DISABLED
        cloudAnchorMode = Config.CloudAnchorMode.DISABLED
        // Keep the default focus mode from the session config.
    }
)
```

`ENVIRONMENTAL_HDR` is intentionally not used because the placed image is rendered unlit and does not benefit from AR light-estimation outputs.

### 12.3.3 Frame Update Loop

On each AR frame:

1. verify camera tracking state,
2. perform a center-screen hit test,
3. choose the nearest valid hit on a supported plane,
4. apply the hit stabilization rule,
5. if `renderAssetState == Ready`, verify that the referenced `assetHandleId` is still registered in the controller and that the preview node for that handle exists and is scene-attached,
6. update the preview node pose, visibility, and facing only for the currently prepared asset handle when a stable valid hit exists,
7. hide the preview node when no stable valid hit exists, tracking is lost, or the prepared asset handle is stale/missing,
8. publish hit state to the ViewModel,
9. publish preview render state and debug render status to the ViewModel,
10. update reticle validity.

If camera tracking is temporarily lost, the controller clears only the current valid hit/reticle state and hides the placement preview. It does **not** delete the placed node or reset placement mode.

### 12.3.4 Valid Hit Rule

A hit is valid only if:
- trackable is a plane,
- pose lies inside the plane polygon,
- plane tracking state is valid,
- camera tracking state is `TRACKING`.

### 12.3.5 Preview Observability Rule

The controller must make the following observable for tests and diagnostics:

- the `preparedAssetHandleId`,
- the `previewAssetHandleId`,
- whether the preview node is attached to the scene,
- whether the preview node is currently visible,
- the last frame on which the preview pose was updated, and
- the `assetHandleId` for which that pose update occurred.

`PreviewRenderState.Visible(assetHandleId)` is valid only when all are true for the same handle:
- `renderAssetState is Ready`,
- `renderAssetState.asset.assetHandleId == debugRenderStatus.previewAssetHandleId`,
- `debugRenderStatus.previewNodeAttached == true`,
- `debugRenderStatus.previewNodeVisible == true`, and
- `debugRenderStatus.previewPoseUpdatedForAssetHandleId == assetHandleId` with `previewPoseUpdateFrameCount > 0`.

These signals are mandatory because preview truth must be testable without depending on visual human inspection alone.

## 12.4 Placement Flow

### 12.4.1 Preconditions

- camera permission granted
- AR ready
- selected image exists
- `renderAssetState == Ready`
- `previewRenderState is Visible`
- `renderAssetState is Ready` and `previewRenderState.assetHandleId == renderAssetState.asset.assetHandleId`
- preview node is attached for that same asset handle
- valid hit exists
- placement mode is `WaitingForPlacement`

### 12.4.2 Procedure

1. Confirm that the prepared render bundle referenced by `PreparedRenderAsset.assetHandleId` exists inside the controller registry and that its preview node is scene-attached.
2. Create ARCore anchor from the current hit.
3. Build rectangular quad geometry from aspect ratio using a bottom-center pivot.
4. Reuse the already prepared texture/material state from the controller-owned render bundle for that asset handle, or clone from the prepared preview node without reopening the image stream.
5. Apply the same upright orientation and the same anti-coplanar forward offset used by the preview.
6. Create the placed node and attach it to the anchor.
7. Apply default scale and rotation.
8. Hide the placement preview node for that same asset handle.
9. Publish placed-node render status within the next frame.
10. Set placement mode to `Placed`.

If the prepared asset handle is missing, stale, or does not match the current selection revision, placement must fail with `E-RENDER-001` rather than silently rebuilding from source metadata.

### 12.4.3 Size Formula

Given:
- `baseHeightMeters = 0.30f`
- `aspectRatio = widthPx / heightPx`

Then:
- `height = 0.30 m`
- `width = 0.30f * aspectRatio m`

### 12.4.4 Anchor Policy

- Exactly one active anchor exists for the image
- Delete, replace, and confirm reposition always release the old anchor
- Anchors are never serialized

### 12.4.5 Placement Success Rule

Placement success is not defined only by the absence of an exception. It additionally requires:
- placed node attached to the scene,
- placed node transform applied successfully,
- no render error reported for that node in the next frame.

If any of these checks fail, the controller must surface `E-FILE-003` or a placement error and revert to `WaitingForPlacement`.

## 12.5 Transform Flow

### 12.5.1 Gesture Routing

Gesture routing is corrected and fixed as follows:

- The AR render surface is owned by SceneView
- SceneView camera/navigation gestures are disabled for this feature because device motion, not touch orbiting, defines the handheld AR viewpoint
- A Compose gesture overlay sits above the AR surface
- The overlay consumes only two-finger transform gestures
- Single-finger gestures are not consumed by the overlay
- Buttons remain above the gesture overlay in z-order

This prevents ambiguous ownership between SceneView and Compose.

### 12.5.2 Scale

Scale applies only when:
- image exists
- placement mode is `Placed`
- recording state is not blocking input

Formula:

```kotlin
newScale = (currentScale * pinchFactor).coerceIn(0.25f, 4.0f)
```

### 12.5.3 Rotation

Rotation applies only when:
- image exists
- placement mode is `Placed`

Formula:

```kotlin
newRotationY = ((currentRotationY + deltaAngle) % 360f + 360f) % 360f
```

### 12.5.4 Rendering Update

- Update node transform through `ArSceneController`
- Persist transform in `PlacementTransform`

---

## 12.6 Reposition Flow

### 12.6.1 Entry

- User taps **Reposition**
- `placementMode = Repositioning`
- Current image remains visible at 50% alpha
- Center reticle becomes active
- Transform gestures are disabled

### 12.6.2 Confirm Procedure

1. Validate current center hit.
2. Create new anchor.
3. Move image node to new anchor.
4. Preserve scale and rotation.
5. Release old anchor.
6. Restore alpha to 100%.
7. Return to `Placed`.

### 12.6.3 Cancel Procedure

- Restore alpha to 100%
- Keep existing anchor and transform
- Return to `Placed`

---

## 12.7 Delete Flow

### 12.7.1 Trigger

User taps **Delete**.

### 12.7.2 Procedure

1. Remove node from scene.
2. Release anchor.
3. Clear `placedImage`.
4. Keep `selectedImage` unchanged and switch to `WaitingForPlacement`.

---

## 12.8 Recording Flow

### 12.8.1 Trigger

User taps **Record**.

### 12.8.2 Preconditions

- AR screen is active and visible
- AR route lifecycle state is `RESUMED`
- recording state is `Idle`
- if microphone permission is missing, request it first
- after microphone permission is granted, request MediaProjection consent

### 12.8.3 Components

- `MediaProjectionManager` for consent intent [R5]
- `MediaProjection` for virtual display [R5]
- `MediaRecorder` for MP4 encoding [R6]
- `RecordingService` foreground service for Android 14+ mediaProjection compliance [R10]
- `RecordedFileValidator` for post-save validation

### 12.8.4 Recording Target Policy

The product requirement is to start recording from the AR route and keep recording lifecycle tied to that route.

Policy:
- the recording flow is initiated only from the AR route,
- the recorded session is tied to the AR route lifecycle,
- API 32/33 full-display capture is acceptable,
- Android 14+ app-window/full-display selection is acceptable,
- if the AR route is no longer foreground, recording is stopped and finalized.

### 12.8.5 Start Procedure

Corrected order:

1. If microphone permission is missing, request it.
2. Launch MediaProjection consent from the AR screen.
3. If consent is granted:
   - start `RecordingService`,
   - call `ServiceCompat.startForeground(..., FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)` from the service before capture begins,
   - create output MediaStore entry,
   - configure `MediaRecorder`,
   - obtain `MediaProjection`,
   - register `MediaProjection.Callback` **before** calling `createVirtualDisplay()`,
   - create exactly one `VirtualDisplay` for this session using the recorder surface,
   - start `MediaRecorder` and transition to active recording state.
4. The consent intent/result pair and the resulting `MediaProjection` instance are single-session objects and must not be cached or reused.
5. If any step fails:
   - release partial resources,
   - stop the foreground service if started,
   - delete incomplete MediaStore entry if created,
   - show error.

### 12.8.6 Recorder Configuration

```kotlin
mediaRecorder.apply {
    setAudioSource(MediaRecorder.AudioSource.MIC)
    setVideoSource(MediaRecorder.VideoSource.SURFACE)
    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
    setVideoFrameRate(30)
    setVideoEncodingBitRate(8_000_000)
    setVideoSize(videoWidth, videoHeight)
    setOutputFile(parcelFileDescriptor.fileDescriptor)
    prepare()
}
```

### 12.8.7 Stop Serialization Rule

The recording controller owns a single-threaded critical section for start, stop, and failure transitions.

Rules:
- only one stop path may run,
- `OnStopRecordClick`, route exit, timeout, `MediaProjection.Callback.onStop()`, and captured-content-invisible auto-stop all delegate to the same idempotent stop function,
- repeated stop requests after the first are ignored,
- no new recording may start until cleanup has fully completed.

### 12.8.8 Stop Procedure

1. Enter `Finalizing`.
2. Stop `MediaRecorder` in a guarded `try/catch` block.
3. Release `MediaRecorder`.
4. Release `VirtualDisplay`.
5. Stop `MediaProjection`.
6. Finalize MediaStore entry by setting `IS_PENDING = 0`.
7. Run `RecordedFileValidator` against the saved `Uri`.
8. Validation succeeds only if all checks pass:
   - file exists and can be opened,
   - file size is greater than zero,
   - MP4 contains a video track,
   - MP4 contains an audio track,
   - duration is greater than a minimum validity threshold.
9. Stop `RecordingService`.
10. Emit success snackbar only after validation success.
11. Return to `Idle`.

### 12.8.9 Stop Failure Rule

`MediaRecorder.stop()` may throw if the recording session is invalid or too short.

Behavior:
- if stop fails, the controller attempts cleanup,
- the incomplete or invalid file is deleted,
- the foreground service is stopped,
- user sees a failure message,
- state returns through `Error` to `Idle`.

### 12.8.10 Automatic Stop Conditions

Recording stops automatically when:
- 10 minutes elapsed
- `MediaProjection.Callback.onStop()` signals capture end [R5]
- fatal recorder error occurs
- user leaves the AR screen
- app reaches `onStop()` while the AR route is no longer foreground
- configuration/window-size change while recording is active

### 12.8.11 Recording Interaction Policy

During active recording:
- `Select Image` is disabled,
- back navigation requests recording stop first,
- transform gestures remain enabled,
- reposition and delete remain allowed,
- if the user navigates away after stop is requested, navigation completes only after cleanup finishes.

## 12.9 Permission Flow

### 12.9.1 Camera Permission

- Requested on first AR screen entry
- Without camera permission, SceneView is not created

### 12.9.2 Microphone Permission

- Requested lazily when **Record** is tapped
- Denial does not block AR placement features
- Denial keeps recording unavailable until retried

### 12.9.3 MediaProjection Consent

- Requested each time a recording session starts [R5]
- The returned consent data is used only for that recording start attempt and is never stored for reuse [R5]
- If cancelled, return to `Idle`

### 12.9.4 Notification Permission

- `POST_NOTIFICATIONS` is declared because the app targets current SDKs and uses a recording foreground service on Android 14+ [R10][R11]
- Notification permission is not treated as a blocker for recording start [R11]
- If denied, recording still proceeds, but the user may not see the foreground-service notice in the notification drawer on Android 13+ [R11]

### 12.9.5 Recording Availability Resolution

- On AR screen entry, evaluate AR readiness and permission state
- Recording availability is determined by `isArReady` and `recordingState`
- Recording flow failures are surfaced as explicit record-start/record-stop errors

## 13. UI Event Contract

```kotlin
sealed interface ArUiEvent {
    data object OnSelectImageClick : ArUiEvent
    data class OnImageSelected(val uri: Uri?) : ArUiEvent
    data class OnRenderAssetPrepared(val asset: PreparedRenderAsset) : ArUiEvent
    data class OnRenderAssetFailed(val reason: String) : ArUiEvent
    data object OnPlaceClick : ArUiEvent
    data object OnRepositionClick : ArUiEvent
    data object OnConfirmRepositionClick : ArUiEvent
    data object OnCancelRepositionClick : ArUiEvent
    data object OnDeleteClick : ArUiEvent
    data class OnScaleGesture(val factor: Float) : ArUiEvent
    data class OnRotateGesture(val deltaDeg: Float) : ArUiEvent
    data object OnRecordClick : ArUiEvent
    data object OnStopRecordClick : ArUiEvent
    data class OnFrameHitUpdated(val hit: HitTestUiModel?) : ArUiEvent
    data class OnPreviewRenderStateChanged(val state: PreviewRenderState) : ArUiEvent
    data class OnDebugRenderStatusChanged(val status: DebugRenderStatus) : ArUiEvent
    data class OnArAvailabilityResolved(
        val availability: ArAvailability,
        val installRequired: Boolean
    ) : ArUiEvent
    data class OnCameraTrackingChanged(val isTracking: Boolean) : ArUiEvent
    data object OnBackClick : ArUiEvent
}
```

## 14. Platform Interface Design

## 14.1 ArSceneController

```kotlin
interface ArSceneController {
    fun bindScene(engine: Engine, childNodes: MutableList<Node>)
    fun prepareSelectedImage(selectedImage: SelectedImage): AppResult<PreparedRenderAsset>
    fun processFrame(
        frame: Frame,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        placementMode: PlacementMode
    ): FrameProcessingResult
    fun placePreparedImage(session: Session, preparedAsset: PreparedRenderAsset): AppResult<PlacedImageState>
    fun enterRepositionMode()
    fun confirmReposition(session: Session): AppResult<PlacedImageState>
    fun cancelReposition()
    fun applyTransform(scale: Float, rotationYDegrees: Float)
    fun currentDebugRenderStatus(): DebugRenderStatus
    fun deleteImage()
    fun clear()
}
```

### 14.1.1 Responsibilities

- own SceneView-bound resources
- own node, preview-node, and anchor references
- own the registry of prepared render bundles keyed by `assetHandleId`
- load and release textures/materials
- reject stale or unknown prepared asset handles
- apply transforms
- bridge frame hit data to ViewModel
- expose preview and placed render truth for tests and diagnostics

Anchor objects are controller-private. No ARCore anchor identifier is exposed through domain state. `PreparedRenderAsset.assetHandleId` is an opaque controller-issued identity token, not a raw SceneView/ARCore object reference.

## 14.2 RecordingController

```kotlin
interface RecordingController {
    var onProjectionStopped: (() -> Unit)?
    fun createConsentIntent(): Intent
    suspend fun startRecording(
        consentResultCode: Int,
        consentData: Intent,
        maximumWindowBounds: Rect
    ): AppResult<Unit>
    suspend fun stopRecording(): AppResult<Unit>
    fun release()
}
```

### 14.2.1 Responsibilities

- create MediaStore output item only after consent success
- configure and release MediaRecorder
- configure and release MediaProjection and VirtualDisplay
- serialize start/stop transitions
- handle timeout and error cleanup
- validate recorded output before reporting success
- stop the foreground service after cleanup
- delete incomplete or invalid files on failure

## 15. Compose Implementation Design

## 15.1 MainActivity

### Responsibilities

- set content
- provide dependency graph
- own activity result launchers for:
  - OpenDocument
  - camera permission
  - record audio permission
  - MediaProjection consent
  - optional notification permission prompt

ARCore installation/update is **not** modeled as an Activity Result launcher. It is initiated through `ArCoreApk.requestInstall()` from the AR lifecycle path. [R1]

## 15.2 ArScreen

### Responsibilities

- render AR surface and overlays
- bind UI events to ViewModel
- render blocking panels and snackbars
- host gesture overlay above SceneView

### Key Composables

- `ArSceneContainer()`
- `ArTopBar()`
- `ArControls()`
- `ReticleOverlay()`
- `RecordingOverlay()`
- `BlockingPanel()`

## 15.3 Gesture Strategy

- Transform gestures are active only in `Placed`
- Reposition mode disables transform gestures
- Single-finger gestures are not consumed by the transform overlay
- Control buttons always win over gesture detection

---

## 16. Rendering Design

## 16.1 Pinned Image Representation

The selected image is rendered as a rectangular mesh.

Requirements:
- PNG alpha preserved when present
- JPEG rendered as opaque
- double-sided render
- unlit shading
- no cast shadow
- no receive shadow

## 16.2 Placement Preview Representation

During `WaitingForPlacement`:
- when a prepared render asset and a stable valid hit both exist, a visible preview of that same asset handle is rendered in AR space
- the preview uses the same aspect ratio, base size, and facing rules as the final placed image
- the preview node is created and scene-attached during preparation, initially hidden
- the preview is hidden when tracking is lost, no stable valid hit exists, or the prepared asset handle is stale/missing

## 16.3 Reticle Representation

- simple 2D center overlay
- white when no valid hit
- green when valid hit
- visible only in `WaitingForPlacement` and `Repositioning`

## 16.4 Preview Ghost

During `Repositioning`:
- current image opacity = 50%
- image remains anchored at old location until confirm
- reticle indicates candidate new placement

---

## 17. Storage Design

## 17.1 In-Memory Session Data

Stored only in memory:
- selected image metadata
- current transform
- current placement mode
- current recording state
- active anchor reference via controller
- prepared render bundle(s) owned privately by `ArSceneController` and keyed by opaque `assetHandleId`

No decoded `Bitmap`, texture, material instance, preview node, or placed node may be stored in `ArUiState` or `ViewModel`.

## 17.2 Output Video Storage

Saved via MediaStore:
- collection: `MediaStore.Video.Media.EXTERNAL_CONTENT_URI`
- relative path: `Movies/ARSpatialPinning`
- display name: `ar_recording_yyyyMMdd_HHmmss.mp4`

### Example Metadata

```kotlin
val values = ContentValues().apply {
    put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ARSpatialPinning")
    put(MediaStore.Video.Media.IS_PENDING, 1)
}
```

On successful finalization, `IS_PENDING` is set to `0`.

Selected image document permissions are not persisted across app restarts by design. [R8]

---

## 18. Lifecycle Design

## 18.1 Activity and Screen Lifecycle

### onCreate
- initialize Compose root

### onResume
- if AR screen visible and camera permission granted, resume SceneView

### onPause
- pause SceneView rendering callbacks as required by the controller
- do not automatically finalize recording solely because `onPause()` occurred

### onStop
- if the AR route is leaving foreground and recording is active, stop recording defensively and finalize if possible
- suspend SceneView-related active resources as required

### onWindowMetricsChanged / configuration-affecting resize
- if a window-size or configuration-affecting change is observed while recording is active, stop recording defensively and finalize safely

### onDestroy
- release SceneView-bound resources
- release recorder/projection resources

### onRouteExit(`ar`)
- clear AR-session-scoped UI state
- release selected image reference
- release anchor/node resources
- stop recording if active and wait for cleanup before navigation completes

## 18.2 Lifecycle Ownership Rule

- ViewModel owns lightweight UI and state only
- `ArSceneController` owns AR resources
- `RecordingController` owns recording resources
- Heavy platform objects must never be stored in ViewModel
- decoded `Bitmap`, texture, material instance, preview node, placed node, and anchor objects are controller-private only

---

## 19. Concurrency Design

- **Main thread**
  - Compose rendering
  - activity result callbacks
  - UI state dispatch
  - SceneView callback handoff

- **IO dispatcher**
  - file open and validation
  - bitmap bounds decode
  - MediaStore operations

- **Default dispatcher**
  - lightweight pure computations only

### Rule

No bitmap decode, texture source read, or MediaStore stream operation may run on the main thread.

Additional rule:
- `RecordingController` start/stop/failure callbacks are serialized with a mutex or single-thread dispatcher so that MediaProjection callback races cannot execute duplicate cleanup.

---

## 20. Error Handling Design

## 20.1 Error Catalog

| ID | Condition | User Message | Recovery |
|---|---|---|---|
| E-AR-001 | AR unsupported | AR is not supported on this device. | Exit AR screen |
| E-AR-002 | Camera permission denied | Camera permission is required to start AR. | Retry permission |
| E-AR-003 | ARCore install/update required but not completed | AR components are not ready. | Retry install/update |
| E-FILE-001 | Invalid image | The selected file is not a valid PNG or JPEG image. | Select another file |
| E-FILE-002 | File open failed | The image could not be opened. | Re-pick file |
| E-FILE-003 | Preview/render creation failed | The selected image could not be rendered in AR space. | Select another file or retry |
| E-PLACEMENT-001 | No valid plane | Move the device to detect a surface. | Continue scanning |
| E-REC-001 | Microphone permission denied | Microphone permission is required for recording. | Retry permission |
| E-REC-002 | MediaProjection denied | Screen capture permission was not granted. | Retry recording |
| E-REC-003 | Recorder start failed | Recording could not be started. | Retry recording |
| E-REC-004 | Recorder stopped unexpectedly | Recording ended unexpectedly. | Retry recording |
| E-REC-005 | Recorder stop failed | Recording could not be finalized. | Retry recording |
| E-REC-007 | Recorded file invalid | The recording was saved but is not a valid AR recording file. | Retry recording |
| E-STORAGE-001 | Output creation failed | Video file could not be created. | Free storage and retry |

## 20.2 Logging Policy

- recoverable failures are logged with error level
- file contents and user image data are never logged
- frame-by-frame hit logging is disabled in production

---

## 21. Manifest and Permission Design

### 21.1 Required Manifest Entries

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<uses-feature android:name="android.hardware.camera" android:required="true" />
<uses-feature android:name="android.hardware.camera.ar" android:required="true" />

<meta-data android:name="com.google.ar.core" android:value="required" />

<application ...>
    <service
        android:name=".platform.media.RecordingService"
        android:exported="false"
        android:foregroundServiceType="mediaProjection" />
</application>
```

### 21.2 Activity and Service Settings

- activity `screenOrientation="portrait"`
- hardware acceleration enabled
- activity remains resizable to comply with MediaProjection behavior on supported large-screen/windowed environments [R5]
- keep-screen-on policy applied while the AR screen is visible
- recording service runs only during an active recording session
- the service must call `startForeground()` with `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` at runtime
- a notification channel for recording is created on app startup

### 21.3 ARCore Requirement

The application requires ARCore-compatible devices and ARCore availability at runtime. [R1]

Because AR is marked as required in the manifest, unsupported-device handling is mainly a defensive runtime path for sideload/external-install scenarios rather than the primary Play-distribution path.

### 21.4 Notification Policy

- on Android 13+, `POST_NOTIFICATIONS` may be requested as a non-blocking enhancement for visibility of the recording foreground-service notification [R11]
- recording itself must not depend on the notification permission being granted [R11]

## 22. Dependency Design

### 22.1 Required Libraries

- Kotlin Coroutines
- AndroidX Activity Compose
- Jetpack Compose BOM
- Navigation Compose [R3]
- Lifecycle ViewModel Compose
- SceneView Android [R2]
- AndroidX Core / Activity APIs required for runtime permissions and activity result flows
- ARCore integration path required by SceneView [R1][R2]

### 22.2 Version Strategy

Exact versions are managed in `libs.versions.toml`.

Constraint:
- library set must remain compatible with API 32 runtime behavior
- implementation must pin mutually compatible stable versions at build time

---

## 23. Non-Functional Design Constraints

### 23.1 Performance

- reticle update target: every AR frame
- transform response target: under 50 ms perceived latency
- AR screen entry target: within 3 seconds to first preview on supported devices

### 23.2 Memory

- only one placed image texture may be resident at a time
- old texture and material references must be released before replacement

### 23.3 Reliability

- all platform resources are released on screen exit
- failed recordings must not leave pending MediaStore items
- replacing an image must not leak anchors or renderables
- window-size/configuration changes during recording trigger defensive stop and cleanup

### 23.4 Security and Privacy

- no network transmission
- image URIs used locally only
- microphone access only during explicit recording actions

---

## 24. Sequence Specifications

## 24.1 Select, Preview, and Place Image

```text
User
 -> ArScreen: Tap Select Image
 -> System Picker: choose PNG/JPEG
 -> ViewModel: validate URI and build SelectedImage metadata
 -> ArSceneController: prepareSelectedImage(selectedImage)
 -> ArSceneController: register prepared render bundle(assetHandleId) and attach hidden preview node
 -> Frame Loop: stable valid hit arrives
 -> ArSceneController: update preview pose/visibility for matching assetHandleId
 -> ViewModel: previewRenderState becomes Visible(assetHandleId)
 -> User: Tap Place
 -> ViewModel: OnPlaceClick
 -> ArSceneController: placePreparedImage(preparedAsset)
 -> UI State: Placed
```

## 24.2 Reposition Image

```text
User -> UI: Tap Reposition
UI -> ViewModel: enter reposition mode
Frame Loop -> ViewModel: update center hit
User -> UI: Tap Confirm Reposition
ViewModel -> ArSceneController: confirmReposition(hit)
ArSceneController -> ARCore: create new anchor, release old anchor
UI State -> Placed
```

## 24.3 Record AR Screen Session

```text
User -> UI: Tap Record
UI -> Permission flow: request RECORD_AUDIO if needed
UI -> System: request MediaProjection consent from the AR screen
System -> Activity: consent OK
Activity -> RecordingController: startRecording(resultCode, data)
RecordingController -> RecordingService: start foreground notification
RecordingController -> MediaStore: create MP4 item
RecordingController -> MediaRecorder: prepare
RecordingController -> MediaProjection: create virtual display
UI State -> Recording
...
User -> UI: Tap Stop
UI -> RecordingController: stopRecording()
RecordingController -> MediaStore: finalize file
RecordingController -> RecordedFileValidator: verify MP4 contains valid audio/video
RecordingController -> RecordingService: stop
UI -> Snackbar: recording saved
```

---

## 25. Test Design Basis

## 25.1 Unit Test Targets

- PNG/JPEG signature validation
- MIME/header fallback logic
- render-asset state transitions
- prevention of metadata-only/dimension-only `RenderAssetState.Ready`
- preview-state transitions
- `PreviewRenderState.Visible(assetHandleId)` derivation from `DebugRenderStatus`
- stale asset-handle invalidation after image replacement
- scale clamp logic
- rotation normalization logic
- placement mode transitions
- recording state transitions
- output filename generation
- recorded-file validation rules

## 25.2 Instrumentation Test Targets

- start-to-AR navigation
- camera permission denial UI
- microphone permission request flow
- PNG/JPEG selection success/failure
- render-asset error surfaces as `E-FILE-003`
- **Place** remains disabled until `renderAssetState == Ready` and `previewRenderState is Visible` for the same `assetHandleId`
- image replacement invalidates the prior asset handle and keeps **Place** disabled until the new preview becomes visible
- preview visibility after stable valid hit
- preview state change when tracking is lost
- button enablement by placement mode and recording state
- reposition mode button swap
- delete behavior

## 25.3 Controller Integration / Debug-State Test Targets

Because visual AR truth cannot be covered adequately by UI-only tests, the controller must expose debug render status and the following must be verified:

- after successful image preparation, a new `assetHandleId` is registered and the preview node for that handle is attached,
- after stable valid hit, `previewAssetHandleId` matches the prepared handle and the preview node becomes visible,
- preview pose update frame counter advances for that same asset handle while hit remains stable,
- after image replacement, the old asset handle is invalidated and cannot keep preview visible or placement enabled,
- after placement, placed node is attached and preview node is hidden for the same asset handle,
- after delete, placed node is detached,
- no metadata-only, bitmap-only, or dimension-only success path exists after render failure.

## 25.4 Manual Device Test Targets

- ARCore availability/install path
- `UNKNOWN_CHECKING` warm-up behavior
- plane detection on supported hardware
- placement stability and anti-jitter behavior
- transient tracking loss without placement reset
- transform gesture usability
- selected image becomes visibly previewed in AR before **Place** is enabled
- replacing the selected image clears the prior preview/placement readiness and the new image must become visible before **Place** re-enables
- placed image remains visible after placement and after reposition
- recording of the AR screen flow with microphone audio from AR route
- recording interruption and stop failure handling
- recorded file contains both video and audio tracks and opens successfully
- one-consent-per-recording-session behavior on Android 14+
- window/configuration change triggers safe stop while recording
- back navigation while recording
- process death behavior without restore

## 26. Acceptance Mapping

| Requirement | Detailed Design Coverage |
|---|---|
| Start menu | Sections 9.1, 12.1 |
| Transition to AR screen | Sections 8, 12.1 |
| ARCore usage | Sections 3, 12.1, 12.3 |
| SceneView usage | Sections 3, 12.3 |
| Jetpack Compose UI | Sections 3, 15 |
| File picker PNG/JPEG selection | Sections 6.11, 12.2 |
| Visible AR preview before placement | Sections 6.3, 6.14, 10, 11, 12.2 to 12.4, 14, 16, 25 |
| Scale / rotate / reposition / delete | Sections 12.5 to 12.7 |
| Single image only | Sections 6.5, 12.2.5 |
| Session-only save | Sections 6.9, 17.1 |
| No world anchor persistence | Sections 6.9, 12.4.4 |
| Recording lifecycle robustness | Sections 5, 6.10, 12.8, 12.9 |
| Audio recording | Sections 6.10, 12.8 |
| Recorded file validity verification | Sections 12.8, 25 |
| Android API 32 support for AR placement | Sections 5, 21, 22 |

## 27. Implementation Notes

### 27.1 Recommended Delivery Order

1. navigation and start screen
2. AR screen bootstrap and permission flows
3. ARCore availability/install handling
4. image selection and validation
5. center-reticle preview and placement
6. transform gestures
7. reposition mode
8. recording foreground service and MediaProjection flow
9. failure handling and cleanup hardening

### 27.2 Known Technical Risks

- AR stability varies by device and environment
- simultaneous AR rendering and screen recording may reduce performance on lower-end hardware
- very large image assets can still produce noticeable upload delay even with downsampling
- stale or incorrectly synthesized prepared-asset identities can cause invisible preview/placement if not rejected strictly

### 27.3 Mitigations

- enforce bitmap sampling limit
- release old textures, preview nodes, prepared asset handles, and anchors immediately on replacement/delete
- keep only one placed image
- derive preview visibility from controller debug truth for the same asset handle only
- stop and finalize recording on screen exit/background
- lock portrait orientation

---

## 28. Final Design Statement

This revised version is materially stronger than version 1.7 because it no longer allows `RenderAssetState.Ready` to be synthesized from metadata or dimensions alone, no longer allows preview visibility to be inferred without matching controller debug truth for the same prepared asset handle, and no longer reports recording success without validating the saved file.

The design is now suitable as a baseline detailed design for implementation **with these explicit constraints**:

- AR placement/manipulation remains supported on API 32 and above.
- Recording starts from the AR route and follows AR-route lifecycle constraints, while platform-default MediaProjection capture behavior is accepted.
- `SelectedImage` remains metadata-only in UI state; heavy render objects remain controller-private.
- Final placement must use a controller-issued `PreparedRenderAsset`, not raw selection metadata.

## References

- **[R1]** Google ARCore Android Quickstart: https://developers.google.com/ar/develop/java/quickstart
- **[R2]** SceneView Android repository: https://github.com/SceneView/sceneview-android
- **[R3]** Navigation with Compose: https://developer.android.com/develop/ui/compose/navigation
- **[R4]** `ActivityResultContracts.OpenDocument` API reference: https://developer.android.com/reference/androidx/activity/result/contract/ActivityResultContracts.OpenDocument
- **[R5]** Android MediaProjection guide: https://developer.android.com/media/grow/media-projection
- **[R6]** Android MediaRecorder guide: https://developer.android.com/media/platform/mediarecorder
- **[R7]** Jetpack Compose overview: https://developer.android.com/develop/ui/compose/documentation
- **[R8]** Access documents and other files from shared storage: https://developer.android.com/training/data-storage/shared/documents-files
- **[R9]** Media projection sizing with `WindowMetrics`: https://developer.android.com/media/grow/media-projection
- **[R10]** Foreground service types required for current target SDKs: https://developer.android.com/about/versions/14/changes/fgs-types-required
- **[R11]** Notification runtime permission / FGS notification behavior: https://developer.android.com/develop/ui/views/notifications/notification-permission
- **[R12]** ARCore `Config.FocusMode` guidance: https://developers.google.com/ar/reference/java/com/google/ar/core/Config.FocusMode
- **[R13]** Android app screen sharing overview: https://developer.android.com/about/versions/14/features/app-screen-sharing


