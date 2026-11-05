package com.ivarna.fluxlinux.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.sharp.OpenInNew
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ivarna.fluxlinux.core.utils.TermuxX11Preferences
import com.ivarna.fluxlinux.ui.components.aka.PlatedButton
import com.ivarna.fluxlinux.ui.components.aka.SectionLabel
import com.ivarna.fluxlinux.ui.theme.CodeStyle
import com.ivarna.fluxlinux.ui.theme.FluxSpacing

/**
 * Embedded X11 display prefs — writes Lorie keys and broadcasts live updates.
 */
@Composable
fun X11SettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current

    var displayScale by remember {
        mutableFloatStateOf(TermuxX11Preferences.getDisplayScale(context).toFloat())
    }
    var fullscreen by remember { mutableStateOf(TermuxX11Preferences.getFullscreen(context)) }
    var hideCutout by remember { mutableStateOf(TermuxX11Preferences.getHideCutout(context)) }
    var keepScreenOn by remember { mutableStateOf(TermuxX11Preferences.getKeepScreenOn(context)) }
    var capturePointer by remember { mutableStateOf(TermuxX11Preferences.getCapturePointer(context)) }
    var showAdditionalKbd by remember {
        mutableStateOf(TermuxX11Preferences.getShowAdditionalKeyboard(context))
    }
    var showIME by remember { mutableStateOf(TermuxX11Preferences.getShowIME(context)) }
    var preferScancodes by remember {
        mutableStateOf(TermuxX11Preferences.getPreferScancodes(context))
    }
    var scancodeWorkaround by remember {
        mutableStateOf(TermuxX11Preferences.getScancodeWorkaround(context))
    }

    // termux-x11's own preferences screen (LoriePreferences) edits the same keys while this
    // screen is in the back stack: re-read them on every resume, as SettingsScreen does.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                displayScale = TermuxX11Preferences.getDisplayScale(context).toFloat()
                fullscreen = TermuxX11Preferences.getFullscreen(context)
                hideCutout = TermuxX11Preferences.getHideCutout(context)
                keepScreenOn = TermuxX11Preferences.getKeepScreenOn(context)
                capturePointer = TermuxX11Preferences.getCapturePointer(context)
                showAdditionalKbd = TermuxX11Preferences.getShowAdditionalKeyboard(context)
                showIME = TermuxX11Preferences.getShowIME(context)
                preferScancodes = TermuxX11Preferences.getPreferScancodes(context)
                scancodeWorkaround = TermuxX11Preferences.getScancodeWorkaround(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsPageScaffold(title = "X11 display", onBack = onBack) {
        SettingsPageNote("Changes apply to the embedded X11 display immediately (no external Termux:X11).")

        SectionLabel("Display")
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = FluxSpacing.lg, vertical = FluxSpacing.sm)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Display scale",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "${displayScale.toInt()}%",
                    style = CodeStyle,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Slider(
                value = displayScale,
                onValueChange = { displayScale = it },
                onValueChangeFinished = {
                    TermuxX11Preferences.setDisplayScale(context, displayScale.toInt())
                },
                valueRange = 30f..300f,
                steps = 26,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    activeTickColor = MaterialTheme.colorScheme.onPrimary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    inactiveTickColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                modifier = Modifier.semantics { contentDescription = "Display scale" }
            )
        }
        PrefSwitch(
            title = "Fullscreen",
            subtitle = "Immersive mode on the device display",
            checked = fullscreen,
            onChange = {
                fullscreen = it
                TermuxX11Preferences.setFullscreen(context, it)
            }
        )
        PrefSwitch(
            title = "Hide display cutout",
            subtitle = "Use notch / cutout area",
            checked = hideCutout,
            onChange = {
                hideCutout = it
                TermuxX11Preferences.setHideCutout(context, it)
            }
        )
        PrefSwitch(
            title = "Keep screen on",
            subtitle = "Prevent timeout while X11 is open",
            checked = keepScreenOn,
            onChange = {
                keepScreenOn = it
                TermuxX11Preferences.setKeepScreenOn(context, it)
            }
        )

        SectionLabel("Input")
        PrefSwitch(
            title = "Capture external pointer",
            subtitle = "Intercept hardware pointer events",
            checked = capturePointer,
            onChange = {
                capturePointer = it
                TermuxX11Preferences.setCapturePointer(context, it)
            }
        )
        PrefSwitch(
            title = "Show additional keyboard",
            subtitle = "Extra keys bar inside the X11 surface",
            checked = showAdditionalKbd,
            onChange = {
                showAdditionalKbd = it
                TermuxX11Preferences.setShowAdditionalKeyboard(context, it)
            }
        )
        PrefSwitch(
            title = "Show IME with external keyboard",
            subtitle = "Software keyboard while a hardware keyboard is connected",
            checked = showIME,
            onChange = {
                showIME = it
                TermuxX11Preferences.setShowIME(context, it)
            }
        )
        PrefSwitch(
            title = "Prefer scancodes",
            subtitle = "Let the X server handle keyboard layout",
            checked = preferScancodes,
            onChange = {
                preferScancodes = it
                TermuxX11Preferences.setPreferScancodes(context, it)
            }
        )
        PrefSwitch(
            title = "Hardware keyboard scancodes workaround",
            subtitle = "Fix scancodes on some devices",
            checked = scancodeWorkaround,
            onChange = {
                scancodeWorkaround = it
                TermuxX11Preferences.setScancodeWorkaround(context, it)
            }
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FluxSpacing.lg, vertical = FluxSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(FluxSpacing.md)
        ) {
            PlatedButton(
                text = "Re-apply to running display",
                onClick = {
                    TermuxX11Preferences.applyToTermux(context)
                    Toast.makeText(context, "Applied to embedded X11", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth()
            )
            SettingsPageSecondaryButton(
                text = "Open full X11 preferences",
                icon = Icons.AutoMirrored.Sharp.OpenInNew,
                onClick = {
                    TermuxX11Preferences.applyToTermux(context)
                    TermuxX11Preferences.openTermuxX11Preferences(context)
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun PrefSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    SettingsPageSwitchRow(
        title = title,
        supporting = subtitle,
        checked = checked,
        onCheckedChange = onChange
    )
}
