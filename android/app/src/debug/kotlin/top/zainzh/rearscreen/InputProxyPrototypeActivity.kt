package top.zainzh.rearscreen

import android.content.*
import android.content.pm.PackageManager
import android.os.*
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import rikka.shizuku.Shizuku
import top.yukonga.miuix.kmp.basic.TextButton

/** Local opt-in only, absent from release. */
class InputProxyPrototypeActivity : ComponentActivity() {
    private var enabled by mutableStateOf(false)
    private var status by mutableStateOf("等待连接 Shizuku")
    private var virtualStatus by mutableStateOf("{}")
    private var service by mutableStateOf<ITaskService?>(null)
    private val statusHandler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() { refresh(); statusHandler.postDelayed(this, 1000) }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = ITaskService.Stub.asInterface(binder); refresh()
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null; status = "Shizuku 已断开" }
    }
    private val refreshing = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun refresh() {
        val remote = service ?: return
        if (!refreshing.compareAndSet(false, true)) return
        Thread {
            try { val result = runCatching { remote.rearInputProxyStatus }.getOrElse { it.toString() }
                val virtual = runCatching { remote.rearVirtualCastStatus }.getOrDefault("{}")
                runOnUiThread { status = result; virtualStatus = virtual }
            } finally { refreshing.set(false) }
        }.start()
    }
    private fun startTouchTest() {
        val remote = service ?: return
        enabled = true
        RearInputProxySession.setEnabled(this, true)
        Thread {
            runCatching {
                remote.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP")
                remote.executeShellCommand("am start --display 1 -n $packageName/${FixtureInputActivity::class.java.name}")
                val deadline = SystemClock.uptimeMillis() + 4000
                while (FixtureInputActivity.instance == null && SystemClock.uptimeMillis() < deadline) Thread.sleep(50)
                val fixture = FixtureInputActivity.instance ?: error("背屏测试页未启动")
                val keeper = Intent(this, RearScreenKeeperService::class.java)
                    .putExtra("lastMovedTask", "$packageName:${fixture.taskId}")
                    .putExtra("keepScreenOnEnabled", false)
                startForegroundService(keeper)
            }.onFailure { runOnUiThread { status = it.toString() } }
            Thread.sleep(600); refresh()
        }.start()
    }
    private fun stopTouchTest() {
        enabled = false
        RearInputProxySession.setEnabled(this, false)
        stopService(Intent(this, RearScreenKeeperService::class.java))
        Thread {
            runCatching { service?.stopRearInputProxy(null) }
            runOnUiThread { FixtureInputActivity.instance?.finish() }
            refresh()
        }.start()
    }
    override fun onResume() {
        super.onResume(); enabled = RearInputProxySession.enabled(this)
        statusHandler.post(poll)
    }
    override fun onPause() { statusHandler.removeCallbacks(poll); super.onPause() }
    private fun addDebugTile() {
        if (Build.VERSION.SDK_INT >= 33) getSystemService(android.app.StatusBarManager::class.java)
            .requestAddTileService(ComponentName(this, SwitchToRearTileService::class.java), getString(R.string.tile_label),
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_tile_switch_display), mainExecutor) { }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enabled = RearInputProxySession.enabled(this)
        TaskServiceManager.acquire(this, connection)
        miuixContent {
            PageFrame("背屏投放调试", ::finish, "input_proxy_back") {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val snapshot = runCatching { org.json.JSONObject(status) }.getOrNull()
                    val running = snapshot?.optString("state") == "running"
                    val phase = when {
                        service == null -> "等待 Shizuku 连接"
                        running -> "物理直投代理运行中"
                        !enabled -> "物理直投代理未启用"
                        snapshot?.optString("reason") !in listOf(null, "", "stopped") -> "代理已停止：${snapshot?.optString("reason")}"
                        else -> "已启用，等待调试版投放"
                    }
                    val virtual = runCatching { org.json.JSONObject(virtualStatus) }.getOrNull()
                    val virtualRunning = virtual?.optString("state") == "running"
                    val virtualInput = virtual?.optJSONObject("input")
                    AppCard {
                        RearCastModeToggle()
                        AppDivider()
                        AppRow("投放状态", if (virtualRunning) "虚拟显示：${virtual?.optString("package")}"
                            else if (RearScreenKeeperService.isPhysicalCastRunning()) "原有直接投放运行中" else "当前没有投放会话")
                        AppRow("磁铁自动化", "吸附：mrss-debug://switch?current=1\n移除：mrss-debug://return?current=1\n需指定「ReMRSS 调试版」；mrss:// 仍使用正式版。")
                        if (virtualRunning) AppRow("实际转发", "已读取 ${virtualInput?.optLong("frames")} 帧，转发 ${virtualInput?.optLong("events")} 个触摸事件")
                        AppRow("使用范围", "磁铁与「背屏投放（调试版）」磁贴统一按模式开关运行。虚拟模式当前仅支持原始方向，DPI 参数只作用于虚拟显示。")
                    }
                    TextButton("停止虚拟投放并恢复", {
                        val remote = service ?: return@TextButton
                        Thread {
                            VirtualCastUriFlow.handle(this@InputProxyPrototypeActivity, remote, android.net.Uri.parse("${BuildConfig.URI_SCHEME}://return?current=1"))
                            refresh()
                        }.start()
                    }, enabled = virtualRunning)
                    AppCard {
                        AppRow("独立输入测试", "仅下方测试按钮启用物理输入对照，不影响正常投放模式。直接投放不会接管背屏触摸。")
                        AppRow("运行状态", phase)
                        if (running) AppRow("实际转发", "已读取 ${snapshot?.optLong("frames")} 帧，转发 ${snapshot?.optLong("events")} 个触摸事件")
                        if (!BuildConfig.INPUT_PROXY_PROTOTYPE) AppRow("原型构建", "当前构建未包含读取器")
                    }
                    TextButton("授权 Shizuku", { runCatching {
                        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) Shizuku.requestPermission(704)
                    }.onFailure { status = it.toString() } })
                    if (Build.VERSION.SDK_INT >= 33) TextButton("添加调试版投放磁贴", ::addDebugTile)
                    TextButton("刷新状态", ::refresh)
                    TextButton("开始背屏手势测试", ::startTouchTest, enabled = BuildConfig.INPUT_PROXY_PROTOTYPE && service != null)
                    TextButton("停止测试并恢复", ::stopTouchTest)
                }
            }
        }
    }
    override fun onDestroy() { TaskServiceManager.release(connection); super.onDestroy() }
}
