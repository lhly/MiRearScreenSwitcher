# Android Studio 构建原生正式 APK

当前源码为 v3.3.0，设置界面已改为 Java / Android 原生 View。构建需要 JDK 21、Gradle 8.9、Android SDK 36 / Build Tools 36.0.0；无需 Flutter、Dart 或 NDK。

## Android Studio

1. 打开项目的 `android/` 目录并完成 Gradle Sync。
2. Gradle JDK 使用本机 `.local/toolchains/jdk21`，SDK 路径由 `android/local.properties` 的 `sdk.dir` 指定。
3. 在 Build Variants 中选择 `release`，运行 Gradle 任务 `:app:assembleRelease`。
4. 输出位于项目根目录 `build/app/outputs/apk/release/app-release.apk`。

若仍以项目根目录打开现有 IDE 工程，“构建正式 APK”运行配置继续调用 `tools/build_release.sh`。本轮已经验证命令行构建；迁移后的 Android Studio 图形界面没有重新验收。

终端从项目根目录执行：

```sh
./tools/build_release.sh
```

脚本自动加载本机 `.local/env.sh`，也允许其他机器使用已有 Java/SDK 环境。版本在根目录 `version.properties` 中设置：

```properties
versionName=3.3.0
versionCode=2012
```

正式版启用 R8 与资源压缩，包含原生 Java 代码和 Shizuku API，不携带 Flutter 引擎，无需按 CPU 架构打包。

## 签名和更新

本机沿用原个人正式签名（PKCS12 / RSA 3072，别名 `mrss-release`），release 缺少签名时停止构建。备份：

```text
.local/signing/mrss-release.p12
android/key.properties
```

密钥及含密码的配置被 Git 忽略，请勿公开。换电脑时恢复两份文件并调整路径；`android/key.properties.example` 是无密码示例。证书 SHA256：

```text
6479192fc1646532afc357f27d1581832bef5e6ad15e2f44501d883f04c783ac
```

个人正式版包名为 `com.tgwgroup.MiRearScreenSwitcher.personal`，名称 MRSS，URI 为 `mrss://`。可以覆盖同签名的个人 v3.2.1 并导入其旧设置。作者原版和 `.debug` 修复测试版分别保存数据，首次安装个人正式版仍需重新设置和授权 Shizuku。

## 验证与产物

原生 v3.3.0 已完成本机正式构建、Lint、偏好迁移及界面运行测试、签名/包名/版本/非调试/无 Flutter 结构校验。实际测量和验证边界见 [原生版验证记录](native-ui-validation.md)。本轮未安装到小米真机，也未发布到 GitHub。

[自动发版说明](github-release.md) · [此前磁贴真机验证](hyperos4-tile-validation.md)
