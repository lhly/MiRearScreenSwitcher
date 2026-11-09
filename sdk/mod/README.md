## ReMRSS Mod SDK kit 1.2.0

**RC4 本地候选**：自有通知接口加入独立 `rear.notification@1` 能力、Mod Effect、TS helper、CLI/JSON Schema 校验，以及可打包的 `notification` ZIP 示例。RC3 原有 Mod 接口保持兼容；RC4 此处为本地实现，不代表已发布。

从普通 ZIP 开始：Mod 用 JSON 声明两个原生场景；只有确实需要自定义业务规则时才附带 `main.js`。宿主按同一 manifest、签名和载荷摘要校验安装包。无脚本包不会启动 JavaScript runtime。

## 快速开始

需要 Python 3.10+、OpenSSL（只用于作者密钥签名/验证）；含 reducer 的开发测试还需要 Node.js 18+。Node 只跑作者本机的确定性规则测试，不进入 Android 包，也不替代宿主 QuickJS。

```sh
./bin/remrss create sample-panel --template info --author "Your name"
./bin/remrss check sample-panel
./bin/remrss test sample-panel
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out author-private.pem
./bin/remrss pack sample-panel --key author-private.pem --out sample-panel.zip
./bin/remrss check sample-panel.zip
```

`tests/config-changes.json` 可单独覆盖配置保存事件：用例显式提供已初始化 state、previousConfig 和新 config，并断言派生 state/config，或断言 reducer 拒绝保存。该事件由宿主保留，不能放进普通 `events`。`tests/cases.json` 的单个 reducer 用例可传自己的 `config`；未提供的项使用 `configSchema` 中的默认值，之后会按同一 schema 校验。该配置对象会传给本用例的 `init` 和每次 `reduce`，未知字段、缺少必填项或越界值会让 `remrss test` 失败，而不是被忽略：

```json
{"name":"自定义起分和步长","config":{"start":10,"step":3},"events":[{"type":"counter.increment"}],"expected":{"count":13}}
```

请在自己的密钥管理器中保存私钥；不要提交、发给宿主维护者或放入 ZIP。`pack` 从私钥导出公钥，仅写入 `author.pub`，生成 `manifest.sig` 后再执行完整包验签回读。

模板：`blank` 是真正空 state、且不启动 VM 的声明式页面；`info` 是复用宿主电量来源的无脚本面板；`counter` 是中性的通用 reducer 演练，不含计分牌规则或宿主专用业务分支；保存“每次增减”后，它通过 `host.configChanged` 原子更新用于界面显示的派生状态，不需要再点一次“重新计算”；`notification` 是带发布、更新、取消按钮和真实结果状态的独立通知示例。四个模板都声明必需的 `rear.scene@1`，使其默认 rear scene 在用户授权后可投放；`rear.scene.keep_visible@1` 是可选声明，`notification` 的 `rear.notification@1` 也默认可选。声明从不代表用户已授权，用户仍会在导入时逐项选择。


## 保存配置并更新派生状态

对 `runtime.kind: "quickjs"`，设置页点“保存设置”会排入一个宿主保留的 reducer 事件。事件输入包含当前已提交 `state`、经过 `configSchema` 校验的新 `config`、旧已提交值 `previousConfig`，以及事件元数据：

```js
reduce({ state, config, previousConfig, event }) {
  if (event.type === 'host.configChanged') {
    return { state: { ...state, stepUsed: config.step, previousStep: previousConfig.step } };
  }
  if (event.type === 'counter.increment') return { state: { ...state, count: state.count + config.step } };
  return { state };
}
```

宿主校验 reducer 返回的 state 后，将 state 与新 config 在同一 revision 提交，再通知所有设置/背屏观察者。脚本异常拒绝配置时只拒绝本次 Save：旧 state/config 与逻辑场景观察者保留；为覆盖脚本异常和 native 分配/栈失败的共同错误码，宿主会先确认关闭旧 worker，再从已提交模型启动新 worker。设置页显示错误并保留草稿；新 worker 恢复后可在同一页重试。关闭或恢复失败、预算耗尽、worker/transport 故障仍按终态路径收尾。

