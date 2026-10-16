package com.notiregx.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class VibrationTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val now = 1_760_000_000_000L // 2025-10-09

    @Test
    fun parsesSingleLineRecords() {
        val dump = """
            VibratorManagerService:
              Recent vibrations:
                createTime: 10-08 21:15:03.120, startTime: 10-08 21:15:03.125, durationMs: 300, status: FINISHED, callerInfo: {uid=1000, opPkg=android, usage=NOTIFICATION, reason=Notification (com.whatsapp 10234) }
                createTime: 10-08 21:16:00.000, durationMs: 15, status: FINISHED, callerInfo: {uid=10111, opPkg=com.keyboard, usage=TOUCH, reason=keypress}
                createTime: 10-08 21:17:42.500, durationMs: 500, status: FINISHED, callerInfo: {uid=10222, opPkg=com.ghost.app, usage=UNKNOWN, reason=sync done}
                createTime: 10-08 21:18:00.000, durationMs: 500, status: IGNORED_FOR_SETTINGS, callerInfo: {uid=10222, opPkg=com.ghost.app, usage=ALARM, reason=x}
        """.trimIndent()
        val v = VibrationDumpParser.parse(dump, now, utc)
        assertEquals(2, v.size)

        val whatsapp = v[0]
        assertEquals("com.whatsapp", whatsapp.packageName)
        assertTrue(whatsapp.viaNotification)
        assertEquals("NOTIFICATION", whatsapp.usage)
        assertEquals(300L, whatsapp.durationMs)
        assertEquals(1759958103120L, whatsapp.time) // 2025-10-08 21:15:03.120 UTC

        val ghost = v[1]
        assertEquals("com.ghost.app", ghost.packageName)
        assertFalse(ghost.viaNotification)
        assertEquals("sync done", ghost.reason)
    }

    @Test
    fun joinsIndentedContinuationLines() {
        val dump = """
              Previous vibrations:
                2025-10-08 09:00:00.000
                  uid: 10300, opPkg: com.fitness
                  usage: ALARM, durationMs: 1000
                2025-10-08 09:05:00.000
                  uid: 10301
        """.trimIndent()
        val v = VibrationDumpParser.parse(dump, now, utc)
        assertEquals(2, v.size)
        assertEquals("com.fitness", v[0].packageName)
        assertEquals("ALARM", v[0].usage)
        assertEquals(1000L, v[0].durationMs)
        assertNull(v[1].packageName)
        assertEquals(10301, v[1].uid)
    }

    /** Captured from a Galaxy A56 on One UI 8.5 (Android 16). */
    @Test
    fun parsesOneUi8PipeFormat() {
        val dump = """
              Recent vibrations:
                UNKNOWN:
                  10-08 12:32:21.320 |   effect |             finished | duration:   288ms | start: 12:32:21.326 | end: 12:32:21.609 | scale:      NONE (1.00) | adaptiveScale=1.00 | flags:    0 | usage: UNKNOWN | com.android.systemui (uid=10051, deviceId=0) | reason: null

                ALARM:
                  10-08 12:06:13.358 |   effect | cancelled_superseded | duration:    42ms | start: 12:06:13.363 | end: 12:06:13.400 | scale:      NONE (1.00) | adaptiveScale=1.00 | flags:    0 | usage: ALARM | hu.diverzum.diverzummobile (uid=10342, deviceId=0) | reason:
                  10-08 12:06:13.380 |   effect | cancelled_superseded | duration:     9ms | start:              | end: 12:06:13.390 | scale:      NONE (1.00) | adaptiveScale=1.00 | flags:    0 | usage: ALARM | hu.diverzum.diverzummobile (uid=10342, deviceId=0) | reason:
                  10-08 12:06:13.661 |   effect |             finished | duration:   139ms | start: 12:06:13.692 | end: 12:06:13.800 | scale:      NONE (1.00) | adaptiveScale=1.00 | flags:    0 | usage: ALARM | hu.diverzum.diverzummobile (uid=10342, deviceId=0) | reason:

                TOUCH:
                  10-08 14:37:24.888 |   effect |             finished | duration:   245ms | start: 14:37:24.892 | end: 14:37:25.133 | scale:      NONE (1.00) | adaptiveScale=1.00 | flags:    0 | usage: TOUCH | com.android.settings (uid=1000, deviceId=0) | reason: perform
                NOTIFICATION:
                  10-08 21:59:03.841 |   effect |             finished | duration:   134ms | start: 21:59:03.848 | end: 21:59:03.975 | scale:      NONE (1.00) | adaptiveScale=1.00 | flags:    0 | usage: NOTIFICATION | android (uid=1000, deviceId=0) | reason: Notification (com.facebook.orca 10375) (Delayed) | played: [Step=0ms(amplitude=0.00
                IME:
                  10-08 22:00:00.000 |   effect |             finished | duration:    10ms | start: 22:00:00.001 | end: 22:00:00.011 | scale:      NONE (1.00) | adaptiveScale=1.00 | flags:    0 | usage: IME | com.samsung.android.honeyboard (uid=10100, deviceId=0) | reason: null
              Aggregated vibration history:
                ALARM:
                  10-08 12:06:13.661 |   effect |             finished | duration:   139ms | start: 12:06:13.692 | end: 12:06:13.800 | scale:      NONE (1.00) | adaptiveScale=1.00 | flags:    0 | usage: ALARM | hu.diverzum.diverzummobile (uid=10342, deviceId=0) | reason:
        """.trimIndent()
        val v = VibrationDumpParser.parse(dump, now, utc)
        assertEquals(listOf("hu.diverzum.diverzummobile", "com.android.systemui", "com.facebook.orca"), v.map { it.packageName })

        val burst = v[0]
        assertEquals("ALARM", burst.usage)
        assertEquals(181L, burst.durationMs) // 42 + 139 merged; the never-started 9 ms piece is dropped
        assertNull(burst.reason)

        assertEquals(288L, v[1].durationMs)
        assertNull(v[1].reason)
        assertTrue(v[2].viaNotification)
    }

    @Test
    fun yearlessDatesInTheFutureBelongToLastYear() {
        val v = VibrationDumpParser.parse("12-31 23:00:00.000 uid=1 opPkg=a", now, utc)
        assertEquals(2024, Calendar.getInstance(utc).apply { timeInMillis = v[0].time }.get(Calendar.YEAR))
    }

    private fun ev(time: Long, pkg: String, source: VibrationSource) =
        VibrationEvent(time, pkg, pkg, source, detail = "")

    @Test
    fun timelinePairsLoggedVibrationsWithNotifications() {
        val events = listOf(
            ev(10_000, "chat", VibrationSource.SYSTEM),
            ev(10_400, "chat", VibrationSource.NOTIFICATION),
            ev(50_000, "ghost", VibrationSource.SYSTEM),
            ev(90_000, "mail", VibrationSource.NOTIFICATION),
        )
        val rows = VibrationLog.timeline(events)
        assertEquals(listOf("mail", "ghost", "chat"), rows.map { it.event.packageName })
        assertFalse(rows[0].isGhost) // unconfirmed estimate
        assertTrue(rows[1].isGhost)
        assertEquals(10_400L, rows[2].notification?.time)
    }

    @Test
    fun logDedupesAndKeepsNewestFirst() {
        val log = VibrationLog()
        log.add(listOf(ev(1, "a", VibrationSource.SYSTEM), ev(3, "b", VibrationSource.SYSTEM)))
        log.add(listOf(ev(3, "b", VibrationSource.SYSTEM), ev(2, "c", VibrationSource.SYSTEM)))
        assertEquals(listOf(3L, 2L, 1L), log.events.value.map { it.time })
    }
}
