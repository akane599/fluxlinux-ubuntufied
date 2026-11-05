package com.ivarna.fluxlinux.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ivarna.fluxlinux.ui.theme.AkaTheme

/**
 * The app's switch: Material 3, printed in the inks. On = red track with an ink-black
 * thumb. [onShellField] is for switches sitting on the Federal Blue shell (the drawer),
 * where the off track takes the field's own tint so it doesn't read as a hole.
 */
@Composable
fun FluxSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onShellField: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val inks = AkaTheme.inks
    val colors = SwitchDefaults.colors(
        checkedThumbColor = scheme.onPrimary,
        checkedTrackColor = scheme.primary,
        checkedBorderColor = scheme.primary,
        checkedIconColor = scheme.primary,
        uncheckedThumbColor = if (onShellField) inks.onShellFieldMuted else scheme.outline,
        uncheckedTrackColor = if (onShellField) inks.shellFieldHigh else scheme.surfaceContainerHighest,
        uncheckedBorderColor = if (onShellField) inks.onShellFieldMuted else scheme.outline,
    )
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = colors,
        enabled = enabled,
        modifier = modifier,
    )
}
