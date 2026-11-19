package io.github.lumiagg.gestureevo.settings

import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.ActivityDynamicShortcutSettingsBinding
import io.github.lumiagg.gestureevo.db.DynamicShortcutModel
import io.github.lumiagg.gestureevo.diagnostics.AppLogger
import io.github.lumiagg.gestureevo.diagnostics.LogLevel
import io.github.lumiagg.gestureevo.diagnostics.LogModule
import io.github.lumiagg.gestureevo.gesture.config.GestureActionType
import io.github.lumiagg.gestureevo.settings.ui.DynamicShortcutAdapter
import io.github.lumiagg.gestureevo.settings.ui.ImportOption
import io.github.lumiagg.gestureevo.settings.ui.showImportSelectionBottomSheet
import io.github.lumiagg.gestureevo.utils.ActionProxy
import io.github.lumiagg.gestureevo.utils.ClipboardUtils
import io.github.lumiagg.gestureevo.utils.GestureActionLink
import io.github.lumiagg.gestureevo.utils.ToastHelper
import io.github.lumiagg.gestureevo.utils.shortcuts.DynamicShortcuts
import kotlinx.coroutines.launch

class DynamicShortcutSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDynamicShortcutSettingsBinding
    private lateinit var model: DynamicShortcutViewModel
    private var shownCandidates: List<DynamicShortcutModel>? = null
    private val adapter by lazy {
        DynamicShortcutAdapter(this, lifecycleScope,
            onClick = { record ->
                lifecycleScope.launch { ActionProxy(this@DynamicShortcutSettingsActivity, actionLink(record)).execute() }
            },
            onLongClick = { record ->
                ClipboardUtils.copyText(this, actionLink(record))
                ToastHelper.showInUIThread(this, getString(R.string.dynamic_shortcuts_link_copied))
            },
        )
    }
    private val homePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val allowed = DynamicShortcuts.hasPermission(this)
        AppLogger.event(
            LogModule.APP,
            "dynamic_shortcut_home_result",
            fields = mapOf("resultCode" to result.resultCode, "allowed" to allowed),
        )
        if (!allowed) {
            ToastHelper.showInUIThread(this, getString(R.string.dynamic_shortcuts_home_not_granted))
        }
        updatePermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[DynamicShortcutViewModel::class.java]
        binding = ActivityDynamicShortcutSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.importedList.layoutManager = LinearLayoutManager(this)
        binding.importedList.adapter = adapter
        binding.grantHome.setOnClickListener { requestHome() }
        binding.restoreHome.setOnClickListener { openHomeSettings() }
        binding.queryShortcuts.setOnClickListener { model.query() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    binding.progress.isVisible = state.busy
                    binding.status.text = state.message
                    binding.status.isVisible = state.message.isNotEmpty()
                    binding.emptyList.isVisible = !state.busy && state.imported.isEmpty()
                    adapter.submitList(state.imported)
                    binding.importedCard.setSummary(resources.getQuantityString(
                        R.plurals.dynamic_shortcuts_list_summary, state.imported.size, state.imported.size))
                    updatePermission()
                    val candidates = state.candidates
                    if (candidates != null && shownCandidates !== candidates) showSelection(candidates)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Returning from Appearance can change icons without changing the shortcut records.
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
        updatePermission()
        model.refresh()
    }

    private fun updatePermission() {
        val supported = Build.VERSION.SDK_INT >= 25
        val allowed = supported && DynamicShortcuts.hasPermission(this)
        val roleHeld = Build.VERSION.SDK_INT >= 29 &&
            getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME) == true
        binding.permissionStatus.setText(when {
            !supported -> R.string.dynamic_shortcuts_unsupported
            allowed -> R.string.dynamic_shortcuts_permission_ready
            roleHeld -> R.string.dynamic_shortcuts_permission_role_only
            else -> R.string.dynamic_shortcuts_permission_needed
        })
        binding.grantHome.isEnabled = supported && !allowed && !model.state.value.busy
        binding.queryShortcuts.isEnabled = allowed && !model.state.value.busy
        binding.restoreHome.isEnabled = !model.state.value.busy
    }

    private fun requestHome() {
        val roleManager = if (Build.VERSION.SDK_INT >= 29) {
            getSystemService(RoleManager::class.java)
        } else {
            null
        }
        val roleAvailable = roleManager?.isRoleAvailable(RoleManager.ROLE_HOME) == true
        val roleHeld = roleManager?.isRoleHeld(RoleManager.ROLE_HOME) == true
        val allowed = DynamicShortcuts.hasPermission(this)
        AppLogger.event(
            LogModule.APP,
            "dynamic_shortcut_home_request",
            fields = mapOf(
                "sdk" to Build.VERSION.SDK_INT,
                "roleAvailable" to roleAvailable,
                "roleHeld" to roleHeld,
                "allowed" to allowed,
            ),
        )

        if (roleManager == null || !roleAvailable) {
            ToastHelper.showInUIThread(this, getString(R.string.dynamic_shortcuts_home_unavailable))
            openHomeSettings()
            return
        }
        if (roleHeld) {
            // The role is already ours but LauncherApps still denies shortcut access. Re-requesting
            // the role would be a no-op, so send the user to the system home picker instead.
            ToastHelper.showInUIThread(this, getString(R.string.dynamic_shortcuts_permission_role_only))
            openHomeSettings()
            return
        }

        val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME)
        if (intent.resolveActivity(packageManager) == null) {
            ToastHelper.showInUIThread(this, getString(R.string.dynamic_shortcuts_home_unavailable))
            openHomeSettings()
            return
        }
        launchHomePicker(intent)
    }

    private fun openHomeSettings() {
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (_: Exception) {
            ToastHelper.showInUIThread(this, getString(R.string.dynamic_shortcuts_home_unavailable))
        }
    }

    private fun launchHomePicker(intent: Intent) {
        try {
            homePicker.launch(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            AppLogger.event(
                LogModule.APP,
                "dynamic_shortcut_home_intent_missing",
                LogLevel.WARN,
                fields = mapOf("action" to intent.action.orEmpty()),
            )
            ToastHelper.showInUIThread(this, getString(R.string.dynamic_shortcuts_home_unavailable))
            openHomeSettings()
        }
    }

    private fun showSelection(records: List<DynamicShortcutModel>) {
        shownCandidates = records
        showImportSelectionBottomSheet(
            activity = this,
            title = getString(R.string.dynamic_shortcuts_select),
            description = getString(R.string.dynamic_shortcuts_import_description),
            options = records.map { record ->
                ImportOption(
                    value = record,
                    title = record.label,
                    subtitle = adapter.appLabel(record),
                    packageName = record.packageName,
                    status = if (record.imported) getString(R.string.dynamic_shortcuts_already_imported) else "",
                    initiallySelected = record in model.selected,
                )
            },
            onSelectionChanged = { record, selected ->
                if (selected) model.selected.add(record) else model.selected.remove(record)
            },
            onCancelled = model::dismissCandidates,
            onImport = model::importSelected,
        )
    }

    private fun actionLink(record: DynamicShortcutModel): String = GestureActionLink(
        actionType = GestureActionType.OTHER_SHORTCUT,
    ).apply {
        addParams("uri", DynamicShortcuts.uri(record))
        addParams("pkg", record.packageName)
    }.buildLink()
}
