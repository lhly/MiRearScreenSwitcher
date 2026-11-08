package top.zainzh.rearscreen.platform.runtime;

/** JNI surface is loaded only inside the isolated worker process. */
final class QuickJsNative {
    static {
        System.loadLibrary("remrss_mod_runtime");
    }

    private QuickJsNative() {}

    static native long nativeCreate();
    static native String nativeLoadModule(long handle, String source);
    static native String nativeInvoke(long handle, String method, String contextJson);
    static native int nativeBudgetCapabilityFlags(long handle);
    static native long nativeLastExecutionCpuTimeNanos(long handle);
    static native String nativePauseBudgetThreadForTesting(long handle);
    static native void nativeStallForWatchdogTesting(long handle);
    static native void nativeDestroy(long handle);
}
