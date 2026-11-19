package com.idlogistics.scannercheckout

import android.content.Context
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/*
 * ============================================================================================
 *  POWER AUTOMATE CONTRACT (PROPOSED – to be aligned with the existing flow)
 *  All JSON field names live in [Payload] so aligning with the real flow is a one-file change.
 *
 *  One HTTP-triggered flow ("When an HTTP request is received"), POST, JSON body, with "Operation":
 *
 *  1) GetStatus  -> {"Operation":"GetStatus","SerialNumber":"26135A0195","AssetTag":"492447"}
 *     reply 200  <- {"Success":true,"Status":"CHECKED IN","Department":"INBOUND",
 *                    "UserName":"F123456","CheckInDate":"2026-11-17","CheckInTime":"10:04 AM",
 *                    "AssetTag":"492447"}
 *                   Status = "CHECKED IN" (open row exists) or "CHECKED OUT"/"NONE" (no open row)
 *
 *  2) Submit     -> {"Operation":"Submit","TransactionId":"uuid","SerialNumber":"…","AssetTag":"…",
 *                    "Department":"INBOUND","Action":"CHECK IN","UserName":"F123456","Model":"PM86",
 *                    "DeviceTimestamp":"2026-11-17T10:04:12-05:00","Date":"2026-11-17",
 *                    "Time":"10:04 AM","Offline":false,"AppVersion":"0.1.0"}
 *     reply 200  <- {"Success":true,"Message":"Saved"}
 *     reply 409  <- {"Success":false,"Code":"ALREADY_CHECKED_IN"|"NOT_CHECKED_IN"|"WRONG_DEPARTMENT"|
 *                    "ROW_NOT_FOUND"|"DUPLICATE","Message":"…"}
 *     CHECK OUT must UPDATE the open row for this SerialNumber (no new row).
 *     A repeated TransactionId must be treated as already done (return Success:true).
 *
 *  3) Register   -> {"Operation":"Register","SerialNumber":"…","AssetTag":"…","Force":false}
 *     reply 200/409 <- {"Success":true} or {"Success":false,"Code":"ASSET_TAG_IN_USE"|"ALREADY_REGISTERED"}
 *
 *  HTTP 5xx / 408 / 429 / timeouts / non-JSON replies are treated as "unavailable" (retry later).
 * ============================================================================================
 */
object Payload {
    const val OPERATION = "Operation"
    const val OP_GET_STATUS = "GetStatus"
    const val OP_SUBMIT = "Submit"
    const val OP_REGISTER = "Register"

    const val SERIAL = "SerialNumber"
    const val ASSET = "AssetTag"
    const val DEPARTMENT = "Department"
    const val ACTION = "Action"
    const val LOGIN = "UserName"          // existing flow field (was AssociateLogin in the draft)
    const val MODEL = "Model"             // existing flow field
    const val TX_ID = "TransactionId"
    const val DEVICE_TS = "DeviceTimestamp"
    const val DATE = "Date"
    const val TIME = "Time"
    const val OFFLINE = "Offline"
    const val APP_VERSION = "AppVersion"
    const val FORCE = "Force"

    const val R_SUCCESS = "Success"
    const val R_CODE = "Code"
    const val R_MESSAGE = "Message"
    const val R_STATUS = "Status"
    const val R_DEPARTMENT = "Department"
    const val R_LOGIN = "UserName"
    const val R_CHECKIN_DATE = "CheckInDate"
    const val R_CHECKIN_TIME = "CheckInTime"
    const val R_ASSET = "AssetTag"

    /** Excel "Status" column holds the Action text ("CHECK IN" / "CHECK OUT"); both spellings accepted. */
    val OPEN_STATUSES = setOf("CHECK IN", "CHECKED IN")
}

data class StatusResponse(
    val open: Boolean,
    val department: String?,
    val associate: String?,
    val since: String?,
    val centralAssetTag: String?
)

data class SubmitResponse(val success: Boolean, val code: String?, val message: String?)

/** The flow answered but refused (business rule). Not retried. */
class BackendRejected(val code: String?, message: String?) : Exception(message ?: "Rejected")

interface Backend {
    /** @throws IOException when the flow/network is unavailable. @throws BackendRejected */
    fun getStatus(serial: String, assetTag: String): StatusResponse
    /** @throws IOException when the flow/network is unavailable (caller queues the transaction). */
    fun submit(tx: Transaction): SubmitResponse
    /** @throws IOException when the flow/network is unavailable. */
    fun register(serial: String, assetTag: String, force: Boolean): SubmitResponse
    val isTest: Boolean
}

object BackendFactory {
    fun create(ctx: Context): Backend {
        val s = AppSettings(ctx)
        return if (s.isTestMode) LocalTestBackend(s) else PowerAutomateBackend(s.endpointUrl)
    }
}

class PowerAutomateBackend(private val url: String) : Backend {
    override val isTest = false

    private companion object {
        const val EMPTY_REPLY = "_emptyReply"
    }

