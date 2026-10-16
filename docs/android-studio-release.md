# Android Studio 构建正式 APK

当前源码版本为 v3.3.2 / 2014。构建需要 JDK 21、Gradle 9.7.1、AGP 9.4.1、Kotlin 2.4.20、Android SDK 37.0 / Build Tools 37.0.0。

## Android Studio

1. 打开项目的 `android/` 目录并完成 Gradle Sync。
2. Gradle JDK 使用本机 `.local/toolchains/jdk21`，SDK 路径由 `android/local.properties` 的 `sdk.dir` 指定。
3. 在 Build Variants 中选择 `release`，运行 Gradle 任务 `:app:assembleRelease`。
4. 输出位于项目根目录 `build/app/outputs/apk/release/app-release.apk`。

若仍以项目根目录打开现有 IDE 工程，“构建正式 APK”运行配置继续调用 `tools/build_release.sh`。已通过 Android Studio 的 `app` 运行配置在 Medium Phone API 36 模拟器构建、安装并启动调试应用；调试名称为“MRSS 调试版”，版本后缀为 `-debug`。

终端从项目根目录执行：

```sh
./tools/build_release.sh
```

脚本自动加载本机 `.local/env.sh`，也允许其他机器使用已有 Java/SDK 环境。版本在根目录 `version.properties` 中设置：

```properties
versionName=3.3.2
versionCode=2014
```

正式版启用 R8 与资源压缩，包含 Java 代码和 Shizuku API，无需按 CPU 架构打包。

## 许可证与对应源码

Gradle 的 `prepareLegalAssets` 从根目录 LICENSE 和 NOTICE 生成 APK assets；请维护根文件，不手工复制到资源目录。关于页可离线阅读完整文本。`BuildConfig.SOURCE_TAG` 使用 `versionName` 生成 `v<版本号>`，不附加 debug 后缀。

公开发版时必须将该 tag 指向构建所用的完整源码，并保留对应版本的构建说明。v3.3.2 使用同名 tag 提供对应源码；本地修改以工作区为准。正式发布步骤见 [自动发版说明](github-release.md)。

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

个人正式版包名为 `com.tgwgroup.MiRearScreenSwitcher.personal`，名称为 MRSS，URI 为 `mrss://`。可以覆盖同签名的个人 v3.2.1 并导入其旧设置。作者原版和 `.debug` 修复测试版分别保存数据，首次安装个人正式版仍需重新设置和授权 Shizuku。

## 验证与产物

[v3.3.0](https://github.com/lhly/MiRearScreenSwitcher/releases/tag/v3.3.0) 已完成本机正式构建、Lint、偏好迁移及界面运行测试，并通过云端构建、发布和下载成品校验。APK 为 581,195 字节（约 0.58 MB），已在小米真机安装验证，使用未发现问题。详细记录见 [v3.3.0 验证记录](native-ui-validation.md)。

[自动发版说明](github-release.md) · [此前磁贴真机验证](hyperos4-tile-validation.md)
