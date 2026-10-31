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
import androidx.compose.foundation.layout.fillMaxHeight
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
 * Two-column split layout optimized for Landscape orientation.
 * Crossfades between [LargeStatusIndicator] and [DisplayCanvas] on the left half.
 */
@Composable
fun HomeLandscapeContent(
    status: ConnectionStatus,
    onStatusClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSurfaceCreated: (Surface) -> Unit,
    modifier: Modifier = Modifier,
    onSurfaceDestroyed: ((Surface) -> Unit)? = null,
    decoderStats: VideoDecoderStats? = null
) {
    val rightScrollState = rememberScrollState()
    val isConnected = (status == ConnectionStatus.CONNECTED)

    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left Column: Crossfade between Status Indicator and Live Video DisplayCanvas
        Box(
            modifier = Modifier
                .weight(1.3f)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            Crossfade(
                targetState = isConnected,
                animationSpec = tween(durationMillis = 450),
                label = "LandscapeStatusToCanvasCrossfade"
            ) { connected ->
                if (connected) {
                    DisplayCanvas(
                        onSurfaceCreated = onSurfaceCreated,
                        onSurfaceDestroyed = onSurfaceDestroyed,
                        resolutionLabel = "${decoderStats?.streamWidth ?: 1920}x${decoderStats?.streamHeight ?: 1080} @ 60Hz",
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                    )
                } else {
                    LargeStatusIndicator(
                        status = status,
                        onStatusClick = onStatusClick,
                        size = 180.dp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // Right Column: Header & Dynamic Panel (Telemetry when connected, Setup Guide when awaiting)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rightScrollState),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            TopHeaderBar(
                onSettingsClick = onSettingsClick,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(10.dp))

            if (isConnected) {
                LandscapeTelemetryCard(
                    decoderStats = decoderStats,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                InstructionSection(modifier = Modifier.fillMaxWidth())
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun LandscapeTelemetryCard(
    decoderStats: VideoDecoderStats?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(CardElevated)
            .border(1.dp, CardElevatedBorder, RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = "Active",
                        tint = StatusGreen,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "FEED ACTIVE",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(DarkSurfaceVariant)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (decoderStats?.isRunning == true) "AVC RUNNING" else "1080p 60Hz",
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusGreen,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Text(
                text = "SurfaceView bound to MediaCodec H.264 video pipeline. Ingesting AOA byte chunks (${decoderStats?.bytesIngested ?: 0} bytes read, ${decoderStats?.framesDecoded ?: 0} frames decoded).",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                lineHeight = 16.sp
            )
        }
    }
}
