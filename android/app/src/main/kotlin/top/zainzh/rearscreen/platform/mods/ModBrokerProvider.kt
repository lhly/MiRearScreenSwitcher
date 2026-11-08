package top.zainzh.rearscreen.platform.mods

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/** One host broker per process; UI effect requests are handed to the currently visible Mod scene. */
object ModBrokerProvider {
    @Volatile private var broker: ModEventBroker? = null
    fun get(context: Context): ModEventBroker = broker ?: synchronized(this) {
        broker ?: ModEventBroker(context.applicationContext, object : ModEffectDispatcher {
            override fun requestRearScene(mod: InstalledMod, eventId: String, effect: ModRearOpenEffect) {
                val consumer = ModHostEffectRouter.consumers[mod.id]
                    ?: throw IllegalStateException("当前没有可确认此 Effect 的可见 Mod 场景")
                consumer.request(mod, eventId, effect)
            }
            override fun requestRearSceneWithResult(mod: InstalledMod, eventId: String, effect: ModRearOpenEffect, complete: (Boolean, String?) -> Unit) {
                val consumer = ModHostEffectRouter.consumers[mod.id]
                if (consumer == null) complete(false, "E_SCENE_NOT_VISIBLE")
                else consumer.requestWithResult(mod, eventId, effect, complete)
            }
        }).also { broker = it }
    }
}

internal object ModHostEffectRouter {
    fun interface Consumer {
        fun request(mod: InstalledMod, eventId: String, effect: ModRearOpenEffect)
        fun requestWithResult(mod: InstalledMod, eventId: String, effect: ModRearOpenEffect, complete: (Boolean, String?) -> Unit) {
            runCatching { request(mod, eventId, effect) }
                .onSuccess { complete(true, null) }
                .onFailure { complete(false, "E_EFFECT_DISPATCH") }
        }
    }
    val consumers = ConcurrentHashMap<String, Consumer>()
}
