package io.github.lumiagg.gestureevo.search

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.github.lumiagg.gestureevo.R

/**
 * Publishes the launcher shortcut that opens the search page directly.
 *
 * The shortcut is intentionally dynamic: its intent uses the current package name,
 * so it also works for the .debug applicationId used by local builds.
 */
object SearchShortcutPublisher {
    private const val SHORTCUT_ID = "search"
    const val ACTION_OPEN_SEARCH = "io.github.lumiagg.gestureevo.action.OPEN_SEARCH"

    fun publish(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return

        val intent = Intent(context, QuickSearchActivity::class.java).apply {
            action = ACTION_OPEN_SEARCH
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val shortcut = ShortcutInfoCompat.Builder(context, SHORTCUT_ID)
            .setShortLabel(context.getString(R.string.shortcut_search_short))
            .setLongLabel(context.getString(R.string.shortcut_search_long))
            .setIcon(IconCompat.createWithResource(context, R.drawable.search_24px))
            .setIntent(intent)
            .setRank(0)
            .build()

        runCatching {
            ShortcutManagerCompat.setDynamicShortcuts(context, listOf(shortcut))
        }
    }
}
