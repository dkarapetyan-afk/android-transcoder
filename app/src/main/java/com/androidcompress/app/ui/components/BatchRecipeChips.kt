package com.androidcompress.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.androidcompress.app.R
import com.androidcompress.app.encode.BatchRecipe
import com.androidcompress.app.ui.batchRecipeLabel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BatchRecipeChips(
    waitingCount: Int,
    onPick: (BatchRecipe) -> Unit,
) {
    if (waitingCount <= 0) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.batch_apply_title), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.batch_apply_hint, waitingCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BatchRecipe.listed().forEach { recipe ->
                FilterChip(
                    selected = false,
                    onClick = { onPick(recipe) },
                    label = { Text(batchRecipeLabel(recipe)) },
                )
            }
        }
    }
}
