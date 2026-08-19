package com.androidcompress.app.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.androidcompress.app.R
import com.androidcompress.app.ui.components.AppTopBar

@Composable
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(topBar = { AppTopBar(stringResource(R.string.about_title), onBack = onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.about_blurb))
            Text(stringResource(R.string.about_privacy_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.about_privacy))
            Text(stringResource(R.string.about_oss_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.about_oss_ffmpeg))
            Text(stringResource(R.string.about_oss_media3))
            Text(stringResource(R.string.about_oss_hevc))
            Text(stringResource(R.string.about_oss_apache))
        }
    }
}
