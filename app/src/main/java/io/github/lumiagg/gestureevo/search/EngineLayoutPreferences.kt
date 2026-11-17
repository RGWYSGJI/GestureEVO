package io.github.lumiagg.gestureevo.search

import androidx.annotation.StringRes
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.utils.readKey
import kotlinx.coroutines.flow.first

/** One search-engine grid layout. */
data class EngineLayoutPreference(
    val rowCount: Int,
    val columnCount: Int,
    val showLabel: Boolean,
)

/**
 * Search-engine layout scopes. The three entries are independent so changing the
 * search page no longer changes OCR or the drag overlay (and vice versa).
 */
enum class EngineLayoutScope(
    @StringRes val labelRes: Int,
    val storageKey: String,
    val defaultRows: Int = 1,
    val defaultColumns: Int = 5,
) {
    SEARCH(R.string.search_engine_layout_scope_search, "search"),
    INSPIRE(R.string.search_engine_layout_scope_inspire, "inspire"),
    DROP(R.string.search_engine_layout_scope_drop, "drop"),
    ;

    val rowCountKey: String get() = "engine_row_count_$storageKey"
    val columnCountKey: String get() = "engine_column_count_$storageKey"
    val showLabelKey: String get() = "engine_show_label_$storageKey"

    companion object {
        fun fromStorageKey(value: String?): EngineLayoutScope =
            entries.firstOrNull { it.storageKey == value } ?: SEARCH
    }
}

object EngineLayoutPreferences {
    const val MIN_ROW_COUNT = 1
    const val MAX_ROW_COUNT = 5
    const val MIN_COLUMN_COUNT = 2
    const val MAX_COLUMN_COUNT = 8

    /** Selected scope on the settings screen. */
    const val KEY_SCOPE = "engine_layout_scope"

    /**
     * Legacy keys. They are only a migration fallback: once a scope is changed,
     * its own key is written and the legacy key is no longer used for that scope.
     */
    const val LEGACY_ROW_COUNT = "engine_row_count"
    const val LEGACY_COLUMN_COUNT = "engine_column_count"
    const val LEGACY_SHOW_LABEL = "engine_show_label"

    fun read(preferences: Preferences, scope: EngineLayoutScope): EngineLayoutPreference {
        val legacyRows = preferences.readKey(LEGACY_ROW_COUNT, scope.defaultRows)
        val legacyColumns = preferences.readKey(LEGACY_COLUMN_COUNT, scope.defaultColumns)
        val legacyShowLabel = preferences.readKey(LEGACY_SHOW_LABEL, true)
        return EngineLayoutPreference(
            rowCount = preferences.readKey(scope.rowCountKey, legacyRows)
                .coerceIn(MIN_ROW_COUNT, MAX_ROW_COUNT),
            columnCount = preferences.readKey(scope.columnCountKey, legacyColumns)
                .coerceIn(MIN_COLUMN_COUNT, MAX_COLUMN_COUNT),
            showLabel = preferences.readKey(scope.showLabelKey, legacyShowLabel),
        )
    }

    suspend fun read(store: DataStore<Preferences>, scope: EngineLayoutScope): EngineLayoutPreference =
        read(store.data.first(), scope)
}