    override fun getStatus(serial: String, assetTag: String): StatusResponse {
        val r = post(
            JSONObject()
                .put(Payload.OPERATION, Payload.OP_GET_STATUS)
                .put(Payload.SERIAL, serial)
                .put(Payload.ASSET, assetTag)
        )
        if (r.optBoolean(EMPTY_REPLY, false)) {
            // Without a Response action the flow cannot tell us the current state.
            throw BackendRejected("NO_RESPONSE_ACTION", "The flow returned no status. Add a 'Response' action to the flow.")
        }
        if (!r.optBoolean(Payload.R_SUCCESS, true)) {
            throw BackendRejected(r.strOrNull(Payload.R_CODE), r.strOrNull(Payload.R_MESSAGE))
        }
        val status = r.strOrNull(Payload.R_STATUS) ?: ""
        val date = r.strOrNull(Payload.R_CHECKIN_DATE)
        val time = r.strOrNull(Payload.R_CHECKIN_TIME)
        return StatusResponse(
            open = Payload.OPEN_STATUSES.any { it.equals(status, ignoreCase = true) },
            department = r.strOrNull(Payload.R_DEPARTMENT),
            associate = r.strOrNull(Payload.R_LOGIN),
            since = listOfNotNull(date, time).joinToString(" ").ifBlank { null },
            centralAssetTag = r.strOrNull(Payload.R_ASSET)
        )
    }

    override fun submit(tx: Transaction): SubmitResponse {
        val r = post(
            JSONObject()
                .put(Payload.OPERATION, Payload.OP_SUBMIT)
                .put(Payload.TX_ID, tx.id)
                .put(Payload.SERIAL, tx.serial)
                .put(Payload.ASSET, tx.assetTag)
                .put(Payload.DEPARTMENT, tx.department.name)
                .put(Payload.ACTION, tx.action.label)
                .put(Payload.LOGIN, tx.login)
                .put(Payload.MODEL, android.os.Build.MODEL ?: "PM86")
                .put(Payload.DEVICE_TS, tx.deviceTimestamp)
                .put(Payload.DATE, tx.date)
                .put(Payload.TIME, tx.time)
                .put(Payload.OFFLINE, tx.offline)
                .put(Payload.APP_VERSION, tx.appVersion)
        )
        return toSubmitResponse(r)
    }

    override fun register(serial: String, assetTag: String, force: Boolean): SubmitResponse {
        val r = post(
            JSONObject()
                .put(Payload.OPERATION, Payload.OP_REGISTER)
                .put(Payload.SERIAL, serial)
                .put(Payload.ASSET, assetTag)
                .put(Payload.FORCE, force)
        )
        return toSubmitResponse(r)
    }

    private fun toSubmitResponse(r: JSONObject) = SubmitResponse(
        success = r.optBoolean(Payload.R_SUCCESS, false),
        code = r.strOrNull(Payload.R_CODE),
        message = r.strOrNull(Payload.R_MESSAGE)
    )

    private fun post(body: JSONObject): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 25_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            if (code >= 500 || code == 408 || code == 429) throw IOException("Flow unavailable (HTTP $code)")

            if (code in 200..299 && text.isBlank()) {
                // Flow accepted the request but has no "Response" action (HTTP 202, empty body).
                return JSONObject().put(Payload.R_SUCCESS, true).put(EMPTY_REPLY, true)
            }
            val obj = if (text.isBlank()) JSONObject() else try {
                JSONObject(text)
            } catch (e: JSONException) {
                throw IOException("Unexpected reply (HTTP $code)")
            }
            if (code in 200..299) {
                if (!obj.has(Payload.R_SUCCESS)) obj.put(Payload.R_SUCCESS, true)
                return obj
            }
            // 4xx: a business rejection only if the flow sent a Success:false body; otherwise it is a
            // configuration problem (bad/expired URL) – treat as unavailable so nothing is lost.
            if (obj.has(Payload.R_SUCCESS)) return obj
            throw IOException("Flow returned HTTP $code")
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * TEST MODE backend (used while no Power Automate URL is configured).
 * Keeps the check-in state on the device so the scanner flow can be proven before the flow exists.
 * Nothing is sent to Excel – the app shows a yellow TEST MODE banner.
 */
class LocalTestBackend(private val s: AppSettings) : Backend {
    override val isTest = true

    override fun getStatus(serial: String, assetTag: String): StatusResponse {
        val st = s.testStateJson?.let { LocalState.fromJson(it) }
        return StatusResponse(st?.open == true, st?.department, st?.associate, st?.since, assetTag)
    }

    override fun submit(tx: Transaction): SubmitResponse {
        val st = s.testStateJson?.let { LocalState.fromJson(it) }
        val open = st?.open == true
        return when (tx.action) {
            Action.CHECK_IN -> {
                if (open) return SubmitResponse(false, "ALREADY_CHECKED_IN", "Scanner is already checked in to ${st?.department}.")
                s.testStateJson = LocalState(true, tx.department.name, tx.login, "${tx.date} ${tx.time}", "LOCAL").toJson()
                SubmitResponse(true, null, "Saved (test mode)")
            }
            Action.CHECK_OUT -> {
                if (!open) return SubmitResponse(false, "NOT_CHECKED_IN", "Scanner is not checked in.")
                if (!st!!.department.equals(tx.department.name, ignoreCase = true)) {
                    return SubmitResponse(false, "WRONG_DEPARTMENT", "Scanner is checked in to ${st.department}.")
                }
                s.testStateJson = LocalState(false, null, null, null, "LOCAL").toJson()
                SubmitResponse(true, null, "Saved (test mode)")
            }
        }
    }

    override fun register(serial: String, assetTag: String, force: Boolean) =
        SubmitResponse(true, null, "Registered locally (test mode)")
}
