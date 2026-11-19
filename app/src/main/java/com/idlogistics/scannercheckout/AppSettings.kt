package com.idlogistics.scannercheckout

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * All local configuration. Survives reboot and power loss (SharedPreferences on internal storage).
 * The check-in/out STATE is authoritative in Power Automate/Excel; [lastState] is only a cache
 * used when the network is unavailable.
 */
class AppSettings(context: Context) {

    private val p: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- QR code values (configurable until final values are confirmed) ----
    var inboundCode: String
        get() = p.getString(K_INBOUND, DEF_INBOUND) ?: DEF_INBOUND
        set(v) { p.edit().putString(K_INBOUND, v.trim()).apply() }
    var outboundCode: String
        get() = p.getString(K_OUTBOUND, DEF_OUTBOUND) ?: DEF_OUTBOUND
        set(v) { p.edit().putString(K_OUTBOUND, v.trim()).apply() }
    var shipdockCode: String
        get() = p.getString(K_SHIPDOCK, DEF_SHIPDOCK) ?: DEF_SHIPDOCK
        set(v) { p.edit().putString(K_SHIPDOCK, v.trim()).apply() }
    var icqaCode: String
        get() = p.getString(K_ICQA, DEF_ICQA) ?: DEF_ICQA
        set(v) { p.edit().putString(K_ICQA, v.trim()).apply() }
    var adminCode: String
        get() = p.getString(K_ADMIN, DEF_ADMIN) ?: DEF_ADMIN
        set(v) { p.edit().putString(K_ADMIN, v.trim()).apply() }

    // ---- Behaviour ----
    var debounceMs: Long
        get() = p.getLong(K_DEBOUNCE, 3_000L)
        set(v) { p.edit().putLong(K_DEBOUNCE, v.coerceIn(500L, 60_000L)).apply() }
    var cooldownMs: Long
        get() = p.getLong(K_COOLDOWN, 10_000L)
        set(v) { p.edit().putLong(K_COOLDOWN, v.coerceIn(0L, 600_000L)).apply() }
    var loginTimeoutSec: Int
        get() = p.getInt(K_LOGIN_TIMEOUT, 60)
        set(v) { p.edit().putInt(K_LOGIN_TIMEOUT, v.coerceIn(15, 600)).apply() }
    var allowCrossDepartmentCheckout: Boolean
        get() = p.getBoolean(K_CROSS, false)
        set(v) { p.edit().putBoolean(K_CROSS, v).apply() }
    var timeZoneId: String
        get() = p.getString(K_TZ, DEF_TZ) ?: DEF_TZ
        set(v) { p.edit().putString(K_TZ, v).apply() }
    /** Login ID format check. Blank is always rejected. */
    var loginPattern: String
        get() = p.getString(K_LOGIN_PATTERN, DEF_LOGIN_PATTERN) ?: DEF_LOGIN_PATTERN
        set(v) { p.edit().putString(K_LOGIN_PATTERN, v).apply() }

    // ---- Scanner registration (IT only) ----
    val assetTag: String? get() = p.getString(K_ASSET, null)
    val registeredSerial: String? get() = p.getString(K_REG_SERIAL, null)
    val registeredAt: String? get() = p.getString(K_REG_AT, null)
    /** Serial read over ADB (ro.serialno) by the PC registration tool – most trustworthy source. */
    val provisionedSerial: String? get() = p.getString(K_PROV_SERIAL, null)

    fun saveRegistration(serial: String, assetTag: String, at: String, fromAdb: Boolean) {
        val e = p.edit()
            .putString(K_ASSET, assetTag)
            .putString(K_REG_SERIAL, serial)
            .putString(K_REG_AT, at)
        if (fromAdb) e.putString(K_PROV_SERIAL, serial)
        e.commit()
    }

    // ---- Power Automate endpoint (never shown in normal UI) ----
    var endpointUrl: String
        get() = p.getString(K_ENDPOINT, "") ?: ""
        set(v) { p.edit().putString(K_ENDPOINT, v.trim()).commit() }
    val isTestMode: Boolean get() = endpointUrl.isBlank()

