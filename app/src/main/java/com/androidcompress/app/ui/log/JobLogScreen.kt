package com.androidcompress.app.ui.log

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidcompress.app.R
import com.androidcompress.app.ui.components.AppTopBar
import kotlinx.coroutines.launch

@Composable
fun JobLogScreen(
    viewModel: JobLogViewModel,
    onBack: () -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val copiedMessage = stringResource(R.string.log_copied)
    Scaffold(
        topBar = {
            AppTopBar(ui.title.ifBlank { stringResource(R.string.log_title) }, onBack = onBack)
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (ui.empty) {
                Text(
                    stringResource(R.string.log_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                OutlinedButton(
                    onClick = {
                        if (viewModel.copy(context)) {
                            scope.launch { snackbar.showSnackbar(copiedMessage) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.log_copy))
                }
                SelectionContainer(Modifier.weight(1f)) {
                    Text(
                        ui.text,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        }
    }
}
