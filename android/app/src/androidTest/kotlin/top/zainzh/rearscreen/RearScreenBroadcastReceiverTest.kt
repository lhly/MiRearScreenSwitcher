package top.zainzh.rearscreen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RearScreenBroadcastReceiverTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun rearOffBroadcastIsForwardedAsAnObservationOnlyDuringModuleLease() {
        assumeFalse("test requires no pre-existing module display lease", ModuleDisplayRecoveryService.hasActiveLease())
        val (field, receiver) = activeLeaseField()
        val previous = field.get(receiver)
        val delivered = CountDownLatch(1)
        val actionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ModuleDisplayRecoveryService.ACTION_MANUAL_REAR_OFF) delivered.countDown()
            }
        }
        ContextCompat.registerReceiver(context, actionReceiver,
            IntentFilter(ModuleDisplayRecoveryService.ACTION_MANUAL_REAR_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            field.set(receiver, "test-active-lease")
            RearScreenBroadcastReceiver().onReceive(context, Intent("miui.intent.action.SUB_SCREEN_OFF"))
            assertTrue("SUB_SCREEN_OFF should forward a classification hint", delivered.await(2, TimeUnit.SECONDS))
        } finally {
            field.set(receiver, previous)
            context.unregisterReceiver(actionReceiver)
        }
    }

    private fun activeLeaseField(): Pair<Field, Any?> {
        for (owner in listOf(ModuleDisplayRecoveryService::class.java, ModuleDisplayRecoveryService.Companion::class.java)) {
            val field = runCatching { owner.getDeclaredField("activeLease") }.getOrNull() ?: continue
            field.isAccessible = true
            val target = if (Modifier.isStatic(field.modifiers)) null else ModuleDisplayRecoveryService.Companion
            return field to target
        }
        throw AssertionError("could not locate in-memory active lease field")
    }
}
