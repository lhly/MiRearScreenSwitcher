/*
 * Author: AntiOblivionis
 * QQ: 319641317
 * Github: https://github.com/GoldenglowSusie/
 * Bilibili: 罗德岛T0驭械术师澄闪
 *
 * Chief Tester: 汐木泽
 *
 * Co-developed with AI assistants:
 * - Cursor
 * - Claude-4.5-Sonnet
 * - GPT-5
 * - Gemini-2.5-Pro
 */

package top.zainzh.rearscreen;

import android.graphics.Rect;
import android.util.Log;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads confirmed rear-display metrics; missing or ambiguous data is never guessed. */
public class RearDisplayHelper {
    private static final String TAG = "RearDisplayHelper";
    private static final int REAR_DISPLAY_ID = 1;

    /** An unpopulated instance is invalid and must not be cached or applied. */
    public static class RearDisplayInfo {
        public int width, height, densityDpi;
        public Rect cutout;

        public boolean isValid() {
            return width > 0 && height > 0 && densityDpi > 0 && cutout != null
                    && cutout.left >= 0 && cutout.top >= 0 && cutout.right >= 0 && cutout.bottom >= 0
                    && (long) cutout.left + cutout.right < width && (long) cutout.top + cutout.bottom < height;
        }
        public boolean hasCutout() {
            return cutout != null && (cutout.left > 0 || cutout.top > 0 || cutout.right > 0 || cutout.bottom > 0);
        }
        @Override public String toString() {
            return "RearDisplayInfo{width=" + width + ", height=" + height + ", dpi=" + densityDpi + ", cutout=" + cutout + "}";
        }
    }

    /** Returns null when the service, rear viewport or required metrics are unavailable. */
    public static RearDisplayInfo getRearDisplayInfo(ITaskService taskService) {
        if (taskService == null) return null;
        try {
            RearDisplayInfo info = parseRearDisplayInfo(taskService.executeShellCommandWithResult("dumpsys display"));
            if (info == null) Log.w(TAG, "背屏信息缺失或无法确认，等待后续重试");
            return info;
        } catch (Exception e) {
            Log.w(TAG, "读取背屏信息失败，等待后续重试", e);
            return null;
        }
    }

    static RearDisplayInfo parseRearDisplayInfo(String dumpsys) {
        if (dumpsys == null || dumpsys.isEmpty()) return null;
        try {
            Matcher viewports = Pattern.compile("DisplayViewport\\{[^}]*\\}").matcher(dumpsys);
            String viewport = null;
            while (viewports.find()) {
                String candidate = viewports.group();
                if (Pattern.compile("\\bdisplayId\\s*=\\s*" + REAR_DISPLAY_ID + "\\b").matcher(candidate).find()
                        && !Pattern.compile("\\bvalid\\s*=\\s*false\\b").matcher(candidate).find()) {
                    if (viewport != null && !viewport.equals(candidate)) return null;
                    viewport = candidate;
                }
            }
            if (viewport == null) return null;
            RearDisplayInfo info = new RearDisplayInfo();
            info.width = number(viewport, "\\bdeviceWidth=(\\d+)");
            info.height = number(viewport, "\\bdeviceHeight=(\\d+)");
            if (info.width <= 0 || info.height <= 0) return null;
            Matcher identity = Pattern.compile("\\buniqueId=['\"]([^'\"]+)['\"]").matcher(viewport);
            String uniqueId = identity.find() ? identity.group(1) : null;
            String device = null;
            int position = 0;
            while ((position = dumpsys.indexOf("DisplayDeviceInfo{", position)) >= 0) {
                int end = blockEnd(dumpsys, position + "DisplayDeviceInfo".length());
                if (end < 0) return null;
                String candidate = dumpsys.substring(position, end);
                Matcher id = Pattern.compile("\\buniqueId=['\"]([^'\"]+)['\"]").matcher(candidate);
                boolean matches = uniqueId != null ? id.find() && uniqueId.equals(id.group(1))
                        : Pattern.compile(",\\s*" + info.width + "\\s*x\\s*" + info.height + "\\s*,").matcher(candidate).find();
                if (matches) {
                    // A missing viewport identity may only fall back to one unambiguous device.
                    if (device != null && !device.equals(candidate)) return null;
                    device = candidate;
                }
                position = end;
            }
            if (device == null) return null;
            // Viewport density reflects the current override; physical-device density may differ.
            Matcher density = Pattern.compile("\\bdensityDpi=(\\d+)").matcher(viewport);
            info.densityDpi = density.find() ? Integer.parseInt(density.group(1)) : number(device, "\\bdensity\\s+(\\d+)");
            info.cutout = cutout(device);
            return info.isValid() ? info : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static int number(String text, String expression) {
        Matcher value = Pattern.compile(expression).matcher(text);
        return value.find() ? Integer.parseInt(value.group(1)) : 0;
    }
    private static Rect cutout(String device) {
        Matcher insets = Pattern.compile("DisplayCutout\\{\\s*insets=Rect\\(\\s*(\\d+)\\s*,\\s*(\\d+)(?:\\s*-\\s*|\\s*,\\s*)(\\d+)\\s*,\\s*(\\d+)\\s*\\)").matcher(device);
        if (insets.find()) return new Rect(Integer.parseInt(insets.group(1)), Integer.parseInt(insets.group(2)),
                Integer.parseInt(insets.group(3)), Integer.parseInt(insets.group(4)));
        // An explicit null means no cutout. An absent/unrecognized field remains unknown.
        return Pattern.compile("\\b(?:cutout|displayCutout)\\s*(?:[=:]\\s*)?null\\b", Pattern.CASE_INSENSITIVE).matcher(device).find()
                ? new Rect(0, 0, 0, 0) : null;
    }
    /** Find the complete device block, including nested mode/cutout records and quoted labels. */
    private static int blockEnd(String text, int start) {
        int depth = 0;
        char quote = 0;
        for (int i = start; i < text.length(); i++) {
            char value = text.charAt(i);
            if (quote != 0) {
                if (value == '\\') i++;
                else if (value == quote) quote = 0;
            } else if (value == '"' || value == '\'') quote = value;
            else if (value == '{') depth++;
            else if (value == '}' && --depth == 0) return i + 1;
        }
        return -1;
    }
}
