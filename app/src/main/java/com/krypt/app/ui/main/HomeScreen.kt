package com.krypt.app.ui.main

import androidx.annotation.StringRes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.krypt.app.R
import com.krypt.app.ui.components.AppIcon
import com.krypt.app.ui.components.BodyText
import com.krypt.app.ui.components.CountBadge
import com.krypt.app.ui.components.Eyebrow
import com.krypt.app.ui.components.GroupDivider
import com.krypt.app.ui.components.KryptIcon
import com.krypt.app.ui.components.KryptIcons
import com.krypt.app.ui.components.KryptSheet
import com.krypt.app.ui.components.PrimaryButton
import com.krypt.app.ui.components.ScreenTitle
import com.krypt.app.ui.components.SectionHeader
import com.krypt.app.ui.components.StatusLine
import com.krypt.app.ui.components.TertiaryButton
import com.krypt.app.ui.home.AppIconCache
import com.krypt.app.ui.home.AppRowStatus
import com.krypt.app.ui.home.AppearanceSheet
import com.krypt.app.ui.home.AppearanceViewModel
import com.krypt.app.ui.home.DailyLine
import com.krypt.app.ui.home.HomeViewModel
import com.krypt.app.ui.home.InstalledAppRow
import com.krypt.app.ui.home.InstalledAppRowState
import com.krypt.app.ui.theme.KryptMotion
import com.krypt.app.ui.theme.KryptShape
import com.krypt.app.ui.theme.KryptSize
import com.krypt.app.ui.theme.KryptSpace
import com.krypt.app.ui.theme.KryptTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Date

/**
 * Home: your apps (docs/design-system/Screens.md and components/AppRow).
 * The Guardian chooses which apps are locked, and sees or ends what is open
 * now. Reachable only after the Guardian PIN ([AppEntryPinScreen]).
 *
 * A thin layer over [HomeViewModel]: the eyebrow, the serif title and the
 * count badges, the search field, then the sections Open now, Locked and Not
 * locked. Turning a lock on happens at once; turning it off asks in a sheet
 * first. Tapping a row that is open now offers Relock now or Stop daily
 * time. The contrast button opens the Appearance sheet.
 *
 * @param highlightPackage the app a "New app protected" notification pointed
 *   at: Home scrolls to it and highlights it for 2 seconds, then calls
 *   [onHighlightShown].
 */
