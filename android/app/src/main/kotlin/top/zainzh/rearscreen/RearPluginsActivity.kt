package top.zainzh.rearscreen

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/** Legacy entry point forwards to the shared Modules tab. */
class RearPluginsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_NAVIGATION_TARGET, 2)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
        finish()
    }
}
