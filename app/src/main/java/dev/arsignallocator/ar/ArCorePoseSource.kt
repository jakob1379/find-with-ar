package dev.arsignallocator.ar

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Camera
import com.google.ar.core.Config
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import dev.arsignallocator.core.ElapsedRealtimeClock
import dev.arsignallocator.core.MonotonicClock
import dev.arsignallocator.model.ArRuntimeStatus
import dev.arsignallocator.model.ArStatus
import dev.arsignallocator.model.PoseObservation
import dev.arsignallocator.model.PoseTrackingState
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

interface PoseSource {
    val poses: SharedFlow<PoseObservation>
    val status: StateFlow<ArStatus>
}

data class NormalizedScreenPoint(val x: Float, val y: Float)

/** Camera transform for projecting anchor-relative points into normalized screen coordinates. */
class ArProjectionSnapshot internal constructor(
    private val clipFromAnchor: FloatArray,
    val updatedAtElapsedNanos: Long,
) {
    init {
        require(clipFromAnchor.size == 16)
    }

    fun project(xMetres: Double, yMetres: Double, zMetres: Double): NormalizedScreenPoint? {
        val x = xMetres.toFloat()
        val y = yMetres.toFloat()
        val z = zMetres.toFloat()
        val clipX = clipFromAnchor[0] * x + clipFromAnchor[4] * y +
            clipFromAnchor[8] * z + clipFromAnchor[12]
        val clipY = clipFromAnchor[1] * x + clipFromAnchor[5] * y +
            clipFromAnchor[9] * z + clipFromAnchor[13]
        val clipW = clipFromAnchor[3] * x + clipFromAnchor[7] * y +
            clipFromAnchor[11] * z + clipFromAnchor[15]
        if (!clipW.isFinite() || clipW <= 1e-4f) return null
        val normalizedX = (clipX / clipW + 1f) / 2f
        val normalizedY = (1f - clipY / clipW) / 2f
        if (!normalizedX.isFinite() || !normalizedY.isFinite()) return null
        return NormalizedScreenPoint(normalizedX, normalizedY)
    }
}

/**
 * Foreground-only ARCore owner. Camera poses are expressed relative to a floor-level ARCore anchor,
 * keeping a short session stable across world-coordinate edits.
 */
