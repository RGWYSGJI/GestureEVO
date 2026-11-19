package io.github.lumiagg.gestureevo.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import io.github.lumiagg.gestureevo.R

/**
 * Temporary HOME activity used while dynamic shortcuts are imported.
 *
 * This activity must stay alive as the current home screen. Finishing it while it is the
 * default home makes the system relaunch it in a loop, which looks like the role request
 * "did nothing". The user gets an explicit way back to the import screen and to the
 * original launcher instead.
 */
class ShortcutImportHomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shortcut_import_home)

        findViewById<android.view.View>(R.id.openImport).setOnClickListener {
            openImportScreen()
        }
        findViewById<android.view.View>(R.id.restoreHome).setOnClickListener {
            openHomeSettings()
        }

        // Home activities should not be dismissed with Back; otherwise the system has no home
        // screen to show and immediately starts this activity again.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })

        // The first launch is the one that follows the role request. Later launches (for example
        // the user pressing Home) stay on this screen instead of stacking another settings page.
        if (savedInstanceState == null) {
            openImportScreen()
        }
    }

    private fun openImportScreen() {
        startActivity(Intent(this, DynamicShortcutSettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
    }

    private fun openHomeSettings() {
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.dynamic_shortcuts_home_unavailable, Toast.LENGTH_LONG).show()
        }
    }
}
