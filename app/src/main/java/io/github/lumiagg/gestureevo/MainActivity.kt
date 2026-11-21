package io.github.lumiagg.gestureevo

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.lumiagg.gestureevo.search.config.SearchEngineDataManager
import io.github.lumiagg.gestureevo.settings.BackgroundStabilityActivity
import io.github.lumiagg.gestureevo.settings.SettingsActivity
import io.github.lumiagg.gestureevo.utils.icon.IconHelper
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                startupValue(Unit) { SearchEngineDataManager.ensureDefaultEngines(applicationContext) }
                startupValue(Unit) { IconHelper.initIconConfig(applicationContext) }
            }
            val hideTask = startupValue(true) { settingsStore.readKey(BackgroundStabilityActivity.HIDE_TASK, true) }
            startActivity(Intent(this@MainActivity, SettingsActivity::class.java).apply {
                if (!hideTask) flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT
            })
            EvoApplication.application.refreshSearchCatalogs()
            finish()
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
