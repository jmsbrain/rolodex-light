package com.jmsbrain.rolodex.ui

import android.content.ClipData
import android.content.res.Configuration
import android.text.format.DateUtils
import android.util.DisplayMetrics
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightModal
import com.thelightphone.sdk.ui.LightModalManager
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightFullscreenModal
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

/** Page text size vs. Light's standard, as in Android Rolodex. */
const val TEXT_SCALE = 1.2f

/** Contact list names and search, as in Android Rolodex. */
const val LIST_TEXT_SCALE = 1.3f

/** Text in [content] at [scale] times its current size. */
@Composable
fun TextScale(scale: Float, content: @Composable () -> Unit) {
    val current = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(current.density, current.fontScale * scale), content = content)
}

/** A screen with a top bar, scrolling content and an optional bottom bar. Page text is at [TEXT_SCALE]. */
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
            ) { TextScale(TEXT_SCALE) { content() } }
            if (bottom.isNotEmpty()) LightBottomBar(bottom)
        }
    }
}

fun backButton(onClick: () -> Unit) = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = onClick)

/** Label, then the value (or a dimmed hint when empty). Tap to edit. */
@Composable
fun TapField(label: String, value: String, placeholder: String, onClick: () -> Unit) {
    LightText(label, LightTextVariant.Superfine, lighten = true, modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()))
    LightText(
        text = value.ifBlank { placeholder },
        variant = LightTextVariant.Paragraph,
        lighten = value.isBlank(),
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.3f.gridUnitsAsDp()),
    )
}

/** Full-screen Light keyboard editor; returns the new text, or null if backed out. */
class TextEditScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val initial: String,
    private val caps: Boolean = true,
    private val singleLine: Boolean = true,
) : SimpleLightScreen<String>(sealedActivity) {

    @Composable
    override fun Content() {
        val keyboard = rememberKeyboardOptions()
        val state = rememberTextFieldState(initial)
        RolodexTheme {
            DefaultDisplaySize {
                LightTextInputEditor(
                    title = title,
                    state = state,
                    keyboardOptionsFlow = keyboard,
                    onSubmit = { goBack(it.toString()) },
                    onBack = { goBack(null) },
                    initialCaps = caps && initial.isBlank(),
                    singleLine = singleLine,
                )
            }
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

/** Section heading, e.g. PHONE. */
@Composable
fun Heading(title: String) {
    LightText(title, LightTextVariant.Detail, lighten = true, modifier = Modifier.padding(top = 1.5f.gridUnitsAsDp()))
}

/** A tappable word in the page, e.g. SYNC NOW. */
@Composable
fun TextAction(label: String, onClick: () -> Unit) {
    LightText(
        text = label,
        variant = LightTextVariant.Button,
        modifier = Modifier
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.5f.gridUnitsAsDp()),
    )
}

/** Dimmed explanation text. */
@Composable
fun Dim(text: String) = LightText(text, LightTextVariant.Paragraph, lighten = true)

/** Tap and long-press, without a press effect. */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.tapAndHold(onTap: (() -> Unit)?, onHold: () -> Unit): Modifier =
    combinedClickable(interactionSource = null, indication = null, onLongClick = onHold, onClick = { onTap?.invoke() })

// Not tied to a screen: the copy can finish after the screen is gone.
private val uiScope = MainScope()

/** Copies text and says so. */
@Composable
fun rememberCopy(): (String) -> Unit {
    val clipboard = LocalClipboard.current
    return remember(clipboard) {
        { text: String ->
            uiScope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("contact", text))) }
            showNotice("Copied")
        }
    }
}

/** A short message at the bottom of the screen that goes away by itself. */
fun showNotice(text: String) = LightModalManager.show(Notice(text))

private class Notice(private val text: String) : LightModal {
    private val done = CompletableDeferred<Unit>()
    override val onExpired: () -> Unit = {}
    override fun dismiss() {
        done.complete(Unit)
    }

    override suspend fun awaitDismiss() = done.await()

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        LightTheme(colors = colors) {
            Box(Modifier.fillMaxSize().padding(bottom = 6f.gridUnitsAsDp()), contentAlignment = Alignment.BottomCenter) {
                LightText(
                    text = text,
                    variant = LightTextVariant.Copy,
                    modifier = Modifier
                        .background(LightThemeTokens.colors.background)
                        .border(1.dp, LightThemeTokens.colors.content)
                        .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
                )
            }
        }
    }
}

/** "5 MINUTES AGO", or "JUST NOW". */
fun ago(time: Long): String {
    val now = System.currentTimeMillis()
    return if (now - time < DateUtils.MINUTE_IN_MILLIS) "JUST NOW"
    else DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS).toString().uppercase(Locale.getDefault())
}

/** [message] instead of the page, not over it: the SDK modal lets taps through to the page. */
@Composable
fun MessageOr(message: String?, onClose: () -> Unit, page: @Composable () -> Unit) {
    if (message != null) RolodexTheme { LightFullscreenModal(message = message, onClose = onClose) } else page()
}

/** The phone's default display size. A smaller Display size setting shrinks the SDK keyboard; LightOS's own doesn't. */
@Composable
fun DefaultDisplaySize(content: @Composable () -> Unit) {
    val current = LocalDensity.current
    val config = LocalConfiguration.current
    val stable = DisplayMetrics.DENSITY_DEVICE_STABLE
    val density = stable / DisplayMetrics.DENSITY_DEFAULT.toFloat()
    // Same pixels, counted in default-size dp (the SDK's grid sizes from these).
    val defaultConfig = Configuration(config).apply {
        densityDpi = stable
        screenWidthDp = (config.screenWidthDp * current.density / density).roundToInt()
        screenHeightDp = (config.screenHeightDp * current.density / density).roundToInt()
    }
    CompositionLocalProvider(
        LocalDensity provides Density(density, current.fontScale),
        LocalConfiguration provides defaultConfig,
        content = content,
    )
}