@Composable
fun HomeScreen(
    highlightPackage: String? = null,
    onHighlightShown: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
    appearanceViewModel: AppearanceViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val appearance by appearanceViewModel.appearance.collectAsStateWithLifecycle()

    // Pick up apps installed or removed while Krypt was in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    val list = remember(state.rows) { homeList(state.rows) }
    val listState = rememberLazyListState()

    // The sheets follow their app by package name, so they always show its latest state.
    var appearanceOpen by remember { mutableStateOf(false) }
    var optionsFor by rememberSaveable { mutableStateOf<String?>(null) }
    var unlockFor by rememberSaveable { mutableStateOf<String?>(null) }
    var highlighted by remember { mutableStateOf<String?>(null) }

    val latestOnHighlightShown by rememberUpdatedState(onHighlightShown)
    LaunchedEffect(highlightPackage) {
        val pkg = highlightPackage ?: return@LaunchedEffect
        // Show the whole list, so an earlier search can't hide the app.
        if (state.query.isNotBlank()) viewModel.setQuery("")
        snapshotFlow { state.loaded }.first { it }
        // The list may predate the app's install: give the refresh on resume a moment to bring it.
        val shown = withTimeoutOrNull(HIGHLIGHT_WAIT_MS) {
            snapshotFlow { state }.first { now ->
                now.query.isBlank() && now.rows.any { it.packageName == pkg }
            }
        }
        if (shown != null) {
            val index = LEADING_ITEMS + homeList(shown.rows).indexOfApp(pkg)
            try {
                try {
                    // Keep one item above it in view: its section header or the app before it.
                    listState.animateScrollToItem((index - 1).coerceAtLeast(0))
                } catch (interrupted: CancellationException) {
                    // A touch stopped the scroll: still highlight, unless this effect itself ended.
                    ensureActive()
                }
                highlighted = pkg
                delay(HIGHLIGHT_MS)
            } finally {
                highlighted = null
            }
        }
        latestOnHighlightShown()
    }

    val colors = MaterialTheme.colorScheme
    Scaffold(
        containerColor = colors.background,
        contentColor = colors.onSurface,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { inner ->
        val direction = LocalLayoutDirection.current
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = inner.calculateStartPadding(direction),
                    top = inner.calculateTopPadding(),
                    end = inner.calculateEndPadding(direction),
                ),
            state = listState,
            // The list scrolls under the navigation bar and the keyboard; its end stays reachable.
            contentPadding = PaddingValues(bottom = inner.calculateBottomPadding() + KryptSpace.s6),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item(key = KEY_HEADER, contentType = KEY_HEADER) {
                HomeHeader(
                    // Counted over every app, not just the search results.
                    lockedCount = state.lockedCount,
                    openCount = state.openCount,
                    countsKnown = state.loaded,
                    onOpenAppearance = { appearanceOpen = true },
                    modifier = Modifier.homeGutter(),
                )
            }
            item(key = KEY_SEARCH, contentType = KEY_SEARCH) {
                SearchField(
                    query = state.query,
                    onQueryChange = viewModel::setQuery,
                    modifier = Modifier
                        .homeGutter()
                        .padding(top = KryptSpace.s5),
                )
            }
            if (state.rows.isEmpty()) {
                item(key = KEY_STATUS, contentType = KEY_STATUS) {
                    ListStatus(
                        loaded = state.loaded,
                        query = state.query,
                        modifier = Modifier
                            .homeGutter()
                            .padding(top = KryptSpace.s6),
                    )
                }
            }
            items(
                items = list.entries,
                key = { it.key },
                contentType = { it.contentType },
            ) { entry ->
                val placement = Modifier.animateItem(
                    fadeInSpec = null,
                    placementSpec = tween(KryptMotion.BASE_MS, easing = KryptMotion.Easing),
                    fadeOutSpec = null,
                )
                when (entry) {
                    is HomeEntry.Section -> SectionHeader(
                        title = stringResource(entry.section.title),
                        count = entry.count,
                        modifier = placement.homeGutter(),
                    )
                    is HomeEntry.App -> {
                        val row = entry.row
                        val openOptions: (() -> Unit)? = if (row.status.hasOptions()) {
                            { optionsFor = row.packageName }
                        } else {
                            null
                        }
                        Column(modifier = placement.homeColumn()) {
                            if (!entry.firstInSection) {
                                GroupDivider(Modifier.padding(horizontal = KryptSpace.gutter))
                            }
                            InstalledAppRow(
                                state = row,
                                iconCache = viewModel.iconCache,
                                onToggle = {
                                    if (row.isLocked) {
                                        // Removing a lock opens the app for good: ask first.
                                        unlockFor = row.packageName
                                    } else {
                                        viewModel.onToggle(row)
                                    }
                                },
                                onOpenOptions = openOptions,
                                highlighted = row.packageName == highlighted,
                            )
                        }
                    }
                }
            }
        }
    }

    if (appearanceOpen) {
        AppearanceSheet(
            selected = appearance,
            onSelect = appearanceViewModel::choose,
            onDismiss = { appearanceOpen = false },
        )
    }
    optionsFor?.let { pkg ->
        key(pkg) {
            RowOptionsSheet(
                live = state.rows.firstOrNull { it.packageName == pkg }?.takeIf { it.status.hasOptions() },
                iconCache = viewModel.iconCache,
                onLockNow = viewModel::onLockNow,
                onEndDailyTime = viewModel::onEndDailyTime,
                onClosed = { optionsFor = null },
            )
        }
    }
    unlockFor?.let { pkg ->
        key(pkg) {
            RemoveLockSheet(
                live = state.rows.firstOrNull { it.packageName == pkg }?.takeIf { it.isLocked },
                onRemoveLock = viewModel::onToggle,
                onClosed = { unlockFor = null },
            )
        }
    }
}