配置事件返回的 Effect 也必须经过类型、能力和来源预校验，且只有事务提交后才派发。Save 本身不产生用户激活：当前 `rear.open` 需要明确的用户手势，因此在配置更新中会被拒绝且不投放；这不代表所有未来 Effect 类型一律禁用。CLI 用例可断言 reducer 产生的 Effect 意图，但只是作者侧 reducer 检查，不代表宿主授权、激活或实际派发已通过。无脚本 Mod 的保存仍直接校验并提交 config，不启动 QuickJS；首次 init 规则不变，配置事件不会重跑 init。

作者侧测试把这类用例放入 `tests/config-changes.json`，用 `remrss test <project>` 验证成功派生和拒绝分支。CLI 会验证输入/期望值符合 manifest schema、期望 config 等于提交的新 config，并拒绝将 host 保留事件写在普通事件用例中。

### 自有背屏通知

`rear.notification@1` 是 Mod 独立能力，不继承通知监听器的来源 App 选择。用 `host.notification.publish({id, title, text?, durationMs?, priority?, privacy?})` 发布或更新自己的通知，用 `host.notification.cancel(id)` 取消自己的通知；底层Effect身份由宿主从当前已验证Mod安装记录注入，JS 不接受 owner/package/certificate。标题为 1–80 个 Unicode 字符，正文为 0–240 个字符，允许正文换行；纯文本请求不超过 2 KiB。时长默认 8000ms、范围 1000–15000ms；队列每 owner 最多 4 条，宿主负责速率、TTL、去重、全局开关、隐私、勿扰与覆盖恢复。

SDK 中的 `host.notification` 方法只构造普通 JSON Effect，不访问宿主服务；在手写 `main.js` 时，返回等价的 JSON Effect 字面量即可，宿主仅在 reducer 事务提交后经 broker 校验并派发。

`host.effectResult` 的 `accepted` 只说明请求已入队；`suppressed` 和 `rejected` 分别表示策略抑制与请求失败。后续 `host.notificationResult` 以 `DISPLAYED` 表示宿主已取得真实窗口可见证据，另可报告 `SUPPRESSED`、`ENDED`、`FAILED`、原因及结束原因。该 host 事件不能由 reducer 或按钮伪造。宿主不为通知启动常驻 worker；Mod 停用、卸载、撤权或故障时清理其自有项。

```sh
./bin/remrss create my-notification --template notification --author "示例作者"
./bin/remrss test my-notification
./bin/remrss pack my-notification --key author-private.pem --out my-notification.zip
```

`notification` 模板包含发布/更新/取消三个后屏按钮和可见状态；`remrss test` 的 `expectedEffects` 仅核对 reducer 意图，实际是否显示以 `host.notificationResult` 为准。

## 包格式与信任

ZIP 条目仅含 `manifest.json`、`manifest.sig`、`author.pub` 及 manifest 列出的载荷。`manifest.json` 必须是 UTF-8 原始字节；签名采用 RSA-SHA256。作者公钥 DER 的 SHA-256 指纹在已签 manifest 的 `author.keyFingerprint` 中绑定；每个可执行/渲染载荷都列路径、字节数和 SHA-256。宿主只接受此格式 `remrss.mod` / `formatVersion: 1`，不读取旧 `.remodule`/schema 1。

```json
{
  "format": "remrss.mod",
  "formatVersion": 1,
  "id": "sample-panel",
  "name": "Sample panel",
  "version": "0.1.0",
  "versionCode": 1,
  "author": {"name": "Your name", "keyFingerprint": "由 pack 填入的 64 位小写十六进制"},
  "platform": {"api": 1},
  "minimumAndroidApi": 24,
  "capabilities": {"required": [{"name": "data.battery", "minimumVersion": 1}], "optional": []},
  "permissions": {"required": [], "optional": []},
  "scenes": {"settings": "settings.ui.json", "rear": "rear.ui.json"},
  "runtime": {"kind": "none"},
  "data": {"stateSchema": {"type":"object","properties":{},"additionalProperties":false}, "configSchema": {"type":"object","properties":{},"additionalProperties":false}, "dataVersion": 1},
  "payloads": []
}
```

