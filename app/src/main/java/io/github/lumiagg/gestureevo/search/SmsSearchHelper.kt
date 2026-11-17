package io.github.lumiagg.gestureevo.search

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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

        val escaped = keyword
            .replace(ESCAPE, ESCAPE + ESCAPE)
            .replace("%", ESCAPE + "%")
            .replace("_", ESCAPE + "_")
        val clauses =
            "(${Telephony.Sms.BODY} LIKE ? ESCAPE '$ESCAPE' " +
                "OR ${Telephony.Sms.ADDRESS} LIKE ? ESCAPE '$ESCAPE')"
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
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                clauses,
                args,
                order,
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
                val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                buildList {
                    while (cursor.moveToNext()) {
                        val body = cursor.getString(bodyIndex).orEmpty()
                        if (body.isBlank()) continue
                        add(
                            SmsHit(
                                id = cursor.getLong(idIndex),
                                address = cursor.getString(addressIndex).orEmpty(),
                                body = body,
                                date = cursor.getLong(dateIndex),
                            ),
                        )
                    }
                }
            }.orEmpty()
        }.getOrElse { emptyList() }
    }
}