/** The eyebrow with the Appearance button, the serif title, and the count badges. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeHeader(
    lockedCount: Int,
    openCount: Int,
    countsKnown: Boolean,
    onOpenAppearance: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val krypt = KryptTheme.colors
    Column(modifier = modifier.padding(top = KryptSpace.s8)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Eyebrow(text = stringResource(R.string.eyebrow_krypt))
            IconButton(
                onClick = onOpenAppearance,
                // Lines the icon itself, not its 48dp target, up with the gutter.
                modifier = Modifier.offset(x = (KryptSize.touchTarget - KryptSize.iconMd) / 2),
            ) {
                KryptIcon(
                    icon = KryptIcons.Contrast,
                    contentDescription = stringResource(R.string.appearance),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        ScreenTitle(text = stringResource(R.string.home_title))
        FlowRow(
            modifier = Modifier
                .padding(top = KryptSpace.s4)
                // Holds its place while the apps load, so nothing below jumps.
                .then(if (countsKnown) Modifier else Modifier.alpha(0f).clearAndSetSemantics {}),
            horizontalArrangement = Arrangement.spacedBy(KryptSpace.s2),
            verticalArrangement = Arrangement.spacedBy(KryptSpace.s2),
        ) {
            CountBadge(
                text = pluralStringResource(R.plurals.home_badge_locked, lockedCount, lockedCount),
                icon = KryptIcons.Lock,
                containerColor = krypt.lockedContainer,
                contentColor = krypt.onLockedContainer,
            )
            if (openCount > 0) {
                CountBadge(
                    text = pluralStringResource(R.plurals.home_badge_open, openCount, openCount),
                    icon = KryptIcons.LockOpen,
                    containerColor = krypt.openContainer,
                    contentColor = krypt.onOpenContainer,
                )
            }
        }
    }
}

/** Search apps: filters the list as the Guardian types; Search on the keyboard just hides it. */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = KryptSize.fieldHeight),
        textStyle = MaterialTheme.typography.bodyLarge,
        placeholder = { Text(text = stringResource(R.string.home_search_hint)) },
        leadingIcon = {
            KryptIcon(icon = KryptIcons.Search, contentDescription = null, tint = colors.onSurfaceVariant)
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(
            onSearch = {
                keyboard?.hide()
                focusManager.clearFocus()
            },
        ),
        shape = KryptShape.md,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = colors.surfaceContainer,
            unfocusedContainerColor = colors.surfaceContainer,
            disabledContainerColor = colors.surfaceContainer,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent,
            focusedPlaceholderColor = colors.onSurfaceVariant,
            unfocusedPlaceholderColor = colors.onSurfaceVariant,
        ),
    )
}

