package top.zainzh.rearscreen.platform.runtime;

import java.nio.charset.StandardCharsets;

/** Host-JVM JNI harness for the QuickJS core; it does not simulate Android isolation. */
public final class RuntimeHarness {
    private static final String MODULE = "globalThis.__remrss={" +
            "init(ctx){return {value:0,greeting:'背屏🌟'}}," +
            "reduce({state,event}){" +
            "if(event.type==='spin'){while(true){}}" +
            "if(event.type==='microtasks'){const again=()=>Promise.resolve().then(again);again();return {state,effects:[]}}" +
            "if(event.type==='recurse'){const f=()=>f();f();}" +
            "if(event.type==='memory'){let a=[];for(let i=0;i<1000000;i++)a.push({x:'allocation'});return {state:a,effects:[]}}" +
            "if(event.type==='nan')return {state:{score:NaN},effects:[]};" +
            "if(event.type==='infinity')return {state:{score:Infinity},effects:[]};" +
            "return {state:{value:state.value+event.delta},effects:[]}" +
            "},migrate({state,config}){return {state:{value:state.value+1},config:{...config,migrated:true}}}};";

    private RuntimeHarness() {}

    public static void main(String[] args) {
        int passed = 0;
        long handle = create();
        try {
            ok(load(handle, MODULE), "load module"); passed++;
            String init = invoke(handle, "init", "{\"config\":{},\"env\":{}}");
            contains(init, "\"value\":0", "init result");
            contains(init, "背屏🌟", "Unicode round-trip"); passed++;
            String add = invoke(handle, "reduce", "{\"state\":{\"value\":0},\"event\":{\"type\":\"add\",\"delta\":3}}");
            contains(add, "\"value\":3", "reduce result"); passed++;
            String migration = invoke(handle, "migrate", "{\"state\":{\"value\":3},\"config\":{},\"fromVersion\":1,\"toVersion\":2,\"env\":{}}");
            contains(migration, "\"migrated\":true", "migration result");
            contains(migration, "\"value\":4", "migration state"); passed++;
            long spinMs = measureBudget("spin"); passed++;
            long initMs = measureInitBudget(); passed++;
            long migrationMs = measureMigrationBudget(); passed++;
            long microtaskMs = measureBudget("microtasks"); passed++;
            long recursionMs = measureFailure("recurse"); passed++;
            long memoryMs = measureFailure("memory"); passed++;
            contains(invoke(handle, "reduce", "{\"state\":{},\"event\":{\"type\":\"nan\"}}"), "E_INVALID_RESULT", "NaN rejected before stringify"); passed++;
            contains(invoke(handle, "reduce", "{\"state\":{},\"event\":{\"type\":\"infinity\"}}"), "E_INVALID_RESULT", "Infinity rejected before stringify"); passed++;
            contains(invoke(handle, "reduce", "{"), "\"ok\":false", "malformed JSON"); passed++;
            contains(invoke(handle, "reduce", "[]"), "\"ok\":false", "wrong message shape"); passed++;
            contains(invoke(handle, "wrong", "{}"), "\"ok\":false", "invalid operation"); passed++;
            contains(load(handle, "function broken( {"), "\"ok\":false", "invalid source"); passed++;
            String missing = load(handle, "globalThis.__remrss={init(){return {} }};");
            contains(missing, "\"ok\":true", "module can load before calling reducer");
            contains(invoke(handle, "reduce", "{}"), "\"ok\":false", "missing reducer export"); passed++;
            contains(invoke(handle, "migrate", "{}"), "E_MIGRATION_REQUIRED", "missing migration export"); passed++;
            contains(load(handle, "globalThis.__remrss={init(){return{}},reduce(){return{}},migrate({state,config}){return {state,config,effects:[]}}};"), "\"ok\":true", "migration-effect module loads");
            contains(invoke(handle, "migrate", "{\"state\":{},\"config\":{},\"fromVersion\":1,\"toVersion\":2}"), "E_INVALID_MIGRATION", "migration cannot return effects"); passed++;
            contains(load(handle, "globalThis.__remrss={init(){return{}},reduce(){return{}},migrate(){throw Error('migration failed')}};"), "\"ok\":true", "throwing migration loads");
            contains(invoke(handle, "migrate", "{\"state\":{},\"config\":{},\"fromVersion\":1,\"toVersion\":2}"), "E_SCRIPT", "migration exception is not hidden"); passed++;
            ok(load(handle, "globalThis.__remrss={init(){return{}},reduce(){return {state:{code:'E_CPU_BUDGET'}}}};"), "business-code module loads");
            String businessCode = invoke(handle, "reduce", "{}");
            contains(businessCode, "\"code\":\"E_CPU_BUDGET\"", "business field can equal runtime code");
            contains(businessCode, "\"__remrss_runtime_v1\":true,\"ok\":true", "business JSON is inside success envelope"); passed++;
            ok(load(handle, "globalThis.__remrss={init(){return{}},reduce(){return{}},migrate(){const r={state:{score:7},config:{}};Object.defineProperty(r,'toJSON',{value(){return {state:{score:NaN},config:{},effects:[{type:'rear.open'}]}}});return r}};"), "non-enumerable-toJSON module loads");
            String nonEnumerableToJson = invoke(handle, "migrate", "{\"state\":{},\"config\":{},\"fromVersion\":1,\"toVersion\":2}");
            contains(nonEnumerableToJson, "\"score\":7", "non-enumerable toJSON cannot replace inspected state");
            notContains(nonEnumerableToJson, "effects", "non-enumerable toJSON cannot inject effects"); passed++;
            ok(load(handle, "globalThis.proxyTrap=0;globalThis.__remrss={init(){return {proxyTrap}},reduce(){return new Proxy({state:{},effects:[]},{ownKeys(){proxyTrap++;return ['state','effects']}})},migrate(){return new Proxy({state:{},config:{}},{ownKeys(){proxyTrap++;return ['state','config']}})}};"), "proxy-result module loads");
            contains(invoke(handle, "reduce", "{}"), "E_INVALID_RESULT", "proxy reducer result rejected before traps");
            contains(invoke(handle, "migrate", "{\"state\":{},\"config\":{},\"fromVersion\":1,\"toVersion\":2}"), "E_INVALID_MIGRATION", "proxy migration result rejected before traps");
            contains(invoke(handle, "init", "{}"), "\"proxyTrap\":0", "result validation did not execute proxy traps"); passed++;
            ok(load(handle, "Object.defineProperty(Object.prototype,'then',{get(){globalThis.thenRead=true;return function(){}}});globalThis.__remrss={init(){return {safe:true}},reduce(){return {}}};"), "inherited-then module loads");
            contains(invoke(handle, "init", "{}"), "\"safe\":true", "result serializes without reading inherited then getter");
            ok(load(handle, "globalThis.__remrss={init(){return {thenRead:!!globalThis.thenRead}},reduce(){return {}}};"), "then-getter assertion module loads");
            contains(invoke(handle, "init", "{}"), "\"thenRead\":false", "inherited then getter was not executed"); passed++;
            ok(load(handle, "globalThis.__remrss={init(){return {sab:typeof SharedArrayBuffer,atomics:typeof Atomics,functionSab:({}).constructor.constructor('return typeof SharedArrayBuffer')(),functionAtomics:({}).constructor.constructor('return typeof Atomics')()}},reduce(){return Atomics.wait(new Int32Array(new SharedArrayBuffer(4)),0,0)}};"), "blocking-primitives module loads");
            String blockingGlobals = invoke(handle, "init", "{}");
            contains(blockingGlobals, "\"sab\":\"undefined\"", "SharedArrayBuffer global disabled");
            contains(blockingGlobals, "\"atomics\":\"undefined\"", "Atomics global disabled");
            contains(blockingGlobals, "\"functionSab\":\"undefined\"", "SharedArrayBuffer remains disabled via Function constructor");
            contains(blockingGlobals, "\"functionAtomics\":\"undefined\"", "Atomics remains disabled via Function constructor");
            contains(invoke(handle, "reduce", "{}"), "E_SCRIPT", "Atomics.wait cannot block without CPU use"); passed++;
            ok(load(handle, "Object.prototype.toJSON=function(){return {state:{score:NaN},config:{},effects:[]}};globalThis.__remrss={init(){return{}},reduce(){return{}},migrate(){return {state:{score:9},config:{}}}};"), "inherited-toJSON module loads");
            String inheritedToJson = invoke(handle, "migrate", "{\"state\":{},\"config\":{},\"fromVersion\":1,\"toVersion\":2}");
            contains(inheritedToJson, "\"score\":9", "inherited toJSON cannot replace inspected state");
            notContains(inheritedToJson, "effects", "inherited toJSON cannot inject effects"); passed++;
            ok(load(handle, "globalThis.__remrss={init(){return{}},reduce(){return{}},migrate(){const r={state:{score:NaN},config:{}};Object.defineProperty(r,'toJSON',{value(){return {state:{score:null},config:{},effects:[]}}});return r}};"), "NaN-toJSON module loads");
            contains(invoke(handle, "migrate", "{\"state\":{},\"config\":{},\"fromVersion\":1,\"toVersion\":2}"), "E_INVALID_RESULT", "NaN is rejected before toJSON can normalize it"); passed++;
            ok(load(handle, "globalThis.reproOriginalObject=Object;globalThis.__remrss={init(){return{}},reduce(){return{}},migrate(){const r={state:{n:1},config:{}};Object.defineProperty(globalThis,'Object',{configurable:true,get(){r.effects=[];return reproOriginalObject}});return r}};"), "mutable-Object effects reproduction loads");
            String mutableObjectEffects = invoke(handle, "migrate", "{\"state\":{},\"config\":{},\"fromVersion\":1,\"toVersion\":2}");
            contains(mutableObjectEffects, "\"n\":1", "snapshot does not execute script-replaced Object getter");
            notContains(mutableObjectEffects, "effects", "Object getter cannot inject migration effects during snapshot"); passed++;
            ok(load(handle, "Object.defineProperty(globalThis,'Object',{value:reproOriginalObject,writable:true,configurable:true});"), "restore Object after effects reproduction"); passed++;
            ok(load(handle, "globalThis.__remrss={init(){return{}},reduce(){return{}},migrate(){const r={state:{n:1},config:{}};let reads=0;Object.defineProperty(globalThis,'Object',{configurable:true,get(){if(++reads===4)r.state.n=NaN;return reproOriginalObject}});return r}};"), "mutable-Object finite-number reproduction loads");
            String mutableObjectNaN = invoke(handle, "migrate", "{\"state\":{},\"config\":{},\"fromVersion\":1,\"toVersion\":2}");
            contains(mutableObjectNaN, "\"n\":1", "snapshot emits the original finite migration value");
            notContains(mutableObjectNaN, "\"n\":null", "snapshot cannot stringify a getter-injected NaN as null"); passed++;
            ok(load(handle, "Object.defineProperty(globalThis,'Object',{value:reproOriginalObject,writable:true,configurable:true});"), "restore Object after finite-number reproduction"); passed++;
            contains(load(handle, "globalThis.__remrss={init(){return {api:[typeof std,typeof os,typeof process,typeof require]}} ,reduce(){return {}}};"), "\"ok\":true", "limited globals load");
            String api = invoke(handle, "init", "{}");
            contains(api, "undefined", "no std/os/process/require globals"); passed++;
            contains(load(handle, "globalThis.__remrss={init(){return Promise.resolve({})},reduce(){return {}}};"), "\"ok\":true", "async source loads");
            contains(invoke(handle, "init", "{}"), "E_ASYNC_NOT_ALLOWED", "async return rejected"); passed++;

            QuickJsNative.nativeDestroy(handle);
            handle = create();
            ok(load(handle, MODULE), "recreate runtime after disposal");
            String restored = invoke(handle, "reduce", "{\"state\":{\"value\":8},\"event\":{\"type\":\"add\",\"delta\":2}}");
            contains(restored, "\"value\":10", "restored model survives new worker"); passed++;

            System.out.println("PASS=" + passed + " spin_ms=" + spinMs + " init_spin_ms=" + initMs + " migrate_spin_ms=" + migrationMs + " microtask_ms=" + microtaskMs +
                    " recursion_ms=" + recursionMs + " memory_ms=" + memoryMs + " native_heap_budget_bytes=8388608");
        } finally {
            if (handle != 0) QuickJsNative.nativeDestroy(handle);
        }
    }