class ArCorePoseSource(
    private val activity: Activity,
    lifecycle: Lifecycle,
    private val clock: MonotonicClock = ElapsedRealtimeClock,
) : PoseSource, DefaultLifecycleObserver {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val backgroundRenderer = CameraBackgroundRenderer()
    private val _poses = MutableSharedFlow<PoseObservation>(extraBufferCapacity = 120)
    private val _status = MutableStateFlow(ArStatus(ArRuntimeStatus.INACTIVE))
    private val _projection = MutableStateFlow<ArProjectionSnapshot?>(null)
    private var session: Session? = null
    private var originAnchor: Anchor? = null
    private var active = false
    private var lifecycleResumed = false
    private var sessionResumed = false
    private var installRequested = false
    private var textureBoundToSession = false
    private var viewportWidth = 1
    private var viewportHeight = 1
    private var lastProjectionPublishedNanos = 0L

    override val poses: SharedFlow<PoseObservation> = _poses.asSharedFlow()
    override val status: StateFlow<ArStatus> = _status.asStateFlow()
    val projection: StateFlow<ArProjectionSnapshot?> = _projection.asStateFlow()

    val surfaceView: GLSurfaceView = GLSurfaceView(activity).apply {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(object : GLSurfaceView.Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                GLES20.glClearColor(0.02f, 0.02f, 0.02f, 1f)
                try {
                    backgroundRenderer.createOnGlThread()
                    textureBoundToSession = false
                } catch (error: RuntimeException) {
                    fail("Could not initialize the AR camera renderer", error, recoverable = true)
                }
            }

            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                viewportWidth = width.coerceAtLeast(1)
                viewportHeight = height.coerceAtLeast(1)
                GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
            }

            override fun onDrawFrame(gl: GL10?) {
                drawArFrame()
            }
        })
        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    init {
        lifecycle.addObserver(this)
    }

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (active) {
            maybeResume()
        } else {
            pauseSession()
            originAnchor?.detach()
            originAnchor = null
            _projection.value = null
        }
    }

    fun retry() {
        installRequested = false
        if (active && lifecycleResumed) maybeResume()
    }

    override fun onResume(owner: LifecycleOwner) {
        lifecycleResumed = true
        if (active) maybeResume()
    }

    override fun onPause(owner: LifecycleOwner) {
        lifecycleResumed = false
        pauseSession()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        pauseSession()
        originAnchor?.detach()
        originAnchor = null
        _projection.value = null
        session?.close()
        session = null
    }

    private fun maybeResume() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!active || !lifecycleResumed || sessionResumed) return
        if (activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            _status.value = ArStatus(
                ArRuntimeStatus.CAMERA_PERMISSION_REQUIRED,
                "Camera permission is needed to measure where the phone moves",
                recoverable = true,
            )
            return
        }

        val availability = ArCoreApk.getInstance().checkAvailability(activity)
        if (availability.isTransient) {
            _status.value = ArStatus(ArRuntimeStatus.CHECKING, "Checking AR support", true)
            mainHandler.postDelayed({ if (active && lifecycleResumed) maybeResume() }, 250L)
            return
        }
        if (!availability.isSupported) {
            _status.value = ArStatus(
                ArRuntimeStatus.UNSUPPORTED,
                "This phone is not supported by Google Play Services for AR",
                recoverable = false,
            )
            return
        }

        try {
            val installStatus = ArCoreApk.getInstance().requestInstall(activity, !installRequested)
            if (installStatus == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                installRequested = true
                _status.value = ArStatus(
                    ArRuntimeStatus.INSTALL_REQUESTED,
                    "Finish installing Google Play Services for AR, then return",
                    recoverable = true,
                )
                return
            }

            val activeSession = session ?: Session(activity).also { newSession ->
                newSession.configure(
                    Config(newSession).apply {
                        updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                    },
                )
                session = newSession
                textureBoundToSession = false
            }
            activeSession.resume()
            sessionResumed = true
            surfaceView.onResume()
            _status.value = ArStatus(ArRuntimeStatus.READY, "Move the phone slowly to start tracking")
        } catch (error: UnavailableUserDeclinedInstallationException) {
            fail("Google Play Services for AR installation was declined", error, true)
        } catch (error: UnavailableArcoreNotInstalledException) {
            fail("Google Play Services for AR is not installed", error, true)
        } catch (error: UnavailableApkTooOldException) {
            fail("Google Play Services for AR must be updated", error, true)
        } catch (error: UnavailableSdkTooOldException) {
            fail("This app's ARCore SDK is too old for the installed service", error, false)
        } catch (error: UnavailableDeviceNotCompatibleException) {
            fail("This phone is not ARCore compatible", error, false)
        } catch (error: CameraNotAvailableException) {
            _status.value = ArStatus(
                ArRuntimeStatus.CAMERA_UNAVAILABLE,
                "The camera is busy. Close other camera apps and retry",
                recoverable = true,
            )
        } catch (error: SecurityException) {
            fail("Camera permission was revoked", error, true)
        }
    }

    private fun pauseSession() {
        _projection.value = null
        if (!sessionResumed) {
            if (!active) _status.value = ArStatus(ArRuntimeStatus.INACTIVE)
            return
        }
        surfaceView.onPause()
        session?.pause()
        sessionResumed = false
        if (!active) _status.value = ArStatus(ArRuntimeStatus.INACTIVE)
    }

    private fun drawArFrame() {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val activeSession = session ?: return
        if (!sessionResumed || backgroundRenderer.textureId < 0) return
        try {
            if (!textureBoundToSession) {
                activeSession.setCameraTextureName(backgroundRenderer.textureId)
                textureBoundToSession = true
            }
            activeSession.setDisplayGeometry(displayRotation(), viewportWidth, viewportHeight)
            val frame = activeSession.update()
            backgroundRenderer.draw(frame)
            val camera = frame.camera
            if (camera.trackingState != TrackingState.TRACKING) {
                _projection.value = null
                _status.value = ArStatus(
                    ArRuntimeStatus.TRACKING_PAUSED,
                    "AR tracking paused: ${camera.trackingFailureReason.name.lowercase()}",
                    recoverable = true,
                )
                return
            }
            val existingAnchor = originAnchor
            val anchor = when {
                existingAnchor == null -> findFloorAnchor(activeSession, camera.pose)
                existingAnchor.trackingState == TrackingState.TRACKING -> existingAnchor
                else -> null
            }
            if (anchor == null) {
                _projection.value = null
                _status.value = ArStatus(
                    ArRuntimeStatus.TRACKING_PAUSED,
                    if (existingAnchor == null) {
                        "Aim the camera at the floor and move it slowly to establish the room origin"
                    } else {
                        "The room origin is not tracked; move slowly or restart the search"
                    },
                    recoverable = true,
                )
                return
            }
            val relative = anchor.pose.inverse().compose(camera.pose)
            val translation = relative.translation
            val rotation = relative.rotationQuaternion
            val receivedAtElapsedNanos = clock.nowNanos()
            publishProjection(camera, anchor, receivedAtElapsedNanos)
            _poses.tryEmit(
                PoseObservation(
                    capturedAtPlatformNanos = frame.timestamp,
                    receivedAtElapsedNanos = receivedAtElapsedNanos,
                    xMetres = translation[0].toDouble(),
                    yMetres = translation[1].toDouble(),
                    zMetres = translation[2].toDouble(),
                    qx = rotation[0].toDouble(),
                    qy = rotation[1].toDouble(),
                    qz = rotation[2].toDouble(),
                    qw = rotation[3].toDouble(),
                    trackingState = PoseTrackingState.TRACKING,
                ),
            )
            if (_status.value.status != ArRuntimeStatus.TRACKING) {
                _status.value = ArStatus(ArRuntimeStatus.TRACKING, "AR tracking is stable")
            }
        } catch (error: CameraNotAvailableException) {
            sessionResumed = false
            _projection.value = null
            _status.value = ArStatus(
                ArRuntimeStatus.CAMERA_UNAVAILABLE,
                "The camera became unavailable. Return to the device list and retry",
                recoverable = true,
            )
        } catch (error: RuntimeException) {
            fail("AR tracking failed", error, recoverable = true)
        }
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        activity.display?.rotation ?: Surface.ROTATION_0
    } else {
        activity.windowManager.defaultDisplay.rotation
    }

    private fun fail(message: String, error: Throwable, recoverable: Boolean) {
        _projection.value = null
        _status.value = ArStatus(
            ArRuntimeStatus.ERROR,
            "$message (${error.javaClass.simpleName})",
            recoverable,
        )
    }

    private fun publishProjection(camera: Camera, anchor: Anchor, observedAtNanos: Long) {
        if (observedAtNanos - lastProjectionPublishedNanos < PROJECTION_INTERVAL_NANOS) return
        val anchorToWorld = FloatArray(16)
        val viewFromWorld = FloatArray(16)
        val clipFromView = FloatArray(16)
        val viewFromAnchor = FloatArray(16)
        val clipFromAnchor = FloatArray(16)
        anchor.pose.toMatrix(anchorToWorld, 0)
        camera.getViewMatrix(viewFromWorld, 0)
        camera.getProjectionMatrix(clipFromView, 0, 0.05f, 100f)
        Matrix.multiplyMM(viewFromAnchor, 0, viewFromWorld, 0, anchorToWorld, 0)
        Matrix.multiplyMM(clipFromAnchor, 0, clipFromView, 0, viewFromAnchor, 0)
        lastProjectionPublishedNanos = observedAtNanos
        _projection.value = ArProjectionSnapshot(clipFromAnchor, observedAtNanos)
    }

    private fun findFloorAnchor(activeSession: Session, cameraPose: Pose): Anchor? {
        val floor = activeSession.getAllTrackables(Plane::class.java)
            .asSequence()
            .filter { plane ->
                plane.trackingState == TrackingState.TRACKING &&
                    plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                    plane.subsumedBy == null &&
                    plane.centerPose.ty() < cameraPose.ty() - 0.4f
            }
            .minByOrNull { plane -> plane.centerPose.ty() }
            ?: return null
        return activeSession.createAnchor(
            Pose.makeTranslation(cameraPose.tx(), floor.centerPose.ty(), cameraPose.tz()),
        ).also {
            originAnchor = it
        }
    }

    private companion object {
        const val PROJECTION_INTERVAL_NANOS = 33_000_000L
    }
}