/** What shows instead of rows: finding the apps, no apps at all, or no match for the search. */
@Composable
private fun ListStatus(
    loaded: Boolean,
    query: String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(KryptSpace.s2),
    ) {
        when {
            !loaded -> {
                Text(
                    text = stringResource(R.string.home_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.primary,
                    trackColor = colors.outlineVariant,
                    strokeCap = StrokeCap.Butt,
                    gapSize = 0.dp,
                )
            }
            query.isBlank() -> {
                Text(
                    text = stringResource(R.string.home_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface,
                )
                BodyText(text = stringResource(R.string.home_empty_body))
            }
            else -> BodyText(text = stringResource(R.string.home_empty_no_match, query))
        }
    }
}

/**
 * The sheet a row open now opens: the app, its status with the time it ends,
 * and one way to end the access early (Relock now / Stop daily time).
 *
 * @param live the app as it is now, or null once it has nothing to offer
 *   (its time ran out, or it was removed); the sheet then closes by itself.
 */
@Composable
private fun RowOptionsSheet(
    live: InstalledAppRowState?,
    iconCache: AppIconCache,
    onLockNow: (InstalledAppRowState) -> Unit,
    onEndDailyTime: (InstalledAppRowState) -> Unit,
    onClosed: () -> Unit,
) {
    // While the sheet slides away its app may already read "Locked": keep showing what was offered.
    val row = rememberLastNonNull(live)
    if (row == null) {
        // Nothing to offer from the start (e.g. restored after the time ran out).
        LaunchedEffect(Unit) { onClosed() }
        return
    }
    HomeSheet(onClosed = onClosed, stale = live == null) { close ->
        val status = row.status
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KryptSpace.s3),
        ) {
            AppIcon(packageName = row.packageName, label = row.displayName, iconCache = iconCache)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                StatusLine(status = status, endsAt = endsAt(row))
            }
        }
        when (status) {
            is AppRowStatus.Unlocked -> PrimaryButton(
                text = stringResource(R.string.home_sheet_relock),
                onClick = { close { onLockNow(row) } },
                modifier = Modifier.padding(top = KryptSpace.s3),
                icon = KryptIcons.Lock,
            )
            is AppRowStatus.Daily -> PrimaryButton(
                text = stringResource(R.string.home_sheet_stop_daily),
                onClick = { close { onEndDailyTime(row) } },
                modifier = Modifier.padding(top = KryptSpace.s3),
            )
            else -> Unit
        }
        val keep = if (status is AppRowStatus.Daily && status.line !is DailyLine.Left) {
            R.string.home_sheet_keep_daily
        } else {
            R.string.home_sheet_keep_open
        }
        TertiaryButton(
            text = stringResource(keep),
            onClick = { close {} },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Asks before a lock is removed, because the app then opens without the
 * Guardian for good.
 *
 * @param live the app while it is still locked; null once it isn't (or is
 *   gone), and the sheet then closes by itself.
 */
