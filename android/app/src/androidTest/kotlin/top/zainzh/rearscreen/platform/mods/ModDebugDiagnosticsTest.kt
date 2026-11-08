package top.zainzh.rearscreen.platform.mods

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModDebugDiagnosticsTest {
    @Test fun recordsAreBoundedAndOnlyAcceptNarrowDiagnosticFields() {
        val buffer = ModDebugDiagnostics(capacity = 3)
        buffer.record("runtime.ready", revision = 0)
        buffer.record("model.committed", revision = 1, eventId = "event:1")
        buffer.record("effect.requested", eventId = "event:2")
        buffer.record("runtime.error", code = "E_CPU_BUDGET")

        val records = buffer.snapshot()
        assertEquals(3, records.size)
        assertEquals(listOf("model.committed", "effect.requested", "runtime.error"), records.map { it.kind })
        assertEquals(null, records.last().eventId)
        assertEquals("E_CPU_BUDGET", records.last().code)
        assertThrows(IllegalArgumentException::class.java) { buffer.record("global.dump") }
        assertThrows(IllegalArgumentException::class.java) { ModDebugDiagnostics(capacity = 65) }
    }

    @Test fun resourceSnapshotCountsOnlyTheSelectedSignedPackagePayloads() {
        val archive = InstrumentationRegistry.getInstrumentation().context.assets.open("platform/mods/generic-counter.zip").use { it.readBytes() }
        val pkg = ModPackageReader.read(archive.inputStream())
        val resources = ModDebugDiagnostics.resources(pkg)
        assertEquals(pkg.payloads.size, resources.payloadCount)
        assertEquals(pkg.payloads.values.sumOf { it.size.toLong() }, resources.totalBytes)
        assertEquals(pkg.payloads.filterKeys { it.endsWith(".ui.json") }.values.sumOf { it.size.toLong() }, resources.sceneBytes)
        assertEquals(pkg.payloads["main.js"]?.size?.toLong() ?: 0L, resources.scriptBytes)
        assertEquals(pkg.payloads.filterKeys { it.startsWith("assets/") }.values.sumOf { it.size.toLong() }, resources.assetBytes)
        assertTrue(resources.otherBytes >= 0)
    }
}
