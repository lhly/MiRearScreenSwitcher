package dev.remrss.sample

import dev.remrss.platform.sdk.RearWindowReport

/** Immutable key for the actual reported window geometry; revision alone is deliberately excluded. */
internal data class ViewportSignature(
    val taskId: Int,
    val displayId: Int,
    val physicalDisplayPx: Pair<Int, Int>?,
    val contentBoundsPx: List<Int>,
    val windowPx: Pair<Int, Int>,
    val windowDp: Pair<Float, Float>,
    val densityDpi: Int,
    val renderBufferPx: Pair<Int, Int>?,
    val displayRotation: Int,
    val contentRotation: Int,
    val inputTransform: List<Float>
) {
    companion object {
        fun from(report: RearWindowReport) = ViewportSignature(
            report.taskId, report.displayId, report.physicalDisplayPx,
            report.contentBoundsPx.toList(), report.windowPx, report.windowDp, report.densityDpi,
            report.renderBufferPx, report.displayRotation, report.contentRotation, report.inputTransform.toList()
        )
    }
}
