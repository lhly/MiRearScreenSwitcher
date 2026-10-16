package com.tgwgroup.MiRearScreenSwitcher

import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.blur

/** API-33 shader/backdrop references stay in this file, outside the legacy rendering path. */
@RequiresApi(33)
@Composable
internal fun GlassHomeFrame(
    dark: Boolean,
    content: @Composable (Modifier) -> Unit,
    navigation: @Composable BoxScope.(Modifier) -> Unit,
) {
    val backdrop = rememberLayerBackdrop()
    val tint = if (dark) Color(0xFF25252B).copy(alpha = .72f) else Color(0xFFF8FAFF).copy(alpha = .64f)
    Box(Modifier.fillMaxSize()) {
        content(Modifier.layerBackdrop(backdrop))
        navigation(Modifier.drawBackdrop(backdrop, shape = { RoundedCornerShape(32.dp) }, effects = { blur(22.dp.toPx()) }, onDrawSurface = { drawRect(tint) }))
    }
}
