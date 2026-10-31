package com.displaylink.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PowerOff
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.displaylink.app.aoa.AoaDiagnosticUtility
import com.displaylink.app.aoa.AoaManager
import com.displaylink.app.aoa.DiagnosticSeverity
import com.displaylink.app.ui.theme.AccentCyan
import com.displaylink.app.ui.theme.CardElevated
import com.displaylink.app.ui.theme.CardElevatedBorder
import com.displaylink.app.ui.theme.DarkBackground
import com.displaylink.app.ui.theme.DarkSurface
import com.displaylink.app.ui.theme.DarkSurfaceBorder
import com.displaylink.app.ui.theme.DarkSurfaceVariant
import com.displaylink.app.ui.theme.StatusAmber
import com.displaylink.app.ui.theme.StatusGreen
import com.displaylink.app.ui.theme.TextPrimary
import com.displaylink.app.ui.theme.TextSecondary
import com.displaylink.app.ui.theme.TextTertiary
import java.io.IOException

/**
 * Settings modal sheet for configuring display preferences
 * and inspecting real-time AOA USB accessory diagnostics & test harness.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    onDismissRequest: () -> Unit,
    aoaManager: AoaManager? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scrollState = rememberScrollState()

    var lowLatency by remember { mutableStateOf(true) }
    var keepAwake by remember { mutableStateOf(true) }
    var autoRotate by remember { mutableStateOf(true) }
    var showLogs by remember { mutableStateOf(false) }

    val healthReport by (aoaManager?.diagnostics?.healthReport?.collectAsState()
        ?: remember { mutableStateOf(null) })
    val logs by (aoaManager?.diagnostics?.logs?.collectAsState()
        ?: remember { mutableStateOf(emptyList()) })

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = DarkSurface,
        scrimColor = DarkBackground.copy(alpha = 0.8f),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(DarkSurfaceBorder)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "DisplayLink Settings",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Hardware diagnostics and display preferences",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary
                    )
                }

                IconButton(
                    onClick = onDismissRequest,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(DarkSurfaceVariant)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ==========================================
            // AOA DIAGNOSTIC & TEST HARNESS PANEL
            // ==========================================
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(CardElevated)
                    .border(1.dp, AccentCyan.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                                imageVector = Icons.Outlined.BugReport,
                                contentDescription = "Diagnostics",
                                tint = AccentCyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "AOA DIAGNOSTIC HARNESS",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextPrimary,
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
                                text = if (healthReport?.isFileDescriptorActive == true) "FD ACTIVE" else "STANDBY",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (healthReport?.isFileDescriptorActive == true) StatusGreen else StatusAmber,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Diagnostic Metrics Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        DiagnosticMetric(
                            label = "Permission",
                            value = if (healthReport?.hasUsbPermission == true) "GRANTED" else "NONE",
                            isOk = healthReport?.hasUsbPermission == true
                        )
                        DiagnosticMetric(
                            label = "FD Status",
                            value = if (healthReport?.isFileDescriptorActive == true) "VALID" else "CLOSED",
                            isOk = healthReport?.isFileDescriptorActive == true
                        )
                        DiagnosticMetric(
                            label = "Events",
                            value = "${logs.size} LOGS",
                            isOk = true
                        )
                    }

                    // Simulation Triggers for Developers
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                aoaManager?.checkConnectedAccessories()
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentCyan),
                            border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(DarkSurfaceBorder))
                        ) {
                            Text("Rescan", fontSize = 11.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                aoaManager?.closeAccessoryConnection(reason = "Simulated manual disconnect")
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusAmber),
                            border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(DarkSurfaceBorder))
                        ) {
                            Text("Detach", fontSize = 11.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                aoaManager?.onFileDescriptorClosed(IOException("Simulated native broken pipe"))
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5252)),
                            border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(DarkSurfaceBorder))
                        ) {
                            Text("FD Crash", fontSize = 11.sp)
                        }
                    }

                    // Expandable Logs Toggle
                    Text(
                        text = if (showLogs) "Hide Diagnostic Event Stream ▲" else "View Diagnostic Event Stream (${logs.size}) ▼",
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clickable { showLogs = !showLogs }
                            .padding(vertical = 4.dp)
                    )

                    AnimatedVisibility(visible = showLogs) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(DarkBackground)
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (logs.isEmpty()) {
                                Text(
                                    text = "No diagnostic events logged yet.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextTertiary,
                                    fontSize = 11.sp
                                )
                            } else {
                                logs.takeLast(6).reversed().forEach { log ->
                                    Row(
                                        verticalAlignment = Alignment.Top,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = when (log.severity) {
                                                DiagnosticSeverity.SUCCESS -> "●"
                                                DiagnosticSeverity.INFO -> "●"
                                                DiagnosticSeverity.WARNING -> "▲"
                                                DiagnosticSeverity.ERROR -> "■"
                                            },
                                            color = when (log.severity) {
                                                DiagnosticSeverity.SUCCESS -> StatusGreen
                                                DiagnosticSeverity.INFO -> AccentCyan
                                                DiagnosticSeverity.WARNING -> StatusAmber
                                                DiagnosticSeverity.ERROR -> Color(0xFFFF5252)
                                            },
                                            fontSize = 9.sp
                                        )
                                        Text(
                                            text = log.message,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextSecondary,
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 2
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ==========================================
            // PREFERENCES SWITCHES
            // ==========================================
            SettingToggleItem(
                title = "Low Latency Video",
                description = "Prioritize real-time responsiveness for mirroring",
                icon = Icons.Outlined.Bolt,
                checked = lowLatency,
                onCheckedChange = { lowLatency = it }
            )

            Spacer(modifier = Modifier.height(14.dp))

            SettingToggleItem(
                title = "Keep Screen Awake",
                description = "Prevent phone sleep while connected to external monitor",
                icon = Icons.Outlined.Visibility,
                checked = keepAwake,
                onCheckedChange = { keepAwake = it }
            )

            Spacer(modifier = Modifier.height(14.dp))

            SettingToggleItem(
                title = "Auto-Rotate Output",
                description = "Synchronize monitor orientation with phone sensor",
                icon = Icons.Outlined.ScreenRotation,
                checked = autoRotate,
                onCheckedChange = { autoRotate = it }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // App Version Info Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(CardElevated)
                    .border(1.dp, CardElevatedBorder, RoundedCornerShape(14.dp))
                    .padding(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = "Info",
                        tint = AccentCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "DisplayLink UI v1.0.0",
                            style = MaterialTheme.typography.labelLarge,
                            color = TextPrimary
                        )
                        Text(
                            text = "AOA USB Protocol & Diagnostic Engine",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextTertiary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticMetric(label: String, value: String, isOk: Boolean) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary,
            fontSize = 10.sp
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = if (isOk) StatusGreen else TextSecondary,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun SettingToggleItem(
    title: String,
    description: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardElevated)
            .border(1.dp, CardElevatedBorder, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(DarkSurfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (checked) AccentCyan else TextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                fontSize = 14.sp
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                fontSize = 12.sp
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = DarkBackground,
                checkedTrackColor = AccentCyan,
                uncheckedThumbColor = TextTertiary,
                uncheckedTrackColor = DarkSurfaceVariant,
                uncheckedBorderColor = DarkSurfaceBorder
            )
        )
    }
}
