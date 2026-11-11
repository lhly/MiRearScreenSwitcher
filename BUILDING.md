# 从源码构建 ReMRSS

需要调试或自行签名时，使用与目标版本一致的 tag 源码。直接安装软件请前往 [正式版下载](https://github.com/lhly/ReMRSS/releases/latest)。

宿主 APK 的最低系统版本为 Android 16（API 36）；独立 Android SDK AAR 仍以 API 24 为最低版本，集成方式见 [SDK 指南](sdk/android/README.md)。

## 准备环境

- JDK 21。
- Android SDK Platform 37.0、Build Tools 37.0.0、NDK 27.0.12077973。
- Gradle、Android Gradle Plugin 和 Kotlin 使用仓库内的 Wrapper 与版本配置，不需要全局安装 Gradle。

在 Android Studio 打开 `android/`，将 Gradle JDK 设为 JDK 21，并通过 SDK Manager 安装以上组件。SDK 路径写入本机 `android/local.properties` 的 `sdk.dir`，或使用 `ANDROID_HOME`；不提交本机路径配置。

## 构建调试版

在项目根目录运行：

```sh
./android/gradlew -p android :app:assembleDebug
```

APK 位于 `build/app/outputs/apk/debug/app-debug.apk`。调试版包名为 `top.zainzh.rearscreen.debug`，协议为 `mrss-debug://`，与正式版的配置和权限分开。

## 使用自己的密钥构建正式版

1. 准备自己的签名密钥。
2. 将 `android/key.properties.example` 复制为 `android/key.properties`，填写自己的密钥路径、类型、别名和密码。`storeFile` 的相对路径以 `android/app/` 为基准。
3. 在项目根目录执行：

```sh
./tools/build_release.sh
```

APK 位于 `build/app/outputs/apk/release/app-release.apk`。正式版启用 R8 和资源压缩；版本读取根目录 `version.properties`。签名密钥及含密码的配置仅保存在本机，不得提交或放入发行附件。

自行签名的 APK 不能覆盖不同签名的官方安装包。若需要保留现有数据，请继续使用相同包名、相同签名的更新；不要为安装自签名包直接卸载正在使用的版本。

## 对应源码与许可证

已发布版本的源码和构建说明保留在对应 tag 中。根目录 [LICENSE](LICENSE) 和 [NOTICE](NOTICE) 是 APK 内离线许可与归属信息的来源；修改或再分发时保留相应内容。

[用户指南](docs/README.md) · [项目首页](README.md)
