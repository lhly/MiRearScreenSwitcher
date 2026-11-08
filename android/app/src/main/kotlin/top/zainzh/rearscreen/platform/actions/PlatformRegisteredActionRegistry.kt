package top.zainzh.rearscreen.platform.actions

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.util.Log
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

internal data class InstalledActionTarget(
    val label: String,
    val currentSignerSha256: String,
    val acceptedSignerSha256s: Set<String>
)

internal sealed interface TargetInspection {
    data class Available(val target: InstalledActionTarget) : TargetInspection
    data object Missing : TargetInspection
    data object NotLaunchable : TargetInspection
}

internal fun interface ActionTargetInspector {
    fun inspect(component: ComponentName): TargetInspection
}

internal fun interface ActionIntentDispatcher {
    fun start(intent: Intent): DispatchAttempt
}

internal sealed interface DispatchAttempt {
    data object Accepted : DispatchAttempt
    data object BackgroundBlocked : DispatchAttempt
    data object Rejected : DispatchAttempt
}

internal class AndroidActionTargetInspector(private val context: Context) : ActionTargetInspector {
    private val packageManager = context.packageManager

    @Suppress("DEPRECATION")
    override fun inspect(component: ComponentName): TargetInspection = try {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getActivityInfo(component, PackageManager.ComponentInfoFlags.of(0))
        } else {
            packageManager.getActivityInfo(component, 0)
        }
        if (!info.enabled || !info.applicationInfo.enabled || !info.exported) return TargetInspection.NotLaunchable
        val packageInfo = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageInfo(component.packageName, PackageManager.PackageInfoFlags.of(packageInfoLookupFlagForSdk(Build.VERSION.SDK_INT).toLong()))
        } else {
            packageManager.getPackageInfo(component.packageName, packageInfoLookupFlagForSdk(Build.VERSION.SDK_INT))
        }
        val (fingerprint, acceptedFingerprints) = if (Build.VERSION.SDK_INT >= 28) {
            val signing = packageInfo.signingInfo ?: return TargetInspection.Missing
            val current = signing.apkContentsSigners.map(::certificateSha256).sorted()
            if (current.isEmpty()) return TargetInspection.Missing
            if (signing.hasMultipleSigners()) {
                // A multi-signer package is identified by the complete signer set, not whichever
                // certificate happened to appear first in PackageManager's returned array.
                val identity = current.joinToString("+")
                identity to setOf(identity)
            } else {
                val currentSigner = current.single()
                val lineage = signing.signingCertificateHistory.orEmpty().map(::certificateSha256).toSet() + currentSigner
                currentSigner to lineage
            }
        } else {
            val current = (packageInfo.signatures ?: emptyArray()).map(::certificateSha256).sorted()
            if (current.isEmpty()) return TargetInspection.Missing
            val identity = current.joinToString("+")
            identity to setOf(identity)
        }
        val label = runCatching { info.loadLabel(packageManager).toString() }.getOrDefault(component.packageName)
        TargetInspection.Available(InstalledActionTarget(label, fingerprint, acceptedFingerprints))
    } catch (_: PackageManager.NameNotFoundException) {
        TargetInspection.Missing
    } catch (_: SecurityException) {
        TargetInspection.Missing
    }

    private fun certificateSha256(signature: Signature): String =
        MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
}

internal fun packageInfoLookupFlagForSdk(sdkInt: Int): Int =
    if (sdkInt >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

private class AndroidActionIntentDispatcher(private val context: Context) : ActionIntentDispatcher {
    override fun start(intent: Intent): DispatchAttempt = try {
        val options = if (Build.VERSION.SDK_INT >= 26) {
            ActivityOptions.makeBasic().setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY).toBundle()
        } else null
        // Always launch from the application context onto display 0. No task id is targeted and no
        // existing package task is killed or explicitly reparented by the host.
        if (options == null) context.startActivity(intent) else context.startActivity(intent, options)
        DispatchAttempt.Accepted
    } catch (_: SecurityException) {
        DispatchAttempt.Rejected
    } catch (_: ActivityNotFoundException) {
        DispatchAttempt.Rejected
    } catch (_: RuntimeException) {
        DispatchAttempt.Rejected
    }
}

/**
 * Owner-private action registry. Call read/write/resolve/launch from a worker thread; none of its
 * package-manager or disk work is intended for a Compose callback on the UI thread.
 */
