package top.zainzh.rearscreen.platform.ipc

import org.json.JSONObject

/** rear.info dimensions intentionally preserve the distinction between display mode and logical size. */
internal data class RearDisplayDimensions(
    val physicalModeWidthPx: Int?,
    val physicalModeHeightPx: Int?,
    val logicalWidthPx: Int,
    val logicalHeightPx: Int,
    val densityDpi: Int,
    /** Current Display.rotation quarter-turn constant, independent of the mode's native orientation. */
    val rotation: Int
) {
    init {
        require((physicalModeWidthPx == null) == (physicalModeHeightPx == null))
        require(physicalModeWidthPx == null || (physicalModeWidthPx > 0 && physicalModeHeightPx!! > 0))
        require(logicalWidthPx > 0 && logicalHeightPx > 0 && densityDpi > 0 && rotation in 0..3)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("physicalModePx", if (physicalModeWidthPx == null) JSONObject.NULL else JSONObject()
            .put("width", physicalModeWidthPx).put("height", physicalModeHeightPx))
        .put("logicalDisplayPx", JSONObject().put("width", logicalWidthPx).put("height", logicalHeightPx))
        .put("densityDpi", densityDpi)
        .put("rotation", rotation)
}
