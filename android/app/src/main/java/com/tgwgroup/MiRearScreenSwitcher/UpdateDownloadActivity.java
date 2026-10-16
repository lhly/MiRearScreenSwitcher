package com.tgwgroup.MiRearScreenSwitcher;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.content.FileProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import java.io.File;
import java.util.Locale;

/** Foreground update flow; the user always confirms installation in Android's installer. */
public class UpdateDownloadActivity extends Activity {
    static final String EXTRA_MANIFEST = "version_manifest";
    private UpdateDownloadTask task;
    private UpdatePackage update;
    private GlassUi ui;
    private TextView status, details;
    private ProgressBar progress;
    private Button primary;
    private boolean resumed, installerOpened, permissionOpened, installerError;

    static Intent intent(Context context, UpdatePackage update) {
        return new Intent(context, UpdateDownloadActivity.class).putExtra(EXTRA_MANIFEST, update.toJson());
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (getIntent().getStringExtra(EXTRA_MANIFEST) == null) { finish(); return; }
        ReleaseUpdateChecker.Result result = ReleaseUpdateChecker.evaluate(new ReleaseUpdateChecker.Response(200,
                getIntent().getStringExtra(EXTRA_MANIFEST)), BuildConfig.SOURCE_TAG, BuildConfig.VERSION_CODE);
        if (result.status != ReleaseUpdateChecker.Status.AVAILABLE || result.apk == null) { finish(); return; }
        update = result.apk;
        installerOpened = state != null && state.getBoolean("installer_opened");
        permissionOpened = state != null && state.getBoolean("permission_opened");
        ui = new GlassUi(this);
        buildView();
        Object retained = getLastNonConfigurationInstance();
        task = retained instanceof UpdateDownloadTask ? (UpdateDownloadTask) retained : createTask(update);
    }

    // Package-visible seams keep controlled transport tests out of the public app interface.
    UpdateDownloadTask createTask(UpdatePackage update) { return new UpdateDownloadTask(getApplicationContext(), update); }

