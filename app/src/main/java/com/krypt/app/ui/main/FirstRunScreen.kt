package com.krypt.app.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.krypt.app.R
import com.krypt.app.ui.components.BodyText
import com.krypt.app.ui.components.Eyebrow
import com.krypt.app.ui.components.GroupDivider
import com.krypt.app.ui.components.HintText
import com.krypt.app.ui.components.KryptGroup
import com.krypt.app.ui.components.KryptIcon
import com.krypt.app.ui.components.KryptIcons
import com.krypt.app.ui.components.KryptPage
import com.krypt.app.ui.components.PrimaryButton
import com.krypt.app.ui.components.ScreenTitle
import com.krypt.app.ui.components.TertiaryButton
import com.krypt.app.ui.link.pasteKryptLinkAction
import com.krypt.app.ui.theme.KryptSpace
import kotlinx.coroutines.launch

/** Where the first run is: the choice, the permission checklist, or the Guardian's screen. */
private enum class FirstRunStep { Choose, Protect, Guardian }

/**
 * Everything [MainRoute] shows until onboarding is finished
 * (docs/design-system/Screens.md, "First run"). It opens on "who is this
 * phone for?": protecting it leads to the permission checklist, while a
 * Guardian only needs Paste a link, so that path asks for no permission.
 * Back, on screen or from the system, returns to the choice.
 *
 * The step is saved, so a trip to Settings that recreates the activity
 * comes back to the same screen.
 */
@Composable
fun FirstRunRoute(onComplete: () -> Unit) {
    var step by rememberSaveable { mutableStateOf(FirstRunStep.Choose) }
    BackHandler(enabled = step != FirstRunStep.Choose) { step = FirstRunStep.Choose }
    when (step) {
        FirstRunStep.Choose -> FirstRunScreen(
            onProtect = { step = FirstRunStep.Protect },
            onGuardian = { step = FirstRunStep.Guardian },
        )
        FirstRunStep.Protect -> OnboardingScreen(
            onComplete = onComplete,
            onBack = { step = FirstRunStep.Choose },
        )
        FirstRunStep.Guardian -> GuardianWelcomeScreen(
            onBack = { step = FirstRunStep.Choose },
        )
    }
}

/** "Welcome to Krypt": two large choices in one group, and nothing asked of the phone yet. */
@Composable
fun FirstRunScreen(
    onProtect: () -> Unit,
    onGuardian: () -> Unit,
) {
    KryptPage {
        Eyebrow(stringResource(R.string.eyebrow_krypt))
        Spacer(Modifier.height(KryptSpace.s2))
        ScreenTitle(stringResource(R.string.first_run_title))
        Spacer(Modifier.height(KryptSpace.s6))
        KryptGroup {
            FirstRunChoice(
                title = stringResource(R.string.first_run_protect),
                line = stringResource(R.string.first_run_protect_line),
                onClick = onProtect,
            )
            GroupDivider()
            FirstRunChoice(
                title = stringResource(R.string.first_run_guardian),
                line = stringResource(R.string.first_run_guardian_line),
                onClick = onGuardian,
            )
        }
    }
}

/**
 * The Guardian's phone: Krypt is here only to open request links, so the
 * one action is Paste a link, for a messenger that doesn't make a link
 * tappable. No permission is asked on this path.
 */
@Composable
fun GuardianWelcomeScreen(onBack: () -> Unit) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val linkNotFound = stringResource(R.string.link_not_found)
    val pasteLink = pasteKryptLinkAction {
        scope.launch { snackbarHostState.showSnackbar(linkNotFound) }
    }
    KryptPage(
        snackbarHostState = snackbarHostState,
        actions = {
            PrimaryButton(
                text = stringResource(R.string.paste_link),
                onClick = pasteLink,
                icon = KryptIcons.Link,
            )
            HintText(stringResource(R.string.paste_link_hint))
            TertiaryButton(text = stringResource(R.string.action_back), onClick = onBack)
        },
    ) {
        Eyebrow(stringResource(R.string.guardian_home_eyebrow))
        Spacer(Modifier.height(KryptSpace.s2))
        ScreenTitle(stringResource(R.string.guardian_home_title))
        Spacer(Modifier.height(KryptSpace.s2))
        BodyText(stringResource(R.string.guardian_home_body))
    }
}

/** One large choice row: a name, one line under it, and an arrow. The whole row is the button. */
@Composable
private fun FirstRunChoice(
    title: String,
    line: String,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = CHOICE_MIN_HEIGHT)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(KryptSpace.s4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KryptSpace.s3),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(CHOICE_LINE_GAP),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface,
            )
            Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
        KryptIcon(
            icon = KryptIcons.ArrowForward,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
        )
    }
}

/** A large choice row is at least this tall (Screens.md, "First run"). */
private val CHOICE_MIN_HEIGHT = 72.dp

/** Between a choice's name and its line (`.kx-row__body`). */
private val CHOICE_LINE_GAP = 2.dp
