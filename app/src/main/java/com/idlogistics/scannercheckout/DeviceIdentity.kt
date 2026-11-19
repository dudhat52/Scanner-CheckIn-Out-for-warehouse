package com.idlogistics.scannercheckout

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * PM86 serial number detection.
 *
 * Verified on PM86 (Android 11): ro.serialno = 26135A0195, and the same value appears in
 *   Settings.Global  net_hostname   = "PM86-26135A0195"
 *   Settings.Secure  bluetooth_name = "SN26135A0195"
 * Android 11 blocks Build.getSerial() for normal apps, so those settings are used, cross-checked
 * against the serial the PC tool reads over ADB (provisioned serial), which is authoritative.
 *
 * NOTE: the scan ENGINE has its own serial (/sys/n5600/serial, e.g. 26034B21FF). It is NOT the
 * device serial and is never used.
 */
object DeviceIdentity {

    data class Candidate(val value: String, val source: String)

    private val SERIAL_RE = Regex("^[A-Z0-9]{6,20}$")

    fun candidates(ctx: Context): List<Candidate> {
        val out = mutableListOf<Candidate>()
        tryBuildSerial()?.let { out += Candidate(it, "Build.getSerial") }

        Settings.Global.getString(ctx.contentResolver, "net_hostname")?.let { h ->
            // "PM86-26135A0195"
            val v = h.substringAfter('-', "").trim().uppercase()
            if (h.uppercase().startsWith("PM86-") && SERIAL_RE.matches(v)) out += Candidate(v, "net_hostname")
        }
        Settings.Secure.getString(ctx.contentResolver, "bluetooth_name")?.let { b ->
            // "SN26135A0195"
            val t = b.trim().uppercase()
            if (t.startsWith("SN")) {
                val v = t.removePrefix("SN")
                if (SERIAL_RE.matches(v)) out += Candidate(v, "bluetooth_name")
            }
        }
        return out
    }

    /** Best serial detected on the device itself (without ADB), or null. */
    fun detect(ctx: Context): Candidate? {
        val c = candidates(ctx)
        return c.firstOrNull { it.source == "Build.getSerial" }
            ?: c.firstOrNull { it.source == "net_hostname" }
            ?: c.firstOrNull()
    }

    @SuppressLint("HardwareIds", "MissingPermission")
    private fun tryBuildSerial(): String? = try {
        val s = Build.getSerial()?.trim()?.uppercase()
        if (s != null && s != "UNKNOWN" && SERIAL_RE.matches(s)) s else null
    } catch (e: SecurityException) {
        null
    } catch (e: Exception) {
        null
    }
}

/** Result of checking that this scanner has a usable, consistent identity. */
sealed class IdentityResult {
    data class Ok(val serial: String, val assetTag: String, val serialSource: String) : IdentityResult()
    data class Problem(val title: String, val message: String) : IdentityResult()
}

object Identity {
    fun check(ctx: Context, s: AppSettings = AppSettings(ctx)): IdentityResult {
        val detected = DeviceIdentity.detect(ctx)
        val provisioned = s.provisionedSerial

        // Provisioned (ADB) serial wins; detected value must agree with it.
        val serial: String
        val source: String
        if (provisioned != null) {
            if (detected != null && !detected.value.equals(provisioned, ignoreCase = true)) {
                return IdentityResult.Problem(
                    "Serial mismatch",
                    "This scanner reports ${detected.value} but was registered as $provisioned.\nContact IT."
                )
            }
            serial = provisioned
            source = "ADB registration"
        } else if (detected != null) {
            serial = detected.value
            source = detected.source
        } else {
            return IdentityResult.Problem(
                "Serial not found",
                "This scanner's serial number cannot be read.\nContact IT."
            )
        }

        val asset = s.assetTag
        if (asset.isNullOrBlank()) {
            return IdentityResult.Problem(
                "Scanner not registered",
                "No Asset Tag is assigned to this scanner.\nContact IT."
            )
        }
        val reg = s.registeredSerial
        if (reg != null && !reg.equals(serial, ignoreCase = true)) {
            return IdentityResult.Problem(
                "Registration mismatch",
                "Asset Tag $asset belongs to scanner $reg, but this is $serial.\nContact IT."
            )
        }
        return IdentityResult.Ok(serial, asset, source)
    }
}
