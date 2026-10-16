package top.zainzh.rearscreen;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Searchable, recycled native list. Icons are loaded only for visible rows. */
final class NativeAppPicker {
    private final NativeSettingsUi owner;
    private final MainActivity activity;
    private final GlassUi ui;
    private final LinearLayout root;
    private final ListView list;
    private final EditText search;
    private final TextView count, empty;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final LruCache<String, Drawable> icons = new LruCache<>(64);
    private final SharedPreferences preferences;
    private final Set<String> selected;
    private final List<App> all = new ArrayList<>(), visible = new ArrayList<>();
    private final AppAdapter adapter = new AppAdapter();
    private boolean includeSystem, destroyed, loading, permissionKnown;

    NativeAppPicker(NativeSettingsUi owner, MainActivity activity, Bundle state) {
        this.owner = owner; this.activity = activity; ui = owner.ui;
        preferences = activity.getSharedPreferences("mrss_settings", Context.MODE_PRIVATE);
        selected = new HashSet<>(preferences.getStringSet("notification_selected_apps", Collections.emptySet()));
        includeSystem = state != null && state.getBoolean("native_include_system", false);
        boolean compact = activity.getResources().getConfiguration().screenHeightDp < 480;
        root = compact ? ui.row() : ui.column();
        root.setPadding(ui.dp(20), ui.dp(14), ui.dp(20), ui.dp(12));
        LinearLayout controls = ui.column();
        if (compact) {
            ScrollView sidebar = new ScrollView(activity);
            sidebar.setVerticalScrollBarEnabled(false);
            sidebar.addView(controls, new ScrollView.LayoutParams(-1, -2));
            root.addView(sidebar, new LinearLayout.LayoutParams(ui.dp(240), -1));
        } else root.addView(controls, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout heading = ui.row();
        GlassUi.Icon back = ui.icon("back", ui.text);
        back.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        back.setContentDescription(owner.s("back"));
        ui.clickable(back, owner::closePicker, 24);
        heading.addView(back, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        TextView title = ui.text(owner.s("notification_apps"), 25, ui.text, true);
        title.setPadding(ui.dp(8), 0, 0, 0);
        androidx.core.view.ViewCompat.setAccessibilityHeading(title, true);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1)); controls.addView(heading);
        count = ui.text(owner.count("selected_count", selected.size()), 13, ui.secondary, false);
        count.setPadding(ui.dp(8), ui.dp(10), 0, ui.dp(16)); controls.addView(count);
        search = new EditText(activity);
        search.setTextSize(16); search.setTextColor(ui.text); search.setHintTextColor(ui.secondary);
        search.setHint(owner.s("search_hint")); search.setContentDescription(owner.s("search_apps"));
        search.setSingleLine(true); search.setMinHeight(ui.dp(56)); search.setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(12));
        search.setBackground(ui.glass(20, false));
        if (state != null) search.setText(state.getString("native_search", ""));
        controls.addView(search, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout filters = ui.row(); filters.setPadding(ui.dp(4), ui.dp(8), 0, ui.dp(8));
        TextView systemLabel = ui.text(owner.s("system_apps"), 14, ui.secondary, false);
        filters.addView(systemLabel, new LinearLayout.LayoutParams(0, -2, 1));
        Switch system = new Switch(activity); system.setMinimumHeight(ui.dp(48)); system.setMinimumWidth(ui.dp(52));
        system.setContentDescription(owner.s("system_apps")); system.setChecked(includeSystem);
        ui.styleSwitch(system); filters.addView(system);
        system.setOnCheckedChangeListener((button, checked) -> { includeSystem = checked; filter(); });
        controls.addView(filters);
        boolean stacked = compact || activity.getResources().getConfiguration().fontScale > 1.5f;
        LinearLayout bulk = stacked ? ui.column() : ui.row();
        String[] labels = {"select_all", "deselect_all", "refresh"};
        Runnable[] actions = {() -> bulkSelect(true), () -> bulkSelect(false), this::load};
        for (int i = 0; i < labels.length; i++) {
            LinearLayout.LayoutParams item = stacked ? new LinearLayout.LayoutParams(-1, -2)
                    : new LinearLayout.LayoutParams(i == 2 ? -2 : 0, -2, i == 2 ? 0 : 1);
            if (i > 0) { if (stacked) item.topMargin = ui.dp(8); else item.leftMargin = ui.dp(8); }
            bulk.addView(ui.button(owner.s(labels[i]), false, actions[i]), item);
        }
        controls.addView(bulk);
        boolean granted = queryGranted(); permissionKnown = granted;
        if (!granted) {
            TextView access = ui.text(owner.s("apps_access_help"), 13, ui.accent, false);
            access.setPadding(ui.dp(8), ui.dp(12), ui.dp(8), ui.dp(12)); access.setMinHeight(ui.dp(48));
            ui.clickable(access, () -> owner.action("requestQueryAllPackagesPermission"), 16); controls.addView(access);
        }
        FrameLayout card = new FrameLayout(activity); card.setBackground(ui.glass(24, false));
        card.setClipToOutline(true); card.setElevation(ui.dp(2));
        LinearLayout.LayoutParams cardParams = compact ? new LinearLayout.LayoutParams(0, -1, 1)
                : new LinearLayout.LayoutParams(-1, 0, 1);
        if (compact) cardParams.leftMargin = ui.dp(16); else cardParams.topMargin = ui.dp(14);
        root.addView(card, cardParams);
        list = new ListView(activity); list.setDivider(null); list.setVerticalScrollBarEnabled(false);
        list.setPadding(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(4)); list.setClipToPadding(false);
        card.addView(list, new FrameLayout.LayoutParams(-1, -1));
        empty = ui.text(owner.s("loading_apps"), 15, ui.secondary, false);
        empty.setGravity(Gravity.CENTER); empty.setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(20));
        card.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        list.setEmptyView(empty); list.setAdapter(adapter);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { filter(); }
            @Override public void afterTextChanged(Editable text) { }
        });
        load();
    }

    View getView() { return root; }
    void onResume() {
        boolean granted = queryGranted();
        if (granted != permissionKnown) { permissionKnown = granted; load(); }
    }
    void destroy() { destroyed = true; executor.shutdownNow(); icons.evictAll(); }
    void saveState(Bundle state) {
        state.putBoolean("native_include_system", includeSystem);
        state.putString("native_search", search.getText().toString());
    }

    private boolean queryGranted() {
        return activity.checkSelfPermission("android.permission.QUERY_ALL_PACKAGES") == PackageManager.PERMISSION_GRANTED;
    }

    private void load() {
        if (destroyed || loading) return;
        loading = true; empty.setText(owner.s("loading_apps"));
        executor.execute(() -> {
            List<App> apps = new ArrayList<>(); String failure = null;
            try {
                PackageManager pm = activity.getPackageManager();
                for (ApplicationInfo info : pm.getInstalledApplications(0)) {
                    if (!info.packageName.equals(activity.getPackageName()))
                        apps.add(new App(info, pm.getApplicationLabel(info).toString()));
                }
            } catch (Exception error) { failure = error.getMessage(); }
            String message = failure;
            activity.runOnUiThread(() -> {
                if (destroyed) return;
                loading = false; all.clear(); all.addAll(apps);
                empty.setText(message == null ? owner.s("no_apps_found") : message); filter();
            });
        });
    }

    private void filter() {
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        visible.clear();
        for (App app : all) if ((includeSystem || !app.system)
                && (query.isEmpty() || app.name.toLowerCase(Locale.ROOT).contains(query)
                    || app.info.packageName.toLowerCase(Locale.ROOT).contains(query))) visible.add(app);
        Collator collator = Collator.getInstance(activity.getResources().getConfiguration().getLocales().get(0));
        visible.sort((a, b) -> {
            int picked = Boolean.compare(selected.contains(b.info.packageName), selected.contains(a.info.packageName));
            return picked != 0 ? picked : collator.compare(a.name, b.name);
        });
        count.setText(owner.s("apps_summary").replace("{0}", String.valueOf(selected.size())).replace("{1}", String.valueOf(visible.size())));
        adapter.notifyDataSetChanged();
    }

    private void bulkSelect(boolean enabled) {
        for (App app : visible) {
            if (enabled) selected.add(app.info.packageName); else selected.remove(app.info.packageName);
        }
        save(); filter();
    }

    private void save() {
        preferences.edit().putStringSet("notification_selected_apps", new HashSet<>(selected)).apply();
    }

    private static final class App {
        final ApplicationInfo info;
        final String name;
        final boolean system;
        App(ApplicationInfo info, String name) {
            this.info = info; this.name = name;
            system = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0 || info.packageName.startsWith("com.android.")
                    || info.packageName.startsWith("com.google.android.") || info.packageName.startsWith("android");
        }
    }

    private final class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return visible.size(); }
        @Override public App getItem(int position) { return visible.get(position); }
        @Override public long getItemId(int position) { return getItem(position).info.packageName.hashCode(); }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            Holder holder;
            if (recycled == null) {
                LinearLayout row = ui.row(); row.setPadding(ui.dp(12), ui.dp(14), ui.dp(12), ui.dp(14));
                row.setMinimumHeight(ui.dp(80));
                ImageView icon = new ImageView(activity); icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                row.addView(icon, new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
                LinearLayout labels = ui.column(); labels.setPadding(ui.dp(14), 0, ui.dp(10), 0);
                TextView name = ui.text("", 16, ui.text, true), packageName = ui.text("", 12, ui.secondary, false);
                packageName.setPadding(0, ui.dp(4), 0, 0); packageName.setMaxLines(2);
                packageName.setEllipsize(android.text.TextUtils.TruncateAt.END);
                labels.addView(name); labels.addView(packageName); row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
                CheckBox check = new CheckBox(activity); check.setMinHeight(ui.dp(48)); check.setMinWidth(ui.dp(48));
                check.setButtonTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{ui.accent, ui.secondary}));
                row.addView(check); holder = new Holder(row, icon, name, packageName, check); row.setTag(holder);
                recycled = row;
            } else holder = (Holder) recycled.getTag();
            App app = getItem(position); String id = app.info.packageName;
            holder.name.setText(app.name); holder.packageName.setText(id); holder.icon.setTag(id);
            holder.check.setOnCheckedChangeListener(null); holder.check.setChecked(selected.contains(id));
            holder.check.setContentDescription(app.name);
            holder.check.setOnCheckedChangeListener((button, checked) -> {
                if (checked) selected.add(id); else selected.remove(id); save(); filter();
            });
            ui.clickable(holder.row, holder.check::toggle, 18);
            Drawable cached = icons.get(id);
            holder.icon.setImageDrawable(cached != null ? cached : activity.getPackageManager().getDefaultActivityIcon());
            if (cached == null && !destroyed) executor.execute(() -> {
                try {
                    Drawable image = activity.getPackageManager().getApplicationIcon(app.info);
                    activity.runOnUiThread(() -> {
                        if (destroyed) return;
                        icons.put(id, image);
                        if (id.equals(holder.icon.getTag())) holder.icon.setImageDrawable(image);
                    });
                } catch (Exception ignored) { }
            });
            return recycled;
        }
    }

    private static final class Holder {
        final LinearLayout row;
        final ImageView icon;
        final TextView name, packageName;
        final CheckBox check;
        Holder(LinearLayout row, ImageView icon, TextView name, TextView packageName, CheckBox check) {
            this.row = row; this.icon = icon; this.name = name; this.packageName = packageName; this.check = check;
        }
    }
}
