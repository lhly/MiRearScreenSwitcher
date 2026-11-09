package top.zainzh.rearscreen

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Base64
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Renders the production Miuix consent content in four local-only configuration variants. */
class NotificationConsentComposeTest {
    @get:Rule val compose = createAndroidComposeRule<NotificationConsentPreviewActivity>()

    @Test fun capturesFourLocalConfigurationVariantsAndAggregatesUiAssertions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val result = Bundle()
        val variantDetails = JSONArray()
        val failures = mutableListOf<String>()
        val encodedImages = mutableListOf<Pair<String, String>>()
        val modes = listOf(
            NotificationConsentPreviewMode.LIGHT_DEFAULT_PORTRAIT,
            NotificationConsentPreviewMode.DARK_DEFAULT_PORTRAIT,
            NotificationConsentPreviewMode.LIGHT_LARGE_PORTRAIT,
            NotificationConsentPreviewMode.DARK_LARGE_LANDSCAPE,
        )

        modes.forEachIndexed { index, mode ->
            instrumentation.runOnMainSync { compose.activity.showPreviewMode(mode) }
            compose.waitForIdle()

            val applied = compose.activity.lastAppliedConfiguration
            val variant = JSONObject()
                .put("name", mode.name.lowercase())
                .put("expectedDark", mode.dark)
                .put("expectedFontScale", mode.fontScale)
                .put("expectedOrientation", if (mode.landscape) "landscape" else "portrait")
                .put("viewportWidthDp", mode.widthDp)
                .put("viewportHeightDp", mode.heightDp)
            if (applied == null) {
                failures += "${mode.name}: no applied LocalConfiguration measurement"
            } else {
                variant.put("actualDark", applied.dark)
                    .put("actualFontScale", applied.fontScale.toDouble())
                    .put("actualOrientation", if (applied.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait")
                    .put("actualScreenWidthDp", applied.widthDp)
                    .put("actualScreenHeightDp", applied.heightDp)
                    .put("actualDensity", applied.density.toDouble())
                if (applied.dark != mode.dark || applied.fontScale != mode.fontScale ||
                    applied.orientation != (if (mode.landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT) ||
                    applied.widthDp != mode.widthDp || applied.heightDp != mode.heightDp) {
                    failures += "${mode.name}: local configuration did not match requested variant ($applied)"
                }
            }

            // Capture each exact production screen before any assertions or scroll actions, so a
            // failing variant still contributes an image to instrumentation results.
            runCatching {
                val captured = compose.onNodeWithTag("notification_consent_viewport")
                    .captureToImage().asAndroidBitmap()
                val maxWidth = 640
                val scale = minOf(1f, maxWidth.toFloat() / captured.width)
                val bitmap = if (scale < 1f) Bitmap.createScaledBitmap(
                    captured, (captured.width * scale).toInt(), (captured.height * scale).toInt(), true
                ) else captured
                val bytes = ByteArrayOutputStream().use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                    stream.toByteArray()
                }
                val key = "notificationConsent.jpg.${index}.${mode.name.lowercase()}"
                encodedImages += key to Base64.encodeToString(bytes, Base64.NO_WRAP)
                variant.put("resultKey", key)
                    .put("imageFormat", "JPEG")
                    .put("jpegQuality", 85)
                    .put("jpegWidth", bitmap.width)
                    .put("jpegHeight", bitmap.height)
                    .put("jpegBytes", bytes.size)
                if (bitmap !== captured) bitmap.recycle()
                if (captured !== bitmap) captured.recycle()
            }.onFailure { failures += "${mode.name}: screenshot capture failed: ${it.message}" }

            runCatching {
                val activity = compose.activity
                compose.onNodeWithText(activity.getString(R.string.notification_publisher_consent_title)).assertIsDisplayed()
                compose.onNodeWithText("Notification Preview Publisher", substring = true).assertIsDisplayed()
                compose.onNodeWithText(activity.getString(R.string.notification_publisher_consent_access)).assertIsDisplayed()
                compose.onNode(isToggleable()).assertIsOn()
                // These checks scroll only when needed; actual Save interaction is covered below.
                compose.onNodeWithTag("notification_consent_save").performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("notification_consent_cancel").performScrollTo().assertIsDisplayed()
            }.onFailure { failures += "${mode.name}: consent semantics/layout failed: ${it.message}" }

            variant.put("assertionsPassed", failures.none { it.startsWith("${mode.name}:") })
            variantDetails.put(variant)
        }

        val metadata = variantDetails.toString()
        // Bundle strings are UTF-16 in the instrumentation result path. Keep comfortable
        // distance below Binder's transaction ceiling instead of retrying with unreadable images.
        val imageChars = encodedImages.sumOf { it.second.length.toLong() }
        val estimateBytes = imageChars * 2L + metadata.length * 2L + 8L * 1024L
        val resultLimitBytes = 800L * 1024L
        if (estimateBytes >= resultLimitBytes) {
            failures += "screenshot result Bundle too large: estimated ${estimateBytes} bytes, limit ${resultLimitBytes} bytes"
        } else {
            encodedImages.forEach { (key, base64) -> result.putString(key, base64) }
        }
        result.putString("notificationConsent.variants", metadata)
        result.putLong("notificationConsent.estimatedBundleBytes", estimateBytes)
        result.putLong("notificationConsent.bundleLimitBytes", resultLimitBytes)
        // addResults runs before the final aggregate assertion, preserving screenshots on failure.
        instrumentation.addResults(result)
        assertTrue("Notification consent UI failures: ${failures.joinToString("; ")}", failures.isEmpty())
    }

    @Test fun backCancelsWithoutCommittingChangedSwitch() {
        NotificationConsentPreviewActivity.lastResult = null
        NotificationConsentPreviewActivity.lastSavedChoice = null
        compose.onNode(isToggleable()).performClick()
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitUntil(5_000) { NotificationConsentPreviewActivity.lastResult == "cancel" }
        assertEquals("cancel", NotificationConsentPreviewActivity.lastResult)
        assertEquals(null, NotificationConsentPreviewActivity.lastSavedChoice)
    }

    @Test fun saveCommitsCurrentChoice() {
        NotificationConsentPreviewActivity.lastResult = null
        NotificationConsentPreviewActivity.lastSavedChoice = null
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithTag("notification_consent_save").performScrollTo().performClick()
        compose.waitUntil(5_000) { NotificationConsentPreviewActivity.lastResult == "save" }
        assertEquals("save", NotificationConsentPreviewActivity.lastResult)
        assertEquals(false, NotificationConsentPreviewActivity.lastSavedChoice)
    }
}
