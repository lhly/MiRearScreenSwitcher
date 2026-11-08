package top.zainzh.rearscreen.platform.mods

import android.content.Context
import android.os.Build
import org.json.JSONObject
import top.zainzh.rearscreen.platform.runtime.IsolatedModRuntimeClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Raised only when the host cannot prove that the migration runtime released its slot. */
internal class ModRuntimeCloseUnconfirmedException(cause: Throwable? = null) :
    IllegalStateException("迁移 runtime 尚未确认关闭；旧版本和本地数据保持不变", cause)

/** Message text can come from untrusted Mod code; lifecycle decisions use this host-owned type only. */
internal fun isRuntimeCloseUnconfirmed(failure: Throwable?): Boolean =
    failure is ModRuntimeCloseUnconfirmedException

/** Executes only the new package's pure migrate function, then lets the store validate and atomically commit. */
class IsolatedModMigrationRunner(context: Context) : ModMigrationRunner {
    private val appContext = context.applicationContext
    private val epoch = AtomicLong(System.nanoTime())
    /** Deterministic AndroidTest handoff point; production callers leave this unset. */
    internal var afterMigrationRequestForTesting: (() -> Unit)? = null

    override fun migrate(oldPackage: VerifiedModPackage, newPackage: VerifiedModPackage, model: ModPersistedModel): ModMigrationResult {
        require(Build.VERSION.SDK_INT >= 29) { "dataVersion 迁移需要 Android API 29+ 隔离 runtime" }
        require(newPackage.runtime == ModRuntimeKind.QUICKJS && newPackage.reducerSource != null) { "dataVersion 变化需要新包提供纯 migrate 函数" }
        val done = CountDownLatch(1)
        val result = AtomicReference<ModMigrationResult?>()
        val failure = AtomicReference<String?>()
        val callbackExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "remrss-mod-migration-callback").apply { isDaemon = true } }
        val clientRef = AtomicReference<IsolatedModRuntimeClient?>()
        val connected = java.util.concurrent.atomic.AtomicBoolean(false)
        val loadSent = java.util.concurrent.atomic.AtomicBoolean(false)
        fun loadWhenConnected() {
            connected.set(true)
            val bound = clientRef.get() ?: return
            if (loadSent.compareAndSet(false, true)) bound.loadModule(newPackage.reducerSource)
        }
        var client: IsolatedModRuntimeClient? = null
        var migrationResult: ModMigrationResult? = null
        var migrationFailure: Throwable? = null
        var restoreInterrupt = false
        try {
            client = IsolatedModRuntimeClient.bind(appContext, epoch.incrementAndGet(), callbackExecutor,
                object : IsolatedModRuntimeClient.Listener {
                    override fun onConnected(epoch: Long) { loadWhenConnected() }

                    override fun onResult(epoch: Long, operation: String, json: String) {
                        try {
                            when (operation) {
                                "loadModule" -> {
                                    val loaded = ModPackageReader.parseRuntimeObject(json, "migration.loadModule")
                                    if (!loaded.optBoolean("ok", false)) throw IllegalStateException(
                                        "${loaded.optString("code", "E_SCRIPT")}: ${loaded.optString("message", "新 Mod 脚本加载失败")}")
                                    val context = JSONObject()
                                        // A package that was enabled but never activated has no
                                        // persisted state yet. Keep that absence distinct from
                                        // an initialized empty object so migrate can preserve it.
                                        .put("state", model.state ?: JSONObject.NULL)
                                        .put("config", model.config)
                                        .put("fromVersion", model.dataVersion)
                                        .put("toVersion", newPackage.dataVersion)
                                        .put("env", JSONObject())
                                    (clientRef.get() ?: throw IllegalStateException("迁移 runtime 已关闭")).migrate(context.toString())
                                    // The test hook fires only after the real migrate
                                    // request is queued; the caller thread is already
                                    // waiting on its result latch.
                                    afterMigrationRequestForTesting?.invoke()
                                }
                                "migrate" -> {
                                    val value = ModPackageReader.parseRuntimeObject(json, "migration.result")
                                    require(value.keys().asSequence().toSet() == setOf("state", "config")) { "migrate 必须只返回 {state,config}，不得返回 Effect" }
                                    require(value.has("state")) { "migrate 结果缺少 state" }
                                    val migratedState = when (val state = value.opt("state")) {
                                        is JSONObject -> state
                                        JSONObject.NULL -> null
                                        null -> throw IllegalArgumentException("migrate 结果缺少 state")
                                        else -> throw IllegalArgumentException("migrate 的 state 必须为 object 或 null（未初始化模型）")
                                    }
                                    require(value.opt("config") is JSONObject) { "migrate 的 config 必须为 object" }
                                    result.set(ModMigrationResult(migratedState, value.getJSONObject("config")))
                                    done.countDown()
                                }
                                else -> throw IllegalStateException("迁移 runtime 返回意外操作 $operation")
                            }
                        } catch (e: Exception) { failure.compareAndSet(null, e.message ?: "迁移结果无效"); done.countDown() }
                    }

                    override fun onEngineError(
                        epoch: Long,
                        operation: String,
                        error: IsolatedModRuntimeClient.EngineCallError,
                    ) {
                        failure.compareAndSet(null, "${error.code()}: ${error.message()}")
                        done.countDown()
                    }

                    override fun onDisconnected(epoch: Long, reason: String) {
                        failure.compareAndSet(null, reason); done.countDown()
                    }
                }).also { clientRef.set(it) }
            if (connected.get()) loadWhenConnected()
            // The runtime applies a native CPU watchdog (500 ms for migration). Do not
            // classify host scheduling or Binder delivery delay as Mod CPU exhaustion.
            // Completion or a runtime-disconnect callback ends this wait.
            done.await()
            failure.get()?.let { throw IllegalStateException("Mod 迁移失败：$it") }
            migrationResult = result.get() ?: throw IllegalStateException("Mod 迁移没有返回新模型")
        } catch (e: InterruptedException) {
            // InterruptedException clears the flag. Preserve intent locally while we
            // wait for closeAsync's real destroy/unbind/slot-release confirmation.
            restoreInterrupt = true
            migrationFailure = IllegalStateException("Mod 迁移被中断；旧版本和模型保持不变", e)
        } catch (e: Exception) {
            migrationFailure = e
        } finally {
            val closeCompletion = runCatching { client?.closeAsync() }.getOrElse { closeError ->
                migrationFailure = ModRuntimeCloseUnconfirmedException(closeError)
                null
            }
            if (closeCompletion != null) {
                // Future.get throws immediately if the current thread's interrupt
                // flag is set, so retain that flag out-of-band and retry only the
                // wait—not the runtime close transaction—until confirmation.
                while (true) {
                    try {
                        closeCompletion.get()
                        break
                    } catch (e: InterruptedException) {
                        restoreInterrupt = true
                    } catch (e: java.util.concurrent.ExecutionException) {
                        migrationFailure = ModRuntimeCloseUnconfirmedException(e.cause ?: e)
                        break
                    }
                }
            }
            callbackExecutor.shutdownNow()
            if (restoreInterrupt) Thread.currentThread().interrupt()
        }
        migrationFailure?.let { throw it }
        return migrationResult ?: throw IllegalStateException("Mod 迁移没有返回新模型")
    }

}
