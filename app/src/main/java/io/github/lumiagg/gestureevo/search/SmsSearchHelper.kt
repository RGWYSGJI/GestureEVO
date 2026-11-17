package io.github.lumiagg.gestureevo.search

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import java.util.Locale

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


    /**
     * Splits a query into words so that all words must occur somewhere in the
     * message, but they do not have to occur as one contiguous phrase.
     *
     * ICU's word iterator handles CJK text better than splitting on whitespace
     * alone. If it still returns a single long CJK token, fall back to
     * non-overlapping two-character chunks so that a phrase like "微信支付"
     * can match "微信……支付".
     */
    private fun tokenize(keyword: String): List<String> {
        val iterator = android.icu.text.BreakIterator.getWordInstance(Locale.CHINA)
        iterator.setText(keyword)
        val words = mutableListOf<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != android.icu.text.BreakIterator.DONE) {
            val part = keyword.substring(start, end).trim()
            if (part.isNotEmpty() && part.any { it.isLetterOrDigit() }) {
                words += part
            }
            start = end
            end = iterator.next()
        }

        if (words.size > 1) return words

        val single = words.firstOrNull().orEmpty()
        val isLongCjk = single.length >= 3 && single.all { it.isCjk() }
        return if (isLongCjk) single.chunked(2) else words
    }

    private fun Char.isCjk(): Boolean =
        this in '\u4E00'..'\u9FFF' || this in '\u3400'..'\u4DBF'

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

        val tokens = tokenize(keyword)
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
