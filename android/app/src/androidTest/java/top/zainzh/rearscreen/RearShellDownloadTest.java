package top.zainzh.rearscreen;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
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
            String[] devices={"pandora","popsicle","hongkong","madrid"};
            int[][] sizes={{904,572},{976,596},{912,596},{976,596}};
            for(int d=0;d<devices.length;d++) for(String color:RearShellCatalog.colors(devices[d])) {
            String device=devices[d];
            try(RearShellRepository.Asset full=RearShellRepository.loadForDevice(isolated,device,"full",color)){
                assertEquals(sizes[d][0],full.geometry.width,0);assertEquals(sizes[d][1],full.geometry.height,0);
                assertTrue(full.bitmap.hasAlpha());
                verifyCompositions(base,full,device,color,"full",sizes[d][0],sizes[d][1]);
            }
            File pack=new File(root,"rear_shells/"+RearShellRepository.resourceId(device,color));
            assertTrue(new File(pack,"full_shell.png").isFile());assertTrue(new File(pack,"half_shell.png").isFile());
            long modified=new File(pack,"half_shell.png").lastModified();
            try(RearShellRepository.Asset half=RearShellRepository.loadForDevice(isolated,device,"half",color)){
                assertEquals(sizes[d][0],half.geometry.width,0);assertEquals(sizes[d][1],half.geometry.height,0);
                verifyCompositions(base,half,device,color,"half",sizes[d][0],sizes[d][1]);
            }
            assertEquals(modified,new File(pack,"half_shell.png").lastModified());
            android.util.Log.i("RearShellDownloadTest",device+"/"+color+" full+half, four rotations and cached reuse passed");
            }
        }finally{delete(root);}
    }

    private void verifyCompositions(Context context,RearShellRepository.Asset asset,String device,String color,String style,int width,int height) throws Exception {
        Bitmap raw=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(raw);Paint paint=new Paint();
        paint.setColor(Color.RED);canvas.drawRect(0,0,width/2f,height,paint);
        paint.setColor(Color.GREEN);canvas.drawRect(width/2f,0,width,height/2f,paint);
        paint.setColor(Color.BLUE);canvas.drawRect(width/2f,height/2f,width,height,paint);
        try {
            for(int rotation=0;rotation<4;rotation++) {
                Matrix matrix=new Matrix();matrix.postRotate(90f*rotation);
                Bitmap rotated=rotation==0?raw:Bitmap.createBitmap(raw,0,0,width,height,matrix,true);
                try {
                    Bitmap result=RearScreenshotPipeline.compose(rotated,rotation,asset);
                    try {
                        assertEquals(asset.geometry.outputWidth,result.getWidth());assertEquals(asset.geometry.outputHeight,result.getHeight());
                        int x=Math.round(asset.geometry.left+width*.75f);
                        int upper=result.getPixel(x,Math.round(asset.geometry.top+height*.25f));
                        int lower=result.getPixel(x,Math.round(asset.geometry.top+height*.75f));
                        assertTrue(device+" "+style+" upper quadrant",Color.green(upper)>Color.red(upper)+80 && Color.green(upper)>Color.blue(upper)+80);
                        assertTrue(device+" "+style+" lower quadrant",Color.blue(lower)>Color.red(lower)+80 && Color.blue(lower)>Color.green(lower)+80);
                        if(rotation==0 && ("CY".equals(color)||"BK".equals(color))) {
                            try(java.io.OutputStream output=context.openFileOutput("shell-"+device+"-"+color+"-"+style+".png",0)) {
                                result.compress(Bitmap.CompressFormat.PNG,100,output);
                            }
                        }
                    } finally {result.recycle();}
                } finally {if(rotated!=raw)rotated.recycle();}
            }
        } finally {raw.recycle();}
    }
}
