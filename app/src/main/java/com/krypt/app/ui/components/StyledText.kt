package com.krypt.app.ui.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/** One argument of [styledFormat]: its text and, optionally, a style of its own. */
data class StyledArg(val text: String, val style: SpanStyle? = null)

private val PLACEHOLDER = Regex("%(?:(\\d+)\\$)?([sd%])")

/**
 * Formats an Android string template, e.g. "Open · %1$d min left", into an
 * [AnnotatedString] and gives each argument its own style: a ticking number
 * in mono, the duration of the summary sentence in italic. Supports `%N$s`,
 * `%N$d`, `%s`, `%d` and `%%`. Pass the template unformatted
 * (`stringResource(id)` or `pluralStringResource(id, count)` with no args).
 */
fun styledFormat(template: String, vararg args: StyledArg): AnnotatedString = buildAnnotatedString {
    var copied = 0
    var nextArg = 0
    for (match in PLACEHOLDER.findAll(template)) {
        append(template.substring(copied, match.range.first))
        copied = match.range.last + 1
        if (match.groupValues[2] == "%") {
            append('%')
            continue
        }
        val index = match.groupValues[1].toIntOrNull()?.minus(1) ?: nextArg++
        val arg = args.getOrNull(index) ?: continue
        val style = arg.style
        if (style == null) append(arg.text) else withStyle(style) { append(arg.text) }
    }
    append(template.substring(copied))
}
