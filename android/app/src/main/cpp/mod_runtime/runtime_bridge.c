#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <math.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/syscall.h>
#include <pthread.h>
#include <stdatomic.h>
#include <signal.h>

#include "quickjs.h"

#define MOD_SOURCE_LIMIT (512U * 1024U)
#define MOD_MESSAGE_LIMIT (256U * 1024U)
#define MOD_RESULT_LIMIT (256U * 1024U)
#define MOD_JS_HEAP_LIMIT (8U * 1024U * 1024U)
#define MOD_JS_STACK_LIMIT (512U * 1024U)
#define MOD_JOB_LIMIT 64
#define MOD_JSON_DEPTH_LIMIT 32
#define MOD_JSON_NODE_LIMIT 16384

typedef struct {
    JSRuntime *runtime;
    JSContext *context;
    JSValue object_prototype;
    JSValue array_prototype;
    int64_t cpu_deadline_ns;
    int64_t cpu_start_ns;
    int schedstat_fd;
    clockid_t target_thread_clock;
    int target_thread_clock_valid;
    int monitor_created;
    pthread_t monitor_thread;
    atomic_int stop_monitor;
    atomic_int capability_flags;
    atomic_llong last_cpu_elapsed_ns;
    int budget_reason; /* 1=CPU, 3=microtask count */
} ModRuntime;

