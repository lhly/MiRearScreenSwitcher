package top.zainzh.rearscreen.platform.clock

import android.os.SystemClock
import java.util.TimeZone

/** Snapshot values are sampled together on demand; they are not a persistent deadline source. */
data class ClockSnapshot(
    /** UTC Unix epoch milliseconds. Subject to wall-clock corrections. */
    val wallTimeMs: Long,
    /** Milliseconds since boot, monotonic and including deep sleep. */
    val elapsedRealtimeMs: Long,
    /** IANA timezone identifier used by the device at sampling time. */
    val timezoneId: String,
    /** No stable boot identifier is exposed by this provider. */
    val bootId: String? = null,
)

fun interface ClockSnapshotSource { fun snapshot(): ClockSnapshot }

/** Android source deliberately uses elapsedRealtime, not uptimeMillis or wall time. */
class AndroidClockSnapshotSource : ClockSnapshotSource {
    override fun snapshot() = ClockSnapshot(
        wallTimeMs = System.currentTimeMillis(),
        elapsedRealtimeMs = SystemClock.elapsedRealtime(),
        timezoneId = TimeZone.getDefault().id,
    )
}
