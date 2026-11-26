package io.github.lumiagg.gestureevo.inspire

import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.lifecycleScope
import io.github.lumiagg.gestureevo.search.EngineLayoutPreference
import io.github.lumiagg.gestureevo.search.EngineLayoutPreferences
import io.github.lumiagg.gestureevo.search.EngineLayoutScope
import io.github.lumiagg.gestureevo.search.QuickSearchEnginePager
import io.github.lumiagg.gestureevo.search.SearchEngineExecutor
import io.github.lumiagg.gestureevo.search.config.EngineType
import io.github.lumiagg.gestureevo.search.config.SearchEngine
import io.github.lumiagg.gestureevo.search.config.SearchEngineDataManager
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.KEY_INSPIRE_SHOW_ENGINE
import io.github.lumiagg.gestureevo.settings.SearchEngineSettingsActivity
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.launch

class SearchEngineManagerInspire(
    private val activity: InspireContentActivity,
    private val enginePager: QuickSearchEnginePager,
    private val queryProvider: () -> String = { "" },
    private val imageHandleProvider: () -> ManagedBitmap? = { null },
) {
    private val lifecycleScope: LifecycleCoroutineScope
        get() = activity.lifecycleScope

    fun initialize() = lifecycleScope.launch {
        val showEngine = activity.settingsStore.readKey(KEY_INSPIRE_SHOW_ENGINE, false)
        if (showEngine) {
            initEnginePager()
        }
    }

    private suspend fun initEnginePager() {
        val layout = EngineLayoutPreferences.read(
            activity.settingsStore,
            EngineLayoutScope.INSPIRE,
        )
        val showImageBadge = activity.settingsStore.readKey(
            SearchEngineSettingsActivity.KEY_SHOW_IMAGE_BADGE,
            true,
        )

        enginePager.configure(
            enginePagerConfig(layout, showImageBadge),
        )
        enginePager.setCallbacks(
            onEngineClick = { engine -> clickSearchEngine(engine, false) },
            onEngineLongClick = { engine -> clickSearchEngine(engine, true) },
            onOrderChanged = {},
        )
        enginePager.submitList(
            SearchEngineDataManager.readSearchEngineList(activity.applicationContext)
                .filter { it.showInSearchEngineList },
        )
    }

    private fun enginePagerConfig(
        layout: EngineLayoutPreference,
        showImageBadge: Boolean,
    ): QuickSearchEnginePager.Config =
        QuickSearchEnginePager.Config(
            rowCount = layout.rowCount,
            columnCount = layout.columnCount,
            showLabel = layout.showLabel,
            showImageBadge = showImageBadge,
            previewSortMode = false,
        )

    private fun clickSearchEngine(engine: SearchEngine, isPress: Boolean) {
        val query = if (engine.engineType == EngineType.SHARE_IMAGE_TO_APP) "" else queryProvider()
        val imageHandle = if (engine.supportsImageShare()) imageHandleProvider() else null
        SearchEngineExecutor(activity, engine, isPress) {
            InspireDataHolder.clear()
            activity.finish()
        }.execute(query = query, bitmapHandle = imageHandle).invokeOnCompletion {
            imageHandle?.close()
        }
    }
}