    // ---- Admin PIN ----
    var pinHash: String?
        get() = p.getString(K_PIN_HASH, null)
        set(v) { p.edit().putString(K_PIN_HASH, v).commit() }
    var pinSalt: String?
        get() = p.getString(K_PIN_SALT, null)
        set(v) { p.edit().putString(K_PIN_SALT, v).commit() }
    var pinFails: Int
        get() = p.getInt(K_PIN_FAILS, 0)
        set(v) { p.edit().putInt(K_PIN_FAILS, v).commit() }
    var pinLockUntil: Long
        get() = p.getLong(K_PIN_LOCK, 0L)
        set(v) { p.edit().putLong(K_PIN_LOCK, v).commit() }

    // ---- Cached state (fallback only) ----
    var lastState: LocalState?
        get() = p.getString(K_LAST_STATE, null)?.let { LocalState.fromJson(it) }
        set(v) { p.edit().putString(K_LAST_STATE, v?.toJson()).commit() }

    // ---- Test-mode backend storage ----
    var testStateJson: String?
        get() = p.getString(K_TEST_STATE, null)
        set(v) { p.edit().putString(K_TEST_STATE, v).commit() }

    companion object {
        private const val PREFS = "scanner_checkout_settings"

        const val DEF_INBOUND = "IDLOGISTICS_INBOUND"
        const val DEF_OUTBOUND = "IDLOGISTICS_OUTBOUND"
        const val DEF_SHIPDOCK = "IDLOGISTICS_SHIPDOCK"
        const val DEF_ICQA = "IDLOGISTICS_ICQA"
        const val DEF_ADMIN = "IDLOGISTICS_ADMIN"
        const val DEF_TZ = "America/Toronto"
        const val DEF_LOGIN_PATTERN = "^[A-Za-z0-9._-]{2,30}$"

        private const val K_INBOUND = "code_inbound"
        private const val K_OUTBOUND = "code_outbound"
        private const val K_SHIPDOCK = "code_shipdock"
        private const val K_ICQA = "code_icqa"
        private const val K_ADMIN = "code_admin"
        private const val K_DEBOUNCE = "debounce_ms"
        private const val K_COOLDOWN = "cooldown_ms"
        private const val K_LOGIN_TIMEOUT = "login_timeout_sec"
        private const val K_CROSS = "allow_cross_dept"
        private const val K_TZ = "time_zone"
        private const val K_LOGIN_PATTERN = "login_pattern"
        private const val K_ASSET = "asset_tag"
        private const val K_REG_SERIAL = "registered_serial"
        private const val K_REG_AT = "registered_at"
        private const val K_PROV_SERIAL = "provisioned_serial"
        private const val K_ENDPOINT = "endpoint_url"
        private const val K_PIN_HASH = "pin_hash"
        private const val K_PIN_SALT = "pin_salt"
        private const val K_PIN_FAILS = "pin_fails"
        private const val K_PIN_LOCK = "pin_lock_until"
        private const val K_LAST_STATE = "last_state"
        private const val K_TEST_STATE = "test_state"
    }
}

/** Snapshot of this scanner's open/closed record. */
data class LocalState(
    val open: Boolean,
    val department: String?,
    val associate: String?,
    val since: String?,
    val source: String          // "CENTRAL" or "LOCAL"
) {
    fun toJson(): String = JSONObject()
        .put("open", open)
        .put("department", department ?: JSONObject.NULL)
        .put("associate", associate ?: JSONObject.NULL)
        .put("since", since ?: JSONObject.NULL)
        .put("source", source)
        .toString()

    companion object {
        fun fromJson(s: String): LocalState? = try {
            val o = JSONObject(s)
            LocalState(
                o.optBoolean("open", false),
                o.strOrNull("department"),
                o.strOrNull("associate"),
                o.strOrNull("since"),
                o.optString("source", "LOCAL")
            )
        } catch (e: Exception) {
            null
        }
    }
}

/** JSONObject.optString returns "" or "null" for missing values; this returns a real null. */
fun JSONObject.strOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).trim().takeIf { it.isNotEmpty() } else null
