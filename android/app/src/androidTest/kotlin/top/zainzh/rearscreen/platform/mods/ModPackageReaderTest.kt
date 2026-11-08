package top.zainzh.rearscreen.platform.mods

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.json.JSONObject
import org.junit.runner.RunWith
import top.zainzh.rearscreen.platform.runtime.IsolatedModRuntimeClient
import top.zainzh.rearscreen.platform.runtime.ModRuntimeCloseBarrierBridge
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class ModPackageReaderTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().context
    private val appContext get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var fixtureStore: ModPackageStoreTestSession
    private lateinit var productionBefore: ModStoreTestSnapshot

    @Before fun openIsolatedStoreForThisTest() {
        productionBefore = ModPackageStore.productionSnapshotForTest(appContext)
        fixtureStore = ModPackageStoreTestSession.create(appContext, "reader-${System.nanoTime()}")
    }

    @After fun closeIsolatedStoreAndVerifyProductionUnchanged() {
        fixtureStore.close()
        assertEquals("AndroidTest must not change the target host's installed Mods, trust, grants, package paths, or models",
            productionBefore, ModPackageStore.productionSnapshotForTest(appContext))
    }

    private fun newBroker(): ModEventBroker = ModEventBroker(appContext, ModEffectDispatcher { _, _, _ -> }).apply {
        testStoreStorage = fixtureStore.storage
    }

    @Test fun businessErrorShapedInitReducerAndMigrationValuesRemainData() {
        val oldArchive = context.assets.open("platform/mods/error-fields-v1.zip").use { it.readBytes() }
        val newArchive = context.assets.open("platform/mods/error-fields-v2.zip").use { it.readBytes() }
        val oldPackage = ModPackageReader.read(ByteArrayInputStream(oldArchive))
        val newPackage = ModPackageReader.read(ByteArrayInputStream(newArchive))
        assertEquals(oldPackage.id, newPackage.id)
        assertEquals(oldPackage.authorFingerprint, newPackage.authorFingerprint)
        val grants = oldPackage.requiredCapabilities.map { it.name }.toSet()
        val installed = fixtureStore.install(oldArchive, ModInstallDecision(oldPackage.authorFingerprint, grants))
        fixtureStore.setEnabled(installed.id, true)
        val broker = newBroker()
        val initialReady = java.util.concurrent.CountDownLatch(1)
        val configSaved = java.util.concurrent.CountDownLatch(1)
        val configCommitted = java.util.concurrent.CountDownLatch(1)
        val preUpdateEvent = java.util.concurrent.CountDownLatch(1)
        val replaced = java.util.concurrent.CountDownLatch(1)
        val postUpdateReady = java.util.concurrent.CountDownLatch(1)
        val postUpdateEvent = java.util.concurrent.CountDownLatch(1)
        val errors = java.util.concurrent.CopyOnWriteArrayList<String>()
        val direct = java.util.concurrent.Executor { it.run() }
        val readyCalls = java.util.concurrent.atomic.AtomicInteger()
        val listener = object : ModBrokerListener {
            override fun onReady(modId: String, model: ModPersistedModel) {
                if (readyCalls.incrementAndGet() == 1) initialReady.countDown() else postUpdateReady.countDown()
            }
            override fun onModelCommitted(modId: String, eventId: String?, model: ModPersistedModel) {
                if (model.config.optInt("step") == 2) configCommitted.countDown()
                if (model.state?.optInt("count") == 2) preUpdateEvent.countDown()
                if (model.state?.optInt("count") == 4) postUpdateEvent.countDown()
            }
            override fun onError(modId: String, code: String, message: String) {
                if (code == "E_PACKAGE_REPLACED") replaced.countDown()
                else if (code != "E_PACKAGE_UPDATING") errors += "$code: $message"
            }
            override fun onEffectRequested(modId: String, eventId: String) { errors += "unexpected effect $eventId" }
        }
        try {
            broker.open(installed.id, direct, listener)
            assertTrue("init accepts schema-valid state containing business error-like fields", initialReady.await(8, java.util.concurrent.TimeUnit.SECONDS))
            var model = requireNotNull(fixtureStore.model(installed.id))
            assertEquals(false, model.state?.getBoolean("ok"))
            assertEquals("E_SCRIPT", model.state?.getString("code"))
            assertEquals("E_CPU_BUDGET", model.state?.getString("cpuCode"))

            val nextConfig = JSONObject().put("step", 2).put("ok", false).put("code", "E_SCRIPT").put("cpuCode", "E_CPU_BUDGET")
            broker.saveConfig(installed.id, nextConfig, direct) { ok, _ -> if (ok) configSaved.countDown() }
            assertTrue("schema-valid config error-like fields save as data", configSaved.await(8, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("config reducer commits despite business code fields", configCommitted.await(8, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(broker.submitEvent(installed.id, "counter.increment"))
            assertTrue("reduce commits state carrying E_SCRIPT/E_CPU_BUDGET values", preUpdateEvent.await(8, java.util.concurrent.TimeUnit.SECONDS))

            val replacementReady = java.util.concurrent.CountDownLatch(1)
            broker.beginPackageReplacement(installed.id, direct) { ok, _ -> if (ok) replacementReady.countDown() }
            assertTrue("old worker closes before the migration runner starts", replacementReady.await(8, java.util.concurrent.TimeUnit.SECONDS))
            val updated = fixtureStore.install(newArchive,
                ModInstallDecision(newPackage.authorFingerprint, newPackage.requiredCapabilities.map { it.name }.toSet()),
                IsolatedModMigrationRunner(appContext))
            assertTrue(updated.enabled)
            model = requireNotNull(fixtureStore.model(installed.id))
            assertEquals(2, model.dataVersion)
            assertEquals(2, model.state?.getInt("count"))
            assertEquals(false, model.state?.getBoolean("ok"))
            assertEquals("E_SCRIPT", model.state?.getString("code"))
            assertEquals("E_CPU_BUDGET", model.config.getString("cpuCode"))
            broker.finishPackageReplacement(installed.id, true)
            assertTrue("replacement retires the old logical worker", replaced.await(8, java.util.concurrent.TimeUnit.SECONDS))
            broker.open(installed.id, direct, listener)
            assertTrue("new package loads migrated state rather than rerunning init", postUpdateReady.await(8, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(2, fixtureStore.model(installed.id)?.state?.getInt("count"))
            assertTrue("migrated worker remains usable with error-like data fields", broker.submitEvent(installed.id, "counter.increment"))
            assertTrue(postUpdateEvent.await(8, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(4, fixtureStore.model(installed.id)?.state?.getInt("count"))
            assertTrue("business JSON fields do not broadcast engine failure: $errors", errors.isEmpty())
        } finally {
            broker.closeObserver(installed.id, listener)
            broker.close()
            fixtureStore.uninstall(installed.id)
        }
    }

    private fun canonicalJson(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString(prefix = "{", postfix = "}") { key ->
            "${JSONObject.quote(key)}:${canonicalJson(value.get(key))}"
        }
        is org.json.JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { canonicalJson(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }

    @Test fun isolatedTestStoreLeavesProductionInstallTrustAndModelsUntouched() {
        val productionContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertFalse("the ordinary Activity context is credential protected", productionContext.isDeviceProtectedStorage)
        val before = ModPackageStore.productionSnapshotForTest(productionContext)
        val testStore = ModPackageStoreTestSession.create(productionContext, "storage-separation")
        try {
            val fixture = context.assets.open("platform/mods/counter-transaction-v1.zip").use { it.readBytes() }
            val pkg = ModPackageReader.read(ByteArrayInputStream(fixture))
            assertFalse("the signed test fixture must not reuse a production Mod ID", before.entries.any { it.id == pkg.id })
            assertTrue("the fixture database must be device protected", testStore.storage.testContext.isDeviceProtectedStorage)
            val testDatabasePath = testStore.storage.testContext.getDatabasePath(testStore.storage.database.databaseName).canonicalPath
            val productionDatabasePath = productionContext.getDatabasePath(ModDatabase.DATABASE_NAME).canonicalPath
            assertTrue("test and production database paths must differ", testDatabasePath != productionDatabasePath)
            val productionPackageRoot = File(productionContext.noBackupFilesDir, "platform-mods").canonicalFile
            assertFalse("test ZIP root must be separate from production", testStore.storage.packageRoot.canonicalFile == productionPackageRoot)

            val installed = testStore.install(fixture, ModInstallDecision(pkg.authorFingerprint, pkg.requiredCapabilities.map { it.name }.toSet()))
            assertEquals(pkg.id, installed.id)
            assertNotNull(testStore.find(pkg.id))
            assertTrue(testStore.setEnabled(pkg.id, true).enabled)
            testStore.commitInitialState(pkg.id, JSONObject().put("count", 7))
            assertEquals(7, testStore.model(pkg.id)?.state?.getInt("count"))
            assertFalse("test fixture must remain invisible to normal Modules list", ModPackageStore.list(productionContext).any { it.id == pkg.id })
            testStore.uninstall(pkg.id)
        } finally {
            testStore.close()
        }
        assertTrue("test DB/payload/trust/model writes leave the production store unchanged",
            before == ModPackageStore.productionSnapshotForTest(productionContext))
    }

    @Test fun reboundRuntimeEpochRejectsLateReplyFromPreviousWorker() {
        val gate = ModRuntimeEpochGate(41L)
        assertTrue(gate.accepts(41L))
        gate.advance(42L)
        assertTrue(gate.accepts(42L))
        assertEquals(false, gate.accepts(41L))
    }

    @Test fun noScriptPackageVerifiesWithoutReducerSource() {
        val mod = context.assets.open("platform/mods/no-script-info.zip").use { ModPackageReader.read(it) }
        assertEquals("platform.info", mod.id)
        assertEquals(ModRuntimeKind.NONE, mod.runtime)
        assertNull(mod.reducerSource)
        assertEquals(listOf(ModCapability("data.battery", 1)), mod.requiredCapabilities)
        assertEquals("settings", mod.settingsScene.getString("scene"))
    }

    @Test fun genericReducerPackageVerifiesWorkerAbiAndScenes() {
        val mod = context.assets.open("platform/mods/generic-counter.zip").use { ModPackageReader.read(it) }
        assertEquals("platform.counter", mod.id)
        assertEquals(ModRuntimeKind.QUICKJS, mod.runtime)
        assertNotNull(mod.reducerSource)
        assert(mod.reducerSource!!.toString(Charsets.UTF_8).contains("globalThis.__remrss"))
        assertEquals("rear", requireNotNull(mod.rearScene).getString("scene"))
    }

    @Test fun genericQuickJsMigrationReceivesNullAndRejectsAStateDereference() {
        val oldArchive = context.assets.open("platform/mods/counter-transaction-v1.zip").use { it.readBytes() }
        val newArchive = context.assets.open("platform/mods/counter-transaction-v2-invalid-migration.zip").use { it.readBytes() }
        val oldPackage = ModPackageReader.read(ByteArrayInputStream(oldArchive))
        val newPackage = ModPackageReader.read(ByteArrayInputStream(newArchive))
        assertEquals("platform.countertxn", oldPackage.id)
        assertEquals(oldPackage.authorFingerprint, newPackage.authorFingerprint)
        assertEquals(1, oldPackage.dataVersion)
        assertEquals(2, newPackage.dataVersion)
        assertNull(fixtureStore.model(oldPackage.id))

        val grants = oldPackage.requiredCapabilities.map { it.name }.toSet()
        fixtureStore.install(oldArchive, ModInstallDecision(oldPackage.authorFingerprint, grants))
        val current = requireNotNull(fixtureStore.model(oldPackage.id))
        assertNull("install leaves the generic fixture uninitialized", current.state)
        val dereferencingSource = """
            globalThis.__remrss = {
              init() { return { count: 0 }; },
              reduce({ state }) { return { state }; },
              migrate({ state, config }) { return { state: { count: state.count + 1 }, config }; }
            };
        """.trimIndent()
        val runner = IsolatedModMigrationRunner(appContext)
        val migrationFailure = assertThrows(IllegalStateException::class.java) {
            fixtureStore.install(newArchive, ModInstallDecision(newPackage.authorFingerprint, grants),
                ModMigrationRunner { old, next, model ->
                    runner.migrate(old, next.copy(reducerSource = dereferencingSource.toByteArray(Charsets.UTF_8)), model)
                })
        }
        assertTrue("real QuickJS sees null rather than an invented empty object",
            migrationFailure.message?.contains("cannot read property 'count' of null") == true)
        assertEquals("failed migration keeps generic v1 package active", 1, fixtureStore.installed(oldPackage.id)?.versionCode)
        assertNull("failed migration preserves the uninitialized model", fixtureStore.model(oldPackage.id)?.state)
    }

    @Test fun genericNullToNullMigrationPreservesModelAndInitializesOnFirstOpen() {
        val oldArchive = context.assets.open("platform/mods/counter-transaction-v1.zip").use { it.readBytes() }
        val newArchive = context.assets.open("platform/mods/counter-transaction-v2-invalid-migration.zip").use { it.readBytes() }
        val oldPackage = ModPackageReader.read(ByteArrayInputStream(oldArchive))
        val newPackage = ModPackageReader.read(ByteArrayInputStream(newArchive))
        val grants = oldPackage.requiredCapabilities.map { it.name }.toSet()
        fixtureStore.install(oldArchive, ModInstallDecision(oldPackage.authorFingerprint, grants))
        assertNull("install does not run init", fixtureStore.model(oldPackage.id)?.state)

        val preservingSource = """
            globalThis.__remrss = {
              init() { return { count: 0 }; },
              reduce({ state, event }) {
                if (event.type === "counter.increment") return { state: { ...state, count: state.count + 1 } };
                return { state };
              },
              migrate({ state, config }) {
                if (state === null) return { state: null, config };
                return { state, config };
              }
            };
        """.trimIndent()
        val runner = IsolatedModMigrationRunner(appContext)
        val updated = fixtureStore.install(newArchive, ModInstallDecision(newPackage.authorFingerprint, grants),
            ModMigrationRunner { old, next, model ->
                runner.migrate(old, next.copy(reducerSource = preservingSource.toByteArray(Charsets.UTF_8)), model)
            })
        assertEquals(2, updated.dataVersion)
        assertNull("valid null-to-null migration keeps the model uninitialized", fixtureStore.model(newPackage.id)?.state)
        assertTrue(fixtureStore.setEnabled(newPackage.id, true).enabled)

        val broker = newBroker()
        var listener: ModBrokerListener? = null
        try {
            val ready = java.util.concurrent.CountDownLatch(1)
            listener = object : ModBrokerListener {
                override fun onReady(modId: String, model: ModPersistedModel) { ready.countDown() }
                override fun onModelCommitted(modId: String, eventId: String?, model: ModPersistedModel) { }
                override fun onError(modId: String, code: String, message: String) { }
                override fun onEffectRequested(modId: String, eventId: String) { }
            }
            broker.open(newPackage.id, java.util.concurrent.Executor { it.run() }, listener!!)
            assertTrue("only first activation after migration runs init", ready.await(8, java.util.concurrent.TimeUnit.SECONDS))
            val initialized = requireNotNull(fixtureStore.model(newPackage.id))
            ModJsonSchema.validateValue(newPackage.stateSchema, initialized.state, "state")
            assertEquals(0, initialized.state!!.getInt("count"))
        } finally {
            listener?.let { broker.closeObserver(newPackage.id, it) }
            broker.close()
        }
    }

    @Test fun initializedAuthorStateCannotBeClearedByQuickJsMigrationAndOldWorkerContinues() {
        val oldArchive = context.assets.open("platform/mods/counter-transaction-v1.zip").use { it.readBytes() }
        val newArchive = context.assets.open("platform/mods/counter-transaction-v2-invalid-migration.zip").use { it.readBytes() }
        val oldPackage = ModPackageReader.read(ByteArrayInputStream(oldArchive))
        val newPackage = ModPackageReader.read(ByteArrayInputStream(newArchive))
        val store = ModPackageStoreTestSession.create(InstrumentationRegistry.getInstrumentation().targetContext, "m7-state-erasure")
        val grants = oldPackage.requiredCapabilities.map { it.name }.toSet()
        var broker: ModEventBroker? = null
        var listener: ModBrokerListener? = null
        try {
            assertEquals("platform.countertxn", oldPackage.id)
            store.install(oldArchive, ModInstallDecision(oldPackage.authorFingerprint, grants))
            val activeBroker = newBroker().apply { testStoreStorage = store.storage }
            broker = activeBroker
            store.setEnabled(oldPackage.id, true)
            val ready = java.util.concurrent.CountDownLatch(1)
            val firstCommit = java.util.concurrent.CountDownLatch(1)
            val continuedCommit = java.util.concurrent.CountDownLatch(1)
            listener = object : ModBrokerListener {
                override fun onReady(modId: String, model: ModPersistedModel) { ready.countDown() }
                override fun onModelCommitted(modId: String, eventId: String?, model: ModPersistedModel) {
                    val count = model.state?.optInt("count", -1) ?: -1
                    if (count == 1) firstCommit.countDown()
                    if (count == 2) continuedCommit.countDown()
                }
                override fun onError(modId: String, code: String, message: String) { }
                override fun onEffectRequested(modId: String, eventId: String) { }
            }
            activeBroker.open(oldPackage.id, java.util.concurrent.Executor { it.run() }, listener!!)
            assertTrue("the v1 reducer initializes exactly once", ready.await(8, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("first counter input is accepted", activeBroker.submitEvent(oldPackage.id, "counter.increment"))
            assertTrue("the initialized state is committed", firstCommit.await(8, java.util.concurrent.TimeUnit.SECONDS))
            val before = store.model(oldPackage.id)!!
            assertEquals(1, before.state!!.getInt("count"))
            assertTrue(store.installed(oldPackage.id)!!.enabled)

            // Execute a real QuickJS migrate that maliciously (or accidentally)
            // returns null despite receiving a committed object model.
            val erasingSource = """
                globalThis.__remrss = {
                  init() { return { count: 0 }; },
                  reduce({ state, event }) {
                    if (event.type === 'counter.increment') return { state: { ...state, count: state.count + 1 } };
                    return { state };
                  },
                  migrate({ config }) { return { state: null, config }; }
                };
            """.trimIndent().toByteArray(Charsets.UTF_8)
            val nullMigrator = ModMigrationRunner { old, next, model ->
                IsolatedModMigrationRunner(appContext).migrate(old, next.copy(reducerSource = erasingSource), model)
            }
            val rejection = assertThrows(IllegalArgumentException::class.java) {
                store.install(newArchive,
                    ModInstallDecision(newPackage.authorFingerprint, newPackage.requiredCapabilities.map { it.name }.toSet()), nullMigrator)
            }
            assertTrue(rejection.message?.contains("不得丢弃已有 state") == true)
            val afterReject = store.model(oldPackage.id)!!
            assertEquals(1, store.installed(oldPackage.id)?.versionCode)
            assertEquals(1, afterReject.dataVersion)
            assertEquals(before.revision, afterReject.revision)
            assertEquals(before.state.toString(), afterReject.state.toString())
            assertEquals(before.config.toString(), afterReject.config.toString())
            assertTrue("old package remains enabled", store.installed(oldPackage.id)!!.enabled)

            assertTrue("the old worker still accepts a reducer event after rejected migration", activeBroker.submitEvent(oldPackage.id, "counter.increment"))
            assertTrue("the old worker commits after rejected migration", continuedCommit.await(8, java.util.concurrent.TimeUnit.SECONDS))
            val continued = store.model(oldPackage.id)!!
            assertEquals(2, continued.state!!.getInt("count"))
            assertEquals(before.revision + 1, continued.revision)
        } finally {
            listener?.let { broker?.closeObserver(oldPackage.id, it) }
            broker?.closeAfterDrainForTesting(oldPackage.id)?.get(8, java.util.concurrent.TimeUnit.SECONDS)
            broker?.close()
            if (store.installed(oldPackage.id) != null) store.uninstall(oldPackage.id)
            store.close()
        }
    }

    @Test fun noScriptSceneFixtureDeclaresTheRearSceneGrantSeparately() {
        val mod = context.assets.open("platform/mods/scene-info.zip").use { ModPackageReader.read(it) }
        assertEquals(ModRuntimeKind.NONE, mod.runtime)
        assertEquals(setOf("data.battery", "rear.scene"), mod.requiredCapabilities.map { it.name }.toSet())
    }

    @Test fun signedPayloadTamperingIsRejectedBeforeInstall() {
        val raw = context.assets.open("platform/mods/generic-counter.zip").use { it.readBytes() }
        val changed = rewriteZip(raw) { name, bytes ->
            if (name == "main.js") bytes + "\n// altered after signing".toByteArray() else bytes
        }
        val error = assertThrows(ModPackageException::class.java) {
            ModPackageReader.read(ByteArrayInputStream(changed))
        }
        assertEquals(ModPackageError.PAYLOAD_MISMATCH, error.code)
    }

    @Test fun duplicateZipEntryIsRejected() {
        val error = assertThrows(ModPackageException::class.java) {
            context.assets.open("platform/mods/duplicate-manifest.zip").use { ModPackageReader.read(it) }
        }
        assertEquals(ModPackageError.DUPLICATE_ENTRY, error.code)
    }

    @Test fun importTrustEnableConfigTransactionAndUninstallAreSeparate() {
        val pkg = context.assets.open("platform/mods/no-script-info.zip").use { ModPackageReader.read(it) }
        val installed = fixtureStore.install( context.assets.open("platform/mods/no-script-info.zip").use { it.readBytes() },
            ModInstallDecision(pkg.authorFingerprint, setOf("data.battery")))
        try {
            assertEquals("platform.info", installed.id)
            assertEquals(false, installed.enabled)
            assertTrue(fixtureStore.isAuthorTrusted( pkg.authorFingerprint))
            assertEquals(setOf("data.battery"), installed.grantedCapabilities)
            assertNull(fixtureStore.model( installed.id)?.state)
            val unchanged = fixtureStore.commitModel( installed.id, 0, null, JSONObject())
            assertEquals(0L, unchanged.revision)
            assertTrue(fixtureStore.setEnabled( installed.id, true).enabled)
            assertEquals(false, fixtureStore.setEnabled( installed.id, false).enabled)
        } finally {
            fixtureStore.uninstall( installed.id)
        }
        assertNull(fixtureStore.installed( installed.id))
        assertNull(fixtureStore.model( installed.id))
    }

    @Test fun sharedNoScriptModBrokerNotifiesBothScreensAndDetachesIndependently() {
        val archive = context.assets.open("platform/mods/no-script-info.zip").use { it.readBytes() }
        val pkg = ModPackageReader.read(ByteArrayInputStream(archive))
        val installed = fixtureStore.install( archive, ModInstallDecision(pkg.authorFingerprint, setOf("data.battery")))
        val broker = newBroker()
        try {
            fixtureStore.setEnabled( installed.id, true)
            val firstReady = java.util.concurrent.CountDownLatch(1)
            val secondReady = java.util.concurrent.CountDownLatch(1)
            val firstCommit = java.util.concurrent.CountDownLatch(1)
            val secondCommit = java.util.concurrent.CountDownLatch(1)
            val first = object : ModBrokerListener {
                override fun onReady(modId: String, model: ModPersistedModel) { firstReady.countDown() }
                override fun onModelCommitted(modId: String, eventId: String?, model: ModPersistedModel) { firstCommit.countDown() }
                override fun onError(modId: String, code: String, message: String) { }
                override fun onEffectRequested(modId: String, eventId: String) { }
            }
            val second = object : ModBrokerListener {
                override fun onReady(modId: String, model: ModPersistedModel) { secondReady.countDown() }
                override fun onModelCommitted(modId: String, eventId: String?, model: ModPersistedModel) { secondCommit.countDown() }
                override fun onError(modId: String, code: String, message: String) { }
                override fun onEffectRequested(modId: String, eventId: String) { }
            }
            broker.open(installed.id, java.util.concurrent.Executor { it.run() }, first)
            broker.open(installed.id, java.util.concurrent.Executor { it.run() }, second)
            assertTrue(firstReady.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(secondReady.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse("declarative/no-script Mod must not create a QuickJS worker", broker.hasRuntimeForTesting(installed.id))
            val saved = java.util.concurrent.CountDownLatch(1)
            broker.saveConfig(installed.id, JSONObject(), java.util.concurrent.Executor { it.run() }) { ok, _ -> if (ok) saved.countDown() }
            assertTrue(saved.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse("native config saves stay in the direct Store path", broker.hasRuntimeForTesting(installed.id))
            assertTrue(firstCommit.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(secondCommit.await(3, java.util.concurrent.TimeUnit.SECONDS))

            broker.closeObserver(installed.id, first)
            val onlySecond = java.util.concurrent.CountDownLatch(1)
            broker.saveConfig(installed.id, JSONObject(), java.util.concurrent.Executor { it.run() }) { ok, _ -> if (ok) onlySecond.countDown() }
            assertTrue(onlySecond.await(3, java.util.concurrent.TimeUnit.SECONDS))
            val reopened = java.util.concurrent.CountDownLatch(1)
            val third = object : ModBrokerListener {
                override fun onReady(modId: String, model: ModPersistedModel) { reopened.countDown() }
                override fun onModelCommitted(modId: String, eventId: String?, model: ModPersistedModel) { }
                override fun onError(modId: String, code: String, message: String) { }
                override fun onEffectRequested(modId: String, eventId: String) { }
            }
            broker.closeObserver(installed.id, second)
            broker.open(installed.id, java.util.concurrent.Executor { it.run() }, third)
            assertTrue(reopened.await(3, java.util.concurrent.TimeUnit.SECONDS))
            broker.closeObserver(installed.id, third)
        } finally {
            broker.close()
            fixtureStore.uninstall( installed.id)
        }
    }

    @Test fun packageReplacementWaitsForAcceptedConfigAndCanResumeOldMod() {
        val archive = context.assets.open("platform/mods/no-script-info.zip").use { it.readBytes() }
        val pkg = ModPackageReader.read(ByteArrayInputStream(archive))
        val installed = fixtureStore.install( archive, ModInstallDecision(pkg.authorFingerprint, setOf("data.battery")))
        val broker = newBroker()
        try {
            fixtureStore.setEnabled( installed.id, true)
            val ready = java.util.concurrent.CountDownLatch(1)
            val resumed = java.util.concurrent.CountDownLatch(1)
            val listener = object : ModBrokerListener {
                private var readyCount = 0
                override fun onReady(modId: String, model: ModPersistedModel) { if (++readyCount == 1) ready.countDown() else resumed.countDown() }
                override fun onModelCommitted(modId: String, eventId: String?, model: ModPersistedModel) { }
                override fun onError(modId: String, code: String, message: String) { }
                override fun onEffectRequested(modId: String, eventId: String) { }
            }
            broker.open(installed.id, java.util.concurrent.Executor { it.run() }, listener)
            assertTrue(ready.await(3, java.util.concurrent.TimeUnit.SECONDS))
            val acceptedConfig = java.util.concurrent.CountDownLatch(1)
            val replacementReady = java.util.concurrent.CountDownLatch(1)
            broker.saveConfig(installed.id, JSONObject(), java.util.concurrent.Executor { it.run() }) { ok, _ -> if (ok) acceptedConfig.countDown() }
            broker.beginPackageReplacement(installed.id, java.util.concurrent.Executor { it.run() }) { ok, _ -> if (ok) replacementReady.countDown() }
            assertTrue("accepted config must commit before replacement quiesces", acceptedConfig.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("replacement waits for idle queue", replacementReady.await(3, java.util.concurrent.TimeUnit.SECONDS))
            broker.finishPackageReplacement(installed.id, false)
            assertTrue("failed update restores the installed old package", resumed.await(3, java.util.concurrent.TimeUnit.SECONDS))
            broker.closeObserver(installed.id, listener)
        } finally {
            broker.close()
            fixtureStore.uninstall( installed.id)
        }
    }

    private fun signedArchiveWithCapability(capability: String): ByteArray {
        val raw = context.assets.open("platform/mods/generic-counter.zip").use { it.readBytes() }
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(raw)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        val manifest = JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8))
        val pair = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(pair.public.encoded)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        manifest.getJSONObject("author").put("keyFingerprint", fingerprint)
        manifest.getJSONObject("capabilities").getJSONArray("required")
            .put(JSONObject().put("name", capability).put("minimumVersion", 1))
        val manifestBytes = manifest.toString().toByteArray(Charsets.UTF_8)
        val signer = java.security.Signature.getInstance("SHA256withRSA").apply { initSign(pair.private); update(manifestBytes) }
        val publicPem = "-----BEGIN PUBLIC KEY-----\n" +
            android.util.Base64.encodeToString(pair.public.encoded, android.util.Base64.NO_WRAP) +
            "\n-----END PUBLIC KEY-----\n"
        entries["manifest.json"] = manifestBytes
        entries["manifest.sig"] = signer.sign()
        entries["author.pub"] = publicPem.toByteArray(Charsets.US_ASCII)
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun signedActionArchive(ref: JSONObject, requireActionConfig: Boolean = false, actionIdPattern: String = "^[a-f0-9]{48}$"): ByteArray {
        val raw = context.assets.open("platform/mods/generic-counter.zip").use { it.readBytes() }
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(raw)).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; entries[entry.name] = zip.readBytes() }
        }
        val manifest = JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8))
        val actionRefSchema = JSONObject().put("type", "object")
            .put("properties", JSONObject()
                .put("id", JSONObject().put("type", "string").put("maxLength", 48).put("pattern", actionIdPattern))
                .put("version", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 1).put("const", 1)))
            .put("required", org.json.JSONArray().put("id").put("version")).put("additionalProperties", false)
        val configSchema = manifest.getJSONObject("data").getJSONObject("configSchema")
        configSchema.getJSONObject("properties").put("actionRef", actionRefSchema)
        if (requireActionConfig) {
            actionRefSchema.put("default", JSONObject().put("id", "a".repeat(48)).put("version", 1))
            configSchema.put("required", org.json.JSONArray().put("actionRef"))
        }
        manifest.getJSONObject("capabilities").getJSONArray("required")
            .put(JSONObject().put("name", "action.invoke").put("minimumVersion", 1))
        entries["settings.ui.json"] = """{"scene":"settings","root":{"type":"layout.column","children":[{"type":"settings.action","label":"动作","value":{"bind":"config.actionRef"}},{"type":"settings.save"}]}}""".toByteArray()
        entries["rear.ui.json"] = JSONObject().put("scene", "rear").put("root", JSONObject()
            .put("type", "ui.button").put("label", "执行动作")
            .put("onPress", JSONObject().put("type", "registeredAction").put("ref", ref))).toString().toByteArray()
        val payloads = manifest.getJSONArray("payloads")
        for (i in 0 until payloads.length()) {
            val payload = payloads.getJSONObject(i); val bytes = entries.getValue(payload.getString("path"))
            payload.put("bytes", bytes.size).put("sha256", java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') })
        }
        val pair = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(pair.public.encoded)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        manifest.getJSONObject("author").put("keyFingerprint", fingerprint)
        val manifestBytes = manifest.toString().toByteArray(Charsets.UTF_8)
        val signer = java.security.Signature.getInstance("SHA256withRSA").apply { initSign(pair.private); update(manifestBytes) }
        val publicPem = "-----BEGIN PUBLIC KEY-----\n" + android.util.Base64.encodeToString(pair.public.encoded, android.util.Base64.NO_WRAP) + "\n-----END PUBLIC KEY-----\n"
        entries["manifest.json"] = manifestBytes; entries["manifest.sig"] = signer.sign(); entries["author.pub"] = publicPem.toByteArray(Charsets.US_ASCII)
        return ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip -> entries.forEach { (name, bytes) -> zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(bytes); zip.closeEntry() } } }.toByteArray()
    }

    @Test fun registeredActionPickerBindingRequiresOptionalOwnerOpaqueConfig() {
        val accepted = ModPackageReader.read(ByteArrayInputStream(signedActionArchive(JSONObject().put("bind", "config.actionRef"))))
        assertTrue(accepted.requiredCapabilities.any { it.name == "action.invoke" })
        val stateBinding = assertThrows(ModPackageException::class.java) {
            ModPackageReader.read(ByteArrayInputStream(signedActionArchive(JSONObject().put("bind", "state.actionRef"))))
        }
        assertEquals(ModPackageError.INVALID_UI, stateBinding.code)
        val requiredConfig = assertThrows(ModPackageException::class.java) {
            ModPackageReader.read(ByteArrayInputStream(signedActionArchive(JSONObject().put("bind", "config.actionRef"), requireActionConfig = true)))
        }
        assertEquals(ModPackageError.INVALID_UI, requiredConfig.code)
        val unknownConfig = assertThrows(ModPackageException::class.java) {
            ModPackageReader.read(ByteArrayInputStream(signedActionArchive(JSONObject().put("bind", "config.missingAction"))))
        }
        assertEquals(ModPackageError.INVALID_UI, unknownConfig.code)
        val malformedOpaqueSchema = assertThrows(ModPackageException::class.java) {
            ModPackageReader.read(ByteArrayInputStream(signedActionArchive(JSONObject().put("bind", "config.actionRef"), actionIdPattern = "^[a-z]+$")))
        }
        assertTrue(malformedOpaqueSchema.code in setOf(ModPackageError.INVALID_SCHEMA, ModPackageError.INVALID_UI))
        val nonOpaqueLiteral = assertThrows(ModPackageException::class.java) {
            ModPackageReader.read(ByteArrayInputStream(signedActionArchive(JSONObject().put("id", "action.start").put("version", 1))))
        }
        assertEquals(ModPackageError.INVALID_UI, nonOpaqueLiteral.code)
    }

    @Test fun rearCastCapabilityIsRejectedByHostParser() {
        val error = assertThrows(ModPackageException::class.java) {
            ModPackageReader.read(ByteArrayInputStream(signedArchiveWithCapability("rear.cast")))
        }
        assertEquals(ModPackageError.UNKNOWN_CAPABILITY, error.code)
    }

    private fun rewriteZip(input: ByteArray, transform: (String, ByteArray) -> ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        ZipInputStream(ByteArrayInputStream(input)).use { source ->
            ZipOutputStream(out).use { target ->
                while (true) {
                    val entry = source.nextEntry ?: break
                    target.putNextEntry(java.util.zip.ZipEntry(entry.name))
                    val bytes = transform(entry.name, source.readBytes())
                    target.write(bytes)
                    target.closeEntry()
                }
            }
        }
        return out.toByteArray()
    }

    @Test fun isolatedCounterReduceCommitsOnceToBothObservers() {
        val archive = context.assets.open("platform/mods/generic-counter.zip").use { it.readBytes() }
        val pkg = ModPackageReader.read(ByteArrayInputStream(archive))
        val installed = fixtureStore.install( archive,
            ModInstallDecision(pkg.authorFingerprint, emptySet()))
        val broker = newBroker()
        val ready = java.util.concurrent.CountDownLatch(2)
        val commits = java.util.concurrent.CountDownLatch(2)
        val errors = java.util.concurrent.CopyOnWriteArrayList<String>()
        val first = object : ModBrokerListener {
            override fun onReady(modId: String, model: ModPersistedModel) {
                assertEquals(0, model.state?.getInt("count")); ready.countDown()
            }
            override fun onModelCommitted(modId: String, eventId: String?, model: ModPersistedModel) {
                if (eventId != null && model.state?.optInt("count") == 1) commits.countDown()
            }
            override fun onError(modId: String, code: String, message: String) { errors += "$code: $message" }
            override fun onEffectRequested(modId: String, eventId: String) { }
        }
        val second = object : ModBrokerListener {
            override fun onReady(modId: String, model: ModPersistedModel) { ready.countDown() }
            override fun onModelCommitted(modId: String, eventId: String?, model: ModPersistedModel) {
                if (eventId != null && model.state?.optInt("count") == 1) commits.countDown()
            }
            override fun onError(modId: String, code: String, message: String) { errors += "$code: $message" }
            override fun onEffectRequested(modId: String, eventId: String) { }
        }
        try {
            fixtureStore.setEnabled( installed.id, true)
            val direct = java.util.concurrent.Executor { it.run() }
            broker.open(installed.id, direct, first)
            broker.open(installed.id, direct, second)
            assertTrue("isolated QuickJS init", ready.await(8, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("counter event accepted", broker.submitEvent(installed.id, "counter.increment"))
            assertTrue("one runtime reduce is committed to both observers", commits.await(8, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(emptyList<String>(), errors)
            assertEquals(1, fixtureStore.model( installed.id)?.state?.getInt("count"))
        } finally {
            broker.closeObserver(installed.id, first)
            broker.closeObserver(installed.id, second)
            broker.close()
            fixtureStore.uninstall( installed.id)
        }
    }

    @Test fun savedConfigRunsHostReducerAndCommitsDerivedStateWithConfigForBothObservers() {
        val archive = context.assets.open("platform/mods/config-derived-counter.zip").use { it.readBytes() }
        val pkg = ModPackageReader.read(ByteArrayInputStream(archive))
        val grants = pkg.requiredCapabilities.map { it.name }.toSet()
        val installed = fixtureStore.install(archive, ModInstallDecision(pkg.authorFingerprint, grants))
        assertTrue(fixtureStore.setEnabled(installed.id, true).enabled)
        val broker = newBroker()
        val ready = java.util.concurrent.CountDownLatch(2)
        val initializedModel = java.util.concurrent.atomic.AtomicReference<ModPersistedModel>()
        val deliveries = java.util.concurrent.CopyOnWriteArrayList<ModPersistedModel>()
        val bothCommitted = java.util.concurrent.CountDownLatch(2)
        val saved = java.util.concurrent.CountDownLatch(1)
        val errors = java.util.concurrent.CopyOnWriteArrayList<String>()
        val direct = java.util.concurrent.Executor { it.run() }
        fun observer() = object : ModBrokerListener {
            override fun onReady(modId: String, model: ModPersistedModel) {
                initializedModel.compareAndSet(null, model)
                ready.countDown()
            }
            override fun onModelCommitted(modId: String, eventId: String?, model: ModPersistedModel) {
                if (eventId != null && model.config.optInt("step") == 4) {
                    deliveries += model
                    bothCommitted.countDown()
                }
            }
            override fun onError(modId: String, code: String, message: String) { errors += "$code: $message" }
            override fun onEffectRequested(modId: String, eventId: String) { errors += "unexpected effect $eventId" }
        }
        val first = observer()
        val second = observer()
        try {
            broker.open(installed.id, direct, first)
            broker.open(installed.id, direct, second)
            assertTrue("both observers attach to initialized QuickJS Mod", ready.await(8, java.util.concurrent.TimeUnit.SECONDS))
            val beforeRevision = requireNotNull(initializedModel.get()).revision
            assertTrue("reserved host event cannot be submitted as a business event",
                !broker.submitEvent(installed.id, ModEventBroker.HOST_CONFIG_CHANGED_EVENT))
            broker.saveConfig(installed.id, JSONObject().put("step", 4).put("rejectChange", false), direct) { ok, _ -> if (ok) saved.countDown() }
            assertTrue("Save completes only after the reducer result is committed", saved.await(8, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("both observers receive the committed config-change revision", bothCommitted.await(8, java.util.concurrent.TimeUnit.SECONDS))
            val committed = requireNotNull(fixtureStore.model(installed.id))
            assertEquals(beforeRevision + 1, committed.revision)
            assertEquals(4, committed.config.getInt("step"))
            assertEquals(4, committed.state?.getInt("stepUsed"))
            assertEquals(1, committed.state?.getInt("previousStep"))
            assertEquals(0, committed.state?.getInt("count"))
            assertEquals(2, deliveries.size)
            deliveries.forEach { model ->
                assertEquals(committed.revision, model.revision)
                assertEquals(canonicalJson(committed.config), canonicalJson(model.config))
                assertEquals(canonicalJson(committed.state), canonicalJson(model.state))
            }
            assertTrue("no intermediate config/state pairing or Effect was delivered", errors.isEmpty())
        } finally {
            broker.closeObserver(installed.id, first)
            broker.closeObserver(installed.id, second)
            broker.close()
            fixtureStore.uninstall(installed.id)
        }
    }

    @Test fun rejectedHostConfigChangeKeepsBothObserversAndAllowsRetryAndAnotherEvent() {
        val archive = context.assets.open("platform/mods/config-derived-counter.zip").use { it.readBytes() }
        val pkg = ModPackageReader.read(ByteArrayInputStream(archive))
        val installed = fixtureStore.install(archive, ModInstallDecision(pkg.authorFingerprint, pkg.requiredCapabilities.map { it.name }.toSet()))
        assertTrue(fixtureStore.setEnabled(installed.id, true).enabled)
        val broker = newBroker()
        val ready = java.util.concurrent.CountDownLatch(2)
        val recoveredReady = java.util.concurrent.CountDownLatch(2)
        val readyCount = java.util.concurrent.atomic.AtomicInteger()
        val tailEventCommitted = java.util.concurrent.CountDownLatch(2)
        val tailConfigCommitted = java.util.concurrent.CountDownLatch(2)
        val tailConfigSaved = java.util.concurrent.CountDownLatch(1)
        val legalSaveCommits = java.util.concurrent.CountDownLatch(2)
        val otherObserverCommits = java.util.concurrent.CountDownLatch(2)
        val failures = java.util.concurrent.CopyOnWriteArrayList<String>()
        val direct = java.util.concurrent.Executor { it.run() }
        var closeBarrier: ModRuntimeCloseBarrierBridge.Barrier? = null
        fun observer() = object : ModBrokerListener {
            override fun onReady(modId: String, model: ModPersistedModel) {
                if (readyCount.incrementAndGet() <= 2) ready.countDown() else recoveredReady.countDown()
            }
            override fun onModelCommitted(modId: String, eventId: String?, model: ModPersistedModel) {
                if (model.config.optInt("step") == 4) legalSaveCommits.countDown()
                if (model.state?.optInt("count") == 1) tailEventCommitted.countDown()
                if (model.config.optInt("step") == 2) tailConfigCommitted.countDown()
                if (model.state?.optInt("count") == 5) otherObserverCommits.countDown()
            }
            override fun onError(modId: String, code: String, message: String) { failures += "$code: $message" }
            override fun onEffectRequested(modId: String, eventId: String) { failures += "unexpected effect $eventId" }
        }
        val first = observer()
        val second = observer()
        try {
            broker.open(installed.id, direct, first)
            broker.open(installed.id, direct, second)
            assertTrue("both QuickJS observers attach", ready.await(8, java.util.concurrent.TimeUnit.SECONDS))
            val before = requireNotNull(fixtureStore.model(installed.id))
            closeBarrier = ModRuntimeCloseBarrierBridge.blockNextClose()
            val callback = java.util.concurrent.CountDownLatch(1)
            val callbackResult = java.util.concurrent.atomic.AtomicReference<Pair<Boolean, String?>>()
            broker.saveConfig(installed.id, JSONObject().put("step", 5).put("rejectChange", true), direct) { ok, message ->
                callbackResult.set(ok to message); callback.countDown()
            }
            assertTrue("typed script failure begins a real worker reset", closeBarrier!!.awaitEntered(8, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("valid event input remains accepted while worker reset is pending",
                broker.submitEvent(installed.id, "counter.increment"))
            broker.saveConfig(installed.id, JSONObject().put("step", 2).put("rejectChange", false), direct) { ok, _ ->
                if (ok) tailConfigSaved.countDown()
            }
            closeBarrier!!.release()
            assertTrue("save failure is reported to the form", callback.await(8, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(false, callbackResult.get()?.first)
            assertTrue("the actual reducer error is reported to this Save", callbackResult.get()?.second?.contains("E_SCRIPT") == true)
            val after = requireNotNull(fixtureStore.model(installed.id))
            assertEquals(before.revision, after.revision)
            assertEquals(canonicalJson(before.config), canonicalJson(after.config))
            assertEquals(canonicalJson(before.state), canonicalJson(after.state))
            assertEquals(1, fixtureStore.installed(installed.id)?.versionCode)
            assertTrue(fixtureStore.installed(installed.id)?.enabled == true)
            assertTrue("both observers reattach to a fresh worker loaded from the committed model",
                recoveredReady.await(8, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("accepted event before the tail Save drains after recovery", tailEventCommitted.await(8, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("accepted tail Config drains after recovery", tailConfigSaved.await(8, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("both observers receive the tail Config revision", tailConfigCommitted.await(8, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("recovered worker is available for a same-screen retry", broker.hasRuntimeForTesting(installed.id))

            val retry = java.util.concurrent.CountDownLatch(1)
            val retryResult = java.util.concurrent.atomic.AtomicReference<Pair<Boolean, String?>>()
            broker.saveConfig(installed.id, JSONObject().put("step", 4).put("rejectChange", false), direct) { ok, message ->
                retryResult.set(ok to message); retry.countDown()
            }
            assertTrue("same settings screen can retry without reopening", retry.await(8, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(true, retryResult.get()?.first)
            assertTrue("the retry commits to both observers", legalSaveCommits.await(8, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("observer slot and runtime remain usable", broker.hasRuntimeForTesting(installed.id))

            assertTrue("the other visible observer can continue business input", broker.submitEvent(installed.id, "counter.increment"))
            assertTrue("the second observer receives the next reducer commit", otherObserverCommits.await(8, java.util.concurrent.TimeUnit.SECONDS))
            val finalModel = requireNotNull(fixtureStore.model(installed.id))
            assertEquals(5, finalModel.state?.getInt("count"))
            assertEquals(4, finalModel.config.getInt("step"))
            assertEquals(2, finalModel.state?.getInt("previousStep"))
            assertTrue("script refusal does not broadcast a session failure", failures.isEmpty())
        } finally {
            closeBarrier?.release()
            broker.closeObserver(installed.id, first)
            broker.closeObserver(installed.id, second)
            broker.close()
            fixtureStore.uninstall(installed.id)
        }
    }

    @Test fun brokerCloseSettlesRejectedConfigSaveWhileWorkerCloseIsBlocked() {
        val archive = context.assets.open("platform/mods/config-derived-counter.zip").use { it.readBytes() }
        val pkg = ModPackageReader.read(ByteArrayInputStream(archive))
        val installed = fixtureStore.install(archive,
            ModInstallDecision(pkg.authorFingerprint, pkg.requiredCapabilities.map { it.name }.toSet()))
        assertTrue(fixtureStore.setEnabled(installed.id, true).enabled)
        val broker = newBroker()
        val ready = java.util.concurrent.CountDownLatch(2)
        val readyCount = java.util.concurrent.atomic.AtomicInteger()
        val saveCallbacks = java.util.concurrent.atomic.AtomicInteger()
        val saveFinished = java.util.concurrent.CountDownLatch(1)
        val saveResult = java.util.concurrent.atomic.AtomicReference<Pair<Boolean, String?>>()
        val direct = java.util.concurrent.Executor { it.run() }
        var closeBarrier: ModRuntimeCloseBarrierBridge.Barrier? = null
        fun listener() = object : ModBrokerListener {
            override fun onReady(modId: String, model: ModPersistedModel) {
                readyCount.incrementAndGet()
                ready.countDown()
            }
            override fun onModelCommitted(modId: String, eventId: String?, model: ModPersistedModel) { }
            override fun onError(modId: String, code: String, message: String) { }
            override fun onEffectRequested(modId: String, eventId: String) { }
        }
        val first = listener()
        val second = listener()
        try {
            broker.open(installed.id, direct, first)
            broker.open(installed.id, direct, second)
            assertTrue("both observers attach before Save", ready.await(8, java.util.concurrent.TimeUnit.SECONDS))
            val before = requireNotNull(fixtureStore.model(installed.id))

            closeBarrier = ModRuntimeCloseBarrierBridge.blockNextClose()
            broker.saveConfig(installed.id, JSONObject().put("step", 5).put("rejectChange", true), direct) { ok, message ->
                saveCallbacks.incrementAndGet()
                saveResult.set(ok to message)
                saveFinished.countDown()
            }
            assertTrue("rejected host Config starts a confirmed close", closeBarrier!!.awaitEntered(8, java.util.concurrent.TimeUnit.SECONDS))
            broker.close()

            assertTrue("broker close settles the in-flight Save before worker destruction finishes",
                saveFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(false, saveResult.get()?.first)
            assertTrue("the Save reports HostClosed, not an empty callback or reducer error",
                saveResult.get()?.second?.contains("E_HOST_CLOSED") == true)
            assertEquals("one callback settles the failed Save", 1, saveCallbacks.get())
            val after = requireNotNull(fixtureStore.model(installed.id))
            assertEquals(before.revision, after.revision)
            assertEquals(canonicalJson(before.config), canonicalJson(after.config))
            assertEquals(canonicalJson(before.state), canonicalJson(after.state))

            closeBarrier!!.release()
            val probeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
            var probe: IsolatedModRuntimeClient? = null
            try {
                val probeConnected = java.util.concurrent.CountDownLatch(1)
                probe = IsolatedModRuntimeClient.bind(appContext, 9_734L, probeExecutor, object : IsolatedModRuntimeClient.Listener {
                    override fun onConnected(epoch: Long) { probeConnected.countDown() }
                    override fun onResult(epoch: Long, operation: String, json: String) { }
                    override fun onDisconnected(epoch: Long, reason: String) { }
                })
                assertTrue("the closed broker's worker slot returns after the barrier releases",
                    probeConnected.await(8, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals("closing must not recreate the failed observer pair", 2, readyCount.get())
                assertEquals("late close completion cannot settle the Save twice", 1, saveCallbacks.get())
            } finally {
                probe?.closeAsync()?.get(8, java.util.concurrent.TimeUnit.SECONDS)
                probeExecutor.shutdownNow()
            }
        } finally {
            closeBarrier?.release()
            broker.closeObserver(installed.id, first)
            broker.closeObserver(installed.id, second)
            broker.close()
            fixtureStore.uninstall(installed.id)
        }
    }

    @Test fun configChangeEffectIsRejectedBeforeDispatchOrModelCommit() {
        val archive = context.assets.open("platform/mods/config-derived-counter.zip").use { it.readBytes() }
        val pkg = ModPackageReader.read(ByteArrayInputStream(archive))
        val installed = fixtureStore.install(archive, ModInstallDecision(pkg.authorFingerprint, pkg.requiredCapabilities.map { it.name }.toSet()))
        assertTrue(fixtureStore.setEnabled(installed.id, true).enabled)
        val broker = newBroker()
        val ready = java.util.concurrent.CountDownLatch(1)
        val committed = java.util.concurrent.CountDownLatch(1)
        val effects = java.util.concurrent.atomic.AtomicInteger()
        val observerErrors = java.util.concurrent.CopyOnWriteArrayList<String>()
        val direct = java.util.concurrent.Executor { it.run() }
        val listener = object : ModBrokerListener {
            override fun onReady(modId: String, model: ModPersistedModel) { ready.countDown() }
            override fun onModelCommitted(modId: String, eventId: String?, model: ModPersistedModel) { committed.countDown() }
            override fun onError(modId: String, code: String, message: String) { observerErrors += "$code: $message" }
            override fun onEffectRequested(modId: String, eventId: String) { effects.incrementAndGet() }
        }
        try {
            broker.open(installed.id, direct, listener)
            assertTrue("QuickJS init completes", ready.await(8, java.util.concurrent.TimeUnit.SECONDS))
            val before = requireNotNull(fixtureStore.model(installed.id))
            val callback = java.util.concurrent.CountDownLatch(1)
            val saveResult = java.util.concurrent.atomic.AtomicReference<Pair<Boolean, String?>>()
            broker.saveConfig(installed.id, JSONObject().put("step", 6).put("emitEffect", true), direct) { ok, reason ->
                saveResult.set(ok to reason); callback.countDown()
            }
            assertTrue("activation-bound Effect makes this Save fail", callback.await(8, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(false, saveResult.get()?.first)
            assertTrue("failure explains that Save cannot mint activation", saveResult.get()?.second?.contains("E_ACTIVATION_REQUIRED") == true)
            assertEquals(0, effects.get())
            val after = requireNotNull(fixtureStore.model(installed.id))
            assertEquals(before.revision, after.revision)
            assertEquals(canonicalJson(before.config), canonicalJson(after.config))
            assertEquals(canonicalJson(before.state), canonicalJson(after.state))
            assertTrue("no rear activation occurs as a side effect of Save", effects.get() == 0)
            assertTrue("activation refusal does not tear down the reducer worker", broker.hasRuntimeForTesting(installed.id))
            assertTrue("normal business event still runs after the rejected Effect", broker.submitEvent(installed.id, "counter.increment"))
            assertTrue("business event result is delivered after refusal", committed.await(8, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(1, fixtureStore.model(installed.id)?.state?.getInt("count"))
            assertTrue(observerErrors.isEmpty())
        } finally {
            broker.closeObserver(installed.id, listener)
            broker.close()
            fixtureStore.uninstall(installed.id)
        }
    }

    @Test fun acceptedReducerThenTailConfigDrainsBeforeReplacementAndLastObserverClose() {
        val archive = context.assets.open("platform/mods/config-derived-counter.zip").use { it.readBytes() }
        val pkg = ModPackageReader.read(ByteArrayInputStream(archive))
        val installed = fixtureStore.install(archive, ModInstallDecision(pkg.authorFingerprint, pkg.requiredCapabilities.map { it.name }.toSet()))
        assertTrue(fixtureStore.setEnabled(installed.id, true).enabled)
        val broker = newBroker()
        val direct = java.util.concurrent.Executor { it.run() }
        val firstReady = java.util.concurrent.CountDownLatch(1)
        val resumed = java.util.concurrent.CountDownLatch(1)
        val replacementReady = java.util.concurrent.CountDownLatch(1)
        val firstEvent = java.util.concurrent.CountDownLatch(1)
        val tailEvent = java.util.concurrent.CountDownLatch(1)
        val replacementConfig = java.util.concurrent.CountDownLatch(1)
        val restoredEvent = java.util.concurrent.CountDownLatch(1)
        val closeConfig = java.util.concurrent.CountDownLatch(1)
        val failures = java.util.concurrent.CopyOnWriteArrayList<String>()
        val readyIndex = java.util.concurrent.atomic.AtomicInteger()
        val queuedReplacement = java.util.concurrent.atomic.AtomicBoolean()
        val queuedClose = java.util.concurrent.atomic.AtomicBoolean()
        val listener = object : ModBrokerListener {
            override fun onReady(modId: String, model: ModPersistedModel) {
                if (readyIndex.incrementAndGet() == 1) firstReady.countDown() else resumed.countDown()
            }
            override fun onModelCommitted(modId: String, eventId: String?, model: ModPersistedModel) {
                when (model.state?.optInt("count")) {
                    1 -> {
                        firstEvent.countDown()
                        if (queuedReplacement.compareAndSet(false, true)) {
                            check(broker.submitEvent(installed.id, "counter.increment"))
                            broker.saveConfig(installed.id, JSONObject().put("step", 3).put("rejectChange", false), direct) { ok, _ ->
                                if (ok) replacementConfig.countDown()
                            }
                            broker.beginPackageReplacement(installed.id, direct) { ok, _ -> if (ok) replacementReady.countDown() }
                        }
                    }
                    2 -> tailEvent.countDown()
                    5 -> {
                        restoredEvent.countDown()
                        if (queuedClose.compareAndSet(false, true)) {
                            check(broker.submitEvent(installed.id, "counter.increment"))
                            broker.saveConfig(installed.id, JSONObject().put("step", 4).put("rejectChange", false), direct) { ok, _ ->
                                if (ok) closeConfig.countDown()
                            }
                            broker.closeObserver(installed.id, this)
                        }
                    }
                }
            }
            override fun onError(modId: String, code: String, message: String) {
                if (code != "E_PACKAGE_UPDATING") failures += "$code: $message"
            }
            override fun onEffectRequested(modId: String, eventId: String) { failures += "unexpected effect $eventId" }
        }
        try {
            broker.open(installed.id, direct, listener)
            assertTrue("counter initializes", firstReady.await(8, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(broker.submitEvent(installed.id, "counter.increment"))
            assertTrue("first reduce commits", firstEvent.await(8, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("accepted tail reduce drains", tailEvent.await(8, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("tail configChanged commits before worker teardown", replacementConfig.await(8, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("replacement begins only after reducer/config queue drains", replacementReady.await(8, java.util.concurrent.TimeUnit.SECONDS))
            var model = requireNotNull(fixtureStore.model(installed.id))
            assertEquals(2, model.state?.getInt("count"))
            assertEquals(3, model.config.getInt("step"))
            assertEquals(3, model.state?.getInt("stepUsed"))
            assertEquals(1, model.state?.getInt("previousStep"))

            broker.finishPackageReplacement(installed.id, false)
            assertTrue("old package resumes with committed config and state", resumed.await(8, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("resumed reducer uses committed step", broker.submitEvent(installed.id, "counter.increment"))
            assertTrue("resumed worker commits using new config", restoredEvent.await(8, java.util.concurrent.TimeUnit.SECONDS))
            val closeDrain = requireNotNull(broker.closeAfterDrainForTesting(installed.id))
            assertTrue("tail work before last observer close is accepted", closeConfig.await(8, java.util.concurrent.TimeUnit.SECONDS))
            closeDrain.get(8, java.util.concurrent.TimeUnit.SECONDS)
            model = requireNotNull(fixtureStore.model(installed.id))
            assertEquals("final queued increment uses prior committed step 3", 8, model.state?.getInt("count"))
            assertEquals(4, model.config.getInt("step"))
            assertEquals(4, model.state?.getInt("stepUsed"))
            assertEquals(3, model.state?.getInt("previousStep"))
            assertTrue("no Effect or queue failure during either drain", failures.isEmpty())
        } finally {
            broker.closeObserver(installed.id, listener)
            broker.close()
            fixtureStore.uninstall(installed.id)
        }
    }

    @Test fun failedQuickJsMigrationPreservesOldModelAndReleasesSlotBeforeResume() {
        val targetV1 = context.assets.open("platform/mods/counter-transaction-v1.zip").use { it.readBytes() }
        val targetV2 = context.assets.open("platform/mods/counter-transaction-v2-invalid-migration.zip").use { it.readBytes() }
        val secondArchive = context.assets.open("platform/mods/generic-counter.zip").use { it.readBytes() }
        val targetPkg = ModPackageReader.read(ByteArrayInputStream(targetV1))
        val secondPkg = ModPackageReader.read(ByteArrayInputStream(secondArchive))
        assertEquals(targetPkg.authorFingerprint, ModPackageReader.read(ByteArrayInputStream(targetV2)).authorFingerprint)
        listOf(targetPkg, secondPkg).forEach { fixture ->
            fixtureStore.installed( fixture.id)?.let { stale ->
                require(stale.authorFingerprint == fixture.authorFingerprint) {
                    "transaction fixture ID belongs to another author; preserving the installed package"
                }
                fixtureStore.uninstall( fixture.id)
            }
        }
        val targetGrants = targetPkg.requiredCapabilities.map { it.name }.toSet()
        val secondGrants = secondPkg.requiredCapabilities.map { it.name }.toSet()
        val target = fixtureStore.install( targetV1, ModInstallDecision(targetPkg.authorFingerprint, targetGrants))
        val second = fixtureStore.install( secondArchive, ModInstallDecision(secondPkg.authorFingerprint, secondGrants))
        val broker = newBroker()
        val targetReady = java.util.concurrent.CountDownLatch(1)
        val resumedReady = java.util.concurrent.CountDownLatch(1)
        val reopenedReady = java.util.concurrent.CountDownLatch(1)
        val secondReady = java.util.concurrent.CountDownLatch(1)
        val eventCommitted = java.util.concurrent.CountDownLatch(1)
        val tailEventCommitted = java.util.concurrent.CountDownLatch(1)
        val tailConfigCommitted = java.util.concurrent.CountDownLatch(1)
        val replacementReady = java.util.concurrent.CountDownLatch(1)
        val postRestoreEvent = java.util.concurrent.CountDownLatch(1)
        val failures = java.util.concurrent.CopyOnWriteArrayList<String>()
        val readyNumber = java.util.concurrent.atomic.AtomicInteger()
        val tailScheduled = java.util.concurrent.atomic.AtomicBoolean()
        val direct = java.util.concurrent.Executor { it.run() }
        val targetListener = object : ModBrokerListener {
            override fun onReady(modId: String, model: ModPersistedModel) {
                when (readyNumber.incrementAndGet()) {
                    1 -> targetReady.countDown()
                    2 -> resumedReady.countDown()
                    else -> {
                        assertEquals(3, model.state?.getInt("count"))
                        reopenedReady.countDown()
                    }
                }
            }
            override fun onModelCommitted(modId: String, eventId: String?, model: ModPersistedModel) {
                if (model.state?.optInt("count") == 1) {
                    eventCommitted.countDown()
                    if (tailScheduled.compareAndSet(false, true)) {
                        // Enqueue another real reduce, then a Config, then pause for
                        // replacement from inside the first reduce callback. The
                        // single broker executor must preserve this order while the
                        // second QuickJS call is in flight.
                        check(broker.submitEvent(target.id, "counter.increment"))
                        broker.saveConfig(target.id, JSONObject(), direct) { ok, _ -> if (ok) tailConfigCommitted.countDown() }
                        broker.beginPackageReplacement(target.id, direct) { ok, _ -> if (ok) replacementReady.countDown() }
                    }
                }
                if (model.state?.optInt("count") == 2) tailEventCommitted.countDown()
                if (model.state?.optInt("count") == 3) postRestoreEvent.countDown()
            }
            override fun onError(modId: String, code: String, message: String) {
                if (code != "E_PACKAGE_UPDATING") failures += "$code: $message"
            }
            override fun onEffectRequested(modId: String, eventId: String) { }
        }
        val secondListener = object : ModBrokerListener {
            override fun onReady(modId: String, model: ModPersistedModel) { secondReady.countDown() }
            override fun onModelCommitted(modId: String, eventId: String?, model: ModPersistedModel) { }
            override fun onError(modId: String, code: String, message: String) {
                if (code != "E_PACKAGE_UPDATING") failures += "$code: $message"
            }
            override fun onEffectRequested(modId: String, eventId: String) { }
        }
        try {
            fixtureStore.setEnabled( target.id, true)
            fixtureStore.setEnabled( second.id, true)
            broker.open(target.id, direct, targetListener)
            broker.open(second.id, direct, secondListener)
            assertTrue("two real QuickJS workers start", targetReady.await(8, java.util.concurrent.TimeUnit.SECONDS) && secondReady.await(8, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(broker.submitEvent(target.id, "counter.increment"))
            assertTrue("old model commits before update", eventCommitted.await(8, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("tail reducer commits before replacement", tailEventCommitted.await(8, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("queued config commits before replacement closes worker", tailConfigCommitted.await(8, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("old worker releases its slot before migration", replacementReady.await(8, java.util.concurrent.TimeUnit.SECONDS))
            val oldModel = requireNotNull(fixtureStore.model( target.id))
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                fixtureStore.install( targetV2, ModInstallDecision(targetPkg.authorFingerprint, targetGrants), IsolatedModMigrationRunner(appContext))
            }
            assertEquals(1, fixtureStore.installed( target.id)?.versionCode)
            assertEquals(oldModel.revision, fixtureStore.model( target.id)?.revision)
            assertEquals(2, oldModel.state?.getInt("count"))
            assertEquals(oldModel.state?.getInt("count"), fixtureStore.model( target.id)?.state?.getInt("count"))
            assertEquals(true, fixtureStore.installed( target.id)?.enabled)
            broker.finishPackageReplacement(target.id, false)
            assertTrue("old package worker resumes after failed pure migration", resumedReady.await(8, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("restored reducer remains usable", broker.submitEvent(target.id, "counter.increment"))
            assertTrue("restored reducer commits after failed migration", postRestoreEvent.await(8, java.util.concurrent.TimeUnit.SECONDS))

            // Keep the second Mod occupying one of the two runtime slots while the
            // target's last observer leaves and reopens. This proves closeAsync has
            // actually returned the target slot before a fresh epoch is bound.
            broker.closeObserver(target.id, targetListener)
            broker.open(target.id, direct, targetListener)
            assertTrue("reopen waits for confirmed old-worker slot release", reopenedReady.await(8, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("second worker remained available", fixtureStore.installed( second.id)?.enabled == true)
            assertTrue("unexpected runtime errors: $failures", failures.isEmpty())
        } finally {
            broker.closeObserver(target.id, targetListener)
            broker.closeObserver(second.id, secondListener)
            broker.close()
            fixtureStore.uninstall( target.id)
            fixtureStore.uninstall( second.id)
        }
    }

    @Test fun detachedPendingReopenDoesNotStartAnOrphanWorker() {
        val targetArchive = context.assets.open("platform/mods/counter-transaction-v1.zip").use { it.readBytes() }
        val secondArchive = context.assets.open("platform/mods/generic-counter.zip").use { it.readBytes() }
        val targetPkg = ModPackageReader.read(ByteArrayInputStream(targetArchive))
        val secondPkg = ModPackageReader.read(ByteArrayInputStream(secondArchive))
        listOf(targetPkg, secondPkg).forEach { fixture ->
            fixtureStore.installed( fixture.id)?.let { stale ->
                require(stale.authorFingerprint == fixture.authorFingerprint) { "fixture ID belongs to another author" }
                fixtureStore.uninstall( fixture.id)
            }
        }
        val target = fixtureStore.install( targetArchive,
            ModInstallDecision(targetPkg.authorFingerprint, targetPkg.requiredCapabilities.map { it.name }.toSet()))
        val second = fixtureStore.install( secondArchive,
            ModInstallDecision(secondPkg.authorFingerprint, secondPkg.requiredCapabilities.map { it.name }.toSet()))
        val broker = newBroker()
        val targetReady = java.util.concurrent.CountDownLatch(1)
        val secondReady = java.util.concurrent.CountDownLatch(1)
        val detachedReady = java.util.concurrent.CountDownLatch(1)
        val direct = java.util.concurrent.Executor { it.run() }
        val first = object : ModBrokerListener {
            override fun onReady(modId: String, model: ModPersistedModel) { targetReady.countDown() }
            override fun onModelCommitted(modId: String, eventId: String?, model: ModPersistedModel) { }
            override fun onError(modId: String, code: String, message: String) { }
            override fun onEffectRequested(modId: String, eventId: String) { }
        }
        val detached = object : ModBrokerListener {
            override fun onReady(modId: String, model: ModPersistedModel) { detachedReady.countDown() }
            override fun onModelCommitted(modId: String, eventId: String?, model: ModPersistedModel) { }
            override fun onError(modId: String, code: String, message: String) { }
            override fun onEffectRequested(modId: String, eventId: String) { }
        }
        val secondListener = object : ModBrokerListener {
            override fun onReady(modId: String, model: ModPersistedModel) { secondReady.countDown() }
            override fun onModelCommitted(modId: String, eventId: String?, model: ModPersistedModel) { }
            override fun onError(modId: String, code: String, message: String) { }
            override fun onEffectRequested(modId: String, eventId: String) { }
        }
        var closeBarrier: ModRuntimeCloseBarrierBridge.Barrier? = null
        var probe: IsolatedModRuntimeClient? = null
        val probeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            fixtureStore.setEnabled( target.id, true)
            fixtureStore.setEnabled( second.id, true)
            broker.open(target.id, direct, first)
            broker.open(second.id, direct, secondListener)
            assertTrue(targetReady.await(8, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(secondReady.await(8, java.util.concurrent.TimeUnit.SECONDS))

            closeBarrier = ModRuntimeCloseBarrierBridge.blockNextClose()
            broker.closeObserver(target.id, first)
            assertTrue("last visible observer starts a blocked real worker close",
                closeBarrier!!.awaitEntered(5, java.util.concurrent.TimeUnit.SECONDS))
            broker.open(target.id, direct, detached)
            assertTrue("B is queued for reopen while A's worker is closing",
                broker.hasPendingOpenObserverForTesting(target.id, detached))
            broker.closeObserver(target.id, detached)
            assertFalse("destroying B cancels the queued reopen request",
                broker.hasPendingOpenObserverForTesting(target.id, detached))
            val closed = requireNotNull(broker.closeAfterDrainForTesting(target.id))
            closeBarrier!!.release()
            closed.get(8, java.util.concurrent.TimeUnit.SECONDS)

            val probeConnected = java.util.concurrent.CountDownLatch(1)
            probe = IsolatedModRuntimeClient.bind(appContext, 9_201L, probeExecutor, object : IsolatedModRuntimeClient.Listener {
                override fun onConnected(epoch: Long) { probeConnected.countDown() }
                override fun onResult(epoch: Long, operation: String, json: String) { }
                override fun onDisconnected(epoch: Long, reason: String) { }
            })
            assertTrue("the slot released by A remains available while the second Mod stays open",
                probeConnected.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("B must not be resurrected as a no-page worker", 1L, detachedReady.count)
        } finally {
            closeBarrier?.release()
            probe?.closeAsync()?.get(8, java.util.concurrent.TimeUnit.SECONDS)
            broker.closeObserver(target.id, first)
            broker.closeObserver(target.id, detached)
            broker.closeObserver(second.id, secondListener)
            broker.closeAfterDrainForTesting(second.id)?.get(8, java.util.concurrent.TimeUnit.SECONDS)
            broker.close()
            probeExecutor.shutdownNow()
            fixtureStore.uninstall( target.id)
            fixtureStore.uninstall( second.id)
        }
    }

    @Test fun interruptedMigrationWaitsForConfirmedCloseBeforeReturningToStore() {
        val targetV1 = context.assets.open("platform/mods/counter-transaction-v1.zip").use { it.readBytes() }
        val targetV2 = context.assets.open("platform/mods/counter-transaction-v2-invalid-migration.zip").use { it.readBytes() }
        val secondArchive = context.assets.open("platform/mods/generic-counter.zip").use { it.readBytes() }
        val targetPkg = ModPackageReader.read(ByteArrayInputStream(targetV1))
        val secondPkg = ModPackageReader.read(ByteArrayInputStream(secondArchive))
        assertEquals(targetPkg.authorFingerprint, ModPackageReader.read(ByteArrayInputStream(targetV2)).authorFingerprint)
        listOf(targetPkg, secondPkg).forEach { fixture ->
            fixtureStore.installed( fixture.id)?.let { stale ->
                require(stale.authorFingerprint == fixture.authorFingerprint) { "fixture ID belongs to another author" }
                fixtureStore.uninstall( fixture.id)
            }
        }
        val targetGrants = targetPkg.requiredCapabilities.map { it.name }.toSet()
        val secondGrants = secondPkg.requiredCapabilities.map { it.name }.toSet()
        val target = fixtureStore.install( targetV1, ModInstallDecision(targetPkg.authorFingerprint, targetGrants))
        val second = fixtureStore.install( secondArchive, ModInstallDecision(secondPkg.authorFingerprint, secondGrants))
        val broker = newBroker()
        val targetInitialReady = java.util.concurrent.CountDownLatch(1)
        val targetResumed = java.util.concurrent.CountDownLatch(1)
        val targetReadyCount = java.util.concurrent.atomic.AtomicInteger()
        val secondReady = java.util.concurrent.CountDownLatch(1)
        val targetCommit = java.util.concurrent.CountDownLatch(1)
        val direct = java.util.concurrent.Executor { it.run() }
        val listener = object : ModBrokerListener {
            override fun onReady(modId: String, model: ModPersistedModel) {
                if (targetReadyCount.getAndIncrement() == 0) targetInitialReady.countDown() else targetResumed.countDown()
            }
            override fun onModelCommitted(modId: String, eventId: String?, model: ModPersistedModel) {
                if (model.state?.optInt("count") == 1) targetCommit.countDown()
            }
            override fun onError(modId: String, code: String, message: String) { }
            override fun onEffectRequested(modId: String, eventId: String) { }
        }
        val secondListener = object : ModBrokerListener {
            override fun onReady(modId: String, model: ModPersistedModel) { secondReady.countDown() }
            override fun onModelCommitted(modId: String, eventId: String?, model: ModPersistedModel) { }
            override fun onError(modId: String, code: String, message: String) { }
            override fun onEffectRequested(modId: String, eventId: String) { }
        }
        val migrationRequested = java.util.concurrent.CountDownLatch(1)
        val releaseMigrationCallback = java.util.concurrent.CountDownLatch(1)
        val migrationReturned = java.util.concurrent.CountDownLatch(1)
        val interruptedRestored = java.util.concurrent.atomic.AtomicBoolean(false)
        val migrationFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        var migrationThread: Thread? = null
        var closeBarrier: ModRuntimeCloseBarrierBridge.Barrier? = null
        try {
            fixtureStore.setEnabled( target.id, true)
            fixtureStore.setEnabled( second.id, true)
            broker.open(target.id, direct, listener)
            broker.open(second.id, direct, secondListener)
            assertTrue(targetInitialReady.await(8, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(secondReady.await(8, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(broker.submitEvent(target.id, "counter.increment"))
            assertTrue(targetCommit.await(8, java.util.concurrent.TimeUnit.SECONDS))

            val replacementReady = java.util.concurrent.CountDownLatch(1)
            broker.beginPackageReplacement(target.id, direct) { ok, _ -> if (ok) replacementReady.countDown() }
            assertTrue("old Mod worker closes before migration begins", replacementReady.await(8, java.util.concurrent.TimeUnit.SECONDS))
            closeBarrier = ModRuntimeCloseBarrierBridge.blockNextClose()
            val runner = IsolatedModMigrationRunner(appContext).apply {
                afterMigrationRequestForTesting = {
                    migrationRequested.countDown()
                    try { releaseMigrationCallback.await() }
                    catch (e: InterruptedException) { Thread.currentThread().interrupt() }
                }
            }
            migrationThread = Thread {
                try {
                    fixtureStore.install( targetV2, ModInstallDecision(targetPkg.authorFingerprint, targetGrants), runner)
                } catch (e: Throwable) {
                    migrationFailure.set(e)
                    // Match the real Settings caller: a failed replacement may
                    // resume the old worker only after Store.install has returned.
                    broker.finishPackageReplacement(target.id, false)
                } finally {
                    interruptedRestored.set(Thread.currentThread().isInterrupted)
                    migrationReturned.countDown()
                }
            }.apply { name = "mod-migration-interrupt-test"; isDaemon = true; start() }

            assertTrue("pure migration request reaches QuickJS while old version/model remain active",
                migrationRequested.await(8, java.util.concurrent.TimeUnit.SECONDS))
            migrationThread!!.interrupt()
            assertTrue("migration teardown reaches deterministic pre-Binder close barrier",
                closeBarrier!!.awaitEntered(5, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse("store must not return or trigger old-worker recovery before close confirmation",
                migrationReturned.await(200, java.util.concurrent.TimeUnit.MILLISECONDS))

            releaseMigrationCallback.countDown()
            closeBarrier!!.release()
            assertTrue("interrupted install returns after runtime slot is actually released",
                migrationReturned.await(8, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("interrupt status is restored after cleanup", interruptedRestored.get())
            assertTrue(migrationFailure.get()?.message?.contains("被中断") == true)
            assertEquals(1, fixtureStore.installed( target.id)?.versionCode)
            assertEquals(1, fixtureStore.model( target.id)?.state?.getInt("count"))
            assertTrue("failed interrupted update preserves enabled state",
                fixtureStore.installed( target.id)?.enabled == true)

            assertTrue("old worker rebinds only after migration close returns", targetResumed.await(8, java.util.concurrent.TimeUnit.SECONDS))
            val resumed = java.util.concurrent.CountDownLatch(1)
            val resumedListener = object : ModBrokerListener {
                override fun onReady(modId: String, model: ModPersistedModel) { resumed.countDown() }
                override fun onModelCommitted(modId: String, eventId: String?, model: ModPersistedModel) { }
                override fun onError(modId: String, code: String, message: String) { }
                override fun onEffectRequested(modId: String, eventId: String) { }
            }
            broker.open(target.id, direct, resumedListener)
            assertTrue("old counter worker is still usable alongside the second Mod", resumed.await(8, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("second Mod remains enabled", fixtureStore.installed( second.id)?.enabled == true)
            broker.closeObserver(target.id, resumedListener)
        } finally {
            releaseMigrationCallback.countDown()
            closeBarrier?.release()
            migrationThread?.takeIf { it.isAlive }?.interrupt()
            migrationReturned.await(8, java.util.concurrent.TimeUnit.SECONDS)
            broker.closeObserver(target.id, listener)
            broker.closeObserver(second.id, secondListener)
            broker.closeAfterDrainForTesting(second.id)?.get(8, java.util.concurrent.TimeUnit.SECONDS)
            broker.close()
            fixtureStore.uninstall( target.id)
            fixtureStore.uninstall( second.id)
        }
    }

    @Test fun scriptErrorTextCannotSuppressReplacementRecoveryAfterConfirmedClose() {
        val targetV1 = context.assets.open("platform/mods/counter-transaction-v1.zip").use { it.readBytes() }
        val targetV2 = context.assets.open("platform/mods/counter-transaction-v2-invalid-migration.zip").use { it.readBytes() }
        val targetPkg = ModPackageReader.read(ByteArrayInputStream(targetV1))
        val replacementPkg = ModPackageReader.read(ByteArrayInputStream(targetV2))
        assertEquals(targetPkg.authorFingerprint, replacementPkg.authorFingerprint)
        fixtureStore.installed( targetPkg.id)?.let { stale ->
            require(stale.authorFingerprint == targetPkg.authorFingerprint) { "fixture ID belongs to another author" }
            fixtureStore.uninstall( targetPkg.id)
        }
        val targetGrants = targetPkg.requiredCapabilities.map { it.name }.toSet()
        val target = fixtureStore.install( targetV1, ModInstallDecision(targetPkg.authorFingerprint, targetGrants))
        val broker = newBroker()
        val initialReady = java.util.concurrent.CountDownLatch(1)
        val recoveredReady = java.util.concurrent.CountDownLatch(1)
        val committedBeforeFailure = java.util.concurrent.CountDownLatch(1)
        val committedAfterRecovery = java.util.concurrent.CountDownLatch(1)
        val readyCount = java.util.concurrent.atomic.AtomicInteger()
        val direct = java.util.concurrent.Executor { it.run() }
        val listener = object : ModBrokerListener {
            override fun onReady(modId: String, model: ModPersistedModel) {
                if (readyCount.getAndIncrement() == 0) initialReady.countDown() else recoveredReady.countDown()
            }
            override fun onModelCommitted(modId: String, eventId: String?, model: ModPersistedModel) {
                if (model.state?.optInt("count") == 1) committedBeforeFailure.countDown()
                if (model.state?.optInt("count") == 2) committedAfterRecovery.countDown()
            }
            override fun onError(modId: String, code: String, message: String) { }
            override fun onEffectRequested(modId: String, eventId: String) { }
        }
        try {
            fixtureStore.setEnabled( target.id, true)
            broker.open(target.id, direct, listener)
            assertTrue("old package worker starts", initialReady.await(8, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(broker.submitEvent(target.id, "counter.increment"))
            assertTrue("old state is persisted before replacement", committedBeforeFailure.await(8, java.util.concurrent.TimeUnit.SECONDS))
            val replacementReady = java.util.concurrent.CountDownLatch(1)
            broker.beginPackageReplacement(target.id, direct) { ok, _ -> if (ok) replacementReady.countDown() }
            assertTrue("replacement waits until the old worker is fully suspended", replacementReady.await(8, java.util.concurrent.TimeUnit.SECONDS))

            // Use a valid signed same-author update package but replace only the
            // in-memory test runner input, so the actual isolated JS migrate()
            // raises the exact message that used to spoof the host lifecycle check.
            val scriptErrorRunner = ModMigrationRunner { oldPackage, newPackage, model ->
                val throwingSource = """
                    globalThis.__remrss = {
                      init() { return { count: 0 }; },
                      migrate() { throw new Error('E_CLOSE_UNCONFIRMED'); },
                      reduce({ state, event }) {
                        if (event.type === 'counter.increment') return { state: { count: state.count + 1 } };
                        return { state };
                      }
                    };
                """.trimIndent().toByteArray(Charsets.UTF_8)
                IsolatedModMigrationRunner(appContext).migrate(oldPackage, newPackage.copy(reducerSource = throwingSource), model)
            }
            val scriptFailure = assertThrows(IllegalStateException::class.java) {
                fixtureStore.install( targetV2, ModInstallDecision(targetPkg.authorFingerprint, targetGrants), scriptErrorRunner)
            }
            assertTrue("untrusted script text is retained for the error display", scriptFailure.message?.contains("E_CLOSE_UNCONFIRMED") == true)
            assertFalse("ordinary migration/script errors cannot masquerade as host close uncertainty",
                isRuntimeCloseUnconfirmed(scriptFailure))
            assertFalse("the lifecycle check rejects matching text without the typed host exception",
                isRuntimeCloseUnconfirmed(IllegalStateException("E_CLOSE_UNCONFIRMED")))
            assertEquals("normal runtime close left the old package installed", 1, fixtureStore.installed( target.id)?.versionCode)
            assertEquals(1, fixtureStore.model( target.id)?.state?.getInt("count"))
            assertTrue(fixtureStore.installed( target.id)?.enabled == true)

            if (!isRuntimeCloseUnconfirmed(scriptFailure)) broker.finishPackageReplacement(target.id, false)
            assertTrue("old worker resumes after the script error and confirmed close", recoveredReady.await(8, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("restored old reducer remains usable", broker.submitEvent(target.id, "counter.increment"))
            assertTrue("old state advances after recovery", committedAfterRecovery.await(8, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            broker.closeObserver(target.id, listener)
            broker.closeAfterDrainForTesting(target.id)?.get(8, java.util.concurrent.TimeUnit.SECONDS)
            broker.close()
            fixtureStore.uninstall( target.id)
        }
    }
}
