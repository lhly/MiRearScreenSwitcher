# Android SDK 接入指南

这份指南覆盖 SDK `0.4.0` 的接口：查询背屏信息与能力、用户确认后打开一个独立 rear Activity、读取用户授权的平台数据，以及登记和逐次确认调用快捷操作、独立授权后发布自己的背屏通知。它不代表 cast、clock 或 FULL/region 能力已开放。

## 1. 引用本地 AAR

把交付包中的 `platform-sdk-0.4.0.aar` 放入 Android app module 的 `libs/`，然后声明文件依赖：

```kotlin
dependencies {
    implementation(files("libs/platform-sdk-0.4.0.aar"))
}
```

ReMRSS 没有为这个 AAR 配置 Maven 发布。AAR 无额外 AndroidX 或第三方运行库依赖；它的 Kotlin API 使用 Kotlin standard library 类型，Kotlin Android plugin 会自动提供该库。AAR Kotlin metadata 为 2.4.0；Kotlin Gradle Plugin 2.4.20 是此 sample 已验证的消费者编译器，其他版本未由本次验证覆盖。宿主源码和主仓库的 Gradle project 都不需要进入消费者工程。源码仓库的双屏示例在 `sdk/sample/`；交付包把它原样导出为 `sample/`，并提供预览APK `sample/apk/rear-sdk-sample-debug.apk`。`sdk/sample/standalone` 是独立 Gradle 模板；样例工程使用 Android Gradle Plugin 9.4.1、Gradle 9.7.1、Kotlin Android plugin 2.4.20、compileSdk 37、minSdk 24、targetSdk 36。由于该模板采用外部 Kotlin Android plugin，它显式关闭 AGP 内建 Kotlin 和新 DSL；使用其 Gradle Wrapper 和 Android SDK 37 编译即可。

样例默认绑定生产 host `top.zainzh.rearscreen`。本机确实运行另一个对应 Host 调试包时，才显式覆盖：

```sh
./gradlew :app:assembleDebug -PremrssHostPackage=top.example.rearscreen.debug
```

此属性同时替换样例自己的窄范围 `<queries><package .../></queries>` 和 SDK 服务目标；AAR 自身声明的生产 Host 可见性仍会合并保留。不设置时样例不会误连调试包。样例 APK 使用自己的 debug 签名，不需要 Host 私钥。Host package 仅是连接目标，不会改变 Binder 校验 caller UID/签名，也不赋予 privileged backend 权限。

## 2. 在 Application 持有一个 client，查询实际设备状态

`RearClient` 应由应用级对象持有，在相关功能仍使用时保持连接；不要为每个 Activity 各建一个 client。连接协商 `remrss.platform/1`。连接后分别查询 capability 与当前背屏信息：前者报告能力及可用性，后者的 `panel` 可为空；不要凭机型、display ID 或 SDK 版本推断硬件存在。

```kotlin
class GameApplication : Application() {
    lateinit var rear: RearClient
        private set

    override fun onCreate() {
        super.onCreate()
        rear = RearClient(this) { event -> handleRearEvent(event) }
        rear.connect { connected ->
            connected.onSuccess {
                rear.queryCapabilities { result ->
                    result.onSuccess { caps ->
                        // 检查 caps.contract、deviceAvailable、backendConnected 和逐项 capabilities。
                    }
                }
                rear.queryRearInfo { result ->
                    result.onSuccess { info ->
                        val panel = info.panel // null 表示当前无法提供面板信息
                    }
                }
            }
        }
    }
}
```

`RearInfoSnapshot.panel` 描述物理模式栅格（若可用）、当前逻辑像素尺寸、density、rotation 和 display 状态；逻辑尺寸可能因系统尺寸覆盖而不同于模式栅格。它不是 app 的最终窗口尺寸。实际 Scene 窗口以 Ready/viewport report 为准。回调默认投递到主线程；不兼容服务、服务不可用、断连和前台条件不满足会分别返回 `E_CONTRACT_INCOMPATIBLE`、`E_SERVICE_UNAVAILABLE`/`E_SERVICE_DISCONNECTED`、`E_NOT_CONNECTED` 或 `E_FOREGROUND_REQUIRED`。展示真实错误并让用户按需重试，不要用轮询维持 Binder。

