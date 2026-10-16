package com.notiregx.app.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.notiregx.app.R
import com.notiregx.app.model.Rule
import com.notiregx.app.model.RuleAdvice
import com.notiregx.app.model.RuleType
import com.notiregx.app.model.SuggestionGroup

/** "You keep swiping these away" — a rule NotiRegX proposes from what the user dismisses unread. */
@Composable
fun AdviceCard(advice: RuleAdvice, onUse: () -> Unit, onIgnore: () -> Unit) {
    val s = advice.suggestion
    val resources = LocalContext.current.resources
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(advice.packageName, size = 28.dp)
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.advice_title, advice.appName), style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (s.group == SuggestionGroup.EVERYTHING) stringResource(R.string.suggestion_everything, advice.appName) else s.text(),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                if (advice.opened == 0) {
                    resources.getQuantityString(R.plurals.advice_dismissed_never_opened, advice.dismissed, advice.dismissed)
                } else {
                    resources.getQuantityString(R.plurals.advice_dismissed_opened_some, advice.dismissed, advice.dismissed, advice.opened)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            s.examples.firstOrNull()?.let { e ->
                Text(
                    stringResource(
                        R.string.advice_example,
                        listOf(e.title, e.text).filter { it.isNotEmpty() }.joinToString(": ").replace('\n', ' '),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onIgnore) { Text(stringResource(R.string.advice_not_now)) }
                Spacer(Modifier.size(8.dp))
                Button(onClick = onUse) { Text(stringResource(R.string.advice_create_rule)) }
            }
        }
    }
}

/** The rule an accepted suggestion starts as; the editor opens on it so it can still be adjusted. */
fun ruleFromAdvice(context: Context, advice: RuleAdvice): Rule {
    val s = advice.suggestion
    val label = if (s.group == SuggestionGroup.EVERYTHING) context.getString(R.string.advice_rule_name_everything) else context.suggestionText(s)
    return Rule(
        name = "${advice.appName}: $label".take(60),
        regex = s.regex,
        field = s.field,
        invert = s.invert,
        packageName = advice.packageName,
        appName = advice.appName,
        type = RuleType.HIDE,
    )
}
