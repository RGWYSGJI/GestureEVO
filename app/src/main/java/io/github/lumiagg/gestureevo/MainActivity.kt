package io.github.lumiagg.gestureevo

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import io.github.lumiagg.gestureevo.search.QuickSearchActivity
import io.github.lumiagg.gestureevo.search.config.SearchEngineDataManager
import io.github.lumiagg.gestureevo.settings.BackgroundStabilityActivity
import io.github.lumiagg.gestureevo.settings.SearchEngineSettingsActivity.Companion.KEY_DEFAULT_SEARCH_PAGE
import io.github.lumiagg.gestureevo.settings.SettingsActivity
import io.github.lumiagg.gestureevo.settings.SettingsGuideActivity
import io.github.lumiagg.gestureevo.settings.guide.SettingsGuideState
import io.github.lumiagg.gestureevo.utils.icon.IconHelper
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Launcher dispatcher.
 *
 * It must stay alive under the screen it opens: only an existing instance receives
 * [onNewIntent] when the launcher is tapped again. With the old "start a screen and finish()"
 * implementation the task was simply brought back to the front, so a settings page left in
 * the background was resumed and the "open on the search page" setting was never consulted.
 *
 * Kept at the bottom of the task stack, a launcher tap reaches [onNewIntent], the singleTask
 * launch clears the settings pages above it, and the routing decision runs again.
 */
class MainActivity : AppCompatActivity() {
    /** A routing coroutine is in flight; a resume during this window belongs to the launch itself. */
    private var routing = false
    /** A screen has been opened above the dispatcher, so the next free resume means "back". */
    private var openedChild = false
    /** Swallows the resume that belongs to the current launch/re-launch instead of "returning". */
    private var suppressNextResume = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The first onResume belongs to this launch, never to "returned from a child".
        suppressNextResume = true
        route()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openedChild = false
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            // The system is about to resume this instance for the re-launch.
            suppressNextResume = true
        }
        route()
    }

    override fun onResume() {
        super.onResume()
        if (suppressNextResume) {
            suppressNextResume = false
            return
        }
        // Back from the screen we opened: the dispatcher must not stay as an empty page.
        if (openedChild && !routing) {
            openedChild = false
            finish()
        }
    }

    private fun route() {
        if (routing) return
        routing = true
        lifecycleScope.launch {
            try {
                // Decide onboarding before creating defaults; initialize icons before inflating its preview.
                val showGuide = startupValue(false) { SettingsGuideState.shouldShowOnLaunch(applicationContext) }
                withContext(Dispatchers.IO) {
                    startupValue(Unit) { SearchEngineDataManager.ensureDefaultEngines(applicationContext) }
                    startupValue(Unit) { IconHelper.initIconConfig(applicationContext) }
                }
                val search = startupValue(false) { settingsStore.readKey(KEY_DEFAULT_SEARCH_PAGE, false) }
                val hideTask = startupValue(true) { settingsStore.readKey(BackgroundStabilityActivity.HIDE_TASK, true) }
                if (isFinishing || isDestroyed) return@launch
                if (!showGuide && search) {
                    QuickSearchActivity.start(this@MainActivity)
                } else {
                    startActivity(
                        Intent(
                            this@MainActivity,
                            if (showGuide) SettingsGuideActivity::class.java else SettingsActivity::class.java
                        ).apply {
                            if (showGuide) putExtra(SettingsGuideActivity.EXTRA_FROM_LAUNCHER, true)
                            if (!hideTask) {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                            }
                        }
                    )
                }
                EvoApplication.application.refreshSearchCatalogs()
                openedChild = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Startup", "Unable to route the launcher entry", e)
                if (!isFinishing && !isDestroyed) {
                    startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                    openedChild = true
                }
            } finally {
                routing = false
            }
        }
    }

    private suspend fun <T> startupValue(fallback: T, read: suspend () -> T): T = try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("Startup", "Unable to initialize startup settings", e)
        fallback
    }
}
