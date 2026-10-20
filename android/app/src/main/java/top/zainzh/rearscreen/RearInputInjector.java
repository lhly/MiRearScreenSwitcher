package top.zainzh.rearscreen;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.KeyCharacterMap;
import android.view.MotionEvent;

import java.lang.reflect.Method;

/** Motion-event adapter shared by the shell proxy and its on-device probe. */
public final class RearInputInjector {
    public interface Sink { boolean inject(InputEvent event) throws Exception; }
    private final Sink sink;
    private final Method setDisplayId;
    private final int displayId;

    public RearInputInjector(int displayId, Sink sink) throws Exception {
        if (displayId < 1) throw new IllegalArgumentException("Main display injection is not supported");
        this.displayId = displayId;
        this.sink = sink;
        setDisplayId = InputEvent.class.getMethod("setDisplayId", int.class);
    }

    /** Called inside the Shizuku process, never with the ordinary app's identity. */
    public static RearInputInjector shell() throws Exception {
        return shell(1);
    }

    /** Virtual IDs are supplied only by the session that owns that display. */
    static RearInputInjector shell(int displayId) throws Exception {
        Class<?> services = Class.forName("android.os.ServiceManager");
        Object binder = services.getMethod("getService", String.class).invoke(null, "input");
        Class<?> stub = Class.forName("android.hardware.input.IInputManager$Stub");
        Object manager = stub.getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
        Method inject = Class.forName("android.hardware.input.IInputManager")
                .getMethod("injectInputEvent", InputEvent.class, int.class);
        return new RearInputInjector(displayId, event -> Boolean.TRUE.equals(inject.invoke(manager, event, 1)));
    }

    public void touch(int action, int[] ids, float[] xs, float[] ys, long downTime, long eventTime) throws Exception {
        send(action, ids, xs, ys, downTime, eventTime, InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.TOOL_TYPE_FINGER, -31);
    }

    /** Navigation stays on the owned virtual display, including while the main keyguard is showing. */
    public void back(long eventTime) throws Exception {
        if (displayId < 2) throw new IllegalStateException("Back requires a virtual cast display");
        long now = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
        KeyEvent up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP);
        setDisplayId.invoke(down, displayId); setDisplayId.invoke(up, displayId);
        try {
            if (!sink.inject(down)) throw new IllegalStateException("Virtual Back DOWN rejected");
        } finally {
            if (!sink.inject(up)) throw new IllegalStateException("Virtual Back UP rejected");
        }
    }

    private void send(int action, int[] ids, float[] xs, float[] ys, long downTime, long eventTime,
                      int source, int toolType, int device) throws Exception {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[ids.length];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[ids.length];
        for (int i = 0; i < ids.length; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = ids[i]; properties[i].toolType = toolType;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = xs[i]; coords[i].y = ys[i]; coords[i].pressure = 1; coords[i].size = .05f;
        }
        MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, ids.length, properties, coords,
                0, 0, 1, 1, device, 0, source, 0);
        try {
            setDisplayId.invoke(event, displayId);
            if (!sink.inject(event)) throw new IllegalStateException("Rear input injection rejected: " + action);
        } finally { event.recycle(); }
    }

    /** Experimental generic pointer events; ordinary touch coordinates/timing are unchanged. */
    public void resetSystemGesture(String mode, float x, float y) throws Exception {
        if ("none".equals(mode)) return;
        long now = SystemClock.uptimeMillis();
        int[] ids = {0}; float[] xs = {x}, ys = {y};
        if ("hover".equals(mode)) {
            send(MotionEvent.ACTION_HOVER_MOVE, ids, xs, ys, now, now,
                    InputDevice.SOURCE_MOUSE, MotionEvent.TOOL_TYPE_MOUSE, -32);
            send(MotionEvent.ACTION_HOVER_EXIT, ids, xs, ys, now, SystemClock.uptimeMillis(),
                    InputDevice.SOURCE_MOUSE, MotionEvent.TOOL_TYPE_MOUSE, -32);
        } else if ("scroll".equals(mode)) {
            send(MotionEvent.ACTION_SCROLL, ids, xs, ys, now, now,
                    InputDevice.SOURCE_MOUSE, MotionEvent.TOOL_TYPE_MOUSE, -32);
        } else throw new IllegalArgumentException("Unknown gesture reset mode: " + mode);
    }
}
