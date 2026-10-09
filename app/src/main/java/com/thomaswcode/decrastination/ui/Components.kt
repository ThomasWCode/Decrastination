package com.thomaswcode.decrastination.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thomaswcode.decrastination.R

/*
 * The app's building blocks for a screen that skims: sections under a bold coloured heading, and
 * the items in them as tiles a shade lighter than the background, a sliver of it between each, so
 * one item ends visibly where the next begins. A group's outer corners are round, its inner ones
 * square.
 */

private val OUTER = 20.dp
private val GAP = 3.dp

/** The shape of the [index]th of [count] tiles in a group: round only at the group's ends. */
fun groupShape(index: Int, count: Int): RoundedCornerShape {
    val top = if (index == 0) OUTER else 0.dp
    val bottom = if (index == count - 1) OUTER else 0.dp
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/**
 * The [index]th of [count] tiles in a lazy list's group: inset from the screen's edges, with its
 * gap below. [onClick] makes the whole tile a button.
 */
@Composable
fun ListTile(index: Int, count: Int, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shaped = Modifier.fillMaxWidth()
        .padding(start = 12.dp, end = 12.dp, bottom = if (index == count - 1) 0.dp else GAP)
        .clip(groupShape(index, count))
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
    Column(
        (if (onClick != null) shaped.clickable(onClick = onClick) else shaped).padding(horizontal = 16.dp, vertical = 12.dp),
        content = content,
    )
}

/**
 * A group laid out in one go (not lazily): its children are tiles, each given its own background
 * with [GroupTile], and the group's corners are rounded by clipping the whole.
 */
@Composable
fun Group(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    if (title != null) SectionHeading(title)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(OUTER)),
        verticalArrangement = Arrangement.spacedBy(GAP),
        content = content,
    )
}

/** One tile of a [Group]. */
@Composable
fun GroupTile(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 16.dp, vertical = 12.dp),
        content = content,
    )
}

/** A section's heading: bold, in the theme's accent, with room above to part it from the last. */
@Composable
fun SectionHeading(title: String, trailing: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        trailing?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/**
 * A heading that folds its section away: [summary] says what's in it, so it reads folded too,
 * and [detail] (in [detailColor]) a line more under the title.
 */
@Composable
fun FoldingHeading(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    summary: String? = null,
    detail: String? = null,
    detailColor: Color = Color.Unspecified,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 28.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = if (detailColor == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else detailColor)
            }
        }
        summary?.let { Pill(it) }
        Icon(
            painterResource(R.drawable.ic_expand_more),
            contentDescription = if (expanded) "Fold" else "Unfold",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp).size(24.dp).rotate(if (expanded) 180f else 0f),
        )
    }
}

/** A short value set apart: a duration, a count. */
@Composable
fun Pill(text: String, container: Color = MaterialTheme.colorScheme.secondaryContainer, content: Color = MaterialTheme.colorScheme.onSecondaryContainer) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(50)) {
        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

/** A warning set apart, in the error colours. */
@Composable
fun WarningPill(text: String) = Pill(text, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)

/** A secondary screen's bar: its title, and a way back. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackBar(title: String, activity: Activity) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = { activity.finish() }) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back") }
        },
    )
}
