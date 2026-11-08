# ReMRSS ZIP Mod：作者快速上手

一个 ReMRSS Mod 是一个普通 `.zip`：manifest 声明作者、版本、能力和两个界面；签名锁定 manifest，载荷摘要锁定每个 UI/资源/脚本文件。无脚本的信息面板只走宿主原生 renderer，不会启动 JavaScript。

## 做出第一个可导入的 Mod

安装 Python 3.10+ 与 OpenSSL。只有编写 reducer 并运行逻辑测试时才需要 Node.js 18+。

```sh
./bin/remrss create hello-panel --template info --author "作者名字"
./bin/remrss check hello-panel
./bin/remrss preview hello-panel
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out author-private.pem
./bin/remrss pack hello-panel --key author-private.pem --out hello-panel.zip
./bin/remrss check hello-panel.zip
```

这些命令从解压后的 SDK kit 根目录运行（`bin/remrss` 在 ZIP 中保留可执行权限）。若直接在 ReMRSS 源码仓库根目录开发，可改用 `./sdk/mod/bin/remrss`。

`pack` 会签名并重新验签 ZIP。作者私钥只保存在你自己的密钥管理器中；它既不放入包，也不交给 ReMRSS 维护者。导入时先验证公钥指纹和全部载荷，再单独展示作者信任与每项能力授权。新 Mod 默认停用；启用、撤权、卸载与更新由设置页控制。

`preview` 检查 UI 声明与本地资源，不生成视觉截图。要看实际 Android 画面：打包 ZIP、在 ReMRSS 的 Mod 管理页导入并审核，随后打开该 Mod 卡片的“本机预览”。预览复用宿主 Compose/Miuix renderer，可切换设置/背屏场景、尺寸、安全区、主题、字号、旋转和纯黑遮挡；不申请后屏、显示租约或运行 reducer。按钮事件只提示不会执行，设置保存只改变当前预览状态。

## 包协议与作者身份

格式固定为 `remrss.mod` / `formatVersion: 1`。ZIP 包含 `manifest.json`、`manifest.sig`、`author.pub` 与已列载荷。签名算法为 RSA-SHA256，签名覆盖 manifest 原始 UTF-8 字节；manifest 中的 SHA-256 路径清单覆盖每个资源、界面和 `main.js`。修改任意载荷必须重新打包签名。

作者身份由公钥 DER 的 SHA-256 指纹确定。维护者私钥不能代表作者签包。同一个 Mod ID 一经安装会永久绑定其作者指纹；不同作者不能接管。更新需要更高 `versionCode`，同作者更新保留逻辑会话身份和数据。旧 `.remodule` / schema 1 不兼容此协议。

作者声明的能力、宿主识别的声明、用户实际授权是三件事：未知能力或高于宿主支持版本的 `minimumVersion` 会在打包/导入时拒绝；必需能力须逐项授权，可选能力默认关闭；撤权不会改写模型。当前CLI与host开放 `rear.scene@1`、`rear.scene.keep_visible@1`、`data.battery@1`、`data.systemMetrics@1` 与宿主管理的 `model.local@1`、`ui.native@1`、`ui.dialog@1`、`action.invoke@1`、`clock.snapshot@1` 与 `schedule.visible@1`。数据能力仅提供通过授权的可见原生 UI 绑定：`data.battery` 有 `data.battery.level`、`.scale`、`.charging`；`data.systemMetrics` 有 CPU 利用率百分比和内存可用/总字节数绑定。provider按需共享低频采集，页面不可见时停止订阅；数据不会默认周期性传入 JavaScript。GPU 当前标为不支持，不提供伪值。设计中的 `rear.region@1`（当前FULL返回UNSUPPORTED）、`rear.cast@1`、`rear.notification@1`、其余尚未接入的能力仍不会因为写入设计文档就被工具视为可用。`rear.scene` 只有在用户已授权后才能从设置页的真实“显示到背屏”点击触发；一次点击签发单次短期激活票据。Reducer 不能自行签发票据、开场景或调用 Android 服务。

## 两种 Mod：声明式与 reducer

