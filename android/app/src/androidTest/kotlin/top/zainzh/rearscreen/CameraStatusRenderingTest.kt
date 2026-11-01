package top.zainzh.rearscreen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class CameraStatusRenderingTest {
    @Test fun completeGlyphsAndWeatherIconsStayOutsideLenses() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val profile = RearCameraGapGeometry.supported("popsicle")!!
        inst.runOnMainSync {
            val view = RearCameraStatusView(inst.targetContext)
            view.configure(profile, 0, 0)
            view.layout(0, 0, 976, 596)
            val bitmap = Bitmap.createBitmap(976, 596, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val allGlyphs = Bitmap.createBitmap(976, 596, Bitmap.Config.ARGB_8888)
            val allCanvas = Canvas(allGlyphs)
            val weekdays = listOf("周日", "星期三", "Wed", "mer.")
            val temperatures = listOf("7℃", "-12℃", "-99℃", "120℉")
            for (type in listOf(0, 1, 2, 3, 7, 8, 12, 15, 25, 99)) {
                for (i in weekdays.indices) {
                    bitmap.eraseColor(Color.TRANSPARENT)
                    view.setData("23:59", "12/31", weekdays[i], RearWeatherSnapshot.parse(temperatures[i], type.toString(), System.currentTimeMillis().toString(), "1"))
                    view.draw(canvas)
                    allCanvas.drawBitmap(bitmap, 0f, 0f, null)
                    var ink = 0
                    for (y in 0 until 596) for (x in 0 until 296) {
                        if (Color.alpha(bitmap.getPixel(x, y)) < 16) continue
                        ink++
                        assertTrue("Upper lens collision at $x,$y", hypot(x - 150.0, y - 150.0) >= 137 + RearCameraStatusGeometry.FILM_CLEARANCE)
                        assertTrue("Lower lens collision at $x,$y", hypot(x - 150.0, y - 450.0) >= 137 + RearCameraStatusGeometry.FILM_CLEARANCE)
                        assertTrue("Outer edge collision", x > 4 && x < 292 && y > 4 && y < 592)
                    }
                    assertTrue("Missing rendered information", ink > 200)
                }
            }
            inst.targetContext.openFileOutput("camera-status-all-glyphs.png", 0).use {
                allGlyphs.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            allGlyphs.recycle()
            bitmap.recycle()
        }
    }
}
