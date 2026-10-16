package com.tgwgroup.MiRearScreenSwitcher;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.Formatter;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import java.util.Locale;

/** One update dialog: result, transfer, verification, permission and installer retry. */
final class UpdateDialogController {
    // Only application-context work and plain data survive Activity recreation.
    public static final class Session extends ViewModel {
        ReleaseUpdateChecker.Result result;
        UpdateDownloadTask task;
        boolean visible, installerOpened, permissionOpened, installerError;

        void clear() {
            if (task != null) task.close();
            task = null; result = null; visible = false;
            installerOpened = false; permissionOpened = false; installerError = false;
        }
        @Override protected void onCleared() { clear(); }
    }

    private final MainActivity activity;
    private final GlassUi ui;
    private final Session session;
    private final Runnable retryCheck;
    private AlertDialog dialog;
    private TextView status, details;
    private ProgressBar progress;
    private boolean resumed;

    UpdateDialogController(MainActivity activity, Bundle saved, Runnable retryCheck) {
        this.activity = activity; this.retryCheck = retryCheck; ui = new GlassUi(activity);
        session = new ViewModelProvider(activity, new ViewModelProvider.NewInstanceFactory()).get(Session.class);
        if (!session.visible && saved != null && saved.getBoolean("update_dialog_visible")) {
            String manifest = saved.getString("update_dialog_manifest");
            if (manifest != null) {
                ReleaseUpdateChecker.Result result = ReleaseUpdateChecker.evaluate(
                        new ReleaseUpdateChecker.Response(200, manifest), BuildConfig.SOURCE_TAG, BuildConfig.VERSION_CODE);
                // Process recreation restores the offer, never silently starts a new transfer.
                if (result.status == ReleaseUpdateChecker.Status.AVAILABLE) { session.result = result; session.visible = true; }
            }
        }
    }

    static String summary(Context context, ReleaseUpdateChecker.Result result) {
        String key;
        switch (result.status) {
            case AVAILABLE: return context.getString(R.string.ui_update_available).replace("{0}", result.tag);
            case CURRENT: key = "update_current"; break;
            case LOCAL_NEWER: key = "update_local_newer"; break;
            case MANIFEST_UNAVAILABLE: key = "update_none"; break;
            case RESTRICTED: key = "update_restricted"; break;
            case INVALID_RESPONSE: key = "update_invalid"; break;
            default: key = "update_network_error";
        }
        return NativeStrings.get(context, key);
    }

    void show(ReleaseUpdateChecker.Result result) {
        cancel(); session.result = result; session.visible = true;
        if (resumed) { buildDialog(); attachTask(); }
    }

    void onResume() {
        resumed = true;
        if (!session.visible) return;
        // Returning from settings or recreating the host must revalidate before automatic installation.
        if (session.task != null && session.task.state().phase == UpdateDownloadTask.Phase.READY
                && (session.permissionOpened || !session.installerOpened)) {
            session.permissionOpened = false;
            if (activity.canInstallUpdates()) session.task.verifyAgain();
        }
        buildDialog(); attachTask();
    }

    void onPause() { resumed = false; if (session.task != null) session.task.detach(); }

    void destroy() {
        onPause(); disposeDialog();
        if (!activity.isChangingConfigurations()) session.clear();
    }

    void saveState(Bundle saved) {
        if (session.visible && session.result != null && session.result.apk != null) {
            saved.putBoolean("update_dialog_visible", true);
            saved.putString("update_dialog_manifest", session.result.apk.toJson());
        }
    }

    private void disposeDialog() {
        // Dialog posts onDismiss asynchronously. Remove it before a configuration-change dismissal.
        if (dialog != null) { dialog.setOnDismissListener(null); dialog.setOnCancelListener(null); dialog.dismiss(); }
        dialog = null; status = null; details = null; progress = null;
    }

    private void cancel() {
        disposeDialog(); session.clear();
    }

