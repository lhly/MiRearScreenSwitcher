package top.zainzh.rearscreen;

import java.io.File;
import java.io.FileOutputStream;

/** Read only the probe's own movie through the selected service; shell storage access can differ. */
final class RootRecordingValidation {
    static void copyVideo(ITaskService service, String video, File destination) throws Exception {
        String quoted = PrivilegedShellArguments.quote(video);
        long size = Long.parseLong(service.executeShellCommandWithResult("stat -c '%s' " + quoted).trim());
        if (size <= 0 || size > 16 * 1024 * 1024) throw new java.io.IOException("Invalid probe movie size: " + size);
        long written = 0;
        try (FileOutputStream output = new FileOutputStream(destination)) {
            for (int block = 0; written < size; block++) {
                // Keep every Binder reply comfortably below its transaction limit.
                String encoded = service.executeShellCommandWithResult("dd if=" + quoted + " bs=49152 skip=" + block + " count=1 2>/dev/null | base64");
                byte[] bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT);
                if (bytes.length == 0 || bytes.length > 49152 || written + bytes.length > size)
                    throw new java.io.IOException("Truncated probe movie at " + written);
                output.write(bytes); written += bytes.length;
            }
        }
    }
    private RootRecordingValidation() {}
}
