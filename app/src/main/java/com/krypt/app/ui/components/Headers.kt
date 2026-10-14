package com.krypt.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.krypt.app.ui.theme.KryptSize
import com.krypt.app.ui.theme.KryptSpace
import com.krypt.app.ui.theme.KryptTheme

/** The small-capitals line above a title, e.g. "PROTECTED BY KRYPT", with the mark. */
@Composable
fun Eyebrow(
    text: String,
    modifier: Modifier = Modifier,
    showMark: Boolean = true,
) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KryptSpace.s2),
    ) {
        if (showMark) KryptMark(height = KryptSize.iconSm, tint = color)
        Text(text = text, style = KryptTheme.text.eyebrow, color = color)
    }
}

/** A screen's serif title (display-small), e.g. "Your apps". */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.semantics { heading() },
        style = MaterialTheme.typography.displaySmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** The one serif statement on a full screen (display-large), e.g. "Locked." */
@Composable
fun Statement(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.semantics { heading() },
        style = MaterialTheme.typography.displayLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** Supporting copy under a title, in body-large. */
@Composable
fun BodyText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(text = text, modifier = modifier, style = MaterialTheme.typography.bodyLarge, color = color)
}

/** A small hint or explanation, in body-small; centre it under a button with [textAlign]. */
@Composable
fun HintText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    textAlign: TextAlign? = null,
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        textAlign = textAlign,
    )
}

/** A list section's small-capitals header and its count, e.g. "LOCKED" ... "40". */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = KryptSpace.s6, bottom = KryptSpace.s2)
            .semantics(mergeDescendants = true) { heading() },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, style = KryptTheme.text.eyebrow, color = color)
        if (count != null) Text(text = count.toString(), style = KryptTheme.text.eyebrow, color = color)
    }
}
