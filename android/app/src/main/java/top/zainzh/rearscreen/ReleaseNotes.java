package top.zainzh.rearscreen;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Optional, bounded user-facing feature notes carried by a release manifest. */
final class ReleaseNotes {
    static final int MAX_ITEMS_PER_GROUP = 12;
    static final int MAX_ITEM_LENGTH = 500;
    static final int MAX_TOTAL_LENGTH = 6000;
    static final ReleaseNotes EMPTY = new ReleaseNotes(new String[0], new String[0], new String[0]);

    private final String[] added;
    private final String[] improved;
    private final String[] fixed;

    private ReleaseNotes(String[] added, String[] improved, String[] fixed) {
        this.added = added.clone();
        this.improved = improved.clone();
        this.fixed = fixed.clone();
    }

    String[] getAdded() { return added.clone(); }
    String[] getImproved() { return improved.clone(); }
    String[] getFixed() { return fixed.clone(); }
    boolean isEmpty() { return added.length == 0 && improved.length == 0 && fixed.length == 0; }

    JSONObject toJson() {
        try {
            return new JSONObject().put("added", new JSONArray(added))
                    .put("improved", new JSONArray(improved)).put("fixed", new JSONArray(fixed));
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Parses only the optional releaseNotes field; malformed notes never weaken APK validation. */
    static ReleaseNotes parseOptional(JSONObject manifest) {
        if (manifest == null) return EMPTY;
        Object raw = manifest.opt("releaseNotes");
        if (!(raw instanceof JSONObject)) return EMPTY;
        JSONObject object = (JSONObject) raw;
        try {
            List<String> added = readGroup(object, "added");
            List<String> improved = readGroup(object, "improved");
            List<String> fixed = readGroup(object, "fixed");
            int total = totalLength(added) + totalLength(improved) + totalLength(fixed);
            if (total > MAX_TOTAL_LENGTH) return EMPTY;
            return new ReleaseNotes(added.toArray(new String[0]), improved.toArray(new String[0]), fixed.toArray(new String[0]));
        } catch (JSONException | IllegalArgumentException malformed) {
            return EMPTY;
        }
    }

    private static List<String> readGroup(JSONObject object, String name) throws JSONException {
        Object value = object.get(name);
        if (!(value instanceof JSONArray)) throw new IllegalArgumentException("Invalid notes group");
        JSONArray array = (JSONArray) value;
        if (array.length() > MAX_ITEMS_PER_GROUP) throw new IllegalArgumentException("Too many notes");
        List<String> items = new ArrayList<>(array.length());
        for (int index = 0; index < array.length(); index++) {
            Object item = array.get(index);
            if (!(item instanceof String)) throw new IllegalArgumentException("Invalid note item");
            String text = ((String) item).trim();
            if (text.isEmpty() || text.length() > MAX_ITEM_LENGTH) throw new IllegalArgumentException("Invalid note length");
            for (int offset = 0; offset < text.length(); offset++) {
                if (text.charAt(offset) < 0x20) throw new IllegalArgumentException("Invalid note character");
            }
            items.add(text);
        }
        return items;
    }

    private static int totalLength(List<String> items) {
        int total = 0;
        for (String item : items) total += item.length();
        return total;
    }
}
