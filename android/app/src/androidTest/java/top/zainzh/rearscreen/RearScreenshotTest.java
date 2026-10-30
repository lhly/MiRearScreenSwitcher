package top.zainzh.rearscreen;

import android.content.Context;
import android.database.Cursor;
import android.graphics.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.Bundle;
import android.provider.MediaStore;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import java.io.*;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.Assert.*;

/** Pixel/rotation, media transactions and fallback checks against the real Android framework. */
public class RearScreenshotTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private Set<Uri> before;
    @Before public void snapshotMedia() { before = media(); }
    @After public void cleanMedia() {
        Set<Uri> created = media(); created.removeAll(before);
        for (Uri uri : created) context.getContentResolver().delete(uri, null, null);
    }
    private Set<Uri> media() {
        Set<Uri> result = new HashSet<>();
        Bundle query = new Bundle();
        query.putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "owner_package_name=? AND relative_path=?");
        query.putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                new String[]{context.getPackageName(), "Pictures/RearDisplay/"});
        query.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);
        try (Cursor c = context.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{"_id"}, query, null)) {
            if (c != null) while (c.moveToNext()) result.add(android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)));
        }
        return result;
    }
    private Bitmap pattern() {
        Bitmap b = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888);
        b.setPixels(new int[]{Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.CYAN, Color.MAGENTA, Color.WHITE, Color.BLACK}, 0, 4, 0, 0, 4, 2);
        return b;
    }
    private RearShellRepository.Asset asset() {
        Bitmap overlay = Bitmap.createBitmap(6, 4, Bitmap.Config.ARGB_8888);
        overlay.setPixel(0, 0, Color.GRAY);
        return new RearShellRepository.Asset(overlay, new RearShellGeometry(4, 2, 1, 1, 1, 1));
    }
    @Test public void geometryRejectsInvalidAndExcessiveCanvases() {
        double[][] invalid = {{Double.NaN, 2, 1, 1, 1, 1}, {4, 0, 1, 1, 1, 1}, {4, 2, -1, 1, 1, 1}, {8192,8192,0,0,0,0}};
        for (double[] v : invalid) {
            try { new RearShellGeometry(v[0],v[1],v[2],v[3],v[4],v[5]); fail("Invalid geometry accepted"); }
            catch (IllegalArgumentException expected) { }
        }
        RearShellGeometry actual = new RearShellGeometry(976, 596, 122, 108, 112, 1806);
        assertEquals(1210, actual.outputWidth); assertEquals(2510, actual.outputHeight);
        assertTrue(actual.fits(976,596)); assertFalse(actual.fits(596,976));
    }
    @Test public void compositeKeepsScreenPixelsShellLayerAndTransparencyForAllRotations() throws Exception {
        Bitmap base = pattern();
        try (RearShellRepository.Asset asset = asset()) {
            for (int rotation = 0; rotation < 4; rotation++) {
                Matrix m = new Matrix(); m.postRotate(rotation * 90f);
                Bitmap raw = Bitmap.createBitmap(base,0,0,4,2,m,false);
                Bitmap output = RearScreenshotPipeline.compose(raw,rotation,asset);
                assertEquals(6,output.getWidth()); assertEquals(4,output.getHeight());
                assertEquals(Color.GRAY,output.getPixel(0,0)); assertEquals(0,Color.alpha(output.getPixel(5,3)));
                for(int y=0;y<2;y++) for(int x=0;x<4;x++) assertEquals("rotation="+rotation,base.getPixel(x,y),output.getPixel(x+1,y+1));
                output.recycle(); if(raw!=base)raw.recycle();
            }
            Bitmap mismatch = Bitmap.createBitmap(3,3,Bitmap.Config.ARGB_8888);
            try { RearScreenshotPipeline.compose(mismatch,0,asset); fail("Wrong aspect ratio accepted"); }
            catch(IOException expected) { } finally { mismatch.recycle(); }
        } finally { base.recycle(); }
    }
    @Test public void publishCreatesReadableOwnedPngAndFailedWriteLeavesNoRow() throws Exception {
        Bitmap b = pattern();
        Uri uri = RearScreenshotPipeline.save(context,null,b,true);
        try (Cursor c=context.getContentResolver().query(uri,new String[]{"is_pending","mime_type","relative_path"},null,null,null)) {
            assertNotNull(c); assertTrue(c.moveToFirst()); assertEquals(0,c.getInt(0)); assertEquals("image/png",c.getString(1));
            assertEquals("Pictures/RearDisplay/",c.getString(2));
        }
        try(InputStream input=context.getContentResolver().openInputStream(uri)) {
            Bitmap read=BitmapFactory.decodeStream(input); assertNotNull(read); assertEquals(Color.MAGENTA,read.getPixel(1,1));read.recycle();
        }
        b.recycle(); Set<Uri> saved=media();
        try { RearScreenshotPipeline.save(context,null,b,true); fail("Recycled bitmap exported"); }
        catch(Exception expected) { }
        assertEquals(saved,media());
    }
    @Test public void shellFailurePreservesOriginalAndNeverRecapturesLater() throws Exception {
        File source=File.createTempFile("capture-test", ".png",context.getCacheDir());
        Bitmap b=pattern(); try(OutputStream out=new FileOutputStream(source)){b.compress(Bitmap.CompressFormat.PNG,100,out);} b.recycle();
        int[] captures={0};
        ITaskService service=(ITaskService) Proxy.newProxyInstance(ITaskService.class.getClassLoader(),new Class[]{ITaskService.class},(proxy,method,args)->{
            if("captureRearScreenshot".equals(method.getName())) {
                captures[0]++;int[] meta=(int[])args[0];meta[0]=0;meta[1]=4;meta[2]=2;
                return ParcelFileDescriptor.open(source,ParcelFileDescriptor.MODE_READ_ONLY);
            }
            throw new UnsupportedOperationException(method.getName());
        });
        try {
            RearScreenshotPipeline.Result result=RearScreenshotPipeline.capture(context,service,
                    new RearScreenshotPreferences.Options(true,false,"full","BK"),()->{},(style,color)->{
                        assertEquals(1,captures[0]);throw new IOException("Offline fixture");
                    });
            assertEquals(RearScreenshotPipeline.Result.ORIGINAL_FALLBACK,result);assertEquals(1,captures[0]);
            Set<Uri> created=media();created.removeAll(before);assertEquals(1,created.size());
            try(InputStream in=context.getContentResolver().openInputStream(created.iterator().next())) {
                Bitmap read=BitmapFactory.decodeStream(in);assertNotNull(read);assertEquals(4,read.getWidth());assertEquals(Color.RED,read.getPixel(0,0));read.recycle();
            }
        } finally { assertTrue(source.delete()); }
    }
    @Test public void successfulShellAndOptionalOriginalAreSeparateImages() throws Exception {
        File source=File.createTempFile("capture-test", ".png",context.getCacheDir());
        Bitmap b=pattern();try(OutputStream out=new FileOutputStream(source)){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();
        ITaskService service=(ITaskService)Proxy.newProxyInstance(ITaskService.class.getClassLoader(),new Class[]{ITaskService.class},(proxy,method,args)->{
            if("captureRearScreenshot".equals(method.getName())) {int[] meta=(int[])args[0];meta[0]=0;meta[1]=4;meta[2]=2;return ParcelFileDescriptor.open(source,ParcelFileDescriptor.MODE_READ_ONLY);}
            throw new UnsupportedOperationException(method.getName());
        });
        try {
            assertEquals(RearScreenshotPipeline.Result.SHELL_AND_ORIGINAL,RearScreenshotPipeline.capture(context,service,
                    new RearScreenshotPreferences.Options(true,true,"full","BK"),()->{},(style,color)->asset()));
            Set<Uri> created=media();created.removeAll(before);assertEquals(2,created.size());
            Set<Integer> widths=new HashSet<>();
            for(Uri uri:created)try(InputStream in=context.getContentResolver().openInputStream(uri)){
                Bitmap read=BitmapFactory.decodeStream(in);widths.add(read.getWidth());read.recycle();
            }
            assertEquals(new HashSet<>(Arrays.asList(4,6)),widths);
        } finally {assertTrue(source.delete());}
    }
}
