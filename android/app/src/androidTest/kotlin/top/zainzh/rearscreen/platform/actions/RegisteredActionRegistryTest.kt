package top.zainzh.rearscreen.platform.actions

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.content.pm.LauncherApps
import android.graphics.drawable.Icon
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import top.zainzh.rearscreen.platform.SessionOwner
import top.zainzh.rearscreen.platform.SessionPrincipal

@RunWith(AndroidJUnit4::class)
class RegisteredActionRegistryTest {
    private val component = ComponentName("dev.example.automation", "dev.example.automation.RunActionActivity")
    private val originalSigner = "a".repeat(64)
    private val inspector = MutableInspector(InstalledActionTarget("Automation", originalSigner, setOf(originalSigner)))
    private val store = MemoryStore()
    private var dispatched: Intent? = null
    private val registry = PlatformRegisteredActionRegistry(
        androidx.test.core.app.ApplicationProvider.getApplicationContext(),
        store,
        inspector,
        ActionIntentDispatcher { intent -> dispatched = Intent(intent); DispatchAttempt.Accepted },
        nextId = sequenceId()
    )

    @Test fun explicitProviderShortcutRoundTripsBoundedTargetAndLaunchesOnDefaultDisplay() {
        val owner = modOwner("session-one")
        val input = shortcutResult("Run nightly task", component).apply {
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            clipData = android.content.ClipData.newPlainText("ignored", "ignored")
        }
        val registered = registry.registerFromProviderResult(owner, input) as ActionRegistrationResult.Registered
        val record = store.actions.single()
        assertEquals(registered.ref, record.ref)
        assertEquals("dev.example.automation", record.targetPackage)
        assertEquals("tasker://run/42", record.dataUri)
        assertEquals(ActionExtra.Text("job-42"), record.extras["task"])
        assertEquals(ActionExtra.Object(mapOf("attempt" to ActionExtra.Int32(3))), record.extras["options"])

        // A normal Mod session/reopen has a new session id, but its stable author+id binding survives.
        val resolved = registry.resolve(modOwner("session-after-reopen"), registered.ref) as ActionResolveResult.Resolved
        assertEquals(ActionDispatchResult.Accepted, registry.launch(resolved.action))
        val sent = requireNotNull(dispatched)
        assertEquals(component, sent.component)
        assertEquals("tasker://run/42", sent.data.toString())
        assertEquals("job-42", sent.getStringExtra("task"))
        assertEquals(3, sent.getBundleExtra("options")?.getInt("attempt"))
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, sent.flags)
        assertNull(sent.clipData)
    }

    @Test fun refIsOpaqueAndOwnerBoundAndRevocationIsSticky() {
        val first = registry.registerFromProviderResult(modOwner("alpha"), shortcutResult("Run", component)) as ActionRegistrationResult.Registered
        val second = registry.registerFromProviderResult(modOwner("alpha"), shortcutResult("Run again", component)) as ActionRegistrationResult.Registered
        assertEquals(48, first.ref.id.length)
        assertTrue(first.ref.id.matches(Regex("[a-f0-9]{48}")))
        assertNotEquals(first.ref.id, second.ref.id)
        assertEquals(ActionError.OWNER_MISMATCH, (registry.resolve(modOwner("other-publisher", modId = "different"), first.ref) as ActionResolveResult.Rejected).error)
        assertTrue(registry.revoke(modOwner("alpha"), first.ref))
        assertEquals(ActionError.REVOKED, (registry.resolve(modOwner("new-session"), first.ref) as ActionResolveResult.Rejected).error)
        assertEquals(1, registry.listForOwner(modOwner("alpha")).size)
    }

    @Test fun signingInfoLookupUsesApiCorrectFlagAndProductionInspectorReadsCurrentSigner() {
        assertEquals(android.content.pm.PackageManager.GET_SIGNATURES, packageInfoLookupFlagForSdk(27))
        assertEquals(android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES, packageInfoLookupFlagForSdk(28))
        assertEquals(android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES, packageInfoLookupFlagForSdk(32))
        assertEquals(android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES, packageInfoLookupFlagForSdk(33))

        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val ownActivity = ComponentName(context.packageName, top.zainzh.rearscreen.MainActivity::class.java.name)
        val inspected = AndroidActionTargetInspector(context).inspect(ownActivity)
        assertTrue("production PackageManager signer inspection failed: $inspected", inspected is TargetInspection.Available)
        val target = (inspected as TargetInspection.Available).target
        assertTrue(target.currentSignerSha256.matches(Regex("[a-f0-9]{64}")))
        assertTrue(target.currentSignerSha256 in target.acceptedSignerSha256s)
    }

    @Test fun revokedResolvedHandleNeverDispatchesAndPublisherRevokeAllIsAlsoRechecked() {
        val owner = modOwner("revoke-before-launch")
        val ref = (registry.registerFromProviderResult(owner, shortcutResult("Run", component)) as ActionRegistrationResult.Registered).ref
        val stale = registry.resolve(owner, ref) as ActionResolveResult.Resolved
        assertTrue(registry.revoke(owner, ref))
        assertEquals(ActionDispatchResult.Rejected(ActionError.REVOKED), registry.launch(stale.action))
        assertNull("revoked single action was dispatched", dispatched)

        val second = (registry.registerFromProviderResult(owner, shortcutResult("Run again", component)) as ActionRegistrationResult.Registered).ref
        val staleAfterPublisherRevoke = registry.resolve(owner, second) as ActionResolveResult.Resolved
        assertEquals(1, registry.revokeAllForPublisher(owner))
        assertEquals(ActionDispatchResult.Rejected(ActionError.REVOKED), registry.launch(staleAfterPublisherRevoke.action))
        assertNull("revoked publisher action was dispatched", dispatched)
    }

    @Test fun launchSubmissionAndRevokeAreSerializedPerPublisher() {
        val owner = modOwner("serialized-revoke")
        val localStore = MemoryStore()
        val dispatchCount = AtomicInteger()
        val dispatchEntered = CountDownLatch(1)
        val releaseDispatch = CountDownLatch(1)
        val serializedRegistry = PlatformRegisteredActionRegistry(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            localStore,
            inspector,
            ActionIntentDispatcher {
                dispatchCount.incrementAndGet()
                dispatchEntered.countDown()
                check(releaseDispatch.await(3, TimeUnit.SECONDS))
                DispatchAttempt.Accepted
            },
            nextId = sequenceId()
        )
        val ref = (serializedRegistry.registerFromProviderResult(owner, shortcutResult("Run", component)) as ActionRegistrationResult.Registered).ref
        val resolved = serializedRegistry.resolve(owner, ref) as ActionResolveResult.Resolved
        val launchResult = AtomicReference<ActionDispatchResult>()
        val revokeResult = AtomicReference<Boolean>()
        val launchDone = CountDownLatch(1)
        val revokeStarted = CountDownLatch(1)
        val revokeDone = CountDownLatch(1)
        Thread {
            try { launchResult.set(serializedRegistry.launch(resolved.action)) }
            finally { launchDone.countDown() }
        }.start()
        assertTrue("launch did not reach the dispatcher", dispatchEntered.await(2, TimeUnit.SECONDS))
        Thread {
            revokeStarted.countDown()
            try { revokeResult.set(serializedRegistry.revoke(owner, ref)) }
            finally { revokeDone.countDown() }
        }.start()
        assertTrue("revoke thread did not start", revokeStarted.await(2, TimeUnit.SECONDS))
        assertFalse("revoke completed before the already-submitted Activity request returned", revokeDone.await(100, TimeUnit.MILLISECONDS))
        releaseDispatch.countDown()
        assertTrue("dispatch did not finish", launchDone.await(2, TimeUnit.SECONDS))
        assertTrue("revoke did not finish", revokeDone.await(2, TimeUnit.SECONDS))
        assertEquals(ActionDispatchResult.Accepted, launchResult.get())
        assertEquals(true, revokeResult.get())
        assertEquals(ActionDispatchResult.Rejected(ActionError.REVOKED), serializedRegistry.launch(resolved.action))
        assertEquals("only the pre-revoke submission may reach Android", 1, dispatchCount.get())
    }

    @Test fun systemAppPickerResultRegistersOnlyAnExplicitLauncherActivity() {
        val selected = Intent.makeMainActivity(component)
        val ref = (registry.registerLauncherResult(modOwner("launcher"), selected) as ActionRegistrationResult.Registered).ref
        val record = store.actions.single()
        assertEquals("Automation", record.label)
        assertEquals(Intent.ACTION_MAIN, record.action)
        assertEquals(component.packageName, record.targetPackage)
        val resolved = registry.resolve(modOwner("launcher-reopened"), ref) as ActionResolveResult.Resolved
        assertEquals(ActionDispatchResult.Accepted, registry.launch(resolved.action))
        assertEquals(Intent.ACTION_MAIN, dispatched?.action)
        assertEquals(component, dispatched?.component)
    }

    @Test fun modernShortcutManagerResultWithRedactedIntentFailsClosed() {
        assumeTrue(Build.VERSION.SDK_INT >= 26)
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val ownTarget = ComponentName(context.packageName, top.zainzh.rearscreen.MainActivity::class.java.name)
        val info = ShortcutInfo.Builder(context, "test-action")
            .setShortLabel("Test automation")
            .setIcon(Icon.createWithResource(context, android.R.drawable.ic_menu_view))
            .setIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.test/path?mode=night")).apply {
                component = ownTarget
                addCategory(Intent.CATEGORY_BROWSABLE)
                putExtra("mode", "night")
            })
            .build()
        val result = context.getSystemService(ShortcutManager::class.java).createShortcutResultIntent(info)
        assumeTrue("No launcher-supported shortcut result on this AVD", result != null)
        val request = pinItemRequest(result!!)
        assumeTrue("The system did not return a PinItemRequest", request != null)
        val returnedIntent = request?.shortcutInfo?.intent
        android.util.Log.i("RegisteredActionProbe", "pin=${request != null}, intent=${returnedIntent?.let { "${it.action}:${it.component}" }}, stackSize=${request?.shortcutInfo?.intents?.size}")
        val parsed = ActionTargetPolicy.parseProviderResult(result!!)
        if (returnedIntent == null) {
            assertNull("redacted modern shortcut Intent must not be guessed or invoked", parsed)
            return
        }
        val selected = requireNotNull(parsed)
        assertEquals("Test automation", selected.label)
        assertEquals(ownTarget, selected.component)
        assertEquals(Intent.ACTION_VIEW, selected.action)
        assertEquals("https://example.test/path?mode=night", selected.dataUri)
        assertEquals(ActionExtra.Text("night"), selected.extras["mode"])
    }

    @Test fun legacyProviderMayBindAUserSelectedFixedHttpsTarget() {
        val https = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.test/launch?mode=quiet")).apply {
            component = this@RegisteredActionRegistryTest.component
            addCategory(Intent.CATEGORY_BROWSABLE)
            putExtra("mode", "quiet")
        }
        val providerResult = shortcutResult("Quiet mode", null, https)
        assertNotNull("fixed HTTPS shortcut should parse", ActionTargetPolicy.parseProviderResult(providerResult))
        val registration = registry.registerFromProviderResult(modOwner("https"), providerResult)
        val ref = (registration as? ActionRegistrationResult.Registered)?.ref
            ?: throw AssertionError("fixed HTTPS shortcut was rejected: $registration")
        val record = store.actions.single()
        assertEquals("https://example.test/launch?mode=quiet", record.dataUri)
        val resolved = registry.resolve(modOwner("https-reopen"), ref) as ActionResolveResult.Resolved
        assertEquals(ActionDispatchResult.Accepted, registry.launch(resolved.action))
        assertEquals("https://example.test/launch?mode=quiet", dispatched?.data.toString())
        assertEquals("quiet", dispatched?.getStringExtra("mode"))
    }

    @Test fun legacyProviderMayReturnItsBoundedComponentSpecificShortcutCategory() {
        val owner = modOwner("settings-category")
        val settingsCategory = "com.android.settings.SHORTCUT"
        val selectedTarget = Intent(Intent.ACTION_MAIN).setComponent(
            ComponentName("com.android.settings", "com.android.settings.Settings\$NetworkProviderSettingsActivity")
        ).addCategory(settingsCategory)
        val result = Intent()
            .putExtra(Intent.EXTRA_SHORTCUT_NAME, "Internet")
            .putExtra(Intent.EXTRA_SHORTCUT_INTENT, selectedTarget)
        val ref = (registry.registerFromProviderResult(owner, result) as ActionRegistrationResult.Registered).ref
        assertEquals(listOf(settingsCategory), store.actions.single().categories)
        val resolved = registry.resolve(modOwner("settings-category-reopened"), ref) as ActionResolveResult.Resolved
        assertEquals(ActionDispatchResult.Accepted, registry.launch(resolved.action))
        assertTrue(dispatched?.categories?.contains(settingsCategory) == true)
    }

    @Test fun sdkConnectionEpochDoesNotBecomeThePersistentPublisherKey() {
        val first = sdkOwner(epoch = 8)
        val registered = registry.registerLauncherResult(first, Intent.makeMainActivity(component)) as ActionRegistrationResult.Registered
        val reconnected = registry.resolve(sdkOwner(epoch = 9), registered.ref)
        assertTrue(reconnected is ActionResolveResult.Resolved)
        val otherSigner = sdkOwner(epoch = 10, signer = "another-cert")
        assertEquals(ActionError.OWNER_MISMATCH, (registry.resolve(otherSigner, registered.ref) as ActionResolveResult.Rejected).error)
    }

    @Test fun replacementAndRemovalDoNotSilentlyRetarget() {
        val ref = (registry.registerFromProviderResult(modOwner("session"), shortcutResult("Run", component)) as ActionRegistrationResult.Registered).ref
        inspector.current = InstalledActionTarget("Replaced app", "b".repeat(64), setOf("b".repeat(64)))
        assertEquals(ActionError.TARGET_UNAVAILABLE, (registry.resolve(modOwner("session2"), ref) as ActionResolveResult.Rejected).error)
        inspector.current = null
        assertEquals(ActionError.TARGET_UNAVAILABLE, (registry.resolve(modOwner("session3"), ref) as ActionResolveResult.Rejected).error)
    }

    @Test fun malformedOrDangerousProviderOutputsAreRejected() {
        val implicit = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.test/run"))
        assertEquals(ActionError.INVALID_TARGET,
            (registry.registerFromProviderResult(modOwner("session"), shortcutResult("Bad", null, implicit)) as ActionRegistrationResult.Rejected).error)

        val malformedLauncher = Intent(Intent.ACTION_MAIN)
        assertEquals(ActionError.INVALID_TARGET,
            (registry.registerLauncherResult(modOwner("session"), malformedLauncher) as ActionRegistrationResult.Rejected).error)

        val unsupported = Intent(Intent.ACTION_VIEW, Uri.parse("content://dev.example.automation/private")).apply { component = component }
        assertEquals(ActionError.INVALID_TARGET,
            (registry.registerFromProviderResult(modOwner("session"), shortcutResult("Bad data", null, unsupported)) as ActionRegistrationResult.Rejected).error)

        val localFile = Intent(Intent.ACTION_VIEW, Uri.parse("file:///private/automation.json")).apply { component = component }
        assertEquals(ActionError.INVALID_TARGET,
            (registry.registerFromProviderResult(modOwner("session"), shortcutResult("Bad file", null, localFile)) as ActionRegistrationResult.Rejected).error)

        val parcelableExtra = Intent(Intent.ACTION_VIEW).apply {
            component = this@RegisteredActionRegistryTest.component
            putExtra("not-allowed", android.net.Uri.parse("content://dev.example.automation/private"))
        }
        assertEquals(ActionError.INVALID_TARGET,
            (registry.registerFromProviderResult(modOwner("session"), shortcutResult("Bad extra", null, parcelableExtra)) as ActionRegistrationResult.Rejected).error)

        val tooManyExtras = Intent(Intent.ACTION_VIEW).apply {
            component = this@RegisteredActionRegistryTest.component
            repeat(ActionTargetPolicy.MAX_EXTRA_KEYS + 1) { putExtra("arg-$it", it) }
        }
        assertEquals(ActionError.INVALID_TARGET,
            (registry.registerFromProviderResult(modOwner("session"), shortcutResult("Too many extras", null, tooManyExtras)) as ActionRegistrationResult.Rejected).error)
        assertTrue(store.actions.isEmpty())
    }

    @Test fun registrationHasPerPublisherLimit() {
        val owner = modOwner("session")
        repeat(8) { assertTrue(registry.registerFromProviderResult(owner, shortcutResult("Action $it", component) ) is ActionRegistrationResult.Registered) }
        val overflow = registry.registerFromProviderResult(owner, shortcutResult("Too many", component))
        assertEquals(ActionError.LIMIT_EXCEEDED, (overflow as ActionRegistrationResult.Rejected).error)
        assertEquals(8, store.actions.size)
    }

    @Test fun cancelledSystemSelectionNeverCallsThePersistencePath() {
        var writes = 0
        assertEquals(PickerCommit.Cancelled, commitPickerResult(Activity.RESULT_CANCELED, shortcutResult("Run", component)) { writes++; Unit })
        assertEquals(PickerCommit.Cancelled, commitPickerResult(Activity.RESULT_OK, null) { writes++; Unit })
        assertEquals(0, writes)
        val committed = commitPickerResult(Activity.RESULT_OK, shortcutResult("Run", component)) { writes++; "saved" }
        assertEquals(PickerCommit.Saved("saved"), committed)
        assertEquals(1, writes)
    }

    @Test fun unreadableRegistryFailsClosedInsteadOfReplacingExistingBindings() {
        val broken = object : RegisteredActionStore {
            override fun read(): List<RegisteredAction> = error("corrupt registry")
            override fun write(actions: List<RegisteredAction>): Boolean = error("must not overwrite corrupt registry")
        }
        val guarded = PlatformRegisteredActionRegistry(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(), broken, inspector,
            ActionIntentDispatcher { DispatchAttempt.Accepted }, nextId = sequenceId()
        )
        assertEquals(ActionError.STORAGE_FAILED,
            (guarded.registerFromProviderResult(modOwner("session"), shortcutResult("Run", component)) as ActionRegistrationResult.Rejected).error)
        assertEquals(ActionError.STORAGE_FAILED,
            (guarded.resolve(modOwner("session"), RegisteredActionRef("a".repeat(48))) as ActionResolveResult.Rejected).error)
        assertTrue(guarded.listForOwner(modOwner("session")).isEmpty())
        assertFalse(guarded.revoke(modOwner("session"), RegisteredActionRef("a".repeat(48))))
    }

    private fun shortcutResult(label: String, targetComponent: ComponentName?, target: Intent? = null): Intent {
        val launch = target ?: Intent("dev.example.automation.RUN").apply {
            component = targetComponent
            data = Uri.parse("tasker://run/42")
            addCategory(Intent.CATEGORY_BROWSABLE)
            putExtra("task", "job-42")
            putExtra("options", Bundle().apply { putInt("attempt", 3) })
        }
        return Intent().putExtra(Intent.EXTRA_SHORTCUT_NAME, label).putExtra(Intent.EXTRA_SHORTCUT_INTENT, launch)
    }

    private fun modOwner(session: String, modId: String = "scoreboard") = SessionOwner(
        10123, "top.zainzh.rearscreen", "host-cert", SessionPrincipal.SignedMod("author-fingerprint", modId, session)
    )

    private fun sdkOwner(epoch: Long, signer: String = "sdk-cert") = SessionOwner(
        10234, "dev.example.scoreboard", "sdk-transport-cert", SessionPrincipal.AndroidClient(10234, "dev.example.scoreboard", signer, epoch)
    )

    private class MemoryStore : RegisteredActionStore {
        var actions = emptyList<RegisteredAction>()
        override fun read() = actions.toList()
        override fun write(actions: List<RegisteredAction>): Boolean { this.actions = actions.toList(); return true }
    }

    private class MutableInspector(var current: InstalledActionTarget?) : ActionTargetInspector {
        override fun inspect(component: ComponentName): TargetInspection = current?.let(TargetInspection::Available) ?: TargetInspection.Missing
    }

    private fun sequenceId(): () -> String {
        var n = 0
        return { (++n).toString().padStart(48, '0') }
    }

    @Suppress("DEPRECATION")
    private fun pinItemRequest(result: Intent): LauncherApps.PinItemRequest? = runCatching {
        if (Build.VERSION.SDK_INT >= 33) result.getParcelableExtra(
            LauncherApps.EXTRA_PIN_ITEM_REQUEST, LauncherApps.PinItemRequest::class.java
        ) else result.getParcelableExtra(LauncherApps.EXTRA_PIN_ITEM_REQUEST) as? LauncherApps.PinItemRequest
    }.getOrNull()

}
