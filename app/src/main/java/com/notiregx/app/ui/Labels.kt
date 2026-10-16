package com.notiregx.app.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.notiregx.app.R
import com.notiregx.app.model.Action
import com.notiregx.app.model.Burst
import com.notiregx.app.model.LoggedNotification
import com.notiregx.app.model.MatchField
import com.notiregx.app.model.Schedule
import com.notiregx.app.model.Suggestion
import com.notiregx.app.model.SuggestionKind
import com.notiregx.app.model.formatMinute
import java.text.DateFormatSymbols

/** Phrases the model's structured descriptions in the app's current language. */

fun Context.suggestionText(s: Suggestion): String {
    val field = getString(if (s.field == MatchField.TEXT) R.string.field_text else R.string.field_title)
    return when (s.kind) {
        SuggestionKind.LIKE -> getString(R.string.suggestion_like, field, s.value)
        SuggestionKind.STARTS_WITH -> getString(R.string.suggestion_starts_with, field, s.value)
        SuggestionKind.ENDS_WITH -> getString(R.string.suggestion_ends_with, field, s.value)
        SuggestionKind.CONTAINS -> getString(R.string.suggestion_contains, field, s.value)
        SuggestionKind.IS -> getString(R.string.suggestion_is, field, s.value)
        SuggestionKind.MENTIONS -> getString(R.string.suggestion_mentions, s.value)
        SuggestionKind.EXCEPT_TITLE -> getString(R.string.suggestion_except_title, s.value)
        SuggestionKind.EXCEPT_TITLES_LIKE -> getString(R.string.suggestion_except_titles_like, s.value)
        SuggestionKind.EVERYTHING -> getString(R.string.suggestion_everything, s.value)
    }
}

fun Context.scheduleSummary(s: Schedule): String {
    val days = when (s.days) {
        Schedule.ALL_DAYS -> getString(R.string.schedule_every_day)
        Schedule.WEEKDAYS -> getString(R.string.schedule_weekdays)
        Schedule.WEEKEND -> getString(R.string.schedule_weekends)
        else -> Schedule.DAY_ORDER.filter { it in s.days }.joinToString(", ") { dayName(it) }
    }
    return if (s.startMinute == s.endMinute) getString(R.string.schedule_all_day, days)
    else getString(R.string.schedule_range, days, formatMinute(s.startMinute), formatMinute(s.endMinute))
}

/** Short weekday name ("Mon", "H", "Mo") for a Calendar.DAY_OF_WEEK value, in the app's language. */
fun Context.dayName(day: Int): String =
    DateFormatSymbols.getInstance(resources.configuration.locales[0]).shortWeekdays[day].trimEnd('.')

fun Context.burstSummary(b: Burst): String = getString(R.string.burst_summary, b.count, b.windowMinutes)

fun Context.outcomeLabel(l: LoggedNotification): String = when {
    !l.filtering -> getString(R.string.outcome_paused)
    l.ruleName == null -> getString(R.string.outcome_no_match)
    l.action == Action.HIDE -> getString(R.string.outcome_hidden, l.ruleName)
    l.action == Action.BUNDLE -> getString(R.string.outcome_bundled, l.ruleName)
    else -> getString(R.string.outcome_let_through, l.ruleName)
}

@Composable
fun Suggestion.text(): String = LocalContext.current.suggestionText(this)
