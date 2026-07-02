package ch.lkmc.kararead.ui.navigation

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ch.lkmc.kararead.ui.highlights.HighlightsScreen
import ch.lkmc.kararead.ui.library.LibraryScreen
import ch.lkmc.kararead.ui.library.ListBookmarksScreen
import ch.lkmc.kararead.ui.lists.ListsScreen
import ch.lkmc.kararead.ui.onboarding.OnboardingScreen
import ch.lkmc.kararead.ui.reader.ReaderScreen
import ch.lkmc.kararead.ui.search.SearchScreen
import ch.lkmc.kararead.ui.settings.SettingsScreen
import ch.lkmc.kararead.ui.stats.StatsScreen

object Routes {
    const val ONBOARDING = "onboarding"
    const val LIBRARY = "library"
    const val LISTS = "lists"
    const val HIGHLIGHTS = "highlights"
    const val STATS = "stats"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val READER = "reader/{bookmarkId}"
    const val LIST_DETAIL = "list/{listId}/{listName}"

    fun reader(bookmarkId: String) = "reader/$bookmarkId"
    fun listDetail(listId: String, listName: String) =
        "list/$listId/${Uri.encode(listName)}"
}

private data class TopTab(val route: String, val label: String, val icon: ImageVector)

private val topTabs = listOf(
    TopTab(Routes.LIBRARY, "Read", Icons.AutoMirrored.Outlined.MenuBook),
    TopTab(Routes.LISTS, "Lists", Icons.Outlined.CollectionsBookmark),
    TopTab(Routes.STATS, "Stats", Icons.Outlined.Insights),
    TopTab(Routes.SEARCH, "Search", Icons.Outlined.Search),
    TopTab(Routes.SETTINGS, "Settings", Icons.Outlined.Settings),
)

@Composable
fun KararreadNavHost(startDestination: String) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in topTabs.map { it.route }

    Scaffold(
        // The tab screens each run their own Scaffold whose TopAppBar handles
        // the status-bar inset. Without zeroing this, the outer Scaffold's
        // content padding *also* included the status bar — every tab showed a
        // status-bar-high dead gap above its title (visible in the repo's own
        // screenshots).
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    val destination = backStackEntry?.destination
                    topTabs.forEach { tab ->
                        val selected = destination?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(
                    onConnected = {
                        navController.navigate(Routes.LIBRARY) {
                            popUpTo(Routes.ONBOARDING) { inclusive = true }
                        }
                    },
                )
            }
            tabComposable(Routes.LIBRARY, padding) {
                LibraryScreen(
                    onOpenReader = {
                        navController.navigate(Routes.reader(it)) { launchSingleTop = true }
                    },
                )
            }
            tabComposable(Routes.LISTS, padding) {
                ListsScreen(
                    onOpenList = { id, name ->
                        navController.navigate(Routes.listDetail(id, name)) { launchSingleTop = true }
                    },
                    onOpenHighlights = {
                        navController.navigate(Routes.HIGHLIGHTS) { launchSingleTop = true }
                    },
                )
            }
            tabComposable(Routes.STATS, padding) {
                StatsScreen()
            }
            tabComposable(Routes.SEARCH, padding) {
                SearchScreen(
                    onOpenReader = {
                        navController.navigate(Routes.reader(it)) { launchSingleTop = true }
                    },
                )
            }
            tabComposable(Routes.SETTINGS, padding) {
                SettingsScreen(
                    onSignedOut = {
                        navController.navigate(Routes.ONBOARDING) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                )
            }
            composable(
                route = Routes.HIGHLIGHTS,
                enterTransition = { slideIn() },
                exitTransition = { slideOut() },
            ) {
                HighlightsScreen(
                    onOpenReader = {
                        navController.navigate(Routes.reader(it)) { launchSingleTop = true }
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = Routes.LIST_DETAIL,
                enterTransition = { slideIn() },
                exitTransition = { slideOut() },
            ) { entry ->
                val listId = entry.arguments?.getString("listId").orEmpty()
                // Navigation delivers path arguments already URI-decoded;
                // decoding again mangled list names containing '%'.
                val listName = entry.arguments?.getString("listName").orEmpty()
                ListBookmarksScreen(
                    listId = listId,
                    listName = listName,
                    onOpenReader = {
                        navController.navigate(Routes.reader(it)) { launchSingleTop = true }
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = Routes.READER,
                enterTransition = { slideIn() },
                exitTransition = { slideOut() },
            ) {
                ReaderScreen(
                    onBack = { navController.popBackStack() },
                    onOpenReader = { id ->
                        // Replace the current reader so Back returns to the list
                        // rather than walking through every article read.
                        navController.navigate(Routes.reader(id)) {
                            popUpTo(Routes.READER) { inclusive = true }
                        }
                    },
                )
            }
        }
    }
}

private fun androidx.navigation.NavGraphBuilder.tabComposable(
    route: String,
    padding: androidx.compose.foundation.layout.PaddingValues,
    content: @Composable () -> Unit,
) {
    composable(route) {
        androidx.compose.foundation.layout.Box(
            Modifier
                .padding(padding)
                // The bottom-bar height is applied as padding above; consume it
                // so the screens' own Scaffolds don't pad for the system bars
                // hidden behind it a second time.
                .consumeWindowInsets(padding),
        ) { content() }
    }
}

private fun AnimatedContentTransitionScope<*>.slideIn() =
    slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(280))

private fun AnimatedContentTransitionScope<*>.slideOut() =
    slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(280))
