package top.zainzh.rearscreen;

import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;

/** Migrate only known recovery files, holding the same lock as the live shell watchdog. */
final class RearFreezeDirectory {
    static void prepareShellOwner(File directory) throws Exception {
        if (android.os.Process.myUid() != 0 || !directory.exists()) return;
        StructStat stat = Os.lstat(directory.getPath());
        if (!OsConstants.S_ISDIR(stat.st_mode) || stat.st_uid != 0 && stat.st_uid != 2000)
            throw new IOException("Unexpected freeze directory owner/type");
        if (stat.st_uid == 2000) return;
        File owner = new File(directory, "owner.lock");
        if (owner.exists() && !OsConstants.S_ISREG(Os.lstat(owner.getPath()).st_mode))
            throw new IOException("Invalid freeze lock");
        try (RandomAccessFile file = new RandomAccessFile(owner, "rw"); FileLock lock = file.getChannel().tryLock()) {
            if (lock == null) throw new IOException("Active freeze watchdog owns recovery");
            for (String name : new String[]{"owner.lock", "pending.properties", "pending.tmp"}) {
                File entry = new File(directory, name);
                if (!entry.exists()) continue;
                StructStat item = Os.lstat(entry.getPath());
                if (!OsConstants.S_ISREG(item.st_mode) || item.st_uid != 0 && item.st_uid != 2000)
                    throw new IOException("Unexpected freeze recovery file");
                Os.chown(entry.getPath(), 2000, 2000);
                Os.chmod(entry.getPath(), 0600);
            }
            Os.chown(directory.getPath(), 2000, 2000);
            Os.chmod(directory.getPath(), 0700);
        }
    }
}
