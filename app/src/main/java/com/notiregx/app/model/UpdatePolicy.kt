package com.notiregx.app.model

import org.json.JSONObject

/** A published GitHub release: its version and where its APK can be downloaded. */
data class Release(val version: String, val apkUrl: String)

/** Rules for self-updating: when to look, whether a release is newer, and reading GitHub's answer. */
object UpdatePolicy {
    const val RELEASES_URL = "https://api.github.com/repos/Lonezsi/NotiRegX/releases/latest"
    const val APK_NAME = "NotiRegX.apk"
    const val CHECK_EVERY_MS = 12 * 60 * 60_000L

    /** True when release [tag] (like "v1.2.0") is a higher version than [current] (like "1.1.0"). */
    fun isNewer(tag: String, current: String): Boolean {
        val a = parts(tag) ?: return false
        val b = parts(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parts(version: String): List<Int>? =
        version.trim().removePrefix("v").split(".").map { it.toIntOrNull() ?: return null }

    fun shouldCheck(lastCheckMs: Long, nowMs: Long): Boolean = nowMs - lastCheckMs >= CHECK_EVERY_MS

    /** Reads GitHub's "latest release" answer; null if it has no [APK_NAME] asset. */
    fun parseRelease(json: String): Release? {
        val o = JSONObject(json)
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name") == APK_NAME) return Release(o.getString("tag_name"), a.getString("browser_download_url"))
        }
        return null
    }
}
