package com.krypt.app.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.krypt.app.ui.theme.KryptShape
import com.krypt.app.ui.theme.KryptSize
import com.krypt.app.ui.theme.KryptSpace

/**
 * A full Krypt screen: one left-aligned column, at most `column-max` wide
 * with the 20dp gutter, drawn edge to edge inside the system bars. The
 * column scrolls (so 200% text never breaks it) while the optional
 * [actions] stay pinned to the bottom: the primary button first, then any
 * tertiaries.
 */
@Composable
fun KryptPage(
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = null,
    topPadding: Dp = KryptSpace.s12,
    scrollState: ScrollState = rememberScrollState(),
    actions: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val background = MaterialTheme.colorScheme.background
    Scaffold(
        modifier = modifier,
        containerColor = background,
        contentColor = MaterialTheme.colorScheme.onSurface,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = {
            if (snackbarHostState != null) {
                SnackbarHost(snackbarHostState) { data -> KryptSnackbar(data) }
            }
        },
        bottomBar = {
            if (actions != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(background)
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                        ),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = KryptSize.columnMax)
                            .fillMaxWidth()
                            .padding(
                                start = KryptSpace.gutter,
                                end = KryptSpace.gutter,
                                top = KryptSpace.s3,
                                bottom = KryptSpace.s6,
                            ),
                        verticalArrangement = Arrangement.spacedBy(KryptSpace.s2),
                        content = actions,
                    )
                }
            }
        },
    ) { inner ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = KryptSize.columnMax)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(horizontal = KryptSpace.gutter)
                    .padding(top = topPadding, bottom = KryptSpace.s6),
                content = content,
            )
        }
    }
}

/** Krypt's snackbar: used only for "No Krypt link found". */
@Composable
fun KryptSnackbar(data: SnackbarData) {
    Snackbar(
        snackbarData = data,
        shape = KryptShape.md,
        containerColor = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
    )
}
