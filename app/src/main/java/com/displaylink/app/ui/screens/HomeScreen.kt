package com.displaylink.app.ui.screens

import android.content.res.Configuration
import android.view.Surface
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.displaylink.app.aoa.AoaManager
import com.displaylink.app.ui.components.ConnectionStatus
import com.displaylink.app.ui.components.SettingsSheet
import com.displaylink.app.ui.theme.DarkBackground
import com.displaylink.app.ui.theme.DisplayLinkTheme
import com.displaylink.app.video.VideoDecoderStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Main Home Screen for DisplayLink.
 * Binds directly to the AOA StateFlow for automated connection status transitions,
 * crossfades the LargeStatusIndicator to DisplayCanvas when CONNECTED,
 * and routes the hardware video [Surface] to the MediaCodec H.264 decoder worker.
 */
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    connectionStatusFlow: StateFlow<ConnectionStatus>? = null,
    decoderStatsFlow: StateFlow<VideoDecoderStats>? = null,
    aoaManager: AoaManager? = null,
    onSurfaceCreated: ((Surface) -> Unit)? = null,
    onSurfaceDestroyed: ((Surface) -> Unit)? = null,
    onManualStatusToggle: (() -> Unit)? = null
) {
    // If a live AOA StateFlow is provided, collect its value; otherwise fallback to local state for previews
    val liveStatus = connectionStatusFlow?.collectAsState()?.value
    val decoderStats by (decoderStatsFlow?.collectAsState() ?: remember { mutableStateOf(null) })
    var previewStatus by rememberSaveable { mutableStateOf(ConnectionStatus.AWAITING) }

    val activeStatus = liveStatus ?: previewStatus
    var showSettings by rememberSaveable { mutableStateOf(false) }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val handleStatusClick = {
        if (onManualStatusToggle != null) {
            onManualStatusToggle()
        } else if (connectionStatusFlow == null) {
            // In preview/standalone mode, clicking cycles through states
            previewStatus = previewStatus.next()
        }
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground),
        color = DarkBackground
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            // Adaptive orientation detection (checks both configuration and aspect ratio)
            val useLandscapeLayout = isLandscape || (maxWidth > maxHeight && maxWidth > 600.dp)

            Crossfade(
                targetState = useLandscapeLayout,
                animationSpec = tween(durationMillis = 350),
                label = "OrientationLayoutCrossfade"
            ) { landscape ->
                if (landscape) {
                    HomeLandscapeContent(
                        status = activeStatus,
                        onStatusClick = handleStatusClick,
                        onSettingsClick = {
                            showSettings = true
                        },
                        onSurfaceCreated = { surface ->
                            onSurfaceCreated?.invoke(surface)
                        },
                        onSurfaceDestroyed = { surface ->
                            onSurfaceDestroyed?.invoke(surface)
                        },
                        decoderStats = decoderStats
                    )
                } else {
                    HomePortraitContent(
                        status = activeStatus,
                        onStatusClick = handleStatusClick,
                        onSettingsClick = {
                            showSettings = true
                        },
                        onSurfaceCreated = { surface ->
                            onSurfaceCreated?.invoke(surface)
                        },
                        onSurfaceDestroyed = { surface ->
                            onSurfaceDestroyed?.invoke(surface)
                        },
                        decoderStats = decoderStats
                    )
                }
            }

            // Settings Bottom Sheet
            if (showSettings) {
                SettingsSheet(
                    onDismissRequest = { showSettings = false },
                    aoaManager = aoaManager
                )
            }
        }
    }
}

// ==========================================
// PREVIEWS FOR ANDROID STUDIO DESIGN CANVAS
// ==========================================

@Preview(name = "1. Portrait Phone (Awaiting)", showBackground = true, device = Devices.PIXEL_7)
@Composable
fun PreviewHomePortrait() {
    DisplayLinkTheme {
        HomeScreen()
    }
}

@Preview(
    name = "2. Landscape Phone (Awaiting)",
    showBackground = true,
    widthDp = 844,
    heightDp = 390
)
@Composable
fun PreviewHomeLandscape() {
    DisplayLinkTheme {
        HomeScreen()
    }
}

@Preview(
    name = "3. Connected Video Feed - Portrait",
    showBackground = true,
    device = Devices.PIXEL_7
)
@Composable
fun PreviewHomeConnected() {
    val connectedFlow = remember { MutableStateFlow(ConnectionStatus.CONNECTED) }
    val mockStats = remember {
        MutableStateFlow(
            VideoDecoderStats(
                isRunning = true,
                framesDecoded = 1840,
                bytesIngested = 4500000,
                estimatedFps = 60f,
                streamWidth = 1920,
                streamHeight = 1080
            )
        )
    }
    DisplayLinkTheme {
        HomeScreen(
            connectionStatusFlow = connectedFlow,
            decoderStatsFlow = mockStats
        )
    }
}

@Preview(
    name = "4. Connected Video Feed - Landscape",
    showBackground = true,
    widthDp = 844,
    heightDp = 390
)
@Composable
fun PreviewHomeConnectedLandscape() {
    val connectedFlow = remember { MutableStateFlow(ConnectionStatus.CONNECTED) }
    val mockStats = remember {
        MutableStateFlow(
            VideoDecoderStats(
                isRunning = true,
                framesDecoded = 1840,
                bytesIngested = 4500000,
                estimatedFps = 60f,
                streamWidth = 1920,
                streamHeight = 1080
            )
        )
    }
    DisplayLinkTheme {
        HomeScreen(
            connectionStatusFlow = connectedFlow,
            decoderStatsFlow = mockStats
        )
    }
}
