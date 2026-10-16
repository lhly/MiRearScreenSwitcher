package top.zainzh.rearscreen;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.core.content.FileProvider;
import java.io.File;

/** Grants Android's installer read access only to a verified, private APK. */
final class UpdateInstaller {
    static Intent intent(Context context, File file) {
        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".updates", file);
        Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri("ReMRSS update", uri));
        return intent;
    }
}
