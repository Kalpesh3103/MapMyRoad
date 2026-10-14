package com.mapmyroad.sensorlogger.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.mapmyroad.sensorlogger.R
import com.mapmyroad.sensorlogger.core.ElapsedFormat
import com.mapmyroad.sensorlogger.data.SessionInfo
import com.mapmyroad.sensorlogger.data.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Lists recorded sessions with Share (zip via FileProvider) and Delete (with confirmation). */
@Composable
fun SessionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { SessionStore(context) }
    val scope = rememberCoroutineScope()
    val chooserTitle = stringResource(R.string.share_chooser_title)
    var sessions by remember { mutableStateOf<List<SessionInfo>?>(null) }
    var refreshCount by remember { mutableIntStateOf(0) }
    var pendingDeleteName by rememberSaveable { mutableStateOf<String?>(null) }
    var busyName by remember { mutableStateOf<String?>(null) }
    var messageRes by remember { mutableStateOf<Int?>(null) }

    BackHandler(onBack = onBack)
    LaunchedEffect(refreshCount) {
        sessions = withContext(Dispatchers.IO) { store.list() }
    }

    fun share(name: String) {
        busyName = name
        messageRes = null
        scope.launch {
            try {
                val zip = withContext(Dispatchers.IO) { store.zipForSharing(name) }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)
                startShare(context, uri, name, chooserTitle)
            } catch (e: IOException) {
                messageRes = R.string.share_failed
            } catch (e: IllegalArgumentException) {
                messageRes = R.string.share_failed
            } catch (e: ActivityNotFoundException) {
                messageRes = R.string.share_failed
            } finally {
                busyName = null
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(text = stringResource(R.string.back)) }
            Text(text = stringResource(R.string.sessions_title), style = MaterialTheme.typography.headlineSmall)
        }
        Text(text = stringResource(R.string.sessions_privacy_note), style = MaterialTheme.typography.bodySmall)
        messageRes?.let { Text(text = stringResource(it), color = MaterialTheme.colorScheme.error) }

        val current = sessions
        when {
            current == null -> Text(text = stringResource(R.string.loading))
            current.isEmpty() -> Text(text = stringResource(R.string.no_sessions))
            else ->
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(current, key = { it.name }) { session ->
                        SessionRow(
                            session = session,
                            isBusy = busyName == session.name,
                            onShare = { share(session.name) },
                            onDelete = { pendingDeleteName = session.name },
                        )
                    }
                }
        }
    }

    val deleteName = pendingDeleteName
    if (deleteName != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteName = null },
            title = { Text(text = stringResource(R.string.delete_confirm_title)) },
            text = { Text(text = stringResource(R.string.delete_confirm_text, deleteName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteName = null
                        scope.launch {
                            val isDeleted = withContext(Dispatchers.IO) { store.delete(deleteName) }
                            if (!isDeleted) messageRes = R.string.delete_failed
                            refreshCount++
                        }
                    },
                ) {
                    Text(text = stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteName = null }) {
                    Text(text = stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun SessionRow(
    session: SessionInfo,
    isBusy: Boolean,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = session.name, style = MaterialTheme.typography.titleSmall)
            val size = Formatter.formatShortFileSize(context, session.sizeBytes)
            val duration = session.durationMs?.let { ElapsedFormat.hms(it) } ?: stringResource(R.string.unknown)
            Text(text = stringResource(R.string.session_details, size, duration), style = MaterialTheme.typography.bodySmall)
            if (session.isComplete == false) {
                Text(text = stringResource(R.string.session_incomplete), style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onShare, enabled = !isBusy) {
                    Text(text = stringResource(if (isBusy) R.string.zipping else R.string.share))
                }
                OutlinedButton(onClick = onDelete, enabled = !isBusy) {
                    Text(text = stringResource(R.string.delete))
                }
            }
        }
    }
}

private fun startShare(
    context: Context,
    uri: Uri,
    name: String,
    chooserTitle: String,
) {
    val send =
        Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(name, uri)
    context.startActivity(Intent.createChooser(send, chooserTitle))
}
