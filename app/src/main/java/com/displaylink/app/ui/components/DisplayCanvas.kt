package com.displaylink.app.ui.components

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.displaylink.app.ui.theme.CardElevated
import com.displaylink.app.ui.theme.CardElevatedBorder
import com.displaylink.app.ui.theme.DarkBackground
import com.displaylink.app.ui.theme.DarkSurfaceVariant
import com.displaylink.app.ui.theme.StatusGreen
import com.displaylink.app.ui.theme.TextPrimary
import com.displaylink.app.ui.theme.TextSecondary
import com.displaylink.app.ui.theme.TextTertiary

/**
 * DisplayCanvas wraps a native Android [SurfaceView] via Compose's [AndroidView].
 *
 * Exposes the underlying hardware [Surface] via [onSurfaceCreated] so it can
 * be bound directly to a MediaCodec or DisplayLink video decoder pipeline.
 */
@Composable
fun DisplayCanvas(
    onSurfaceCreated: (Surface) -> Unit,
    modifier: Modifier = Modifier,
    onSurfaceDestroyed: ((Surface) -> Unit)? = null,
    onSurfaceChanged: ((Surface, Int, Int) -> Unit)? = null,
    resolutionLabel: String = "1080p @ 60Hz",
    aspectRatio: Float = 16f / 9f
) {
    var isSurfaceReady by remember { mutableStateOf(false) }
    val isPreview = LocalInspectionMode.current

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(DarkBackground)
            .border(1.dp, CardElevatedBorder, RoundedCornerShape(18.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (!isPreview) {
            // Native SurfaceView wrapped inside AndroidView
            AndroidView(
                factory = { context ->
                    SurfaceView(context).apply {
                        keepScreenOn = true
                        holder.setFormat(android.graphics.PixelFormat.OPAQUE)
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                isSurfaceReady = true
                                onSurfaceCreated(holder.surface)
                            }

                            override fun surfaceChanged(
                                holder: SurfaceHolder,
                                format: Int,
                                width: Int,
                                height: Int
                            ) {
                                onSurfaceChanged?.invoke(holder.surface, width, height)
                            }

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                isSurfaceReady = false
                                onSurfaceDestroyed?.invoke(holder.surface)
                            }
                        })
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(18.dp))
            )
        } else {
            // Android Studio Design Canvas preview placeholder
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(CardElevated),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Tv,
                        contentDescription = "Display Preview",
                        tint = StatusGreen,
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = "Hardware SurfaceView Active",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = resolutionLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary
                    )
                }
            }
        }

        // Live HUD Overlay (Top Status Pill)
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(DarkBackground.copy(alpha = 0.75f))
                .border(1.dp, CardElevatedBorder.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(StatusGreen)
                )
                Text(
                    text = "LIVE FEED",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextPrimary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
            }
        }

        // Live HUD Overlay (Bottom Resolution Tag)
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(DarkBackground.copy(alpha = 0.75f))
                .border(1.dp, CardElevatedBorder.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = resolutionLabel,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

