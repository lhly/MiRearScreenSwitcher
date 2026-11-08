package top.zainzh.rearscreen

import android.app.Activity
import android.content.Context

/** Release adapter has no legacy .remodule owner; generic Cast and Scene routes remain independent. */
internal object RearModuleSession {
    fun taskId() = -1
    fun isVisible(id: Int) = false
    fun isOwner(id: Int, owner: Activity) = false
    fun isAnimationTask(id: Int) = false
    fun identityFor(id: Int): ModuleRearAnimationTaskIdentity? = null
    fun animationCapture(animationId: Long): ModuleRearAnimationTaskCapture? = null
    fun appContext(): Context? = null
    fun owns(foreground: String, packageName: String) = false
    fun captureAnimationTask(animationId: Long, id: Int) = Unit
    fun animationChanged() = Unit
}
