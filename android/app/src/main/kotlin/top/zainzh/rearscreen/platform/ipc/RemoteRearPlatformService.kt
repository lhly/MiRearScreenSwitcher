package top.zainzh.rearscreen.platform.ipc

import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.IBinder
import android.os.RemoteException
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import top.zainzh.rearscreen.platform.*
import top.zainzh.rearscreen.platform.data.DataField
import top.zainzh.rearscreen.platform.data.DataSnapshot
import top.zainzh.rearscreen.platform.data.DataValue
import top.zainzh.rearscreen.SceneActivationConfirmationActivity
import top.zainzh.rearscreen.DataAccessConfirmationActivity
import top.zainzh.rearscreen.NotificationAccessConfirmationActivity
import top.zainzh.rearscreen.platform.notifications.NotificationEndReason
import top.zainzh.rearscreen.platform.notifications.NotificationEvent
import top.zainzh.rearscreen.platform.notifications.NotificationEventStatus
import top.zainzh.rearscreen.platform.notifications.NotificationPriority
import top.zainzh.rearscreen.platform.notifications.NotificationPrivacy
import top.zainzh.rearscreen.platform.notifications.NotificationRequest
import top.zainzh.rearscreen.platform.notifications.NotificationSubmitStatus
import top.zainzh.rearscreen.platform.notifications.NotificationObserver
import top.zainzh.rearscreen.platform.actions.PlatformRegisteredActionRegistry
import top.zainzh.rearscreen.platform.actions.RegisteredActionRef
import top.zainzh.rearscreen.platform.actions.ActionResolveResult
import top.zainzh.rearscreen.platform.actions.RegisteredActionActivation
import top.zainzh.rearscreen.platform.actions.RegisteredActionsActivity
import top.zainzh.rearscreen.platform.actions.RegisteredActionInvocationActivity
import java.io.Closeable
import java.security.MessageDigest
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** The only exported rear-platform surface. All requests are scoped to the calling UID and certificate. */
class RemoteRearPlatformService : Service() {
    private data class ClientConnection(
        val owner: SessionOwner,
        val callback: IRemoteRearPlatformCallback,
        val binder: IBinder,
        @Volatile var dead: Boolean = false,
        var deathRecipient: IBinder.DeathRecipient? = null
    )
    private data class PendingActivation(
        val nonce: String,
        val owner: SessionOwner,
        val request: SceneRequest,
        val connection: ClientConnection,
        val expiresAt: Long
    )
    private data class PendingDataConsent(
        val nonce: String, val owner: SessionOwner, val fields: Set<DataField>,
        val connection: ClientConnection, val expiresAt: Long
    )
    private data class PendingNotificationConsent(
        val nonce: String, val owner: SessionOwner, val connection: ClientConnection, val expiresAt: Long,
        val pendingIntent: PendingIntent
    )
    private data class PendingActionInvocation(
        val nonce: String,
        val owner: SessionOwner,
        val ref: RegisteredActionRef,
        val connection: ClientConnection,
        val expiresAt: Long,
    )
    private class DataSubscription(val connection: ClientConnection) {
        private val lease = DataSubscriptionLease<DataSnapshot>()
        fun deliver(snapshot: DataSnapshot, emit: (DataSnapshot) -> Unit) = lease.deliver(snapshot, emit)
        fun activate(handle: Closeable, emit: (DataSnapshot) -> Unit) = lease.activate(handle, emit)
        fun close() = lease.close()
    }

