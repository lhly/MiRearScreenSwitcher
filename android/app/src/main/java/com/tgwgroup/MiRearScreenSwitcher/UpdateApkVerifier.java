package com.tgwgroup.MiRearScreenSwitcher;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;

/** Verify the complete APK before granting any installer read access. */
final class UpdateApkVerifier {
    static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    static void verifyHash(File file, UpdatePackage update) throws IOException {
        if (file.length() != update.size) throw new UpdateDownloadTask.Failure(UpdateDownloadTask.Error.SIZE);
        MessageDigest digest = sha256();
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32768]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        if (!hex(digest.digest()).equals(update.sha256)) throw new UpdateDownloadTask.Failure(UpdateDownloadTask.Error.HASH);
    }

    @SuppressWarnings("deprecation")
    static void verify(Context context, File file, UpdatePackage update) throws IOException {
        verifyHash(file, update);
        verifyPackage(context, file, update);
    }

    @SuppressWarnings("deprecation")
    static void verifyPackage(Context context, File file, UpdatePackage update) throws IOException {
        if (!context.getPackageName().equals(update.packageName)) {
            throw new UpdateDownloadTask.Failure(UpdateDownloadTask.Error.PACKAGE);
        }
        PackageManager manager = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo archive;
        try { archive = manager.getPackageArchiveInfo(file.getAbsolutePath(), flags); }
        catch (RuntimeException invalid) { throw new UpdateDownloadTask.Failure(UpdateDownloadTask.Error.PACKAGE); }
        try {
            PackageInfo installed = manager.getPackageInfo(context.getPackageName(), flags);
            verifyIdentity(archive, installed, update);
        } catch (PackageManager.NameNotFoundException impossible) {
            throw new UpdateDownloadTask.Failure(UpdateDownloadTask.Error.PACKAGE);
        }
    }

    @SuppressWarnings("deprecation")
    static void verifyIdentity(PackageInfo archive, PackageInfo installed, UpdatePackage update) throws IOException {
        if (archive == null || !update.packageName.equals(archive.packageName)
                || !update.packageName.equals(installed.packageName)) throw new UpdateDownloadTask.Failure(UpdateDownloadTask.Error.PACKAGE);
        long code = Build.VERSION.SDK_INT >= 28 ? archive.getLongVersionCode() : archive.versionCode;
        long current = Build.VERSION.SDK_INT >= 28 ? installed.getLongVersionCode() : installed.versionCode;
        if (code != update.versionCode || !update.versionName.equals(archive.versionName) || code <= current) {
            throw new UpdateDownloadTask.Failure(UpdateDownloadTask.Error.VERSION);
        }
        Set<String> expected = signatures(installed), actual = signatures(archive);
        if (expected.isEmpty() || !expected.equals(actual)) throw new UpdateDownloadTask.Failure(UpdateDownloadTask.Error.SIGNATURE);
    }

    @SuppressWarnings("deprecation")
    private static Set<String> signatures(PackageInfo info) {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= 28) {
            if (info.signingInfo == null) return java.util.Collections.emptySet();
            signatures = info.signingInfo.hasMultipleSigners() ? info.signingInfo.getApkContentsSigners()
                    : info.signingInfo.getSigningCertificateHistory();
        } else signatures = info.signatures;
        Set<String> result = new HashSet<>();
        if (signatures != null) for (Signature signature : signatures) result.add(hex(sha256().digest(signature.toByteArray())));
        return result;
    }
}
