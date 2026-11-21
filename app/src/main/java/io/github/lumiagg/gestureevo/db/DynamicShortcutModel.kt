package io.github.lumiagg.gestureevo.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query

/** Device-local references. A restored reference cannot restore the system pin grant. */
@Entity(primaryKeys = ["packageName", "shortcutId", "userSerial"])
data class DynamicShortcutModel(
    val packageName: String,
    val shortcutId: String,
    val userSerial: Long,
    val label: String,
    val appLabel: String,
    val imported: Boolean = false,
)

@Dao
interface DynamicShortcutDao {
    @Query("SELECT EXISTS(SELECT 1 FROM DynamicShortcutModel WHERE packageName = :pkg AND shortcutId = :id AND userSerial = :serial AND imported = 1)")
    suspend fun isImported(pkg: String, id: String, serial: Long): Boolean
}