## 3. 只有提供 rear Activity 的应用才声明 Scene 入口

Scene 要求应用和传给 `RearSceneRequest` 的确切 Activity 都声明 rear policy。仅用 capability/info/data 的应用不需要这些声明。

```xml
<application ...>
    <meta-data android:name="miui.rear.policy" android:value="1" />

    <activity
        android:name=".RearSceneActivity"
        android:exported="true"
        android:excludeFromRecents="true"
        android:taskAffinity="${applicationId}.rear"
        android:launchMode="standard"
        android:allowTaskReparenting="false"
        android:showWhenLocked="true"
        android:configChanges="orientation|screenSize|keyboardHidden|screenLayout|uiMode|smallestScreenSize|density|fontScale">
        <meta-data android:name="miui.rear.policy" android:value="1" />
    </activity>
</application>
```

Host 以显式组件在独立背屏 task 启动该 Activity。保持 `standard` launch mode、独立 task affinity、默认 document launch mode 和 `allowTaskReparenting=false`；Host 根据新建 task 的归属验证 Scene，不支持把既有 task 重新挂载过来。Activity 必须 exported 以接受 Host 的显式启动，但启动 ticket 只允许该次 Host 授权的 Activity 使用。

从用户明确点击的前台操作请求 Host 确认；不要在后台自动弹出授权或自行选择 rear display：

```kotlin
rear.requestSceneActivation(
    activity,
    RearSceneRequest(ComponentName(activity, RearSceneActivity::class.java), keepVisible = false)
) { result ->
    result.onFailure { showError(it.message) }
}
```

Host 会向用户说明应用与组件，并让用户确认。授权通过不等于窗口已 Ready、可见或常亮。`keepVisible=false` 是建议默认：一次明确确认可在目标背屏休眠时唤醒该屏用于本次显示，但不会唤醒主屏或循环点亮。`keepVisible=true` 只在同一有效 Scene lease、Host 允许显示、并且 rear window 确实可见时保持屏幕；它不是 Scene 启动条件，也不是默认常亮开关。

## 4. Ready 后持续报告实测 viewport 与可见性

第一次 layout 后，从 ticket 对应的 Activity 上报窗口 Ready。不要在 `onCreate`、`setContentView` 前或普通 Activity 中报告：

```kotlin
window.decorView.post {
    val ticket = intent.getStringExtra(RearClient.EXTRA_LAUNCH_TICKET)
        ?: return@post finishAndRemoveTask()
    rear.reportWindowReady(this, ticket) { result ->
        result.onFailure { showError(it.message) }
    }
}
```

应用级 event handler 处理 `accepted` 中的 `sessionId`、`generation`、`launchTicket` 和 `keepVisible`，并将这份 lease 状态交给 rear Activity。Ready 是 Host 对该 ticket Activity 的窗口测量与 task 校验通过，不等于可见。Activity 首次 attach 或配置重建后，用同一 lease 重新挂 observer，再报告当前真实 viewport；不要再次请求新 Scene 或重放 ticket：

```kotlin
rear.attachObserver(sessionId, generation) { attached ->
    attached.onSuccess { result ->
        if (result.success) {
            // 只有新的、完成布局的 Activity window 才创建新 viewport revision。
            rear.reportViewport(this, sessionId, generation, nextRevision()) { report ->
                report.onSuccess { accepted -> if (!accepted.success) showError(accepted.error) }
            }
        }
    }
}
```

在真实布局、配置或 display 尺寸变化时重新测量并递增 revision。不要以 `onResume`、focus 或任务存在替代 viewport。可见性也要独立报告：仅当目标 display 与实际窗口 attachment/visibility/bounds 证明窗口可见时传 `true`；窗口离屏、遮挡或 detached 时传 `false`。`ready`、focus 和 task identity 都不是“正在显示”的证明。

```kotlin
rear.reportVisibility(sessionId, generation, measuredRearWindowVisible) { result ->
    result.onSuccess { if (!it.success) showError(it.error) }
}
```

