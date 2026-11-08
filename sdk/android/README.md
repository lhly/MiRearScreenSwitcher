# ReMRSS Android SDK

`RearClient` connects an app to ReMRSS's narrow public Binder service. It does not contain Root/Shizuku code, shell commands, Mod rendering, or a QuickJS dependency.
The AAR manifest declares package visibility for the production host package so Android 11+ can resolve its explicit service binding; no broad package query is requested.

Normal consumers use the default `RearClient` constructor, which targets the production host package. `RearClient.forHostPackage(...)` is provided for local/debug host variants only; it does not change caller identity checks or grant backend privileges.

## Add the SDK

Build the local AAR with `:platform-sdk:assembleRelease`. The output is `build/platform-sdk/outputs/aar/platform-sdk-release.aar`. This repository does not publish the AAR to a remote Maven repository.

The complete sample APK is in `sdk/sample`; it keeps a counter interactive on the main Activity while a separate rear Activity displays and changes the same app-owned state.

## Declare an eligible rear Scene in the manifest

An app that exposes a rear Scene needs the ReMRSS/OEM rear policy declaration at both app and Scene Activity scope. Use this template for the app owner and the exact Activity passed to `RearSceneRequest`; the platform's own rear entry uses `miui.rear.policy=1` at these scopes. The SDK sample follows the same contract.

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:name=".GameApplication"
        android:label="Game"
        android:theme="@style/AppTheme">
        <meta-data
            android:name="miui.rear.policy"
            android:value="1" />

        <activity
            android:name=".RearSceneActivity"
            android:exported="true"
            android:excludeFromRecents="true"
            android:taskAffinity="${applicationId}.rear"
            android:launchMode="standard"
            android:showWhenLocked="true"
            android:configChanges="orientation|screenSize|keyboardHidden|screenLayout|uiMode|smallestScreenSize|density|fontScale">
            <meta-data
                android:name="miui.rear.policy"
                android:value="1" />
        </activity>
    </application>
</manifest>
```

Keep the separate rear Activity standard-launched and isolated from the app's normal task as shown; export it for the host's privileged explicit launch, then require the one-use Scene ticket before accepting its first ready report. The merged SDK manifest supplies the narrow host package query. The policy metadata marks rear-Scene eligibility; it does not grant Root/Shizuku, create a rear display, or guarantee that every ROM will accept the launch. ReMRSS owns this supported manifest convention, so Scene authors should use the template rather than maintain per-device policy variations.

Apps that use only capability discovery, read-only data, or other non-Scene SDK APIs do not need either rear policy declaration. Add the app-level declaration only when the app actually provides a rear Scene.

## Connect from the application owner

Keep one `RearClient` in the app's `Application` while a rear scene is active. A Activity-scoped connection would incorrectly close the client's scene on rotation.

```kotlin
class GameApplication : Application() {
    lateinit var rear: RearClient

    override fun onCreate() {
        super.onCreate()
        rear = RearClient(this) { event -> handleRearEvent(event) }
        rear.connect { result -> if (result.isSuccess) rear.queryCapabilities(::showCapabilities) }
    }
}
```

Request activation from the app's visible user control. The host separately shows a Miuix confirmation naming the app and component; display focus is not a visibility or keep-visible requirement.

```kotlin
rear.requestSceneActivation(
    this,
    RearSceneRequest(ComponentName(this, RearActivity::class.java), keepVisible = true)
) { result -> result.exceptionOrNull()?.let(::showError) }
```

Every explicitly confirmed new Scene may wake only its selected rear display once when that panel is asleep, so `keepVisible=false` scenes can still be shown initially. This does not wake the main display or start a periodic wake loop. `keepVisible=true` separately allows the Activity to hold its own `FLAG_KEEP_SCREEN_ON` while the same lease is active, Core permits visibility, and the measured rear window is actually visible. Activity focus/resume alone is not proof of rear visibility. Apply the accepted request and measured state:

```kotlin
override fun onResume() {
    super.onResume()
    RearSceneWindowPolicy.applySceneLease(
        this,
        requested = lease.keepVisible,
        leaseActive = lease.isActive,
        windowActuallyVisible = rearWindowActuallyVisible,
        sceneVisible = sceneVisible
    )
}

