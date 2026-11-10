# ReMRSS Android SDK

ReMRSS Android SDK `0.4.0` 提供背屏信息与能力查询、独立 rear Activity、可选只读平台数据、用户登记的快捷操作，以及每个应用单独授权的自有背屏通知。SDK 不嵌入特权后端。请从[作者接入指南](docs/INTEGRATION.md)开始。双屏计分示例位于源码仓库 `sdk/sample/`；离线交付包将其放在 `sample/`。

## 引用本地 AAR

0.4.0 的交付文件名为 `platform-sdk-0.4.0.aar`，用于与旧版 SDK 二进制明确区分；它不是 Maven artifact。将文件放入 Android app module 的 `libs/`：

```kotlin
dependencies {
    implementation(files("libs/platform-sdk-0.4.0.aar"))
}
```

可用随包独立 sample 作为消费者配置参考：Android Gradle Plugin 9.4.1、Gradle 9.7.1 Wrapper、Kotlin Android plugin 2.4.20、compile SDK 37、min SDK 24、target SDK 36。模板关闭 AGP 内建 Kotlin 和新 DSL，并以外置 Kotlin 编译器对齐 AAR Kotlin metadata。sample 不依赖 ReMRSS Host 源码、`:app` 或 `:platform-sdk` 工程，默认 Host 包为 `top.zainzh.rearscreen`；仅在本机确实运行匹配的调试 Host 时才使用 `-PremrssHostPackage=<package>` 覆盖。sample 使用自己的 debug 签名。

AAR 无额外 AndroidX 或第三方运行时依赖；Kotlin API 使用 Kotlin standard library。Kotlin metadata 为 2.4.0，本 sample 以 Kotlin Gradle Plugin 2.4.20 编译；其他编译器版本不在此验证范围内。SDK 不包含 Root、Shizuku、QuickJS、Miuix 或 ROM 实现。此本地 AAR 用法不代表 Maven 发布。

交付包同时提供 sample 源码和预览 APK：`sample/` 与 `sample/apk/rear-sdk-sample-debug.apk`。

交付包的 `delivery.json` 中，`hostSdkInteroperability.verifiedScopes` 列出已验收的消费者互通范围；`verified` 只适用于所列范围和 `consumerKind`，不表示未列出的 SDK 能力或随包 sample APK 已完成实机验证。

## 保持能力边界清晰

客户端连接时协商 `remrss.platform/1`。根据当前 capability 与结果处理能力，不要仅按 SDK 版本或设备型号推断支持情况。0.4.0 包含 rear info、Scene 激活/呈现与 `keepVisible`、授权后电池和系统指标、用户登记快捷操作及 publisher 自有通知。通知需用户逐发布者授权，使用独立命名空间和有限 TTL/配额；`ACCEPTED` 仅表示宿主接收排队，`DISPLAYED` 表示宿主确认目标显示区的窗口/布局可见，不证明物理面板点亮。通知不要求接入方启动 FGS，也不承诺第三方进程锁屏后台常驻或可靠运行。GPU 使用率可能不可用；本版不提供 FULL/region、cast session 或 clock API。

详见[作者接入指南](docs/INTEGRATION.md)，其中包含连接、查询、Scene 确认、可见性、双屏状态、清理和自有通知流程。
