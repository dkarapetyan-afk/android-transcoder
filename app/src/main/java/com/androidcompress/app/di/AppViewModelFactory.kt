package com.androidcompress.app.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.androidcompress.app.ui.compress.CompressViewModel
import com.androidcompress.app.ui.home.HomeViewModel
import com.androidcompress.app.ui.library.LibraryViewModel
import com.androidcompress.app.ui.log.JobLogViewModel
import com.androidcompress.app.ui.progress.ProgressViewModel
import com.androidcompress.app.ui.record.RecordViewModel
import com.androidcompress.app.ui.result.ResultViewModel
import com.androidcompress.app.ui.settings.SettingsViewModel

class AppViewModelFactory(
    private val container: AppContainer,
    private val jobId: String? = null,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val vm = when {
            modelClass.isAssignableFrom(HomeViewModel::class.java) -> HomeViewModel(container)
            modelClass.isAssignableFrom(RecordViewModel::class.java) -> RecordViewModel(container)
            modelClass.isAssignableFrom(CompressViewModel::class.java) ->
                CompressViewModel(container, requireNotNull(jobId))
            modelClass.isAssignableFrom(ProgressViewModel::class.java) ->
                ProgressViewModel(container, requireNotNull(jobId))
            modelClass.isAssignableFrom(ResultViewModel::class.java) ->
                ResultViewModel(container, requireNotNull(jobId))
            modelClass.isAssignableFrom(LibraryViewModel::class.java) -> LibraryViewModel(container)
            modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(container)
            modelClass.isAssignableFrom(JobLogViewModel::class.java) ->
                JobLogViewModel(container, jobId ?: JobLogViewModel.LAST)
            else -> error("Unknown ViewModel ${modelClass.name}")
        }
        return vm as T
    }
}
