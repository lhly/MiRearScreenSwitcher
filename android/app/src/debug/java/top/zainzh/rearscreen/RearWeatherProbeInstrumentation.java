package top.zainzh.rearscreen;

import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import java.util.concurrent.*;

/** Small local integration probe; excludes location fields from its report. */
public final class RearWeatherProbeInstrumentation extends Instrumentation {
    private final CountDownLatch connected = new CountDownLatch(1);
    private ITaskService remote;
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName n, IBinder b) { remote = ITaskService.Stub.asInterface(b); connected.countDown(); }
        public void onServiceDisconnected(ComponentName n) { remote = null; }
    };
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); int code = 0;
        try {
            runOnMainSync(() -> TaskServiceManager.acquire(getTargetContext(), connection));
            if (!connected.await(8, TimeUnit.SECONDS)) throw new AssertionError("Shizuku unavailable");
            result.putString("fixed_weather", remote.readSystemWeather());
            org.json.JSONObject weather = new org.json.JSONObject(result.getString("fixed_weather"));
            if (weather.has("city_id") || weather.has("city_name")) throw new AssertionError("Location crossed weather IPC");
            if (RearWeatherSnapshot.parse(weather.optString("temperature", null), weather.optString("weather_type", null),
                    weather.optString("publish_time", null), weather.optString("temperature_unit", null)) == null)
                throw new AssertionError("No valid system weather");
            code = -1;
        } catch (Exception e) { result.putString("error", android.util.Log.getStackTraceString(e)); }
        finally { runOnMainSync(() -> TaskServiceManager.release(connection)); }
        finish(code, result);
    }
}