    private static long measureBudget(String kind) {
        long handle = create();
        try {
            ok(load(handle, MODULE), kind + " worker starts");
        long start = System.nanoTime();
        String result = invoke(handle, "reduce", "{\"state\":{},\"event\":{\"type\":\"" + kind + "\"}}");
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        contains(result, kind.equals("microtasks") ? "E_MICROTASK_LIMIT" : "E_CPU_BUDGET", kind + " bounded failure");
        if (elapsed > 1_200) throw new AssertionError(kind + " took " + elapsed + "ms");
        return elapsed;
        } finally { QuickJsNative.nativeDestroy(handle); }
    }

    private static long measureInitBudget() {
        long handle = create();
        try {
            ok(load(handle, "globalThis.__remrss={init(){while(true){}},reduce(){return {}}};"), "init-budget worker starts");
            long start = System.nanoTime();
            String result = invoke(handle, "init", "{}");
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            contains(result, "E_CPU_BUDGET", "init CPU budget failure");
            if (elapsed < 400 || elapsed > 1_200) throw new AssertionError("init CPU limit should be near 500ms, got " + elapsed + "ms");
            return elapsed;
        } finally { QuickJsNative.nativeDestroy(handle); }
    }

    private static long measureMigrationBudget() {
        long handle = create();
        try {
            ok(load(handle, "globalThis.__remrss={init(){return{}},reduce(){return{}},migrate(){while(true){}}};"), "migration-budget worker starts");
            long start = System.nanoTime();
            String result = invoke(handle, "migrate", "{\"state\":{},\"config\":{},\"fromVersion\":1,\"toVersion\":2}");
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            contains(result, "E_CPU_BUDGET", "migration CPU budget failure");
            if (elapsed < 400 || elapsed > 1_200) throw new AssertionError("migration CPU limit should be near 500ms, got " + elapsed + "ms");
            return elapsed;
        } finally { QuickJsNative.nativeDestroy(handle); }
    }

