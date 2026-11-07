package com.example.looma.feature.note.components

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.delay

@Composable
internal fun NoteTextField(
    state: TextFieldState,
    active: Boolean,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
    lineLimits: TextFieldLineLimits = TextFieldLineLimits.Default,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onKeyboardAction: KeyboardActionHandler? = null,
    scrollState: ScrollState = rememberScrollState(),
    decorator: TextFieldDecorator,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    var lastTouch by remember { mutableLongStateOf(0L) }
    var showInsertionHandle by remember { mutableStateOf(false) }
    val accessibility = LocalAccessibilityManager.current
    val timeout = accessibility?.calculateRecommendedTimeoutMillis(
        originalTimeoutMillis = 3_000L,
        containsIcons = true,
        containsText = false,
        containsControls = true,
    ) ?: 3_000L
    LaunchedEffect(lastTouch, state.selection, state.text.toString(), focused, active, timeout) {
        showInsertionHandle = focused && active
        if (showInsertionHandle) {
            delay(timeout)
            showInsertionHandle = false
        }
    }
    val selectionColors = LocalTextSelectionColors.current
    val handleVisible = active && (!state.selection.collapsed || showInsertionHandle)
    CompositionLocalProvider(
        LocalTextSelectionColors provides TextSelectionColors(
            handleColor = if (handleVisible) selectionColors.handleColor else Color.Transparent,
            backgroundColor = selectionColors.backgroundColor,
        ),
    ) {
        BasicTextField(
            state = state,
            modifier = modifier.pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        lastTouch = SystemClock.uptimeMillis()
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                    } while (event.changes.any { it.pressed })
                }
            },
            interactionSource = interactionSource,
            textStyle = textStyle,
            cursorBrush = SolidColor(if (active) MaterialTheme.colorScheme.primary else Color.Unspecified),
            lineLimits = lineLimits,
            keyboardOptions = keyboardOptions,
            onKeyboardAction = onKeyboardAction,
            scrollState = scrollState,
            decorator = decorator,
        )
    }
}
