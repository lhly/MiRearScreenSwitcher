package top.zainzh.rearscreen.platform.mods

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/** One host broker per process; UI effect requests are handed to the currently visible Mod scene. */
object ModBrokerProvider {
    @Volatile private var broker: ModEventBroker? = null
    fun get(context: Context): ModEventBroker = broker ?: synchronized(this) {
        broker ?: ModEventBroker(context.applicationContext, ModEffectDispatcher { mod, eventId, effect ->
            val consumer = ModHostEffectRouter.consumers[mod.id]
                ?: throw IllegalStateException("当前没有可确认此 Effect 的可见 Mod 场景")
            consumer.request(mod, eventId, effect)
        }).also { broker = it }
    }
}

internal object ModHostEffectRouter {
    fun interface Consumer { fun request(mod: InstalledMod, eventId: String, effect: ModRearOpenEffect) }
    val consumers = ConcurrentHashMap<String, Consumer>()
}
