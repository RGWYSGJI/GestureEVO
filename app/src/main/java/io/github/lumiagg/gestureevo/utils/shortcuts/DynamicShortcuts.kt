package io.github.lumiagg.gestureevo.utils.shortcuts

import android.net.Uri
import android.os.Build
import io.github.lumiagg.gestureevo.db.DatabaseManager

/**
 * Compatibility resolver for gesture links that were created before dynamic shortcut
 * importing was removed. New shortcuts cannot be imported or selected.
 */
object DynamicShortcuts {
    const val SCHEME = "gesture-evo-shortcut"
    private val dao get() = DatabaseManager.db.dynamicShortcutDao()

    internal suspend fun requireImported(uri: Uri, expectedPackage: String): ShortcutIdentity {
        require(Build.VERSION.SDK_INT >= 25 && uri.scheme == SCHEME && uri.host == "launch")
        val pkg = requireNotNull(uri.getQueryParameter("pkg")).also { require(it.isNotBlank()) }
        require(expectedPackage.isEmpty() || expectedPackage == pkg)
        val id = requireNotNull(uri.getQueryParameter("id")).also { require(it.isNotEmpty()) }
        val serial = requireNotNull(uri.getQueryParameter("user")?.toLongOrNull()).also { require(it >= 0) }
        check(dao.isImported(pkg, id, serial)) { "Shortcut needs to be imported on this device" }
        return ShortcutIdentity(pkg, id, serial)
    }
}
