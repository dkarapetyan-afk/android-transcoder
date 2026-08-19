package com.androidcompress.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.androidcompress.app.agent.AgentLaunch
import com.androidcompress.app.container
import com.androidcompress.app.data.JobStatus
import com.androidcompress.app.di.AppViewModelFactory
import com.androidcompress.app.media.ShareIntents
import com.androidcompress.app.media.ShareRequest
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@Composable
fun CompressApp(
    shareRequests: StateFlow<ShareRequest?>,
    onShareConsumed: (Long) -> Unit,
    agentUiRequests: StateFlow<AgentLaunch.UiRequest?> = MutableStateFlow(null),
    onAgentUiConsumed: (Long) -> Unit = {},
) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val container = remember { context.container() }
    val shareRequest by shareRequests.collectAsStateWithLifecycle()
    val agentUiRequest by agentUiRequests.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var importing by remember { mutableStateOf(false) }
    val blockClicks = remember { MutableInteractionSource() }

    LaunchedEffect(shareRequest?.nonce) {
        val request = shareRequest ?: return@LaunchedEffect
        val accepted = request.uris.filter { uri ->
            ShareIntents.isLikelyShareItem(context.contentResolver.getType(uri) ?: request.mimeType)
        }
        if (accepted.isEmpty()) {
            snackbar.showSnackbar("That share is not a video, audio, or picture file.")
            onShareConsumed(request.nonce)
            return@LaunchedEffect
        }
        importing = true
        try {
            val batch = container.importer.importShared(accepted) { uri ->
                context.contentResolver.getType(uri)
            }
            when {
                batch.jobIds.isNotEmpty() -> {
                    nav.navigate("compress/${batch.jobIds.first()}")
                    if (batch.errors.isNotEmpty()) {
                        snackbar.showSnackbar(
                            "Opened ${batch.jobIds.size} file(s). ${batch.errors.size} could not be read.",
                        )
                    } else if (batch.jobIds.size > 1) {
                        snackbar.showSnackbar("Opened ${batch.jobIds.size} files. The rest are in Recent.")
                    }
                }
                else -> snackbar.showSnackbar(batch.errors.firstOrNull() ?: "Unable to read that file")
            }
        } finally {
            importing = false
            onShareConsumed(request.nonce)
        }
    }

    LaunchedEffect(agentUiRequest?.nonce) {
        val request = agentUiRequest ?: return@LaunchedEffect
        when (request.destination) {
            AgentLaunch.OPEN_SETTINGS -> {
                nav.navigate(if (request.requestLibrary) "settings/library" else "settings") {
                    launchSingleTop = true
                }
            }
            AgentLaunch.OPEN_RESULT -> if (request.jobId.isNotBlank()) {
                nav.openResult(request.jobId)
            }
        }
        onAgentUiConsumed(request.nonce)
    }

    Box(Modifier.fillMaxSize()) {
        NavHost(
            navController = nav,
            startDestination = "home",
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
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
                onBack = { nav.safePop() },
                onFinished = { id ->
                    container.recording.consumeFinished()
                    nav.openCompressKeepingHome(id)
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
                onBack = { nav.safePop() },
                onStarted = { nav.openProgress(it) },
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
                onBack = { nav.popToHome() },
                onFinished = { nav.openResult(it) },
                onSwitch = { nav.openProgress(it) },
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
                onBack = { nav.popToHome() },
                onViewLog = { nav.navigate("log/$id") },
            )
        }
        composable("library") {
            val vm: LibraryViewModel = viewModel(factory = AppViewModelFactory(container))
            LibraryScreen(
                viewModel = vm,
                onBack = { nav.safePop() },
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
            JobLogScreen(viewModel = vm, onBack = { nav.safePop() })
        }
        composable("settings") {
            val vm: SettingsViewModel = viewModel(factory = AppViewModelFactory(container))
            SettingsScreen(viewModel = vm, onBack = { nav.safePop() })
        }
        composable("settings/library") {
            val vm: SettingsViewModel = viewModel(factory = AppViewModelFactory(container))
            SettingsScreen(
                viewModel = vm,
                onBack = { nav.safePop() },
                promptLibraryAccess = true,
            )
        }
        composable("about") {
            AboutScreen(onBack = { nav.safePop() })
        }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
        )
        if (importing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
                    .clickable(
                        interactionSource = blockClicks,
                        indication = null,
                        onClick = {},
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Card {
                    Column(
                        Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text("Opening shared file…")
                    }
                }
            }
        }
    }
}

private const val ROUTE_HOME = "home"

private fun NavHostController.openCompressKeepingHome(jobId: String) {
    if (jobId.isBlank()) return
    navigate(ROUTE_HOME) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
    navigate("compress/$jobId") { launchSingleTop = true }
}

private fun NavHostController.openProgress(jobId: String) {
    if (jobId.isBlank()) return
    navigate("progress/$jobId") {
        popUpTo(ROUTE_HOME) { inclusive = false }
        launchSingleTop = true
    }
}

private fun NavHostController.openResult(jobId: String) {
    if (jobId.isBlank()) return
    navigate("result/$jobId") {
        popUpTo(ROUTE_HOME) { inclusive = false }
        launchSingleTop = true
    }
}

private fun NavHostController.popToHome() {
    if (currentDestination?.route == ROUTE_HOME) return
    if (!popBackStack(ROUTE_HOME, inclusive = false)) {
        navigate(ROUTE_HOME) {
            popUpTo(graph.id) { inclusive = true }
            launchSingleTop = true
        }
    }
}

private fun NavHostController.safePop() {
    if (previousBackStackEntry != null) {
        popBackStack()
    } else {
        popToHome()
    }
}
