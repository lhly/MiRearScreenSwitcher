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
import java.util.function.BooleanSupplier;

/** One update dialog and one retained, lifecycle-safe check session. */
final class UpdateDialogController {
    interface CheckListener {
        void onCheckCompleted(ReleaseUpdateChecker.Result result, boolean manual);
    }

    // The checker is application-context-only. The listener is detached with its Activity.
    public static final class Session extends ViewModel {
        ReleaseUpdateChecker checker;
        ReleaseUpdateChecker.Result result;
        ReleaseUpdateChecker.Result pendingAutomaticResult;
        UpdateDownloadTask task;
        CheckListener listener;
        boolean initialized;
        boolean checking;
        boolean automaticCheckClaimed;
        boolean currentCheckAutomatic;
        boolean manualRequested;
        boolean pendingAutomaticOffer;
        ReleaseUpdateChecker.Result pendingManualResult;
        boolean automaticOfferConsumed;
        boolean visible;
        boolean dialogAutomatic;
        boolean installerOpened;
        boolean permissionOpened;
        boolean installerError;

        synchronized void complete(ReleaseUpdateChecker.Result completed) {
            checking = false;
            boolean manual = manualRequested;
            manualRequested = false;
            currentCheckAutomatic = false;
            if (!manual && completed.status == ReleaseUpdateChecker.Status.AVAILABLE) {
                pendingAutomaticResult = completed;
                pendingAutomaticOffer = true;
            }
            CheckListener current = listener;
            if (current != null) current.onCheckCompleted(completed, manual);
            else if (manual) pendingManualResult = completed;
        }

        synchronized void clearDialog() {
            if (task != null) task.close();
            task = null;
            result = null;
            visible = false;
            dialogAutomatic = false;
            installerOpened = false;
            permissionOpened = false;
            installerError = false;
        }

        @Override protected synchronized void onCleared() {
            clearDialog();
            listener = null;
            if (checker != null) checker.close();
            checker = null;
        }

        synchronized void replaceWithInjectedChecker(ReleaseUpdateChecker replacement, boolean automaticChecksEnabled) {
            if (checker != null && checker != replacement) checker.close();
            clearDialog();
            checker = replacement;
            checking = false;
            automaticCheckClaimed = !automaticChecksEnabled;
            currentCheckAutomatic = false;
            manualRequested = false;
            pendingAutomaticOffer = false;
            pendingAutomaticResult = null;
            pendingManualResult = null;
            automaticOfferConsumed = false;
            initialized = true;
        }
    }

    private static final String KEY_CHECKING = "update_session_checking";
    private static final String KEY_AUTO_CLAIMED = "update_session_auto_claimed";
    private static final String KEY_CURRENT_AUTO = "update_session_current_auto";
    private static final String KEY_MANUAL = "update_session_manual";
    private static final String KEY_PENDING_AUTO = "update_session_pending_auto";
    private static final String KEY_PENDING_AUTO_MANIFEST = "update_session_pending_auto_manifest";
    private static final String KEY_PENDING_AUTO_STATUS = "update_session_pending_auto_status";
    private static final String KEY_PENDING_AUTO_TAG = "update_session_pending_auto_tag";
    private static final String KEY_PENDING_MANUAL = "update_session_pending_manual";
    private static final String KEY_PENDING_MANUAL_STATUS = "update_session_pending_manual_status";
    private static final String KEY_PENDING_MANUAL_TAG = "update_session_pending_manual_tag";
    private static final String KEY_AUTO_CONSUMED = "update_session_auto_consumed";
    private static final String KEY_DIALOG_VISIBLE = "update_dialog_visible";
    private static final String KEY_DIALOG_AUTOMATIC = "update_dialog_automatic";
    private static final String KEY_DIALOG_MANIFEST = "update_dialog_manifest";
    private static final String KEY_DIALOG_STATUS = "update_dialog_status";
    private static final String KEY_DIALOG_TAG = "update_dialog_tag";

    private final MainActivity activity;
    private final UpdateDialogUi ui;
    private final Session session;
    private final Runnable retryCheck;
    private final CheckListener checkListener;
    private final BooleanSupplier automaticOfferAllowed;
    private boolean resumed;
    private boolean manualFeedbackVisible;

    UpdateDialogController(MainActivity activity, Bundle saved, ReleaseUpdateChecker checker,
            boolean automaticChecksEnabled, boolean injectedChecker, CheckListener listener,
            BooleanSupplier automaticOfferAllowed, Runnable retryCheck) {
        this.activity = activity;
        this.retryCheck = retryCheck;
        this.checkListener = listener;
        this.automaticOfferAllowed = automaticOfferAllowed;
        ui = new UpdateDialogUi(activity, this::cancel);
        session = new ViewModelProvider(activity, new ViewModelProvider.NewInstanceFactory()).get(Session.class);
        session.listener = checkListener;
        synchronized (session) {
            if (injectedChecker) {
                session.replaceWithInjectedChecker(checker, automaticChecksEnabled);
            } else {
                if (session.checker == null) session.checker = checker;
                else if (checker != session.checker) checker.close();
                if (!session.initialized) restoreSession(saved, true);
            }
        }
    }