- `blank`：真正空 state 的空白原生页面。
- `info`：无脚本电量信息示例。UI `data.*` binding 只读对应宿主数据源；不加载 VM。
- `counter`：通用状态事务与事件测试示例，不含记分牌规则，也不依赖 host 特判。

三个模板的默认 manifest 都声明必需的 `rear.scene@1`，让其rear场景在用户授权后可投放；`rear.scene.keep_visible@1` 为可选能力，声明不是授权。

UI 文件 `settings.ui.json` 和 `rear.ui.json` 都是有界 JSON 节点树，在设置/背屏共用 Kotlin Compose 与 Miuix renderer。可用容器为 `layout.column`、`layout.row`、`layout.box`；显示节点包括 `ui.text`、`ui.number`、`ui.list`、`ui.icon`、`ui.deadlineText`、`ui.image`、`ui.divider`、`ui.progress`、`ui.button` 与 `ui.confirm`。设置节点有 `settings.row/input/action/number/toggle/select/save/discard`。列表只绑定 schema 声明的标量数组，显示 1..32 项；条件用 `visibleWhen: {bind, equals}`，格式与语义 tone/字号/图标/布局对齐均使用宿主白名单。`ui.deadlineText`绑定 elapsedRealtime 毫秒截止值，仅可见时原生刷新，不运行 JS 或保持常亮。`layout.row` 先扣除子项间距，再将可用宽度平均分配为有界单元；嵌套列、文本和铺满宽度的控件会在各自单元内换行和测量，不会裁掉后续兄弟。按钮可发命名 reducer event，或在真实原生点击时调用宿主核验的登记动作引用；不传任意宿主函数、Intent 或 `hostAction`。

Binding 使用 `{"bind":"state.some.field"}`、`config.*` 或声明能力后可用的 `data.*`，不允许表达式、路径遍历或任意代码。配置编辑是草稿；只有点击“保存设置”才一起提交，取消不会改模型。state/config 都按 manifest JSON schema 做有界递归校验；拒绝额外字段、超深结构、超大数组/字符串和超出数值范围的提交。常用上限见 `sdk/mod/README.md`。

设置动作picker字段要在 `configSchema.properties` 中声明为**非 required 的可选字段**；只有 picker 保存后才会出现值。格式固定为：

```json
"actionRef": {
  "type": "object",
  "properties": {
    "id": { "type": "string", "maxLength": 48, "pattern": "^[a-f0-9]{48}$" },
    "version": { "type": "integer", "minimum": 1, "maximum": 1, "const": 1 }
  },
  "required": ["id", "version"],
  "additionalProperties": false
}
```

`settings.action` 与后屏按钮都绑定 `config.actionRef`；后屏节点使用 `"onPress":{"type":"registeredAction","ref":{"bind":"config.actionRef"}}`。只支持一个顶层 config 字段，不能从 state 或 JS 构造引用。尚未选择时按钮处于禁用状态。

需要真正业务规则时，设置 `runtime.kind` 为 `quickjs` 并提供单文件 `main.js`，公共入口为：

```js
globalThis.__remrss = {
  init({ config, env }) { return { count: 0 }; },
  reduce({ state, config, event, env }) {
    if (event.type === 'counter.increment') return { state: { ...state, count: state.count + 1 } };
    return { state };
  }
};
```

`init` 只在首次激活且 durable model 尚不存在时运行；worker 重启和同版本更新不重跑。每个 reducer 输入严格排队；宿主验证 state 后先持久化并推进 revision，再执行 Effect。相同 state/config 不产生磁盘写入或 revision 增量。Effect 只可请求已登记能力，外部动作需宿主界面上的真实点击。

保存设置时，QuickJS Mod 会收到宿主保留的 `host.configChanged` reducer 事件；输入携带旧 state、新 schema 校验后的 `config`、旧提交的 `previousConfig` 和事件元数据。返回的 `{state}` 通过 schema 校验后，state 与新 config 在同一 revision 提交，再同时通知可见观察者。脚本异常只拒绝本次 Save，旧模型/配置和逻辑观察者保持；宿主会确认关闭旧 worker，再从已提交模型启动新 worker，以涵盖普通异常及 native 分配/栈失败。设置页显示错误并保留草稿，新 worker恢复后可原地重试；关闭或恢复失败、预算耗尽、worker死亡或传输故障仍按终态收尾。