class PlatformRegisteredActionRegistry internal constructor(
    context: Context,
    private val store: RegisteredActionStore = PrivateFileRegisteredActionStore(context),
    private val inspector: ActionTargetInspector = AndroidActionTargetInspector(context.applicationContext),
    private val dispatcher: ActionIntentDispatcher = AndroidActionIntentDispatcher(context.applicationContext),
    private val nextId: () -> String = ::newOpaqueId
) : RegisteredActionRegistry {
    private val storeLock = Any()
    private val publisherDispatchGates = ConcurrentHashMap<ActionPublisherKey, Any>()

    override fun listForOwner(owner: top.zainzh.rearscreen.platform.SessionOwner): List<RegisteredActionSummary> {
        val publisher = ActionPublisherKey.from(owner)
        val records = synchronized(storeLock) { runCatching { store.read() }.getOrNull() } ?: return emptyList()
        // PackageManager inspection can cross process boundaries; keep it outside the store lock.
        return records.asSequence().filter { it.owner == publisher && !it.revoked }.map { action ->
            val target = inspect(action)
            RegisteredActionSummary(action.ref, action.label, target?.label ?: action.targetPackage, target != null, false)
        }.toList()
    }

    override fun registerFromProviderResult(
        owner: top.zainzh.rearscreen.platform.SessionOwner,
        providerResult: Intent
    ): ActionRegistrationResult {
        val parsed = ActionTargetPolicy.parseProviderResult(providerResult)
            ?: return ActionRegistrationResult.Rejected(ActionError.INVALID_TARGET)
        return registerParsed(owner, parsed)
    }

    override fun registerLauncherResult(
        owner: top.zainzh.rearscreen.platform.SessionOwner,
        selectedActivity: Intent
    ): ActionRegistrationResult {
        val parsed = ActionTargetPolicy.parseSelectedLauncher(selectedActivity)
            ?: return ActionRegistrationResult.Rejected(ActionError.INVALID_TARGET)
        return registerParsed(owner, parsed)
    }

    private fun registerParsed(owner: top.zainzh.rearscreen.platform.SessionOwner,
                               parsed: ActionTargetPolicy.ParsedShortcut): ActionRegistrationResult {
        val publisher = ActionPublisherKey.from(owner)
            ?: return ActionRegistrationResult.Rejected(ActionError.OWNER_MISMATCH)
        val component = parsed.component
        val inspected = (inspector.inspect(component) as? TargetInspection.Available)?.target
            ?: return ActionRegistrationResult.Rejected(ActionError.TARGET_UNAVAILABLE)
        return synchronized(storeLock) {
            val current = runCatching { store.read() }.getOrElse {
                return@synchronized ActionRegistrationResult.Rejected(ActionError.STORAGE_FAILED)
            }
            val retained = if (current.size >= MAX_RECORDS) current.filterNot { it.revoked } else current
            if (current.count { !it.revoked } >= MAX_ACTIVE_ACTIONS ||
                current.count { it.owner == publisher && !it.revoked } >= MAX_ACTIONS_PER_PUBLISHER ||
                (current.size >= MAX_RECORDS && retained.size >= MAX_RECORDS)) return ActionRegistrationResult.Rejected(ActionError.LIMIT_EXCEEDED)
            val id = generateUniqueId(retained)
            RegisteredAction(
                RegisteredActionRef(id, ACTION_REF_VERSION), publisher, parsed.label.ifBlank { inspected.label },
                component.packageName, component.className, inspected.currentSignerSha256, parsed.action,
                parsed.dataUri, parsed.categories, parsed.extras
            ).let { record ->
                if (!store.write(retained + record)) ActionRegistrationResult.Rejected(ActionError.STORAGE_FAILED)
                else ActionRegistrationResult.Registered(record.ref)
            }
        }
    }

    override fun revoke(owner: top.zainzh.rearscreen.platform.SessionOwner, ref: RegisteredActionRef): Boolean {
        val publisher = ActionPublisherKey.from(owner)
        val publisherGate = publisher?.let(::publisherGate) ?: return false
        return synchronized(publisherGate) publisher@{
            synchronized(storeLock) store@{
                val current = runCatching { store.read() }.getOrNull() ?: return@store false
                val index = current.indexOfFirst { it.ref == ref && it.owner == publisher && !it.revoked }
                if (index < 0) false else store.write(current.toMutableList().also { it[index] = it[index].copy(revoked = true) })
            }
        }
    }

    override fun revokeAllForPublisher(owner: top.zainzh.rearscreen.platform.SessionOwner): Int {
        val publisher = ActionPublisherKey.from(owner)
        val publisherGate = publisher?.let(::publisherGate) ?: return 0
        return synchronized(publisherGate) publisher@{
            synchronized(storeLock) store@{
                val current = runCatching { store.read() }.getOrNull() ?: return@store 0
                val updated = current.map { if (it.owner == publisher && !it.revoked) it.copy(revoked = true) else it }
                val count = current.count { it.owner == publisher && !it.revoked }
                if (count == 0) 0 else if (store.write(updated)) count else 0
            }
        }
    }

    override fun resolve(
        owner: top.zainzh.rearscreen.platform.SessionOwner,
        ref: RegisteredActionRef
    ): ActionResolveResult {
        val records = synchronized(storeLock) { runCatching { store.read() }.getOrElse {
            return ActionResolveResult.Rejected(ActionError.STORAGE_FAILED)
        } }
        val record = records.firstOrNull { it.ref.id == ref.id }
            ?: return ActionResolveResult.Rejected(ActionError.NOT_FOUND)
        if (record.ref.version != ref.version) return ActionResolveResult.Rejected(ActionError.NOT_FOUND)
        if (record.owner != ActionPublisherKey.from(owner)) return ActionResolveResult.Rejected(ActionError.OWNER_MISMATCH)
        if (record.revoked) return ActionResolveResult.Rejected(ActionError.REVOKED)
        if (inspect(record) == null) return ActionResolveResult.Rejected(ActionError.TARGET_UNAVAILABLE)
        return ActionResolveResult.Resolved(ResolvedRegisteredAction(record))
    }

    override fun launch(resolved: ResolvedRegisteredAction): ActionDispatchResult {
        val handle = resolved.record
        // Per-publisher serialization makes revoke/revokeAll and dispatch submission linearizable.
        // resolve() is only a lookup; it does not reserve execution. This path is called off the UI
        // and Binder request threads, and the short owner gate is not held around Core authorization.
        return synchronized(publisherGate(handle.owner)) dispatch@{
            val current = runCatching { synchronized(storeLock) { store.read() } }.getOrElse {
                return@dispatch ActionDispatchResult.Rejected(ActionError.STORAGE_FAILED)
            }.firstOrNull { it.ref.id == handle.ref.id }
                ?: return@dispatch ActionDispatchResult.Rejected(ActionError.NOT_FOUND)
            if (current.ref.version != handle.ref.version) return@dispatch ActionDispatchResult.Rejected(ActionError.NOT_FOUND)
            if (current.owner != handle.owner) return@dispatch ActionDispatchResult.Rejected(ActionError.OWNER_MISMATCH)
            if (current.revoked) return@dispatch ActionDispatchResult.Rejected(ActionError.REVOKED)
            // Close the uninstall/replacement race between resolve and startActivity.
            if (inspect(current) == null) return@dispatch ActionDispatchResult.Rejected(ActionError.TARGET_UNAVAILABLE)
            when (dispatcher.start(ActionTargetPolicy.toIntent(current))) {
                DispatchAttempt.Accepted -> ActionDispatchResult.Accepted
                DispatchAttempt.BackgroundBlocked -> ActionDispatchResult.Rejected(ActionError.BACKGROUND_RESTRICTED)
                DispatchAttempt.Rejected -> ActionDispatchResult.Rejected(ActionError.LAUNCH_REJECTED)
            }
        }
    }

    private fun publisherGate(publisher: ActionPublisherKey): Any =
        publisherDispatchGates.computeIfAbsent(publisher) { Any() }

    private fun inspect(action: RegisteredAction): InstalledActionTarget? {
        val result = inspector.inspect(ComponentName(action.targetPackage, action.targetClass)) as? TargetInspection.Available
            ?: return null
        return result.target.takeIf { action.targetSignerSha256 in it.acceptedSignerSha256s }
    }

    private fun generateUniqueId(existing: List<RegisteredAction>): String {
        repeat(4) {
            val candidate = nextId()
            if (candidate.matches(OPAQUE_ID_PATTERN) && existing.none { it.ref.id == candidate }) return candidate
        }
        error("unable to allocate a unique action reference")
    }

    companion object {
        const val ACTION_REF_VERSION = 1
        const val MAX_ACTIVE_ACTIONS = 64
        const val MAX_ACTIONS_PER_PUBLISHER = 8
        const val MAX_RECORDS = 256
        val OPAQUE_ID_PATTERN = Regex("[a-f0-9]{48}")

        @Volatile private var instance: PlatformRegisteredActionRegistry? = null

        fun get(context: Context): PlatformRegisteredActionRegistry = instance ?: synchronized(this) {
            instance ?: PlatformRegisteredActionRegistry(context.applicationContext).also { instance = it }
        }

        fun newOpaqueId(): String = ByteArray(24).also(SecureRandom()::nextBytes)
            .joinToString("") { "%02x".format(it) }
    }
}
