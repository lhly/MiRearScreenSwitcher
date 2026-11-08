package top.zainzh.rearscreen.platform

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import top.zainzh.rearscreen.platform.data.DataField
import top.zainzh.rearscreen.platform.data.DataSnapshot
import top.zainzh.rearscreen.platform.data.PlatformDataProvider
import top.zainzh.rearscreen.platform.data.PlatformDataProviders
import top.zainzh.rearscreen.platform.data.DataValue
import top.zainzh.rearscreen.platform.mods.ModPackageStore
import java.io.Closeable
import java.security.MessageDigest
import java.util.concurrent.RejectedExecutionException

internal sealed interface ModDataObserveResult {
    data class Opened(val subscription: Closeable) : ModDataObserveResult
    data class Rejected(val code: String) : ModDataObserveResult
}

/** One process-owned provider with live Mod grant filtering and revocable observer leases. */
internal class ModPlatformDataAccess(private val context: Context) {
    private val app = context.applicationContext
    val provider: PlatformDataProvider = PlatformDataProviders.create(app)
    private val lock = Any()
    private val leases = LinkedHashMap<SessionOwner, MutableSet<DataLease>>()
    private val sdkGrants = SdkDataGrantStore(app)

    fun updateSdkDataConsent(owner: SessionOwner, requested: Set<DataField>, allowed: Set<DataField>) {
        val before = grantedSdkDataFields(owner)
        sdkGrants.updateConsent(owner, requested, allowed)
        val after = grantedSdkDataFields(owner)
        if (!after.containsAll(before)) revoke(owner)
    }

    fun grantedSdkDataFields(owner: SessionOwner): Set<DataField> {
        val grants = sdkGrants.grants(owner)
        return buildSet {
            if (SdkDataGrantStore.DATA_BATTERY in grants) {
                add(DataField.BATTERY_LEVEL_PERCENT); add(DataField.BATTERY_CHARGING)
            }
            if (SdkDataGrantStore.DATA_SYSTEM_METRICS in grants) {
                add(DataField.SYSTEM_CPU_UTILIZATION_PERCENT); add(DataField.SYSTEM_MEMORY_AVAILABLE_BYTES)
                add(DataField.SYSTEM_MEMORY_TOTAL_BYTES); add(DataField.SYSTEM_GPU_UTILIZATION_PERCENT)
            }
        }
    }

    fun ownerForMod(modId: String): SessionOwner? {
        val installed = ModPackageStore.installed(app, modId) ?: return null
        if (!isActiveTrusted(installed)) return null
        return runCatching {
            SessionOwner(Process.myUid(), app.packageName, hostSigningDigest(), SessionPrincipal.SignedMod(
                installed.authorFingerprint, installed.id, installed.logicalSessionId
            ))
        }.getOrNull()
    }

    fun snapshot(owner: SessionOwner, requested: Set<DataField>): DataSnapshot {
        val allowed = authorizedFields(owner).intersect(requested)
        return if (allowed.isEmpty()) emptySnapshot() else provider.snapshot(owner, allowed).filteredTo(allowed)
    }

    fun observe(
        owner: SessionOwner,
        requested: Set<DataField>,
        intervalMs: Long,
        callback: (DataSnapshot) -> Unit
    ): ModDataObserveResult {
        if (requested.isEmpty()) return ModDataObserveResult.Rejected("E_INVALID_STATE")
        val allowed = authorizedFields(owner).intersect(requested)
        if (allowed.isEmpty()) return ModDataObserveResult.Rejected("E_CAPABILITY_DENIED")
        val lease = DataLease(owner, requested, allowed, callback)
        synchronized(lock) { leases.getOrPut(owner) { linkedSetOf() }.add(lease) }
        return try {
            lease.start(intervalMs)
            ModDataObserveResult.Opened(lease)
        } catch (_: RejectedExecutionException) {
            lease.close()
            ModDataObserveResult.Rejected("E_LIMIT_EXCEEDED")
        } catch (_: IllegalArgumentException) {
            lease.close()
            ModDataObserveResult.Rejected("E_LIMIT_EXCEEDED")
        } catch (_: IllegalStateException) {
            lease.close()
            ModDataObserveResult.Rejected("E_SERVICE_UNAVAILABLE")
        } catch (_: Exception) {
            lease.close()
            ModDataObserveResult.Rejected("E_SERVICE_UNAVAILABLE")
        }
    }

