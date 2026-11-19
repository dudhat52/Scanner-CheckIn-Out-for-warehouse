package com.idlogistics.scannercheckout

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives the PM86 ScanSettings broadcast (action device.scanner.EVENT).
 *
 * Registered two ways (both deliver here; ScanRouter removes duplicates):
 *  1. Dynamically by ScanListenerService – the path that works for real scans on Android 11,
 *     including when MainActivity is closed or another app is in front.
 *  2. In the manifest – used for explicit adb test broadcasts.
 */
class ScanReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ScannerContract.ACTION) return
        val value = ScanParser.extract(intent)
        if (value == null) {
            Log.d(TAG, "Ignored blank/failed scan broadcast")
            return
        }
        ScanRouter.route(context.applicationContext, value)
    }

    companion object {
        private const val TAG = "ScanReceiver"
    }
}

object ScanParser {
    private val CONTROL_CHARS = Regex("[\\r\\n\\t\\u0000]")

    /** Returns the decoded text, or null for failed decodes, blank or oversized values. */
    fun extract(intent: Intent): String? {
        if (intent.hasExtra(ScannerContract.EXTRA_DECODE_RESULT) &&
            !intent.getBooleanExtra(ScannerContract.EXTRA_DECODE_RESULT, true)
        ) return null

        var v: String? = try {
            intent.getStringExtra(ScannerContract.EXTRA_DECODE_STRING)
        } catch (e: Exception) {
            null
        }
        if (v.isNullOrBlank()) {
            val bytes = try {
                intent.getByteArrayExtra(ScannerContract.EXTRA_DECODE_BYTES)
            } catch (e: Exception) {
                null
            }
            if (bytes != null && bytes.isNotEmpty()) v = String(bytes, Charsets.UTF_8)
        }
        val cleaned = v?.replace(CONTROL_CHARS, "")?.trim()
        if (cleaned.isNullOrEmpty() || cleaned.length > ScannerContract.MAX_VALUE_LENGTH) return null
        return cleaned
    }
}