`visibilityChanged`、`suspended`、`restored` 是重新核对状态的提示，不是可长期缓存的授权。收到事件后查询当前 Core presentation，拒绝旧 revision；Activity 每次 start/stop 建立/失效请求 epoch，避免旧窗口迟到的查询重新打开新窗口。内容绘制/采样应同时受当前 presentation visible 与本地窗口实测可见性约束；变为 hidden 时立即遮成纯黑并停止自身绘制和采集。纯黑遮挡不等于硬件息屏，仍可能耗电。不要用焦点变化单独推断背屏被隐藏。

```kotlin
private val presentationGate = RearScenePresentationGate(sessionId, generation)
private val requestEpoch = RearScenePresentationRequestEpoch()

private fun reconcilePresentation() {
    val epoch = requestEpoch.begin()
    rear.queryScenePresentation(sessionId, generation) { result ->
        if (!requestEpoch.isCurrent(epoch) || !activityStarted) return@queryScenePresentation
        result.onSuccess { current ->
            if (presentationGate.apply(current)) {
                renderScene(current.visible && measuredRearWindowVisible)
            }
        }
    }
}

override fun onStop() {
    activityStarted = false
    requestEpoch.invalidate()
    super.onStop()
}
```

Host 可在授权 Scene 仍存活时暂时遮挡内容；收到恢复提示后先 query 当前 presentation 再恢复自有画面。`keepVisible` 只由当前 lease 与实测窗口条件控制。系统安全状态无法证明时，Host 可能有界结束该 Scene；重新使用时需由用户再次发起，不要自动重放 ticket。硬件窗口、触摸与 OEM 行为按已验证的设备范围理解，不能推广为所有 ROM 保证。

## 5. 双屏使用同一份应用状态，不跨屏复制业务状态

主屏与 rear Activity 都读取/更新应用自己的共享状态；各自只在可见生命周期内订阅，并在 `onStop` 撤销该 observer。不要用 Binder 轮询、持久化“镜像副本”或后台线程让隐藏页面保持刷新。

```kotlin
// Application owns the value; both Activities call addPoint() and observeScore().
override fun onStart() {
    super.onStart()
    scoreSubscription = app.observeScore { score -> runOnUiThread { render(score) } }
}

override fun onStop() {
    scoreSubscription?.close()
    scoreSubscription = null
    super.onStop()
}
```

样例 `/sdk/sample` 是一个可运行的双屏记分牌：主屏和 rear Activity 都能加分，更新来自同一个 Application-owned `ScoreboardState`；rear observer 随 Activity 生命周期启停。保留现有用户界面和业务状态归属即可，不需要把 SDK UI 塞入 Host 设置页面。

## 6. 数据权限只在前台征求，订阅跟随可见页面

数据 API 与 Scene 和 privileged backend 分离。仅在用户前台动作中请求明确类别；Host 显示选择页并保存按 caller package 与签名证书区分的授权。只查询已授权类别。观察仅用于当前可见 UI，离开页面关闭订阅；Host 至少按 2 秒间隔采样。GPU 利用率可返回 `UNSUPPORTED`，不可用/待采样的数据通过 `RearDataValue.Unavailable` 表示，不要伪造数值。

使用请求代际同时门控等待中的请求和活动订阅：页面停止时递增代际并关闭当前订阅；旧快照和错误不再更新界面，迟到的成功回调立即关闭新返回的订阅。这样 A→停止→重新显示并请求 B 时，A 不会覆盖 B 的订阅句柄。

