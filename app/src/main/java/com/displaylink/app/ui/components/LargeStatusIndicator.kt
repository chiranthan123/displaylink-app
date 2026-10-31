package com.displaylink.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.displaylink.app.ui.theme.AccentCyan
import com.displaylink.app.ui.theme.AccentCyanGlow
import com.displaylink.app.ui.theme.DarkSurface
import com.displaylink.app.ui.theme.DarkSurfaceBorder
import com.displaylink.app.ui.theme.DarkSurfaceVariant
import com.displaylink.app.ui.theme.StatusAmber
import com.displaylink.app.ui.theme.StatusGreen
import com.displaylink.app.ui.theme.TextPrimary
import com.displaylink.app.ui.theme.TextSecondary
import com.displaylink.app.ui.theme.TextTertiary

enum class ConnectionStatus(
    val title: String,
    val subtitle: String,
    val primaryColor: Color,
    val beaconColor: Color
) {
    AWAITING(
        title = "Awaiting USB Connection",
        subtitle = "Connect a DisplayLink dock, adapter, or monitor to begin screen mirroring.",
        primaryColor = AccentCyan,
        beaconColor = AccentCyan
    ),
    CONNECTING(
        title = "Establishing Link...",
        subtitle = "Handshaking with external display controller. Please keep cable plugged in.",
        primaryColor = StatusAmber,
        beaconColor = StatusAmber
    ),
    CONNECTED(
        title = "Display Connected",
        subtitle = "Screen mirrored successfully at 1080p @ 60Hz to external display.",
        primaryColor = StatusGreen,
        beaconColor = StatusGreen
    );

    fun next(): ConnectionStatus = when (this) {
        AWAITING -> CONNECTING
        CONNECTING -> CONNECTED
        CONNECTED -> AWAITING
    }
}

/**
 * Large glowing status indicator featuring animated pulsing concentric rings,
 * hardware beacon, and high-contrast status text.
 */
@Composable
fun LargeStatusIndicator(
    status: ConnectionStatus = ConnectionStatus.AWAITING,
    onStatusClick: (() -> Unit)? = null,
    size: Dp = 220.dp,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulseAnimation")

    // Outer pulse 1: Scale and Alpha
    val pulseScale1 by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseScale1"
    )

    val pulseAlpha1 by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseAlpha1"
    )

    // Outer pulse 2 (offset)
    val pulseScale2 by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, delayMillis = 800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseScale2"
    )

    val pulseAlpha2 by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, delayMillis = 800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseAlpha2"
    )

    // Inner breathing glow
    val breathingAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathingGlow"
    )

    val animatedColor by animateColorAsState(
        targetValue = status.primaryColor,
        animationSpec = tween(durationMillis = 500),
        label = "statusColorAnimation"
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Glowing Circular Radar & Icon
        Box(
            modifier = Modifier
                .size(size)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onStatusClick?.invoke() },
            contentAlignment = Alignment.Center
        ) {
            // Concentric Canvas Pulse Waves
            Canvas(modifier = Modifier.size(size)) {
                val center = Offset(this.size.width / 2f, this.size.height / 2f)
                val baseRadius = this.size.minDimension * 0.34f

                // Expanding Wave 1
                drawCircle(
                    color = animatedColor.copy(alpha = pulseAlpha1),
                    radius = baseRadius * pulseScale1,
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )

                // Expanding Wave 2
                drawCircle(
                    color = animatedColor.copy(alpha = pulseAlpha2),
                    radius = baseRadius * pulseScale2,
                    center = center,
                    style = Stroke(width = 1.5.dp.toPx())
                )

                // Soft Background Glow Aura
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            animatedColor.copy(alpha = breathingAlpha * 0.35f),
                            animatedColor.copy(alpha = 0.0f)
                        ),
                        center = center,
                        radius = baseRadius * 1.25f
                    ),
                    radius = baseRadius * 1.25f,
                    center = center
                )
            }

            // Outer Orbit Border Ring
            Box(
                modifier = Modifier
                    .size(size * 0.72f)
                    .clip(CircleShape)
                    .border(
                        width = 1.5.dp,
                        brush = Brush.sweepGradient(
                            colors = listOf(
                                animatedColor.copy(alpha = 0.8f),
                                DarkSurfaceBorder,
                                animatedColor.copy(alpha = 0.3f),
                                DarkSurfaceBorder,
                                animatedColor.copy(alpha = 0.8f)
                            )
                        ),
                        shape = CircleShape
                    )
                    .background(DarkSurfaceVariant.copy(alpha = 0.75f)),
                contentAlignment = Alignment.Center
            ) {
                // Inner Dark Core Disc
                Box(
                    modifier = Modifier
                        .size(size * 0.58f)
                        .clip(CircleShape)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    DarkSurface,
                                    DarkSurfaceVariant
                                )
                            )
                        )
                        .border(1.dp, animatedColor.copy(alpha = 0.4f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    // Center Hardware Icon
                    Icon(
                        imageVector = when (status) {
                            ConnectionStatus.AWAITING -> Icons.Default.Usb
                            ConnectionStatus.CONNECTING -> Icons.Default.Cable
                            ConnectionStatus.CONNECTED -> Icons.Default.Tv
                        },
                        contentDescription = "Status Hardware Icon",
                        tint = animatedColor,
                        modifier = Modifier.size(size * 0.26f)
                    )
                }
            }

            // Top Status Beacon Pill
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(DarkSurfaceVariant)
                    .border(1.dp, animatedColor.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(animatedColor)
                    )
                    Text(
                        text = when (status) {
                            ConnectionStatus.AWAITING -> "SEARCHING"
                            ConnectionStatus.CONNECTING -> "SYNCING"
                            ConnectionStatus.CONNECTED -> "ACTIVE"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = TextPrimary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Large Primary Status Label
        Text(
            text = status.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Descriptive Subtitle
        Text(
            text = status.subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

