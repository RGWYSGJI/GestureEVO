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
    private const val MAX_RESULTS = 100
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

    fun search(context: Context, query: String): List<SmsHit> {
        val keyword = query.trim()
        if (keyword.isEmpty() || !hasSmsPermission(context)) return emptyList()

        val tokens = keyword
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .map { token ->
                token
                    .replace(ESCAPE, ESCAPE + ESCAPE)
                    .replace("%", ESCAPE + "%")
                    .replace("_", ESCAPE + "_")
            }
        if (tokens.isEmpty()) return emptyList()

        val clauses = tokens.joinToString(" AND ") {
            "(${Telephony.Sms.BODY} LIKE ? ESCAPE '$ESCAPE' " +
                "OR ${Telephony.Sms.ADDRESS} LIKE ? ESCAPE '$ESCAPE')"
        }
        val args = tokens
            .flatMap { token -> listOf("%$token%", "%$token%") }
            .toTypedArray()
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
        )
        val order = "${Telephony.Sms.DATE} DESC"

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
                    while (cursor.moveToNext() && size < MAX_RESULTS) {
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