```kotlin
import java.io.Closeable

private var batteryObservation: Closeable? = null
private var activityStarted = false
private var batteryObservationRequested = false
private var dataObservationEpoch = 0L

private fun observeBatteryWhileVisible() {
    if (batteryObservationRequested) return
    batteryObservationRequested = true
    val requestEpoch = ++dataObservationEpoch
    rear.observeData(
        setOf(RearDataField.BATTERY_LEVEL_PERCENT, RearDataField.BATTERY_CHARGING),
        intervalMs = 5_000L,
        onSnapshot = { snapshot -> runOnUiThread {
            if (activityStarted && batteryObservationRequested && requestEpoch == dataObservationEpoch) {
                renderBattery(snapshot)
            }
        } }
    ) { result -> runOnUiThread {
        result.fold(onSuccess = { subscription ->
            if (activityStarted && batteryObservationRequested && requestEpoch == dataObservationEpoch) {
                batteryObservation = subscription
            } else {
                subscription.close()
            }
        }, onFailure = { error ->
            if (requestEpoch == dataObservationEpoch) {
                batteryObservationRequested = false
                showError(error)
            }
        })
    } }
}

override fun onStart() {
    super.onStart()
    activityStarted = true
}

override fun onStop() {
    activityStarted = false
    batteryObservationRequested = false
    dataObservationEpoch++
    batteryObservation?.close()
    batteryObservation = null
    super.onStop()
}
```

`queryData` 没有隐式弹窗；尚未授权、样本待取或来源不可用时，向用户展示接口返回的原因。若用户需要变更或撤销类别，可在新的前台动作中重新请求相关类别。订阅由当前 Binder client epoch 持有，关闭 subscription 或释放 client 都会清理它。

## 7. 明确关闭与重建

用户主动关闭 Scene 时调用 `closeScene(sessionId)`；Host 的 `closed` event 用于清理对应 lease。Activity 因旋转/配置改变而重建不是用户关闭：保留 Application 中的 `sessionId`/`generation`/revision，在新窗口 attach observer、重新测量并报告 viewport；不要重复激活。

在应用真正释放平台连接时才调用 `RearClient.close()`。它释放该 client epoch 的 sessions 与数据订阅；不要因为单个 Activity `onStop` 就关闭应用级 client。SDK callback/event 代表当前连接一代，断开或 client 关闭后应停止采用迟到结果。

## 8. 使用用户登记的快捷操作

快捷操作由用户从本机已安装应用或受支持的快捷操作提供器中登记。登记时，Host 保存显式目标组件、创建者签名和有界参数；SDK 只收到不可执行的 opaque ref，不会拿到任意 `Intent`、Shell 或 Root/Shizuku 接口。每个 publisher 只能使用自己登记的 ref。

先从前台页面打开 Host picker 并读取 Activity result。Picker 会说明授权应用仅调用用户为它登记的快捷操作；取消时 result 中没有 ref。

```kotlin
private val actionPicker = registerForActivityResult(
    ActivityResultContracts.StartIntentSenderForResult()
) { result ->
    val actionRef = RearRegisteredActionRef.fromActivityResult(result.data)
    if (actionRef != null) saveSelectedAction(actionRef)
}

private fun chooseRegisteredAction() {
    rear.requestRegisteredActionSelection { result ->
        result.onSuccess { pending ->
            actionPicker.launch(IntentSenderRequest.Builder(pending.intentSender).build())
        }.onFailure(::showError)
    }
}
```

调用时再次请求 Host 确认 PendingIntent，并在用户确认后由 Host 重新校验 ref 所属 publisher、撤销状态、目标可用性和当前签名。应用只在 `RearClient` 的事件回调中接收完成结果：`type == "registeredActionInvocation"`，其中 `accepted` 表示系统接受了显式 Activity 启动请求，不代表目标应用完成了后续业务。

```kotlin
rear.requestRegisteredActionInvocation(actionRef) { result ->
    result.onSuccess { pending ->
        actionPicker.launch(IntentSenderRequest.Builder(pending.intentSender).build())
    }.onFailure(::showError)
}
```

旧版 Host 仍可使用旧 SDK 已有的 Scene 和数据接口；当 Host capability snapshot 不含 `action.invoke` 时，新动作入口返回 `E_UNSUPPORTED`，不影响客户端连接。

## 本版可用范围

