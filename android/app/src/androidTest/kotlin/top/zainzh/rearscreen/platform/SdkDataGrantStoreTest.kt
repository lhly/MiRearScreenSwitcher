package top.zainzh.rearscreen.platform

import android.content.pm.PackageManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.zainzh.rearscreen.platform.data.DataField
import java.security.MessageDigest

class SdkDataGrantStoreTest {
    @Test fun consentPersistsAcrossBinderEpochButIsRevocableByRequestedCategory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uid = context.packageManager.getApplicationInfo(context.packageName, 0).uid
        val info = if (Build.VERSION.SDK_INT >= 28) context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        else @Suppress("DEPRECATION") context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        val certificate = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        else @Suppress("DEPRECATION") info.signatures?.firstOrNull()?.toByteArray()
        val digest = requireNotNull(certificate).let { MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { b -> "%02X".format(b) } }
        fun owner(epoch: Long) = SessionOwner(uid, context.packageName, digest,
            SessionPrincipal.AndroidClient(uid, context.packageName, digest, epoch))

        val store = SdkDataGrantStore(context)
        val battery = setOf(DataField.BATTERY_LEVEL_PERCENT, DataField.BATTERY_CHARGING)
        store.updateConsent(owner(1), battery, battery)
        assertTrue(store.grants(owner(2)).contains(SdkDataGrantStore.DATA_BATTERY))
        store.updateConsent(owner(2), battery, emptySet())
        assertEquals(emptySet<String>(), store.grants(owner(3)))
    }
}