Config reducer 的 Effect 需要按类型、能力与来源预校验，且只能在 state/config 事务提交后派发。Save 不签发用户激活：目前 `rear.open` 要求明确的用户手势，在配置事件中会因缺少激活而拒绝，不会自动投放。未来非激活 Effect 只有在其宿主合同实际实现后才可能通过；当前 RC3 支持 `schedule.visible`：周期至少 1000ms，每 owner 最多 4 个 key；单次 deadline 与周期可组合在同一 key，数值为非负 JavaScript 安全整数。隐藏不补积压周期；过期 deadline 最多立即触发一次。`host.effectResult`区分接受/拒绝，schedule回调为`host.schedule.tick`。派发失败发生在提交之后，不回滚已保存模型。`remrss test` 的 `expected.effects` 只校验作者 reducer 产生的 Effect 意图，不代表宿主授权/激活/派发验收。无脚本 Mod 仍直接保存配置而不启动 VM，首次 init 规则不变。作者可用 `tests/config-changes.json` 检查成功派生、拒绝分支和Effect输出；CLI 会校验配置和输出结构。

```js
reduce({ state, config, previousConfig, event }) {
  if (event.type === 'host.configChanged') {
    return { state: { ...state, stepUsed: config.step, previousStep: previousConfig.step } };
  }
  if (event.type === 'counter.increment') return { state: { ...state, count: state.count + config.step } };
  return { state };
}
```

隔离 runtime 对 `reduce` 使用 100 ms 原生 CPU watchdog，对首次 `init` 与纯 `migrate` 使用 500 ms 原生 CPU watchdog。它限制的是脚本实际消耗的 CPU 时间；系统调度、Binder 往返和宿主排队时间不计入该预算，也不承诺每个调用固定在某个墙钟时间内返回。超预算由 runtime 以 `E_CPU_BUDGET` 失败结果报告，宿主不会把业务 state 中普通的 `code` 字段误判为引擎预算错误。

SDK 的 `buildReducerSource({init, reduce, migrate?})` 把自包含函数序列化为 `globalThis.__remrss` ABI。依赖必须由作者构建过程 bundle 进单文件；不允许 Node API、网络、Android `Context`、Root、Shell 或 Binder。`remrss test` 用本地 Node 对声明的事件和迁移用例做确定性验证，它不是 Android runtime 证明。

## 数据升级与回滚

`data.dataVersion` 覆盖 state 与 config 的整体格式。格式版本变化时，新包必须有纯 `migrate({state, config, fromVersion, toVersion, env})`，只返回 `{state, config}`。若包尚未首次激活，`state` 为 `null`；迁移应原样返回 `state: null` 并转换 config，宿主会把它作为“仍未初始化”保存，首次激活时才调用新版本 `init`。已有模型的 `state` 是 object，迁移必须返回符合新 state schema 的 object；宿主提交边界会拒绝 object→null，避免通过后续 `init` 静默清空数据。迁移在隔离 runtime 中运行，宿主对非 null 的新 state 与 config 双 schema 校验；Effect、额外返回字段或校验失败会让升级失败。只有迁移成功后，新 ZIP、模型和活动包指针才一起事务切换；失败保留旧包、旧模型。没有迁移函数就拒绝这次数据格式升级。

先检查 `state === null`，再读取比分、计数等业务字段。迁移测试也要覆盖这个尚未初始化的分支：

```js
migrate({ state, config, fromVersion, toVersion, env }) {
  const nextConfig = { ...config, teamA: config.teamA || 'A队' };
  if (state === null) return { state: null, config: nextConfig };
  return { state: { ...state, teamA: nextConfig.teamA }, config: nextConfig };
}
```