`minimumVersion` 与 core `CapabilityRequirement(name, minimumVersion)` 同形；当前工具开放 `rear.scene@1`、`rear.scene.keep_visible@1`、`rear.notification@1`、`data.battery@1`、`data.systemMetrics@1`、`action.invoke@1`、`clock.snapshot@1`、`schedule.visible@1`，以及宿主管理的 `model.local@1`、`ui.native@1`、`ui.dialog@1`。`data.battery` UI binding 为 `data.battery.level`（百分比）、`data.battery.scale`（100）和 `data.battery.charging`；`data.systemMetrics` UI binding 为 `data.systemMetrics.cpu.utilizationPercent`、`data.systemMetrics.memory.availableBytes` 与 `.totalBytes`。数据只在获授权且页面可见时供原生场景绑定使用，关闭订阅即清空旧值，不默认传入脚本；GPU字段目前不受支持。设计文档 §4.1 中其余能力仍在实现/验收中，不会因为名字已列在设计表就被工具当作可用。设备能力、后端连接、作者信任和用户逐项授权仍由宿主分别判断；声明不是授权。

上限：ZIP 2 MiB、展开 8 MiB、64 条目、`main.js` 512 KiB、单场景 UI 64 KiB；拒绝路径穿越、重复条目、符号链接、未知 UI 组件/字段和未声明载荷。状态/配置 schema 只支持有界 JSON 基础类型与 object/array 子结构；UI binding 只能引用 `state.*`、`config.*` 或版本化公开 `data.*` 路径，不接受表达式。

## 原生场景与业务规则

`settings.ui.json` 与 `rear.ui.json` 声明同一套公开节点。容器为 `layout.column`、`layout.row`、`layout.box`；常用节点包括 `ui.text`、`ui.number`、`ui.button`、`ui.divider`、`ui.progress` 以及受限 Miuix `settings.*` 节点。`layout.row` 会先扣除各子项之间的 `gap`，再把剩余宽度等分为有界子项；子树（包括 `layout.column` 和铺满宽度的按钮）只能在自己的格内排版，不会把兄弟挤出屏幕。需要并排显示成组信息与控件时，可让每个 Row 子项使用一列；子项过多时优先拆分为多行。按钮只发命名 event；renderer 不提供任意宿主函数。

```json
{"scene":"rear","root":{"type":"layout.column","gap":12,"children":[
  {"type":"ui.number","value":{"bind":"state.count"},"style":"displayNumber"},
  {"type":"ui.button","label":"+1","onPress":{"type":"event","name":"counter.increment"}}
]}}
```

仅 `runtime.kind: "quickjs"` 的 Mod 才可包含 `main.js`。worker 公共 ABI 为单文件 `globalThis.__remrss = { init(context), reduce(context), migrate?(context) }`；`migrate` 仅在 `dataVersion` 改变时调用。首次安装不执行脚本；首次激活且确无模型时 `init` 创建初始 state，worker 重建和同版本更新均不重跑 `init`。每个 event 顺序接收已提交 state/config 与宿主标记的 event；`reduce` 返回 `{state, effects?}`。宿主先限界校验并事务提交状态，提交成功后才执行 Effect。升级 `dataVersion` 必须有 `migrate({state, config, fromVersion, toVersion, env})`，返回 `{state, config}` 且不含 Effect；若包从未激活，`state` 是 `null`，迁移应返回 `state: null`，由新版本 `init` 在首次激活时创建模型，例如 `if (state === null) return { state: null, config: nextConfig };`；已有 state 则必须返回符合新 schema 的 state object，宿主拒绝把已有 object迁成null。迁移用例必须显式填写`state`，省略字段不能代表null；CLI会拒绝object→null用例。非null新state和config均通过schema校验后与包指针同事务提交，失败保留旧包与模型。不提供迁移函数时拒绝数据版本升级。脚本只构造登记过的Effect，不接触Android `Context`、文件、网络、Root、Shell或Binder。

`sdk/mod/src/index.js` 提供 `buildReducerSource({init, reduce})`，将无闭包函数生成 worker 的单文件 ABI。外部依赖必须由作者构建阶段 bundle 成单文件；包内不加载运行时 import、Node API 或第三方字节码。

## 检查/预览边界

`check` 可检查源项目结构或签名 ZIP，`test` 在本机 Node 对 reducer case 做确定性测试，`pack` 输出普通 ZIP。`preview` 做 UI 与本地资源预检；实际视觉预览由 ReMRSS 内置的 Kotlin Compose/Miuix 原生 renderer 完成：导入 ZIP 并审核作者后，打开 Mod 卡片的“本机预览”。可切换设置/背屏场景、尺寸、安全区、主题、字号、旋转与纯黑遮挡，不申请rear租约，也不运行业务脚本。SDK 不提供 HTML 仿制图。

