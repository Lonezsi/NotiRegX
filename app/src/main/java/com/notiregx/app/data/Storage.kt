package com.notiregx.app.data

import android.util.AtomicFile
import android.util.Log
import com.notiregx.app.model.Action
import com.notiregx.app.model.Bundle
import com.notiregx.app.model.Burst
import com.notiregx.app.model.LoggedNotification
import com.notiregx.app.model.MatchField
import com.notiregx.app.model.Notification
import com.notiregx.app.model.Rule
import com.notiregx.app.model.RuleType
import com.notiregx.app.model.Schedule
import com.notiregx.app.model.VibrationEvent
import com.notiregx.app.model.VibrationSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/** Persists rules (with histories and bundles), the Recent log and the vibration log as JSON files. */
class Storage(dir: File) {
    private val rulesFile = File(dir, "rules.json")
    private val recentFile = File(dir, "recent.json")
    private val vibrationsFile = File(dir, "vibrations.json")
    private val writer = Executors.newSingleThreadExecutor()

    fun loadRules(): List<Rule> = read(rulesFile, "rules", ::ruleFromJson)

    fun saveRules(rules: List<Rule>) = write(rulesFile, "rules", rules.map { ruleToJson(it, includeData = true) })

    /** Rule configurations (no history) as a shareable JSON document. */
    fun exportRules(rules: List<Rule>): String =
        JSONObject().put("app", "NotiRegX").put("version", 1)
            .put("rules", JSONArray(rules.map { ruleToJson(it, includeData = false) }))
            .toString(2)

    /** Parses an exported document; throws if it isn't one. */
    fun importRules(json: String): List<Rule> =
        JSONObject(json).getJSONArray("rules").objects().map(::ruleFromJson)

    fun loadRecent(): List<LoggedNotification> = read(recentFile, "recent", ::loggedFromJson)

    fun saveRecent(recent: List<LoggedNotification>) = write(recentFile, "recent", recent.map(::loggedToJson))

    fun loadVibrations(): List<VibrationEvent> = read(vibrationsFile, "vibrations", ::vibrationFromJson)

    fun saveVibrations(events: List<VibrationEvent>) = write(vibrationsFile, "vibrations", events.map(::vibrationToJson))

    private fun <T> read(file: File, key: String, parse: (JSONObject) -> T): List<T> {
        if (!file.exists()) return emptyList()
        return try {
            val root = JSONObject(String(AtomicFile(file).readFully(), Charsets.UTF_8))
            root.getJSONArray(key).objects().map(parse)
        } catch (e: Exception) {
            Log.e(TAG, "Could not read ${file.name}; keeping a copy and starting empty", e)
            file.copyTo(File(file.path + ".corrupt"), overwrite = true)
            emptyList()
        }
    }