在 `tests/migrations.json` 中必须显式填写 `state`：用 `"state": null`、`"expected": {"state": null, ...}` 验证未初始化模型；state 字段缺失不是 null。object 输入必须迁移成符合目标 schema 的 object，不能用 `expected.state: null` 表示清空。CLI 会校验非 null 的迁移结果符合目标 state/config schema，并拒绝遗漏输入或object→null用例。

## 工具命令和可复现检查

```sh
./sdk/mod/bin/remrss create my-mod --template blank --author "作者"
./sdk/mod/bin/remrss check my-mod          # 检查源码目录
./sdk/mod/bin/remrss test my-mod           # Node reducer / migrate case
./sdk/mod/bin/remrss preview my-mod        # UI/资源预检；实际视觉在宿主原生界面看
./sdk/mod/bin/remrss pack my-mod --key author-private.pem --out my-mod.zip
./sdk/mod/bin/remrss check my-mod.zip      # 验签、指纹和所有载荷
./sdk/mod/bin/remrss dev-reload my-mod --key author-private.pem --installed current.zip --out my-mod-debug.zip
```

Reducer case 可为本次输入提供配置：`{"config":{"start":10,"step":3}}`。工具先取 `configSchema` 默认值，再递归合并 case 中的 object 字段；最终配置按 schema 校验，并同时传给本 case 的 `init` 与每次 `reduce`。未知字段、缺少必填项或超范围值会让 `test` 明确失败，不会悄悄换成空配置。迁移 case 的 `config` 是旧版输入，应由迁移用例明确提供。

可机器读取的 schema 随 SDK 分发：`sdk/mod/manifest.schema.json`（本地 URI `urn:remrss:mod:manifest:v1`）与 `sdk/mod/ui.schema.json`（`urn:remrss:mod:ui:v1`）与 `sdk/mod/runtime.schema.json`（`urn:remrss:mod:runtime:v1`）；它们不请求线上服务。


### Debug 同作者重载

`dev-reload` 为已安装 Mod 生成本地 Debug 重载 ZIP。候选包须与当前包 ID、作者指纹、`versionCode` 和 `dataVersion` 相同；Android Debug 构建在开发重载页面复核信任、授权与现有模型兼容后原子替换，保留状态、配置和正常版本序列。普通更新仍递增 `versionCode` 并走标准导入/迁移；版本或数据格式变化不走重载通道。该入口只编进 Debug，不开常驻 HTTP 或设备自动发现。

clock 环境字段为 `wallTimeMs`、`elapsedRealtimeMs`、`timezoneId`、`bootId: null`。`bootId` 当前明确为 `null`，只把 `elapsedRealtimeMs` 用于本次设备启动周期的计算；不能存成跨重启 deadline，也不能把 `wallTimeMs` 当作替代时间基准。可见调度的最小周期 1000ms、每 owner 4 个 key；周期与一次 deadline 可同 key 合并，数值需为安全整数；隐藏时取消 lease，恢复不补发周期积压，过期 deadline 最多即时发一次。`host.schedule.tick.payload.causes` 只包含`interval`和/或`deadline`，两者可同时出现。动作选择用 `settings.action`，将 opaque ref 保存为 `{id,version}`；后屏按钮通过 `ref:{bind:'config.actionRef'}` 消费同一个可选顶层配置字段；字段只允许登记生成的 48 位小写十六进制 `id` 和 `version:1`。未选择时按钮禁用并引导到设置；执行仍要求 `action.invoke` 能力授权及用户登记，renderer 不构造执行目标。


### Debug 自有 Mod 诊断

Debug 构建的“Mod 开发重载”页面可输入单个已安装 Mod ID 并连接其诊断会话。页面只保留最多 64 条运行就绪、模型提交 revision、Effect 请求和错误代码；不记录 state/config 或错误正文，不枚举/读取其他 Mod。切到后台、关闭页面或选择文件时会停止观察；记录仅在当前 Debug Activity 内存中，不写盘。资源计数是这个 Mod 签名 ZIP 载荷的字节数，不代表 worker/进程 PSS、CPU 或电量。该工具不轮询、不启动常驻服务，Release 不含 Debug 入口。