static int64_t clock_ns(clockid_t clock_id) {
    struct timespec ts;
    if (clock_gettime(clock_id, &ts) != 0) return 0;
    return (int64_t) ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static void *budget_watchdog(void *opaque) {
    ModRuntime *mod = (ModRuntime *) opaque;
    const int64_t grace_ns = 250LL * 1000LL * 1000LL;
    const struct timespec sample_delay = {0, 10LL * 1000LL * 1000LL};
    while (!atomic_load_explicit(&mod->stop_monitor, memory_order_relaxed)) {
        if (mod->target_thread_clock_valid) {
            struct timespec cpu;
            if (clock_gettime(mod->target_thread_clock, &cpu) == 0) {
                int64_t cpu_ns = (int64_t) cpu.tv_sec * 1000000000LL + cpu.tv_nsec;
                if (cpu_ns > mod->cpu_deadline_ns + grace_ns) {
                    kill(getpid(), SIGKILL);
                    return NULL;
                }
            }
        }
        nanosleep(&sample_delay, NULL);
    }
    return NULL;
}

static void stop_budget(ModRuntime *mod) {
    if (mod->monitor_created) {
        int64_t cpu_now = clock_ns(CLOCK_THREAD_CPUTIME_ID);
        if (cpu_now >= mod->cpu_start_ns) {
            atomic_store_explicit(&mod->last_cpu_elapsed_ns, cpu_now - mod->cpu_start_ns, memory_order_relaxed);
        }
    }
    atomic_store_explicit(&mod->stop_monitor, 1, memory_order_relaxed);
    if (mod->monitor_created) {
        pthread_join(mod->monitor_thread, NULL);
        mod->monitor_created = 0;
    }
    if (mod->schedstat_fd >= 0) {
        close(mod->schedstat_fd);
        mod->schedstat_fd = -1;
    }
}

static void start_budget(ModRuntime *mod, int cpu_budget_ms) {
    stop_budget(mod);
    atomic_store_explicit(&mod->last_cpu_elapsed_ns, 0, memory_order_relaxed);
    int64_t cpu_now = clock_ns(CLOCK_THREAD_CPUTIME_ID);
    mod->cpu_start_ns = cpu_now;
    mod->cpu_deadline_ns = cpu_now + (int64_t) cpu_budget_ms * 1000000LL;
    if (mod->schedstat_fd >= 0) close(mod->schedstat_fd);
    mod->schedstat_fd = -1;
#if defined(SYS_gettid) && (defined(__ANDROID__) || defined(__linux__))
    char path[96];
    long tid = syscall(SYS_gettid);
    if (tid > 0) {
        snprintf(path, sizeof(path), "/proc/self/task/%ld/schedstat", tid);
        mod->schedstat_fd = open(path, O_RDONLY | O_CLOEXEC);
    }
#endif
    mod->budget_reason = 0;
#if defined(__ANDROID__) || defined(__linux__)
    mod->target_thread_clock_valid = pthread_getcpuclockid(pthread_self(), &mod->target_thread_clock) == 0;
#else
    mod->target_thread_clock_valid = 0;
#endif
    atomic_store_explicit(&mod->stop_monitor, 0, memory_order_relaxed);
    mod->monitor_created = pthread_create(&mod->monitor_thread, NULL, budget_watchdog, mod) == 0;
    int flags = (mod->target_thread_clock_valid ? 1 : 0) |
                (mod->schedstat_fd >= 0 ? 2 : 0) |
                (mod->monitor_created ? 4 : 0);
    atomic_store_explicit(&mod->capability_flags, flags, memory_order_relaxed);
}

static int budget_capabilities_available(ModRuntime *mod) {
#if defined(__ANDROID__)
    return (atomic_load_explicit(&mod->capability_flags, memory_order_relaxed) & 5) == 5;
#else
    /* Host JNI harness validates QuickJS logic, not the Android watchdog contract. */
    (void) mod;
    return 1;
#endif
}

static int interrupt_handler(JSRuntime *runtime, void *opaque) {
    (void) runtime;
    ModRuntime *mod = (ModRuntime *) opaque;
    if (clock_ns(CLOCK_THREAD_CPUTIME_ID) > mod->cpu_deadline_ns) {
        mod->budget_reason = 1;
        return 1;
    }
    return 0;
}

static char *copy_jstring(JNIEnv *env, jstring value, size_t limit, const char *name, size_t *out_len) {
    (void) name;
    if (value == NULL) return NULL;
    jsize chars_len = (*env)->GetStringLength(env, value);
    const jchar *chars = (*env)->GetStringChars(env, value, NULL);
    if (chars == NULL) return NULL;
    size_t capacity = (size_t) chars_len * 3U + 1U;
    char *copy = (char *) malloc(capacity);
    if (copy == NULL) { (*env)->ReleaseStringChars(env, value, chars); return NULL; }
    size_t out = 0;
    for (jsize i = 0; i < chars_len; i++) {
        uint32_t cp = chars[i];
        if (cp >= 0xd800 && cp <= 0xdbff) {
            if (i + 1 >= chars_len || chars[i + 1] < 0xdc00 || chars[i + 1] > 0xdfff) goto invalid;
            cp = 0x10000U + (((cp - 0xd800U) << 10U) | (chars[++i] - 0xdc00U));
        } else if (cp >= 0xdc00 && cp <= 0xdfff) {
            goto invalid;
        }
        if (cp < 0x80) copy[out++] = (char) cp;
        else if (cp < 0x800) {
            copy[out++] = (char) (0xc0U | (cp >> 6U));
            copy[out++] = (char) (0x80U | (cp & 0x3fU));
        } else if (cp < 0x10000) {
            copy[out++] = (char) (0xe0U | (cp >> 12U));
            copy[out++] = (char) (0x80U | ((cp >> 6U) & 0x3fU));
            copy[out++] = (char) (0x80U | (cp & 0x3fU));
        } else {
            copy[out++] = (char) (0xf0U | (cp >> 18U));
            copy[out++] = (char) (0x80U | ((cp >> 12U) & 0x3fU));
            copy[out++] = (char) (0x80U | ((cp >> 6U) & 0x3fU));
            copy[out++] = (char) (0x80U | (cp & 0x3fU));
        }
        if (out > limit) goto invalid;
    }
    copy[out] = '\0';
    (*env)->ReleaseStringChars(env, value, chars);
    if (out_len != NULL) *out_len = out;
    return copy;
invalid:
    free(copy);
    (*env)->ReleaseStringChars(env, value, chars);
    return NULL;
}

static jstring new_jstring_utf8(JNIEnv *env, const char *bytes, size_t len) {
    jchar *chars = (jchar *) malloc((len + 1U) * sizeof(jchar));
    if (chars == NULL) return NULL;
    size_t in = 0, out = 0;
    while (in < len) {
        uint8_t a = (uint8_t) bytes[in++];
        uint32_t cp;
        if (a < 0x80) cp = a;
        else if (a >= 0xc2 && a <= 0xdf && in < len) {
            uint8_t b = (uint8_t) bytes[in++]; if ((b & 0xc0) != 0x80) goto invalid_utf8;
            cp = ((uint32_t) (a & 0x1f) << 6U) | (b & 0x3fU);
        } else if (a >= 0xe0 && a <= 0xef && in + 1 < len) {
            uint8_t b = (uint8_t) bytes[in++], c = (uint8_t) bytes[in++];
            if ((b & 0xc0) != 0x80 || (c & 0xc0) != 0x80 || (a == 0xe0 && b < 0xa0) || (a == 0xed && b >= 0xa0)) goto invalid_utf8;
            cp = ((uint32_t) (a & 0x0f) << 12U) | ((uint32_t) (b & 0x3fU) << 6U) | (c & 0x3fU);
        } else if (a >= 0xf0 && a <= 0xf4 && in + 2 < len) {
            uint8_t b = (uint8_t) bytes[in++], c = (uint8_t) bytes[in++], d = (uint8_t) bytes[in++];
            if ((b & 0xc0) != 0x80 || (c & 0xc0) != 0x80 || (d & 0xc0) != 0x80 ||
                (a == 0xf0 && b < 0x90) || (a == 0xf4 && b >= 0x90)) goto invalid_utf8;
            cp = ((uint32_t) (a & 7U) << 18U) | ((uint32_t) (b & 0x3fU) << 12U) | ((uint32_t) (c & 0x3fU) << 6U) | (d & 0x3fU);
        } else goto invalid_utf8;
        if (cp <= 0xffff) chars[out++] = (jchar) cp;
        else {
            cp -= 0x10000U;
            chars[out++] = (jchar) (0xd800U + (cp >> 10U));
            chars[out++] = (jchar) (0xdc00U + (cp & 0x3ffU));
        }
    }
    jstring result = (*env)->NewString(env, chars, (jsize) out);
    free(chars);
    return result;
invalid_utf8:
    free(chars);
    return NULL;
}

static jstring java_error(JNIEnv *env, const char *code, const char *message) {
    char buffer[768];
    int prefix = snprintf(buffer, sizeof(buffer), "{\"__remrss_runtime_v1\":true,\"ok\":false,\"code\":\"%s\",\"message\":\"", code);
    size_t n = prefix > 0 ? (size_t) prefix : 0;
    const unsigned char *src = (const unsigned char *) (message == NULL ? "runtime error" : message);
    static const char hex[] = "0123456789abcdef";
    for (; *src != 0 && n + 7 < sizeof(buffer); src++) {
        if (*src == '"' || *src == '\\') { buffer[n++] = '\\'; buffer[n++] = (char) *src; }
        else if (*src < 0x20) {
            buffer[n++] = '\\'; buffer[n++] = 'u'; buffer[n++] = '0'; buffer[n++] = '0';
            buffer[n++] = hex[*src >> 4]; buffer[n++] = hex[*src & 0x0f];
        } else buffer[n++] = (char) *src;
    }
    const char suffix[] = "\"}";
    memcpy(buffer + n, suffix, sizeof(suffix));
    return (*env)->NewStringUTF(env, buffer);
}

static jstring java_success(JNIEnv *env, const char *payload, size_t len) {
    const char prefix[] = "{\"__remrss_runtime_v1\":true,\"ok\":true,\"payload\":";
    const char suffix[] = "}";
    size_t total = sizeof(prefix) - 1U + len + sizeof(suffix);
    char *buffer = (char *) malloc(total);
    if (buffer == NULL) return java_error(env, "E_OUT_OF_MEMORY", "could not create runtime result envelope");
    size_t offset = sizeof(prefix) - 1U;
    memcpy(buffer, prefix, offset);
    memcpy(buffer + offset, payload, len); offset += len;
    buffer[offset++] = '}';
    jstring result = new_jstring_utf8(env, buffer, offset);
    free(buffer);
    return result;
}

static jstring budget_error(JNIEnv *env, ModRuntime *mod) {
    switch (mod->budget_reason) {
        case 1: return java_error(env, "E_CPU_BUDGET", "script exceeded CPU budget");
        case 3: return java_error(env, "E_MICROTASK_LIMIT", "script exceeded microtask limit");
        default: return java_error(env, "E_BUDGET_EXCEEDED", "script exceeded runtime budget");
    }
}

static jstring exception_to_result(JNIEnv *env, ModRuntime *mod, JSValue value) {
    const char *message = JS_ToCString(mod->context, value);
    jstring result;
    if (mod->budget_reason != 0) {
        result = budget_error(env, mod);
    } else {
        result = java_error(env, "E_SCRIPT", message == NULL ? "script failed" : message);
    }
    if (message != NULL) JS_FreeCString(mod->context, message);
    JS_FreeValue(mod->context, value);
    return result;
}

static JSValue parse_json(JSContext *ctx, const char *json, size_t len) {
    return JS_ParseJSON(ctx, json, len, "mod-message.json");
}

static int json_shape_ok(const char *json, size_t len) {
    int depth = 0, in_string = 0, escaped = 0;
    size_t tokens = 0;
    for (size_t i = 0; i < len; i++) {
        unsigned char c = (unsigned char) json[i];
        if (in_string) {
            if (escaped) escaped = 0;
            else if (c == '\\') escaped = 1;
            else if (c == '"') in_string = 0;
            continue;
        }
        if (c == '"') in_string = 1;
        else if (c == '{' || c == '[') {
            if (++depth > MOD_JSON_DEPTH_LIMIT) return 0;
            if (++tokens > MOD_JSON_NODE_LIMIT) return 0;
        } else if (c == '}' || c == ']') {
            if (--depth < 0) return 0;
        } else if (c == ',' || c == ':') {
            if (++tokens > MOD_JSON_NODE_LIMIT) return 0;
        }
    }
    return depth == 0 && !in_string && tokens <= MOD_JSON_NODE_LIMIT;
}

static int descriptor_is_data(JSPropertyDescriptor *desc) {
    return JS_IsUndefined(desc->getter) && JS_IsUndefined(desc->setter);
}

static int plain_prototype(ModRuntime *mod, JSValueConst value, int array) {
    JSContext *ctx = mod->context;
    JSValue actual = JS_GetPrototype(ctx, value);
    if (JS_IsException(actual)) return 0;
    if (JS_IsNull(actual)) { JS_FreeValue(ctx, actual); return !array; }
    JSValueConst expected = array ? mod->array_prototype : mod->object_prototype;
    int equal = JS_StrictEq(ctx, actual, expected);
    JS_FreeValue(ctx, actual);
    return equal;
}

static int verify_json_value(ModRuntime *mod, JSValueConst value, int depth, uint32_t *nodes) {
    JSContext *ctx = mod->context;
    if (++(*nodes) > MOD_JSON_NODE_LIMIT || depth > MOD_JSON_DEPTH_LIMIT) return 0;
    if (JS_IsNull(value) || JS_IsBool(value) || JS_IsString(value)) return 1;
    if (JS_IsNumber(value)) {
        double number;
        return JS_ToFloat64(ctx, &number, value) == 0 && isfinite(number);
    }
    if (!JS_IsObject(value) || JS_IsProxy(ctx, value) || JS_IsFunction(ctx, value) || JS_IsSymbol(value) || JS_IsBigInt(ctx, value)) return 0;

    int array = JS_IsArray(ctx, value);
    if (!plain_prototype(mod, value, array)) return 0;
    if (array) {
        JSValue length_value = JS_GetPropertyStr(ctx, value, "length");
        uint32_t length = 0;
        int valid = !JS_IsException(length_value) && JS_ToUint32(ctx, &length, length_value) == 0 && length <= MOD_JSON_NODE_LIMIT;
        JS_FreeValue(ctx, length_value);
        if (!valid) return 0;
        for (uint32_t i = 0; i < length; i++) {
            JSAtom atom = JS_NewAtomUInt32(ctx, i);
            JSPropertyDescriptor desc;
            int present = JS_GetOwnProperty(ctx, &desc, value, atom);
            JS_FreeAtom(ctx, atom);
            if (present <= 0) return 0; /* sparse arrays are not JSON state */
            int data = descriptor_is_data(&desc);
            int child_ok = data && verify_json_value(mod, desc.value, depth + 1, nodes);
            JS_FreeValue(ctx, desc.value);
            JS_FreeValue(ctx, desc.getter);
            JS_FreeValue(ctx, desc.setter);
            if (!child_ok) return 0;
        }
        return 1;
    }

    JSPropertyEnum *properties = NULL;
    uint32_t count = 0;
    if (JS_GetOwnPropertyNames(ctx, &properties, &count, value, JS_GPN_STRING_MASK | JS_GPN_ENUM_ONLY) < 0) return 0;
    if (count > MOD_JSON_NODE_LIMIT) { JS_FreePropertyEnum(ctx, properties, count); return 0; }
    int valid = 1;
    for (uint32_t i = 0; i < count && valid; i++) {
        JSPropertyDescriptor desc;
        int present = JS_GetOwnProperty(ctx, &desc, value, properties[i].atom);
        if (present <= 0) { valid = 0; break; }
        valid = descriptor_is_data(&desc) && verify_json_value(mod, desc.value, depth + 1, nodes);
        JS_FreeValue(ctx, desc.value);
        JS_FreeValue(ctx, desc.getter);
        JS_FreeValue(ctx, desc.setter);
    }
    JS_FreePropertyEnum(ctx, properties, count);
    return valid;
}

/* Copy only inspected own enumerable data properties into null-prototype containers.
 * JSON.stringify on this snapshot cannot call the Mod's toJSON/accessors/proxy traps. */
static JSValue snapshot_json_value(ModRuntime *mod, JSValueConst value, int depth, uint32_t *nodes) {
    JSContext *ctx = mod->context;
    if (++(*nodes) > MOD_JSON_NODE_LIMIT || depth > MOD_JSON_DEPTH_LIMIT)
        return JS_ThrowTypeError(ctx, "JSON result exceeds limits");
    if (JS_IsNumber(value)) {
        double number;
        if (JS_ToFloat64(ctx, &number, value) < 0 || !isfinite(number))
            return JS_ThrowTypeError(ctx, "JSON result contains a non-finite number");
        return JS_DupValue(ctx, value);
    }
    if (JS_IsNull(value) || JS_IsBool(value) || JS_IsString(value))
        return JS_DupValue(ctx, value);
    if (!JS_IsObject(value) || JS_IsProxy(ctx, value) || JS_IsFunction(ctx, value) || JS_IsSymbol(value) || JS_IsBigInt(ctx, value))
        return JS_ThrowTypeError(ctx, "result contains a non-JSON value");

    int array = JS_IsArray(ctx, value);
    if (!plain_prototype(mod, value, array))
        return JS_ThrowTypeError(ctx, "result must use plain JSON objects and arrays");
    JSValue snapshot = array ? JS_NewArray(ctx) : JS_NewObjectProto(ctx, JS_NULL);
    if (JS_IsException(snapshot)) return snapshot;
    if (array && JS_SetPrototype(ctx, snapshot, JS_NULL) < 0) {
        JS_FreeValue(ctx, snapshot);
        return JS_EXCEPTION;
    }
    if (array) {
        JSValue length_value = JS_GetPropertyStr(ctx, value, "length");
        uint32_t length = 0;
        int valid = !JS_IsException(length_value) && JS_ToUint32(ctx, &length, length_value) == 0 && length <= MOD_JSON_NODE_LIMIT;
        JS_FreeValue(ctx, length_value);
        if (!valid) { JS_FreeValue(ctx, snapshot); return JS_ThrowTypeError(ctx, "invalid JSON array"); }
        for (uint32_t i = 0; i < length; i++) {
            JSAtom atom = JS_NewAtomUInt32(ctx, i);
            JSPropertyDescriptor desc;
            int present = JS_GetOwnProperty(ctx, &desc, value, atom);
            if (present <= 0) { JS_FreeAtom(ctx, atom); JS_FreeValue(ctx, snapshot); return JS_ThrowTypeError(ctx, "sparse arrays are not JSON"); }
            int data = descriptor_is_data(&desc);
            JSValue child = data ? snapshot_json_value(mod, desc.value, depth + 1, nodes) : JS_ThrowTypeError(ctx, "accessors are not JSON");
            JS_FreeValue(ctx, desc.value); JS_FreeValue(ctx, desc.getter); JS_FreeValue(ctx, desc.setter);
            if (JS_IsException(child) || JS_SetProperty(ctx, snapshot, atom, child) < 0) {
                JS_FreeAtom(ctx, atom); JS_FreeValue(ctx, snapshot); return JS_EXCEPTION;
            }
            JS_FreeAtom(ctx, atom);
        }
        return snapshot;
    }

    JSPropertyEnum *properties = NULL;
    uint32_t count = 0;
    if (JS_GetOwnPropertyNames(ctx, &properties, &count, value, JS_GPN_STRING_MASK | JS_GPN_ENUM_ONLY) < 0) {
        JS_FreeValue(ctx, snapshot); return JS_EXCEPTION;
    }
    if (count > MOD_JSON_NODE_LIMIT) {
        JS_FreePropertyEnum(ctx, properties, count); JS_FreeValue(ctx, snapshot);
        return JS_ThrowTypeError(ctx, "object exceeds JSON property limit");
    }
    for (uint32_t i = 0; i < count; i++) {
        JSPropertyDescriptor desc;
        int present = JS_GetOwnProperty(ctx, &desc, value, properties[i].atom);
        if (present <= 0) { JS_FreePropertyEnum(ctx, properties, count); JS_FreeValue(ctx, snapshot); return JS_ThrowTypeError(ctx, "invalid JSON property"); }
        int data = descriptor_is_data(&desc);
        JSValue child = data ? snapshot_json_value(mod, desc.value, depth + 1, nodes) : JS_ThrowTypeError(ctx, "accessors are not JSON");
        JS_FreeValue(ctx, desc.value); JS_FreeValue(ctx, desc.getter); JS_FreeValue(ctx, desc.setter);
        if (JS_IsException(child) || JS_SetProperty(ctx, snapshot, properties[i].atom, child) < 0) {
            JS_FreePropertyEnum(ctx, properties, count); JS_FreeValue(ctx, snapshot); return JS_EXCEPTION;
        }
    }
    JS_FreePropertyEnum(ctx, properties, count);
    return snapshot;
}

static JSValue call_export(ModRuntime *mod, const char *name, JSValueConst argument, int *missing_export) {
    *missing_export = 0;
    JSValue global = JS_GetGlobalObject(mod->context);
    JSValue module = JS_GetPropertyStr(mod->context, global, "__remrss");
    JS_FreeValue(mod->context, global);
    if (JS_IsException(module)) return module;
    JSValue fn = JS_GetPropertyStr(mod->context, module, name);
    JS_FreeValue(mod->context, module);
    if (JS_IsException(fn)) return fn;
    if (!JS_IsFunction(mod->context, fn)) {
        JS_FreeValue(mod->context, fn);
        if (strcmp(name, "migrate") == 0) {
            *missing_export = 1;
            return JS_UNDEFINED;
        }
        return JS_ThrowTypeError(mod->context, "module export '%s' must be a function", name);
    }
    JSValue result = JS_Call(mod->context, fn, JS_UNDEFINED, 1, &argument);
    JS_FreeValue(mod->context, fn);
    return result;
}

static int has_own_data_property(JSContext *ctx, JSValueConst object, const char *name) {
    JSAtom atom = JS_NewAtom(ctx, name);
    JSPropertyDescriptor desc;
    int present = JS_GetOwnProperty(ctx, &desc, object, atom);
    JS_FreeAtom(ctx, atom);
    if (present > 0) {
        int data = descriptor_is_data(&desc);
        JS_FreeValue(ctx, desc.value);
        JS_FreeValue(ctx, desc.getter);
        JS_FreeValue(ctx, desc.setter);
        return data ? 1 : -1;
    }
    return present;
}

static int valid_migration_result(JSContext *ctx, JSValueConst result) {
    if (!JS_IsObject(result) || JS_IsProxy(ctx, result) || JS_IsArray(ctx, result)) return 0;
    int state = has_own_data_property(ctx, result, "state");
    int config = has_own_data_property(ctx, result, "config");
    int effects = has_own_data_property(ctx, result, "effects");
    return state == 1 && config == 1 && effects == 0;
}

static int drain_jobs(ModRuntime *mod) {
    int jobs = 0;
    while (JS_IsJobPending(mod->runtime)) {
        if (jobs >= MOD_JOB_LIMIT) {
            mod->budget_reason = 3;
            return 0;
        }
        JSContext *job_ctx = NULL;
        int pending = JS_ExecutePendingJob(mod->runtime, &job_ctx);
        if (pending < 0) return 0;
        if (pending == 0) return 1;
        jobs++;
        if (interrupt_handler(mod->runtime, mod)) return 0;
    }
    return 1;
}

static jstring stringify_result(JNIEnv *env, ModRuntime *mod, JSValue value, int is_migration) {
    if (JS_IsException(value)) return exception_to_result(env, mod, JS_GetException(mod->context));
    if (JS_IsPromise(mod->context, value)) {
        JS_FreeValue(mod->context, value);
        return java_error(env, "E_ASYNC_NOT_ALLOWED", "init and reduce must return synchronously");
    }
    /* Check the raw migration result before snapshotting so even a non-enumerable
     * own effects field remains forbidden. This path only inspects descriptors. */
    if (is_migration && !valid_migration_result(mod->context, value)) {
        JSValue pending = JS_GetException(mod->context);
        JS_FreeValue(mod->context, pending);
        JS_FreeValue(mod->context, value);
        return java_error(env, "E_INVALID_MIGRATION", "migrate must return state and config without effects");
    }
    uint32_t nodes = 0;
    if (!verify_json_value(mod, value, 0, &nodes)) {
        JSValue pending = JS_GetException(mod->context);
        JS_FreeValue(mod->context, pending);
        JS_FreeValue(mod->context, value);
        return java_error(env, "E_INVALID_RESULT", "result must be bounded JSON without non-finite numbers, accessors, or runtime objects");
    }
    nodes = 0;
    JSValue snapshot = snapshot_json_value(mod, value, 0, &nodes);
    JS_FreeValue(mod->context, value);
    if (JS_IsException(snapshot)) return exception_to_result(env, mod, JS_GetException(mod->context));
    /* Migration constraints apply to the exact tree that will be serialized,
     * not merely to a mutable script object inspected before it. */
    if (is_migration && !valid_migration_result(mod->context, snapshot)) {
        JSValue pending = JS_GetException(mod->context);
        JS_FreeValue(mod->context, pending);
        JS_FreeValue(mod->context, snapshot);
        return java_error(env, "E_INVALID_MIGRATION", "migrate snapshot must contain state and config without effects");
    }
    JSValue json = JS_JSONStringify(mod->context, snapshot, JS_UNDEFINED, JS_UNDEFINED);
    JS_FreeValue(mod->context, snapshot);
    if (JS_IsException(json)) return exception_to_result(env, mod, JS_GetException(mod->context));
    size_t len = 0;
    const char *utf8 = JS_ToCStringLen(mod->context, &len, json);
    if (utf8 == NULL) return java_error(env, "E_INVALID_RESULT", "result is not JSON serializable");
    if (len > MOD_RESULT_LIMIT) {
        JS_FreeCString(mod->context, utf8);
        JS_FreeValue(mod->context, json);
        return java_error(env, "E_MESSAGE_TOO_LARGE", "script result exceeds limit");
    }
    jstring result = java_success(env, utf8, len);
    JS_FreeCString(mod->context, utf8);
    JS_FreeValue(mod->context, json);
    return result;
}

JNIEXPORT jlong JNICALL
Java_top_zainzh_rearscreen_platform_runtime_QuickJsNative_nativeCreate(JNIEnv *env, jobject self) {
    (void) env; (void) self;
    ModRuntime *mod = (ModRuntime *) calloc(1, sizeof(ModRuntime));
    if (mod == NULL) return 0;
    mod->schedstat_fd = -1;
    mod->object_prototype = JS_UNDEFINED;
    mod->array_prototype = JS_UNDEFINED;
    atomic_init(&mod->stop_monitor, 1);
    atomic_init(&mod->capability_flags, 0);
    atomic_init(&mod->last_cpu_elapsed_ns, 0);
    mod->runtime = JS_NewRuntime();
    if (mod->runtime == NULL) { free(mod); return 0; }
    JS_SetMemoryLimit(mod->runtime, MOD_JS_HEAP_LIMIT);
    JS_SetMaxStackSize(mod->runtime, MOD_JS_STACK_LIMIT);
    JS_SetInterruptHandler(mod->runtime, interrupt_handler, mod);
    mod->context = JS_NewContext(mod->runtime);
    if (mod->context == NULL) {
        JS_FreeRuntime(mod->runtime);
        free(mod);
        return 0;
    }
    JSValue global = JS_GetGlobalObject(mod->context);
    JSValue object_ctor = JS_GetPropertyStr(mod->context, global, "Object");
    JSValue array_ctor = JS_GetPropertyStr(mod->context, global, "Array");
    JSValue object_prototype = JS_IsException(object_ctor) ? JS_EXCEPTION :
            JS_GetPropertyStr(mod->context, object_ctor, "prototype");
    JSValue array_prototype = JS_IsException(array_ctor) ? JS_EXCEPTION :
            JS_GetPropertyStr(mod->context, array_ctor, "prototype");
    JS_FreeValue(mod->context, object_ctor);
    JS_FreeValue(mod->context, array_ctor);
    int prototypes_valid = JS_IsObject(object_prototype) && JS_IsObject(array_prototype);
    if (prototypes_valid) {
        /* Capture trusted intrinsics before any user module executes. All later
         * plain-object checks compare identity against these rooted references,
         * never against script-mutable globalThis.Object/Array properties. */
        mod->object_prototype = object_prototype;
        mod->array_prototype = array_prototype;
    } else {
        if (!JS_IsException(object_prototype)) JS_FreeValue(mod->context, object_prototype);
        if (!JS_IsException(array_prototype)) JS_FreeValue(mod->context, array_prototype);
        JS_FreeValue(mod->context, global);
        JSValue pending = JS_GetException(mod->context);
        JS_FreeValue(mod->context, pending);
        JS_FreeContext(mod->context);
        JS_FreeRuntime(mod->runtime);
        free(mod);
        return 0;
    }
    JS_SetPropertyStr(mod->context, global, "eval", JS_UNDEFINED);
    JS_SetPropertyStr(mod->context, global, "Function", JS_UNDEFINED);
    JS_SetPropertyStr(mod->context, global, "SharedArrayBuffer", JS_UNDEFINED);
    JS_SetPropertyStr(mod->context, global, "Atomics", JS_UNDEFINED);
    JS_FreeValue(mod->context, global);
    return (jlong) (intptr_t) mod;
}

JNIEXPORT jstring JNICALL
Java_top_zainzh_rearscreen_platform_runtime_QuickJsNative_nativeLoadModule(JNIEnv *env, jobject self, jlong handle, jstring source) {
    (void) self;
    ModRuntime *mod = (ModRuntime *) (intptr_t) handle;
    if (mod != NULL) atomic_store_explicit(&mod->last_cpu_elapsed_ns, 0, memory_order_relaxed);
    size_t len = 0;
    char *text = copy_jstring(env, source, MOD_SOURCE_LIMIT, "source", &len);
    if (mod == NULL || text == NULL) return java_error(env, "E_INVALID_INPUT", "module source is missing or exceeds 512 KiB");
    start_budget(mod, 100);
    if (!budget_capabilities_available(mod)) {
        stop_budget(mod);
        free(text);
        return java_error(env, "E_BUDGET_UNAVAILABLE", "thread CPU clock or independent native watchdog is unavailable");
    }
    JSValue result = JS_Eval(mod->context, text, len, "mod-main.js", JS_EVAL_TYPE_GLOBAL | JS_EVAL_FLAG_STRICT);
    free(text);
    jstring output;
    if (JS_IsException(result)) output = exception_to_result(env, mod, JS_GetException(mod->context));
    else {
        JS_FreeValue(mod->context, result);
        if (!drain_jobs(mod)) output = mod->budget_reason != 0 ? budget_error(env, mod) : java_error(env, "E_SCRIPT", "module initialization jobs failed");
        else output = java_success(env, "{\"ok\":true}", strlen("{\"ok\":true}"));
    }
    stop_budget(mod);
    return output;
}

static jstring invoke(JNIEnv *env, ModRuntime *mod, const char *method, jstring input) {
    size_t len = 0;
    char *json = copy_jstring(env, input, MOD_MESSAGE_LIMIT, "message", &len);
    if (json == NULL) return java_error(env, "E_INVALID_INPUT", "message is missing or exceeds 256 KiB");
    if (!json_shape_ok(json, len)) {
        free(json);
        return java_error(env, "E_INVALID_INPUT", "message exceeds JSON depth or item limits");
    }
    JSValue argument = parse_json(mod->context, json, len);
    free(json);
    if (JS_IsException(argument)) return exception_to_result(env, mod, JS_GetException(mod->context));
    if (!JS_IsObject(argument) || JS_IsArray(mod->context, argument)) {
        JS_FreeValue(mod->context, argument);
        return java_error(env, "E_INVALID_INPUT", "runtime context must be a JSON object");
    }
    start_budget(mod, (strcmp(method, "init") == 0 || strcmp(method, "migrate") == 0) ? 500 : 100);
    if (!budget_capabilities_available(mod)) {
        stop_budget(mod);
        return java_error(env, "E_BUDGET_UNAVAILABLE", "thread CPU clock or independent native watchdog is unavailable");
    }
    int missing_export = 0;
    JSValue result = call_export(mod, method, argument, &missing_export);
    JS_FreeValue(mod->context, argument);
    if (missing_export) {
        stop_budget(mod);
        return java_error(env, "E_MIGRATION_REQUIRED", "module has no migrate(context) export");
    }
    if (!drain_jobs(mod)) {
        if (JS_IsException(result)) JS_FreeValue(mod->context, JS_GetException(mod->context));
        JS_FreeValue(mod->context, result);
        jstring failure = mod->budget_reason != 0 ? budget_error(env, mod) : java_error(env, "E_SCRIPT", "pending jobs failed");
        stop_budget(mod);
        return failure;
    }
    if (JS_IsException(result)) {
        jstring failure = exception_to_result(env, mod, JS_GetException(mod->context));
        stop_budget(mod);
        return failure;
    }
    jstring output = stringify_result(env, mod, result, strcmp(method, "migrate") == 0);
    stop_budget(mod);
    return output;
}

JNIEXPORT jstring JNICALL
Java_top_zainzh_rearscreen_platform_runtime_QuickJsNative_nativeInvoke(JNIEnv *env, jobject self, jlong handle, jstring method, jstring input) {
    (void) self;
    ModRuntime *mod = (ModRuntime *) (intptr_t) handle;
    if (mod == NULL) return java_error(env, "E_CLOSED", "runtime is closed");
    atomic_store_explicit(&mod->last_cpu_elapsed_ns, 0, memory_order_relaxed);
    const char *name = (*env)->GetStringUTFChars(env, method, NULL);
    if (name == NULL) return java_error(env, "E_INVALID_INPUT", "method is missing");
    if (strcmp(name, "init") != 0 && strcmp(name, "reduce") != 0 && strcmp(name, "migrate") != 0) {
        (*env)->ReleaseStringUTFChars(env, method, name);
        return java_error(env, "E_INVALID_INPUT", "unsupported runtime operation");
    }
    jstring result = invoke(env, mod, name, input);
    (*env)->ReleaseStringUTFChars(env, method, name);
    return result;
}

JNIEXPORT jint JNICALL
Java_top_zainzh_rearscreen_platform_runtime_QuickJsNative_nativeBudgetCapabilityFlags(JNIEnv *env, jobject self, jlong handle) {
    (void) env; (void) self;
    ModRuntime *mod = (ModRuntime *) (intptr_t) handle;
    return mod == NULL ? 0 : atomic_load_explicit(&mod->capability_flags, memory_order_relaxed);
}

JNIEXPORT jlong JNICALL
Java_top_zainzh_rearscreen_platform_runtime_QuickJsNative_nativeLastExecutionCpuTimeNanos(JNIEnv *env, jobject self, jlong handle) {
    (void) env; (void) self;
    ModRuntime *mod = (ModRuntime *) (intptr_t) handle;
    return mod == NULL ? 0 : (jlong) atomic_load_explicit(&mod->last_cpu_elapsed_ns, memory_order_relaxed);
}

JNIEXPORT jstring JNICALL
Java_top_zainzh_rearscreen_platform_runtime_QuickJsNative_nativePauseBudgetThreadForTesting(JNIEnv *env, jobject self, jlong handle) {
    (void) self;
    ModRuntime *mod = (ModRuntime *) (intptr_t) handle;
    if (mod == NULL) return java_error(env, "E_CLOSED", "runtime is closed");
    start_budget(mod, 100);
    if (!budget_capabilities_available(mod)) {
        stop_budget(mod);
        return java_error(env, "E_BUDGET_UNAVAILABLE", "thread CPU clock or independent native watchdog is unavailable");
    }
    struct timespec pause = {1, 500000000};
    while (nanosleep(&pause, &pause) != 0) { }
    stop_budget(mod);
    return java_success(env, "{\"ok\":true}", strlen("{\"ok\":true}"));
}

JNIEXPORT void JNICALL
Java_top_zainzh_rearscreen_platform_runtime_QuickJsNative_nativeStallForWatchdogTesting(JNIEnv *env, jobject self, jlong handle) {
    (void) env; (void) self;
    ModRuntime *mod = (ModRuntime *) (intptr_t) handle;
    if (mod == NULL) return;
    start_budget(mod, 100);
    volatile uint64_t value = 0;
    while (1) value = value * 1664525U + 1013904223U;
}

JNIEXPORT void JNICALL
Java_top_zainzh_rearscreen_platform_runtime_QuickJsNative_nativeDestroy(JNIEnv *env, jobject self, jlong handle) {
    (void) env; (void) self;
    ModRuntime *mod = (ModRuntime *) (intptr_t) handle;
    if (mod == NULL) return;
    stop_budget(mod);
    if (mod->schedstat_fd >= 0) close(mod->schedstat_fd);
    JS_FreeValue(mod->context, mod->object_prototype);
    JS_FreeValue(mod->context, mod->array_prototype);
    JS_FreeContext(mod->context);
    JS_FreeRuntime(mod->runtime);
    free(mod);
}
