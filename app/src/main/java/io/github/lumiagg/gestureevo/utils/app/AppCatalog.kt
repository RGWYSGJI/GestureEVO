package io.github.lumiagg.gestureevo.utils.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Build
import android.os.Process
import android.os.UserManager
import androidx.room.withTransaction
import io.github.lumiagg.gestureevo.db.APPDao
import io.github.lumiagg.gestureevo.db.APPModel
import io.github.lumiagg.gestureevo.db.DatabaseManager
import io.github.lumiagg.gestureevo.db.getAPPDao
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.saveKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val KEY_LAST_APP_SYNC_TIME = "lastAPPsSyncTime"
private const val KEY_LAST_APP_HASH = "lastAPPsHash"
private val appSyncMutex = Mutex()

suspend fun syncAppCatalog(context: Context) = appSyncMutex.withLock {
    syncAppCatalogUnlocked(context)
}

private suspend fun syncAppCatalogUnlocked(context: Context) = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES
    val installedApps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(flags.toLong()))
    } else {
        @Suppress("DEPRECATION")
        pm.getInstalledApplications(flags)
    }.filter { it.flags and ApplicationInfo.FLAG_INSTALLED != 0 }
        .associateBy { it.packageName }

    val database = DatabaseManager.db
    database.withTransaction {
        val dao = database.appDao
        val known = dao.getAll()
        // Main-profile rows keep the existing install/uninstall reconciliation. Rows for
        // secondary profiles are handled separately so they never mark the main app removed.
        val mainKnown = known.filter { it.userId == 0 }
        val plan = planAppCatalogSync(installedApps.keys, mainKnown.map { CatalogPackage(it.pkgName, it.isRemove) })
        val changes = plan.added.map { packageName ->
            val info = installedApps.getValue(packageName)
            APPModel(appDisplayName(context, info) ?: packageName, packageName).apply {
                refreshCatalogMetadata(context, info)
            }
        }
        if (changes.isNotEmpty()) dao.insert(*changes.toTypedArray())
        for (model in mainKnown) {
            val info = installedApps[model.pkgName]
            if (info != null) {
                model.refreshCatalogMetadata(context, info)
                dao.update(model)
            } else if (model.pkgName in plan.removed) {
                model.isRemove = true
                dao.update(model)
            }
        }
        syncSecondaryProfileApps(context, dao, known)
    }
    // Preserve the persisted key for existing backups; commit it only after database success.
    context.settingsStore.saveKey(KEY_LAST_APP_HASH, installedApps.keys.hashCode())
}

/**
 * Mirrors launcher-visible apps that live in a secondary user/profile (e.g. MagicOS App Twin,
 * Android work profile). These rows carry the profile serial in [APPModel.userId]; the package
 * manager scan above only sees the current profile, which is why clones used to be invisible.
 */
private suspend fun syncSecondaryProfileApps(context: Context, dao: APPDao, known: List<APPModel>) {
    val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager ?: return
    val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps ?: return
    val currentUser = Process.myUserHandle()
    val seen = HashSet<Pair<String, Int>>()
    val secondarySerials = HashSet<Int>()

    for (profile in userManager.userProfiles) {
        if (profile == currentUser) continue
        val serial = runCatching { userManager.getSerialNumberForUser(profile).toInt() }
            .getOrNull() ?: continue
        if (serial <= 0) continue
        secondarySerials.add(serial)
        val activities = runCatching { launcherApps.getActivityList(null, profile) }
            .getOrNull().orEmpty()
        for (activity in activities) {
            val pkg = activity.componentName.packageName
            if (!seen.add(pkg to serial)) continue
            val model = known.firstOrNull { it.pkgName == pkg && it.userId == serial }
                ?: dao.findByPkgAndUser(pkg, serial)
            val label = runCatching { activity.label?.toString() }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: pkg
            val displayName = if (label.contains("分身") || label.contains("双开")) {
                label
            } else {
                "$label（分身）"
            }
            val target = model ?: APPModel(displayName, pkg)
            if (target.name != displayName) target.name = displayName
            target.userId = serial
            target.isRemove = false
            target.setState(APPModel.FLAG_LAUNCHER, true)
            target.setState(APPModel.FLAG_NO_LAUNCHER, false)
            val info = activity.applicationInfo
            val system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
            target.setState(APPModel.FLAG_SYSTEM, system)
            target.setState(APPModel.FLAG_USER, !system)
            if (model == null) {
                dao.insert(target)
            } else {
                dao.update(target)
            }
        }
    }

    // A clone can be removed while its main app remains installed.
    for (model in known) {
        if (model.userId != 0 && model.userId in secondarySerials &&
            (model.pkgName to model.userId) !in seen && !model.isRemove
        ) {
            model.isRemove = true
            dao.update(model)
        }
    }
}

/** Read current package state so queued broadcasts cannot apply stale install/remove events. */
internal suspend fun syncAppCatalogPackage(context: Context, packageName: String) = appSyncMutex.withLock {
    withContext(Dispatchers.IO) {
        val info = context.packageManager.applicationInfoOrNull(packageName)
            ?.takeIf { it.flags and ApplicationInfo.FLAG_INSTALLED != 0 }
        val database = DatabaseManager.db
        database.withTransaction {
            val dao = database.appDao
            val existing = dao.findByPkg(packageName)
            if (info == null) {
                if (existing != null && !existing.isRemove) {
                    existing.isRemove = true
                    dao.update(existing)
                }
            } else {
                val model = existing ?: APPModel(packageName, packageName)
                model.refreshCatalogMetadata(context, info)
                if (existing == null) dao.insert(model) else dao.update(model)
            }
        }
    }
}

private fun APPModel.refreshCatalogMetadata(context: Context, info: ApplicationInfo) {
    appDisplayName(context, info)?.let { if (name != it) name = it }
    isRemove = false
    // Preserve support for launching frozen apps and all unrelated user-configured flags.
    val launchable = !info.enabled || context.packageManager.getLaunchIntentForPackage(pkgName) != null
    setState(APPModel.FLAG_LAUNCHER, launchable)
    setState(APPModel.FLAG_NO_LAUNCHER, !launchable)
    val system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
    setState(APPModel.FLAG_SYSTEM, system)
    setState(APPModel.FLAG_USER, !system)
}

suspend fun appDisplayNameFromDatabase(context: Context, packageName: String): String? =
    getAPPDao().findByPkg(packageName)?.name ?: appDisplayName(context, packageName)

fun appDisplayName(context: Context, packageName: String): String? =
    context.packageManager.applicationInfoOrNull(packageName)?.let { appDisplayName(context, it) }

fun appDisplayName(context: Context, info: ApplicationInfo): String? = try {
    context.packageManager.getApplicationLabel(info).toString()
} catch (_: Resources.NotFoundException) {
    null
}

fun isActivityExported(context: Context, packageName: String, className: String): Boolean = try {
    @Suppress("DEPRECATION")
    context.packageManager.getActivityInfo(ComponentName(packageName, className), 0).exported
} catch (_: PackageManager.NameNotFoundException) {
    false
}

suspend fun syncAppCatalogIfNeeded(context: Context, minIntervalMs: Long = 60_000L) = appSyncMutex.withLock {
    val now = System.currentTimeMillis()
    val lastSync = context.settingsStore.readKey(KEY_LAST_APP_SYNC_TIME, 0L)
    if (now >= lastSync && now - lastSync < minIntervalMs) return@withLock
    syncAppCatalogUnlocked(context)
    context.settingsStore.saveKey(KEY_LAST_APP_SYNC_TIME, System.currentTimeMillis())
}
