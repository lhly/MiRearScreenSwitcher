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
    void ensure(String pkg) throws Exception { ensureAll(new String[]{pkg}); }
    private static List<String> members(Properties p, String key) {
        String value = p.getProperty(key, p.getProperty("package", ""));
        List<String> result = new ArrayList<>();
        for (String pkg : value.split(",")) { validatePackage(pkg); result.add(pkg); }
        return result;
    }
    void ensureAll(String[] packages) throws Exception {
        if (packages.length < 1 || packages.length > 2) throw new IllegalArgumentException("Invalid freeze package count");
        for (String pkg : packages) validatePackage(pkg);
        String group = String.join(",", packages);
        if (saved != null && !group.equals(String.join(",", members(saved,"packages"))))
            throw new IOException("Stale freeze record must be restored first");
        String current = store.read();
        boolean all = true;
        for (String pkg : packages) if (!contains(current,pkg)) all = false;
        if (all) return;
        String original = current;
        Set<String> added = new LinkedHashSet<>();
        if (saved != null) {
            for (String pkg : members(saved,"added")) { added.add(pkg); if (contains(original,pkg)) original = without(original,pkg); }
            if (Objects.equals(current,saved.getProperty("applied"))) original=saved.getProperty("original");
        }
        String applied = current;
        for (String pkg : packages) if (!contains(applied,pkg)) {
            added.add(pkg); applied = applied == null || applied.isEmpty() ? pkg : applied + ", " + pkg;
        }
        Properties next = new Properties(); next.setProperty("package",packages[0]); next.setProperty("packages",group);
        next.setProperty("added",String.join(",",added));
        if (original != null) next.setProperty("original",original);
        next.setProperty("applied",applied);
        File temporary = new File(journal.getParentFile(),"pending.tmp");
        try (FileOutputStream out = new FileOutputStream(temporary)) { next.store(out,"ReMRSS temporary cast freeze protection"); out.getFD().sync(); }
        if (!temporary.renameTo(journal)) throw new IOException("Freeze recovery journal unavailable");
        saved=next; writeVerified(applied);
    }
    void restore() throws Exception {
        if (saved == null) return;
        String current=store.read(), original=saved.getProperty("original");
        if (Objects.equals(current,saved.getProperty("applied"))) writeVerified(original);
        else {
            String retained=current;
            for (String pkg : members(saved,"added")) if (contains(retained,pkg) && !contains(original,pkg)) retained=without(retained,pkg);
            if (!Objects.equals(current,retained)) writeVerified(retained);
        }
        if (journal.exists()&&!journal.delete()) throw new IOException("Freeze recovery record could not be cleared");
        saved=null;
    }
    private void writeVerified(String value) throws Exception {
        store.write(value);
        if (!Objects.equals(value, store.read())) throw new IOException("Freeze whitelist write was not applied");
    }
    @Override public void close() throws IOException {
        try { lock.release(); } finally { lockFile.close(); }
    }
}
