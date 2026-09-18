package com.yomitanmobile.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The settings list: rows separated by hairlines, not boxes.
 *
 * Every setting used to sit in its own grey `surfaceVariant` card. Twenty of
 * them stacked with 12dp between made the screen read as a pile of unrelated
 * tiles: the grey said "this is a container" over and over while nothing said
 * which settings belong together. A rule between rows says the same thing with
 * one pixel, and the section header is then free to be the only visual break.
 *
 * Cards are kept for what is NOT a setting — a notice, a warning, a progress
 * report. Those are meant to stand out from the list, which they can only do
 * while the list itself is flat.
 */

/** Horizontal breathing room shared by every row, so the rules line up. */
val SettingsRowPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)

/** Where a row's text starts — the rules are inset to it, not full-bleed. */
private val TextStart = 16.dp + 32.dp + 12.dp

/**
 * The hairline between two rows.
 *
 * Inset to the text column when the rows carry icons, so the icons read as a
 * column of their own instead of being cut off by every rule.
 */
@Composable
fun SettingsDivider(inset: Boolean = true) {
    Divider(
        modifier = Modifier.padding(start = if (inset) TextStart else 0.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

/**
 * The start of a group of settings.
 *
 * Draws a full-width rule above itself: that rule is what closes the previous
 * group, so a header needs no box and no grey background to be a break.
 */
@Composable
fun SettingsSectionHeader(
    icon: ImageVector,
    title: String,
    first: Boolean = false
) {
    if (!first) {
        Spacer(Modifier.height(20.dp))
        Divider(color = MaterialTheme.colorScheme.outlineVariant)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = if (first) 12.dp else 20.dp, bottom = 8.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(10.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * One setting.
 *
 * [content] is for the settings that are more than a label — a row of chips, a
 * slider, a pair of buttons. It sits under the title in the same row, indented
 * to the text column, so a compound setting still reads as one entry in the
 * list rather than as a panel dropped into it.
 */
@Composable
fun SettingsRow(
    icon: ImageVector? = null,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    showDivider: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(SettingsRowPadding),
            verticalAlignment = Alignment.Top
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp).padding(top = 2.dp)
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        subtitle,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (content != null) {
                    Spacer(Modifier.height(10.dp))
                    content()
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
        if (showDivider) SettingsDivider(inset = icon != null)
    }
}
