# ReMRSS Mod SDK v1

**Kit 1.0.5 candidate** refines `host.configChanged` failures and Effect admission. A reducer refusal rejects only that Save; the logical scene observers and committed model stay active while the host confirms old-worker teardown and reloads the committed model into a fresh worker. Declarative Mods still save directly without starting JavaScript. Effect intents are typed and checked before commit; `rear.open` cannot be emitted by Save because it needs a fresh user activation. The bounded `layout.row` and migration contracts remain unchanged.

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

模板：`blank` 是真正空 state、且不启动 VM 的声明式页面；`info` 是复用宿主电量来源的无脚本面板；`counter` 是中性的通用 reducer 演练，不含计分牌规则或宿主专用业务分支；保存“每次增减”后，它通过 `host.configChanged` 原子更新用于界面显示的派生状态，不需要再点一次“重新计算”。三个模板都声明必需的 `rear.scene@1`，使其默认 rear scene 在用户授权后可投放；`rear.scene.keep_visible@1` 是可选声明。声明从不代表用户已授权，用户仍会在导入时逐项选择。


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

`minimumVersion` 与 core `CapabilityRequirement(name, minimumVersion)` 同形；当前工具开放 `rear.scene@1`、`rear.scene.keep_visible@1`、`data.battery@1`、`data.systemMetrics@1` 与宿主管理的 `model.local@1`、`ui.native@1`、`ui.dialog@1`。`data.battery` UI binding 为 `data.battery.level`（百分比）、`data.battery.scale`（100）和 `data.battery.charging`；`data.systemMetrics` UI binding 为 `data.systemMetrics.cpu.utilizationPercent`、`data.systemMetrics.memory.availableBytes` 与 `.totalBytes`。数据只在获授权且页面可见时供原生场景绑定使用，关闭订阅即清空旧值，不默认传入脚本；GPU字段目前不受支持。设计文档 §4.1 中其余能力仍在实现/验收中，不会因为名字已列在设计表就被工具当作可用。设备能力、后端连接、作者信任和用户逐项授权仍由宿主分别判断；声明不是授权。

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

可机器读取的 JSON Schema：[`manifest.schema.json`](manifest.schema.json)，URI 为 `urn:remrss:mod:manifest:v1`，仅用于本地工具引用，不请求网络。
