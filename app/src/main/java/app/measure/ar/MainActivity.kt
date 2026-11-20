package app.measure.ar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.measure.ar.ar.MeasureRenderer
import app.measure.ar.ui.LengthUnit
import app.measure.ar.ui.MeasureScreen
import app.measure.ar.ui.MeasureTheme
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException

class MainActivity : ComponentActivity() {

    private sealed interface Status {
        data object Starting : Status
        data class NeedsPermission(val permanentlyDenied: Boolean) : Status
        data class Error(val message: String) : Status
        data object Running : Status
    }

    private var status by mutableStateOf<Status>(Status.Starting)
    private var unit by mutableStateOf(LengthUnit.Centimeters)

    private var session: Session? = null
    private var installRequested = false
    private lateinit var renderer: MeasureRenderer
    private lateinit var surfaceView: GLSurfaceView

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                status = Status.NeedsPermission(
                    permanentlyDenied = !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA),
                )
            }
            // On grant, onResume runs next and starts the session.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        unit = LengthUnit.load(this)

        renderer = MeasureRenderer(this)
        surfaceView = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setWillNotDraw(false)
        }

        if (!hasCameraPermission()) permissionLauncher.launch(Manifest.permission.CAMERA)

        setContent {
            MeasureTheme {
                when (val s = status) {
                    Status.Running, Status.Starting -> {
                        val frame by renderer.state.collectAsStateWithLifecycle()
                        MeasureScreen(
                            state = frame,
                            unit = unit,
                            onUnitChange = {
                                unit = it
                                LengthUnit.save(this, it)
                            },
                            onAddPoint = renderer::addPoint,
                            onBeginMove = renderer::beginMove,
                            onEndMove = renderer::endMove,
                            onUndo = renderer::undo,
                            onWeld = renderer::weld,
                            onDeleteSelected = renderer::deleteSelected,
                            onClearAll = renderer::clearAll,
                            camera = {
                                AndroidView(factory = { surfaceView }, modifier = Modifier.fillMaxSize())
                            },
                        )
                    }
                    is Status.NeedsPermission -> MessageScreen(
                        title = "Camera access needed",
                        body = "Measure uses the camera to see the world around you and place points on surfaces.",
                        action = if (s.permanentlyDenied) "Open settings" else "Allow camera",
                        onAction = {
                            if (s.permanentlyDenied) {
                                startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
                                )
                            } else {
                                permissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        },
                    )
                    is Status.Error -> MessageScreen(
                        title = "AR isn't available",
                        body = s.message,
                        action = "Try again",
                        onAction = {
                            status = Status.Starting
                            startSession()
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasCameraPermission()) startSession()
    }

    override fun onPause() {
        super.onPause()
        surfaceView.onPause()
        session?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        renderer.session = null
        session?.close()
        session = null
    }

    private fun startSession() {
        if (session == null) {
            try {
                if (ArCoreApk.getInstance().requestInstall(this, !installRequested) ==
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED
                ) {
                    installRequested = true
                    return // We'll be resumed once Google Play Services for AR finishes installing.
                }
                session = Session(this).also(::configure)
            } catch (e: UnavailableException) {
                status = Status.Error(e.toMessage())
                return
            }
        }
        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            status = Status.Error("The camera is in use by another app. Close it and try again.")
            session?.close()
            session = null
            return
        }
        renderer.session = session
        surfaceView.onResume()
        status = Status.Running
    }

    private fun configure(session: Session) {
        val config = Config(session).apply {
            focusMode = Config.FocusMode.AUTO
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            // Depth lets us measure on surfaces that aren't detected planes (edges, objects, walls).
            depthMode = if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                Config.DepthMode.AUTOMATIC
            } else {
                Config.DepthMode.DISABLED
            }
        }
        session.configure(config)
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun UnavailableException.toMessage() = when (this) {
        is UnavailableArcoreNotInstalledException,
        is UnavailableUserDeclinedInstallationException -> "Please install Google Play Services for AR."
        is UnavailableApkTooOldException -> "Please update Google Play Services for AR."
        is UnavailableSdkTooOldException -> "Please update this app."
        is UnavailableDeviceNotCompatibleException -> "This device doesn't support AR."
        else -> "Couldn't start AR: ${message ?: javaClass.simpleName}"
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MessageScreen(title: String, body: String, action: String, onAction: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Surface(
                shape = MaterialShapes.Cookie9Sided.toShape(),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(120.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painterResource(R.drawable.ic_camera),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(48.dp),
                    )
                }
            }
            Text(title, style = MaterialTheme.typography.headlineMediumEmphasized, textAlign = TextAlign.Center)
            Text(
                body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onAction) { Text(action) }
        }
    }
}
