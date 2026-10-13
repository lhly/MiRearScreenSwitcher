# Android Studio 构建自用正式 APK

## 已完成的配置

本机已配置 Flutter 3.35.7、Dart 3.9.2、JDK 21、Gradle 8.9、Android SDK 36 和 NDK 27。Android Studio 的 Flutter 和 Dart SDK 路径均已指向项目内的工具目录。

项目运行配置“构建正式 APK”调用 `tools/build_release.sh`，由脚本加载 `.local/env.sh`。这样从 IDE 和终端构建都会使用相同的 Java、Android SDK、Flutter 和缓存目录。现有 Gradle 使用 JDK 21；无需更换 Android Studio 自身的运行时。

已生成个人正式签名密钥，格式 PKCS12，RSA 3072 位，别名 `mrss-release`，有效期 10000 天。`android/key.properties` 已配置密钥路径和密码。release 必须配置正式签名；缺少配置会停止构建，不会回退为 debug 签名。

## 在 Android Studio 中构建

1. 打开项目根目录 `MiRearScreenSwitcher`。
2. 顶部运行配置选择 **构建正式 APK**。
3. 点击旁边的绿色三角形运行按钮。
4. 等待“运行”窗口显示 `Built build/app/outputs/flutter-apk/app-release.apk` 和退出代码 `0`。

该配置只生成 APK，不依赖设备选择。输出文件：

```text
build/app/outputs/flutter-apk/app-release.apk
```

也可以在 Android Studio 内置终端的项目根目录执行：

```sh
./tools/build_release.sh
```

当前脚本为 ARM64 手机构建 Flutter AOT 代码，适用于本次连接的小米手机。版本号在 `pubspec.yaml` 中设置，当前为 `3.2.1+2011`；以后更新时递增 `+` 后的 versionCode。

## 签名文件与备份

需要一起妥善备份这两个文件：

```text
.local/signing/mrss-release.p12
android/key.properties
```

前者是私钥，后者包含访问密钥的密码和构建配置。两者均已被 Git 忽略，本机文件权限为 `600`，签名目录权限为 `700`。不要放入代码仓库或公开分享。密码无需在每次构建时手工输入；若使用 Android Studio 的签名向导，可由你自行在本地查看 `key.properties` 后填写。

以后更新自用版必须沿用同一把密钥及同一包名。换电脑时恢复这两个文件并重新准备构建工具；`.local/env.sh` 和 `android/local.properties` 包含本机路径，需要同步调整。示例 `android/key.properties.example` 不含实际密码。

## 包名及原数据

本机 `key.properties` 设置了 `releaseApplicationIdSuffix=.personal`，正式 APK 包名为 `com.tgwgroup.MiRearScreenSwitcher.personal`，显示名称为 MRSS，URI 为 `mrss://`。这是可独立安装的自用版。

作者原版与个人密钥签名不同，个人 APK 无法覆盖作者原版并接管其数据。本轮采用独立包名，原版保留的数据继续归原包名所有；此前安装的 `.debug` 测试版也是独立数据目录。因此首次安装个人正式版时需要重新配置应用并授予 Shizuku 权限。以后同密钥、同包名覆盖更新个人正式版可以沿用它自己的数据。

原版的数据保留及恢复说明见 [磁贴修复验证文档](hyperos4-tile-validation.md)。

推送 tag 自动发布同签名个人 APK 的配置见 [GitHub 自动发版说明](github-release.md)。

## 2026-10-13 构建验证

- 命令行完整 release 构建成功，Android Studio 的“构建正式 APK”运行成功、退出代码为 0。
- APK 版本为 `3.2.1`，versionCode 为 `2011`，minSdk 为 24，targetSdk 为 36，约 17.7 MB。
- `apksigner verify` 通过，证书与生成的个人密钥匹配；APK 不可调试，包含 ARM64 Flutter AOT 代码。
- 证书 SHA256：`6479192fc1646532afc357f27d1581832bef5e6ad15e2f44501d883f04c783ac`。
- 本次 APK SHA256：`7727ab5b401fa070bb11c0aaf1189d83bfadbc2d0e148085b7daa549b97fba1c`。

此前真机磁贴验证使用 debug 修复版；本次正式 APK 尚未安装到手机，release 的混淆后运行行为还需安装验收。构建日志和验签记录在 `.local/diagnostics/`。

配置依据：[Flutter Android 发布文档](https://docs.flutter.dev/deployment/android)、[Android 应用签名文档](https://developer.android.com/studio/publish/app-signing)、[Android 构建 JDK 说明](https://developer.android.com/build/jdks)。
