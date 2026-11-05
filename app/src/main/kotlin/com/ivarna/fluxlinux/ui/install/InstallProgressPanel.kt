package com.ivarna.fluxlinux.ui.install

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.sharp.Code
import androidx.compose.material.icons.sharp.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.ivarna.fluxlinux.R
import com.ivarna.fluxlinux.ui.components.aka.MarkState
import com.ivarna.fluxlinux.ui.components.aka.AkaButton
import com.ivarna.fluxlinux.ui.components.aka.AkaButtonKind
import com.ivarna.fluxlinux.ui.components.aka.StatusMark
import com.ivarna.fluxlinux.ui.components.aka.markColor
import com.ivarna.fluxlinux.ui.components.aka.akaSelection
import com.ivarna.fluxlinux.ui.theme.CodeStyle
import com.ivarna.fluxlinux.ui.theme.FluxSpacing

/**
 * Shared install progress UI for onboarding + Distros [InstallConfigScreen].
 *
 * Honest state: the header's mark says working (dashed, turning), failed (struck) or done
 * (filled) by form first and ink second; a failure keeps its ink until the user acts. The
 * live log sits in the deepest well, set in the code face.
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
    val mark = when {
        failed -> MarkState.FAILED
        isDone -> MarkState.RUNNING
        else -> MarkState.WORKING
    }
    val ink = markColor(mark)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = FluxSpacing.xl, vertical = FluxSpacing.lg)
    ) {
        // Header: the state in form (mark), ink and words.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusMark(state = mark, size = FluxSpacing.lg)
            Spacer(Modifier.width(FluxSpacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        when {
                            failed -> R.string.aka_install_title_failed
                            isDone -> R.string.aka_install_title_done
                            else -> R.string.aka_install_title_running
                        }
                    ),
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.onBackground
                )
                Text(
                    text = stringResource(
                        when {
                            failed -> R.string.aka_install_sub_failed
                            isDone -> R.string.aka_install_sub_done
                            else -> R.string.aka_install_sub_running
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(FluxSpacing.xl))

        // Progress, on the stock: phase, percent in the state's ink, the bar, the detail.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                text = phaseLabel.ifBlank { stringResource(R.string.aka_install_working) },
                style = MaterialTheme.typography.titleSmall,
                color = colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(FluxSpacing.sm))
            Text(
                text = "$percent%",
                style = MaterialTheme.typography.titleLarge,
                color = ink
            )
        }

        Spacer(Modifier.height(FluxSpacing.sm))

        LinearProgressIndicator(
            progress = { percent / 100f },
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.extraSmall),
            color = ink,
            trackColor = colors.surfaceContainerHighest
        )

        if (detail.isNotBlank()) {
            Spacer(Modifier.height(FluxSpacing.sm))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (failed && errorMessage != null) {
            Spacer(Modifier.height(FluxSpacing.md))
            Row(verticalAlignment = Alignment.Top) {
                StatusMark(
                    state = MarkState.FAILED,
                    size = FluxSpacing.md,
                    modifier = Modifier.padding(top = FluxSpacing.xs)
                )
                Spacer(Modifier.width(FluxSpacing.sm))
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.error
                )
            }
        }

        Spacer(Modifier.height(FluxSpacing.md))

        // Log toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Sharp.Code,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(FluxSpacing.lg + FluxSpacing.xxs)
            )
            Spacer(Modifier.width(FluxSpacing.sm))
            Text(
                text = stringResource(R.string.aka_install_output),
                style = MaterialTheme.typography.labelLarge,
                color = colors.onBackground,
                modifier = Modifier.weight(1f)
            )
            AkaButton(
                text = stringResource(if (showLog) R.string.aka_install_hide_log else R.string.aka_install_show_log),
                onClick = onToggleLog,
                kind = AkaButtonKind.SECONDARY,
                contentPadding = PaddingValues(horizontal = FluxSpacing.lg, vertical = FluxSpacing.md),
            )
        }

        // The live log, in the deepest well.
        AnimatedVisibility(
            visible = showLog,
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(MaterialTheme.shapes.medium)
                    .background(colors.surfaceContainerLowest)
                    .padding(FluxSpacing.md)
                    .verticalScroll(logScroll)
            ) {
                // The code face at label size: long apt lines wrap less on a phone.
                val logStyle = CodeStyle.copy(
                    fontSize = MaterialTheme.typography.labelSmall.fontSize,
                    lineHeight = MaterialTheme.typography.labelSmall.lineHeight
                )
                Text(
                    text = logText.ifBlank { stringResource(R.string.aka_install_log_waiting) },
                    style = logStyle,
                    color = if (logText.isBlank()) colors.onSurfaceVariant else colors.onSurface
                )
            }
        }

        if (!showLog) {
            Spacer(Modifier.weight(1f))
        }

        Spacer(Modifier.height(FluxSpacing.lg))

        // Actions
        when {
            failed -> {
                AkaButton(
                    text = stringResource(R.string.aka_install_retry),
                    icon = Icons.Sharp.Refresh,
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth()
                )

                if (onBack != null) {
                    Spacer(Modifier.height(FluxSpacing.xs))
                    AkaButton(
                        text = stringResource(R.string.aka_install_back_to_options),
                        onClick = onBack,
                        kind = AkaButtonKind.SECONDARY,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            isDone && onDone != null -> {
                AkaButton(
                    text = stringResource(R.string.onboarding_continue),
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Shared dark/light theme option for install wizards, in its tile. Selected: konjō with the ai
 * edge and its horizon risen.
 */
@Composable
fun InstallThemePickRow(
    @Suppress("UNUSED_PARAMETER") id: String,
    title: String,
    desc: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val shape = MaterialTheme.shapes.medium
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val titleColor = colors.onSurface
    val mutedColor = if (selected) colors.onSurface.copy(alpha = 0.82f) else colors.onSurfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .akaSelection(selected, pressed, shape)
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.RadioButton,
                onClick = onSelect
            )
            .heightIn(min = FluxSpacing.touch)
            .padding(horizontal = FluxSpacing.sm, vertical = FluxSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(
                selectedColor = colors.onSurface,
                unselectedColor = colors.onSurfaceVariant
            ),
            modifier = Modifier.padding(FluxSpacing.sm)
        )
        Spacer(Modifier.width(FluxSpacing.sm))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FluxSpacing.xxs)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, color = titleColor)
            Text(text = desc, style = MaterialTheme.typography.bodySmall, color = mutedColor)
        }
    }
}
