package top.zainzh.rearscreen;

import java.util.List;

/** Route ordinary contacts unchanged; virtual casts also own the physical right-edge Back gesture. */
public final class RearTouchRouter {
    public static final long MENU_HOLD_MS = 250;
    public interface Back { void send(long time) throws Exception; }
    public interface Feedback { void update(float inward, float y, boolean active, boolean ready, boolean menuArmed); }
    public interface NotificationDismiss { void dismiss(long token); }
    public interface Menu {
        void visible(boolean open) throws Exception;
        void touch(int action, float x, float y, long down, long time) throws Exception;
    }
    private final RearTouchState touch;
    private final Back back;
    private final Feedback feedback;
    private Menu menu;
    private boolean menuOpen, menuTouching, menuBlocked, menuArmed;
    private long holdSince = -1, menuDown;
    private float holdX, holdY, menuX, menuY, holdSlop, menuRelease, currentY;
    private final boolean navigation;
    private int width, height, contacts, slot, tracking;
    private float edge, bottom, slop, commit, flickDistance, flickSpeed;
    private float startX, startY, inward, vertical;
    private long started;
    private boolean sequence, candidate, claimed, cancelled, feedbackActive, available = true, awaitingAllUp;
    private NotificationDismiss notificationDismiss;
    private long notificationToken, notificationDownToken;
    private float notificationLeft, notificationTop, notificationRight, notificationBottom, notificationX, notificationY;
    private int notificationSlot, notificationTracking;
    private boolean notificationClaimed, notificationTap;

    public void notification(long token, float left, float top, float right, float bottom, NotificationDismiss dismiss) {
        if (token != 0 && (token < 0 || left < 0 || top < 0 || right > width || bottom > height
                || !(left < right) || !(top < bottom))) throw new IllegalArgumentException("Invalid notification bounds");
        notificationToken = token; notificationLeft = left; notificationTop = top;
        notificationRight = right; notificationBottom = bottom; notificationDismiss = dismiss;
        if (notificationClaimed && notificationDownToken != token) notificationTap = false;
    }
    private boolean inNotification(float x, float y) {
        return x >= notificationLeft && x < notificationRight && y >= notificationTop && y < notificationBottom;
    }
    private boolean notificationFrame(List<RearTouchState.Contact> next, int previousContacts) {
        if (!notificationClaimed && available && !awaitingAllUp && previousContacts == 0 && !next.isEmpty() && notificationToken > 0) {
            for (RearTouchState.Contact c : next) if (inNotification(c.x, c.y)) {
                notificationClaimed = true; notificationTap = next.size() == 1;
                notificationDownToken = notificationToken; notificationSlot = c.slot; notificationTracking = c.tracking;
                notificationX = c.x; notificationY = c.y; break;
            }
        }
        if (!notificationClaimed) return false;
        if (next.isEmpty()) {
            boolean dismiss = notificationTap && notificationDownToken == notificationToken;
            long token = notificationDownToken;
            notificationClaimed = notificationTap = false;
            awaitingAllUp = false;
            if (dismiss && notificationDismiss != null) notificationDismiss.dismiss(token);
        } else {
            RearTouchState.Contact c = next.get(0);
            if (next.size() != 1 || c.slot != notificationSlot || c.tracking != notificationTracking
                    || !inNotification(c.x, c.y) || Math.abs(c.x - notificationX) > slop || Math.abs(c.y - notificationY) > slop)
                notificationTap = false;
        }
        return true; // Keep consuming through the last UP, including expiry/replacement during a hold.
    }

    public RearTouchRouter(RearTouchState touch, int width, int height, int density,
                           boolean navigation, Back back) {
        this(touch, width, height, density, navigation, back, null);
    }
    public RearTouchRouter(RearTouchState touch, int width, int height, int density,
                           boolean navigation, Back back, Feedback feedback) {
        this.touch = touch; this.navigation = navigation; this.back = back; this.feedback = feedback;
        geometry(width, height, density);
    }

    public void geometry(int width, int height, int density) {
        if (width < 2 || height < 2 || density < 1) throw new IllegalArgumentException("Invalid rear geometry");
        this.width = width; this.height = height;
        float scale = density / 160f;
        // Matches the physical rear navigation strip (~62 px at 450 dpi), independent of virtual DPI.
        edge = Math.round(22 * scale); bottom = Math.round(32 * scale);
        slop = 12 * scale; commit = 32 * scale; holdSlop = 16 * scale; menuRelease = 24 * scale;
        flickDistance = 18 * scale; flickSpeed = 600 * scale;
        reset();
    }

    private void finishFeedback(boolean committed) {
        if (!feedbackActive) return;
        feedbackActive = false;
        if (feedback != null) feedback.update(inward, currentY, false, committed, menuArmed);
    }
    private void reset() {
        finishFeedback(false);
        sequence = candidate = claimed = cancelled = false; inward = vertical = 0; holdSince = -1; menuArmed = false;
    }
    private boolean canCommit(long time) {
        long elapsed = Math.max(1, time - started);
        return claimed && !cancelled && !menuArmed && vertical <= inward * .8f
                && (inward >= commit || (inward >= flickDistance && inward * 1000 / elapsed >= flickSpeed));
    }