// Re-evaluate this on real window/display visibility changes, not only focus changes.
// Report the same measured visibility separately with reportSceneVisibility(...).
```

`ready` means the exact ticket-owned Activity reported a measured window and the host verified its task identity. Visibility is separate: report it from measured Activity window attachment/visibility, visible bounds, and target display state. Observer callbacks (`visibilityChanged`, `suspended`, `restored`) are reconcile triggers, not durable authorization; a delayed `restored` callback may arrive after a newer cover edge. Query the current owner/session snapshot before applying presentation and reject an older revision:

```kotlin
val presentationGate = RearScenePresentationGate(sessionId, generation)
client.queryScenePresentation(sessionId, generation) { result ->
    result.onSuccess { current ->
        if (presentationGate.apply(current)) renderScene(current.visible && rearWindowActuallyVisible)
    }
}
```

An Activity should also invalidate outstanding replies on `onStop` and start a new request epoch on `onStart`; `RearScenePresentationRequestEpoch` provides this small guard. Render a pure-black cover and pause sampling/render work whenever the current Core snapshot is hidden or the measured local window is not actually visible. Notifications and charging may temporarily cover a live Scene; the lease remains, but drawing resumes only after querying the current state. A primary-screen-off event can restore an already-authorized `keepVisible` lease on the same rear task without waiting for main-screen unlock; this never replays the activation ticket or creates another task. A pure-black cover hides content but is not hardware screen-off, so it may still consume power. Never infer a lit/displayed panel from `ready`, focus, or task identity alone.

Presentation revisions are monotonic. A snapshot with a lower revision must be ignored; the exact same revision and same visible value may be applied again idempotently after an Activity restart, while a conflicting value at the same revision is invalid. Keep the Activity's start/stop request epoch check as well, so an old window's delayed query cannot reopen a restarted or stopped surface.

After confirmation, the host starts the registered Activity in a new rear-display task. Its first measured window reports readiness:

```kotlin
window.decorView.post {
    val ticket = intent.getStringExtra(RearClient.EXTRA_LAUNCH_TICKET) ?: return@post finish()
    rear.reportWindowReady(this, ticket, callback = ::handleReportResult)
}
```

Register the Activity with the Scene policy template above. Use `allowTaskReparenting="false"` and the default document launch mode as well. Scene start uses a new/multiple task; the host snapshots matching component tasks across every display and accepts only a unique newly observed task on the requested rear display. Components using `singleTask`/`singleInstance`, document tasks, task reparenting, or the default app affinity are rejected before launch.

Handle the `accepted` event's `sessionId`, `generation`, and `keepVisible` at application scope. On configuration recreation, call `attachObserver(sessionId, generation)` and `reportViewport(activity, sessionId, generation, nextRevision, ...)`; do not open a second scene or replay the launch ticket. Report visibility from the actual rear window/display lifecycle, not merely from `onPause`, focus, or task state. Call `closeScene(sessionId)` for an explicit user close. Call `RearClient.close()` only when the application releases its client connection; this releases only that Binder epoch's sessions.

## Identity, permission, and support

The exported host service derives the caller UID and signer from Binder and PackageManager. The package name passed by the SDK is checked against that UID; the callback Binder owns a connection epoch and its death releases only that client's scenes. A caller cannot choose a display ID or privilege backend.

Capability discovery, the independent Activity Scene path, and opt-in read-only platform data are exposed by this SDK candidate. Data access is separate from Scene/Root/Shizuku: ask from a focused Activity, let the user choose battery and/or system metrics in the host's Miuix screen, then observe only while the app UI is visible and close the returned subscription from `onStop`. The host enforces a 2-second minimum sample interval, shares one source, and returns unavailable values instead of fabricating data.

`queryRearInfo` keeps display mode and logical dimensions separate: `physicalModeWidthPx/physicalModeHeightPx` come from the current `Display.Mode` raster in that mode's native orientation (nullable if unavailable); `logicalWidthPx/logicalHeightPx` are current logical display pixels and may differ under a WM size override. `densityDpi` is the logical display density, and `rotation` is the current quarter-turn rotation; it does not rotate/relabel the mode raster. None of these predicts the final app window. Use the Scene's `ready`/`viewportChanged` event as the final window geometry; it carries separate physical px, window px/dp, content bounds, render buffer, and input transform.

```kotlin
rear.queryRearInfo { result ->
    result.onSuccess { info ->
        val panel = info.panel // null when display metadata is unavailable
        val supported = info.deviceAvailable
        val backendReady = info.backendConnected
    }
}
```

```kotlin
rear.requestDataAuthorization(this, setOf(RearDataField.BATTERY_LEVEL_PERCENT, RearDataField.BATTERY_CHARGING))
rear.observeData(setOf(RearDataField.BATTERY_LEVEL_PERCENT), onSnapshot = { snapshot ->
    val level = snapshot.fields[RearDataField.BATTERY_LEVEL_PERCENT]
}) { result ->
    result.onSuccess { subscription -> visibleSubscription = subscription }
}
// In onStop: visibleSubscription?.close()
```

`RearDataValue.Unavailable` carries `SOURCE_UNAVAILABLE`, `SAMPLE_PENDING`, `INVALID_SAMPLE`, or `UNSUPPORTED`; GPU utilization is currently unsupported. Consent is stored per caller package and signing certificate; the current Binder epoch owns subscriptions and is closed on client release/death. To change or revoke a data category, the app can reopen the same foreground consent request with that category selected. The general `rear.cast` session API, own rear-notification publishing, and registered host actions remain unavailable. Scene still depends on the selected privileged backend; a missing rear backend produces an explicit error rather than a pretend ready state. Device-window and touch behavior still requires target-ROM validation.
