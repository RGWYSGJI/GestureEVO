package io.github.lumiagg.gestureevo.settings

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.lumiagg.gestureevo.databinding.ActivityFreeFormSettingsBinding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class FreeFormSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFreeFormSettingsBinding
    private val _orientation = MutableStateFlow(Configuration.ORIENTATION_PORTRAIT)
    private val orientation: StateFlow<Int> = _orientation

    companion object {
        const val KEY_ORIENTATION = "current_orientation"
        const val KEY_USE_MI_WINDOW = "use_mi_window"
        const val KEY_FREEFORM_CODE = "freeform_code"
        const val KEY_FREEFORM_FOLLOW_TRIGGER_SIDE = "freeform_follow_trigger_side"

        // Base preference keys
        const val KEY_WIDTH = "freeform_width_dp"
        const val KEY_HEIGHT = "freeform_height_dp"
        const val KEY_LEFT_MARGIN = "freeform_left_margin_dp"
        const val KEY_TOP_MARGIN = "freeform_top_margin_dp"
        const val KEY_EDGE_MARGIN = "freeform_edge_margin_dp"

        fun getSettingsKey(baseKey: String, orientation: Int): String {
            val suffix =
                if (orientation == Configuration.ORIENTATION_PORTRAIT) "_portrait" else "_landscape"
            return "${baseKey}${suffix}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFreeFormSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enableFullScreen()

        // Restore orientation state
        savedInstanceState?.let {
            val savedOrientation = it.getInt(KEY_ORIENTATION, Configuration.ORIENTATION_PORTRAIT)
            _orientation.value = savedOrientation
            setScreenOrientation(savedOrientation)
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        lifecycleScope.launch {
            initializeViews()
            setupListeners()
            observeSettings()
            SettingsSearchNavigator.focusAnchorIfNeeded(binding.root, intent)
        }
    }

    private fun enableFullScreen() {
        when {
            // Android 11+ (API 30+): Use WindowInsetsController
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                val controller = window.insetsController
                if (controller != null) {
                    controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    controller.systemBarsBehavior =
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }

            // Android 10 and earlier
            else -> {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_ORIENTATION, _orientation.value)
    }

    private fun setScreenOrientation(orientation: Int) {
        requestedOrientation = if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    private fun initializeViews() {
        if (_orientation.value == Configuration.ORIENTATION_PORTRAIT) {
            binding.directionButtons.check(binding.directionButtonPortrait.id)
        } else {
            binding.directionButtons.check(binding.directionButtonLandscape.id)
        }
    }

    private fun setupListeners() {
        // Orientation button group listener
        binding.directionButtons.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    binding.directionButtonPortrait.id -> {
                        _orientation.value = Configuration.ORIENTATION_PORTRAIT
                        setScreenOrientation(Configuration.ORIENTATION_PORTRAIT)
                    }

                    binding.directionButtonLandscape.id -> {
                        _orientation.value = Configuration.ORIENTATION_LANDSCAPE
                        setScreenOrientation(Configuration.ORIENTATION_LANDSCAPE)
                    }
                }
            }
        }
    }

    private fun observeSettings() {
        lifecycleScope.launch {
            orientation.collect { orientationValue ->
                if (orientationValue == Configuration.ORIENTATION_PORTRAIT) {
                    binding.directionButtons.check(binding.directionButtonPortrait.id)
                } else {
                    binding.directionButtons.check(binding.directionButtonLandscape.id)
                }
            }
        }
    }
}
