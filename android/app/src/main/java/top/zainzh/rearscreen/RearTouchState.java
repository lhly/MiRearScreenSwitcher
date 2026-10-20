package top.zainzh.rearscreen;

import java.util.ArrayList;
import java.util.List;

/** Pure touch protocol conversion; slot reuse must end the previous pointer first. */
public final class RearTouchState {
    public interface Output {
        void event(int action, int[] ids, float[] xs, float[] ys, long down, long time) throws Exception;
        void ended(float x, float y) throws Exception;
    }
    public static final class Contact {
        final int slot, tracking;
        float x, y;
        int pointer;
        public Contact(int slot, int tracking, float x, float y) {
            this.slot = slot; this.tracking = tracking; this.x = x; this.y = y;
        }
    }
    private final List<Contact> active = new ArrayList<>();
    private final Output output;
    private RearCastViewport viewport;
    private boolean outsideViewport;
    private int contentRotation;
    public synchronized void contentRotation(int rotation, long time) throws Exception {
        configure(viewport, rotation, time);
    }
    public synchronized void configure(RearCastViewport next, int rotation, long time) throws Exception {
        RearCastViewport.validateContentRotation(rotation); cancel(time);
        viewport = next; contentRotation = rotation; outsideViewport = false;
        awaitingAllUp = contactCount > 0;
    }
    private long down, last;
    private boolean outputAvailable = true, awaitingAllUp;
    private int contactCount;
    public synchronized void outputAvailable(boolean available, long time) throws Exception {
        if (available == outputAvailable) return;
        if (!available) cancel(time);
        outputAvailable = available;
        awaitingAllUp = contactCount > 0;
    }
    public RearTouchState(Output output) { this(output, null); }
    public RearTouchState(Output output, RearCastViewport viewport) { this.output = output; this.viewport = viewport; }
    public static float[] coordinate(float nx, float ny, int width, int height, int rotation) {
        nx = Math.max(0, Math.min(1, nx)); ny = Math.max(0, Math.min(1, ny));
        float x, y;
        switch (rotation) {
            case 0: x = nx; y = ny; break;
            case 1: x = ny; y = 1 - nx; break;
            case 2: x = 1 - nx; y = 1 - ny; break;
            case 3: x = 1 - ny; y = nx; break;
            default: throw new IllegalArgumentException("Invalid rear rotation");
        }
        return new float[]{x * (width - 1), y * (height - 1)};
    }
    private Contact find(List<Contact> list, Contact c) {
        for (Contact candidate : list) if (candidate.slot == c.slot && candidate.tracking == c.tracking) return candidate;
        return null;
    }
    private void emit(int action, long time) throws Exception {
        int[] ids = new int[active.size()]; float[] xs = new float[ids.length], ys = new float[ids.length];
        for (int i = 0; i < ids.length; i++) { Contact c = active.get(i); ids[i] = c.pointer; xs[i] = c.x; ys[i] = c.y; }
        output.event(action, ids, xs, ys, down, time);
    }
    static void validateContacts(List<Contact> next) {
        if (next.size() > 16) throw new IllegalArgumentException("Too many rear pointers");
        for (int i = 0; i < next.size(); i++) {
            if (next.get(i).slot < 0 || next.get(i).slot >= 16 || next.get(i).tracking < 0) throw new IllegalArgumentException("Invalid rear contact");
            for (int j = 0; j < i; j++) if (next.get(i).slot == next.get(j).slot) throw new IllegalArgumentException("Duplicate rear slot");
        }
    }
    public synchronized void frame(List<Contact> next, long time) throws Exception {
        validateContacts(next);
        time = Math.max(time, last); last = time;
        contactCount = next.size();
        if (next.isEmpty()) outsideViewport = false;
        if (!outputAvailable || awaitingAllUp) {
            if (next.isEmpty()) awaitingAllUp = false;
            return;
        }
        if (viewport != null) {
            if (outsideViewport) return;
            for (Contact c : next) if (!viewport.contains(c.x, c.y)) {
                cancel(time); outsideViewport = true;
                return; // Never synthesize a late DOWN when an outside finger moves into the image.
            }
            List<Contact> mapped = new ArrayList<>();
            for (Contact c : next) {
                float[] xy = viewport.contentPoint(c.x, c.y, contentRotation);
                mapped.add(new Contact(c.slot, c.tracking, xy[0], xy[1]));
            }
            next = mapped; // Keep the router's physical coordinates independent of App pointer coordinates.
        }
        boolean moved = false, changed = false;
        for (Contact c : active) {
            Contact newer = find(next, c);
            if (newer != null) { moved |= c.x != newer.x || c.y != newer.y; c.x = newer.x; c.y = newer.y; }
        }
        for (int i = active.size() - 1; i >= 0; i--) {
            Contact c = active.get(i);
            if (find(next, c) != null) continue;
            boolean end = active.size() == 1;
            emit(end ? 1 : 6 | (i << 8), time); active.remove(i); changed = true;
            if (end) output.ended(c.x, c.y);
        }
        for (Contact c : next) {
            if (find(active, c) != null) continue;
            boolean[] used = new boolean[32]; for (Contact a : active) used[a.pointer] = true;
            int pointer = 0; while (used[pointer]) pointer++;
            c.pointer = pointer; active.add(c); changed = true;
            if (active.size() == 1) down = time;
            emit(active.size() == 1 ? 0 : 5 | ((active.size() - 1) << 8), time);
        }
        if (moved && !changed && !active.isEmpty()) emit(2, time);
    }
    public synchronized void cancel(long time) throws Exception {
        last = Math.max(time, last);
        if (!active.isEmpty()) {
            try { emit(3, last); } finally { active.clear(); }
        }
    }
}
