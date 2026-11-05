package com.notiregx.app.ui

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

private val colors = ConcurrentHashMap<String, Int>()

/**
 * The colour that stands out in an app's icon (Messenger blue, Snapchat yellow, Instagram
 * purple-pink): the average of its strongly coloured pixels, or the icon's average if it's grey.
 */
fun appColor(context: Context, packageName: String): Int = colors.getOrPut(packageName) {
    val bitmap = try {
        context.packageManager.getApplicationIcon(packageName).toBitmap(48, 48)
    } catch (e: PackageManager.NameNotFoundException) {
        return@getOrPut AndroidColor.GRAY
    }
    var r = 0L; var g = 0L; var b = 0L; var n = 0
    var ar = 0L; var ag = 0L; var ab = 0L; var an = 0
    val hsv = FloatArray(3)
    for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
        val p = bitmap.getPixel(x, y)
        if (AndroidColor.alpha(p) < 200) continue
        ar += AndroidColor.red(p); ag += AndroidColor.green(p); ab += AndroidColor.blue(p); an++
        AndroidColor.colorToHSV(p, hsv)
        if (hsv[1] > 0.45f && hsv[2] > 0.35f) {
            r += AndroidColor.red(p); g += AndroidColor.green(p); b += AndroidColor.blue(p); n++
        }
    }
    when {
        n > 20 -> AndroidColor.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
        an > 0 -> AndroidColor.rgb((ar / an).toInt(), (ag / an).toInt(), (ab / an).toInt())
        else -> AndroidColor.GRAY
    }
}

/**
 * One bar split into a segment per app, each in the app's icon colour and as long as its share.
 * [max] is the total of the biggest bar on screen, so bars compare across rows.
 */
@Composable
fun AppStackedBar(segments: List<Pair<String, Long>>, max: Long, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val key = segments.joinToString { it.first }
    val palette by produceState(emptyMap<String, Int>(), key) {
        value = withContext(Dispatchers.Default) { segments.associate { it.first to appColor(context, it.first) } }
    }
    val total = segments.sumOf { it.second }
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(track)) {
        if (total <= 0 || max <= 0) return@Box
        Row(Modifier.fillMaxWidth(total.toFloat() / max).fillMaxHeight()) {
            segments.filter { it.second > 0 }.forEach { (pkg, count) ->
                Box(
                    Modifier
                        .weight(count.toFloat())
                        .fillMaxHeight()
                        .background(palette[pkg]?.let { Color(it) } ?: MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}
