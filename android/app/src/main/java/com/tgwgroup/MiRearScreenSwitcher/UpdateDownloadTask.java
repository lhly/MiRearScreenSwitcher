package com.tgwgroup.MiRearScreenSwitcher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One user-initiated transfer, retained by the update dialog's ViewModel. */
final class UpdateDownloadTask implements AutoCloseable {
    enum Phase { DOWNLOADING, VERIFYING, READY, ERROR, CANCELLED }
    enum Error { NETWORK, STORAGE, SIZE, HASH, PACKAGE, VERSION, SIGNATURE }
    static final class Failure extends IOException {
        final Error error;
        Failure(Error error) { super(error.name()); this.error = error; }
    }
    static final class State {
        final Phase phase; final long bytes; final File file; final Error error;
        State(Phase phase, long bytes, File file, Error error) { this.phase = phase; this.bytes = bytes; this.file = file; this.error = error; }
    }
    interface Listener { void changed(State state); }
    interface Connections { HttpURLConnection open(URL url) throws IOException; }
    interface Verifier { void verify(File file, UpdatePackage update) throws IOException; }

    final UpdatePackage update;
    private final File directory;
    private final Connections connections;
    private final Verifier verifier;
    private final boolean proxy;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile State state = new State(Phase.DOWNLOADING, 0, null, null);
    private volatile boolean closed;
    private Listener listener;
    private HttpURLConnection active;

    UpdateDownloadTask(Context context, UpdatePackage update) {
        this(context, update, UpdateNetwork.proxyEnabled(context), url -> (HttpURLConnection) url.openConnection(),
                (file, data) -> UpdateApkVerifier.verifyPackage(context.getApplicationContext(), file, data));
    }

    UpdateDownloadTask(Context context, UpdatePackage update, Connections connections, Verifier verifier) {
        this(context, update, false, connections, verifier);
    }

    UpdateDownloadTask(Context context, UpdatePackage update, boolean proxy, Connections connections, Verifier verifier) {
        this.update = update; this.proxy = proxy; this.connections = connections; this.verifier = verifier;
        directory = new File(context.getCacheDir(), "updates");
        worker.execute(this::download);
    }

    State state() { return state; }
    void attach(Listener listener) { this.listener = listener; listener.changed(state); }
    void detach() { listener = null; }

    private void publish(Phase phase, long bytes, File file, Error error) {
        if (closed) return;
        state = new State(phase, bytes, file, error);
        main.post(() -> { if (!closed && listener != null) listener.changed(state); });
    }

    private synchronized void activate(HttpURLConnection connection) throws IOException {
        if (closed) { connection.disconnect(); throw new IOException("Cancelled"); }
        active = connection;
    }

    private void checkCancelled() throws IOException {
        if (closed || Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
    }

    private HttpURLConnection openDownload() throws IOException {
        URI next;
        if (!UpdatePackage.validDownloadUrl(update.url, update.tag)) throw new Failure(Error.NETWORK);
        try { next = URI.create(update.url); } catch (IllegalArgumentException invalid) { throw new Failure(Error.NETWORK); }
        for (int redirects = 0; redirects <= 5; redirects++) {
            checkCancelled();
            if (!UpdatePackage.allowedRedirect(next)) throw new Failure(Error.NETWORK);
            URI request = UpdateNetwork.route(next, proxy);
            HttpURLConnection connection = connections.open(request.toURL());
            activate(connection);
            connection.setConnectTimeout(10000); connection.setReadTimeout(20000);
            connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", "MRSS/" + BuildConfig.VERSION_NAME);
            connection.setRequestProperty("Accept-Encoding", "identity");
            int code = connection.getResponseCode();
            if (code == 200) return connection;
            String location = connection.getHeaderField("Location");
            connection.disconnect();
            if ((code != 301 && code != 302 && code != 303 && code != 307 && code != 308) || location == null) {
                throw new Failure(Error.NETWORK);
            }
            try { next = UpdateNetwork.redirect(next, location, proxy); }
            catch (IllegalArgumentException invalid) { throw new Failure(Error.NETWORK); }
        }
        throw new Failure(Error.NETWORK);
    }

    private void download() {
        File partial = null, ready = null;
        try {
            if (update.size <= 0 || update.size > UpdatePackage.MAX_SIZE) throw new Failure(Error.SIZE);
            if (!directory.isDirectory() && !directory.mkdirs()) throw new Failure(Error.STORAGE);
            // Retain completed APKs while an installer may still hold their URI. Expire old cache later.
            File[] old = directory.listFiles();
            if (old != null) for (File file : old) {
                if (System.currentTimeMillis() - file.lastModified() > 24L * 60 * 60 * 1000) file.delete();
            }
            try { partial = File.createTempFile("mrss-", ".part", directory); }
            catch (IOException failed) { throw new Failure(Error.STORAGE); }
            HttpURLConnection connection = openDownload();
            long length = connection.getContentLengthLong();
            if (length >= 0 && length != update.size) throw new Failure(Error.SIZE);
            long received = 0, lastProgress = 0, started = SystemClock.elapsedRealtime();
            FileOutputStream output;
            try { output = new FileOutputStream(partial); } catch (IOException failed) { throw new Failure(Error.STORAGE); }
            try (FileOutputStream sink = output; InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[32768]; int count;
                while ((count = input.read(buffer)) != -1) {
                    checkCancelled();
                    if (SystemClock.elapsedRealtime() - started > 10 * 60 * 1000) throw new Failure(Error.NETWORK);
                    received += count;
                    if (received > update.size) throw new Failure(Error.SIZE);
                    try { sink.write(buffer, 0, count); } catch (IOException failed) { throw new Failure(Error.STORAGE); }
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastProgress >= 100 || received == update.size) {
                        publish(Phase.DOWNLOADING, received, null, null); lastProgress = now;
                    }
                }
            }
            if (received != update.size) throw new Failure(Error.SIZE);
            checkCancelled();
            publish(Phase.VERIFYING, received, null, null);
            // Hash validation remains mandatory even when tests inject package identity inspection.
            UpdateApkVerifier.verifyHash(partial, update);
            verifier.verify(partial, update);
            checkCancelled();
            ready = new File(directory, partial.getName().replace(".part", ".apk"));
            if (!partial.renameTo(ready)) throw new Failure(Error.STORAGE);
            publish(Phase.READY, received, ready, null);
        } catch (IOException | SecurityException failure) {
            if (ready != null) ready.delete();
            if (!closed) publish(Phase.ERROR, state.bytes, null,
                    failure instanceof Failure ? ((Failure) failure).error : Error.NETWORK);
        } finally {
            if (partial != null) partial.delete();
            synchronized (this) { if (active != null) active.disconnect(); active = null; }
        }
    }

    synchronized void verifyAgain() {
        if (closed || state.phase != Phase.READY) return;
        File file = state.file;
        publish(Phase.VERIFYING, update.size, file, null);
        worker.execute(() -> {
            try {
                UpdateApkVerifier.verifyHash(file, update); verifier.verify(file, update);
                publish(Phase.READY, update.size, file, null);
            } catch (IOException failure) {
                file.delete();
                publish(Phase.ERROR, 0, null, failure instanceof Failure ? ((Failure) failure).error : Error.STORAGE);
            }
        });
    }

    @Override public synchronized void close() {
        closed = true; listener = null;
        if (active != null) active.disconnect();
        main.removeCallbacksAndMessages(null); worker.shutdownNow();
    }
}
