package com.androidcompress.app.encode

import com.androidcompress.app.data.EncodeProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class EncodeProgressStore {
    private val _progress = MutableStateFlow<EncodeProgress?>(null)
    val progress: StateFlow<EncodeProgress?> = _progress.asStateFlow()

    fun update(value: EncodeProgress?) {
        _progress.value = value
    }
}