    public void setMenu(Menu menu) { this.menu = menu; }
    public boolean isMenuOpen() { return menuOpen; }
    public boolean isMenuArmed() { return menuArmed; }
    public void dismissMenu(long time) throws Exception {
        if (!menuOpen) return;
        if (menuTouching) menu.touch(3, menuX, menuY, menuDown, time);
        menuTouching = menuOpen = false; menuBlocked = false;
        awaitingAllUp = contacts > 0; reset(); menu.visible(false);
    }
    /** Called by an independent clock: a stationary finger produces no evdev frames. */
    public void tick(long time) throws Exception {
        if (menu == null || menuOpen || menuArmed || !available || awaitingAllUp || contacts != 1
                || !claimed || cancelled || holdSince < 0 || time - holdSince < MENU_HOLD_MS) return;
        // Reaching stage two changes the affordance only. The user's UP commits it.
        menuArmed = true; feedbackActive = true;
        if (feedback != null) feedback.update(inward, currentY, true, false, true);
    }
    private void menuFrame(List<RearTouchState.Contact> next, long time) throws Exception {
        if (next.size() > 1) {
            if (menuTouching) menu.touch(3, menuX, menuY, menuDown, time);
            menuTouching = false; menuBlocked = true;
        } else if (next.isEmpty()) {
            if (menuTouching) menu.touch(1, menuX, menuY, menuDown, time);
            menuTouching = menuBlocked = false;
        } else if (!menuBlocked) {
            RearTouchState.Contact c = next.get(0);
            if (menuTouching && (c.slot != slot || c.tracking != tracking)) {
                menu.touch(3, menuX, menuY, menuDown, time); menuTouching = false; menuBlocked = true; return;
            }
            menuX = c.x; menuY = c.y;
            if (!menuTouching) { slot = c.slot; tracking = c.tracking; menuDown = time; }
            menu.touch(menuTouching ? 2 : 0, menuX, menuY, menuDown, time); menuTouching = true;
        }
    }
    public void outputAvailable(boolean value, long time) throws Exception {
        if (value == available) return;
        dismissMenu(time);
        touch.outputAvailable(value, time);
        available = value; awaitingAllUp = contacts > 0; reset();
        if (!value) notificationTap = false;
    }

    public void cancel(long time) throws Exception { dismissMenu(time); awaitingAllUp = contacts > 0; notificationTap = false; reset(); touch.cancel(time); }

    /** Returns true for a completed accepted contact frame, including a matched real final UP. */
    public boolean frame(List<RearTouchState.Contact> next, long time) throws Exception {
        RearTouchState.validateContacts(next);
        int previousContacts = contacts;
        contacts = next.size();
        // A final UP belongs to a live sequence only when a prior real frame established contacts.
        // This deliberately excludes first/repeated empty frames and the post-pause all-up drain.
        boolean acceptedInteraction = available && !awaitingAllUp && (!next.isEmpty() || previousContacts > 0);
        if (notificationClaimed && notificationFrame(next, previousContacts)) return acceptedInteraction;
        if (menuOpen) { menuFrame(next, time); return acceptedInteraction; }
        if (notificationFrame(next, previousContacts)) return acceptedInteraction;
        if (available && awaitingAllUp) {
            if (next.isEmpty()) { touch.frame(next, time); awaitingAllUp = false; }
            return false; // A dismissed menu must not create a late App DOWN from a still-held finger.
        }
        if (!navigation || !available) {
            touch.frame(next, time);
            if (next.isEmpty()) awaitingAllUp = false;
            return acceptedInteraction;
        }
        if (next.isEmpty()) {
            boolean consumed = claimed;
            boolean openMenu = menu != null && menuArmed && !cancelled
                    && inward >= menuRelease && vertical <= inward * .8f;
            boolean perform = !openMenu && canCommit(time);
            finishFeedback(perform);
            reset();
            if (consumed) {
                // CANCEL cleared the App stream; still retire the physical contacts for reconnect gating.
                touch.frame(next, time);
                if (openMenu) {
                    menuOpen = true; menuTouching = menuBlocked = false;
                    menu.visible(true);
                } else if (perform) back.send(time);
            }
            else touch.frame(next, time);
            return acceptedInteraction;
        }
        RearTouchState.Contact first = next.get(0);
        if (!sequence) {
            sequence = true; started = time;
            slot = first.slot; tracking = first.tracking; startX = first.x; startY = first.y;
            candidate = next.size() == 1 && startX >= width - edge && startX < width
                    && startY >= 0 && startY < height - bottom;
        }
        if (next.size() != 1 || first.slot != slot || first.tracking != tracking) {
            candidate = false;
            if (claimed) cancelled = true;
        } else if (candidate || claimed) {
            inward = startX - first.x; vertical = Math.abs(first.y - startY);
            if (!claimed) {
                if (time - started > 700 || inward < -slop || (vertical > slop && vertical > inward * .8f))
                    candidate = false;
                else if (inward >= slop && inward > vertical * 1.5f) {
                    // Same contract as system pointer pilfering: cancel the App stream before taking over.
                    claimed = true; touch.cancel(time);
                }
            }
        }
        if (claimed) {
            currentY = first.y;
            // A lower release threshold prevents flicker around the menu-entry distance.
            if (cancelled || inward < menuRelease || vertical > Math.max(0, inward) * .8f) menuArmed = false;
            if (!menuArmed && !cancelled && inward >= commit && vertical <= inward * .8f) {
                if (holdSince < 0 || Math.abs(first.x - holdX) > holdSlop || Math.abs(first.y - holdY) > holdSlop) {
                    holdSince = time; holdX = first.x; holdY = first.y;
                }
            } else holdSince = -1;
            if (cancelled) finishFeedback(false);
            else {
                feedbackActive = true;
                if (feedback != null) feedback.update(Math.max(0, inward), first.y, true, canCommit(time), menuArmed);
            }
        }
        if (!claimed) touch.frame(next, time);
        return acceptedInteraction;
    }
}
