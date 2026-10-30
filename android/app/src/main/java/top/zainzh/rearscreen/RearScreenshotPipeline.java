package top.zainzh.rearscreen;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;
import java.io.*;
import java.util.UUID;

/** Capture first, then compose and publish; a failed shell must never lose a valid raw capture. */
final class RearScreenshotPipeline {
    enum Result { ORIGINAL, SHELL, SHELL_AND_ORIGINAL, ORIGINAL_FALLBACK, SHELL_ORIGINAL_FAILED }
    interface Progress { void preparingShell(); }
    interface ShellLoader { RearShellRepository.Asset load(String style, String color) throws Exception; }
    private RearScreenshotPipeline() {}
    static Result capture(Context context, ITaskService service, Progress progress) throws Exception {
        RearScreenshotPreferences.Options options = RearScreenshotPreferences.read(context);
        return capture(context, service, options, progress, (style, color) -> RearShellRepository.load(context, style, color));
    }
    static Result capture(Context context, ITaskService service, RearScreenshotPreferences.Options options,
            Progress progress, ShellLoader loader) throws Exception {
        int[] metadata = new int[3];
        Bitmap raw;
        try (ParcelFileDescriptor descriptor = service.captureRearScreenshot(metadata);
             InputStream input = descriptor == null ? null : new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            if (input == null) throw new IOException("Capture service returned no image");
            raw = BitmapFactory.decodeStream(input);
            if (raw == null) throw new IOException("Invalid rear screenshot");
        }
        Bitmap framed = null;
        try {
            if (!options.shell) { save(context, service, raw, false); return Result.ORIGINAL; }
            try {
                if (!RearShellRepository.cached(context, options.style, options.color)) progress.preparingShell();
                try (RearShellRepository.Asset asset = loader.load(options.style, options.color)) {
                    framed = compose(raw, metadata[0], asset);
                }
            } catch (Exception | OutOfMemoryError e) {
                Log.w("RearScreenshot", "Shell unavailable; saving the original", e);
                save(context, service, raw, false);
                return Result.ORIGINAL_FALLBACK;
            }
            // If publishing the larger composite fails, also try to retain the smaller original.
            try { save(context, service, framed, true); }
            catch (Exception e) {
                Log.w("RearScreenshot", "Shell export failed; saving the original", e);
                save(context, service, raw, false); return Result.ORIGINAL_FALLBACK;
            }
            if (!options.keepOriginal) return Result.SHELL;
            try { save(context, service, raw, false); return Result.SHELL_AND_ORIGINAL; }
            catch (Exception e) {
                Log.w("RearScreenshot", "Composite saved but original export failed", e);
                return Result.SHELL_ORIGINAL_FAILED;
            }
        } finally { raw.recycle(); if (framed != null) framed.recycle(); }
    }
    static Bitmap compose(Bitmap raw, int rotation, RearShellRepository.Asset asset) throws IOException {
        if (rotation < 0 || rotation > 3) throw new IOException("Invalid capture orientation");
        Bitmap source = raw;
        if (rotation != 0) {
            Matrix matrix = new Matrix(); matrix.postRotate(-90f * rotation);
            source = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), matrix, true);
        }
        Bitmap result = null;
        try {
            RearShellGeometry g = asset.geometry;
            if (!g.fits(source.getWidth(), source.getHeight())) throw new IOException("Shell does not match rear display");
            result = Bitmap.createBitmap(g.outputWidth, g.outputHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(result); Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            canvas.drawBitmap(source, null, new RectF(g.left, g.top, g.left + g.width, g.top + g.height), paint);
            canvas.drawBitmap(asset.bitmap, null, new RectF(0, 0, g.outputWidth, g.outputHeight), paint);
            return result;
        } catch (Exception | OutOfMemoryError e) { if (result != null) result.recycle(); throw e; }
        finally { if (source != raw) source.recycle(); }
    }
    static Uri save(Context context, ITaskService service, Bitmap bitmap, boolean shell) throws Exception {
        if (Build.VERSION.SDK_INT < 29) {
            File file = File.createTempFile("rear-screenshot-", ".png", context.getCacheDir());
            try {
                try (OutputStream output = new FileOutputStream(file)) {
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException("PNG encoding failed");
                }
                try (ParcelFileDescriptor source = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) {
                    if (!service.saveRearScreenshotLegacy(source)) throw new IOException("Screenshot save failed");
                }
                return Uri.EMPTY;
            } finally { if (!file.delete()) Log.w("RearScreenshot", "Could not remove export cache"); }
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "RD_" + System.currentTimeMillis() + "_"
                + UUID.randomUUID().toString().substring(0, 8) + (shell ? "_shell" : "") + ".png");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RearDisplay");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Cannot create screenshot in media library");
        try {
            try (OutputStream output = context.getContentResolver().openOutputStream(uri)) {
                if (output == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException("Screenshot write failed");
            }
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0);
            if (context.getContentResolver().update(uri, values, null, null) != 1) throw new IOException("Cannot publish screenshot");
            Log.i("RearScreenshot", "Saved " + (shell ? "shell" : "original") + " " + bitmap.getWidth() + "x" + bitmap.getHeight() + " " + uri);
            return uri;
        } catch (Exception | OutOfMemoryError e) {
            try { context.getContentResolver().delete(uri, null, null); }
            catch (Exception cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }
}
