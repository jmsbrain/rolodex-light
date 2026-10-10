package com.jmsbrain.rolodex.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/** Theme + background shared by every screen. */
@Composable
fun RolodexTheme(content: @Composable () -> Unit) {
    val colors by LightThemeController.colors.collectAsState()
    LightTheme(colors = colors) {
        Box(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) { content() }
    }
}

/** A screen with a top bar, scrolling content and an optional bottom bar. */
@Composable
fun RolodexPage(
    title: String,
    left: LightBarButton? = null,
    right: LightBarButton? = null,
    bottom: List<LightBarButton> = emptyList(),
    content: @Composable ColumnScope.() -> Unit,
) {
    RolodexTheme {
        Column(Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = left,
                center = LightTopBarCenter.Text(title),
                rightButton = right,
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            LightScrollView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 1f.gridUnitsAsDp()),
            ) { content() }
            if (bottom.isNotEmpty()) LightBottomBar(bottom)
        }
    }
}

fun backButton(onClick: () -> Unit) = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = onClick)

/** Full-screen Light keyboard editor; returns the new text, or null if backed out. */
class TextEditScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val initial: String,
    private val caps: Boolean = true,
) : SimpleLightScreen<String>(sealedActivity) {

    @Composable
    override fun Content() {
        val keyboard = rememberKeyboardOptions()
        val state = rememberTextFieldState(initial)
        RolodexTheme {
            LightTextInputEditor(
                title = title,
                state = state,
                keyboardOptionsFlow = keyboard,
                onSubmit = { goBack(it.toString()) },
                onBack = { goBack(null) },
                initialCaps = caps && initial.isBlank(),
            )
        }
    }
}

/** Pick one of [options]; returns the choice. */
class ChoiceScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val options: List<String>,
    private val selected: String?,
) : SimpleLightScreen<String>(sealedActivity) {

    @Composable
    override fun Content() {
        RolodexPage(title = title, left = backButton { goBack(null) }) {
            options.forEach { option ->
                LightText(
                    text = option,
                    variant = LightTextVariant.Copy,
                    lighten = option != selected,
                    underline = option == selected,
                    modifier = Modifier
                        .fillMaxWidth()
                        .lightClickable { goBack(option) }
                        .padding(vertical = 0.75f.gridUnitsAsDp()),
                )
            }
        }
    }
}

/** Yes/no question; returns true for [confirm]. */
class ConfirmScreen(
    sealedActivity: SealedLightActivity,
    private val message: String,
    private val confirm: String,
) : SimpleLightScreen<Boolean>(sealedActivity) {

    @Composable
    override fun Content() {
        RolodexPage(
            title = "",
            left = backButton { goBack(false) },
            bottom = listOf(
                LightBarButton.Text(text = "CANCEL", onClick = { goBack(false) }),
                LightBarButton.Text(text = confirm, onClick = { goBack(true) }),
            ),
        ) {
            LightText(text = message, variant = LightTextVariant.Paragraph)
        }
    }
}
