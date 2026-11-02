package top.zainzh.rearscreen;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import com.topjohnwu.superuser.Shell;
import com.topjohnwu.superuser.ipc.RootService;
import java.util.IdentityHashMap;
import java.util.Map;

/** Only an explicit connection attempt may construct a new su shell. Status reads never do. */
final class RootBackend implements PrivilegeBackend {
    private final Intent intent;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<ServiceConnection, ServiceConnection> connections = new IdentityHashMap<>();
    RootBackend(Context context) { intent = new Intent(context, RootTaskService.class); }
    public State availability() {
        Shell shell = Shell.getCachedShell();
        return shell != null && shell.isAlive() && shell.isRoot() ? State.READY : State.NEEDS_AUTHORIZATION;
    }
    public void prepare(boolean allowRequest, Prepared callback) {
        if (availability() == State.READY) { callback.complete(State.READY); return; }
        if (!allowRequest) { callback.complete(State.NEEDS_AUTHORIZATION); return; }
        Shell.EXECUTOR.execute(() -> {
            State state;
            try {
                // Discard libsu's cached non-root fallback before a user-requested retry.
                Shell old = Shell.getCachedShell();
                if (old != null && !old.isRoot()) old.close();
                Shell shell = Shell.getShell();
                state = shell.isRoot() ? State.READY : State.DENIED;
                if (state == State.DENIED) shell.close();
            } catch (Exception error) { state = State.UNAVAILABLE; }
            State result = state;
            main.post(() -> callback.complete(result));
        });
    }
    public void bind(ServiceConnection connection) {
        Shell shell = Shell.getCachedShell();
        if (shell == null || !shell.isAlive() || !shell.isRoot()) throw new IllegalStateException("Root shell unavailable");
        ServiceConnection wrapper = new ServiceConnection() {
            public void onServiceConnected(ComponentName name, IBinder service) {
                if (connections.get(connection) != this) { RootService.unbind(this); return; }
                connection.onServiceConnected(name, service);
            }
            public void onServiceDisconnected(ComponentName name) {
                if (connections.get(connection) == this) connection.onServiceDisconnected(name);
            }
            public void onNullBinding(ComponentName name) { onServiceDisconnected(name); }
            public void onBindingDied(ComponentName name) { onServiceDisconnected(name); }
        };
        connections.put(connection, wrapper);
        Shell.Task task = RootService.bindOrTask(intent, command -> main.post(command), wrapper);
        if (task != null) shell.submitTask(task);
    }
    public void unbind(ServiceConnection connection) {
        ServiceConnection wrapper = connections.remove(connection);
        if (wrapper != null) RootService.unbind(wrapper);
    }
}
