package com.tgwgroup.MiRearScreenSwitcher;

import android.os.Handler;
import android.os.Looper;
import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONObject;

/** On-demand, unauthenticated checks of the stable version manifest on GitHub Raw. */
final class ReleaseUpdateChecker implements AutoCloseable {
    enum Status { AVAILABLE, CURRENT, LOCAL_NEWER, MANIFEST_UNAVAILABLE, RESTRICTED, NETWORK_ERROR, INVALID_RESPONSE }

    static final class Result {
        final Status status;
        final String tag;
        final UpdatePackage apk;
        Result(Status status, String tag) { this(status, tag, null); }
        Result(Status status, String tag, UpdatePackage apk) { this.status = status; this.tag = tag; this.apk = apk; }
        String releaseUrl() {
            // Construct the trusted repository URL; never launch a manifest-supplied URL.
            return tag.isEmpty() ? ProjectInfo.RELEASES : ProjectInfo.RELEASES + "/tag/" + tag;
        }
    }

    interface Callback { void complete(Result result); }
    interface Fetcher {
        Response fetch() throws IOException;
        default void cancel() { }
    }
    static final class Response {
        final int code;
        final String body;
        Response(int code, String body) { this.code = code; this.body = body; }
    }

    private final Fetcher fetcher;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean closed, pending;

    ReleaseUpdateChecker() { this(new RawManifestFetcher()); }
    ReleaseUpdateChecker(Context context) {
        this(new RawManifestFetcher(() -> UpdateNetwork.proxyEnabled(context), url -> (HttpURLConnection) url.openConnection()));
    }
    ReleaseUpdateChecker(Fetcher fetcher) { this.fetcher = fetcher; }

    synchronized boolean check(String installedTag, Callback callback) {
        if (closed || pending) return false;
        pending = true;
        worker.execute(() -> {
            Result result;
            try { result = evaluate(fetcher.fetch(), installedTag, BuildConfig.VERSION_CODE); }
            catch (IOException failure) { result = new Result(Status.NETWORK_ERROR, ""); }
            final Result completed = result;
            main.post(() -> {
                synchronized (ReleaseUpdateChecker.this) {
                    if (closed) return;
                    pending = false;
                }
                callback.complete(completed);
            });
        });
        return true;
    }

    @Override public synchronized void close() {
        closed = true;
        main.removeCallbacksAndMessages(null);
        fetcher.cancel();
        worker.shutdownNow();
    }

    static Result evaluate(Response response, String installedTag, int installedCode) {
        if (response.code == 404) return new Result(Status.MANIFEST_UNAVAILABLE, "");
        if (response.code == 403 || response.code == 429) return new Result(Status.RESTRICTED, "");
        if (response.code != 200) return new Result(Status.NETWORK_ERROR, "");
        try {
            JSONObject manifest = new JSONObject(response.body);
            Object schema = manifest.get("schemaVersion"), code = manifest.get("versionCode");
            Object name = manifest.get("versionName"), value = manifest.get("tag");
            if (!(schema instanceof Integer) || (Integer) schema != 1
                    || !(code instanceof Integer) || (Integer) code <= 0 || (Integer) code > 2100000000
                    || !(name instanceof String) || !(value instanceof String)) {
                return new Result(Status.INVALID_RESPONSE, "");
            }
            String tag = (String) value;
            Version latest = Version.parse(tag), installed = Version.parse(installedTag);
            if (latest == null || installed == null || installedCode <= 0
                    || !tag.equals("v" + name) || latest.prerelease.length != 0
                    || !tag.matches("v[0-9]+\\.[0-9]+\\.[0-9]+")) {
                return new Result(Status.INVALID_RESPONSE, "");
            }
            int order = latest.compareTo(installed);
            int remoteCode = (Integer) code;
            UpdatePackage apk = UpdatePackage.parse(manifest, tag, remoteCode);
            if (order < 0 || remoteCode < installedCode) return new Result(Status.LOCAL_NEWER, tag);
            if (order > 0 && remoteCode == installedCode) return new Result(Status.INVALID_RESPONSE, "");
            return new Result(remoteCode > installedCode ? Status.AVAILABLE : Status.CURRENT, tag, apk);
        } catch (JSONException failure) {
            return new Result(Status.INVALID_RESPONSE, "");
        }
    }