    private val connections = IdentityHashMap<IBinder, ClientConnection>()
    private val sessions = ConcurrentHashMap<String, ClientConnection>()
    private val dataSubscriptions = ConcurrentHashMap<String, DataSubscription>()
    private val worker = Executors.newCachedThreadPool { r -> Thread(r, "rear-platform-ipc").apply { isDaemon = true } }
    private val binder = object : IRemoteRearPlatformService.Stub() {
        override fun getContractVersion(): String = RearSceneCoordinator.PLATFORM_CONTRACT

        override fun queryCapabilities(callerPackageName: String): String {
            val owner = callerOwner(callerPackageName, Binder.getCallingUid(), epoch = 0L)
            val core = RearCapabilityCore.get(this@RemoteRearPlatformService)
            val snapshot = core.displaySessions.queryCapabilities(owner)
            val granted = core.grantedSdkDataFields(owner)
            val dataCaps = listOf(
                CapabilityState("data.battery", 1, CapabilityAvailability.AVAILABLE,
                    granted.containsAll(setOf(DataField.BATTERY_LEVEL_PERCENT, DataField.BATTERY_CHARGING))),
                CapabilityState("data.systemMetrics", 1, CapabilityAvailability.AVAILABLE,
                    granted.containsAll(setOf(DataField.SYSTEM_CPU_UTILIZATION_PERCENT, DataField.SYSTEM_MEMORY_AVAILABLE_BYTES,
                        DataField.SYSTEM_MEMORY_TOTAL_BYTES)), reasonCode = "gpu_field_unsupported"),
                CapabilityState("action.invoke", 1, CapabilityAvailability.AVAILABLE,
                    PlatformRegisteredActionRegistry.get(this@RemoteRearPlatformService).listForOwner(owner).any { it.available && !it.revoked }),
                CapabilityState("rear.notification", 1, CapabilityAvailability.AVAILABLE,
                    core.isSdkNotificationPublisherGranted(owner))
            )
            return encodeCapabilities(snapshot.copy(capabilities = snapshot.capabilities + dataCaps)).toString()
        }

        override fun queryRearInfo(callerPackageName: String): String {
            val owner = callerOwner(callerPackageName, Binder.getCallingUid(), epoch = 0L)
            val core = RearCapabilityCore.get(this@RemoteRearPlatformService)
            val snapshot = core.displaySessions.queryCapabilities(owner)
            val granted = core.grantedSdkDataFields(owner)
            val capabilities = snapshot.copy(capabilities = snapshot.capabilities + listOf(
                CapabilityState("data.battery", 1, CapabilityAvailability.AVAILABLE,
                    granted.containsAll(setOf(DataField.BATTERY_LEVEL_PERCENT, DataField.BATTERY_CHARGING))),
                CapabilityState("data.systemMetrics", 1, CapabilityAvailability.AVAILABLE,
                    granted.containsAll(setOf(DataField.SYSTEM_CPU_UTILIZATION_PERCENT, DataField.SYSTEM_MEMORY_AVAILABLE_BYTES,
                        DataField.SYSTEM_MEMORY_TOTAL_BYTES)), reasonCode = "gpu_field_unsupported"),
                CapabilityState("action.invoke", 1, CapabilityAvailability.AVAILABLE,
                    PlatformRegisteredActionRegistry.get(this@RemoteRearPlatformService).listForOwner(owner).any { it.available && !it.revoked }),
                CapabilityState("rear.notification", 1, CapabilityAvailability.AVAILABLE,
                    core.isSdkNotificationPublisherGranted(owner))
            ))
            val displayId = runCatching { top.zainzh.rearscreen.RearDisplay.id() }.getOrDefault(-1)
            val displayManager = getSystemService(DISPLAY_SERVICE) as? DisplayManager
            val display = displayManager?.getDisplay(displayId)?.takeIf { displayId > 0 && it.isValid }
            val displayInfo = display?.let { value ->
                val metrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION") value.getRealMetrics(metrics)
                // Display.Mode is the mode's physical raster; getRealMetrics is the current
                // logical display size and may reflect wm size overrides. Never conflate them.
                val mode = runCatching { value.mode }.getOrNull()
                val dimensions = runCatching {
                    RearDisplayDimensions(
                        mode?.physicalWidth?.takeIf { it > 0 }, mode?.physicalHeight?.takeIf { it > 0 },
                        metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, value.rotation
                    )
                }.getOrNull()
                if (dimensions == null) null else JSONObject().put("displayId", value.displayId)
                    .put("dimensions", dimensions?.toJson() ?: JSONObject.NULL)
                    .put("state", when (value.state) {
                        android.view.Display.STATE_ON -> "ON"
                        android.view.Display.STATE_OFF -> "OFF"
                        android.view.Display.STATE_DOZE -> "DOZE"
                        android.view.Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
                        android.view.Display.STATE_VR -> "VR"
                        else -> "UNKNOWN"
                    })
            }
            return JSONObject().put("contract", capabilities.platformContract)
                .put("deviceAvailable", capabilities.deviceAvailable)
                .put("backendConnected", capabilities.backendConnected)
                .put("displayInfoAvailable", displayInfo != null)
                .put("display", displayInfo ?: JSONObject.NULL)
                .put("capabilities", JSONArray().apply {
                    capabilities.capabilities.forEach { capability ->
                        put(JSONObject().put("name", capability.name).put("version", capability.version)
                            .put("availability", capability.availability.name).put("granted", capability.granted)
                            .put("reasonCode", capability.reasonCode))
                    }
                }).toString()
        }

        override fun releaseClient(callerPackageName: String, callback: IRemoteRearPlatformCallback) {
            val client = clientConnection(callerPackageName, Binder.getCallingUid(), callback)
            synchronized(connections) {
                client.dead = true
                pendingActivations.entries.removeAll { it.value.connection === client }
                pendingDataConsents.entries.removeAll { it.value.connection === client }
                closeNotificationConsents(client)
                pendingActionInvocations.entries.removeAll { it.value.connection === client }
                sessions.entries.removeAll { (_, value) -> value === client }
                connections.remove(client.binder, client)
            }
            core().displaySessions.closeOwnerSessions(client.owner, SceneCloseReason.CLIENT_CLOSED)
            closeOwnedNotifications(client, NotificationEndReason.OWNER_CLOSED)
            core().closePlatformData(client.owner)
            closeDataSubscriptions(client)
            client.deathRecipient?.let { runCatching { client.binder.unlinkToDeath(it, 0) } }
        }

        override fun requestSceneActivation(
            callerPackageName: String,
            requestJson: String,
            callback: IRemoteRearPlatformCallback
        ): PendingIntent {
            val uid = Binder.getCallingUid()
            val client = clientConnection(callerPackageName, uid, callback)
            val request = parseSceneRequest(requestJson, callerPackageName)
            val capabilities = RearCapabilityCore.get(this@RemoteRearPlatformService)
                .awaitSceneCapabilitiesForActivation(client.owner)
            val sceneAvailability = capabilities.capabilities.firstOrNull { it.name == "rear.scene" }?.availability
            when (sceneAvailability) {
                CapabilityAvailability.AVAILABLE -> Unit
                CapabilityAvailability.CONNECTING -> throw IllegalStateException("E_BACKEND_CONNECTING: rear.scene backend is still connecting")
                CapabilityAvailability.REQUIRES_AUTHORIZATION -> throw IllegalStateException("E_BACKEND_REQUIRES_AUTHORIZATION: selected privilege backend needs authorization")
                else -> throw IllegalStateException("E_BACKEND_UNAVAILABLE: rear.scene is not available")
            }
            val nonce = newOpaqueId()
            val pending = PendingActivation(nonce, client.owner, request, client, SystemClock.elapsedRealtime() + ACTIVATION_INTENT_TTL_MS)
            pendingActivations[nonce] = pending
            val intent = Intent(this@RemoteRearPlatformService, SceneActivationConfirmationActivity::class.java)
                .putExtra(EXTRA_ACTIVATION_NONCE, nonce)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            return PendingIntent.getActivity(
                this@RemoteRearPlatformService,
                nextPendingIntentRequestCode.incrementAndGet().toInt(),
                intent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        override fun reportSceneWindowReady(
            callerPackageName: String,
            launchTicket: String,
            windowReportJson: String,
            callback: IRemoteRearPlatformCallback
        ): String {
            val client = clientConnection(callerPackageName, Binder.getCallingUid(), callback)
            val report = parseWindowReport(windowReportJson, client.owner.transportPackageName)
            val launch = core().resolveSceneLaunchForHostActivity(launchTicket, report.component)
                ?: return errorResult("E_STALE_SESSION")
            if (launch.owner != client.owner) return errorResult("E_CAPABILITY_DENIED")
            val result = core().displaySessions.reportSceneWindowReady(client.owner, launchTicket, report)
            if (result.success) {
                val stillConnected = synchronized(connections) {
                    if (!client.dead && connections[client.binder] === client) {
                        sessions[launch.sessionId] = client
                        true
                    } else false
                }
                if (!stillConnected || !core().ownsActiveSceneSession(client.owner, launch.sessionId)) {
                    sessions.remove(launch.sessionId, client)
                    core().displaySessions.closeScene(client.owner, launch.sessionId)
                }
            }
            return encodeResult(result).toString()
        }

        override fun reportSceneViewport(
            callerPackageName: String,
            sessionId: String,
            generation: Long,
            windowReportJson: String,
            callback: IRemoteRearPlatformCallback
        ): String {
            val client = clientConnection(callerPackageName, Binder.getCallingUid(), callback)
            if (sessions[sessionId] !== client) return errorResult("E_CAPABILITY_DENIED")
            return encodeResult(core().displaySessions.reportSceneViewport(
                client.owner, sessionId, generation, parseWindowReport(windowReportJson, callerPackageName)
            )).toString()
        }

        override fun attachSceneObserver(
            callerPackageName: String,
            sessionId: String,
            generation: Long,
            callback: IRemoteRearPlatformCallback
        ): String {
            val client = clientConnection(callerPackageName, Binder.getCallingUid(), callback)
            if (sessions[sessionId] !== client) return errorResult("E_CAPABILITY_DENIED")
            return encodeResult(core().displaySessions.attachSceneObserver(client.owner, sessionId, generation, observer(client))).toString()
        }

        override fun reportSceneVisibility(
            callerPackageName: String,
            sessionId: String,
            generation: Long,
            visible: Boolean,
            callback: IRemoteRearPlatformCallback
        ): String {
            val client = clientConnection(callerPackageName, Binder.getCallingUid(), callback)
            if (sessions[sessionId] !== client) return errorResult("E_CAPABILITY_DENIED")
            return encodeResult(core().displaySessions.reportSceneVisibility(client.owner, sessionId, generation, visible)).toString()
        }

        override fun queryScenePresentation(
            callerPackageName: String,
            sessionId: String,
            generation: Long,
            callback: IRemoteRearPlatformCallback
        ): String {
            val client = clientConnection(callerPackageName, Binder.getCallingUid(), callback)
            if (sessions[sessionId] !== client) return errorResult("E_CAPABILITY_DENIED")
            val snapshot = core().displaySessions.queryScenePresentation(client.owner, sessionId, generation)
                ?: return errorResult("E_STALE_SESSION")
            return JSONObject().put("success", true)
                .put("sessionId", snapshot.sessionId)
                .put("generation", snapshot.generation)
                .put("revision", snapshot.revision)
                .put("visible", snapshot.visible)
                .put("hiddenReason", snapshot.hiddenReason?.name ?: JSONObject.NULL)
                .toString()
        }

        override fun closeScene(callerPackageName: String, sessionId: String, callback: IRemoteRearPlatformCallback): String {
            val client = clientConnection(callerPackageName, Binder.getCallingUid(), callback)
            if (sessions[sessionId] !== client) return errorResult("E_CAPABILITY_DENIED")
            val result = core().displaySessions.closeScene(client.owner, sessionId)
            if (result.success) sessions.remove(sessionId, client)
            return encodeResult(result).toString()
        }

        override fun requestDataAuthorization(callerPackageName: String, fieldsJson: String,
                                              callback: IRemoteRearPlatformCallback): PendingIntent {
            val client = clientConnection(callerPackageName, Binder.getCallingUid(), callback)
            val fields = parseDataFields(fieldsJson)
            if (fields.isEmpty()) throw IllegalArgumentException("E_INVALID_STATE: empty data fields")
            val nonce = newOpaqueId()
            pendingDataConsents[nonce] = PendingDataConsent(nonce, client.owner, fields, client,
                SystemClock.elapsedRealtime() + DATA_CONSENT_TTL_MS)
            val intent = Intent(this@RemoteRearPlatformService, DataAccessConfirmationActivity::class.java)
                .putExtra(EXTRA_DATA_CONSENT_NONCE, nonce)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            return PendingIntent.getActivity(this@RemoteRearPlatformService, nextPendingIntentRequestCode.incrementAndGet().toInt(), intent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
        }

        override fun requestNotificationAuthorization(callerPackageName: String, callback: IRemoteRearPlatformCallback): PendingIntent {
            val client = clientConnection(callerPackageName, Binder.getCallingUid(), callback)
            pruneNotificationConsents()
            synchronized(pendingNotificationConsents) {
                val ownerCount = pendingNotificationConsents.values.count { it.connection === client }
                if (ownerCount >= MAX_PENDING_NOTIFICATION_CONSENTS_PER_CLIENT ||
                    pendingNotificationConsents.size >= MAX_PENDING_NOTIFICATION_CONSENTS)
                    throw IllegalStateException("E_QUOTA_EXCEEDED: notification authorization requests")
            }
            val nonce = newOpaqueId()
            val intent = Intent(this@RemoteRearPlatformService, NotificationAccessConfirmationActivity::class.java)
                .putExtra(EXTRA_NOTIFICATION_CONSENT_NONCE, nonce)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            val pendingIntent = PendingIntent.getActivity(this@RemoteRearPlatformService,
                nextPendingIntentRequestCode.incrementAndGet().toInt(), intent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
            synchronized(pendingNotificationConsents) {
                // Recheck under the insertion lock so simultaneous requests cannot exceed the cap.
                val ownerCount = pendingNotificationConsents.values.count { it.connection === client }
                if (ownerCount >= MAX_PENDING_NOTIFICATION_CONSENTS_PER_CLIENT ||
                    pendingNotificationConsents.size >= MAX_PENDING_NOTIFICATION_CONSENTS)
                    throw IllegalStateException("E_QUOTA_EXCEEDED: notification authorization requests").also { pendingIntent.cancel() }
                pendingNotificationConsents[nonce] = PendingNotificationConsent(nonce, client.owner, client,
                    SystemClock.elapsedRealtime() + NOTIFICATION_CONSENT_TTL_MS, pendingIntent)
            }
            return pendingIntent
        }

        override fun publishNotification(callerPackageName: String, requestJson: String, callback: IRemoteRearPlatformCallback): String {
            val uid = Binder.getCallingUid()
            val client = clientConnection(callerPackageName, uid, callback)
            val request = parseNotificationRequest(requestJson)
            val stillCurrent = {
                synchronized(connections) { !client.dead && connections[client.binder] === client } && isCurrentClientIdentity(client)
            }
            val owner = core().sdkNotificationOwner(client.owner, stillCurrent)
            val observer = NotificationObserver { event ->
                if (stillCurrent()) send(client, encodeNotificationEvent(event))
            }
            val result = core().ownedNotifications.publish(owner, request, observer)
            return JSONObject().put("status", result.status.name).put("id", result.id)
                .put("requestId", result.requestId ?: JSONObject.NULL).put("reason", result.reason ?: JSONObject.NULL).toString()
        }

        override fun cancelNotification(callerPackageName: String, notificationId: String, callback: IRemoteRearPlatformCallback): String {
            val client = clientConnection(callerPackageName, Binder.getCallingUid(), callback)
            val stillCurrent = {
                synchronized(connections) { !client.dead && connections[client.binder] === client } && isCurrentClientIdentity(client)
            }
            val owner = core().sdkNotificationOwner(client.owner, stillCurrent)
            val result = core().ownedNotifications.cancel(owner, notificationId, NotificationEndReason.CANCELLED)
            return JSONObject().put("status", result.status.name).put("id", result.id)
                .put("requestId", result.requestId ?: JSONObject.NULL).put("reason", result.reason ?: JSONObject.NULL).toString()
        }

        override fun queryData(callerPackageName: String, fieldsJson: String, callback: IRemoteRearPlatformCallback): String {
            val client = clientConnection(callerPackageName, Binder.getCallingUid(), callback)
            val fields = parseDataFields(fieldsJson)
            if (fields.isEmpty()) return errorResult("E_INVALID_STATE")
            val core = core()
            if (!core.grantedSdkDataFields(client.owner).containsAll(fields)) return errorResult("E_CAPABILITY_DENIED")
            return encodeDataSnapshot(core.snapshotPlatformData(client.owner, fields)).put("success", true).toString()
        }

        override fun observeData(callerPackageName: String, fieldsJson: String, intervalMs: Long,
                                 callback: IRemoteRearPlatformCallback): String {
            val client = clientConnection(callerPackageName, Binder.getCallingUid(), callback)
            val fields = parseDataFields(fieldsJson)
            if (fields.isEmpty()) return errorResult("E_INVALID_STATE")
            val core = core()
            if (!core.grantedSdkDataFields(client.owner).containsAll(fields)) return errorResult("E_CAPABILITY_DENIED")
            val subscriptionId = newOpaqueId()
            val subscription = DataSubscription(client)
            dataSubscriptions[subscriptionId] = subscription
            val connected = synchronized(connections) {
                !client.dead && connections[client.binder] === client
            }
            if (!connected) {
                dataSubscriptions.remove(subscriptionId, subscription)
                subscription.close()
                return errorResult("E_CLIENT_DIED")
            }
            val result = try { core.observePlatformData(client.owner, fields, intervalMs) { snapshot ->
                subscription.deliver(snapshot) { delivered ->
                    if (!client.dead && dataSubscriptions[subscriptionId] === subscription)
                        send(client, encodeDataSnapshot(delivered).put("type", "data").put("subscriptionId", subscriptionId))
                }
            } } catch (_: Exception) {
                dataSubscriptions.remove(subscriptionId, subscription)
                subscription.close()
                return errorResult("E_SERVICE_UNAVAILABLE")
            }
            val source = when (result) {
                is ModDataObserveResult.Opened -> result.subscription
                is ModDataObserveResult.Rejected -> {
                    dataSubscriptions.remove(subscriptionId, subscription)
                    subscription.close()
                    return errorResult(result.code)
                }
            }
            val activated = try {
                subscription.activate(source) { delivered ->
                    if (!client.dead && dataSubscriptions[subscriptionId] === subscription)
                        send(client, encodeDataSnapshot(delivered).put("type", "data").put("subscriptionId", subscriptionId))
                }
            } catch (_: Exception) {
                dataSubscriptions.remove(subscriptionId, subscription)
                runCatching { subscription.close() }
                return errorResult("E_SERVICE_UNAVAILABLE")
            }
            if (!activated) {
                dataSubscriptions.remove(subscriptionId, subscription)
                return errorResult("E_CLIENT_DIED")
            }
            return JSONObject().put("success", true).put("subscriptionId", subscriptionId).toString()
        }

        override fun closeDataObservation(callerPackageName: String, subscriptionId: String,
                                          callback: IRemoteRearPlatformCallback): String {
            val client = clientConnection(callerPackageName, Binder.getCallingUid(), callback)
            val subscription = dataSubscriptions[subscriptionId] ?: return errorResult("E_STALE_SESSION")
            if (subscription.connection !== client) return errorResult("E_CAPABILITY_DENIED")
            if (!dataSubscriptions.remove(subscriptionId, subscription)) return errorResult("E_STALE_SESSION")
            return try {
                subscription.close()
                JSONObject().put("success", true).toString()
            } catch (_: Exception) {
                errorResult("E_SERVICE_UNAVAILABLE")
            }
        }

        override fun requestRegisteredActionSelection(
            callerPackageName: String,
            callback: IRemoteRearPlatformCallback,
        ): PendingIntent {
            val client = clientConnection(callerPackageName, Binder.getCallingUid(), callback)
            val intent = RegisteredActionsActivity.createIntent(this@RemoteRearPlatformService, client.owner, selectAction = true)
                .addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            return PendingIntent.getActivity(
                this@RemoteRearPlatformService,
                nextPendingIntentRequestCode.incrementAndGet().toInt(),
                intent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        override fun requestRegisteredActionInvocation(
            callerPackageName: String,
            refId: String,
            refVersion: Int,
            callback: IRemoteRearPlatformCallback,
        ): PendingIntent {
            val client = clientConnection(callerPackageName, Binder.getCallingUid(), callback)
            require(refId.matches(PlatformRegisteredActionRegistry.OPAQUE_ID_PATTERN) && refVersion == PlatformRegisteredActionRegistry.ACTION_REF_VERSION) {
                "E_INVALID_STATE: invalid registered action ref"
            }
            val ref = RegisteredActionRef(refId, refVersion)
            val registry = PlatformRegisteredActionRegistry.get(this@RemoteRearPlatformService)
            if (registry.resolve(client.owner, ref) !is ActionResolveResult.Resolved)
                throw SecurityException("E_CAPABILITY_DENIED: action ref is unavailable to this publisher")
            val now = SystemClock.elapsedRealtime()
            val nonce = newOpaqueId()
            synchronized(pendingActionInvocations) {
                pendingActionInvocations.entries.removeAll { it.value.expiresAt <= now || it.value.connection.dead }
                if (pendingActionInvocations.values.count { it.connection === client } >= MAX_PENDING_ACTION_INVOCATIONS_PER_CLIENT)
                    throw IllegalStateException("E_LIMIT_EXCEEDED: too many pending action confirmations")
                pendingActionInvocations[nonce] = PendingActionInvocation(
                    nonce, client.owner, ref, client, now + ACTION_INVOCATION_TTL_MS
                )
            }
            val intent = Intent(this@RemoteRearPlatformService, RegisteredActionInvocationActivity::class.java)
                .putExtra(EXTRA_ACTION_INVOCATION_NONCE, nonce)
                .addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            return PendingIntent.getActivity(
                this@RemoteRearPlatformService,
                nextPendingIntentRequestCode.incrementAndGet().toInt(),
                intent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeService = this
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        pendingActivations.clear()
        pendingDataConsents.clear()
        closeNotificationConsents()
        pendingActionInvocations.clear()
        val active = synchronized(connections) { connections.values.distinctBy { it.owner }.toList().also { connections.clear() } }
        active.forEach {
            core().displaySessions.closeOwnerSessions(it.owner, SceneCloseReason.HOST_DIED)
            closeOwnedNotifications(it, NotificationEndReason.OWNER_DIED)
            core().closePlatformData(it.owner)
            closeDataSubscriptions(it)
        }
        sessions.clear()
        worker.shutdownNow()
        activeService = null
        super.onDestroy()
    }

    private fun core() = RearCapabilityCore.get(this)

    private fun closeOwnedNotifications(client: ClientConnection, reason: NotificationEndReason) {
        runCatching {
            val owner = core().sdkNotificationOwner(client.owner) { false }
            core().ownedNotifications.closeOwner(owner, reason)
        }
    }

    private fun parseNotificationRequest(json: String): NotificationRequest {
        require(json.toByteArray(Charsets.UTF_8).size <= 2_048) { "E_LIMIT_EXCEEDED: notification request" }
        val value = JSONObject(json)
        require(value.keys().asSequence().toSet().all { it in setOf("id", "title", "text", "durationMs", "priority", "privacy") }) {
            "E_INVALID_ARGUMENT: notification fields"
        }
        val duration = value.optLong("durationMs", 8_000L)
        val priority = runCatching { NotificationPriority.valueOf(value.optString("priority", "NORMAL")) }
            .getOrElse { throw IllegalArgumentException("E_INVALID_ARGUMENT: priority") }
        val privacy = runCatching { NotificationPrivacy.valueOf(value.optString("privacy", "STANDARD")) }
            .getOrElse { throw IllegalArgumentException("E_INVALID_ARGUMENT: privacy") }
        return NotificationRequest(value.getString("id"), value.getString("title"), value.optString("text", ""), duration, priority, privacy)
    }

    private fun encodeNotificationEvent(event: NotificationEvent): JSONObject = JSONObject()
        .put("type", "notificationEvent").put("requestId", event.requestId).put("id", event.id)
        .put("status", event.status.name).put("reason", event.reason ?: JSONObject.NULL)
        .put("endedBy", event.endedBy?.name ?: JSONObject.NULL)

    private fun closeDataSubscriptions(client: ClientConnection) {
        dataSubscriptions.entries.filter { it.value.connection === client }.forEach { (id, subscription) ->
            if (dataSubscriptions.remove(id, subscription)) runCatching { subscription.close() }
        }
    }

    private fun callerOwner(packageName: String, uid: Int, epoch: Long): SessionOwner {
        val packages = packageManager.getPackagesForUid(uid)?.toSet().orEmpty()
        if (packageName !in packages) throw SecurityException("E_CALLER_IDENTITY: package does not belong to Binder UID")
        val digest = signingDigest(packageName)
        return SessionOwner(uid, packageName, digest, SessionPrincipal.AndroidClient(uid, packageName, digest, epoch))
    }

    private fun isCurrentClientIdentity(client: ClientConnection): Boolean = runCatching {
        !client.dead && client.owner.transportPackageName in
            (packageManager.getPackagesForUid(client.owner.transportUid)?.toSet().orEmpty()) &&
            signingDigest(client.owner.transportPackageName) == client.owner.transportSigningCertificateDigest
    }.getOrDefault(false)

    private fun clientConnection(packageName: String, uid: Int, callback: IRemoteRearPlatformCallback): ClientConnection {
        val digest = callerOwner(packageName, uid, 0L).transportSigningCertificateDigest
        val clientBinder = callback.asBinder()
        synchronized(connections) {
            connections[clientBinder]?.let { existing ->
                if (!existing.dead && existing.owner.transportUid == uid && existing.owner.transportPackageName == packageName &&
                    existing.owner.transportSigningCertificateDigest == digest) return existing
                if (!existing.dead) throw SecurityException("E_CALLER_IDENTITY: callback Binder belongs to another client")
            }
            val epoch = nextEpoch.incrementAndGet()
            val owner = callerOwner(packageName, uid, epoch)
            val connection = ClientConnection(owner, callback, clientBinder)
            val death = IBinder.DeathRecipient {
                synchronized(connections) {
                    connection.dead = true
                    pendingActivations.entries.removeAll { it.value.connection === connection }
                    pendingDataConsents.entries.removeAll { it.value.connection === connection }
                    closeNotificationConsents(connection)
                    sessions.entries.removeAll { (_, value) -> value === connection }
                    connections.remove(clientBinder, connection)
                }
                core().displaySessions.closeOwnerSessions(owner, SceneCloseReason.CLIENT_DIED)
                closeOwnedNotifications(connection, NotificationEndReason.OWNER_DIED)
                core().closePlatformData(owner)
                closeDataSubscriptions(connection)
            }
            try { clientBinder.linkToDeath(death, 0) } catch (e: RemoteException) {
                connection.dead = true
                throw IllegalStateException("E_CLIENT_DIED", e)
            }
            connection.deathRecipient = death
            connections[clientBinder] = connection
            return connection
        }
    }

    private fun parseSceneRequest(json: String, callerPackage: String): SceneRequest {
        require(json.length <= MAX_REQUEST_JSON_LENGTH) { "E_LIMIT_EXCEEDED: request" }
        val obj = JSONObject(json)
        val component = ComponentName.unflattenFromString(obj.getString("component"))
            ?: throw IllegalArgumentException("E_INVALID_COMPONENT")
        if (component.packageName != callerPackage) throw SecurityException("E_INVALID_COMPONENT: component must belong to caller")
        val region = runCatching { RearRegion.valueOf(obj.optString("region", RearRegion.CAMERA_SAFE.name)) }
            .getOrElse { throw IllegalArgumentException("E_INVALID_STATE: region") }
        val caps = obj.optJSONArray("requiredCapabilities") ?: JSONArray()
        if (caps.length() > MAX_REQUIREMENTS) throw IllegalArgumentException("E_LIMIT_EXCEEDED: requiredCapabilities")
        val requirements = (0 until caps.length()).map { index ->
            val cap = caps.getJSONObject(index)
            CapabilityRequirement(cap.getString("name"), cap.getInt("minimumVersion")).also {
                if (!CAPABILITY_NAME.matches(it.name) || it.minimumVersion < 1) throw IllegalArgumentException("E_INVALID_STATE: capability")
            }
        }.toSet()
        return SceneRequest(component, region, obj.optBoolean("keepVisible", true), requirements)
    }

    private fun parseDataFields(json: String): Set<DataField> {
        require(json.length <= MAX_REQUEST_JSON_LENGTH) { "E_LIMIT_EXCEEDED: fields" }
        val array = JSONArray(json)
        if (array.length() > DataField.entries.size) throw IllegalArgumentException("E_LIMIT_EXCEEDED: fields")
        return (0 until array.length()).map { index ->
            runCatching { DataField.valueOf(array.getString(index)) }
                .getOrElse { throw IllegalArgumentException("E_INVALID_STATE: data field") }
        }.toSet()
    }

    private fun encodeDataSnapshot(snapshot: DataSnapshot) = JSONObject().apply {
        put("sampledAtElapsedRealtimeMs", snapshot.sampledAtElapsedRealtimeMs)
        put("fields", JSONObject().apply {
            snapshot.fields.forEach { (field, value) ->
                val encoded = when (value) {
                    is DataValue.Number -> JSONObject().put("type", "number").put("value", value.value).put("unit", value.unit)
                    is DataValue.Flag -> JSONObject().put("type", "flag").put("value", value.value)
                    is DataValue.Unavailable -> JSONObject().put("type", "unavailable").put("reason", value.reason.name)
                }.put("sampledAtElapsedRealtimeMs", value.sampledAtElapsedRealtimeMs)
                put(field.name, encoded)
            }
        })
    }

    private fun parseWindowReport(json: String, expectedPackage: String): SceneWindowReport {
        require(json.length <= MAX_WINDOW_JSON_LENGTH) { "E_LIMIT_EXCEEDED: window" }
        val root = JSONObject(json)
        val component = ComponentName.unflattenFromString(root.getString("component"))
            ?: throw IllegalArgumentException("E_INVALID_COMPONENT")
        if (component.packageName != expectedPackage) throw SecurityException("E_INVALID_COMPONENT")
        val value = root.getJSONObject("viewport")
        fun size(name: String): PixelSize? = value.optJSONObject(name)?.let { PixelSize(it.getInt("width"), it.getInt("height")) }
        val bounds = value.getJSONObject("contentBoundsPx")
        val windowDp = value.getJSONObject("windowDp")
        val transform = value.getJSONObject("inputTransform")
        val viewport = RearViewportSnapshot(
            revision = value.getLong("revision"),
            displayId = value.getInt("displayId"),
            physicalDisplayPx = size("physicalDisplayPx"),
            contentBoundsPx = PixelRect(bounds.getInt("left"), bounds.getInt("top"), bounds.getInt("right"), bounds.getInt("bottom")),
            windowPx = value.getJSONObject("windowPx").let { PixelSize(it.getInt("width"), it.getInt("height")) },
            windowDp = DpSize(windowDp.getDouble("width").toFloat(), windowDp.getDouble("height").toFloat()),
            densityDpi = value.getInt("densityDpi"),
            renderBufferPx = size("renderBufferPx"),
            displayRotation = value.getInt("displayRotation"), contentRotation = value.getInt("contentRotation"),
            inputTransform = CoordinateTransform(
                transform.getDouble("scaleX").toFloat(), transform.getDouble("skewX").toFloat(), transform.getDouble("translateX").toFloat(),
                transform.getDouble("skewY").toFloat(), transform.getDouble("scaleY").toFloat(), transform.getDouble("translateY").toFloat()
            )
        )
        return SceneWindowReport(component, root.getInt("taskId"), viewport)
    }

    private fun observer(connection: ClientConnection) = object : SceneObserver {
        override fun onSceneEvent(sessionId: String, generation: Long, event: SceneEvent) {
            if (event is SceneEvent.Closed && !connection.dead) sessions.remove(sessionId, connection)
            runCatching { connection.callback.onEvent(encodeEvent(sessionId, generation, event).toString()) }
        }
    }

    private fun encodeCapabilities(snapshot: CapabilitySnapshot) = JSONObject()
        .put("contract", snapshot.platformContract).put("deviceAvailable", snapshot.deviceAvailable)
        .put("backendConnected", snapshot.backendConnected).put("capabilities", JSONArray().apply {
            snapshot.capabilities.forEach { cap -> put(JSONObject().put("name", cap.name).put("version", cap.version)
                .put("availability", cap.availability.name).put("granted", cap.granted).put("reasonCode", cap.reasonCode)) }
        })

    private fun encodeEvent(sessionId: String, generation: Long, event: SceneEvent) = JSONObject()
        .put("sessionId", sessionId).put("generation", generation).apply {
            when (event) {
                is SceneEvent.Ready -> put("type", "ready").put("viewport", encodeViewport(event.viewport))
                is SceneEvent.ViewportChanged -> put("type", "viewportChanged").put("revision", event.revision).put("viewport", encodeViewport(event.viewport))
                is SceneEvent.VisibilityChanged -> put("type", "visibilityChanged").put("visible", event.visible)
                is SceneEvent.Suspended -> put("type", "suspended").put("reason", event.reason.name)
                SceneEvent.Restored -> put("type", "restored")
                SceneEvent.BackendLost -> put("type", "backendLost")
                is SceneEvent.Closed -> put("type", "closed").put("reason", event.reason.name)
            }
        }

    private fun encodeViewport(viewport: RearViewportSnapshot) = JSONObject().apply {
        put("revision", viewport.revision).put("displayId", viewport.displayId)
        fun px(size: PixelSize?) = size?.let { JSONObject().put("width", it.width).put("height", it.height) }
        put("physicalDisplayPx", px(viewport.physicalDisplayPx))
        put("contentBoundsPx", JSONObject().put("left", viewport.contentBoundsPx.left).put("top", viewport.contentBoundsPx.top)
            .put("right", viewport.contentBoundsPx.right).put("bottom", viewport.contentBoundsPx.bottom))
        put("windowPx", px(viewport.windowPx)).put("windowDp", JSONObject().put("width", viewport.windowDp.width).put("height", viewport.windowDp.height))
        put("densityDpi", viewport.densityDpi).put("renderBufferPx", px(viewport.renderBufferPx))
        put("displayRotation", viewport.displayRotation).put("contentRotation", viewport.contentRotation)
        put("inputTransform", JSONObject().put("scaleX", viewport.inputTransform.scaleX).put("skewX", viewport.inputTransform.skewX)
            .put("translateX", viewport.inputTransform.translateX).put("skewY", viewport.inputTransform.skewY)
            .put("scaleY", viewport.inputTransform.scaleY).put("translateY", viewport.inputTransform.translateY))
    }

    private fun encodeResult(result: SceneOperationResult) = JSONObject().put("success", result.success)
        .put("error", result.error?.let { "E_${it.name}" }).put("retryable", result.retryable)
    private fun errorResult(code: String) = JSONObject().put("success", false).put("error", code).put("retryable", false).toString()
    private fun signingDigest(packageName: String): String {
        val packageInfo = if (android.os.Build.VERSION.SDK_INT >= 28) {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION") packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        }
        val signatures = if (android.os.Build.VERSION.SDK_INT >= 28) packageInfo.signingInfo?.apkContentsSigners else {
            @Suppress("DEPRECATION") packageInfo.signatures
        }
        if (signatures.isNullOrEmpty()) throw SecurityException("E_CALLER_IDENTITY: missing signer")
        val digest = MessageDigest.getInstance("SHA-256").digest(signatures.first().toByteArray())
        return digest.joinToString("") { "%02X".format(it) }
    }

    companion object {
        const val EXTRA_ACTIVATION_NONCE = "remrss_scene_activation_nonce"
        const val EXTRA_DATA_CONSENT_NONCE = "remrss_data_consent_nonce"
        const val EXTRA_ACTION_INVOCATION_NONCE = "remrss_registered_action_invocation_nonce"
        const val EXTRA_NOTIFICATION_CONSENT_NONCE = "remrss_notification_consent_nonce"
        const val ACTIVATION_INTENT_TTL_MS = 60_000L
        const val DATA_CONSENT_TTL_MS = 60_000L
        const val ACTION_INVOCATION_TTL_MS = 60_000L
        const val NOTIFICATION_CONSENT_TTL_MS = 60_000L
        const val MAX_PENDING_ACTION_INVOCATIONS_PER_CLIENT = 16
        private const val MAX_PENDING_NOTIFICATION_CONSENTS_PER_CLIENT = 4
        private const val MAX_PENDING_NOTIFICATION_CONSENTS = 64
        private const val MAX_REQUEST_JSON_LENGTH = 4096
        private const val MAX_WINDOW_JSON_LENGTH = 8192
        private const val MAX_REQUIREMENTS = 16
        private val CAPABILITY_NAME = Regex("[a-z][a-z0-9_.-]{0,63}")
        private val nextEpoch = AtomicLong(0)
        private val nextPendingIntentRequestCode = AtomicLong(0)
        private val pendingActivations = ConcurrentHashMap<String, PendingActivation>()
        private val pendingDataConsents = ConcurrentHashMap<String, PendingDataConsent>()
        private val pendingNotificationConsents = ConcurrentHashMap<String, PendingNotificationConsent>()
        private val pendingActionInvocations = ConcurrentHashMap<String, PendingActionInvocation>()
        @Volatile private var activeService: RemoteRearPlatformService? = null

        private fun pruneNotificationConsents() {
            val now = SystemClock.elapsedRealtime()
            synchronized(pendingNotificationConsents) {
                val expired = pendingNotificationConsents.values.filter { now >= it.expiresAt || it.connection.dead }
                expired.forEach { pending ->
                    if (pendingNotificationConsents.remove(pending.nonce, pending)) pending.pendingIntent.cancel()
                }
            }
        }

        private fun closeNotificationConsents(connection: ClientConnection? = null) {
            synchronized(pendingNotificationConsents) {
                val owned = pendingNotificationConsents.values.filter { connection == null || it.connection === connection }
                owned.forEach { pending ->
                    if (pendingNotificationConsents.remove(pending.nonce, pending)) pending.pendingIntent.cancel()
                }
            }
        }

        internal fun notificationConsentDetails(nonce: String): Pair<String, Boolean>? = activeService?.let { service ->
            pruneNotificationConsents()
            val pending = pendingNotificationConsents[nonce] ?: return@let null
            if (SystemClock.elapsedRealtime() >= pending.expiresAt || !service.isCurrentClientIdentity(pending.connection)) return@let null
            val label = runCatching { service.packageManager.getApplicationLabel(
                service.packageManager.getApplicationInfo(pending.owner.transportPackageName, 0)).toString() }
                .getOrDefault(pending.owner.transportPackageName)
            val granted = service.core().isSdkNotificationPublisherGranted(pending.owner)
            label to granted
        }

        internal fun completeNotificationConsent(nonce: String, granted: Boolean?) {
            val service = activeService ?: return
            pruneNotificationConsents()
            val pending = pendingNotificationConsents.remove(nonce) ?: return
            pending.pendingIntent.cancel()
            if (SystemClock.elapsedRealtime() >= pending.expiresAt || !service.isCurrentClientIdentity(pending.connection)) {
                service.send(pending.connection, JSONObject().put("type", "notificationAuthorization").put("code", "E_STALE_SESSION"))
                return
            }
            if (granted == null) {
                service.send(pending.connection, JSONObject().put("type", "notificationAuthorization").put("code", "E_USER_CANCELLED"))
                return
            }
            service.core().grantSdkNotificationPublisher(pending.owner.transportPackageName,
                pending.owner.transportSigningCertificateDigest, granted)
            service.send(pending.connection, JSONObject().put("type", "notificationAuthorization").put("granted", granted))
        }

        internal fun activationDetails(nonce: String): Triple<String, String, Boolean>? = activeService?.let { service ->
            val pending = pendingActivations[nonce] ?: return@let null
            if (SystemClock.elapsedRealtime() >= pending.expiresAt || !service.isCurrentClientIdentity(pending.connection)) return@let null
            val label = runCatching { service.packageManager.getApplicationLabel(service.packageManager.getApplicationInfo(pending.owner.transportPackageName, 0)).toString() }
                .getOrDefault(pending.owner.transportPackageName)
            val activity = runCatching { service.packageManager.getActivityInfo(pending.request.component, 0).loadLabel(service.packageManager).toString() }
                .getOrDefault(pending.request.component.className)
            Triple(label, activity, pending.request.keepVisible)
        }

        internal fun completeActivation(nonce: String, allow: Boolean) {
            val service = activeService ?: return
            val pending = pendingActivations.remove(nonce) ?: return
            if (SystemClock.elapsedRealtime() >= pending.expiresAt || !service.isCurrentClientIdentity(pending.connection)) {
                service.send(pending.connection, JSONObject().put("type", "error").put("code", "E_STALE_SESSION"))
                return
            }
            if (!allow) {
                service.send(pending.connection, JSONObject().put("type", "error").put("code", "E_CAPABILITY_DENIED"))
                return
            }
            val opened = java.util.concurrent.atomic.AtomicReference<SceneSessionHandle?>(null)
            ActivationWorkRunner.execute(service.worker, onFailure = { code ->
                opened.get()?.let { handle ->
                    runCatching { service.core().displaySessions.closeScene(pending.owner, handle.sessionId) }
                    service.sessions.remove(handle.sessionId, pending.connection)
                }
                service.send(pending.connection, JSONObject().put("type", "error").put("code", code)
                    .put("retryable", code == "E_BACKEND_UNAVAILABLE" || code == "E_SERVICE_UNAVAILABLE")
                    .put("phase", "scene_activation"))
            }) {
                val authority = service.core().issueSceneActivationAfterUserConfirmation(pending.owner, pending.request)
                val observer = service.observer(pending.connection)
                when (val result = service.core().displaySessions.openScene(pending.owner, pending.request, authority, observer)) {
                    is SceneOpenResult.Accepted -> {
                        opened.set(result.handle)
                        val stillConnected = synchronized(service.connections) {
                            if (!pending.connection.dead && service.connections[pending.connection.binder] === pending.connection) {
                                service.sessions[result.handle.sessionId] = pending.connection
                                true
                            } else false
                        }
                        if (!stillConnected || !service.core().ownsActiveSceneSession(pending.owner, result.handle.sessionId)) {
                            service.sessions.remove(result.handle.sessionId, pending.connection)
                            service.core().displaySessions.closeScene(pending.owner, result.handle.sessionId)
                        }
                        service.send(pending.connection, JSONObject().put("type", "accepted")
                            .put("sessionId", result.handle.sessionId).put("generation", result.handle.generation)
                            .put("keepVisible", pending.request.keepVisible)
                            .put("launchTicket", result.handle.launchTicket)
                            .put("launchTicketExpiresAtElapsedRealtimeMs", result.handle.launchTicketExpiresAtElapsedRealtimeMs))
                        if (pending.connection.dead) service.core().displaySessions.closeScene(pending.owner, result.handle.sessionId)
                    }
                    is SceneOpenResult.Rejected -> service.send(pending.connection,
                        JSONObject().put("type", "error").put("code", "E_${result.code.name}")
                            .put("retryable", result.retryable).put("phase", "scene_activation"))
                }
            }
        }

    internal fun dataConsentDetails(nonce: String): Triple<String, List<DataField>, Set<DataField>>? = activeService?.let { service ->
            val pending = pendingDataConsents[nonce] ?: return@let null
            if (SystemClock.elapsedRealtime() >= pending.expiresAt || pending.connection.dead) return@let null
            val label = runCatching { service.packageManager.getApplicationLabel(
                service.packageManager.getApplicationInfo(pending.owner.transportPackageName, 0)).toString() }
                .getOrDefault(pending.owner.transportPackageName)
            Triple(label, pending.fields.sortedBy { it.name }, service.core().grantedSdkDataFields(pending.owner))
        }

        internal fun completeDataConsent(nonce: String, allowedFields: Set<DataField>?) {
            val service = activeService ?: return
            val pending = pendingDataConsents.remove(nonce) ?: return
            if (SystemClock.elapsedRealtime() >= pending.expiresAt || pending.connection.dead) {
                service.send(pending.connection, JSONObject().put("type", "dataAuthorization").put("code", "E_STALE_SESSION"))
                return
            }
            if (allowedFields == null) {
                service.send(pending.connection, JSONObject().put("type", "dataAuthorization").put("code", "E_USER_CANCELLED"))
                return
            }
            val granted = allowedFields.intersect(pending.fields)
            service.core().updateSdkDataConsent(pending.owner, pending.fields, granted)
            service.send(pending.connection, JSONObject().put("type", "dataAuthorization")
                .put("granted", granted.sortedBy { it.name }.joinToString(",")))
        }

        internal data class ActionInvocationPrompt(val appLabel: String, val actionLabel: String, val targetLabel: String)

        internal fun actionInvocationPrompt(nonce: String): ActionInvocationPrompt? = activeService?.let { service ->
            val pending = pendingActionInvocations[nonce] ?: return@let null
            if (SystemClock.elapsedRealtime() >= pending.expiresAt || pending.connection.dead) return@let null
            val action = PlatformRegisteredActionRegistry.get(service).listForOwner(pending.owner)
                .firstOrNull { it.ref == pending.ref && it.available } ?: return@let null
            val appLabel = runCatching { service.packageManager.getApplicationLabel(
                service.packageManager.getApplicationInfo(pending.owner.transportPackageName, 0)).toString() }
                .getOrDefault(pending.owner.transportPackageName)
            ActionInvocationPrompt(appLabel, action.label, action.targetLabel)
        }

        /** Called only by the positive click in the host-owned confirmation Activity. */
        internal fun completeActionInvocation(nonce: String, allow: Boolean) {
            val service = activeService ?: return
            val pending = pendingActionInvocations.remove(nonce) ?: return
            if (SystemClock.elapsedRealtime() >= pending.expiresAt || pending.connection.dead) {
                service.send(pending.connection, JSONObject().put("type", "registeredActionInvocation").put("code", "E_STALE_SESSION"))
                return
            }
            if (!allow) {
                service.send(pending.connection, JSONObject().put("type", "registeredActionInvocation").put("code", "E_USER_CANCELLED"))
                return
            }
            // Mint only after this host Activity's explicit positive click; never expose the token over Binder.
            val activation = service.core().issueRegisteredActionActivationAfterHostClick(pending.owner, pending.ref)
            runCatching {
                service.worker.execute {
                    if (!service.isCurrentClientIdentity(pending.connection)) {
                        service.send(pending.connection, JSONObject().put("type", "registeredActionInvocation").put("code", "E_STALE_SESSION"))
                        return@execute
                    }
                    val result = service.core().invokeRegisteredAction(pending.owner, pending.ref, activation)
                    service.send(pending.connection, JSONObject().put("type", "registeredActionInvocation")
                        .put("refId", pending.ref.id).put("refVersion", pending.ref.version)
                        .put("accepted", result == top.zainzh.rearscreen.platform.actions.ActionDispatchResult.Accepted)
                        .put("code", (result as? top.zainzh.rearscreen.platform.actions.ActionDispatchResult.Rejected)?.error?.name))
                }
            }.onFailure {
                service.send(pending.connection, JSONObject().put("type", "registeredActionInvocation").put("code", "E_SERVICE_UNAVAILABLE"))
            }
        }

        private fun newOpaqueId(): String = java.util.UUID.randomUUID().toString().replace("-", "")
    }

    private fun send(connection: ClientConnection, event: JSONObject) {
        if (connection.dead) return
        runCatching { connection.callback.onEvent(event.toString()) }
    }
}