    private fun write(file: File, key: String, items: List<JSONObject>) = writer.execute {
        val bytes = JSONObject().put("version", 1).put(key, JSONArray(items))
            .toString().toByteArray(Charsets.UTF_8)
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(bytes)
            atomic.finishWrite(out)
        } catch (e: IOException) {
            atomic.failWrite(out)
            Log.e(TAG, "Could not save ${file.name}", e)
        }
    }

    private fun ruleToJson(r: Rule, includeData: Boolean): JSONObject = JSONObject()
        .put("id", r.id)
        .put("name", r.name)
        .put("regex", r.regex)
        .put("field", r.field.name)
        .put("packageName", r.packageName ?: JSONObject.NULL)
        .put("appName", r.appName ?: JSONObject.NULL)
        .put("invert", r.invert)
        .put("type", r.type.name)
        .put("description", r.description)
        .put("enabled", r.enabled)
        .put("debuzz", r.debuzz)
        .put("schedule", r.schedule?.let(::scheduleToJson) ?: JSONObject.NULL)
        .put("burst", r.burst?.let { JSONObject().put("count", it.count).put("windowMinutes", it.windowMinutes) } ?: JSONObject.NULL)
        .put("deliveryTimes", JSONArray(r.deliveryTimes))
        .apply {
            if (includeData) {
                put("createdAt", r.createdAt)
                put("hits", JSONArray(r.hits))
                put("history", JSONArray(r.history.map(::notificationToJson)))
                put("unseen", JSONArray(r.bundle.unseen))
                put("released", r.bundle.released)
            }
        }

    private fun scheduleToJson(s: Schedule) = JSONObject()
        .put("days", JSONArray(s.days.sorted()))
        .put("start", s.startMinute)
        .put("end", s.endMinute)

    private fun scheduleFromJson(o: JSONObject) = Schedule(
        days = o.getJSONArray("days").ints().toSet(),
        startMinute = o.getInt("start"),
        endMinute = o.getInt("end"),
    )

    private fun ruleFromJson(o: JSONObject) = Rule(
        id = o.getString("id"),
        name = o.getString("name"),
        regex = o.optString("regex"),
        field = enumOr(o.optString("field"), MatchField.ANY),
        packageName = o.stringOrNull("packageName"),
        appName = o.stringOrNull("appName"),
        invert = o.optBoolean("invert"),
        type = enumOr(o.optString("type"), RuleType.HIDE),
        description = o.optString("description"),
        enabled = o.optBoolean("enabled", true),
        debuzz = o.optBoolean("debuzz"),
        schedule = o.optJSONObject("schedule")?.let(::scheduleFromJson),
        burst = o.optJSONObject("burst")?.let { Burst(it.getInt("count"), it.getInt("windowMinutes")) },
        deliveryTimes = o.optJSONArray("deliveryTimes")?.ints().orEmpty(),
        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
        hits = o.optJSONArray("hits")?.longs().orEmpty(),
        history = o.optJSONArray("history")?.objects()?.map(::notificationFromJson).orEmpty(),
        bundle = Bundle(o.optJSONArray("unseen")?.longs().orEmpty(), o.optBoolean("released")),
    )

    private fun notificationToJson(n: Notification) = JSONObject()
        .put("id", n.id)
        .put("packageName", n.packageName)
        .put("appName", n.appName)
        .put("title", n.title)
        .put("text", n.text)
        .put("postedAt", n.postedAt)
        .put("url", n.url ?: JSONObject.NULL)

    private fun notificationFromJson(o: JSONObject) = Notification(
        id = o.getLong("id"),
        packageName = o.getString("packageName"),
        appName = o.optString("appName"),
        title = o.optString("title"),
        text = o.optString("text"),
        postedAt = o.optLong("postedAt"),
        url = o.stringOrNull("url"),
    )

    private fun loggedToJson(l: LoggedNotification) = JSONObject()
        .put("notification", notificationToJson(l.notification))
        .put("action", l.action.name)
        .put("ruleName", l.ruleName ?: JSONObject.NULL)
        .put("filtering", l.filtering)

    private fun loggedFromJson(o: JSONObject) = LoggedNotification(
        notification = notificationFromJson(o.getJSONObject("notification")),
        action = enumOr(o.optString("action"), Action.PASS),
        ruleName = o.stringOrNull("ruleName"),
        filtering = o.optBoolean("filtering", true),
    )

    private fun vibrationToJson(v: VibrationEvent) = JSONObject()
        .put("time", v.time)
        .put("packageName", v.packageName)
        .put("appName", v.appName)
        .put("source", v.source.name)
        .put("detail", v.detail)
        .put("usage", v.usage ?: JSONObject.NULL)
        .put("durationMs", v.durationMs ?: JSONObject.NULL)
        .put("viaNotification", v.viaNotification)

    private fun vibrationFromJson(o: JSONObject) = VibrationEvent(
        time = o.getLong("time"),
        packageName = o.getString("packageName"),
        appName = o.optString("appName"),
        source = enumOr(o.optString("source"), VibrationSource.NOTIFICATION),
        detail = o.optString("detail"),
        usage = o.stringOrNull("usage"),
        durationMs = if (o.isNull("durationMs")) null else o.optLong("durationMs"),
        // Older logs didn't store this; the reader labelled those "Notification".
        viaNotification = o.optBoolean("viaNotification", o.optString("detail") == "Notification"),
    )

    private inline fun <reified E : Enum<E>> enumOr(name: String, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    private fun JSONArray.ints() = (0 until length()).map { getInt(it) }

    private fun JSONArray.longs() = (0 until length()).map { getLong(it) }

    private fun JSONObject.stringOrNull(key: String) = if (!has(key) || isNull(key)) null else getString(key)

    private companion object {
        const val TAG = "NotiRegX.Storage"
    }
}
