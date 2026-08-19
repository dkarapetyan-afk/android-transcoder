package com.androidcompress.app.ui.hardware

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidcompress.app.R
import com.androidcompress.app.encode.HardwareEncoderResult
import com.androidcompress.app.encode.HardwareStep
import com.androidcompress.app.ui.components.AppTopBar
import com.androidcompress.app.ui.components.StatLine

@Composable
fun HardwareTestScreen(
    viewModel: HardwareTestViewModel,
    onBack: () -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    Scaffold(topBar = { AppTopBar(stringResource(R.string.hw_title), onBack = onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.hw_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ui.running) {
                val progress = ui.progress
                if (progress != null) {
                    Text(
                        stringResource(R.string.hw_running, progress.index + 1, progress.total),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    LinearProgressIndicator(
                        progress = {
                            if (progress.total <= 0) 0f
                            else ((progress.index + 1).toFloat() / progress.total.toFloat()).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        when (progress.step) {
                            HardwareStep.SOURCE -> stringResource(
                                R.string.hw_step_source,
                                progress.width,
                                progress.height,
                                progress.displayName,
                            )
                            HardwareStep.ENCODE -> stringResource(
                                R.string.hw_step_encode,
                                progress.displayName,
                                progress.width,
                                progress.height,
                            )
                            HardwareStep.TEN_BIT -> stringResource(
                                R.string.hw_step_ten_bit,
                                progress.displayName,
                                progress.width,
                                progress.height,
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                OutlinedButton(onClick = viewModel::cancel, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.hw_cancel))
                }
            } else {
                Button(onClick = viewModel::start, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(
                            if (ui.report == null) R.string.hw_run else R.string.hw_rerun,
                        ),
                    )
                }
            }
            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val report = ui.report
            if (report != null) {
                if (report.cancelled) {
                    Text(
                        stringResource(R.string.hw_cancelled),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                report.results.forEach { result ->
                    HardwareResultCard(result)
                }
            }
        }
    }
}

@Composable
private fun HardwareResultCard(result: HardwareEncoderResult) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(result.displayName, style = MaterialTheme.typography.titleMedium)
            if (result.codecName.isNotBlank()) {
                StatLine(stringResource(R.string.hw_codec_name), result.codecName)
            }
            StatLine(
                stringResource(R.string.hw_advertised_max),
                resolutionLabel(result.advertisedMaxWidth, result.advertisedMaxHeight),
            )
            StatLine(
                stringResource(R.string.hw_verified_max),
                if (result.available) {
                    resolutionLabel(result.verifiedMaxWidth, result.verifiedMaxHeight)
                } else {
                    stringResource(R.string.hw_unavailable)
                },
            )
            StatLine(stringResource(R.string.hw_ten_bit), tenBitLabel(result))
            StatLine(
                stringResource(R.string.hw_hdr),
                stringResource(if (result.advertisedHdr) R.string.hw_yes else R.string.hw_no),
            )
            val speed = result.speedX
            val encodeMs = result.encodeMs
            if (speed != null && encodeMs != null) {
                StatLine(
                    stringResource(R.string.hw_speed),
                    stringResource(R.string.hw_speed_value, speed, encodeMs),
                )
            }
            result.error?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun resolutionLabel(width: Int, height: Int): String =
    if (width > 0 && height > 0) {
        stringResource(R.string.hw_resolution, width, height)
    } else {
        stringResource(R.string.hw_unknown)
    }

@Composable
private fun tenBitLabel(result: HardwareEncoderResult): String = when {
    result.tenBitVerified == true -> stringResource(R.string.hw_ten_bit_ok)
    result.tenBitVerified == false -> stringResource(R.string.hw_ten_bit_fail)
    result.advertisedTenBit -> stringResource(R.string.hw_ten_bit_advertised)
    else -> stringResource(R.string.hw_no)
}