0.4.0 提供：rear info/能力查询；前台用户确认的独立 Scene；Ready、viewport、可见性、当前 presentation 与受约束的 keep-visible；battery 与 system metrics 的授权、查询和可见订阅；用户登记、publisher 隔离并逐次确认的快捷操作；以及每个发布者独立授权、仅作用于自身命名空间的自有背屏通知。通知受有限 TTL、配额和宿主显示策略约束。它不提供 cast session、clock、FULL/region，也不嵌入 Root/Shizuku/QuickJS/Miuix/ROM 实现。请按运行时 capability 与返回状态处理目标设备，不把未开放能力包装成可用接口。


## 9. 发布自己的背屏通知

这是自有通知 API，不是系统通知镜像。它不要求应用启动前台服务，也不承诺第三方进程锁屏后后台常驻、定时唤醒或可靠运行；从应用当前有效的 `RearClient` 连接发起请求。只有用户对当前应用及其签名明确授予发布权后，通知才会进入宿主队列。它与“选择哪些 App 的系统通知可镜像”是两项独立授权。前台 Activity 调用授权入口会打开宿主的 Miuix 确认页；用户可在同一页面关闭已有授权。SDK 的 `onEvent` 收到 `type == "notificationAuthorization"` 表示用户的选择已生效，`data.granted` 是最新状态。

```kotlin
rear.requestNotificationAuthorization(activity) { result ->
    result.onFailure(::showError) // 成功仅表示授权页已打开
}
```

发布请求仅包含有界纯文本。相同连接与 ID 的再次发布会更新该项；关闭 client 只清理该连接发布的项目。Host 按 Binder UID、包名、签名证书和连接代际认领发布者，不接受调用方传入所有者。每条通知 TTL 为 1–15 秒（默认 8 秒）；每发布者最多 4 项、全局最多 16 项，并实施每 10 秒 10 次、每秒突发 4 次限速。超过额度返回明确错误，不会悄悄替换队列中的其他项目。

```kotlin
val notice = RearNotificationRequest(
    id = "timer.main", // 只在本 SDK client 的 namespace 内去重
    title = "计时结束",
    text = "可以查看结果",
    durationMs = 8_000,
)
rear.publishNotification(notice) { result ->
    result.onSuccess { outcome ->
        when (outcome.status) {
            RearNotificationSubmitStatus.ACCEPTED -> showStatus("已进入宿主队列")
            RearNotificationSubmitStatus.SUPPRESSED -> showStatus("当前策略未显示：${outcome.reason}")
            RearNotificationSubmitStatus.REJECTED -> showStatus("请求被拒绝：${outcome.reason}")
        }
    }.onFailure(::showError)
}
```

`ACCEPTED` 只表示进入有界队列，不是已显示。通过 `RearPlatformEvent.notificationEvent` 观察后续结果：`DISPLAYED` 表示宿主确认通知 Activity 窗口在目标显示区可见，或通知胶囊完成可见布局；它不等同于证明某块物理面板已点亮或由面板真实呈现。`SUPPRESSED` 会说明全局开关、勿扰、撤权或显示策略原因；`ENDED`/`FAILED` 带有 `endedBy` 结束原因。临时通知/充电覆盖会在 TTL 未到期时恢复仍有效的自有通知；用户显式切屏/投放不会被通知重新夺回。

```kotlin
RearClient(this) { event ->
    event.notificationEvent?.let { noticeEvent ->
        when (noticeEvent.status) {
            RearNotificationEventStatus.DISPLAYED -> showStatus("已在背屏显示 ${noticeEvent.id}")
            RearNotificationEventStatus.SUPPRESSED -> showStatus("未显示：${noticeEvent.reason}")
            RearNotificationEventStatus.ENDED, RearNotificationEventStatus.FAILED ->
                showStatus("${noticeEvent.id} 已结束：${noticeEvent.endedBy}")
        }
    }
}
```

`cancelNotification(id)` 只取消同一 client epoch 名下的 ID；未知 ID 返回 `REJECTED/NOT_FOUND`。关闭 client、Binder callback 死亡、连接代际替换或撤销授权，会清理该连接自己的通知，不影响其他发布者、系统通知、充电提示或场景。宿主全局通知开关、系统勿扰和隐私设置始终优先。SDK 不读取镜像系统通知，不请求通知监听权限，不支持任意图像、URI、PendingIntent、跳转动作或后台轮询/常亮。