    private void buildView() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightStatusBars(!ui.dark);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightNavigationBars(!ui.dark);
        GlassUi.Background background = ui.new Background();
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout content = ui.column(); content.setPadding(ui.dp(24), ui.dp(24), ui.dp(24), ui.dp(28));
        ViewCompat.setOnApplyWindowInsetsListener(background, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            background.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        TextView title = ui.text(getString(R.string.ui_download_update), 30, ui.text, true);
        ViewCompat.setAccessibilityHeading(title, true); content.addView(title);
        TextView version = ui.text(update.tag, 16, ui.secondary, false);
        version.setPadding(0, ui.dp(12), 0, ui.dp(28)); content.addView(version);
        LinearLayout card = ui.card(); card.setPadding(ui.dp(20), ui.dp(24), ui.dp(20), ui.dp(24));
        status = ui.text("", 18, ui.text, true); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        card.addView(status);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100); progress.setProgressTintList(ColorStateList.valueOf(ui.accent));
        LinearLayout.LayoutParams bar = new LinearLayout.LayoutParams(-1, ui.dp(12)); bar.topMargin = ui.dp(22); bar.bottomMargin = ui.dp(16);
        card.addView(progress, bar);
        details = ui.text("", 14, ui.secondary, false); card.addView(details); content.addView(card);
        TextView help = ui.text(getString(R.string.ui_download_help), 14, ui.secondary, false);
        help.setPadding(0, ui.dp(22), 0, ui.dp(24)); content.addView(help);
        primary = ui.button(getString(R.string.ui_install_update), true, this::primaryAction); content.addView(primary);
        Button cancel = ui.button(getString(R.string.ui_cancel), false, this::finish);
        LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(-1, -2); spacing.topMargin = ui.dp(12); content.addView(cancel, spacing);
        Button releases = ui.button(getString(R.string.ui_view_releases), false,
                () -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(ProjectInfo.RELEASES + "/tag/" + update.tag))));
        content.addView(releases, spacing);
        scroll.addView(content); background.addView(scroll); setContentView(background);
        ViewCompat.requestApplyInsets(background);
    }

    @Override protected void onResume() {
        super.onResume(); resumed = true;
        if (task == null) return;
        // Recheck the private file after returning from the permission screen.
        if (permissionOpened) {
            permissionOpened = false;
            if (canInstall() && task.state().phase == UpdateDownloadTask.Phase.READY) task.verifyAgain();
        } else if (!installerOpened && task.state().phase == UpdateDownloadTask.Phase.READY) task.verifyAgain();
        task.attach(this::render);
    }

    @Override protected void onStop() { resumed = false; if (task != null) task.detach(); super.onStop(); }
    @Override protected void onPause() { resumed = false; if (task != null) task.detach(); super.onPause(); }
    @Override protected void onDestroy() {
        if (task != null) { task.detach(); if (!isChangingConfigurations()) task.close(); }
        super.onDestroy();
    }
    @Override public Object onRetainNonConfigurationInstance() { return task; }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("installer_opened", installerOpened); state.putBoolean("permission_opened", permissionOpened);
        super.onSaveInstanceState(state);
    }

    boolean canInstall() { return Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls(); }

    private void render(UpdateDownloadTask.State state) {
        int percent = (int) Math.min(100, state.bytes * 100 / update.size);
        progress.setProgress(percent); progress.setIndeterminate(state.phase == UpdateDownloadTask.Phase.VERIFYING);
        primary.setVisibility(state.phase == UpdateDownloadTask.Phase.DOWNLOADING || state.phase == UpdateDownloadTask.Phase.VERIFYING ? View.GONE : View.VISIBLE);
        if (state.phase == UpdateDownloadTask.Phase.DOWNLOADING) {
            status.setText(getString(R.string.ui_downloading_update));
            details.setText(String.format(Locale.getDefault(), "%d%% · %.2f / %.2f MB", percent, state.bytes / 1000000d, update.size / 1000000d));
        } else if (state.phase == UpdateDownloadTask.Phase.VERIFYING) {
            status.setText(getString(R.string.ui_verifying_update)); details.setText(getString(R.string.ui_verify_help));
        } else if (state.phase == UpdateDownloadTask.Phase.ERROR) {
            progress.setIndeterminate(false); status.setText(getString(R.string.ui_download_failed));
            details.setText(errorMessage(state.error)); primary.setText(getString(R.string.ui_retry));
        } else if (state.phase == UpdateDownloadTask.Phase.READY) {
            progress.setProgress(100); progress.setIndeterminate(false);
            status.setText(getString(R.string.ui_download_ready));
            details.setText(getString(installerError ? R.string.ui_installer_unavailable
                    : canInstall() ? R.string.ui_ready_install_help : R.string.ui_install_permission_help));
            primary.setText(getString(canInstall() ? R.string.ui_install_update : R.string.ui_allow_install));
            if (resumed && !installerOpened && canInstall()) openInstaller();
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
        if (task.state().phase == UpdateDownloadTask.Phase.ERROR) {
            task.close(); installerOpened = false; task = createTask(update); task.attach(this::render);
        } else if (task.state().phase == UpdateDownloadTask.Phase.READY) {
            installerError = false;
            installerOpened = false;
            if (!canInstall()) {
                try {
                    permissionOpened = true;
                    startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                } catch (RuntimeException unavailable) { permissionOpened = false; installerError = true; details.setText(R.string.ui_installer_unavailable); }
            } else { installerOpened = false; task.verifyAgain(); }
        }
    }

    static Intent installerIntent(Context context, File file) {
        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".updates", file);
        Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri("MRSS update", uri));
        return intent;
    }

    private void openInstaller() {
        try {
            installerOpened = true;
            startActivity(installerIntent(this, task.state().file));
        } catch (RuntimeException unavailable) { installerError = true; details.setText(R.string.ui_installer_unavailable); }
    }
}
