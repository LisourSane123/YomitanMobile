package com.yomitanmobile.ui.common

import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** AnkiDroid's read/write permission, as the manifest names it. */
const val ANKI_PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

/**
 * Runs an action that needs AnkiDroid, asking for the permission first if it
 * is missing and remembering what was being attempted.
 *
 * Four screens each had their own copy of this launcher — the detail screen,
 * both deck generators and the collection scan — which is four places to fix
 * whenever AnkiDroid changes how it grants access.
 *
 * @return a function to wrap any AnkiDroid call in.
 */
@Composable
fun rememberAnkiPermissionGate(): (() -> Unit) -> Unit {
    val context = LocalContext.current
    val deniedMessage = tr(
        "Bez uprawnienia do AnkiDroida nie da się przeczytać kolekcji ani dodać fiszek.",
        "Without AnkiDroid permission the collection cannot be read and no cards can be added."
    )
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = pending
        pending = null
        if (granted) {
            action?.invoke()
        } else {
            Toast.makeText(context, deniedMessage, Toast.LENGTH_LONG).show()
        }
    }

    return { action ->
        val granted = ContextCompat.checkSelfPermission(context, ANKI_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            action()
        } else {
            pending = action
            launcher.launch(ANKI_PERMISSION)
        }
    }
}