    private static long measureFailure(String kind) {
        long handle = create();
        try {
            ok(load(handle, MODULE), kind + " worker starts");
        long start = System.nanoTime();
        String result = invoke(handle, "reduce", "{\"state\":{},\"event\":{\"type\":\"" + kind + "\"}}");
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        contains(result, "\"ok\":false", kind + " is rejected");
        if (elapsed > 1_200) throw new AssertionError(kind + " took " + elapsed + "ms");
        return elapsed;
        } finally { QuickJsNative.nativeDestroy(handle); }
    }

    private static long create() {
        long handle = QuickJsNative.nativeCreate();
        if (handle == 0) throw new AssertionError("nativeCreate failed");
        return handle;
    }

    private static String load(long handle, String module) {
        return QuickJsNative.nativeLoadModule(handle, module);
    }

    private static String invoke(long handle, String method, String input) {
        return QuickJsNative.nativeInvoke(handle, method, input);
    }

    private static void ok(String actual, String label) { contains(actual, "\"ok\":true", label); }
    private static void contains(String actual, String expected, String label) {
        if (!actual.contains(expected)) throw new AssertionError(label + " expected " + expected + " got " + actual);
    }
    private static void notContains(String actual, String forbidden, String label) {
        if (actual.contains(forbidden)) throw new AssertionError(label + " unexpectedly contained " + forbidden + ": " + actual);
    }
}
