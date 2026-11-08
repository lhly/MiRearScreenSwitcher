package top.zainzh.rearscreen.platform.actions

import android.app.Activity
import android.content.Intent

internal sealed interface PickerCommit<out T> {
    data object Cancelled : PickerCommit<Nothing>
    data class Saved<T>(val value: T) : PickerCommit<T>
}

/** A cancelled/empty system selection never calls the persistence function. */
internal fun <T> commitPickerResult(resultCode: Int, data: Intent?, save: (Intent) -> T): PickerCommit<T> {
    if (resultCode != Activity.RESULT_OK || data == null) return PickerCommit.Cancelled
    return PickerCommit.Saved(save(data))
}
