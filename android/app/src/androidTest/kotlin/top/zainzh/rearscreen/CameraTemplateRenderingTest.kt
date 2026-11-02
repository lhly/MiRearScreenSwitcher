package top.zainzh.rearscreen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

/** Actual Android font/icon/bar pixels against independently extracted official openings. */
@androidx.test.filters.SdkSuppress(minSdkVersion = 33)
class CameraTemplateRenderingTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val devices = listOf("pandora", "popsicle", "hongkong", "madrid")

    private fun mask(device: String): Bitmap = inst.context.assets.open("camera_area/$device.png").use {
        BitmapFactory.decodeStream(it)!!
    }

    private fun validate(bitmap: Bitmap, mask: Bitmap, p: RearCameraGapGeometry.Profile, extra: Float) {
        var ink = 0
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            if (Color.alpha(pixels[y * bitmap.width + x]) < 16) continue
            ink++
            assertTrue("${p.device}: outside camera strip at $x,$y", x < mask.width)
            assertTrue("${p.device}: official template obstruction at $x,$y", Color.red(mask.getPixel(x, y)) <= 16)
            val exclusion = p.radius + p.margin + extra
            assertTrue("${p.device}: upper lens at $x,$y", hypot(x - p.x.toDouble(), y - p.upperY.toDouble()) >= exclusion)
            assertTrue("${p.device}: lower lens at $x,$y", hypot(x - p.x.toDouble(), y - p.lowerY.toDouble()) >= exclusion)
            assertTrue("${p.device}: outer edge", x > 4 && y > 4 && y < p.height - 4)
        }
        assertTrue("${p.device}: missing information", ink > 200)
    }

    private fun save(bitmap: Bitmap, name: String) {
        inst.targetContext.openFileOutput(name, 0).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private class HardwareCanvas(width: Int, height: Int) : AutoCloseable {
        private val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        private val node = RenderNode("camera-template-test").apply { setPosition(0, 0, width, height) }
        private val renderer = HardwareRenderer().apply {
            setOpaque(false); setSurface(reader.surface); setContentRoot(node)
        }
        fun draw(view: View): Bitmap {
            val canvas = node.beginRecording()
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            view.draw(canvas); node.endRecording()
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val image = checkNotNull(reader.acquireNextImage()) { "No hardware frame" }
            image.use {
                val buffer = checkNotNull(image.hardwareBuffer)
                buffer.use {
                    val hardware = checkNotNull(Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB)))
                    val bitmap = checkNotNull(hardware.copy(Bitmap.Config.ARGB_8888, false))
                    hardware.recycle()
                    return bitmap
                }
            }
        }
        override fun close() { renderer.destroy(); node.discardDisplayList(); reader.close() }
    }

    @Test fun completeGlyphsAndWeatherIconsStayInAllFourOfficialOpenings() {
        val errors = mutableListOf<String>()
        for (device in devices) {
            val p = RearCameraGapGeometry.supported(device)!!
            val mask = mask(device)
            inst.runOnMainSync {
                val view = RearCameraStatusView(inst.targetContext)
                view.configure(p, 0, 0); view.layout(0, 0, p.width, p.height)
                val bitmap = Bitmap.createBitmap(p.width, p.height, Bitmap.Config.ARGB_8888)
                val union = Bitmap.createBitmap(p.width, p.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap); val unionCanvas = Canvas(union)
                val weekdays = listOf("周日", "星期三", "Wed", "mer.", "週六", "Thu")
                val temperatures = listOf("7℃", "-12℃", "-99℃", "120℉", "-100℃", "999℉")
                for (type in listOf(0, 1, 2, 3, 7, 8, 12, 15, 25, 99)) for (i in weekdays.indices) {
                    bitmap.eraseColor(Color.TRANSPARENT)
                    view.setData(if (i % 2 == 0) "23:59" else "12:08", "12/31", weekdays[i],
                        RearWeatherSnapshot.parse(temperatures[i], type.toString(), System.currentTimeMillis().toString(), if (i == 3 || i == 5) "0" else "1"))
                    view.draw(canvas)
                    try { validate(bitmap, mask, p, RearCameraStatusGeometry.FILM_CLEARANCE) }
                    catch (error: AssertionError) {
                        if (type == 0) save(bitmap, "camera-status-$device-case-$i.png")
                        errors += "$device weekday=${weekdays[i]} temperature=${temperatures[i]}: ${error.message}"
                    }
                    unionCanvas.drawBitmap(bitmap, 0f, 0f, null)
                }
                save(union, "camera-status-$device-all-glyphs.png")
                bitmap.eraseColor(Color.TRANSPARENT)
                view.setData("23:59", "11/02", "周一", RearWeatherSnapshot.parse("7℃", "0", System.currentTimeMillis().toString(), "1"))
                view.draw(canvas)
                save(bitmap, "camera-status-$device-preview.png")
                bitmap.eraseColor(Color.TRANSPARENT); view.setData("23:59", "12/31", "周一", null); view.draw(canvas)
                try { validate(bitmap, mask, p, RearCameraStatusGeometry.FILM_CLEARANCE) }
                catch (error: AssertionError) { errors += "$device without weather: ${error.message}" }
                union.recycle(); bitmap.recycle()
            }
            mask.recycle()
        }
        assertTrue(errors.distinct().joinToString("\n"), errors.isEmpty())
    }

    @Test fun batteryBarsAndAllPercentageGlyphsStayInAllFourOfficialOpenings() {
        for (device in devices) {
            val p = RearCameraGapGeometry.supported(device)!!
            val mask = mask(device)
            inst.runOnMainSync {
                val view = RearCameraGapBatteryView(inst.targetContext)
                view.configure(p, 0, 0); view.layout(0, 0, p.width, p.height)
                val hardware = HardwareCanvas(p.width, p.height)
                for (percentage in 0..100) {
                    view.setPercentage(percentage)
                    val bitmap = hardware.draw(view)
                    validate(bitmap, mask, p, -1f) // Coverage/AA may include the pixel straddling the exact contour.
                    var digits = 0
                    for (y in p.centerY().toInt() - 12..p.centerY().toInt() + 12)
                        for (x in p.x.toInt() - 42..p.x.toInt() + 42) {
                            val color = bitmap.getPixel(x, y)
                            if (Color.red(color) > 210 && Color.green(color) > 230 && Color.blue(color) > 210) digits++
                        }
                    assertTrue("$device: unreadable percentage $percentage", digits > 20)
                    if (percentage == 67) save(bitmap, "camera-battery-$device-preview.png")
                    if (percentage == 100) {
                        val axis = p.x.toInt()
                        for (y in 0 until p.height) for (x in 0 until axis) {
                            // The percentage itself is text, so compare only the bar silhouette.
                            if (axis - x < 48 && kotlin.math.abs(y - p.centerY()) < 18) continue
                            val mirror = 2 * axis - 1 - x // Reflect pixel centers, not pixel edges.
                            val difference = kotlin.math.abs(Color.alpha(bitmap.getPixel(x, y)) - Color.alpha(bitmap.getPixel(mirror, y)))
                            assertTrue("$device: asymmetric bar outline at $x,$y and $mirror,$y", difference <= 2)
                        }
                        save(bitmap, "camera-battery-$device-full.png")
                    }
                    bitmap.recycle()
                }
                view.setPercentage(-1)
                val bitmap = hardware.draw(view)
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                assertTrue("Unknown battery must be invisible", pixels.all { Color.alpha(it) == 0 })
                bitmap.recycle()
                hardware.close()
            }
            mask.recycle()
        }
    }
}
