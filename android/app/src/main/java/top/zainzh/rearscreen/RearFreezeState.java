package top.zainzh.rearscreen;

import java.io.*;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.*;

/** Owns only the package entry added by this cast, never the user's battery policy. */
final class RearFreezeState implements AutoCloseable {
    interface Store {
        String read() throws Exception;
        void write(String value) throws Exception;
    }
    private final Store store;
    private final File journal;
    private final RandomAccessFile lockFile;
    private final FileLock lock;
    private Properties saved;

    RearFreezeState(Store store, File directory) throws Exception {
        this.store = store;
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Freeze recovery directory unavailable");
        directory.setReadable(false, false); directory.setReadable(true, true);
        directory.setWritable(false, false); directory.setWritable(true, true);
        directory.setExecutable(false, false); directory.setExecutable(true, true);
        journal = new File(directory, "pending.properties");
        lockFile = new RandomAccessFile(new File(directory, "owner.lock"), "rw");
        FileLock acquired;
        try { acquired = lockFile.getChannel().tryLock(); }
        catch (OverlappingFileLockException e) { acquired = null; }
        if (acquired == null) { lockFile.close(); throw new IOException("Another freeze guard owns recovery"); }
        lock = acquired;
        try {
            if (journal.isFile()) {
                saved = new Properties();
                try (InputStream in = new FileInputStream(journal)) { saved.load(in); }
                validatePackage(saved.getProperty("package"));
                if (!saved.containsKey("applied")) throw new IOException("Invalid freeze recovery record");
            }
        } catch (Exception e) { close(); throw e; }
    }
    static void validatePackage(String pkg) {
        if (pkg == null || !pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))
            throw new IllegalArgumentException("Invalid freeze guard package");
    }
    static boolean contains(String value, String pkg) {
        if (value != null) for (String member : value.split(",")) if (pkg.equals(member.trim())) return true;
        return false;
    }
    static String without(String value, String pkg) {
        List<String> remaining = new ArrayList<>();
        if (value != null) for (String member : value.split(",")) {
            if (!member.trim().isEmpty() && !pkg.equals(member.trim())) remaining.add(member.trim());
        }
        return String.join(", ", remaining);
    }
    void ensure(String pkg) throws Exception {
        validatePackage(pkg);
        if (saved != null && !pkg.equals(saved.getProperty("package"))) throw new IOException("Stale freeze record must be restored first");
        String current = store.read();
        if (contains(current, pkg)) return;
        // PowerKeeper may rebuild its mirror while casting. Preserve that newer baseline.
        Properties next = new Properties(); next.setProperty("package", pkg);
        if (current != null) next.setProperty("original", current);
        String applied = current == null || current.isEmpty() ? pkg : current + ", " + pkg;
        next.setProperty("applied", applied);
        File temporary = new File(journal.getParentFile(), "pending.tmp");
        try (FileOutputStream out = new FileOutputStream(temporary)) {
            next.store(out, "ReMRSS temporary cast freeze protection"); out.getFD().sync();
        }
        if (!temporary.renameTo(journal)) throw new IOException("Freeze recovery journal unavailable");
        saved = next; // Write-ahead record also covers a failed/ambiguous settings write.
        writeVerified(applied);
    }
    void restore() throws Exception {
        if (saved == null) return;
        String current = store.read(), pkg = saved.getProperty("package");
        String original = saved.getProperty("original");
        if (Objects.equals(current, saved.getProperty("applied"))) writeVerified(original);
        else if (contains(current, pkg) && !contains(original, pkg)) writeVerified(without(current, pkg));
        // Unrelated PowerKeeper/user changes are retained; an already removed entry needs no write.
        if (journal.exists() && !journal.delete()) throw new IOException("Freeze recovery record could not be cleared");
        saved = null;
    }
    private void writeVerified(String value) throws Exception {
        store.write(value);
        if (!Objects.equals(value, store.read())) throw new IOException("Freeze whitelist write was not applied");
    }
    @Override public void close() throws IOException {
        try { lock.release(); } finally { lockFile.close(); }
    }
}
