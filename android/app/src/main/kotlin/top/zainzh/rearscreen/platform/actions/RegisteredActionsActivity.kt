package top.zainzh.rearscreen.platform.actions

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.zainzh.rearscreen.AppCard
import top.zainzh.rearscreen.AppPalette
import top.zainzh.rearscreen.AppRow
import top.zainzh.rearscreen.AppPageHeader
import top.zainzh.rearscreen.R
import top.zainzh.rearscreen.miuixContent
import top.zainzh.rearscreen.platform.SessionOwner
import top.zainzh.rearscreen.platform.SessionPrincipal

/** Private host route. Its caller must pass the owner already resolved by Core/Mod host. */
class RegisteredActionsActivity : ComponentActivity() {
    private var owner: SessionOwner? = null
    private var launcherOnly=false
    private var selectionMode by mutableStateOf(false)
    private var actions by mutableStateOf<List<RegisteredActionSummary>>(emptyList())
    private var shortcutProviders by mutableStateOf<List<ShortcutProviderChoice>>(emptyList())
    private var showProviderPicker by mutableStateOf(false)
    private var feedback by mutableStateOf("")
    private var working by mutableStateOf(false)

    private val shortcutProvider = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        completeSelection(result.resultCode, result.data, source = "shortcut-provider") { registry, caller, data -> registry.registerFromProviderResult(caller, data) }
    }
    private val launcherPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        completeSelection(result.resultCode, result.data, source = "launcher-picker") { registry, caller, data -> registry.registerLauncherResult(caller, data) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        owner = decodeOwner(intent)
        selectionMode = intent.getBooleanExtra(EXTRA_SELECT_ACTION, false)
        launcherOnly=intent.getBooleanExtra("launcher_only",false)
        if (owner == null) {
            finish()
            return
        }
        reload()
        miuixContent {
            val currentOwner = owner
            val actionListState = rememberLazyListState()
            LaunchedEffect(showProviderPicker, feedback) {
                if (feedback.isNotBlank()) actionListState.scrollToItem(0)
            }
            Box(Modifier.fillMaxSize().background(AppPalette.background()).safeDrawingPadding().imePadding()) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), contentAlignment = Alignment.TopCenter) {
                        AppPageHeader(stringResource(R.string.registered_actions_title), ::finish, "registered_actions_back", Modifier.widthIn(max = 680.dp))
                    }
                LazyColumn(
                    Modifier.widthIn(max = 680.dp).fillMaxSize().align(Alignment.CenterHorizontally).padding(horizontal = 20.dp),
                    state = actionListState,
                    contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item(key = "registered_actions_controls") {
                        AppCard {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(selectionIntro(), color = AppPalette.secondary())
                                TextButton(
                                    stringResource(R.string.registered_actions_add_app),
                                    { if (!working) chooseApp() },
                                    Modifier.fillMaxWidth(), minHeight = 48.dp, enabled = !working && !showProviderPicker
                                )
                                if(!launcherOnly) TextButton(
                                    if (working) stringResource(R.string.registered_actions_working)
                                    else stringResource(R.string.registered_actions_add),
                                    { if (!working) chooseShortcutProvider() },
                                    Modifier.fillMaxWidth(), minHeight = 48.dp, enabled = !working && !showProviderPicker
                                )
                            }
                        }
                    }
                    if (showProviderPicker) {
                        item(key = "registered_actions_provider_title") {
                            AppCard {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(stringResource(R.string.registered_actions_picker_title), color = AppPalette.text())
                                    TextButton(
                                        stringResource(R.string.registered_actions_provider_cancel),
                                        { showProviderPicker = false; shortcutProviders = emptyList() },
                                        Modifier.fillMaxWidth(), minHeight = 48.dp
                                    )
                                }
                            }
                        }
                        items(shortcutProviders, key = { it.component.flattenToString() }) { provider ->
                            AppCard {
                                AppRow(provider.label, provider.packageName, action = { launchShortcutProvider(provider) })
                            }
                        }
                    } else {
                        if (feedback.isNotBlank()) item(key = "registered_actions_feedback") {
                            AppCard { Text(feedback, Modifier.padding(18.dp), color = AppPalette.secondary()) }
                        }
                        if (actions.isEmpty()) item(key = "registered_actions_empty") {
                            AppCard { AppRow(stringResource(R.string.registered_actions_empty), null) }
                        }
                        items(actions, key = { it.ref.id }) { action ->
                            AppCard {
                                Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(action.label, Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
                                    Text(
                                        if (action.available) action.targetLabel else stringResource(R.string.registered_action_target_unavailable),
                                        Modifier.padding(horizontal = 18.dp), fontSize = 13.sp,
                                        color = if (action.available) AppPalette.secondary() else AppPalette.destructive()
                                    )
                                    if (selectionMode) TextButton(
                                        stringResource(R.string.registered_action_select),
                                        { select(action.ref) },
                                        Modifier.fillMaxWidth().padding(horizontal = 10.dp), minHeight = 48.dp, enabled = action.available
                                    )
                                    TextButton(
                                        stringResource(R.string.registered_action_revoke),
                                        { currentOwner?.let { revoke(it, action.ref) } },
                                        Modifier.fillMaxWidth().padding(horizontal = 10.dp), minHeight = 48.dp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    }

    private fun chooseShortcutProvider() {
        if (working) return
        working = true
        lastProviderUiProbe = ShortcutProviderUiProbe("querying", null, null, emptyList())
        lifecycleScope.launch {
            val query = withContext(Dispatchers.IO) { queryShortcutProviders(packageManager) }
            working = false
            when {
                query == null -> {
                    lastProviderUiProbe = ShortcutProviderUiProbe("query_failed", null, null, emptyList())
                    feedback = getString(R.string.registered_actions_no_provider)
                }
                query.providers.size > MAX_SHORTCUT_PROVIDERS -> {
                    lastProviderUiProbe = query.toUiProbe("limit")
                    feedback = getString(R.string.registered_actions_provider_limit)
                }
                query.providers.isEmpty() -> {
                    lastProviderUiProbe = query.toUiProbe("empty")
                    feedback = getString(R.string.registered_actions_no_provider)
                }
                else -> {
                    shortcutProviders = query.providers
                    showProviderPicker = true
                    lastProviderUiProbe = query.toUiProbe("list_shown")
                }
            }
        }
    }

    private fun launchShortcutProvider(provider: ShortcutProviderChoice) {
        showProviderPicker = false
        working = true
        lastProviderUiProbe = lastProviderUiProbe?.copy(phase = "launching:${provider.component.flattenToString()}")
        val request = Intent(Intent.ACTION_CREATE_SHORTCUT).setComponent(provider.component)
        runCatching { shortcutProvider.launch(request) }
            .onFailure {
                working = false
                lastProviderUiProbe = lastProviderUiProbe?.copy(phase = "launch_rejected:${provider.component.flattenToString()}")
                feedback = getString(R.string.registered_actions_no_provider)
            }
    }

    private fun chooseApp() {
        val launcherFilter = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val picker = Intent(Intent.ACTION_PICK_ACTIVITY).putExtra(Intent.EXTRA_INTENT, launcherFilter)
        runCatching { launcherPicker.launch(picker) }
            .onFailure { feedback = getString(R.string.registered_actions_no_app_picker) }
    }

    private fun completeSelection(
        resultCode: Int,
        data: Intent?,
        source: String,
        register: (RegisteredActionRegistry, SessionOwner, Intent) -> ActionRegistrationResult
    ) {
        val caller = owner ?: return
        working = true
        val providerResultEvidence = if (source == "shortcut-provider") {
            val hasLegacyTarget = data?.hasExtra(Intent.EXTRA_SHORTCUT_INTENT) == true
            val hasPinItemRequest = data?.hasExtra("android.content.pm.extra.PIN_ITEM_REQUEST") == true
            "code=$resultCode:data=${data != null}:action=${data?.action}:component=${data?.component}:legacy=$hasLegacyTarget:pin=$hasPinItemRequest:shape=${data?.let(ActionTargetPolicy::providerResultShape)}"
        } else null
        providerResultEvidence?.let { evidence ->
            lastProviderUiProbe = lastProviderUiProbe?.copy(phase = "result_received:$evidence")
        }
        lifecycleScope.launch {
            val registered = withContext(Dispatchers.IO) {
                when (val accepted = commitPickerResult(resultCode, data) { selected ->
                    register(PlatformRegisteredActionRegistry.get(applicationContext), caller, selected)
                }) {
                    PickerCommit.Cancelled -> ActionRegistrationResult.UserCancelled
                    is PickerCommit.Saved -> accepted.value
                }
            }
            working = false
            feedback = when (registered) {
                is ActionRegistrationResult.Registered -> getString(R.string.registered_action_saved)
                ActionRegistrationResult.UserCancelled -> getString(R.string.registered_action_cancelled)
                is ActionRegistrationResult.Rejected -> actionErrorText(registered.error)
            }
            if (source == "shortcut-provider") {
                lastProviderUiProbe = lastProviderUiProbe?.copy(phase = when (registered) {
                    is ActionRegistrationResult.Registered -> "result_registered:${registered.ref.id}:${registered.ref.version};$providerResultEvidence"
                    ActionRegistrationResult.UserCancelled -> "result_cancelled;$providerResultEvidence"
                    is ActionRegistrationResult.Rejected -> "result_rejected:${registered.error};$providerResultEvidence"
                })
            }
            reload()
        }
    }

    private fun reload() {
        val currentOwner = owner ?: return
        lifecycleScope.launch {
            actions = withContext(Dispatchers.IO) {
                val registry=PlatformRegisteredActionRegistry.get(applicationContext)
                registry.listForOwner(currentOwner).filter { summary ->
                    !launcherOnly || (registry.resolve(currentOwner,summary.ref) as? ActionResolveResult.Resolved)?.action?.record?.let {
                        it.action==Intent.ACTION_MAIN && Intent.CATEGORY_LAUNCHER in it.categories && it.dataUri==null && it.extras.isEmpty()
                    }==true
                }
            }
        }
    }

    private fun revoke(currentOwner: SessionOwner, ref: RegisteredActionRef) {
        working = true
        lifecycleScope.launch {
            val revoked = withContext(Dispatchers.IO) { PlatformRegisteredActionRegistry.get(applicationContext).revoke(currentOwner, ref) }
            working = false
            feedback = getString(if (revoked) R.string.registered_action_revoked else R.string.registered_action_missing)
            reload()
        }
    }

    private fun select(ref: RegisteredActionRef) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_SELECTED_REF_ID, ref.id).putExtra(EXTRA_SELECTED_REF_VERSION, ref.version))
        finish()
    }

    private fun selectionIntro(): String {
        if (!selectionMode) return getString(R.string.registered_actions_intro)
        val current = owner ?: return getString(R.string.registered_actions_intro)
        val publisher = when (val principal = current.principal) {
            is SessionPrincipal.SignedMod -> "此 Mod"
            is SessionPrincipal.AndroidClient -> runCatching {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(principal.packageName, 0)).toString()
            }.getOrDefault(principal.packageName)
            is SessionPrincipal.HostFeature -> "此功能"
        }
        return "选择快捷操作即允许 $publisher 调用这项操作。目标应用或签名发生变化时，宿主会拒绝执行。"
    }

    private fun actionErrorText(error: ActionError): String = when (error) {
        ActionError.LIMIT_EXCEEDED -> getString(R.string.registered_action_limit)
        ActionError.STORAGE_FAILED -> getString(R.string.registered_action_storage_failed)
        ActionError.TARGET_UNAVAILABLE -> getString(R.string.registered_action_target_unavailable)
        else -> getString(R.string.registered_action_invalid)
    }

    companion object {
        internal const val MAX_SHORTCUT_PROVIDERS = 32
        @Volatile internal var lastProviderUiProbe: ShortcutProviderUiProbe? = null
        /** Only trusted in-process host code should call this with a Core-resolved owner. */
        fun createIntent(context: Context, owner: SessionOwner, selectAction: Boolean = false, launcherOnly: Boolean = false): Intent = Intent(context, RegisteredActionsActivity::class.java).apply {
            putExtra(EXTRA_SELECT_ACTION, selectAction)
            putExtra("launcher_only",launcherOnly)
            putExtra(EXTRA_TRANSPORT_UID, owner.transportUid)
            putExtra(EXTRA_TRANSPORT_PACKAGE, owner.transportPackageName)
            putExtra(EXTRA_TRANSPORT_SIGNER, owner.transportSigningCertificateDigest)
            when (val principal = owner.principal) {
                is SessionPrincipal.SignedMod -> {
                    putExtra(EXTRA_PRINCIPAL_TYPE, "mod")
                    putExtra(EXTRA_MOD_AUTHOR, principal.authorFingerprint)
                    putExtra(EXTRA_MOD_ID, principal.modId)
                    putExtra(EXTRA_LOGICAL_SESSION, principal.logicalSessionId)
                }
                is SessionPrincipal.AndroidClient -> {
                    putExtra(EXTRA_PRINCIPAL_TYPE, "sdk")
                    putExtra(EXTRA_CLIENT_UID, principal.uid)
                    putExtra(EXTRA_CLIENT_PACKAGE, principal.packageName)
                    putExtra(EXTRA_CLIENT_SIGNER, principal.signingCertificateDigest)
                    putExtra(EXTRA_CONNECTION_EPOCH, principal.connectionEpoch)
                }
                is SessionPrincipal.HostFeature -> {
                    putExtra(EXTRA_PRINCIPAL_TYPE, "host")
                    putExtra(EXTRA_HOST_FEATURE, principal.featureId)
                    putExtra(EXTRA_LOGICAL_SESSION, principal.logicalSessionId)
                }
            }
        }

        internal fun decodeOwner(intent: Intent): SessionOwner? = runCatching {
            val transportUid = intent.getIntExtra(EXTRA_TRANSPORT_UID, -1).also { require(it >= 0) }
            val transportPackage = intent.getStringExtra(EXTRA_TRANSPORT_PACKAGE).orEmpty().also { require(it.isNotBlank()) }
            val transportSigner = intent.getStringExtra(EXTRA_TRANSPORT_SIGNER).orEmpty().also { require(it.isNotBlank()) }
            val principal = when (intent.getStringExtra(EXTRA_PRINCIPAL_TYPE)) {
                "mod" -> SessionPrincipal.SignedMod(
                    intent.getStringExtra(EXTRA_MOD_AUTHOR).orEmpty().also { require(it.isNotBlank()) },
                    intent.getStringExtra(EXTRA_MOD_ID).orEmpty().also { require(it.isNotBlank()) },
                    intent.getStringExtra(EXTRA_LOGICAL_SESSION).orEmpty().also { require(it.isNotBlank()) }
                )
                "sdk" -> SessionPrincipal.AndroidClient(
                    intent.getIntExtra(EXTRA_CLIENT_UID, -1).also { require(it >= 0) },
                    intent.getStringExtra(EXTRA_CLIENT_PACKAGE).orEmpty().also { require(it.isNotBlank()) },
                    intent.getStringExtra(EXTRA_CLIENT_SIGNER).orEmpty().also { require(it.isNotBlank()) },
                    intent.getLongExtra(EXTRA_CONNECTION_EPOCH, -1).also { require(it >= 0) }
                )
                "host" -> SessionPrincipal.HostFeature(
                    intent.getStringExtra(EXTRA_HOST_FEATURE).orEmpty().also { require(it.isNotBlank()) },
                    intent.getStringExtra(EXTRA_LOGICAL_SESSION).orEmpty().also { require(it.isNotBlank()) }
                )
                else -> error("unknown principal")
            }
            SessionOwner(transportUid, transportPackage, transportSigner, principal)
        }.getOrNull()

        private const val EXTRA_TRANSPORT_UID = "action_owner_transport_uid"
        private const val EXTRA_TRANSPORT_PACKAGE = "action_owner_transport_package"
        private const val EXTRA_TRANSPORT_SIGNER = "action_owner_transport_signer"
        private const val EXTRA_PRINCIPAL_TYPE = "action_owner_principal_type"
        private const val EXTRA_MOD_AUTHOR = "action_owner_mod_author"
        private const val EXTRA_MOD_ID = "action_owner_mod_id"
        private const val EXTRA_LOGICAL_SESSION = "action_owner_logical_session"
        private const val EXTRA_CLIENT_UID = "action_owner_client_uid"
        private const val EXTRA_CLIENT_PACKAGE = "action_owner_client_package"
        private const val EXTRA_CLIENT_SIGNER = "action_owner_client_signer"
        private const val EXTRA_CONNECTION_EPOCH = "action_owner_connection_epoch"
        private const val EXTRA_HOST_FEATURE = "action_owner_host_feature"
        private const val EXTRA_SELECT_ACTION = "registered_actions_select_action"
        const val EXTRA_SELECTED_REF_ID = "registered_actions_selected_ref_id"
        const val EXTRA_SELECTED_REF_VERSION = "registered_actions_selected_ref_version"
    }
}

