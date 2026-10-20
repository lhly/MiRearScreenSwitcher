package top.zainzh.rearscreen;

import android.os.SystemClock;
import java.util.Arrays;

/** Shell-only software replay through the same safe-area mapping; never writes evdev. */
public final class RearViewportShellFixture {
    public static void main(String[] args) throws Exception {
        if (android.os.Process.myUid() != 2000 || !BuildConfig.INPUT_PROXY_PROTOTYPE) throw new SecurityException("Shell prototype only");
        if (args.length == 1 && args[0].equals("--display-api")) {
            System.out.println(Class.forName("android.hardware.display.IDisplayManager").getMethod("resizeVirtualDisplay",
                    Class.forName("android.hardware.display.IVirtualDisplayCallback"), int.class, int.class, int.class)); return;
        }
        RearCastViewport viewport = RearCastGeometry.read();
        if (args.length == 0) { System.out.println(RearCastGeometry.encode(viewport)); return; }
        if (args.length != 4) throw new IllegalArgumentException("display gesture physicalX physicalY");
        int display = Integer.parseInt(args[0]); String mode = args[1];
        Object global = Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null);
        Object target = global.getClass().getMethod("getDisplayInfo", int.class).invoke(global, display);
        int rotation = target == null ? -1 : target.getClass().getField("rotation").getInt(target);
        if (display < 2 || target == null || !target.getClass().getField("name").get(target).toString().startsWith("ReMRSS-prototype-"))
            throw new SecurityException("An owned safe/full virtual display is required");
        RearCastViewport.validateContentRotation(rotation);
        int logicalWidth = target.getClass().getField("logicalWidth").getInt(target);
        int logicalHeight = target.getClass().getField("logicalHeight").getInt(target);
        int bufferWidth = (rotation & 1) == 0 ? logicalWidth : logicalHeight;
        int bufferHeight = (rotation & 1) == 0 ? logicalHeight : logicalWidth;
        if (sameAspect(viewport.full(), bufferWidth, bufferHeight)) viewport = viewport.full();
        else if (!sameAspect(viewport, bufferWidth, bufferHeight)) throw new SecurityException("Unknown output geometry");
        viewport = viewport.buffered(bufferWidth, bufferHeight);
        RearInputInjector injector = RearInputInjector.shell(display);
        RearTouchState state = new RearTouchState(new RearTouchState.Output() {
            public void event(int action, int[] ids, float[] x, float[] y, long down, long time) throws Exception {
                injector.touch(action, ids, x, y, down, time);
                System.out.println("injected action=" + action + " x=" + Arrays.toString(x) + " y=" + Arrays.toString(y));
            }
            public void ended(float x, float y) { }
        }, viewport);
        state.contentRotation(rotation, SystemClock.uptimeMillis());
        RearTouchRouter router = new RearTouchRouter(state, viewport.physicalWidth, viewport.physicalHeight, viewport.density,
                true, time -> { injector.back(time); System.out.println("back_sent"); });
        float x = Float.parseFloat(args[2]), y = Float.parseFloat(args[3]);
        if (!Float.isFinite(x) || !Float.isFinite(y)) throw new IllegalArgumentException("Invalid coordinates");
        if (mode.equals("back")) {
            frame(router, c(0, x, y)); frame(router, c(0, x - 70, y)); frame(router, c(0, x - 180, y)); frame(router);
        } else if (mode.equals("multi")) {
            frame(router, c(0,x,y)); frame(router,c(0,x,y),c(1,x+100,y+30));
            frame(router,c(0,x-20,y),c(1,x+120,y+30));frame(router,c(1,x+120,y+30));frame(router);
        } else if (mode.equals("swipe")) {
            frame(router,c(0,x,y));for(int i=1;i<=6;i++)frame(router,c(0,x,y-i*20));frame(router);
        } else if (mode.equals("tap") || mode.equals("double") || mode.equals("outside")) {
            frame(router,c(0,x,y));frame(router);
            if (mode.equals("double")) { SystemClock.sleep(60);frame(router,c(0,x,y));frame(router); }
        } else throw new IllegalArgumentException("Unknown gesture");
        System.out.println("completed " + mode);
    }
    private static RearTouchState.Contact c(int slot, float x, float y) { return new RearTouchState.Contact(slot, slot+1, x, y); }
    private static boolean sameAspect(RearCastViewport v, int w, int h) {
        return Math.abs((long) w * v.height() - (long) h * v.width()) <= Math.max(v.width(), v.height());
    }
    private static void frame(RearTouchRouter router, RearTouchState.Contact... c) throws Exception {
        router.frame(Arrays.asList(c), SystemClock.uptimeMillis()); SystemClock.sleep(40);
    }
}
