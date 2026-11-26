package io.github.lumiagg.gestureevo.search

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.inspire.ScreenContentShortcutActivity

/**
 * Publishes the launcher shortcuts that open search and screen content directly.
 *
 * The intents intentionally use the current package name, so the shortcuts also
 * work for the .debug applicationId used by local builds.
 */
object SearchShortcutPublisher {
    private const val SEARCH_SHORTCUT_ID = "search"
    private const val SCREEN_CONTENT_SHORTCUT_ID = "screen_content"

    const val ACTION_OPEN_SEARCH = "io.github.lumiagg.gestureevo.action.OPEN_SEARCH"
    const val ACTION_OPEN_SCREEN_CONTENT =
        "io.github.lumiagg.gestureevo.action.OPEN_SCREEN_CONTENT"

    fun publish(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return

        val shortcuts = listOf(
            buildSearchShortcut(context),
            buildScreenContentShortcut(context),
        )
        runCatching {
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        }
    }

    private fun buildSearchShortcut(context: Context): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, SEARCH_SHORTCUT_ID)
            .setShortLabel(context.getString(R.string.shortcut_search_short))
            .setLongLabel(context.getString(R.string.shortcut_search_long))
            .setIcon(appIcon(context))
            .setIntent(
                Intent(context, QuickSearchActivity::class.java).apply {
                    action = ACTION_OPEN_SEARCH
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION,
                    )
                },
            )
            .setRank(0)
            .build()

    private fun buildScreenContentShortcut(context: Context): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, SCREEN_CONTENT_SHORTCUT_ID)
            .setShortLabel(context.getString(R.string.shortcut_screen_content_short))
            .setLongLabel(context.getString(R.string.shortcut_screen_content_long))
            .setIcon(appIcon(context))
            .setIntent(
                Intent(context, ScreenContentShortcutActivity::class.java).apply {
                    action = ACTION_OPEN_SCREEN_CONTENT
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            .setRank(1)
            .build()

    private fun appIcon(context: Context): IconCompat =
        IconCompat.createWithResource(context, R.mipmap.ic_launcher)
}
