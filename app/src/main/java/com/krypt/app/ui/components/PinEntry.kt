package com.krypt.app.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.insertTextAtCursor
import androidx.compose.ui.semantics.isEditable
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.requestFocus
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.krypt.app.R
import com.krypt.app.ui.common.lockoutCountdown
import com.krypt.app.ui.theme.KryptShape
import com.krypt.app.ui.theme.KryptSize
import com.krypt.app.ui.theme.KryptSpace
import com.krypt.app.ui.theme.KryptTheme

/** Default test tag of [PinEntry]'s input node (the row of dots). */
const val PIN_ENTRY_TAG = "pin_entry"

/**
 * Krypt's PIN entry (docs/design-system/components/PinEntry): one dot per
 * possible digit, a message line, and Krypt's own numeric keypad. Digits
 * are never drawn, not even for a moment, and there is no show-PIN option.
 *
 * The row of dots is the input node: it takes focus without raising the
 * system keyboard, takes digits, Backspace and Enter from a hardware
 * keyboard, and offers TalkBack and tests a set-text action. TalkBack
 * reads "Guardian PIN, 4 digits entered", never the digits.
 *
 * The PIN is only the caller's [pin]: keep it in plain `remember` (never
 * saved state) and clear it after every submit.
 *
 * @param minLength positions drawn with a full ring; the ones after it, up
 *   to [maxLength], are optional and fainter.
 * @param busy the PIN is being checked: the filled dots breathe.
 * @param errorKey changes after each wrong PIN, and the dots shake once.
 * @param lockoutMs while non-null, the keypad is disabled and the lockout
 *   ring replaces the message line; [onLockoutElapsed] runs when it ends.
 * @param onSubmit Enter on a hardware keyboard.
 */
@Composable
fun PinEntry(
    pin: String,
    onPinChange: (String) -> Unit,
    maxLength: Int,
    modifier: Modifier = Modifier,
    minLength: Int = 4,
    enabled: Boolean = true,
    busy: Boolean = false,
    message: String? = null,
    isError: Boolean = false,
    errorKey: Any? = null,
    lockoutMs: Long? = null,
    onLockoutElapsed: () -> Unit = {},
    onSubmit: () -> Unit = {},
    tag: String = PIN_ENTRY_TAG,
) {
    val lockoutLeftMs = lockoutCountdown(lockoutMs, onLockoutElapsed)
    val acceptsInput = enabled && !busy && lockoutMs == null
    val update: (String) -> Unit = { typed ->
        onPinChange(typed.filter { it in '0'..'9' }.take(maxLength))
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PinDots(
            pin = pin,
            maxLength = maxLength,
            minLength = minLength,
            acceptsInput = acceptsInput,
            busy = busy,
            isError = isError,
            errorKey = errorKey,
            update = update,
            onSubmit = onSubmit,
            tag = tag,
        )
        Spacer(Modifier.height(KryptSpace.s3))
        if (lockoutMs != null) {
            LockoutCountdown(remainingMs = lockoutLeftMs, totalMs = lockoutMs)
        } else {
            PinMessage(message = message, isError = isError)
        }
        Spacer(Modifier.height(KryptSpace.s5))
        PinKeypad(
            enabled = acceptsInput,
            onDigit = { digit -> if (pin.length < maxLength) update(pin + digit) },
            onDelete = { update(pin.dropLast(1)) },
        )
    }
}

