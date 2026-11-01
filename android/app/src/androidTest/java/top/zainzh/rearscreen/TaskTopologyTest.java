package top.zainzh.rearscreen;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class TaskTopologyTest {
    @Test public void childTaskIsMovedThroughItsOwningRoot() {
        String stacks = "RootTask id=8 bounds=[0,0][2400,1080] displayId=0 userId=0\n"
                + "  taskId=1042: test.game/.LandscapeGame bounds=[0,0][2400,1080]\n"
                + "RootTask id=42 bounds=[0,0][976,596] displayId=1 userId=0\n"
                + "  taskId=2042: test.other/.Main bounds=[0,0][976,596]\n";
        assertEquals(8, RearSystemBridge.rootTask(stacks, 1042));
        assertEquals(42, RearSystemBridge.rootTask(stacks, 2042));
        assertEquals(-1, RearSystemBridge.rootTask(stacks, 42));
        assertEquals(-1, RearSystemBridge.rootTask(stacks, 999));
    }
    @Test public void legacyStackHeaderAndWhitespaceRemainSupported() {
        String stacks = "  Stack id=9 bounds=[0,0][2400,1080] displayId=0\n"
                + "    taskId=71: test.game/.LandscapeGame\n";
        assertEquals(9, RearSystemBridge.rootTask(stacks, 71));
    }
}
