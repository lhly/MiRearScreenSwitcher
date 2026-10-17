package top.zainzh.rearscreen;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.Formatter;
import android.view.View;
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
    private final UpdateDialogUi ui;
    private final Session session;
    private final Runnable retryCheck;
    private boolean resumed;

    UpdateDialogController(MainActivity activity, Bundle saved, Runnable retryCheck) {
        this.activity = activity; this.retryCheck = retryCheck; ui = new UpdateDialogUi(activity, this::cancel);
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

    View getView() { return ui.getView(); }
    private void disposeDialog() { ui.dismiss(); }
    private void cancel() { disposeDialog(); session.clear(); }
    private void buildDialog() { render(); }
    private void attachTask() { if (session.task != null) session.task.attach(state -> render()); }

    private void render() {
        if (!session.visible || session.result == null) { ui.dismiss(); return; }
        ReleaseUpdateChecker.Result result = session.result;
        boolean available = result.status == ReleaseUpdateChecker.Status.AVAILABLE && result.apk != null;
        java.util.List<String> labels = new java.util.ArrayList<>();
        java.util.List<Runnable> actions = new java.util.ArrayList<>();
        String status = "", details = summary(activity, result);
        int percent = -1;
        boolean verifying = false;
        if (available) {
            UpdateDownloadTask.State state = session.task == null ? null : session.task.state();
            boolean busy = state != null && (state.phase == UpdateDownloadTask.Phase.DOWNLOADING || state.phase == UpdateDownloadTask.Phase.VERIFYING);
            int primary = R.string.ui_download_update;
            details = activity.getString(R.string.ui_update_size).replace("{0}", Formatter.formatShortFileSize(activity, result.apk.size));
            if (state != null) {
                percent = (int) Math.min(100, state.bytes * 100 / result.apk.size);
                if (state.phase == UpdateDownloadTask.Phase.DOWNLOADING) {
                    status = activity.getString(R.string.ui_downloading_update);
                    details = String.format(Locale.getDefault(), "%d%% · %.2f / %.2f MB", percent, state.bytes / 1000000d, result.apk.size / 1000000d);
                } else if (state.phase == UpdateDownloadTask.Phase.VERIFYING) {
                    verifying = true; status = activity.getString(R.string.ui_verifying_update); details = activity.getString(R.string.ui_verify_help);
                } else if (state.phase == UpdateDownloadTask.Phase.ERROR) {
                    percent = -1; status = activity.getString(R.string.ui_download_failed); details = activity.getString(errorMessage(state.error)); primary = R.string.ui_retry;
                } else if (state.phase == UpdateDownloadTask.Phase.READY) {
                    status = activity.getString(R.string.ui_download_ready);
                    details = activity.getString(session.installerError ? R.string.ui_installer_unavailable
                            : activity.canInstallUpdates() ? R.string.ui_ready_install_help : R.string.ui_install_permission_help);
                    primary = activity.canInstallUpdates() ? R.string.ui_install_update : R.string.ui_allow_install;
                }
            }
            if (!busy) {
                labels.add(activity.getString(primary)); actions.add(this::primaryAction);
                labels.add(activity.getString(R.string.ui_view_release)); actions.add(() -> { cancel(); openRelease(result.releaseUrl()); });
            }
        } else {
            boolean failed = result.status != ReleaseUpdateChecker.Status.CURRENT && result.status != ReleaseUpdateChecker.Status.LOCAL_NEWER;
            if (failed) { labels.add(activity.getString(R.string.ui_retry)); actions.add(() -> { cancel(); retryCheck.run(); }); }
            labels.add(activity.getString(R.string.ui_view_releases)); actions.add(() -> { cancel(); openRelease(failed ? ProjectInfo.RELEASES : result.releaseUrl()); });
        }
        labels.add(activity.getString(R.string.ui_cancel)); actions.add(this::cancel);
        ui.update(available ? summary(activity, result) : activity.getString(R.string.ui_check_updates),
                activity.getString(R.string.ui_current_version).replace("{0}", BuildConfig.VERSION_NAME), status, details, percent, verifying,
                labels.toArray(new String[0]), actions.toArray(new Runnable[0]));
        if (available && session.task != null && session.task.state().phase == UpdateDownloadTask.Phase.READY
                && resumed && !session.installerOpened && !session.installerError && activity.canInstallUpdates()) openInstaller();
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
