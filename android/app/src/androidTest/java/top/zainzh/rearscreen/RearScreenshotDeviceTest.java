package top.zainzh.rearscreen;

import android.app.Instrumentation;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.io.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Opt-in physical rear-display test: real Shizuku fd, all frames and physical rotations. */
public class RearScreenshotDeviceTest {
    private final Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    private final Context context=inst.getTargetContext();
    private String shell(String command) throws Exception {
        try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(inst.getUiAutomation().executeShellCommand(command));ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toString("UTF-8").trim();
        }
    }
    private void artifact(String name,Bitmap bitmap)throws Exception{
        try(OutputStream out=new FileOutputStream(new File(context.getFilesDir(),"screenshot-device-"+name+".png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}
    }
    @Test public void shizukuCaptureTemplatesAndFourPhysicalRotations() throws Exception {
        assertTrue("A supported physical rear display is required",RearShellRepository.supported());
        assertTrue("Shizuku must be running",rikka.shizuku.Shizuku.pingBinder());
        assertEquals("Authorize the debug app in Shizuku",android.content.pm.PackageManager.PERMISSION_GRANTED,rikka.shizuku.Shizuku.checkSelfPermission());
        String originalRotation=shell("wm user-rotation -d 1");
        CountDownLatch ready=new CountDownLatch(1);ITaskService[] api=new ITaskService[1];
        ServiceConnection connection=new ServiceConnection(){
            public void onServiceConnected(ComponentName n,IBinder b){api[0]=ITaskService.Stub.asInterface(b);ready.countDown();}
            public void onServiceDisconnected(ComponentName n){api[0]=null;}
        };
        TaskServiceManager.acquire(context,connection);
        try {
            assertTrue("Shizuku connection timeout",ready.await(8,TimeUnit.SECONDS));
            for(int rotation=0;rotation<4;rotation++){
                shell("wm user-rotation -d 1 lock "+rotation);Thread.sleep(700);
                int[] meta=new int[3];Bitmap raw;
                try(ParcelFileDescriptor descriptor=api[0].captureRearScreenshot(meta);InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(descriptor)){
                    raw=BitmapFactory.decodeStream(input);
                }
                assertNotNull(raw);assertEquals(rotation,meta[0]);assertEquals(meta[1],raw.getWidth());assertEquals(meta[2],raw.getHeight());
                artifact("raw-"+rotation,raw);
                try {
                    for(String color:rotation==0?new String[]{"BK","WH","PU","LG"}:new String[]{"LG"}){
                        for(String style:new String[]{"full","half"})try(RearShellRepository.Asset asset=RearShellRepository.load(context,style,color)){
                            Bitmap framed=RearScreenshotPipeline.compose(raw,rotation,asset);
                            try {
                                assertEquals(asset.geometry.outputWidth,framed.getWidth());assertEquals(asset.geometry.outputHeight,framed.getHeight());
                                assertTrue(framed.hasAlpha());
                                artifact(color+"-"+style+"-"+rotation,framed);
                                if(rotation==0&&"LG".equals(color)&&"full".equals(style)){
                                    Uri saved=RearScreenshotPipeline.save(context,api[0],framed,true);
                                    try(InputStream in=context.getContentResolver().openInputStream(saved)){
                                        Bitmap read=BitmapFactory.decodeStream(in);assertNotNull(read);assertEquals(1210,read.getWidth());assertEquals(2510,read.getHeight());read.recycle();
                                    }finally{context.getContentResolver().delete(saved,null,null);}
                                }
                            }finally{framed.recycle();}
                        }
                    }
                }finally{raw.recycle();}
            }
        } finally {
            TaskServiceManager.release(connection);
            shell("wm user-rotation -d 1 "+originalRotation);
        }
    }
}
