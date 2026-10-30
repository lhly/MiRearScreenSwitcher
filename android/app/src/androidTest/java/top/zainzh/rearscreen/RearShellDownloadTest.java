package top.zainzh.rearscreen;

import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.io.File;
import static org.junit.Assert.*;

/** Explicit network integration test, separate from deterministic compositor tests. */
public class RearShellDownloadTest {
    private static void delete(File file) {
        File[] children=file.listFiles();if(children!=null)for(File child:children)delete(child);file.delete();
    }
    @Test public void publicResourceDownloadsAndCachesBothStyles() throws Exception {
        Context base=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root=File.createTempFile("shell-download-test-","",base.getCacheDir());
        assertTrue(root.delete());assertTrue(root.mkdirs());
        Context isolated=new ContextWrapper(base){@Override public File getFilesDir(){return root;}};
        try {
            for(String color:new String[]{"BK","WH","PU","LG"}) {
            try(RearShellRepository.Asset full=RearShellRepository.loadForDevice(isolated,"popsicle","full",color)){
                assertEquals(1210,full.geometry.outputWidth);assertEquals(2510,full.geometry.outputHeight);assertTrue(full.bitmap.hasAlpha());
            }
            File pack=new File(root,"rear_shells/"+RearShellRepository.resourceId("popsicle",color));
            assertTrue(new File(pack,"full_shell.png").isFile());assertTrue(new File(pack,"half_shell.png").isFile());
            long modified=new File(pack,"half_shell.png").lastModified();
            try(RearShellRepository.Asset half=RearShellRepository.loadForDevice(isolated,"popsicle","half",color)){
                assertEquals(1214,half.geometry.outputWidth);assertEquals(2630,half.geometry.outputHeight);
            }
            assertEquals(modified,new File(pack,"half_shell.png").lastModified());
            }
        }finally{delete(root);}
    }
}
