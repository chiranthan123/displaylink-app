package com.displaylink.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.displaylink.app.ui.theme.AccentCyan
import com.displaylink.app.ui.theme.CardElevated
import com.displaylink.app.ui.theme.CardElevatedBorder
import com.displaylink.app.ui.theme.DarkSurfaceVariant
import com.displaylink.app.ui.theme.TextPrimary
import com.displaylink.app.ui.theme.TextSecondary
import com.displaylink.app.ui.theme.TextTertiary

data class SetupStep(
    val stepNumber: String,
    val title: String,
    val description: String,
    val icon: ImageVector
)

private val defaultSteps = listOf(
    SetupStep(
        stepNumber = "1",
        title = "Plug in USB-C Cable",
        description = "Connect your device directly to the DisplayLink adapter or dock.",
        icon = Icons.Outlined.Usb
    ),
    SetupStep(
        stepNumber = "2",
        title = "Attach External Monitor",
        description = "Connect an HDMI or DisplayPort cable between the dock and monitor.",
        icon = Icons.Outlined.Tv
    ),
    SetupStep(
        stepNumber = "3",
        title = "Verify Power Source",
        description = "Confirm your dock and monitor are plugged into power.",
        icon = Icons.Outlined.Power
    )
)

/**
 * Minimalist, dark-themed instruction panel explaining how to connect
 * hardware to initiate DisplayLink video output.
 */
@Composable
fun InstructionSection(
    modifier: Modifier = Modifier,
    steps: List<SetupStep> = defaultSteps
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        CardElevated,
                        CardElevated.copy(alpha = 0.85f)
                    )
                )
            )
            .border(
                width = 1.dp,
                color = CardElevatedBorder,
                shape = RoundedCornerShape(20.dp)
            )
            .padding(20.dp)
    ) {
        Column {
            // Header with badge
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
                        imageVector = Icons.Outlined.Cable,
                        contentDescription = "Setup Guide",
                        tint = AccentCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "HOW TO CONNECT",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(DarkSurfaceVariant)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "PLUG & PLAY",
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Step items
            steps.forEachIndexed { index, step ->
                StepRow(step = step)
                if (index < steps.lastIndex) {
                    // Subtle connector line between steps
                    Box(
                        modifier = Modifier
                            .padding(start = 14.dp, top = 2.dp, bottom = 2.dp)
                            .width(1.dp)
                            .height(12.dp)
                            .background(CardElevatedBorder)
                    )
                }
            }
        }
    }
}

@Composable
private fun StepRow(
    step: SetupStep,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        // Step number badge
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(DarkSurfaceVariant)
                .border(1.dp, CardElevatedBorder, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = step.stepNumber,
                style = MaterialTheme.typography.labelSmall,
                color = AccentCyan,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        // Text details
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = step.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = step.description,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Step mini icon
        Icon(
            imageVector = step.icon,
            contentDescription = null,
            tint = TextTertiary,
            modifier = Modifier
                .size(18.dp)
                .padding(top = 2.dp)
        )
    }
}

