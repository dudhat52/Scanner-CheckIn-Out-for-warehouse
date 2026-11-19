package com.idlogistics.scannercheckout

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * IT provisioning over ADB (used by tools/register_scanner.ps1). Protected by
 * android.permission.DUMP – the ADB shell has it, normal apps cannot get it.
 *
 *  Register:  adb shell am broadcast -a com.idlogistics.scannercheckout.PROVISION
 *               -n com.idlogistics.scannercheckout/.ProvisionReceiver
 *               --es cmd register --es serial 26135A0195 --es asset_tag 492447 [--ez force true]
 *  Status:    ... --es cmd status
 *  Config:    ... --es cmd config [--es endpoint_url '<url>'] [--es admin_pin 123456]
 *
 * Result code printed by "am broadcast": 1 OK, 2 ALREADY_REGISTERED, 3 INVALID, 4 SERIAL_MISMATCH.
 */
class ProvisionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        val s = AppSettings(ctx)
        when (intent.getStringExtra("cmd")?.lowercase()) {
            "register" -> register(ctx, s, intent)
            "config" -> config(ctx, s, intent)
            else -> reply(RESULT_OK_CODE, status(ctx, s))
        }
    }

    private fun register(ctx: Context, s: AppSettings, intent: Intent) {
        val serial = intent.getStringExtra("serial")?.trim()?.uppercase()
        val asset = intent.getStringExtra("asset_tag")?.trim()
        val force = intent.getBooleanExtra("force", false)

        if (serial.isNullOrEmpty() || !serial.matches(Regex("^[A-Z0-9]{6,20}$"))) {
            return reply(RESULT_INVALID, "INVALID serial")
        }
        if (asset.isNullOrEmpty() || !asset.matches(Regex("^\\d{6}$"))) {
            return reply(RESULT_INVALID, "INVALID asset_tag (must be 6 digits)")
        }
        val detected = DeviceIdentity.detect(ctx)
        if (detected != null && !detected.value.equals(serial, ignoreCase = true) && !force) {
            return reply(RESULT_SERIAL_MISMATCH, "SERIAL_MISMATCH device reports ${detected.value} (${detected.source})")
        }
        val existing = s.assetTag
        if (existing != null && existing != asset && !force) {
            return reply(RESULT_ALREADY, "ALREADY_REGISTERED asset_tag=$existing")
        }
        s.saveRegistration(serial, asset, WarehouseClock.today(s), fromAdb = true)
        s.lastState = null
        reply(RESULT_OK_CODE, "OK serial=$serial asset_tag=$asset")
    }

    private fun config(ctx: Context, s: AppSettings, intent: Intent) {
        val msgs = mutableListOf<String>()
        val url: String? = intent.getStringExtra("endpoint_url")?.trim()
        val pin: String? = intent.getStringExtra("admin_pin")

        // Validate everything first, then apply.
        if (url != null && url.isNotEmpty() && !url.startsWith("https://")) {
            reply(RESULT_INVALID, "INVALID endpoint_url (must start with https://)")
            return
        }
        if (pin != null && !PinManager.isValidNewPin(pin)) {
            reply(RESULT_INVALID, "INVALID admin_pin (6+ digits)")
            return
        }
        if (url != null) {
            s.endpointUrl = url
            s.lastState = null
            msgs += if (url.isEmpty()) "endpoint cleared (TEST MODE)" else "endpoint set"
        }
        if (pin != null) {
            PinManager.set(s, pin)
            msgs += "admin PIN set"
        }
        reply(RESULT_OK_CODE, "OK " + msgs.joinToString("; ").ifEmpty { "nothing changed" })
    }

    private fun status(ctx: Context, s: AppSettings): String {
        val d = DeviceIdentity.detect(ctx)
        return listOf(
            "detected_serial=${d?.value ?: "NONE"}(${d?.source ?: "-"})",
            "registered_serial=${s.registeredSerial ?: "NONE"}",
            "asset_tag=${s.assetTag ?: "NONE"}",
            "endpoint=${if (s.isTestMode) "TEST_MODE" else "SET"}",
            "admin_pin=${if (PinManager.isSet(s)) "SET" else "NOT_SET"}",
            "overlay=${if (Settings.canDrawOverlays(ctx)) "GRANTED" else "MISSING"}",
            "pending=${QueueStore.pendingCount(ctx)}",
            "version=${BuildConfig.VERSION_NAME}"
        ).joinToString(";")
    }

    private fun reply(code: Int, data: String) {
        if (isOrderedBroadcast) {
            resultCode = code
            resultData = data
        }
    }

    companion object {
        const val RESULT_OK_CODE = 1
        const val RESULT_ALREADY = 2
        const val RESULT_INVALID = 3
        const val RESULT_SERIAL_MISMATCH = 4
    }
}
