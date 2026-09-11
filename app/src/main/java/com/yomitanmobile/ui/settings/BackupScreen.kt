package com.yomitanmobile.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yomitanmobile.ui.common.rememberTr
import java.io.File
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults

/**
 * Backups on their own screen.
 *
 * This was a 147-line section inside a 1500-line settings screen, with its own
 * state, its own file picker and its own confirmation dialog — the sort of
 * thing that makes a settings list impossible to read or change.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val tr = rememberTr()
    val context = LocalContext.current
    val backups by viewModel.backups.collectAsState()
    val isBackingUp by viewModel.isBackingUp.collectAsState()
    val isRestoring by viewModel.isRestoring.collectAsState()
    var includeSettingsInBackup by remember { mutableStateOf(true) }
    var selectedBackupForRestore by remember { mutableStateOf<File?>(null) }
    var showRestoreDialog by remember { mutableStateOf(false) }

    // JSON mime types vary by file manager (some report octet-stream or
    // text/plain for a .json), so the caller accepts all three.
    val settingsPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                val stream = context.contentResolver.openInputStream(it)
                if (stream != null) viewModel.importSettings(stream)
                else Toast.makeText(context, tr("Nie można otworzyć pliku", "Cannot open file"), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, tr("Błąd: ${e.message}", "Error: ${e.message}"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsEvent.BackupSuccess ->
                    Toast.makeText(context, tr("Kopia zapasowa utworzona", "Backup created"), Toast.LENGTH_LONG).show()
                is SettingsEvent.BackupError ->
                    Toast.makeText(context, tr("Błąd: ${event.message}", "Error: ${event.message}"), Toast.LENGTH_LONG).show()
                is SettingsEvent.RestoreSuccess -> {
                    showRestoreDialog = false
                    Toast.makeText(
                        context,
                        tr("Przywrócono — uruchom aplikację ponownie", "Restored — restart the app"),
                        Toast.LENGTH_LONG
                    ).show()
                }
                is SettingsEvent.RestoreError ->
                    Toast.makeText(context, tr("Błąd przywracania: ${event.message}", "Restore failed: ${event.message}"), Toast.LENGTH_LONG).show()
                else -> Unit
            }
        }
    }

    if (showRestoreDialog && selectedBackupForRestore != null) {
        val folder = selectedBackupForRestore!!
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            title = { Text(tr("Przywrócić kopię zapasową?", "Restore this backup?")) },
            text = {
                Text(
                    tr(
                        "Obecne słowniki i postęp zostaną zastąpione zawartością kopii " +
                            "z ${folder.name}. Aplikacja musi być zrestartowana po przywróceniu.",
                        "The dictionaries and progress you have now are replaced by the backup " +
                            "from ${folder.name}. The app has to be restarted afterwards."
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.restoreBackup(folder) },
                    enabled = !isRestoring
                ) { Text(tr("Przywróć", "Restore")) }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreDialog = false }, enabled = !isRestoring) {
                    Text(tr("Anuluj", "Cancel"))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("Kopia zapasowa", "Backup & Restore")) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = tr("Wróć", "Back"))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isBackingUp && !isRestoring) {
                            includeSettingsInBackup = !includeSettingsInBackup
                        }
                ) {
                    Checkbox(
                        checked = includeSettingsInBackup,
                        onCheckedChange = { includeSettingsInBackup = it },
                        enabled = !isBackingUp && !isRestoring
                    )
                    Text(
                        tr(
                            "Dołącz ustawienia (bez klucza AI)",
                            "Include settings (without AI key)"
                        ),
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                Button(
                    onClick = {
                        if (!isBackingUp) {
                            viewModel.createBackup(includeSettingsInBackup)
                        }
                    },
                    enabled = !isBackingUp && !isRestoring,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isBackingUp) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 1.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(tr("Utwórz kopię zapasową", "Create backup"))
                }
            }

            item {
                OutlinedButton(
                    onClick = {
                        settingsPickerLauncher.launch(
                            arrayOf("application/json", "application/octet-stream", "text/plain")
                        )
                    },
                    enabled = !isBackingUp && !isRestoring,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(tr("Importuj ustawienia z pliku", "Import settings from file"))
                }
                Text(
                    tr(
                        "Wybierz settings.json z folderu kopii zapasowej. Baza danych nie jest zmieniana; klucz AI nigdy nie jest przenoszony.",
                        "Pick a settings.json from a backup folder. The database is untouched; the AI key is never carried over."
                    ),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (backups.isNotEmpty()) {
                item {
                    Text(
                        tr("Dostępne kopie (${backups.size}):", "Available backups (${backups.size}):"),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                items(backups.size) { index ->
                    val backup = backups[index]
                    val timestamp = backup.name.replace("backup_", "")
                    
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    timestamp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    backup.absolutePath,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                            
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        selectedBackupForRestore = backup
                                        showRestoreDialog = true
                                    },
                                    enabled = !isRestoring,
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    if (isRestoring && selectedBackupForRestore == backup) {
                                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.dp)
                                    } else {
                                        Text(tr("Przywróć", "Restore"), fontSize = 11.sp)
                                    }
                                }
                                
                                OutlinedButton(
                                    onClick = { viewModel.deleteBackup(backup) },
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    Text(tr("Usuń", "Delete"), fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }

        }
    }
}