    static final class Version implements Comparable<Version> {
        private static final Pattern FORMAT = Pattern.compile(
                "^[vV]?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"
                + "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$");
        private final BigInteger[] numbers;
        private final String[] prerelease;
        private Version(Matcher match) {
            numbers = new BigInteger[]{new BigInteger(match.group(1)), new BigInteger(match.group(2)), new BigInteger(match.group(3))};
            prerelease = match.group(4) == null ? new String[0] : match.group(4).split("\\.");
        }
        static Version parse(String value) {
            if (value == null || value.length() > 128) return null;
            Matcher match = FORMAT.matcher(value);
            if (!match.matches()) return null;
            Version version = new Version(match);
            for (String part : version.prerelease) {
                if (part.matches("[0-9]+") && part.length() > 1 && part.charAt(0) == '0') return null;
            }
            return version;
        }
        @Override public int compareTo(Version other) {
            for (int i = 0; i < numbers.length; i++) {
                int order = numbers[i].compareTo(other.numbers[i]);
                if (order != 0) return order;
            }
            if (prerelease.length == 0 || other.prerelease.length == 0) {
                return Integer.compare(other.prerelease.length, prerelease.length);
            }
            for (int i = 0; i < Math.min(prerelease.length, other.prerelease.length); i++) {
                String left = prerelease[i], right = other.prerelease[i];
                boolean leftNumeric = left.matches("[0-9]+"), rightNumeric = right.matches("[0-9]+");
                int order = leftNumeric && rightNumeric ? new BigInteger(left).compareTo(new BigInteger(right))
                        : leftNumeric != rightNumeric ? (leftNumeric ? -1 : 1) : left.compareTo(right);
                if (order != 0) return order;
            }
            return Integer.compare(prerelease.length, other.prerelease.length);
        }
    }

    static final class RawManifestFetcher implements Fetcher {
        interface ConnectionFactory { HttpURLConnection open(URL url) throws IOException; }
        private final ConnectionFactory connections;
        private final BooleanSupplier proxyEnabled;
        private HttpURLConnection active;
        private boolean cancelled;
        RawManifestFetcher() { this(url -> (HttpURLConnection) url.openConnection()); }
        RawManifestFetcher(ConnectionFactory connections) { this(() -> false, connections); }
        RawManifestFetcher(BooleanSupplier proxyEnabled, ConnectionFactory connections) {
            this.proxyEnabled = proxyEnabled; this.connections = connections;
        }
        @Override public Response fetch() throws IOException {
            HttpURLConnection connection = connections.open(UpdateNetwork.route(
                    java.net.URI.create(ProjectInfo.VERSION_MANIFEST), proxyEnabled.getAsBoolean()).toURL());
            synchronized (this) {
                if (cancelled) { connection.disconnect(); throw new IOException("Cancelled"); }
                active = connection;
            }
            try {
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setRequestProperty("Accept", "application/json, text/plain");
                connection.setRequestProperty("User-Agent", "MRSS/" + BuildConfig.VERSION_NAME);
                int code = connection.getResponseCode();
                if (code != 200) return new Response(code, "");
                try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096];
                    int size;
                    while ((size = input.read(buffer)) != -1) {
                        if (output.size() + size > 256 * 1024) throw new IOException("Version manifest too large");
                        output.write(buffer, 0, size);
                    }
                    return new Response(code, new String(output.toByteArray(), StandardCharsets.UTF_8));
                }
            } finally {
                connection.disconnect();
                synchronized (this) { active = null; }
            }
        }
        @Override public synchronized void cancel() {
            cancelled = true;
            if (active != null) active.disconnect();
        }
    }
}
