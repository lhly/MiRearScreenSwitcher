package top.zainzh.rearscreen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Select a unique touchscreen, rejecting main-panel associations and ambiguous candidates. */
final class RearInputPolicy {
    static final class Device {
        String path, name = "";
        int xmin, xmax, ymin, ymax, slots, associatedDisplay = -1, external = -1;
        boolean x, y, slot, direct;
        boolean valid() { return direct && x && y && slot && slots > 0 && slots <= 16 && xmax > xmin && ymax > ymin; }
    }
    static List<Device> parse(String dump) {
        List<Device> devices = new ArrayList<>(); Device current = null;
        Pattern header = Pattern.compile("add device \\d+: (/dev/input/event\\d+)");
        Pattern range = Pattern.compile("\\bmin\\s+(-?\\d+),\\s*max\\s+(-?\\d+)");
        for (String line : dump.split("\n")) {
            Matcher h = header.matcher(line);
            if (h.find()) { current = new Device(); current.path = h.group(1); devices.add(current); continue; }
            if (current == null) continue;
            if (line.trim().startsWith("name:")) current.name = line.substring(line.indexOf(':') + 1).trim().replace("\"", "");
            if (line.contains("INPUT_PROP_DIRECT")) current.direct = true;
            Matcher r = range.matcher(line);
            if (!r.find()) continue;
            int min = Integer.parseInt(r.group(1)), max = Integer.parseInt(r.group(2));
            if (line.contains("ABS_MT_POSITION_X") || line.matches(".*\\b0035\\s*:.*")) { current.x = true; current.xmin = min; current.xmax = max; }
            if (line.contains("ABS_MT_POSITION_Y") || line.matches(".*\\b0036\\s*:.*")) { current.y = true; current.ymin = min; current.ymax = max; }
            if (line.contains("ABS_MT_SLOT") || line.matches(".*\\b002f\\s*:.*")) { current.slot = min == 0; current.slots = max + 1; }
        }
        return devices;
    }
    static void associate(List<Device> devices, String dump) {
        String name = null;
        Pattern header = Pattern.compile("^\\s*Device \\d+: (.+)$");
        Pattern viewport = Pattern.compile("Viewport (?:INTERNAL|EXTERNAL): displayId=(\\d+)\\b");
        for (String line : dump.split("\n")) {
            Matcher h = header.matcher(line);
            if (h.find()) { name = h.group(1).trim(); continue; }
            Matcher v = viewport.matcher(line);
            if (name != null && v.find()) for (Device d : devices) if (d.name.equals(name)) {
                int id = Integer.parseInt(v.group(1));
                if (d.associatedDisplay >= 0 && d.associatedDisplay != id) d.associatedDisplay = -2;
                else if (d.associatedDisplay != -2) d.associatedDisplay = id;
            }
        }
    }
    private static double aspect(int x, int y) { return (double) Math.max(x, y) / Math.min(x, y); }
    static Device select(List<Device> devices, int rearId, int rw, int rh, int mw, int mh) {
        if (rearId <= 0 || rw < 2 || rh < 2) return null;
        Device selected = null; int best = 0; boolean ambiguous = false;
        for (Device d : devices) {
            if (!d.valid() || d.external == 1 || d.associatedDisplay == -2 || d.associatedDisplay == 0 || d.associatedDisplay > 0 && d.associatedDisplay != rearId) continue;
            String name = d.name.toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
            if (name.equals("xiaomi_touch_input_0") || name.contains("main_touch") || name.contains("primary_touch")) continue;
            int score = 0;
            if (d.associatedDisplay == rearId) score = 300;
            else if (d.name.equals("Xiaomi_Touch_Input_1")) score = 200;
            else if (name.contains("rear") || name.contains("back_touch") || name.contains("sub_touch")
                    || name.contains("secondary_touch") || name.contains("touch_input_1") || name.contains("背屏") || name.contains("副屏")) score = 100;
            else if (d.external == 0 && mw > 1 && mh > 1) {
                double ratio = aspect(d.xmax - d.xmin, d.ymax - d.ymin);
                double rearError = Math.abs(Math.log(ratio / aspect(rw, rh)));
                double mainError = Math.abs(Math.log(ratio / aspect(mw, mh)));
                if (rearError < 0.08 && mainError - rearError > 0.15) score = 50;
            }
            if (score > best) { selected = d; best = score; ambiguous = false; }
            else if (score > 0 && score == best) ambiguous = true;
        }
        return ambiguous ? null : selected;
    }
}
