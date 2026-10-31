package com.displaylink.app

import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.displaylink.app.aoa.AoaManager
import com.displaylink.app.ui.components.ConnectionStatus
import com.displaylink.app.ui.screens.HomeScreen
import com.displaylink.app.ui.theme.DisplayLinkTheme
import com.displaylink.app.video.AoaVideoPipeline
import com.displaylink.app.video.DesktopResolution
import com.displaylink.app.video.VideoDecoderWorker
import kotlinx.coroutines.launch

/**
 * Main Activity for DisplayLink.
 * Initializes [AoaManager] for Android Open Accessory (AOA) lifecycle,
 * routes docking intents, and wires the active AOA input stream to the
 * [AoaVideoPipeline] targeting the native [Surface] from DisplayCanvas.
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var aoaManager: AoaManager
    private lateinit var videoPipeline: AoaVideoPipeline
    private val videoDecoderWorker = VideoDecoderWorker()

    // Active hardware surface provided by DisplayCanvas's SurfaceView
    private var videoSurface: Surface? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        aoaManager = AoaManager(this)
        aoaManager.start()
        videoPipeline = AoaVideoPipeline(aoaManager = aoaManager)

        // Handle cold-start accessory attachment intent
        handleAccessoryIntent(intent)

        // Reactively manage the video decoder worker thread and screen awake state based on AOA connection state
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                aoaManager.connectionStatus.collect { status ->
                    if (status == ConnectionStatus.CONNECTED) {
                        updateScreenOnFlag(true)
                        tryStartVideoDecoder()
                    } else {
                        updateScreenOnFlag(false)
                        videoPipeline.teardown(reason = "AOA status: $status")
                        videoDecoderWorker.stop()
                    }
                }
            }
        }

        setContent {
            DisplayLinkTheme {
                HomeScreen(
                    connectionStatusFlow = aoaManager.connectionStatus,
                    decoderStatsFlow = videoDecoderWorker.stats,
                    aoaManager = aoaManager,
                    onSurfaceCreated = { surface ->
                        handleVideoSurfaceAvailable(surface)
                    },
                    onSurfaceDestroyed = { surface ->
                        handleVideoSurfaceDestroyed(surface)
                    },
                    onManualStatusToggle = {
                        // User manual check/retry trigger
                        aoaManager.checkConnectedAccessories()
                    }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "MainActivity onStart: app entering foreground")
        // If returning to foreground while already connected, re-apply keep-screen-on and try resume decoder
        if (::aoaManager.isInitialized && aoaManager.connectionStatus.value == ConnectionStatus.CONNECTED) {
            updateScreenOnFlag(true)
            tryStartVideoDecoder()
        }
    }

    override fun onStop() {
        super.onStop()
        Log.i(TAG, "MainActivity onStop: App entered background. Safely cancelling coroutines and freeing MediaCodec resources.")
        // Clear screen awake flag to allow device sleep while backgrounded
        updateScreenOnFlag(false)

        // Cancel reader coroutine, reset NAL parser, and free MediaCodec hardware resources
        if (::videoPipeline.isInitialized) {
            videoPipeline.teardown(reason = "App moved to background")
        }
        videoDecoderWorker.stop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Handle warm-start accessory attachment intent while activity is already active
        handleAccessoryIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "MainActivity onDestroy: Disposing all resources")
        updateScreenOnFlag(false)
        if (::videoPipeline.isInitialized) {
            videoPipeline.destroy()
        }
        videoDecoderWorker.stop()
        if (::aoaManager.isInitialized) {
            aoaManager.stop()
        }
        videoSurface = null
    }

    /**
     * Dynamically updates the FLAG_KEEP_SCREEN_ON window flag.
     * When serving as an active DisplayLink monitor (CONNECTED), keeps the device awake.
     * When detached or backgrounded, clears the flag to allow the screen to sleep normally and conserve battery.
     */
    private fun updateScreenOnFlag(keepAwake: Boolean) {
        if (keepAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            Log.d(TAG, "Applied FLAG_KEEP_SCREEN_ON (display monitor active)")
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            Log.d(TAG, "Cleared FLAG_KEEP_SCREEN_ON (screen allowed to sleep normally)")
        }
    }

    /**
     * Receives the underlying hardware Surface from DisplayCanvas.
     */
    private fun handleVideoSurfaceAvailable(surface: Surface) {
        this.videoSurface = surface
        Log.i(TAG, "Hardware Video Surface ready from DisplayCanvas: $surface")
        tryStartVideoDecoder()
    }

    /**
     * Called when the DisplayCanvas Surface is destroyed (e.g. backgrounded or destroyed).
     */
    private fun handleVideoSurfaceDestroyed(surface: Surface) {
        Log.i(TAG, "Hardware Video Surface destroyed: $surface")
        videoPipeline.teardown(reason = "Surface destroyed")
        videoDecoderWorker.stop()
        if (this.videoSurface == surface) {
            this.videoSurface = null
        }
    }

    /**
     * Attempts to start the low-latency MediaCodec H.264 decoder pipeline if both the hardware
     * Surface and active AOA connection are available.
     */
    private fun tryStartVideoDecoder() {
        val surface = videoSurface
        if (surface == null || !surface.isValid) {
            Log.d(TAG, "Cannot start decoder: Surface not yet available or invalid.")
            return
        }

        if (aoaManager.connectionStatus.value == ConnectionStatus.CONNECTED) {
            Log.i(TAG, "Starting AoaVideoPipeline with hardware surface...")
            val stream = aoaManager.inputStream ?: videoDecoderWorker.createSyntheticH264Stream()
            videoPipeline.start(
                surface = surface,
                resolution = DesktopResolution.FHD_1080P,
                sourceStreamOverride = stream
            )
            videoDecoderWorker.start(surface = surface, inputStream = stream)
        }
    }

    private fun handleAccessoryIntent(intent: Intent?) {
        if (intent == null) return

        if (intent.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED) {
            val accessory: UsbAccessory? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
            }
            aoaManager.handleAccessoryAttached(accessory)
        }
    }
}
