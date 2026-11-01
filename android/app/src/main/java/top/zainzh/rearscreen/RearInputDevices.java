package top.zainzh.rearscreen;

import android.view.InputDevice;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.List;

/** Non-grabbing inventory; the reader rechecks the selected name before taking any input. */
final class RearInputDevices {
    static List<RearInputPolicy.Device> read() throws Exception {
        DisplayRotationController.Result result = DisplayRotationController.runCommand("getevent", "-lp");
        List<RearInputPolicy.Device> devices = RearInputPolicy.parse(result.output);
        boolean readableTouch = false;
        for (RearInputPolicy.Device device : devices) if (device.valid()) readableTouch = true;
        if (!readableTouch) throw new IOException("REAR_INPUT_INVENTORY_UNAVAILABLE: exit=" + result.exitCode + " response=" + result.output);
        if (result.exitCode != 0 || result.output.contains("Permission denied"))
            CompatibilityDiagnostics.record("input_inventory_partial", "Some nodes unreadable; selecting only fully described touchscreens, exit=" + result.exitCode);
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice input = InputDevice.getDevice(id);
            if (input == null) continue;
            try {
                boolean external = (Boolean) InputDevice.class.getMethod("isExternal").invoke(input);
                for (RearInputPolicy.Device d : devices) if (d.name.equals(input.getName())) d.external = external ? 1 : 0;
            } catch (Exception ignored) { }
            try {
                int associated = (Integer) InputDevice.class.getMethod("getAssociatedDisplayId").invoke(input);
                for (RearInputPolicy.Device d : devices) if (d.name.equals(input.getName())) {
                    // Multiple framework devices with the same kernel name cannot prove an association.
                    if (d.associatedDisplay != -1 && d.associatedDisplay != associated) d.associatedDisplay = -2;
                    else if (d.associatedDisplay != -2) d.associatedDisplay = associated;
                }
            } catch (Exception ignored) { }
        }
        try {
            DisplayRotationController.Result mapping = DisplayRotationController.runCommand("dumpsys", "input");
            if (mapping.exitCode == 0) RearInputPolicy.associate(devices, mapping.output);
        } catch (Exception e) { CompatibilityDiagnostics.record("input_association", e.toString()); }
        return devices;
    }
    static RearInputPolicy.Device choose(List<RearInputPolicy.Device> devices) throws Exception {
        int rear = RearDisplay.requireId();
        Object info = RearDisplay.info(rear), main = RearDisplay.info(0);
        Class<?> type = info.getClass();
        return RearInputPolicy.select(devices, rear, type.getField("logicalWidth").getInt(info), type.getField("logicalHeight").getInt(info),
                main.getClass().getField("logicalWidth").getInt(main), main.getClass().getField("logicalHeight").getInt(main));
    }
    static RearInputPolicy.Device require() throws Exception {
        List<RearInputPolicy.Device> devices = read();
        RearInputPolicy.Device chosen = choose(devices);
        if (chosen == null) throw new IOException("REAR_INPUT_UNRESOLVED: missing or ambiguous rear touchscreen; candidates=" + devices.size());
        CompatibilityDiagnostics.record("input_selected", "path=" + chosen.path + " name=" + chosen.name + " association=" + chosen.associatedDisplay);
        return chosen;
    }
    static JSONObject diagnostics() throws Exception {
        List<RearInputPolicy.Device> devices = read(); JSONArray all = new JSONArray();
        for (RearInputPolicy.Device d : devices) all.put(new JSONObject().put("path", d.path).put("name", d.name)
                .put("xmin", d.xmin).put("xmax", d.xmax).put("ymin", d.ymin).put("ymax", d.ymax)
                .put("slots", d.slots).put("associatedDisplay", d.associatedDisplay).put("external", d.external).put("direct", d.direct).put("eligible", d.valid()));
        JSONObject result = new JSONObject().put("devices", all);
        try {
            RearInputPolicy.Device chosen = choose(devices);
            result.put("selected", chosen == null ? JSONObject.NULL : chosen.path);
        } catch (Exception e) { result.put("selectionError", e.toString()); }
        return result;
    }
}
