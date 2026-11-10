# 独立 SDK 示例工程模板

交付时，打包器会把 `sdk/sample/src/main` 和 `sdk/sample/src/test` 单源复制到本工程的 `app/src/main`、`app/src/test`，将 `platform-sdk-0.4.0.aar` 放入 `app/libs/`，并附上 Gradle 9.7.1 Wrapper。消费者需 Android SDK 37 与 JDK 17；Kotlin API 使用 Kotlin standard library，Kotlin Android plugin 会提供它；无额外 AndroidX/第三方运行库依赖。AAR metadata 为 Kotlin 2.4.0，使用 Kotlin Android plugin 2.4.20 编译；该模板为此关闭 AGP 内建 Kotlin 与 new DSL。无需宿主源码、仓库私有 Gradle project 或主仓库构建脚本。示例 applicationId 为 `dev.remrss.sample.consumer`，namespace 和源码包仍为 `dev.remrss.sample`。

默认连接生产宿主 `top.zainzh.rearscreen`。只有明确指定 `-PremrssHostPackage=<包名>` 才会连接另一个本地调试宿主；覆盖时应用包可见性也随 Manifest placeholder 一同改变。示例 Release 使用自己的 debug key 签名，不含宿主私钥。

SDK交付包还提供可直接预览的APK：`sample/apk/rear-sdk-sample-debug.apk`。

```sh
./gradlew :app:assembleDebug
```
