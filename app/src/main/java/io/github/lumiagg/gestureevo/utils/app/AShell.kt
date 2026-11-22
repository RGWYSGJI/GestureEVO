package io.github.lumiagg.gestureevo.utils.app

import android.content.Context

/**
 * Runs shell commands through Shizuku only. There is deliberately no root fallback:
 * callers either use the Shizuku user service or report failure to the user.
 */
object AShell {
    suspend fun doCmd(context: Context, command: String): Pair<Boolean, String> {
        if (!AShizuku.checkPermission(context)) {
            return false to "Shizuku permission not granted"
        }
        return AShizuku.doCmdWithShizuku(command)
    }
}