@Composable
private fun RemoveLockSheet(
    live: InstalledAppRowState?,
    onRemoveLock: (InstalledAppRowState) -> Unit,
    onClosed: () -> Unit,
) {
    val row = rememberLastNonNull(live)
    if (row == null) {
        LaunchedEffect(Unit) { onClosed() }
        return
    }
    HomeSheet(onClosed = onClosed, stale = live == null) { close ->
        Text(
            text = stringResource(R.string.home_unlock_title, row.displayName),
            modifier = Modifier.semantics { heading() },
            style = KryptTheme.text.headline,
            color = MaterialTheme.colorScheme.onSurface,
        )
        BodyText(text = stringResource(R.string.home_unlock_body))
        PrimaryButton(
            text = stringResource(R.string.home_unlock_confirm),
            // The row is locked here, so this removes the lock and never adds one.
            onClick = { close { onRemoveLock(row) } },
            modifier = Modifier.padding(top = KryptSpace.s2),
        )
        TertiaryButton(
            text = stringResource(R.string.home_unlock_cancel),
            onClick = { close {} },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * A [KryptSheet] that closes with its animation. [content] gets
 * `close(then)`: it runs `then` once, slides the sheet away and then calls
 * [onClosed]; taps while it closes are ignored. When [stale] turns true (the
 * app the sheet is about changed underneath it), the sheet closes by itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeSheet(
    onClosed: () -> Unit,
    stale: Boolean,
    content: @Composable ColumnScope.(close: (then: () -> Unit) -> Unit) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val latestOnClosed by rememberUpdatedState(onClosed)
    var closing by remember { mutableStateOf(false) }
    val close: (() -> Unit) -> Unit = { then ->
        if (!closing) {
            closing = true
            then()
            scope.launch { sheetState.hide() }.invokeOnCompletion { latestOnClosed() }
        }
    }
    LaunchedEffect(stale) {
        if (stale) close {}
    }
    // Swiping it down, tapping outside or Back: the sheet has already slid away.
    KryptSheet(onDismissRequest = { latestOnClosed() }, sheetState = sheetState) {
        content(close)
    }
}

/** [value] while it is non-null, else the last non-null value it had. */
@Composable
private fun <T : Any> rememberLastNonNull(value: T?): T? {
    val last = remember { mutableStateOf(value) }
    if (value != null) SideEffect { last.value = value }
    return value ?: last.value
}

/** For a one-time unlock, the clock time it ends, e.g. "14:35", in the phone's 12/24-hour style. */
@Composable
private fun endsAt(row: InstalledAppRowState): String? {
    val context = LocalContext.current
    val timeFormat = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }
    if (row.status !is AppRowStatus.Unlocked || row.grantRemainingMs <= 0L) return null
    // Rounded to the second, so the minute shown doesn't flicker as the countdown ticks.
    val endMs = (System.currentTimeMillis() + row.grantRemainingMs + 500L) / 1_000L * 1_000L
    return timeFormat.format(Date(endMs))
}

/** Home's sections, in screen order. */
private enum class HomeSection(@StringRes val title: Int) {
    OpenNow(R.string.home_section_open),
    Locked(R.string.home_section_locked),
    NotLocked(R.string.home_section_not_locked),
}

/** Open now: a one-time unlock, or daily time left today. Done for today and paused count as locked. */
private fun AppRowStatus.section(): HomeSection = when (this) {
    AppRowStatus.NotLocked -> HomeSection.NotLocked
    AppRowStatus.Locked -> HomeSection.Locked
    is AppRowStatus.Unlocked -> HomeSection.OpenNow
    is AppRowStatus.Daily -> if (line is DailyLine.Left) HomeSection.OpenNow else HomeSection.Locked
}

/** A row with a one-time unlock or daily time opens a sheet to end that access early. */
private fun AppRowStatus.hasOptions(): Boolean =
    this is AppRowStatus.Unlocked || this is AppRowStatus.Daily

/** One item of the list below the search field. */
private sealed interface HomeEntry {
    val key: String
    val contentType: String

    data class Section(val section: HomeSection, val count: Int) : HomeEntry {
        override val key: String get() = "#section-${section.name}"
        override val contentType: String get() = "section"
    }

    /** Keyed by package name, so a row that changes section keeps its place in the list's memory. */
    data class App(val row: InstalledAppRowState, val firstInSection: Boolean) : HomeEntry {
        override val key: String get() = row.packageName
        override val contentType: String get() = "app"
    }
}

private class HomeList(
    val entries: List<HomeEntry>,
) {
    fun indexOfApp(packageName: String): Int =
        entries.indexOfFirst { it is HomeEntry.App && it.row.packageName == packageName }
}

/** Splits the rows into sections, each keeping the rows' own order. */
private fun homeList(rows: List<InstalledAppRowState>): HomeList {
    val bySection = rows.groupBy { it.status.section() }
    val entries = buildList<HomeEntry> {
        HomeSection.entries.forEach { section ->
            val sectionRows = bySection[section].orEmpty()
            if (sectionRows.isNotEmpty()) {
                add(HomeEntry.Section(section, sectionRows.size))
                sectionRows.forEachIndexed { i, row -> add(HomeEntry.App(row, firstInSection = i == 0)) }
            }
        }
    }
    return HomeList(entries = entries)
}

/** Home's column: centred, and never wider than `column-max`. */
private fun Modifier.homeColumn(): Modifier = widthIn(max = KryptSize.columnMax).fillMaxWidth()

/** [homeColumn] with the 20dp gutter: everything but the rows, which pad themselves. */
private fun Modifier.homeGutter(): Modifier = homeColumn().padding(horizontal = KryptSpace.gutter)

private const val KEY_HEADER = "#header"
private const val KEY_SEARCH = "#search"
private const val KEY_STATUS = "#status"

/** The header and the search field come before the first section whenever there are rows. */
private const val LEADING_ITEMS = 2

private const val HIGHLIGHT_MS = 2_000L
private const val HIGHLIGHT_WAIT_MS = 3_000L
