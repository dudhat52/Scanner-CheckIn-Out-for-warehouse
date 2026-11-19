package com.idlogistics.scannercheckout

/**
 * Point Mobile PM86 ScanSettings "Custom intent" output – values verified on the device
 * (PM86, Android 11, build 86.06, ScanSettings 2.27.22, Result type = RESULT_CUSTOM_INTENT).
 *
 * These are the ScanSettings DEFAULTS. Leave them unchanged on every scanner.
 */
object ScannerContract {
    const val ACTION = "device.scanner.EVENT"
    const val CATEGORY = "android.intent.category.DEFAULT"

    const val EXTRA_DECODE_RESULT = "EXTRA_EVENT_DECODE_RESULT"          // Boolean
    const val EXTRA_DECODE_BYTES = "EXTRA_EVENT_DECODE_VALUE"            // ByteArray
    const val EXTRA_DECODE_STRING = "EXTRA_EVENT_DECODE_STRING_VALUE"    // String  <- primary
    const val EXTRA_DECODE_LENGTH = "EXTRA_EVENT_DECODE_LENGTH"          // Int
    const val EXTRA_SYMBOL_NAME = "EXTRA_EVENT_SYMBOL_NAME"              // String

    /** Values longer than this are never department/admin codes; ignore them. */
    const val MAX_VALUE_LENGTH = 256
}