    private void restoreSession(Bundle saved, boolean automaticChecksEnabled) {
        session.initialized = true;
        if (saved == null) return;
        boolean wasChecking = saved.getBoolean(KEY_CHECKING, false);
        session.automaticCheckClaimed = saved.getBoolean(KEY_AUTO_CLAIMED, false);
        session.currentCheckAutomatic = saved.getBoolean(KEY_CURRENT_AUTO, false);
        session.manualRequested = saved.getBoolean(KEY_MANUAL, false);
        session.pendingAutomaticOffer = saved.getBoolean(KEY_PENDING_AUTO, false);
        session.pendingAutomaticResult = readResult(saved, KEY_PENDING_AUTO_MANIFEST,
                KEY_PENDING_AUTO_STATUS, KEY_PENDING_AUTO_TAG);
        session.pendingManualResult = readResult(saved, KEY_PENDING_MANUAL,
                KEY_PENDING_MANUAL_STATUS, KEY_PENDING_MANUAL_TAG);
        session.automaticOfferConsumed = saved.getBoolean(KEY_AUTO_CONSUMED, false);
        // A process death loses the worker. Reopen the same logical request on first foreground.
        session.checking = false;
        if (wasChecking && session.currentCheckAutomatic) session.automaticCheckClaimed = false;
        if (wasChecking && !session.currentCheckAutomatic) session.manualRequested = true;
        ReleaseUpdateChecker.Result restored = readResult(saved, KEY_DIALOG_MANIFEST,
                KEY_DIALOG_STATUS, KEY_DIALOG_TAG);
        if (restored != null) {
            session.result = restored;
            session.visible = saved.getBoolean(KEY_DIALOG_VISIBLE, false);
            session.dialogAutomatic = saved.getBoolean(KEY_DIALOG_AUTOMATIC, false);
            if (session.visible) {
                session.pendingAutomaticOffer = false;
                if (session.dialogAutomatic) session.automaticOfferConsumed = true;
            }
        }
        if (!automaticChecksEnabled) session.automaticCheckClaimed = true;
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

    private static ReleaseUpdateChecker.Result readResult(Bundle saved, String manifestKey,
            String statusKey, String tagKey) {
        String manifest = saved.getString(manifestKey);
        if (manifest != null) {
            ReleaseUpdateChecker.Result parsed = ReleaseUpdateChecker.evaluate(
                    new ReleaseUpdateChecker.Response(200, manifest), BuildConfig.SOURCE_TAG, BuildConfig.VERSION_CODE);
            if (parsed.status != ReleaseUpdateChecker.Status.INVALID_RESPONSE) return parsed;
        }
        String statusName = saved.getString(statusKey);
        if (statusName == null) return null;
        try {
            ReleaseUpdateChecker.Status status = ReleaseUpdateChecker.Status.valueOf(statusName);
            if (status == ReleaseUpdateChecker.Status.AVAILABLE) return null;
            return new ReleaseUpdateChecker.Result(status, saved.getString(tagKey, ""));
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private static void writeResult(Bundle saved, String manifestKey, String statusKey,
            String tagKey, ReleaseUpdateChecker.Result result) {
        if (result == null) return;
        saved.putString(statusKey, result.status.name());
        saved.putString(tagKey, result.tag);
        String manifest = result.toManifestJson();
        if (!manifest.isEmpty()) saved.putString(manifestKey, manifest);
    }

    boolean isChecking() { synchronized (session) { return session.checking; } }

    void startAutomaticCheck() {
        synchronized (session) {
            if (session.automaticCheckClaimed || session.checking) return;
            if (session.visible || session.task != null) {
                session.automaticCheckClaimed = true;
                session.automaticOfferConsumed = true;
                return;
            }
            session.automaticCheckClaimed = true;
            beginCheckLocked(true);
        }
    }

    void resumeInterruptedCheck() {
        synchronized (session) {
            if (session.checking || !session.manualRequested) return;
            beginCheckLocked(false);
        }
    }

    void checkManually() {
        synchronized (session) {
            if (session.checking) {
                session.manualRequested = true;
                session.currentCheckAutomatic = false;
                return;
            }
            beginCheckLocked(false);
        }
    }

    private void beginCheckLocked(boolean automatic) {
        session.checking = true;
        session.currentCheckAutomatic = automatic;
        session.manualRequested = !automatic;
        boolean started = session.checker.check(BuildConfig.SOURCE_TAG, session::complete);
        if (!started) {
            session.checking = false;
            session.currentCheckAutomatic = false;
            if (automatic) session.automaticCheckClaimed = false;
        }
    }

    void offerAutomaticResultIfReady() {
        synchronized (session) {
            if (!resumed || !automaticOfferAllowed.getAsBoolean()
                    || !session.pendingAutomaticOffer || session.automaticOfferConsumed) return;
            session.pendingAutomaticOffer = false;
            // Never replace a visible dialog or an active transfer with an automatic offer.
            if (session.visible || session.task != null) {
                session.automaticOfferConsumed = true;
                session.pendingAutomaticResult = null;
                return;
            }
            ReleaseUpdateChecker.Result pending = session.pendingAutomaticResult;
            if (pending == null || pending.status != ReleaseUpdateChecker.Status.AVAILABLE) {
                session.pendingAutomaticResult = null;
                return;
            }
            session.automaticOfferConsumed = true;
            session.pendingAutomaticResult = null;
            session.result = pending;
            session.visible = true;
            session.dialogAutomatic = true;
            render();
        }
    }

    void handleCheckCompleted(ReleaseUpdateChecker.Result result, boolean manual) {
        synchronized (session) {
            if (manual) {
                session.pendingManualResult = result;
                deliverManualResultIfReady();
            } else {
                offerAutomaticResultIfReady();
            }
        }
    }

    private void deliverManualResultIfReady() {
        if (!resumed || !manualFeedbackVisible || session.pendingManualResult == null) return;
        ReleaseUpdateChecker.Result result = session.pendingManualResult;
        session.pendingManualResult = null;
        if (session.visible || session.task != null) return;
        showManual(result);
    }

    private void showManual(ReleaseUpdateChecker.Result result) {
        session.pendingManualResult = null;
        session.result = result;
        session.visible = true;
        session.dialogAutomatic = false;
        render();
    }

    void onResume(boolean manualFeedbackVisible) {
        resumed = true;
        this.manualFeedbackVisible = manualFeedbackVisible;
        deliverManualResultIfReady();
        if (session.visible && session.result != null) {
            if (session.task != null && session.task.state().phase == UpdateDownloadTask.Phase.READY
                    && (session.permissionOpened || !session.installerOpened)) {
                session.permissionOpened = false;
                if (activity.canInstallUpdates()) session.task.verifyAgain();
            }
            render();
            attachTask();
        }
        offerAutomaticResultIfReady();
    }

    void onPause() { resumed = false; manualFeedbackVisible = false; if (session.task != null) session.task.detach(); }

    void setManualFeedbackVisible(boolean visible) {
        manualFeedbackVisible = visible;
        deliverManualResultIfReady();
    }

    void destroy(boolean changingConfigurations) {
        onPause();
        disposeDialog();
        synchronized (session) {
            if (session.listener == checkListener) session.listener = null;
            if (!changingConfigurations) {
                // ViewModel onCleared owns the final cleanup. Do not retain a UI after a real finish.
                session.visible = false;
            }
        }
    }

    void saveState(Bundle saved) {
        synchronized (session) {
            saved.putBoolean(KEY_CHECKING, session.checking);
            saved.putBoolean(KEY_AUTO_CLAIMED, session.automaticCheckClaimed);
            saved.putBoolean(KEY_CURRENT_AUTO, session.currentCheckAutomatic);
            saved.putBoolean(KEY_MANUAL, session.manualRequested);
            saved.putBoolean(KEY_PENDING_AUTO, session.pendingAutomaticOffer);
            writeResult(saved, KEY_PENDING_AUTO_MANIFEST, KEY_PENDING_AUTO_STATUS,
                    KEY_PENDING_AUTO_TAG, session.pendingAutomaticResult);
            writeResult(saved, KEY_PENDING_MANUAL, KEY_PENDING_MANUAL_STATUS,
                    KEY_PENDING_MANUAL_TAG, session.pendingManualResult);
            saved.putBoolean(KEY_AUTO_CONSUMED, session.automaticOfferConsumed);
            ReleaseUpdateChecker.Result resultToSave = session.visible ? session.result
                    : session.pendingAutomaticOffer ? session.pendingAutomaticResult : null;
            if (resultToSave != null) {
                saved.putBoolean(KEY_DIALOG_VISIBLE, session.visible);
                saved.putBoolean(KEY_DIALOG_AUTOMATIC, session.dialogAutomatic);
                writeResult(saved, KEY_DIALOG_MANIFEST, KEY_DIALOG_STATUS, KEY_DIALOG_TAG, resultToSave);
            }
        }
    }

    View getView() { return ui.getView(); }

    private void disposeDialog() { ui.dismiss(); }

    private void cancel() {
        disposeDialog();
        synchronized (session) {
            if (session.dialogAutomatic) session.automaticOfferConsumed = true;
            session.pendingAutomaticOffer = false;
            session.pendingAutomaticResult = null;
            session.clearDialog();
        }
    }

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
        ReleaseNotes notes = result.notes;
        ui.update(available ? summary(activity, result) : activity.getString(R.string.ui_check_updates),
                activity.getString(R.string.ui_current_version).replace("{0}", BuildConfig.VERSION_NAME), status, details,
                available,
                percent, verifying,
                notes.getAdded(), notes.getImproved(), notes.getFixed(),
                activity.getString(R.string.ui_release_notes_added), activity.getString(R.string.ui_release_notes_improved),
                activity.getString(R.string.ui_release_notes_fixed), activity.getString(R.string.ui_release_notes_empty),
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
