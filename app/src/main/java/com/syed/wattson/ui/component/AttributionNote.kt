package com.syed.wattson.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.syed.wattson.ui.model.AttributionUi
import com.syed.wattson.ui.util.formatMah

/**
 * Explains how much of real drain a breakdown actually covers.
 *
 * Android attributes only the power it can model. Where the kernel withholds per-UID CPU
 * time, the modelled buckets can fall to a small fraction of the charge the cell really
 * gave up — and a list of app figures that omits that gap reads as a complete account
 * when it is nothing of the sort. Shown only when the shortfall is big enough to mislead.
 */
@Composable
fun AttributionNote(
    attribution: AttributionUi?,
    modifier: Modifier = Modifier,
) {
    if (attribution == null || !attribution.isPartial) return
    val coverage = attribution.coverage ?: return
    val missing = attribution.unattributedMah ?: return

    Spacer(Modifier.height(14.dp))
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = "Covers ${(coverage * 100).toInt()}% of actual drain",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = buildString {
                append("Android could not attribute ")
                append(formatMah(missing))
                append(" mAh")
                if (!attribution.cpuTracked) {
                    append(" — this device does not report per-app CPU time, so processor ")
                    append("use is missing from every row below")
                }
                append(". Treat these as relative ranking, not absolute totals.")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
