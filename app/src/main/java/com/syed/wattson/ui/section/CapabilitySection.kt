package com.syed.wattson.ui.section

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.syed.wattson.data.Capabilities
import com.syed.wattson.data.DataTier
import com.syed.wattson.ui.component.SectionCard
import com.syed.wattson.ui.model.BatteryUiModel

/**
 * Explains what this device is letting Wattson read, and how to unlock the rest.
 *
 * Only shown when something is actually missing. Root and adb-granted devices see
 * nothing: everything already works, so a card saying so is just noise.
 */
@Composable
fun CapabilitySection(
    model: BatteryUiModel,
    modifier: Modifier = Modifier,
) {
    if (model.tier != DataTier.BASIC) return

    val context = LocalContext.current
    val command = Capabilities.grantCommand(context.packageName.removeSuffix(".debug"))

    SectionCard(title = "Limited mode", modifier = modifier) {
        Text(
            text = "Charge level, current, voltage, temperature and cycle count are " +
                "shown above — those need no permissions. Capacity health, screen time, " +
                "the drain split and the level history all come from Android's battery " +
                "accounting, which an ordinary app cannot read." +
                "\n\nConnect this phone to a computer once and run:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = command,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(12.dp),
        )
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = { context.copyToClipboard(command) }) {
            Text("Copy command")
        }
        Text(
            text = "Rooted devices skip this entirely — Wattson uses su automatically.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun Context.copyToClipboard(text: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("adb command", text))
}
