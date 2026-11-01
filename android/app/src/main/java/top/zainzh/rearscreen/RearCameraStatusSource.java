package top.zainzh.rearscreen;

import android.content.*;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.text.format.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/** Visible-session data only: minute broadcasts and cancellable local-provider reads. */
@androidx.annotation.RequiresApi(33)
final class RearCameraStatusSource implements AutoCloseable {
    private static final Uri WEATHER = Uri.parse("content://weather/actualWeatherData/1");
    private static final String[] COLUMNS = {"temperature", "weather_type", "publish_time", "temperature_unit", "day"};
    private static final long REFRESH_MS = 15 * 60 * 1000L;
    interface Listener { void changed(String time, String date, String weekday, RearWeatherSnapshot weather); }
    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService worker;
    private CancellationSignal cancellation;
    private RearWeatherSnapshot weather;
    private boolean active, receiverRegistered, observerRegistered;
    private int generation;
    private long lastRead;
    private boolean bound;
    private ITaskService remote;
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (!active) return;
            remote = ITaskService.Stub.asInterface(binder); readWeather();
        }
        public void onServiceDisconnected(ComponentName name) { remote = null; }
    };
    private final Runnable timeout = () -> {
        if (cancellation != null) {
            cancellation.cancel(); cancellation = null; generation++;
            weather = null; emit();
        }
    };
    private final Runnable refresh = this::readWeather;
    private final BroadcastReceiver timeChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            emit();
            if (SystemClock.elapsedRealtime() - lastRead >= REFRESH_MS) readWeather();
        }
    };
    private final ContentObserver observer = new ContentObserver(main) {
        @Override public void onChange(boolean selfChange) {
            if (!active) return;
            main.removeCallbacks(refresh);
            main.postDelayed(refresh, Math.max(250, 5000 - (SystemClock.elapsedRealtime() - lastRead)));
        }
    };

    RearCameraStatusSource(Context context, Listener listener) {
        this.context = context.getApplicationContext(); this.listener = listener;
    }
    void start() {
        if (active) return;
        active = true;
        worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "rear-system-weather"); t.setDaemon(true); return t; });
        IntentFilter filter = new IntentFilter(Intent.ACTION_TIME_TICK);
        filter.addAction(Intent.ACTION_TIME_CHANGED); filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(Intent.ACTION_DATE_CHANGED); filter.addAction(Intent.ACTION_LOCALE_CHANGED);
        context.registerReceiver(timeChanged, filter, Context.RECEIVER_NOT_EXPORTED); receiverRegistered = true;
        try {
            context.getContentResolver().registerContentObserver(Uri.parse("content://weather"), true, observer);
            observerRegistered = true;
        } catch (RuntimeException ignored) { /* Recovery/minute age checks still work without provider notifications. */ }
        emit(); readWeather();
    }
    private void emit() {
        if (!active) return;
        Locale locale = context.getResources().getConfiguration().getLocales().get(0);
        Date now = new Date();
        listener.changed(new SimpleDateFormat(DateFormat.is24HourFormat(context) ? "HH:mm" : "h:mm", locale).format(now),
                new SimpleDateFormat("MM/dd", locale).format(now), new SimpleDateFormat("EEE", locale).format(now),
                weather != null && weather.fresh(now.getTime()) ? weather : null);
    }
    private void readWeather() {
        if (!active || cancellation != null || worker == null) return;
        lastRead = SystemClock.elapsedRealtime();
        CancellationSignal signal = new CancellationSignal(); cancellation = signal;
        int request = ++generation;
        ITaskService bridge = remote;
        main.postDelayed(timeout, 3000);
        worker.execute(() -> {
            RearWeatherSnapshot snapshot = null;
            try (Cursor cursor = context.getContentResolver().query(WEATHER, COLUMNS, null, null, null, signal)) {
                if (BuildConfig.DEBUG) android.util.Log.i("RearCameraStatusProbe", "provider_rows=" + (cursor == null ? -1 : cursor.getCount()));
                if (cursor != null) while (cursor.moveToNext()) {
                    String day = field(cursor, "day");
                    if (day != null && !"1".equals(day)) continue;
                    snapshot = RearWeatherSnapshot.parse(field(cursor, "temperature"), field(cursor, "weather_type"),
                            field(cursor, "publish_time"), field(cursor, "temperature_unit"));
                    if (BuildConfig.DEBUG) android.util.Log.i("RearCameraStatusProbe", "parsed=" + (snapshot != null)
                            + " fresh=" + (snapshot != null && snapshot.fresh(System.currentTimeMillis())));
                    break;
                }
            } catch (RuntimeException error) {
                if (BuildConfig.DEBUG) android.util.Log.i("RearCameraStatusProbe", "provider_error=" + error.getClass().getSimpleName());
            }
            if (snapshot == null && bridge != null && !signal.isCanceled()) {
                try {
                    org.json.JSONObject data = new org.json.JSONObject(bridge.readSystemWeather());
                    snapshot = RearWeatherSnapshot.parse(data.optString("temperature", null), data.optString("weather_type", null),
                            data.optString("publish_time", null), data.optString("temperature_unit", null));
                    if (BuildConfig.DEBUG) android.util.Log.i("RearCameraStatusProbe", "shizuku_weather=" + (snapshot != null));
                } catch (Exception ignored) { }
            }
            RearWeatherSnapshot result = snapshot;
            main.post(() -> {
                if (!active || request != generation) return;
                main.removeCallbacks(timeout); cancellation = null; weather = result; emit();
                if (result == null && !bound) {
                    bound = true; TaskServiceManager.acquire(context, connection);
                }
            });
        });
    }
    private static String field(Cursor cursor, String name) {
        int index = cursor.getColumnIndex(name);
        return index < 0 || cursor.isNull(index) ? null : cursor.getString(index);
    }
    void stop() {
        if (!active) return;
        active = false; generation++;
        main.removeCallbacksAndMessages(null);
        if (cancellation != null) { cancellation.cancel(); cancellation = null; }
        if (worker != null) { worker.shutdownNow(); worker = null; }
        if (receiverRegistered) { context.unregisterReceiver(timeChanged); receiverRegistered = false; }
        if (observerRegistered) { context.getContentResolver().unregisterContentObserver(observer); observerRegistered = false; }
        if (bound) { TaskServiceManager.release(connection); bound = false; remote = null; }
        weather = null;
    }
    boolean isActive() { return active; }
    @Override public void close() { stop(); }
}
