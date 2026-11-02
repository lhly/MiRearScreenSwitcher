package top.zainzh.rearscreen;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import rikka.shizuku.Shizuku;

final class ShizukuBackend implements PrivilegeBackend {
    private final Shizuku.UserServiceArgs args;
    ShizukuBackend(String packageName) {
        args = new Shizuku.UserServiceArgs(new ComponentName(packageName, TaskService.class.getName()))
                .daemon(false).processNameSuffix("task_service").debuggable(false).version(39);
    }
    public State availability() {
        try {
            if (!Shizuku.pingBinder()) return State.UNAVAILABLE;
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ? State.READY : State.NEEDS_AUTHORIZATION;
        } catch (RuntimeException error) { return State.UNAVAILABLE; }
    }
    public void prepare(boolean allowRequest, Prepared callback) { callback.complete(availability()); }
    public void bind(ServiceConnection connection) { Shizuku.bindUserService(args, connection); }
    public void unbind(ServiceConnection connection) { Shizuku.unbindUserService(args, connection, false); }
}
