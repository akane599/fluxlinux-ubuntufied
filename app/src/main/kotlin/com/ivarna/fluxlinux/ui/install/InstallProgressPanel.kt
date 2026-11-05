package com.ivarna.fluxlinux.ui.install

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivarna.fluxlinux.ui.theme.FluxSuccess
import com.ivarna.fluxlinux.ui.theme.TerminalBackground

/**
 * Shared install progress UI for onboarding + Distros [InstallConfigScreen].
 */
@Composable
fun InstallProgressPanel(
    percent: Int,
    phaseLabel: String,
    detail: String,
    logText: String,
    failed: Boolean,
    errorMessage: String?,
    showLog: Boolean,
    onToggleLog: () -> Unit,
    onRetry: () -> Unit,
    onBack: (() -> Unit)? = null,
    onDone: (() -> Unit)? = null,
    isDone: Boolean = false,
    modifier: Modifier = Modifier
) {
    val logScroll = rememberScrollState()
    LaunchedEffect(logText) {
        if (logText.isNotEmpty()) {
            logScroll.animateScrollTo(logScroll.maxValue)
        }
    }

    val colors = MaterialTheme.colorScheme
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        // Header Section
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        failed -> "Setup failed"
                        isDone -> "Install complete"
                        else -> "Setting up Ubuntu"
                    },
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.onBackground
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when {
                        failed -> "An error occurred during execution"
                        isDone -> "Everything is installed"
                        else -> "Downloading and configuring packages"
                    },
                    fontSize = 13.sp,
                    color = colors.onBackground.copy(alpha = 0.65f)
                )
            }

            // Status Indicator Badge
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            failed -> colors.error.copy(alpha = 0.15f)
                            isDone -> FluxSuccess.copy(alpha = 0.15f)
                            else -> colors.secondaryContainer
                        }
                    )
                    .border(
                        1.dp,
                        when {
                            failed -> colors.error.copy(alpha = 0.4f)
                            isDone -> FluxSuccess.copy(alpha = 0.4f)
                            else -> colors.outlineVariant
                        },
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when {
                        failed -> Icons.Filled.ErrorOutline
                        isDone -> Icons.Filled.CheckCircle
                        else -> Icons.Filled.Refresh
                    },
                    contentDescription = null,
                    tint = when {
                        failed -> colors.error
                        isDone -> FluxSuccess
                        else -> colors.primary
                    },
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // Progress Glass Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surfaceContainer)
                .padding(16.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        text = if (phaseLabel.isNotBlank()) phaseLabel else "Working…",
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "$percent%",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            failed -> colors.error
                            isDone -> FluxSuccess
                            else -> colors.primary
                        }
                    )
                }

                Spacer(Modifier.height(10.dp))

                // Progress Bar with Gradient Look
                LinearProgressIndicator(
                    progress = { percent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = when {
                        failed -> colors.error
                        isDone -> FluxSuccess
                        else -> colors.primary
                    },
                    trackColor = colors.surfaceContainerHighest
                )

                if (detail.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = detail,
                        fontSize = 12.sp,
                        color = colors.onSurface.copy(alpha = 0.7f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (failed && errorMessage != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = errorMessage,
                        color = colors.error,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Log Viewer Toggle Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Code,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Output",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.onBackground.copy(alpha = 0.85f)
                )
            }

            TextButton(
                onClick = onToggleLog,
            ) {
                Text(
                    if (showLog) "Hide log" else "Show log",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
            }
        }

        // Live Log Terminal Window
        AnimatedVisibility(
            visible = showLog,
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(14.dp))
                    .background(TerminalBackground)
                    .padding(12.dp)
                    .verticalScroll(logScroll)
            ) {
                Text(
                    text = logText.ifBlank { "Initializing installation stream…" },
                    color = if (logText.isBlank()) colors.onSurfaceVariant else colors.onSurface,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 16.sp
                )
            }
        }

        if (!showLog) {
            Spacer(Modifier.weight(1f))
        }

        Spacer(Modifier.height(16.dp))

        // Action Buttons
        when {
            failed -> {
                Button(
                    onClick = onRetry,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Retry", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }

                if (onBack != null) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = onBack,
                        modifier = Modifier.fillMaxWidth(),
                            ) {
                        Text("Back to options", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    }
                }
            }

            isDone && onDone != null -> {
                Button(
                    onClick = onDone,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Continue", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }
    }
}

/** Shared dark/light theme selectable card for install wizards. */
@Composable
fun InstallThemePickRow(
    id: String,
    title: String,
    desc: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    val colors = MaterialTheme.colorScheme
    val borderColor = if (selected) colors.primary else colors.outlineVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (selected) colors.secondaryContainer
                else colors.surfaceContainer
            )
            .border(if (selected) 1.5.dp else 1.dp, borderColor, shape)
            .clickable(onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(
                selectedColor = colors.primary,
                unselectedColor = colors.onSurface.copy(alpha = 0.4f)
            )
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
                fontSize = 15.sp
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = desc,
                fontSize = 12.sp,
                color = colors.onSurface.copy(alpha = 0.65f)
            )
        }
    }
}
