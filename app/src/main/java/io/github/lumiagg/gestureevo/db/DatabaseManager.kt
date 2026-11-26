package io.github.lumiagg.gestureevo.db

import androidx.room.Room
import io.github.lumiagg.gestureevo.EvoApplication.Companion.application

object DatabaseManager {
    private const val DB_NAME = "GestureEvoRoom.db"
    val db: DataBase by lazy {
        Room.databaseBuilder(application.applicationContext, DataBase::class.java, DB_NAME)
            .addMigrations(DataBase.MIGRATION_1_3, DataBase.MIGRATION_2_3)
            .build()
    }
}

fun getAPPDao(): APPDao = DatabaseManager.db.appDao

fun getContactDao(): ContactDao = DatabaseManager.db.contactDao

fun getShortcutDao(): ShortcutDao = DatabaseManager.db.shortcutDao

fun getSearchHistoryDao(): SearchHistoryDao = DatabaseManager.db.searchHistoryDao

fun getSearchResultPinDao(): SearchResultPinDao = DatabaseManager.db.searchResultPinDao

fun getHiddenSearchResultDao(): HiddenSearchResultDao = DatabaseManager.db.hiddenSearchResultDao

fun getExternalActionPermissionDao(): ExternalActionPermissionDao = DatabaseManager.db.externalActionPermissionDao

fun getPinPoolDao(): PinPoolDao = DatabaseManager.db.pinPoolDao
