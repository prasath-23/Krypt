package com.krypt.app.ui.components

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import org.junit.Assert.assertEquals
import org.junit.Test

class StyledFormatTest {

    private val italic = SpanStyle(fontStyle = FontStyle.Italic)

    @Test
    fun positionalArguments_landWhereTheTemplateSays() {
        val text = styledFormat("%2\$s, then %1\$s", StyledArg("first"), StyledArg("second"))
        assertEquals("second, then first", text.text)
    }

    @Test
    fun aStyledArgument_isStyledOverExactlyItsOwnText() {
        val text = styledFormat("%1\$s, %2\$s, once.", StyledArg("Instagram"), StyledArg("30 minutes", italic))

        assertEquals("Instagram, 30 minutes, once.", text.text)
        val span = text.spanStyles.single()
        assertEquals(italic, span.item)
        assertEquals("30 minutes", text.text.substring(span.start, span.end))
    }

    @Test
    fun numbers_plainPlaceholders_andPercentSigns() {
        assertEquals("Open · 12 min left", styledFormat("Open · %1\$d min left", StyledArg("12")).text)
        assertEquals("a b", styledFormat("%s %s", StyledArg("a"), StyledArg("b")).text)
        assertEquals("100% sure", styledFormat("%1\$d%% sure", StyledArg("100")).text)
    }

    @Test
    fun aMissingArgument_isLeftOut_andTheRestStays() {
        assertEquals("Expires in ", styledFormat("Expires in %1\$s").text)
    }
}
