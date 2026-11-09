package top.zainzh.rearscreen.platform.mods

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** End-to-end Broker regressions: save -> production parser -> lease/effect result -> teardown. */
@RunWith(AndroidJUnit4::class)
class ModBrokerRuntimeRegressionTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.context
    private val appContext get() = instrumentation.targetContext
    private lateinit var fixtureStore: ModPackageStoreTestSession
    private lateinit var productionBefore: ModStoreTestSnapshot

    @Before fun setUp() {
        productionBefore = ModPackageStore.productionSnapshotForTest(appContext)
        fixtureStore = ModPackageStoreTestSession.create(appContext, "broker-runtime-${System.nanoTime()}")
    }

    @After fun tearDown() {
        fixtureStore.close()
        assertEquals(productionBefore, ModPackageStore.productionSnapshotForTest(appContext))
    }

    @Test fun pickerContinuationAndOpaqueDraftSurviveBundleRecreationWithoutLambda() {
        val selected = top.zainzh.rearscreen.platform.actions.RegisteredActionRef("a".repeat(48), 1)
        val beforeRecreation = android.os.Bundle()
        ModConfigPickerState.write(beforeRecreation, "config.actionRef", "platform.counter", mapOf("config.otherAction" to selected))
        val restored = ModConfigPickerState.read(android.os.Bundle(beforeRecreation))
        assertEquals("config.actionRef", restored.pendingBinding)
        assertEquals("platform.counter", restored.pendingModId)
        assertEquals(selected, restored.drafts["config.otherAction"])
    }

    @Test fun saveAcceptsOptionalScheduleLegsChainsResultsConsumesDeadlineAndClosesLease() {
        val archive = scheduleFixture()
        val pkg = ModPackageReader.read(ByteArrayInputStream(archive))
        val installed = fixtureStore.install(archive, ModInstallDecision(pkg.authorFingerprint, pkg.requiredCapabilities.map { it.name }.toSet()))
        assertTrue(fixtureStore.setEnabled(installed.id, true).enabled)
        val broker = ModEventBroker(appContext, ModEffectDispatcher { _, _, _ -> }).apply { testStoreStorage = fixtureStore.storage }
        val direct = Executor { it.run() }
        val ready = CountDownLatch(1)
        val effectRequested = AtomicInteger()
        val committedEffects = CountDownLatch(1)
        val consumedDeadlines = CountDownLatch(1)
        val chainLimited = CountDownLatch(1)
        val errors = java.util.concurrent.CopyOnWriteArrayList<String>()
        val listener = object : ModBrokerListener {
            override fun onReady(modId: String, model: ModPersistedModel) { ready.countDown() }
            override fun onModelCommitted(modId: String, eventId: String?, model: ModPersistedModel) {
                if ((model.state?.optInt("results") ?: 0) >= 3) committedEffects.countDown()
                if ((model.state?.optInt("ticks") ?: 0) >= 2) consumedDeadlines.countDown()
            }
            override fun onError(modId: String, code: String, message: String) { errors += code; if (code == "E_EFFECT_CHAIN_LIMIT") chainLimited.countDown() }
            override fun onEffectRequested(modId: String, eventId: String) { effectRequested.incrementAndGet() }
        }
        try {
            broker.open(installed.id, direct, listener, visible = true)
            assertTrue("QuickJS init", ready.await(8, TimeUnit.SECONDS))
            assertFalse("notification result is a reserved host-only event",
                broker.submitEvent(installed.id, ModEventBroker.HOST_NOTIFICATION_RESULT_EVENT, JSONObject().put("status", "DISPLAYED")))
            assertFalse("visible button input cannot impersonate the host notification callback",
                broker.submitUserEvent(installed.id, ModEventBroker.HOST_NOTIFICATION_RESULT_EVENT, JSONObject().put("status", "DISPLAYED")))
            val now = android.os.SystemClock.elapsedRealtime()
            val effects = JSONArray()
                .put(JSONObject().put("type", "schedule.visible.register").put("id", "periodic").put("intervalMs", 10_000))
                .put(JSONObject().put("type", "schedule.visible.register").put("id", "deadline").put("deadlineElapsedRealtimeMs", now - 1))
                .put(JSONObject().put("type", "schedule.visible.register").put("id", "combined").put("intervalMs", 10_000).put("deadlineElapsedRealtimeMs", now - 1))
            val save = CountDownLatch(1)
            val saved = AtomicInteger(0)
            broker.saveConfig(installed.id, JSONObject().put("effects", effects), direct) { success, _ ->
                if (success) saved.set(1)
                save.countDown()
            }
            assertTrue("config transaction returns", save.await(8, TimeUnit.SECONDS))
            assertEquals(1, saved.get())
            assertTrue("all three Effects passed through Broker parser and dispatch", awaitCount(effectRequested, 3))
            assertTrue("effectResult events return to the reducer", committedEffects.await(8, TimeUnit.SECONDS))
            assertTrue("both expired deadline legs arrive as real schedule.tick events", consumedDeadlines.await(8, TimeUnit.SECONDS))
            assertFalse("one-shot deadline leg is consumed after accepted tick", broker.scheduleHasDeadlineForTesting(installed.id, "deadline"))
            assertFalse("combined timer consumes only its deadline leg", broker.scheduleHasDeadlineForTesting(installed.id, "combined"))
            assertTrue("the periodic leg remains registered", broker.scheduleHasIntervalForTesting(installed.id, "combined"))
            assertTrue("active visible Mod owns the actual provider lease", broker.scheduleOwnerCountForTesting() >= 1)
            Thread.sleep(2_000) // let the independent per-Mod rate bucket refill before the chain-budget case
            val chainSave = CountDownLatch(1)
            broker.saveConfig(installed.id, JSONObject().put("chain", true).put("effects", JSONArray().put(
                JSONObject().put("type", "schedule.visible.register").put("id", "chain").put("intervalMs", 10_000))), direct) { ok, _ ->
                if (!ok) errors += "CONFIG_REJECTED"
                chainSave.countDown()
            }
            assertTrue("chain config transaction returns", chainSave.await(8, TimeUnit.SECONDS))
            assertTrue("one config Save and its recursively returned Effects share the 16-event cap", chainLimited.await(8, TimeUnit.SECONDS))
            val stateDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while ((fixtureStore.model(installed.id)?.state?.optInt("results") ?: 0) < 15 && System.nanoTime() < stateDeadline) Thread.sleep(20)
            assertEquals("fifteen result events commit; the next result is reported, not silently dropped", 15, fixtureStore.model(installed.id)?.state?.optInt("results"))
            broker.invalidateAndWait(installed.id)
            assertEquals("disable/revoke tears down its schedule owner", 0, broker.scheduleOwnerCountForTesting())
        } finally {
            broker.closeObserver(installed.id, listener)
            broker.close()
            fixtureStore.uninstall(installed.id)
        }
    }

    @Test fun configFailureRecoveryRestoresStableScheduleKeyCancelAndDoesNotReplayConsumedDeadline() {
        val archive = scheduleFixture()
        val pkg = ModPackageReader.read(ByteArrayInputStream(archive))
        val installed = fixtureStore.install(archive, ModInstallDecision(pkg.authorFingerprint, pkg.requiredCapabilities.map { it.name }.toSet()))
        assertTrue(fixtureStore.setEnabled(installed.id, true).enabled)
        val broker = ModEventBroker(appContext, ModEffectDispatcher { _, _, _ -> }).apply { testStoreStorage = fixtureStore.storage }
        val direct = Executor { it.run() }
        val ready = CountDownLatch(2)
        val initialReady = CountDownLatch(1)
        val effectCalls = AtomicInteger()
        val listener = object : ModBrokerListener {
            override fun onReady(modId: String, model: ModPersistedModel) { ready.countDown(); initialReady.countDown() }
            override fun onModelCommitted(modId: String, eventId: String?, model: ModPersistedModel) = Unit
            override fun onError(modId: String, code: String, message: String) = Unit
            override fun onEffectRequested(modId: String, eventId: String) { effectCalls.incrementAndGet() }
        }
        try {
            broker.open(installed.id, direct, listener, visible = true)
            assertTrue("initial worker ready", initialReady.await(8, TimeUnit.SECONDS))
            val now = android.os.SystemClock.elapsedRealtime()
            val effects = JSONArray()
                .put(JSONObject().put("type", "schedule.visible.register").put("id", "periodic").put("intervalMs", 10_000))
                .put(JSONObject().put("type", "schedule.visible.register").put("id", "deadline").put("deadlineElapsedRealtimeMs", now - 1))
                .put(JSONObject().put("type", "schedule.visible.register").put("id", "combined").put("intervalMs", 10_000).put("deadlineElapsedRealtimeMs", now - 1))
                .put(JSONObject().put("type", "schedule.visible.register").put("id", "stable-key").put("intervalMs", 1_000))
            val save = CountDownLatch(1)
            broker.saveConfig(installed.id, JSONObject().put("effects", effects), direct) { ok, _ -> if (ok) save.countDown() }
            assertTrue("schedule config saved", save.await(8, TimeUnit.SECONDS))
            assertTrue("initial Effects dispatch", awaitCount(effectCalls, 4))
            assertTrue("deadline-only and combined deadline are delivered", awaitState(installed.id) { it.optInt("deadlineTicks") >= 2 })

            val failedSave = CountDownLatch(1)
            broker.saveConfig(installed.id, JSONObject().put("crash", true), direct) { ok, _ -> if (!ok) failedSave.countDown() }
            assertTrue("failed config is returned after worker recovery", failedSave.await(10, TimeUnit.SECONDS))
            assertTrue("worker has rebound with its visible lease", ready.await(10, TimeUnit.SECONDS))
            val stableTick = awaitState(installed.id) { it.optString("lastKey") == "stable-key" && it.optInt("periodicTicks") >= 1 }
            assertTrue("recovered lease sends the stable author key, not a generated Effect UUID", stableTick)
            assertEquals(2, fixtureStore.model(installed.id)?.state?.optInt("deadlineTicks"))

            val cancelResultTarget = (fixtureStore.model(installed.id)?.state?.optInt("results") ?: 0) + 1
            assertTrue("cancel event is queued", broker.submitEvent(installed.id, "cancel.schedule", JSONObject().put("key", "stable-key")))
            assertTrue("cancel Effect dispatched", awaitCount(effectCalls, 5))
            assertTrue("cancel's causal result is committed", awaitState(installed.id) { it.optInt("results") >= cancelResultTarget })
            val afterCancel = fixtureStore.model(installed.id)?.state?.optInt("periodicTicks") ?: -1
            Thread.sleep(1_300)
            assertEquals("cancel by stable key stops the restored interval", afterCancel, fixtureStore.model(installed.id)?.state?.optInt("periodicTicks"))
            assertEquals("consumed one-shot deadlines are not replayed after worker recovery", 2, fixtureStore.model(installed.id)?.state?.optInt("deadlineTicks"))
            broker.invalidateAndWait(installed.id)
            assertEquals(0, broker.scheduleOwnerCountForTesting())
        } finally {
            broker.closeObserver(installed.id, listener)
            broker.close()
            fixtureStore.uninstall(installed.id)
        }
    }

    @Test fun configSaveAndReturnedEffectsShareCumulativeWorkerCpuBudget() {
        val archive = scheduleFixture()
        val pkg = ModPackageReader.read(ByteArrayInputStream(archive))
        val installed = fixtureStore.install(archive, ModInstallDecision(pkg.authorFingerprint, pkg.requiredCapabilities.map { it.name }.toSet()))
        assertTrue(fixtureStore.setEnabled(installed.id, true).enabled)
        val broker = ModEventBroker(appContext, ModEffectDispatcher { _, _, _ -> }).apply { testStoreStorage = fixtureStore.storage }
        val direct = Executor { it.run() }
        val ready = CountDownLatch(1)
        val limited = CountDownLatch(1)
        val effectCalls = AtomicInteger()
        val listener = object : ModBrokerListener {
            override fun onReady(modId: String, model: ModPersistedModel) { ready.countDown() }
            override fun onModelCommitted(modId: String, eventId: String?, model: ModPersistedModel) = Unit
            override fun onError(modId: String, code: String, message: String) { if (code == "E_EFFECT_CHAIN_CPU_LIMIT") limited.countDown() }
            override fun onEffectRequested(modId: String, eventId: String) { effectCalls.incrementAndGet() }
        }
        try {
            broker.open(installed.id, direct, listener, visible = true)
            assertTrue(ready.await(8, TimeUnit.SECONDS))
            val done = CountDownLatch(1)
            var saved = false
            val effects = JSONArray().put(JSONObject().put("type", "schedule.visible.register").put("id", "cpu-chain").put("intervalMs", 10_000))
            broker.saveConfig(installed.id, JSONObject().put("cpuChain", true).put("effects", effects), direct) { ok, _ -> saved = ok; done.countDown() }
            assertTrue("config Save result returns even when subsequent chain exhausts its CPU budget", done.await(8, TimeUnit.SECONDS))
            assertTrue("config transaction is committed before Effect CPU limit", saved)
            assertTrue("Config reducer and returned Effect-result reducers accumulate >100ms CPU", limited.await(8, TimeUnit.SECONDS))
            assertTrue("the committed config Effect and first result Effect dispatched", awaitCount(effectCalls, 2))
            assertTrue("state committed before CPU cap", awaitState(installed.id) { it.optInt("results") == 2 })
            Thread.sleep(300)
            assertEquals("exhausted chain does not dispatch a third Effect", 2, fixtureStore.model(installed.id)?.state?.optInt("results"))
        } finally {
            broker.closeObserver(installed.id, listener)
            broker.close()
            fixtureStore.uninstall(installed.id)
        }
    }

    private fun awaitCount(value: AtomicInteger, expected: Int): Boolean {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (System.nanoTime() < until) {
            if (value.get() >= expected) return true
            Thread.sleep(20)
        }
        return value.get() >= expected
    }

    private fun awaitState(modId: String, condition: (JSONObject) -> Boolean): Boolean {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < until) {
            val state = fixtureStore.model(modId)?.state
            if (state != null && condition(state)) return true
            Thread.sleep(20)
        }
        return false
    }

    private fun scheduleFixture(): ByteArray {
        val raw = context.assets.open("platform/mods/generic-counter.zip").use { it.readBytes() }
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(raw)).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; entries[entry.name] = zip.readBytes() }
        }
        val script = """globalThis.__remrss = {
          init() { return { results: 0, ticks: 0, lastKey: '', deadlineTicks: 0, periodicTicks: 0 }; },
          reduce({ state, event, config }) {
            if (event.type === 'host.configChanged' && config.crash) throw new Error('fixture crash for config recovery');
            if (event.type === 'host.configChanged') {
              if (config.cpuChain) { const stop = Date.now() + 45; while (Date.now() < stop) {} }
              return { state: config.chain ? { ...state, results: 0 } : state, effects: config.effects };
            }
            if (event.type === 'host.schedule.tick') {
              const key = event.payload.id;
              return { state: { ...state, ticks: state.ticks + 1, lastKey: key,
                deadlineTicks: state.deadlineTicks + (key === 'deadline' || key === 'combined' ? 1 : 0),
                periodicTicks: state.periodicTicks + (key === 'stable-key' ? 1 : 0) } };
            }
            if (event.type === 'cancel.schedule') return { state, effects: [{ type: 'schedule.visible.cancel', id: event.payload.key }] };
            if (event.type === 'host.effectResult') {
              if (config.cpuChain) { const stop = Date.now() + 45; while (Date.now() < stop) {} }
              const results = state.results + 1;
              return (config.chain || config.cpuChain) && results < 20
                ? { state: { ...state, results }, effects: [{ type: 'schedule.visible.register', id: 'chain', intervalMs: 10_000 }] }
                : { state: { ...state, results } };
            }
            return { state };
          }
        };""".toByteArray()
        entries["main.js"] = script
        entries["rear.ui.json"] = """{"scene":"rear","root":{"type":"layout.column","children":[{"type":"ui.text","value":"Broker runtime regression"}]}}""".toByteArray()
        entries["settings.ui.json"] = """{"scene":"settings","root":{"type":"layout.column","children":[{"type":"ui.text","value":"Broker runtime regression"}]}}""".toByteArray()
        val manifest = JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8))
        manifest.put("id", "platform.broker-runtime-regression")
        val caps = manifest.getJSONObject("capabilities").getJSONArray("required")
        caps.put(JSONObject().put("name", "schedule.visible").put("minimumVersion", 1))
        val scheduleEffectSchema = JSONObject().put("type", "object").put("additionalProperties", false)
            .put("required", JSONArray().put("type").put("id"))
            .put("properties", JSONObject()
                .put("type", JSONObject().put("type", "string").put("maxLength", 64)
                    .put("enum", JSONArray().put("schedule.visible.register").put("schedule.visible.cancel")))
                .put("id", JSONObject().put("type", "string").put("maxLength", 64))
                .put("intervalMs", JSONObject().put("type", "integer").put("minimum", 0).put("maximum", 1_000_000_000_000L))
                .put("deadlineElapsedRealtimeMs", JSONObject().put("type", "integer").put("minimum", 0).put("maximum", 1_000_000_000_000L)))
        manifest.getJSONObject("data").getJSONObject("configSchema").getJSONObject("properties")
            .put("effects", JSONObject().put("type", "array").put("maxItems", 8).put("items", scheduleEffectSchema))
            .put("chain", JSONObject().put("type", "boolean"))
            .put("crash", JSONObject().put("type", "boolean"))
            .put("cpuChain", JSONObject().put("type", "boolean"))
        manifest.getJSONObject("data").getJSONObject("stateSchema")
            .put("properties", JSONObject()
                .put("results", JSONObject().put("type", "integer").put("minimum", 0).put("maximum", 1_000_000_000))
                .put("ticks", JSONObject().put("type", "integer").put("minimum", 0).put("maximum", 1_000_000_000))
                .put("lastKey", JSONObject().put("type", "string").put("maxLength", 64))
                .put("deadlineTicks", JSONObject().put("type", "integer").put("minimum", 0).put("maximum", 1_000_000_000))
                .put("periodicTicks", JSONObject().put("type", "integer").put("minimum", 0).put("maximum", 1_000_000_000)))
            .put("required", JSONArray().put("results").put("ticks").put("lastKey").put("deadlineTicks").put("periodicTicks"))
        val payloads = manifest.getJSONArray("payloads")
        for (index in 0 until payloads.length()) {
            val payload = payloads.getJSONObject(index)
            val bytes = entries.getValue(payload.getString("path"))
            payload.put("bytes", bytes.size).put("sha256", java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') })
        }
        val pair = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(pair.public.encoded)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        manifest.getJSONObject("author").put("keyFingerprint", fingerprint)
        val manifestBytes = manifest.toString().toByteArray(Charsets.UTF_8)
        entries["manifest.json"] = manifestBytes
        entries["manifest.sig"] = java.security.Signature.getInstance("SHA256withRSA").run { initSign(pair.private); update(manifestBytes); sign() }
        entries["author.pub"] = ("-----BEGIN PUBLIC KEY-----\n" + android.util.Base64.encodeToString(pair.public.encoded, android.util.Base64.NO_WRAP) + "\n-----END PUBLIC KEY-----\n").toByteArray()
        return ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) -> zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        } }.toByteArray()
    }
}