    private void buildDialog() {
        if (dialog != null || session.result == null) return;
        ReleaseUpdateChecker.Result result = session.result;
        boolean available = result.status == ReleaseUpdateChecker.Status.AVAILABLE && result.apk != null;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(available ? summary(activity, result) : activity.getString(R.string.ui_check_updates))
                .setNegativeButton(R.string.ui_cancel, (d, which) -> cancel());
        String current = activity.getString(R.string.ui_current_version).replace("{0}", BuildConfig.VERSION_NAME);
        if (available) {
            ScrollView scroll = new ScrollView(activity);
            LinearLayout content = ui.column(); content.setPadding(ui.dp(24), ui.dp(6), ui.dp(24), ui.dp(12));
            TextView version = ui.text(current, 14, ui.secondary, false); content.addView(version);
            status = ui.text("", 16, ui.text, true); status.setPadding(0, ui.dp(20), 0, 0);
            status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); content.addView(status);
            progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
            progress.setMax(100); progress.setProgressTintList(ColorStateList.valueOf(ui.accent));
            progress.setIndeterminateTintList(ColorStateList.valueOf(ui.accent));
            LinearLayout.LayoutParams bar = new LinearLayout.LayoutParams(-1, ui.dp(12));
            bar.topMargin = ui.dp(14); bar.bottomMargin = ui.dp(12); content.addView(progress, bar);
            details = ui.text("", 14, ui.secondary, false); details.setPadding(0, ui.dp(14), 0, 0); content.addView(details);
            scroll.addView(content); builder.setView(scroll)
                    .setPositiveButton(R.string.ui_download_update, null)
                    .setNeutralButton(R.string.ui_view_release, (d, which) -> { cancel(); openRelease(result.releaseUrl()); });
        } else {
            builder.setMessage(current + "\n\n" + summary(activity, result));
            boolean failed = result.status != ReleaseUpdateChecker.Status.CURRENT && result.status != ReleaseUpdateChecker.Status.LOCAL_NEWER;
            if (failed) builder.setPositiveButton(R.string.ui_retry, (d, which) -> { cancel(); retryCheck.run(); })
                    .setNeutralButton(R.string.ui_view_releases, (d, which) -> { cancel(); openRelease(ProjectInfo.RELEASES); });
            else builder.setPositiveButton(R.string.ui_view_releases, (d, which) -> { cancel(); openRelease(result.releaseUrl()); });
        }
        dialog = builder.create(); dialog.setCanceledOnTouchOutside(false);
        AlertDialog shown = dialog;
        dialog.setOnCancelListener(d -> { if (dialog == shown) cancel(); });
        dialog.setOnDismissListener(d -> { if (dialog == shown) { session.clear(); dialog = null; } });
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(ui.glass(28, true));
            WindowManager.LayoutParams params = window.getAttributes(); params.dimAmount = ui.dark ? .5f : .28f;
            window.setAttributes(params);
        }
        for (int which : new int[]{-1, -2, -3}) {
            Button button = dialog.getButton(which);
            if (button != null) { button.setTextColor(ui.accent); button.setAllCaps(false); button.setMinHeight(ui.dp(48)); }
        }
        // AlertDialog's default positive listener dismisses the window; keep this instance in place.
        if (available) { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> primaryAction()); render(); }
    }

    private void attachTask() { if (session.task != null) session.task.attach(state -> render()); }

    private void render() {
        if (dialog == null || progress == null || !session.visible) return;
        Button primary = dialog.getButton(AlertDialog.BUTTON_POSITIVE), release = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        UpdateDownloadTask.State state = session.task == null ? null : session.task.state();
        boolean busy = state != null && (state.phase == UpdateDownloadTask.Phase.DOWNLOADING || state.phase == UpdateDownloadTask.Phase.VERIFYING);
        primary.setVisibility(busy ? View.GONE : View.VISIBLE); release.setVisibility(busy ? View.GONE : View.VISIBLE);
        progress.setVisibility(state == null || state.phase == UpdateDownloadTask.Phase.ERROR ? View.GONE : View.VISIBLE);
        status.setVisibility(state == null ? View.GONE : View.VISIBLE);
        if (state == null) {
            details.setText(activity.getString(R.string.ui_update_size).replace("{0}", Formatter.formatShortFileSize(activity, session.result.apk.size)));
            return;
        }
        int percent = (int) Math.min(100, state.bytes * 100 / session.result.apk.size);
        progress.setIndeterminate(state.phase == UpdateDownloadTask.Phase.VERIFYING); progress.setProgress(percent);
        if (state.phase == UpdateDownloadTask.Phase.DOWNLOADING) {
            status.setText(R.string.ui_downloading_update);
            details.setText(String.format(Locale.getDefault(), "%d%% · %.2f / %.2f MB", percent,
                    state.bytes / 1000000d, session.result.apk.size / 1000000d));
        } else if (state.phase == UpdateDownloadTask.Phase.VERIFYING) {
            status.setText(R.string.ui_verifying_update); details.setText(R.string.ui_verify_help);
        } else if (state.phase == UpdateDownloadTask.Phase.ERROR) {
            status.setText(R.string.ui_download_failed); details.setText(errorMessage(state.error)); primary.setText(R.string.ui_retry);
        } else if (state.phase == UpdateDownloadTask.Phase.READY) {
            status.setText(R.string.ui_download_ready);
            details.setText(session.installerError ? R.string.ui_installer_unavailable
                    : activity.canInstallUpdates() ? R.string.ui_ready_install_help : R.string.ui_install_permission_help);
            primary.setText(activity.canInstallUpdates() ? R.string.ui_install_update : R.string.ui_allow_install);
            if (resumed && !session.installerOpened && !session.installerError && activity.canInstallUpdates()) openInstaller();
        }
    }

    private int errorMessage(UpdateDownloadTask.Error error) {
        if (error == UpdateDownloadTask.Error.STORAGE) return R.string.ui_download_storage_error;
        if (error == UpdateDownloadTask.Error.NETWORK) return R.string.ui_download_network_error;
        if (error == UpdateDownloadTask.Error.PACKAGE) return R.string.ui_download_package_error;
        if (error == UpdateDownloadTask.Error.SIGNATURE) return R.string.ui_download_signature_error;
        if (error == UpdateDownloadTask.Error.VERSION) return R.string.ui_download_version_error;
        return R.string.ui_download_integrity_error;
    }

    private void primaryAction() {
        if (session.task == null || session.task.state().phase == UpdateDownloadTask.Phase.ERROR) {
            if (session.task != null) session.task.close();
            session.installerOpened = false; session.installerError = false;
            session.task = activity.createUpdateTask(session.result.apk); attachTask();
        } else if (session.task.state().phase == UpdateDownloadTask.Phase.READY) {
            session.installerError = false; session.installerOpened = false;
            if (!activity.canInstallUpdates()) {
                try {
                    session.permissionOpened = true;
                    activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName())));
                } catch (RuntimeException unavailable) { session.permissionOpened = false; session.installerError = true; render(); }
            } else session.task.verifyAgain();
        }
    }

    private void openInstaller() {
        try {
            session.installerOpened = true;
            activity.startActivity(UpdateInstaller.intent(activity, session.task.state().file));
        } catch (RuntimeException unavailable) { session.installerOpened = false; session.installerError = true; render(); }
    }

    private void openRelease(String url) {
        try { activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (RuntimeException unavailable) { Toast.makeText(activity, R.string.ui_open_failed, Toast.LENGTH_SHORT).show(); }
    }
}