隔离 runtime 以原生 CPU watchdog 限制脚本计算：`reduce` 为 100 ms，首次 `init` 与 `migrate` 为 500 ms。系统调度和 IPC 往返不计入脚本 CPU 预算，也没有固定墙钟返回时限；超预算返回 `E_CPU_BUDGET`。

可机器读取的 JSON Schema：[`manifest.schema.json`](manifest.schema.json)（`urn:remrss:mod:manifest:v1`）、[`ui.schema.json`](ui.schema.json)（`urn:remrss:mod:ui:v1`）与[`runtime.schema.json`](runtime.schema.json)（`urn:remrss:mod:runtime:v1`）。它们仅用于本地工具引用，不请求网络。


## RC3 原生 UI、clock 与动作

UI 节点保持有界、声明式，不提供任意 Miuix API、HTML、外部 URL 或绘制代码。`layout.*` 支持对齐 token；`ui.text/number` 支持语义字号、语义 tone、条件显示与受限格式化。`ui.list` 仅接受 schema 声明的标量数组，最多显示 32 项；图标使用固定名称集合。`ui.deadlineText` 绑定 `state.*` 或 `config.*` 中的 elapsedRealtime 毫秒截止值，需要 `clock.snapshot`。它只在宿主真实可见时原生刷新；隐藏/生命周期离开暂停，恢复时重读 elapsedRealtime，不运行 JavaScript、不常亮。

clock 环境包含 `{wallTimeMs, elapsedRealtimeMs, timezoneId, bootId: null}`。`elapsedRealtimeMs` 仅在本次启动周期比较，不能持久化为跨重启截止时间。`schedule.visible` 每 owner 最多 4 个 key，周期至少 1000ms；周期与单次deadline可组合在同一key上，数值必须是非负 JavaScript 安全整数。隐藏不补发积压 tick，恢复时周期从当前可见时刻重新开始；已过期deadline最多立即触发一次。注册/取消是 Effect 意图，宿主以 `host.effectResult` 回报接受或拒绝；`host.schedule.tick` 的 `causes` 精确列出 `interval` 和/或 `deadline`。

用户登记动作由 `action.invoke@1` 控制。设置页用 `settings.action` 打开宿主选择器，把 opaque `{id,version}` 保存进一个**可选的** config 字段；后屏按钮再绑定同一字段：`onPress:{type:"registeredAction",ref:{bind:"config.launchAction"}}`。只支持顶层 `config.*` 引用，不能绑定 `state.*`；字段 schema 必须严格声明登记生成的 48 位小写十六进制 `id` 与 `version:1`。尚未选择动作时按钮禁用并提示去设置页选择。宿主只在真实原生点击时请求执行，并再次核对能力授权、Mod owner 和该 owner 名下登记引用。动作引用不携带 Intent 或可执行目标。QuickJS reducer 产生的 `host.action.invoke(ref)` 仍须宿主验证用户激活。

## Debug 同作者重载

正常安装/升级使用 `pack` 与递增 `versionCode`。迭代 UI/脚本时，可用当前已签名 ZIP 作为比较基准生成 Debug 包：

```sh
./bin/remrss dev-reload my-mod --key author-private.pem --installed current-signed.zip --out my-mod-debug.zip
```

命令只接受相同 ID、作者指纹、`versionCode` 与 `dataVersion`，并重新签名和回验输出。然后在 Debug 构建的“Mod 开发重载”启动器入口选择该 ZIP。宿主保留原模型/授权并验证新包对当前模型仍兼容；新增必需能力或数据迁移必须通过正常导入/审核。此功能不进入 Release，不启用 HTTP 服务或设备自动发现。

同页诊断要求输入单个 Mod ID，且目标已启用、作者仍受信任。只连接该 Mod，内存中最多保留 64 条 ready、提交 revision、Effect 请求与错误代码，不保留 state/config 或错误正文。离开页面会停止观察；记录只留在当前 Activity。资源统计只显示该 Mod 签名载荷的字节量，不代表 CPU、PSS 或电量。
