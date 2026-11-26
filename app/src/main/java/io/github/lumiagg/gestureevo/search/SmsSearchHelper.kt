package io.github.lumiagg.gestureevo.search

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.Telephony
import androidx.core.content.ContextCompat

/**
 * Reads SMS messages directly from the system provider.
 *
 * The messages are not copied into GestureEVO's database; this keeps the
 * optional search feature privacy-friendly and always up to date.
 */
object SmsSearchHelper {
    private const val ESCAPE = "\\"

    data class SmsHit(
        val id: Long,
        val address: String,
        val body: String,
        val date: Long,
    )

    fun hasSmsPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_SMS,
        ) == PackageManager.PERMISSION_GRANTED

    fun search(
        context: Context,
        query: String,
        limit: Int = 10,
        offset: Int = 0,
    ): List<SmsHit> {
        val keyword = query.trim()
        if (keyword.isEmpty() || limit <= 0 || offset < 0 || !hasSmsPermission(context)) {
            return emptyList()
        }

        val selection =
            "(${Telephony.Sms.BODY} LIKE ? ESCAPE '$ESCAPE' " +
                "OR ${Telephony.Sms.ADDRESS} LIKE ? ESCAPE '$ESCAPE')"
        val escaped = escapeLike(keyword)
        val args = arrayOf("%$escaped%", "%$escaped%")
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
        )
        // Fetch one extra row so the caller can tell whether another page exists.
        val order = "${Telephony.Sms.DATE} DESC LIMIT ${limit + 1} OFFSET $offset"

        return runCatching {
            context.contentResolver
                .query(Telephony.Sms.CONTENT_URI, projection, selection, args, order)
                ?.use { cursor -> cursor.readHits() }
                .orEmpty()
        }.getOrElse { emptyList() }
    }

    private fun escapeLike(value: String): String =
        value
            .replace(ESCAPE, ESCAPE + ESCAPE)
            .replace("%", ESCAPE + "%")
            .replace("_", ESCAPE + "_")

    private fun Cursor.readHits(): List<SmsHit> {
        val idIndex = getColumnIndexOrThrow(Telephony.Sms._ID)
        val addressIndex = getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
        val bodyIndex = getColumnIndexOrThrow(Telephony.Sms.BODY)
        val dateIndex = getColumnIndexOrThrow(Telephony.Sms.DATE)

        return buildList {
            while (moveToNext()) {
                val body = getString(bodyIndex).orEmpty()
                if (body.isBlank()) continue
                add(
                    SmsHit(
                        id = getLong(idIndex),
                        address = getString(addressIndex).orEmpty(),
                        body = body,
                        date = getLong(dateIndex),
                    ),
                )
            }
        }
    }
}
