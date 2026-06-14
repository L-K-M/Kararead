package ch.lkmc.kararead.ui.navigation

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tag
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
import ch.lkmc.kararead.ui.library.LibraryScreen
import ch.lkmc.kararead.ui.library.ListBookmarksScreen
import ch.lkmc.kararead.ui.lists.ListsScreen
import ch.lkmc.kararead.ui.onboarding.OnboardingScreen
import ch.lkmc.kararead.ui.reader.ReaderScreen
import ch.lkmc.kararead.ui.search.SearchScreen
import ch.lkmc.kararead.ui.settings.SettingsScreen
import ch.lkmc.kararead.ui.tags.TagBookmarksScreen
import ch.lkmc.kararead.ui.tags.TagsScreen

object Routes {
    const val ONBOARDING = "onboarding"
    const val LIBRARY = "library"
    const val LISTS = "lists"
    const val TAGS = "tags"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val READER = "reader/{bookmarkId}"
    const val LIST_DETAIL = "list/{listId}/{listName}"
    const val TAG_DETAIL = "tag/{tagId}/{tagName}"

    fun reader(bookmarkId: String) = "reader/$bookmarkId"
    fun listDetail(listId: String, listName: String) =
        "list/$listId/${Uri.encode(listName)}"
    fun tagDetail(tagId: String, tagName: String) =
        "tag/$tagId/${Uri.encode(tagName)}"
}

private data class TopTab(val route: String, val label: String, val icon: ImageVector)

private val topTabs = listOf(
    TopTab(Routes.LIBRARY, "Read", Icons.AutoMirrored.Outlined.MenuBook),
    TopTab(Routes.LISTS, "Lists", Icons.Outlined.CollectionsBookmark),
    TopTab(Routes.TAGS, "Tags", Icons.Outlined.Tag),
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
                LibraryScreen(onOpenReader = { navController.navigate(Routes.reader(it)) })
            }
            tabComposable(Routes.LISTS, padding) {
                ListsScreen(
                    onOpenList = { id, name ->
                        navController.navigate(Routes.listDetail(id, name))
                    },
                )
            }
            tabComposable(Routes.TAGS, padding) {
                TagsScreen(
                    onOpenTag = { id, name ->
                        navController.navigate(Routes.tagDetail(id, name))
                    },
                )
            }
            tabComposable(Routes.SEARCH, padding) {
                SearchScreen(onOpenReader = { navController.navigate(Routes.reader(it)) })
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
                route = Routes.LIST_DETAIL,
                enterTransition = { slideIn() },
                exitTransition = { slideOut() },
            ) { entry ->
                val listId = entry.arguments?.getString("listId").orEmpty()
                val listName = Uri.decode(entry.arguments?.getString("listName").orEmpty())
                ListBookmarksScreen(
                    listId = listId,
                    listName = listName,
                    onOpenReader = { navController.navigate(Routes.reader(it)) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = Routes.TAG_DETAIL,
                enterTransition = { slideIn() },
                exitTransition = { slideOut() },
            ) { entry ->
                val tagName = Uri.decode(entry.arguments?.getString("tagName").orEmpty())
                TagBookmarksScreen(
                    tagName = tagName,
                    onOpenReader = { navController.navigate(Routes.reader(it)) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = Routes.READER,
                enterTransition = { slideIn() },
                exitTransition = { slideOut() },
            ) {
                ReaderScreen(onBack = { navController.popBackStack() })
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
        androidx.compose.foundation.layout.Box(Modifier.padding(padding)) { content() }
    }
}

private fun AnimatedContentTransitionScope<*>.slideIn() =
    slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(280))

private fun AnimatedContentTransitionScope<*>.slideOut() =
    slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(280))
