package com.layarkita.tv.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.layarkita.tv.ui.theme.NetflixBlack
import com.layarkita.tv.ui.theme.NetflixDarkCard
import com.layarkita.tv.ui.theme.NetflixLightGray
import com.layarkita.tv.ui.theme.NetflixRed
import com.layarkita.tv.ui.theme.NetflixWhite
import kotlinx.coroutines.launch

/**
 * TV text field: a tv-material3 non-clickable [Surface] wrapper with a
 * [BasicTextField] inside. The Surface is presentation-only (never focusable),
 * so D-pad focus lands directly on the inner field and TalkBack announces a
 * single editable node.
 *
 * Remote behavior is centralized here so call sites stay declarative:
 * - BACK hides the IME and clears focus, so the next D-pad press moves
 *   instead of re-opening the keyboard.
 * - UP releases the field's input session (which otherwise vetoes outgoing
 *   focus transfers) and moves to [barEscapeLink], running [onBarEscape].
 * - IME actions chain: [onImeAction] overrides; otherwise focus moves to
 *   [nextFieldRequester] when attached (Next/Done chaining), else the IME
 *   just hides.
 *
 * Voice input needs no extra wiring: the stock Gboard TV layout already
 * carries a mic key for plain text input, and the global-search voice button
 * comes from `res/xml/searchable.xml` (`voiceSearchMode`).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Focus chain for the inner field (requesters, links, focus tracking). */
    fieldModifier: Modifier = Modifier,
    hint: String = "",
    /** Receives the current focus state for tinting. */
    leadingIcon: (@Composable (focused: Boolean) -> Unit)? = null,
    textStyle: TextStyle = TextStyle(
        color = NetflixWhite,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium
    ),
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: (() -> Unit)? = null,
    /** Done/Next target (Next/Done chaining). Null keeps focus and hides. */
    nextFieldRequester: FocusRequester? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    containerColor: Color = NetflixBlack,
    // Content-to-bar escape (see topEdgeBarEscape): the field holds an input
    // session that vetoes outgoing transfers, so UP releases it first. Null
    // is a no-op so plain in-content fields skip the escape untouched.
    barEscapeLink: FocusRequester? = null,
    // Genuine escapes also run the owner's scroll hook (see scrollToTop).
    onBarEscape: (suspend () -> Unit)? = null,
    /** Extra key hook for call-site moves (DOWN/RIGHT...). True consumes. */
    onPreviewKey: ((KeyEvent) -> Boolean)? = null,
    enabled: Boolean = true,
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val escapeScope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }

    val defaultIme: () -> Unit = {
        val next = nextFieldRequester
        if (next != null) {
            try {
                next.requestFocus()
            } catch (_: Exception) {
                keyboardController?.hide()
            }
        } else {
            keyboardController?.hide()
        }
    }
    val imeHandler: () -> Unit = { onImeAction?.invoke() ?: defaultIme() }

    Surface(
        shape = RoundedCornerShape(8.dp),
        colors = SurfaceDefaults.colors(containerColor = containerColor),
        modifier = modifier.border(
            width = if (focused) 2.dp else 1.dp,
            color = if (focused) NetflixWhite else NetflixDarkCard,
            shape = RoundedCornerShape(8.dp)
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingIcon != null) {
                leadingIcon(focused)
                Spacer(modifier = Modifier.width(10.dp))
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = textStyle,
                cursorBrush = SolidColor(NetflixRed),
                keyboardOptions = KeyboardOptions(imeAction = imeAction),
                keyboardActions = KeyboardActions(
                    onDone = { imeHandler() },
                    onGo = { imeHandler() },
                    onNext = { imeHandler() },
                    onPrevious = { imeHandler() },
                    onSearch = { imeHandler() },
                    onSend = { imeHandler() },
                ),
                singleLine = true,
                enabled = enabled,
                visualTransformation = visualTransformation,
                modifier = Modifier
                    .weight(1f)
                    .then(fieldModifier)
                    .onFocusChanged { focused = it.isFocused }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        if (onPreviewKey?.invoke(event) == true) return@onPreviewKeyEvent true
                        when (event.key) {
                            Key.Back -> {
                                try {
                                    keyboardController?.hide()
                                } catch (_: Exception) {}
                                try {
                                    focusManager.clearFocus(force = true)
                                } catch (_: Exception) {}
                                true
                            }
                            Key.DirectionUp -> {
                                val link = barEscapeLink ?: return@onPreviewKeyEvent false
                                try {
                                    focusManager.clearFocus(force = true)
                                } catch (_: Exception) {}
                                escapeScope.launch {
                                    try {
                                        onBarEscape?.invoke()
                                    } catch (_: Exception) {}
                                }
                                try {
                                    link.requestFocus()
                                    true
                                } catch (_: Exception) {
                                    false
                                }
                            }
                            else -> false
                        }
                    },
                decorationBox = { inner ->
                    if (value.isEmpty() && hint.isNotEmpty()) {
                        Text(
                            text = hint,
                            color = NetflixLightGray,
                            fontSize = 13.sp
                        )
                    }
                    inner()
                }
            )
        }
    }
}
