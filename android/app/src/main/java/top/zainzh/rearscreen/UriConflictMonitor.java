package top.zainzh.rearscreen;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Reports other visible handlers for our supported commands; custom schemes are not exclusive. */
final class UriConflictMonitor implements AutoCloseable {
    interface Query { List<ResolveInfo> find(Intent intent); }
    private final Context context;
    private final Consumer<List<String>> listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean registered, closed, pending, refreshAgain;
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refresh(); }
    };

    UriConflictMonitor(Context context, Consumer<List<String>> listener) {
        this.context = context.getApplicationContext(); this.listener = listener;
    }

    void start() {
        if (closed) return;
        if (!registered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_PACKAGE_ADDED); filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            filter.addAction(Intent.ACTION_PACKAGE_CHANGED); filter.addDataScheme("package");
            ContextCompat.registerReceiver(context, packages, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
            registered = true;
        }
        refresh();
    }

    void stop() {
        if (registered) { context.unregisterReceiver(packages); registered = false; }
    }

    void refresh() {
        if (closed) return;
        if (pending) { refreshAgain = true; return; }
        pending = true;
        worker.execute(() -> {
            List<String> labels = new ArrayList<>();
            boolean succeeded = false;
            try {
                PackageManager manager = context.getPackageManager();
                for (ResolveInfo handler : findOtherHandlers(context.getPackageName(), BuildConfig.URI_SCHEME,
                        intent -> manager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY))) {
                    String name = handler.activityInfo.packageName;
                    CharSequence label = handler.activityInfo.applicationInfo == null ? name
                            : manager.getApplicationLabel(handler.activityInfo.applicationInfo);
                    labels.add(label + " (" + name + ")");
                }
                succeeded = true;
            } catch (RuntimeException ignored) { /* Retain the previous result instead of reporting a false all-clear. */ }
            final boolean complete = succeeded;
            main.post(() -> {
                if (closed) return;
                pending = false;
                if (complete) listener.accept(Collections.unmodifiableList(labels));
                if (refreshAgain) { refreshAgain = false; refresh(); }
            });
        });
    }

    static List<ResolveInfo> findOtherHandlers(String ownPackage, String scheme, Query query) {
        TreeMap<String, ResolveInfo> others = new TreeMap<>();
        // Host-specific filters must also be detected; querying only "scheme:" misses them.
        for (String command : new String[]{"switch?current=1", "return?current=1", "screenshot", "config?dpi=300&rotation=0"}) {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(scheme + "://" + command));
            for (ResolveInfo resolved : query.find(intent)) {
                ActivityInfo info = resolved.activityInfo;
                if (info != null && info.exported && info.enabled && !ownPackage.equals(info.packageName)) {
                    others.putIfAbsent(info.packageName, resolved);
                }
            }
        }
        return new ArrayList<>(others.values());
    }

    @Override public void close() {
        closed = true; stop(); main.removeCallbacksAndMessages(null); worker.shutdownNow();
    }
}
