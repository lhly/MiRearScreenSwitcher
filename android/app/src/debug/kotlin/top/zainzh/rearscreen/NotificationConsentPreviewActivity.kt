package top.zainzh.rearscreen

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/** Debug-only Activity host for the production notification consent screen UI fixture. */
class NotificationConsentPreviewActivity : ComponentActivity() {
    private var previewMode by mutableStateOf(NotificationConsentPreviewMode.LIGHT_DEFAULT_PORTRAIT)
    @Volatile var lastAppliedConfiguration: NotificationConsentAppliedConfiguration? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                lastResult = "cancel"
                setResult(RESULT_CANCELED)
                finish()
            }
        })
        miuixContent {
            val selectedMode = previewMode
            val currentConfiguration = LocalConfiguration.current
            val currentContext = LocalContext.current
            val systemDensity = LocalDensity.current
            BoxWithConstraints(
                Modifier.fillMaxSize().background(AppPalette.background()),
                contentAlignment = Alignment.TopCenter,
            ) {
                val viewportWidthPx = maxWidth.value * systemDensity.density
                val viewportHeightPx = maxHeight.value * systemDensity.density
                val simulatedDensity = minOf(
                    viewportWidthPx / selectedMode.widthDp,
                    viewportHeightPx / selectedMode.heightDp,
                )
                val simulatedConfiguration = Configuration(currentConfiguration).apply {
                    fontScale = selectedMode.fontScale
                    orientation = if (selectedMode.landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                    screenWidthDp = selectedMode.widthDp
                    screenHeightDp = selectedMode.heightDp
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (selectedMode.dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
                val simulatedContext = currentContext.createConfigurationContext(simulatedConfiguration)
                CompositionLocalProvider(
                    LocalConfiguration provides simulatedConfiguration,
                    LocalContext provides simulatedContext,
                    LocalDensity provides Density(simulatedDensity, selectedMode.fontScale),
                ) {
                    // Reapply the same shared theme after local configuration overrides so dark-mode
                    // colors and controls match the mode being reviewed without changing device state.
                    AppTheme {
                        val appliedConfiguration = LocalConfiguration.current
                        val appliedDensity = LocalDensity.current
                        SideEffect {
                            lastAppliedConfiguration = NotificationConsentAppliedConfiguration(
                                dark = appliedConfiguration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES,
                                fontScale = appliedConfiguration.fontScale,
                                orientation = appliedConfiguration.orientation,
                                widthDp = appliedConfiguration.screenWidthDp,
                                heightDp = appliedConfiguration.screenHeightDp,
                                density = appliedDensity.density,
                            )
                        }
                        Box(
                            Modifier.requiredSize(selectedMode.widthDp.dp, selectedMode.heightDp.dp)
                                .background(AppPalette.background())
                                .testTag("notification_consent_viewport"),
                        ) {
                            key(selectedMode) {
                                NotificationPublisherConsentScreen(
                                    publisherLabel = "Notification Preview Publisher",
                                    initiallyAllowed = true,
                                    onCancel = {
                                        lastResult = "cancel"
                                        setResult(RESULT_CANCELED)
                                        finish()
                                    },
                                    onSave = { allowed ->
                                        lastSavedChoice = allowed
                                        lastResult = "save"
                                        setResult(RESULT_OK)
                                        finish()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    fun showPreviewMode(mode: NotificationConsentPreviewMode) {
        previewMode = mode
    }

    companion object {
        @Volatile var lastResult: String? = null
        @Volatile var lastSavedChoice: Boolean? = null
    }
}

data class NotificationConsentAppliedConfiguration(
    val dark: Boolean,
    val fontScale: Float,
    val orientation: Int,
    val widthDp: Int,
    val heightDp: Int,
    val density: Float,
)

enum class NotificationConsentPreviewMode(
    val dark: Boolean,
    val fontScale: Float,
    val landscape: Boolean,
    val widthDp: Int,
    val heightDp: Int,
) {
    LIGHT_DEFAULT_PORTRAIT(false, 1f, false, 360, 800),
    DARK_DEFAULT_PORTRAIT(true, 1f, false, 360, 800),
    LIGHT_LARGE_PORTRAIT(false, 1.3f, false, 360, 800),
    DARK_LARGE_LANDSCAPE(true, 1.3f, true, 800, 540),
}
