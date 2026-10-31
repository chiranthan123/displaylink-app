package com.displaylink.app.ui.screens

import android.view.Surface
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.displaylink.app.ui.components.ConnectionStatus
import com.displaylink.app.ui.components.DisplayCanvas
import com.displaylink.app.ui.components.InstructionSection
import com.displaylink.app.ui.components.LargeStatusIndicator
import com.displaylink.app.ui.components.TopHeaderBar
import com.displaylink.app.ui.theme.CardElevated
import com.displaylink.app.ui.theme.CardElevatedBorder
import com.displaylink.app.ui.theme.DarkSurfaceVariant
import com.displaylink.app.ui.theme.StatusGreen
import com.displaylink.app.ui.theme.TextPrimary
import com.displaylink.app.ui.theme.TextSecondary
import com.displaylink.app.ui.theme.TextTertiary
import com.displaylink.app.video.VideoDecoderStats

/**
 * Vertical layout optimized for Portrait orientation.
 * Crossfades between [LargeStatusIndicator] and [DisplayCanvas] when connected.
 */
@Composable
fun HomePortraitContent(
    status: ConnectionStatus,
    onStatusClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSurfaceCreated: (Surface) -> Unit,
    modifier: Modifier = Modifier,
    onSurfaceDestroyed: ((Surface) -> Unit)? = null,
    decoderStats: VideoDecoderStats? = null
) {
    val scrollState = rememberScrollState()
    val isConnected = (status == ConnectionStatus.CONNECTED)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top Bar
        TopHeaderBar(
            onSettingsClick = onSettingsClick,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Center: Crossfade between LargeStatusIndicator and DisplayCanvas
        Crossfade(
            targetState = isConnected,
            animationSpec = tween(durationMillis = 450),
            label = "PortraitStatusToCanvasCrossfade",
            modifier = Modifier.fillMaxWidth()
        ) { connected ->
            if (connected) {
                // Active video feed rendered on native SurfaceView
                DisplayCanvas(
                    onSurfaceCreated = onSurfaceCreated,
                    onSurfaceDestroyed = onSurfaceDestroyed,
                    resolutionLabel = "${decoderStats?.streamWidth ?: 1920}x${decoderStats?.streamHeight ?: 1080} @ 60Hz",
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                )
            } else {
                // Awaiting connection status indicator
                LargeStatusIndicator(
                    status = status,
                    onStatusClick = onStatusClick,
                    size = 230.dp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // Bottom Section: Active Feed Telemetry or Setup Instructions
        if (isConnected) {
            ConnectedTelemetryCard(
                decoderStats = decoderStats,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            InstructionSection(modifier = Modifier.fillMaxWidth())
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun ConnectedTelemetryCard(
    decoderStats: VideoDecoderStats?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(CardElevated)
            .border(1.dp, CardElevatedBorder, RoundedCornerShape(20.dp))
            .padding(20.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = "Active",
                        tint = StatusGreen,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "AOA H.264 VIDEO PIPELINE",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(DarkSurfaceVariant)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = if (decoderStats?.isRunning == true) "MEDIACODEC ACTIVE" else "DECODER READY",
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusGreen,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TelemetryItem(
                    label = "Resolution",
                    value = "${decoderStats?.streamWidth ?: 1920} × ${decoderStats?.streamHeight ?: 1080}"
                )
                TelemetryItem(
                    label = "Frames Decoded",
                    value = "${decoderStats?.framesDecoded ?: 0}"
                )
                TelemetryItem(
                    label = "Codec",
                    value = "MediaCodec AVC"
                )
            }
        }
    }
}

@Composable
private fun TelemetryItem(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary,
            fontSize = 11.sp
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold
        )
    }
}
