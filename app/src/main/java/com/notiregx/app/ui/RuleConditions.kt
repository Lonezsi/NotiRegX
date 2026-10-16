package com.notiregx.app.ui

import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.notiregx.app.model.Burst
import com.notiregx.app.model.Schedule
import com.notiregx.app.model.formatMinute

/** Optional conditions on a rule: active hours, burst limit, and (for bundles) digest times. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RuleConditions(
    schedule: Schedule?,
    onSchedule: (Schedule?) -> Unit,
    burst: Burst?,
    onBurst: (Burst?) -> Unit,
    isBundle: Boolean,
    deliveryTimes: List<Int>,
    onDeliveryTimes: (List<Int>) -> Unit,
) {
    val context = LocalContext.current
    fun pickTime(initial: Int, onPicked: (Int) -> Unit) {
        TimePickerDialog(
            context,
            { _, hour, minute -> onPicked(hour * 60 + minute) },
            initial / 60, initial % 60,
            DateFormat.is24HourFormat(context),
        ).show()
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SwitchRow(
            title = "Only at certain times",
            hint = schedule?.summary ?: "Active all the time.",
            checked = schedule != null,
            onChecked = { onSchedule(if (it) Schedule() else null) },
        )
        if (schedule != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Schedule.DAY_ORDER.forEach { day ->
                    FilterChip(
                        selected = day in schedule.days,
                        onClick = {
                            val days = if (day in schedule.days) schedule.days - day else schedule.days + day
                            if (days.isNotEmpty()) onSchedule(schedule.copy(days = days))
                        },
                        label = { Text(Schedule.DAY_NAMES.getValue(day)) },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickTime(schedule.startMinute) { onSchedule(schedule.copy(startMinute = it)) } }) {
                    Text("From ${formatMinute(schedule.startMinute)}")
                }
                OutlinedButton(onClick = { pickTime(schedule.endMinute) { onSchedule(schedule.copy(endMinute = it)) } }) {
                    Text("To ${formatMinute(schedule.endMinute)}")
                }
            }
            Hint("A range that ends before it starts runs overnight. Same start and end means all day.")
        }

        SwitchRow(
            title = "Only during bursts",
            hint = burst?.let { "The first ${it.count} within ${it.windowMinutes} min get through; the rest are caught." }
                ?: "Applies to every matching notification.",
            checked = burst != null,
            onChecked = { onBurst(if (it) Burst() else null) },
        )
        if (burst != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Let through", burst.count, Modifier.width(140.dp)) { onBurst(burst.copy(count = it)) }
                NumberField("Within (min)", burst.windowMinutes, Modifier.width(140.dp)) { onBurst(burst.copy(windowMinutes = it)) }
            }
        }

        if (isBundle) {
            SwitchRow(
                title = "Deliver as a digest",
                hint = if (deliveryTimes.isEmpty()) "The bundle shows up as soon as something is caught."
                else "The bundle stays hidden and shows up at these times.",
                checked = deliveryTimes.isNotEmpty(),
                onChecked = { onDeliveryTimes(if (it) listOf(12 * 60, 18 * 60) else emptyList()) },
            )
            if (deliveryTimes.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    deliveryTimes.sorted().forEach { time ->
                        InputChip(
                            selected = false,
                            onClick = { pickTime(time) { new -> onDeliveryTimes((deliveryTimes - time + new).distinct()) } },
                            label = { Text(formatMinute(time)) },
                            trailingIcon = {
                                if (deliveryTimes.size > 1) {
                                    Icon(
                                        Icons.Default.Clear,
                                        contentDescription = "Remove ${formatMinute(time)}",
                                        modifier = Modifier.clickable { onDeliveryTimes(deliveryTimes - time) },
                                    )
                                }
                            },
                        )
                    }
                    AssistChip(
                        onClick = { pickTime(9 * 60) { onDeliveryTimes((deliveryTimes + it).distinct()) } },
                        label = { Text("Add time") },
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, hint: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Hint(hint)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun NumberField(label: String, value: Int, modifier: Modifier, onValue: (Int) -> Unit) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { text -> text.filter(Char::isDigit).take(4).toIntOrNull()?.takeIf { it > 0 }?.let(onValue) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
