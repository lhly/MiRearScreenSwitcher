package top.zainzh.rearscreen;

import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import org.json.JSONObject;
import java.util.concurrent.*;

/** Ends only the debug app's test service before restoring its pre-test installation. */
public final class CompatibilityCleanupInstrumentation extends Instrumentation {
    private ITaskService remote;
    private final CountDownLatch connected = new CountDownLatch(1);
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) { remote = ITaskService.Stub.asInterface(binder); connected.countDown(); }
        public void onServiceDisconnected(ComponentName name) { }
    };
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); int code = 0;
        try {
            if (!BuildConfig.DEBUG || !getTargetContext().getPackageName().endsWith(".debug")) throw new SecurityException("Debug cleanup only");
            runOnMainSync(() -> TaskServiceManager.acquire(getTargetContext(), connection));
            if (!connected.await(8, TimeUnit.SECONDS)) throw new AssertionError("Shizuku unavailable");
            remote.stopRearVirtualCast(null); remote.stopRearInputProxy(null);
            JSONObject virtual = new JSONObject(remote.getRearVirtualCastStatus()), input = new JSONObject(remote.getRearInputProxyStatus());
            if ("running".equals(virtual.optString("state")) || "running".equals(input.optString("state"))) throw new AssertionError("Test resources still active");
            result.putString("virtual", virtual.toString()); result.putString("input", input.toString());
        } catch (Exception error) { code = 1; result.putString("error", error.toString()); }
        finally { runOnMainSync(() -> TaskServiceManager.release(connection)); }
        if (code == 0 && remote != null) {
            try { remote.destroy(); } catch (RemoteException expectedOnServiceExit) { }
        }
        finish(code, result);
    }
}
