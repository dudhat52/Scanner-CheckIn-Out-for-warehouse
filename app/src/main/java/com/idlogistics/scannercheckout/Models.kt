package com.idlogistics.scannercheckout

import org.json.JSONObject

enum class Department {
    INBOUND, OUTBOUND, SHIPDOCK, ICQA;

    companion object {
        /** Maps a scanned value to a department using the configured QR values (exact match, case-insensitive). */
        fun fromScan(value: String, s: AppSettings): Department? {
            val v = value.trim()
            return when {
                v.equals(s.inboundCode, ignoreCase = true) -> INBOUND
                v.equals(s.outboundCode, ignoreCase = true) -> OUTBOUND
                v.equals(s.shipdockCode, ignoreCase = true) -> SHIPDOCK
                v.equals(s.icqaCode, ignore = true) -> ICQA
                else -> null
            }
        }

        fun fromName(name: String?): Department? =
            values().firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) }
    }
}

enum class Action(val label: String) {
    CHECK_IN("CHECK IN"),
    CHECK_OUT("CHECK OUT");
}

/** One check-in or check-out. [id] makes uploads idempotent (the flow can ignore a repeated id). */
data class Transaction(
    val id: String,
    val serial: String,
    val assetTag: String,
    val department: Department,
    val action: Action,
    val login: String,
    val deviceTimestamp: String,   // ISO-8601 with offset, warehouse time zone
    val date: String,              // yyyy-MM-dd
    val time: String,              // h:mm a
    val offline: Boolean,
    val appVersion: String
) {
    fun toJson(): String = JSONObject()
        .put("id", id).put("serial", serial).put("assetTag", assetTag)
        .put("department", department.name).put("action", action.name)
        .put("login", login).put("deviceTimestamp", deviceTimestamp)
        .put("date", date).put("time", time).put("offline", offline)
        .put("appVersion", appVersion)
        .toString()

    companion object {
        fun fromJson(s: String): Transaction? = try {
            val o = JSONObject(s)
            Transaction(
                o.getString("id"), o.getString("serial"), o.getString("assetTag"),
                Department.valueOf(o.getString("department")), Action.valueOf(o.getString("action")),
                o.getString("login"), o.getString("deviceTimestamp"),
                o.getString("date"), o.getString("time"),
                o.optBoolean("offline", true), o.optString("appVersion", "")
            )
        } catch (e: Exception) {
            null
        }
    }
}
