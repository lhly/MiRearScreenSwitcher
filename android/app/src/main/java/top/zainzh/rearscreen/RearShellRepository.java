package top.zainzh.rearscreen;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Public Xiaomi resource IDs; artwork is downloaded to private storage, never bundled. */
public final class RearShellRepository {
    private static final long MAX_DOWNLOAD = 16 * 1024 * 1024;
    private RearShellRepository() {}
    public static boolean supported() { return resourceId(Build.DEVICE, "BK") != 0; }
    static boolean cached(Context context, String style, String color) {
        File directory = new File(new File(context.getFilesDir(), "rear_shells"), String.valueOf(resourceId(Build.DEVICE, color)));
        return new File(directory, style + "_shell.json").isFile() && new File(directory, style + "_shell.png").isFile();
    }
    static long resourceId(String device, String color) {
        String[] colors = {"BK", "WH", "PU", "LG"};
        long[] ids;
        if ("popsicle".equals(device)) ids = new long[]{25000238622966048L, 25000242116493312L, 25000241377443904L, 25000240442638464L};
        else if ("pandora".equals(device)) ids = new long[]{24994890488217856L, 25005738663542848L, 25005736619999360L, 25005735444545792L};
        else return 0;
        for (int i = 0; i < colors.length; i++) if (colors[i].equals(color)) return ids[i];
        return 0;
    }
    public static final class Asset implements AutoCloseable {
        public final Bitmap bitmap;
        final RearShellGeometry geometry;
        Asset(Bitmap bitmap, RearShellGeometry geometry) { this.bitmap = bitmap; this.geometry = geometry; }
        @Override public void close() { bitmap.recycle(); }
    }
    public static synchronized Asset load(Context context, String style, String color) throws Exception {
        return loadForDevice(context, Build.DEVICE, style, color);
    }
    static synchronized Asset loadForDevice(Context context, String device, String style, String color) throws Exception {
        if (!"full".equals(style) && !"half".equals(style)) throw new IOException("Unknown shell style");
        long id = resourceId(device, color);
        if (id == 0) throw new IOException("No shell for this device");
        File root = new File(context.getFilesDir(), "rear_shells");
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create shell directory");
        File directory = new File(root, String.valueOf(id));
        Asset cached = read(directory, style);
        if (cached != null) return cached;
        // A damaged cache is replaced only after a new archive has passed its checksum and parser.
        File staging = new File(root, id + ".pending");
        delete(staging);
        if (!staging.mkdirs()) throw new IOException("Cannot prepare shell directory");
        File archive = new File(staging, "resource.zip");
        try {
            android.util.Log.i("RearScreenshot", "Downloading shell resource " + id);
            JSONObject response = new JSONObject(new String(getBytes(new URL(
                    "https://i.mi.com/gallery/public/resource/download?id=" + id), 32768), java.nio.charset.StandardCharsets.UTF_8));
            if (response.optInt("code", -1) != 0) throw new IOException("Shell resource unavailable");
            JSONObject data = response.getJSONObject("data");
            String sha1 = data.getString("sha1Base16").toLowerCase(Locale.ROOT);
            if (!sha1.matches("[0-9a-f]{40}")) throw new IOException("Invalid shell checksum");
            URL url = new URL(data.getString("url"));
            String host = url.getHost().toLowerCase(Locale.ROOT);
            if (!"https".equals(url.getProtocol()) || !(host.endsWith(".market.mi-img.com") || host.endsWith(".market.xiaomi.com")))
                throw new IOException("Unexpected shell resource host: " + url.getProtocol() + "://" + url.getHost());
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            HttpURLConnection connection = connect(url);
            try (InputStream input = connection.getInputStream(); OutputStream output = new FileOutputStream(archive)) {
                byte[] buffer = new byte[32768]; long total = 0; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                    total += count;
                    if (total > MAX_DOWNLOAD) throw new IOException("Shell archive too large");
                    digest.update(buffer, 0, count); output.write(buffer, 0, count);
                }
            } finally { connection.disconnect(); }
            StringBuilder actual = new StringBuilder();
            for (byte value : digest.digest()) actual.append(String.format(Locale.ROOT, "%02x", value & 255));
            if (!sha1.contentEquals(actual)) throw new IOException("Shell checksum mismatch");
            try (ZipInputStream zip = new ZipInputStream(new FileInputStream(archive))) {
                ZipEntry entry; long total = 0;
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (entry.isDirectory()) continue;
                    if (!name.matches("(full|half)_shell\\.(png|json)")) throw new IOException("Unexpected shell file");
                    File file = new File(staging, name);
                    if (file.exists()) throw new IOException("Duplicate shell file");
                    try (OutputStream output = new FileOutputStream(file)) {
                        byte[] buffer = new byte[32768]; int count;
                        while ((count = zip.read(buffer)) != -1) {
                            total += count;
                            if (total > 32 * 1024 * 1024) throw new IOException("Expanded shell archive too large");
                            output.write(buffer, 0, count);
                        }
                    }
                }
            }
            // Validate both styles before installing this shared resource pack.
            for (String mode : new String[]{"full", "half"}) {
                Asset asset = read(staging, mode);
                if (asset == null) throw new IOException("Incomplete shell resource");
                asset.close();
            }
            if (!archive.delete()) throw new IOException("Cannot clean shell archive");
            delete(directory);
            if (!staging.renameTo(directory)) throw new IOException("Cannot install shell resource");
            android.util.Log.i("RearScreenshot", "Cached shell resource " + id);
            Asset result = read(directory, style);
            if (result == null) throw new IOException("Invalid installed shell");
            return result;
        } finally { delete(staging); }
    }
    private static Asset read(File directory, String style) {
        try {
            File config = new File(directory, style + "_shell.json");
            if (!config.isFile() || config.length() > 32768) return null;
            JSONObject j;
            try (InputStream input = new FileInputStream(config)) {
                j = new JSONObject(new String(readLimited(input, 32768), java.nio.charset.StandardCharsets.UTF_8));
            }
            // The official resource format spells its width field "with".
            RearShellGeometry geometry = new RearShellGeometry(j.getDouble("with"), j.getDouble("height"),
                    j.getDouble("leftMargin"), j.getDouble("topMargin"), j.getDouble("rightMargin"), j.getDouble("bottomMargin"));
            File image = new File(directory, style + "_shell.png");
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(image.getPath(), options);
            if (options.outWidth <= 0 || options.outHeight <= 0 || (long) options.outWidth * options.outHeight > 24000000) return null;
            Bitmap bitmap = BitmapFactory.decodeFile(image.getPath());
            return bitmap == null ? null : new Asset(bitmap, geometry);
        } catch (Exception e) { return null; }
    }
    private static HttpURLConnection connect(URL url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(10000); connection.setReadTimeout(15000);
        connection.setRequestProperty("Accept", "*/*");
        try {
            if (connection.getResponseCode() != 200) throw new IOException("Shell HTTP " + connection.getResponseCode());
            return connection;
        } catch (IOException e) { connection.disconnect(); throw e; }
    }
    private static byte[] getBytes(URL url, int limit) throws IOException {
        HttpURLConnection connection = connect(url);
        try (InputStream input = connection.getInputStream()) { return readLimited(input, limit); }
        finally { connection.disconnect(); }
    }
    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > limit) throw new IOException("Shell response too large");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        if (file.exists() && !file.delete()) android.util.Log.w("RearShell", "Could not remove " + file.getName());
    }
}
