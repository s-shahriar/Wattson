package com.syed.wattson.ui.section

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.syed.wattson.ui.component.MeterRow
import com.syed.wattson.ui.component.SectionCard
import com.syed.wattson.ui.component.SubItemRow
import com.syed.wattson.ui.model.BatteryUiModel
import com.syed.wattson.ui.util.formatDurationLong
import com.syed.wattson.ui.util.formatMah
import com.syed.wattson.ui.util.formatSharePercent

/** Categories shown before the list is expanded. */
private const val COLLAPSED_CATEGORIES = 5

/** Contributing apps listed under each category. */
private const val CONTRIBUTORS_SHOWN = 3

/**
 * Ranked category totals with the apps behind each.
 *
 * Collapsed to the top few by default — the full list runs to roughly forty rows, which
 * buried the sections below it.
 */
@Composable
fun PowerBreakdownSection(
    model: BatteryUiModel,
    modifier: Modifier = Modifier,
) {
    if (model.categories.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }
    val visible = if (expanded) model.categories else model.categories.take(COLLAPSED_CATEGORIES)
    val hidden = model.categories.size - visible.size

    SectionCard(
        title = "By category",
        subtitle = "${formatMah(model.totalCategoryMah)} mAh across ${model.categories.size} categories",
        modifier = modifier.animateContentSize(),
    ) {
        visible.forEachIndexed { index, category ->
            MeterRow(
                label = category.label,
                value = "${formatMah(category.mah)} mAh",
                trailing = formatSharePercent(category.share),
                fraction = category.relativeToMax,
                // One hue, fading down the ranking: keeps this list from competing with
                // the history chart, where colour carries meaning.
                color = MaterialTheme.colorScheme.primary.copy(
                    alpha = (1f - index * RANK_FADE).coerceAtLeast(MIN_RANK_ALPHA),
                ),
                detail = category.durationMs?.let(::formatDurationLong),
            )
            category.contributors.take(CONTRIBUTORS_SHOWN).forEach { contributor ->
                SubItemRow(
                    name = contributor.label,
                    value = "${formatMah(contributor.mah)} mAh",
                )
            }
            Spacer(Modifier.height(6.dp))
        }

        if (hidden > 0 || expanded) {
            Text(
                text = if (expanded) "Show less" else "Show $hidden more",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 10.dp),
            )
        }
    }
}

/** Alpha lost per rank step. */
private const val RANK_FADE = 0.13f

/** Floor so the lowest-ranked bar stays visible. */
private const val MIN_RANK_ALPHA = 0.3f
