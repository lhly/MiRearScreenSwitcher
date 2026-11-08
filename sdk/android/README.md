# ReMRSS Android SDK

ReMRSS Android SDK `0.2.0` provides a narrow Binder client for rear-display discovery, independent rear Activities, opt-in read-only platform data, and user-registered shortcut actions. It does not embed privileged backends. Start with the [developer integration guide](docs/INTEGRATION.md). The dual-screen scoreboard lives at `sdk/sample/` in the source repository; the delivery bundle places that same sample at `sample/`.

## Consume the local AAR

The SDK is delivered as `platform-sdk-release.aar`, not a Maven artifact. Put it in your app module's `libs/` directory and add:

```kotlin
dependencies {
    implementation(files("libs/platform-sdk-release.aar"))
}
```

Use the included standalone sample project as the reference for its Android Gradle Plugin 9.4.1, Gradle 9.7.1 Wrapper, Kotlin Android plugin 2.4.20, compile SDK 37, minimum SDK 24, and target SDK 36. The template opts out of AGP's built-in Kotlin and new DSL while using the external Kotlin compiler, keeping it aligned with the AAR's Kotlin metadata. The sample does not depend on the ReMRSS host source, `:app`, or `:platform-sdk` Gradle projects. Its default host target is the production package `top.zainzh.rearscreen`; an explicitly supplied `-PremrssHostPackage=<package>` is only for a developer who intentionally runs a matching local host. The sample is signed with its own debug key.

The AAR has no additional AndroidX or third-party runtime dependency. Its Kotlin API uses Kotlin standard-library types; the Kotlin Android plugin supplies the Kotlin standard library automatically. The AAR's Kotlin metadata is 2.4.0; Kotlin Gradle Plugin 2.4.20 is the consumer compiler verified with this sample. Other compiler versions are not covered by that check. It contains no Root, Shizuku, QuickJS, Miuix, or ROM implementation. This local AAR path does not require or imply a Maven publication.

The SDK delivery bundle includes the ready-to-install preview at `sample/apk/rear-sdk-sample-debug.apk`; the editable standalone Gradle project is `sample/`.

## Keep the contract narrow

The client negotiates `remrss.platform/1` when it connects. Query the current contract and capability results rather than assuming a feature from the SDK version or device model. This release exposes rear info, Scene activation/presentation and `keepVisible`, consent-gated battery and system-metrics data, and publisher-owned registered actions. GPU utilization may be reported unavailable. FULL/region, cast sessions, app-published notifications, and clock APIs are not available in this SDK release.

See [Developer integration guide](docs/INTEGRATION.md) for connection setup, capability/data queries, the Scene manifest and user-confirmation flow, Ready/viewport/visibility handling, two-screen state, and cleanup.
