package com.androidcompress.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.androidcompress.app.container
import com.androidcompress.app.data.JobStatus
import com.androidcompress.app.di.AppViewModelFactory
import com.androidcompress.app.ui.about.AboutScreen
import com.androidcompress.app.ui.compress.CompressScreen
import com.androidcompress.app.ui.compress.CompressViewModel
import com.androidcompress.app.ui.home.HomeScreen
import com.androidcompress.app.ui.home.HomeViewModel
import com.androidcompress.app.ui.library.LibraryScreen
import com.androidcompress.app.ui.library.LibraryViewModel
import com.androidcompress.app.ui.log.JobLogScreen
import com.androidcompress.app.ui.log.JobLogViewModel
import com.androidcompress.app.ui.progress.ProgressScreen
import com.androidcompress.app.ui.progress.ProgressViewModel
import com.androidcompress.app.ui.record.RecordScreen
import com.androidcompress.app.ui.record.RecordViewModel
import com.androidcompress.app.ui.result.ResultScreen
import com.androidcompress.app.ui.result.ResultViewModel
import com.androidcompress.app.ui.settings.SettingsScreen
import com.androidcompress.app.ui.settings.SettingsViewModel

@Composable
fun CompressApp() {
    val nav = rememberNavController()
    val context = LocalContext.current
    val container = remember { context.container() }

    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            val vm: HomeViewModel = viewModel(factory = AppViewModelFactory(container))
            HomeScreen(
                viewModel = vm,
                onRecord = { nav.navigate("record") },
                onCompress = { nav.navigate("compress/$it") },
                onProgress = { nav.navigate("progress/$it") },
                onResult = { nav.navigate("result/$it") },
                onLibrary = { nav.navigate("library") },
                onLastLog = { nav.navigate("log/last") },
                onSettings = { nav.navigate("settings") },
                onAbout = { nav.navigate("about") },
            )
        }
        composable("record") {
            val vm: RecordViewModel = viewModel(factory = AppViewModelFactory(container))
            RecordScreen(
                viewModel = vm,
                onBack = { nav.popBackStack() },
                onFinished = { id ->
                    nav.popBackStack()
                    nav.navigate("compress/$id")
                },
            )
        }
        composable(
            "compress/{jobId}",
            arguments = listOf(navArgument("jobId") { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString("jobId").orEmpty()
            val vm: CompressViewModel = viewModel(factory = AppViewModelFactory(container, id))
            CompressScreen(
                viewModel = vm,
                onBack = { nav.popBackStack() },
                onStarted = { nav.navigate("progress/$it") { popUpTo("home") } },
            )
        }
        composable(
            "progress/{jobId}",
            arguments = listOf(navArgument("jobId") { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString("jobId").orEmpty()
            val vm: ProgressViewModel = viewModel(factory = AppViewModelFactory(container, id))
            ProgressScreen(
                viewModel = vm,
                onBack = { nav.popBackStack() },
                onFinished = { nav.navigate("result/$it") { popUpTo("home") } },
                onSwitch = { nav.navigate("progress/$it") { popUpTo("home") } },
            )
        }
        composable(
            "result/{jobId}",
            arguments = listOf(navArgument("jobId") { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString("jobId").orEmpty()
            val vm: ResultViewModel = viewModel(factory = AppViewModelFactory(container, id))
            ResultScreen(
                viewModel = vm,
                onBack = { nav.popBackStack() },
                onViewLog = { nav.navigate("log/$id") },
            )
        }
        composable("library") {
            val vm: LibraryViewModel = viewModel(factory = AppViewModelFactory(container))
            LibraryScreen(
                viewModel = vm,
                onBack = { nav.popBackStack() },
                onOpen = { id, status ->
                    when (status) {
                        JobStatus.RUNNING, JobStatus.QUEUED -> nav.navigate("progress/$id")
                        JobStatus.READY, JobStatus.RECORDING, JobStatus.DRAFT -> nav.navigate("compress/$id")
                        else -> nav.navigate("result/$id")
                    }
                },
            )
        }
        composable(
            "log/{jobId}",
            arguments = listOf(navArgument("jobId") { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString("jobId").orEmpty()
            val vm: JobLogViewModel = viewModel(factory = AppViewModelFactory(container, id))
            JobLogScreen(viewModel = vm, onBack = { nav.popBackStack() })
        }
        composable("settings") {
            val vm: SettingsViewModel = viewModel(factory = AppViewModelFactory(container))
            SettingsScreen(viewModel = vm, onBack = { nav.popBackStack() })
        }
        composable("about") {
            AboutScreen(onBack = { nav.popBackStack() })
        }
    }
}