@Composable
private fun PinDots(
    pin: String,
    maxLength: Int,
    minLength: Int,
    acceptsInput: Boolean,
    busy: Boolean,
    isError: Boolean,
    errorKey: Any?,
    update: (String) -> Unit,
    onSubmit: () -> Unit,
    tag: String,
) {
    val focusRequester = remember { FocusRequester() }
    val shake = remember { Animatable(0f) }
    LaunchedEffect(errorKey) {
        if (errorKey != null) {
            shake.animateTo(
                targetValue = 0f,
                animationSpec = keyframes {
                    durationMillis = SHAKE_MS
                    -6f at 70
                    6f at 210
                },
            )
        } else {
            // Typing right after an error can cut the shake short; never leave the dots off-centre.
            shake.snapTo(0f)
        }
    }
    // Focus the dots so a hardware keyboard types into them; no system keyboard appears.
    LaunchedEffect(acceptsInput) {
        if (acceptsInput) runCatching { focusRequester.requestFocus() }
    }

    val description = pluralStringResource(R.plurals.pin_a11y_digits, pin.length, pin.length)
    val breath = if (busy) {
        rememberInfiniteTransition(label = "pinChecking").animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(BREATH_MS), RepeatMode.Reverse),
            label = "pinBreath",
        ).value
    } else {
        1f
    }

    fun onKey(event: KeyEvent): Boolean {
        if (!acceptsInput || event.type != KeyEventType.KeyDown) return false
        return when (event.key) {
            Key.Backspace, Key.Delete -> {
                update(pin.dropLast(1))
                true
            }
            Key.Enter, Key.NumPadEnter -> {
                onSubmit()
                true
            }
            else -> {
                val typed = event.utf16CodePoint.toChar()
                if (typed in '0'..'9') {
                    if (pin.length < maxLength) update(pin + typed)
                    true
                } else {
                    false
                }
            }
        }
    }

    Row(
        modifier = Modifier
            .testTag(tag)
            .focusRequester(focusRequester)
            .focusable(enabled = acceptsInput)
            .onKeyEvent(::onKey)
            .semantics {
                contentDescription = description
                // An edit box for TalkBack and for text input in tests; the digits stay hidden.
                isEditable = true
                if (!acceptsInput) disabled()
                requestFocus {
                    focusRequester.requestFocus()
                    true
                }
                setText { text ->
                    update(text.text)
                    true
                }
                insertTextAtCursor { text ->
                    update(pin + text.text)
                    true
                }
            }
            .graphicsLayer { translationX = shake.value.dp.toPx() }
            .heightIn(min = 28.dp)
            .padding(vertical = KryptSpace.s2),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(maxLength) { index ->
            PinDot(
                filled = index < pin.length,
                required = index < minLength,
                isError = isError,
                breath = breath,
            )
        }
    }
}

@Composable
private fun PinDot(filled: Boolean, required: Boolean, isError: Boolean, breath: Float) {
    val colors = MaterialTheme.colorScheme
    val fill by animateColorAsState(
        targetValue = if (filled) colors.primary else Color.Transparent,
        animationSpec = tween(FILL_MS),
        label = "pinDotFill",
    )
    val ring = when {
        filled -> Color.Transparent
        isError -> colors.error
        required -> colors.outline
        else -> colors.outlineVariant
    }
    Box(
        modifier = Modifier
            .size(KryptSize.pinDot)
            .graphicsLayer { alpha = if (filled) breath else 1f }
            .clip(CircleShape)
            .background(fill)
            .border(1.5.dp, ring, CircleShape),
    )
}

@Composable
private fun PinMessage(message: String?, isError: Boolean) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 20.dp)
            .semantics { if (isError) liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (message != null) {
            if (isError) {
                KryptIcon(KryptIcons.Error, contentDescription = null, tint = colors.error, size = KryptSize.iconSm)
                Spacer(Modifier.width(KryptSpace.s2))
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) colors.error else colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** A 3 x 4 keypad of borderless keys: 1-9, then 0 and Delete. */
@Composable
private fun PinKeypad(enabled: Boolean, onDigit: (Char) -> Unit, onDelete: () -> Unit) {
    val view = LocalView.current
    val tick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
    Column(
        modifier = Modifier
            .widthIn(max = KEYPAD_MAX_WIDTH)
            .fillMaxWidth()
            .alpha(if (enabled) 1f else DISABLED_CONTENT),
        verticalArrangement = Arrangement.spacedBy(KryptSpace.s2),
    ) {
        for (row in KEY_ROWS) {
            Row(horizontalArrangement = Arrangement.spacedBy(KryptSpace.s2)) {
                for (digit in row) {
                    DigitKey(digit, enabled, Modifier.weight(1f)) {
                        tick()
                        onDigit(digit)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(KryptSpace.s2)) {
            Spacer(Modifier.weight(1f))
            DigitKey('0', enabled, Modifier.weight(1f)) {
                tick()
                onDigit('0')
            }
            Key(
                modifier = Modifier.weight(1f),
                enabled = enabled,
                onClick = {
                    tick()
                    onDelete()
                },
            ) {
                KryptIcon(
                    icon = KryptIcons.Backspace,
                    contentDescription = stringResource(R.string.pin_key_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    size = KryptSize.iconLg,
                )
            }
        }
    }
}

@Composable
private fun DigitKey(digit: Char, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Key(modifier = modifier, enabled = enabled, onClick = onClick) {
        Text(
            text = digit.toString(),
            style = KryptTheme.text.keypad,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun Key(
    modifier: Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .heightIn(min = KryptSize.keyHeight)
            .clip(KryptShape.sm)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

private val KEY_ROWS = listOf("123", "456", "789")
private val KEYPAD_MAX_WIDTH = 320.dp
private const val SHAKE_MS = 280
private const val BREATH_MS = 450
private const val FILL_MS = 120
private const val DISABLED_CONTENT = 0.38f
