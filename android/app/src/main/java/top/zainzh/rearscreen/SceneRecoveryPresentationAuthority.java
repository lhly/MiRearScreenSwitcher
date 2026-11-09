package top.zainzh.rearscreen;

import android.view.Display;
import org.json.JSONArray;
import org.json.JSONObject;

/** Final authority for a Scene recovery success; never used by legacy cast/presentation callers. */
final class SceneRecoveryPresentationAuthority {
    interface RearStateReader { String read(); }
    interface TaskSnapshots { SceneTaskLaunchFlow.TaskSnapshot read(); }
    interface CurrentGate { SceneTaskPresentationFlow.ActionGateResult check(); }

    private SceneRecoveryPresentationAuthority() {}

    /** Cheap preflight prevents even an exact-task focus when the physical selected panel is OFF. */
    static String preflightRear(int expectedDisplayId, RearStateReader reader) {
        return readSelectedRearOn(expectedDisplayId, reader);
    }

    /**
     * Revalidate the scene's current lease, exact foreground task, selected physical display
     * identity, and real ON state after the presentation flow's slow reads or focus command.
     */
    static String confirm(int taskId, String component, int displayId,
                          RearStateReader rearReader, TaskSnapshots taskSnapshots,
                          CurrentGate currentGate) {
        String gate = currentGateStatus(currentGate);
        if (gate != null) return gate;
        String rear = readSelectedRearOn(displayId, rearReader);
        if (rear != null) return rear;
        gate = currentGateStatus(currentGate);
        if (gate != null) return gate;

        final SceneTaskLaunchFlow.TaskSnapshot snapshot;
        try { snapshot = taskSnapshots == null ? null : taskSnapshots.read(); }
        catch (RuntimeException failure) { return "task_snapshot_unavailable"; }
        if (snapshot == null || snapshot.failure != null) return "task_snapshot_unavailable";
        SceneTaskIdentity.Result identity = SceneTaskIdentity.verifyOwner(
                snapshot.records, taskId, component, displayId, snapshot.source);
        if (!identity.verified()) return "owner_" + identity.status.name().toLowerCase(java.util.Locale.ROOT);
        if (identity.actual.visible == null) return "task_visibility_unsupported";
        if (!identity.actual.visible || !exactVisibleTop(snapshot.records, taskId, component, displayId))
            return "scene_not_visible_after_focus";

        // The task snapshot itself can be slow. Read rear state again after it, then put the
        // lease gate last so neither an old power cache nor a stale Scene can claim success.
        rear = readSelectedRearOn(displayId, rearReader);
        if (rear != null) return rear;
        return currentGateStatus(currentGate);
    }

    private static String readSelectedRearOn(int expectedDisplayId, RearStateReader reader) {
        if (reader == null || expectedDisplayId < 1) return "rear_state_unknown";
        try {
            String encoded = reader.read();
            if (encoded == null) return "rear_state_unknown";
            JSONObject state = new JSONObject(encoded);
            if (state.has("error") || state.optInt("selectedId", -1) < 1) return "rear_state_unknown";
            int selectedId = state.optInt("selectedId", -1);
            if (selectedId != expectedDisplayId) return "rear_display_replaced";
            String selectedUnique = state.optString("selectedUnique", "");
            if (selectedUnique.trim().isEmpty()) return "rear_state_unknown";
            JSONArray displays = state.optJSONArray("displays");
            if (displays == null) return "rear_state_unknown";
            for (int i = 0; i < displays.length(); i++) {
                JSONObject display = displays.optJSONObject(i);
                if (display == null || display.optInt("id", -1) != selectedId) continue;
                if (!selectedUnique.equals(display.optString("unique", ""))) return "rear_display_replaced";
                if (!display.has("state")) return "rear_state_unknown";
                return display.optInt("state", -1) == Display.STATE_ON ? null : "rear_display_not_on";
            }
            return "rear_state_unknown";
        } catch (Exception unavailable) {
            return "rear_state_unknown";
        }
    }

    private static boolean exactVisibleTop(java.util.List<SceneTaskIdentity.Record> records,
                                           int taskId, String component, int displayId) {
        int visibleCount = 0;
        for (SceneTaskIdentity.Record record : records) {
            if (record.displayId != displayId) continue;
            if (record.visible == null) return false;
            if (!record.visible) continue;
            visibleCount++;
            if (record.taskId != taskId || !isOwnedPackageTop(component, record.topComponent)) return false;
        }
        return visibleCount == 1;
    }

    private static boolean isOwnedPackageTop(String component, String top) {
        if (component == null || top == null) return false;
        int componentSlash = component.indexOf('/');
        int topSlash = top.indexOf('/');
        return componentSlash > 0 && topSlash > 0 && component.substring(0, componentSlash)
                .equals(top.substring(0, topSlash));
    }

    private static String currentGateStatus(CurrentGate gate) {
        final SceneTaskPresentationFlow.ActionGateResult result;
        try { result = gate == null ? SceneTaskPresentationFlow.ActionGateResult.UNAVAILABLE : gate.check(); }
        catch (RuntimeException failure) { return "action_gate_unavailable"; }
        if (result == SceneTaskPresentationFlow.ActionGateResult.ALLOWED) return null;
        return result == SceneTaskPresentationFlow.ActionGateResult.CANCELLED
                ? "cancelled_before_focus" : "action_gate_unavailable";
    }
}