    /** Capability revoke, disable, trust revoke, or uninstall clears visible values before stopping sources. */
    fun revoke(owner: SessionOwner) {
        val owned = synchronized(lock) { leases.remove(owner)?.toList().orEmpty() }
        owned.forEach(DataLease::revokeAndClose)
    }

    /** Immediately clears every live view for a Mod after a package/grant/trust lifecycle change. */
    fun revokeMod(modId: String) {
        val owned = synchronized(lock) {
            leases.keys.filter { owner ->
                (owner.principal as? SessionPrincipal.SignedMod)?.modId == modId
            }.flatMap { owner -> leases.remove(owner)?.toList().orEmpty() }
        }
        owned.forEach(DataLease::revokeAndClose)
    }

    private fun authorizedFields(owner: SessionOwner): Set<DataField> {
        if (owner.principal is SessionPrincipal.AndroidClient) return grantedSdkDataFields(owner)
        val principal = owner.principal as? SessionPrincipal.SignedMod ?: return emptySet()
        if (owner.transportUid != Process.myUid() || owner.transportPackageName != app.packageName) return emptySet()
        if (runCatching { owner.transportSigningCertificateDigest != hostSigningDigest() }.getOrDefault(true)) return emptySet()
        val installed = ModPackageStore.installed(app, principal.modId) ?: return emptySet()
        if (!isActiveTrusted(installed) || installed.authorFingerprint != principal.authorFingerprint ||
            installed.logicalSessionId != principal.logicalSessionId) return emptySet()
        return buildSet {
            if ("data.battery" in installed.grantedCapabilities) {
                add(DataField.BATTERY_LEVEL_PERCENT)
                add(DataField.BATTERY_CHARGING)
            }
            if ("data.systemMetrics" in installed.grantedCapabilities) {
                add(DataField.SYSTEM_CPU_UTILIZATION_PERCENT)
                add(DataField.SYSTEM_MEMORY_AVAILABLE_BYTES)
                add(DataField.SYSTEM_MEMORY_TOTAL_BYTES)
                add(DataField.SYSTEM_GPU_UTILIZATION_PERCENT)
            }
        }
    }

    private fun isActiveTrusted(installed: top.zainzh.rearscreen.platform.mods.InstalledMod): Boolean =
        installed.enabled && ModPackageStore.isAuthorTrusted(app, installed.authorFingerprint)

    private fun hostSigningDigest(): String {
        val info = if (Build.VERSION.SDK_INT >= 28) {
            app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION") app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SIGNATURES)
        }
        val certificate = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        else @Suppress("DEPRECATION") info.signatures?.firstOrNull()?.toByteArray()
        return certificate?.let { MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { byte -> "%02x".format(byte) } }
            ?: error("Host signing certificate unavailable")
    }

    private fun remove(lease: DataLease) {
        synchronized(lock) {
            leases[lease.owner]?.let { owned ->
                owned.remove(lease)
                if (owned.isEmpty()) leases.remove(lease.owner)
            }
        }
    }

    private fun emptySnapshot() = DataSnapshot(emptyMap(), SystemClock.elapsedRealtime())

    private inner class DataLease(
        val owner: SessionOwner,
        private val requested: Set<DataField>,
        private val initialAllowed: Set<DataField>,
        private val callback: (DataSnapshot) -> Unit
    ) : Closeable {
        private val deliveryGate = DataLeaseDeliveryGate()
        @Volatile private var source: Closeable? = null

        fun start(intervalMs: Long) {
            source = provider.observe(owner, initialAllowed, intervalMs) { snapshot ->
                deliveryGate.deliver {
                    val currentAllowed = authorizedFields(owner).intersect(requested)
                    if (!currentAllowed.containsAll(initialAllowed)) {
                        revokeAndClose()
                    } else {
                        runCatching { callback(snapshot.filteredTo(currentAllowed)) }
                    }
                }
            }
            if (deliveryGate.isClosed) source?.close()
        }

        override fun close() {
            deliveryGate.close {
                source?.close()
                remove(this)
            }
        }

        fun revokeAndClose() {
            deliveryGate.close {
                source?.close()
                remove(this)
                runCatching { callback(emptySnapshot()) }
            }
        }
    }
}
