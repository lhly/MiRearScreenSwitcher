package top.zainzh.rearscreen;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;

/** Runs under the test APK's UID and probes only the fixed read-only root identity command. */
public final class RootForeignCallerService extends Service {
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        android.app.NotificationManager notifications = getSystemService(android.app.NotificationManager.class);
        if (android.os.Build.VERSION.SDK_INT >= 26) notifications.createNotificationChannel(new android.app.NotificationChannel(
                "root_validation", "Root 权限验证", android.app.NotificationManager.IMPORTANCE_LOW));
        android.app.Notification.Builder builder = android.os.Build.VERSION.SDK_INT >= 26
                ? new android.app.Notification.Builder(this, "root_validation") : new android.app.Notification.Builder(this);
        android.app.Notification notification = builder.setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("Root 跨应用权限验证").setContentText("验证结束后自动停止").build();
        if (android.os.Build.VERSION.SDK_INT >= 34) startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, notification);
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent) {
        return new Binder() {
            @Override public boolean onTransact(int code, Parcel input, Parcel output, int flags) {
                if (code != IBinder.FIRST_CALL_TRANSACTION) return false;
                IBinder root = input.readStrongBinder();
                Parcel data = Parcel.obtain(), reply = Parcel.obtain();
                int result = 0;
                long identity = Binder.clearCallingIdentity();
                try {
                    data.writeInterfaceToken("top.zainzh.rearscreen.ITaskService");
                    data.writeString("id -u");
                    root.transact(IBinder.FIRST_CALL_TRANSACTION + 21, data, reply, 0);
                    reply.readException();
                } catch (SecurityException expected) {
                    result = expected.getMessage() != null && expected.getMessage().contains("Root service owner") ? 1 : 2;
                } catch (Exception unexpected) { result = -1; }
                finally { Binder.restoreCallingIdentity(identity); data.recycle(); reply.recycle(); }
                output.writeNoException(); output.writeInt(result); output.writeInt(android.os.Process.myUid());
                return true;
            }
        };
    }
}
