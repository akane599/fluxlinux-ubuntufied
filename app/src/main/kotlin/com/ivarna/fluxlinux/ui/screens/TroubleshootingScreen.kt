package com.ivarna.fluxlinux.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.sharp.KeyboardArrowDown
import androidx.compose.material.icons.sharp.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.ivarna.fluxlinux.R
import com.ivarna.fluxlinux.ui.theme.AkaMotion
import com.ivarna.fluxlinux.ui.theme.FluxSpacing

data class TroubleshootingItem(
    val title: String,
    val content: String
)

@Composable
fun TroubleshootingScreen(
    onBack: () -> Unit
) {
    val issues = listOf(
        TroubleshootingItem(stringResource(R.string.trouble_desktop_title), stringResource(R.string.trouble_desktop_body)),
        TroubleshootingItem(stringResource(R.string.trouble_black_title), stringResource(R.string.trouble_black_body)),
        TroubleshootingItem(stringResource(R.string.trouble_sound_title), stringResource(R.string.trouble_sound_body)),
        TroubleshootingItem(stringResource(R.string.trouble_links_title), stringResource(R.string.trouble_links_body)),
        TroubleshootingItem(stringResource(R.string.trouble_phantom_title), stringResource(R.string.trouble_phantom_body)),
        TroubleshootingItem(stringResource(R.string.trouble_reinstall_title), stringResource(R.string.trouble_reinstall_body))
    )

    SettingsPageScaffold(title = "Troubleshooting", onBack = onBack) {
        issues.forEachIndexed { i, issue ->
            if (i > 0) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(horizontal = FluxSpacing.lg)
                )
            }
            ExpandableIssueCard(issue)
        }
    }
}

/** One question: the title row toggles its answer open (a row on the stock; the name is historical). */
@Composable
fun ExpandableIssueCard(issue: TroubleshootingItem) {
    var expanded by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(role = Role.Button) { expanded = !expanded }
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .padding(horizontal = FluxSpacing.lg, vertical = FluxSpacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = issue.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(FluxSpacing.md))
            Icon(
                imageVector = if (expanded) Icons.Sharp.KeyboardArrowUp else Icons.Sharp.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(AkaMotion.standard()) + fadeIn(AkaMotion.standard(AkaMotion.QUICK)),
            exit = shrinkVertically(AkaMotion.standard(AkaMotion.QUICK)) + fadeOut(AkaMotion.standard(AkaMotion.QUICK))
        ) {
            Text(
                text = issue.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = FluxSpacing.lg,
                    end = FluxSpacing.lg,
                    bottom = FluxSpacing.lg
                )
            )
        }
    }
}
