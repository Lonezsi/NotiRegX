package com.notiregx.app.ui

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.notiregx.app.R

/** Languages the app is translated into, as language tags with their own names (not translated). */
private val LANGUAGES = listOf(
    "en" to "English",
    "hu" to "Magyar",
    "de" to "Deutsch",
    "es" to "Español",
)

/**
 * "Language" row for Settings. Uses Android's per-app language (13+), which also appears under
 * Settings › Apps › NotiRegX › Language; older Android versions follow the system language.
 */
@Composable
fun LanguageSetting() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    val current = remember { appLanguage(context) }

    Row(Modifier.clickable { picking = true }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.language_title), style = MaterialTheme.typography.bodyLarge)
            Text(
                LANGUAGES.firstOrNull { it.first == current }?.second ?: stringResource(R.string.language_system),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { picking = true }) { Text(stringResource(R.string.language_change)) }
    }

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(stringResource(R.string.language_title)) },
            text = {
                Column {
                    (listOf(null to stringResource(R.string.language_system)) + LANGUAGES).forEach { (tag, name) ->
                        Row(
                            Modifier.clickable { setAppLanguage(context, tag); picking = false },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = current == tag, onClick = null)
                            Text(name, Modifier.weight(1f))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** The language picked for this app, or null for "follow the system". */
private fun appLanguage(context: Context): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
    return if (locales.isEmpty) null else locales[0].language
}

/** Switches the app's language; Android recreates the activity in the new language. */
private fun setAppLanguage(context: Context, tag: String?) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    context.getSystemService(LocaleManager::class.java).applicationLocales =
        if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
}
