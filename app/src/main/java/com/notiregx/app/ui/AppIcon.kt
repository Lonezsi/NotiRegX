package com.notiregx.app.ui

import android.content.pm.PackageManager
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val icons = LruCache<String, ImageBitmap>(128)

/** An app's launcher icon, loaded off the main thread and cached; blank space if unknown. */
@Composable
fun AppIcon(packageName: String?, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val icon by produceState(packageName?.let { icons.get(it) }, packageName) {
        if (value != null || packageName == null) return@produceState
        value = withContext(Dispatchers.IO) {
            try {
                val px = (size.value * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
                context.packageManager.getApplicationIcon(packageName).toBitmap(px, px).asImageBitmap()
                    .also { icons.put(packageName, it) }
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        }
    }
    val bitmap = icon
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = modifier.size(size))
    } else {
        Spacer(modifier.size(size))
    }
}
