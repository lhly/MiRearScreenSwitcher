package top.zainzh.rearscreen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pure selection policy: a secondary internal panel is never interchangeable with an external display. */
final class RearDisplayPolicy {
    static final class Candidate {
        final int id, type, width, height;
        final String name, unique;
        Candidate(int id, int type, String name, String unique, int width, int height) {
            this.id = id; this.type = type; this.name = name == null ? "" : name;
            this.unique = unique == null ? "" : unique; this.width = width; this.height = height;
        }
        boolean physical() { return type == 1 || type == 0 && unique.startsWith("local:"); }
        boolean rearLabel() {
            String label = name.toLowerCase(Locale.ROOT);
            return label.contains("rear") || label.contains("subscreen") || label.contains("sub screen")
                    || label.contains("背屏") || label.contains("副屏");
        }
    }
    static Candidate select(List<Candidate> displays) {
        List<Candidate> internal = new ArrayList<>();
        for (Candidate c : displays) if (c.id > 0 && c.width > 1 && c.height > 1 && c.physical()) internal.add(c);
        if (internal.size() == 1) return internal.get(0);
        Candidate named = null;
        for (Candidate c : internal) if (c.rearLabel()) {
            if (named != null) return null;
            named = c;
        }
        return named;
    }
}
