package top.zainzh.rearscreen;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayDeque;

/** Sets only the formal app's visible search editor; no casting code is invoked. */
public final class FormalSearchUiInstrumentation extends Instrumentation {
    private String query;
    @Override public void onCreate(Bundle args) { super.onCreate(args); query = args.getString("query", ""); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); int code = 0;
        try {
            AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            if (root == null) throw new AssertionError("Formal app window missing");
            ArrayDeque<AccessibilityNodeInfo> nodes = new ArrayDeque<>(); nodes.add(root); boolean done = false;
            while (!nodes.isEmpty()) {
                AccessibilityNodeInfo node = nodes.remove();
                if ("top.zainzh.rearscreen".equals(String.valueOf(node.getPackageName()))
                        && "android.widget.EditText".equals(String.valueOf(node.getClassName()))) {
                    Bundle value = new Bundle(); value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query);
                    if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, value)) throw new AssertionError("Search rejected");
                    SystemClock.sleep(300); node.refresh(); result.putString("search", String.valueOf(node.getText())); done = true; break;
                }
                for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo child = node.getChild(i); if (child != null) nodes.add(child); }
            }
            if (!done) throw new AssertionError("Formal search field missing");
        } catch (Throwable error) { code = 1; result.putString("error", error.toString()); }
        finish(code, result);
    }
}
