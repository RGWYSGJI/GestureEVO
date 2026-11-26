package io.github.lumiagg.gestureevo

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import io.github.lumiagg.gestureevo.diagnostics.AppLogger
import io.github.lumiagg.gestureevo.ocr.OCR_MIN_API
import io.github.lumiagg.gestureevo.ocr.OCR_PROCESS_SUFFIX
import io.github.lumiagg.gestureevo.ocr.ScreenOcr
import io.github.lumiagg.gestureevo.search.SearchShortcutPublisher
import io.github.lumiagg.gestureevo.search.SettingsShortcutSearchHelper
import io.github.lumiagg.gestureevo.settings.AppearanceSettingsActivity
import io.github.lumiagg.gestureevo.utils.AppCrashHandler
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.app.syncAppCatalogIfNeeded
import io.github.lumiagg.gestureevo.utils.app.syncAppCatalogPackage
import io.github.lumiagg.gestureevo.utils.icon.IconHelper
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking


class EvoApplication : Application() {
    private val maintenanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val appCatalogReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_PACKAGE_REMOVED &&
                intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
            val packageName = intent.data?.schemeSpecificPart?.takeIf { it.isNotBlank() } ?: return
            val pendingResult = goAsync()
            maintenanceScope.launch {
                try {
                    syncAppCatalogPackage(applicationContext, packageName)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("AppCatalog", "Unable to refresh package $packageName", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    fun refreshSearchCatalogs() {
        maintenanceScope.launch {
            runMaintenanceStep("Unable to refresh app catalog") {
                syncAppCatalogIfNeeded(applicationContext)
            }
            runMaintenanceStep("Unable to refresh shortcuts") {
                SettingsShortcutSearchHelper.syncShortcutsToDatabase(applicationContext)
            }
        }
    }

    private suspend fun runMaintenanceStep(
        failureMessage: String,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Startup", failureMessage, e)
        }
    }

    private val isOcrProcess: Boolean
        get() = Build.VERSION.SDK_INT >= OCR_MIN_API && getProcessName() == packageName + OCR_PROCESS_SUFFIX

    override fun onCreate() {
        super.onCreate()
        if (isOcrProcess) return
        application = this
        SearchShortcutPublisher.publish(this)
        AppLogger.initialize(this)
        maintenanceScope.launch { File(filesDir, "ocr_models").deleteRecursively() }
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(AppCrashHandler(this, previousHandler))
        val preferences = runBlocking { settingsStore.data.first() }
        IconHelper.initializeFromPreferences(this, preferences)
        applySavedLanguage(preferences.readKey(AppearanceSettingsActivity.KEY_APP_LANGUAGE, ""))
        runBlocking { DynamicColorUtils.loadSavedScheme(this@EvoApplication, preferences) }
        DynamicColorUtils.applyAppWideColors(this)
        // Settings views read the shared palette while inflating, before any service starts.
        DynamicColorUtils.refreshSurfaceColors(this)
        ContextCompat.registerReceiver(this, appCatalogReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!isOcrProcess) DynamicColorUtils.refreshSurfaceColors(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!isOcrProcess && level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            ScreenOcr.trimMemory()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (!isOcrProcess) ScreenOcr.trimMemory()
    }

    private fun applySavedLanguage(languageTag: String) {
        val locales = if (languageTag.isBlank()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(languageTag)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    companion object {
        lateinit var application: EvoApplication private set
    }
}