internal data class ShortcutProviderChoice(val component: ComponentName, val label: String, val packageName: String)

internal data class ShortcutProviderQuery(
    val queriedCount: Int,
    val eligibleCount: Int,
    val providers: List<ShortcutProviderChoice>
)

internal data class ShortcutProviderUiProbe(
    val phase: String,
    val queriedCount: Int?,
    val eligibleCount: Int?,
    val providers: List<String>
)

private fun ShortcutProviderQuery.toUiProbe(phase: String) = ShortcutProviderUiProbe(
    phase,
    queriedCount,
    eligibleCount,
    providers.map { "${it.label}=${it.component.flattenToString()}" }
)

internal fun queryShortcutProviders(packageManager: PackageManager): ShortcutProviderQuery? = runCatching {
    val request = Intent(Intent.ACTION_CREATE_SHORTCUT)
    val matches = if (android.os.Build.VERSION.SDK_INT >= 33) {
        packageManager.queryIntentActivities(request, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
    } else {
        @Suppress("DEPRECATION") packageManager.queryIntentActivities(request, PackageManager.MATCH_DEFAULT_ONLY)
    }
    val eligible = matches.mapNotNull { resolveInfo ->
        val info = resolveInfo.activityInfo ?: return@mapNotNull null
        if (!info.exported || !info.enabled || !info.applicationInfo.enabled) return@mapNotNull null
        val label = runCatching { resolveInfo.loadLabel(packageManager).toString().trim() }
            .getOrDefault("").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        ShortcutProviderChoice(ComponentName(info.packageName, info.name), label, info.packageName)
    }
    val providers = eligible.distinctBy { it.component }.sortedWith(Comparator { left, right ->
        val byLabel = left.label.compareTo(right.label, ignoreCase = true)
        if (byLabel != 0) byLabel else left.packageName.compareTo(right.packageName)
    })
    ShortcutProviderQuery(matches.size, eligible.size, providers)
}.getOrNull()
