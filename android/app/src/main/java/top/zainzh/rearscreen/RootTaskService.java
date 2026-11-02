package top.zainzh.rearscreen;

import android.content.Intent;
import android.os.IBinder;
import com.topjohnwu.superuser.ipc.RootService;

/** A non-daemon root host; the ordinary application continues to run under its own UID. */
public final class RootTaskService extends RootService {
    private TaskService service;
    @Override public IBinder onBind(Intent intent) {
        if (android.os.Process.myUid() != 0) throw new SecurityException("Root service requires UID 0");
        if (service == null) service = new TaskService(getApplicationInfo().uid);
        return service;
    }
    @Override public void onDestroy() {
        if (service != null) service.closeSessions();
        service = null;
        super.onDestroy();
    }
}
