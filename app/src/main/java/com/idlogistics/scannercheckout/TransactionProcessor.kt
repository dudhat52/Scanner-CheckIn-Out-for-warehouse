package com.idlogistics.scannercheckout

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

sealed class Decision {
    data class Proceed(
        val department: Department,
        val action: Action,
        val offline: Boolean,
        val note: String?
    ) : Decision()

    data class Blocked(val title: String, val message: String) : Decision()
}

sealed class SubmitOutcome {
    data class Saved(val message: String?) : SubmitOutcome()
    object Queued : SubmitOutcome()
    data class Rejected(val title: String, val message: String) : SubmitOutcome()
}

/**
 * Business logic: identity checks, CHECK IN / CHECK OUT decision, submit with offline queue.
 * Runs on [Bg.io]; callbacks are delivered on the main thread.
 *
 * DECISION RULES
 *  - No open record              -> CHECK IN to the scanned department.
 *  - Open record, same dept      -> CHECK OUT (updates the same row).
 *  - Open record, different dept -> BLOCKED: "Checked in to INBOUND. Scan the INBOUND QR to check
 *                                   out first." Nothing is overwritten. (Optional admin setting
 *                                   allows checking out the open department instead.)
 *
 * STATE SOURCE
 *  - Central (Power Automate) is asked first and is authoritative; the answer is cached.
 *  - If transactions are still queued on this device, the device is ahead of Excel, so the cached
 *    local state is used until the queue is sent.
 *  - If the flow is unreachable, the cached state is used and the transaction is queued.
 *    With no cached state at all (e.g. fresh install while offline) the scan is refused.
 */
object TransactionProcessor {
    private val main = Handler(Looper.getMainLooper())

    fun decide(ctx: Context, scanned: Department, cb: (Decision) -> Unit) {
        val app = ctx.applicationContext
        Bg.io.execute {
            val d = try {
                decideBlocking(app, scanned)
            } catch (e: Exception) {
                Decision.Blocked("Error", "Something went wrong. Try again.\n(${e.javaClass.simpleName})")
            }
            main.post { cb(d) }
        }
    }

    private fun decideBlocking(ctx: Context, scanned: Department): Decision {
        val s = AppSettings(ctx)
        val id = when (val r = Identity.check(ctx, s)) {
            is IdentityResult.Problem -> return Decision.Blocked(r.title, r.message)
            is IdentityResult.Ok -> r
        }

        // Send anything waiting first so the central answer is up to date.
        SyncManager.flushNow(ctx)

        var offline = false
        val state: LocalState = if (QueueStore.pendingCount(ctx) > 0) {
            offline = true
            s.lastState ?: LocalState(false, null, null, null, "LOCAL")
        } else {
            val backend = BackendFactory.create(ctx)
            try {
                val st = backend.getStatus(id.serial, id.assetTag)
                val central = st.centralAssetTag
                if (!central.isNullOrBlank() && !central.equals(id.assetTag, ignoreCase = true)) {
                    return Decision.Blocked(
                        "Asset Tag mismatch",
                        "This scanner is set to ${id.assetTag} but the master list says $central.\nContact IT."
                    )
                }
                LocalState(st.open, st.department, st.associate, st.since, "CENTRAL").also { s.lastState = it }
            } catch (e: BackendRejected) {
                return Decision.Blocked("Cannot check scanner", e.message ?: "The system refused the request.")
            } catch (e: IOException) {
                offline = true
                s.lastState
                    ?: return Decision.Blocked("No connection", "Cannot reach the system.\nCheck Wi-Fi and scan again.")
            }
        }

        if (!state.open) {
            return Decision.Proceed(scanned, Action.CHECK_IN, offline, null)
        }

        val openDept = Department.fromName(state.department)
            ?: return Decision.Blocked(
                "Record problem",
                "This scanner has an open record with an unknown department (${state.department}).\nContact your supervisor."
            )
        val who = listOfNotNull(state.associate, state.since).joinToString(" – ")
        return when {
            openDept == scanned -> Decision.Proceed(
                scanned, Action.CHECK_OUT, offline,
                if (who.isNotBlank()) "Checked in: $who" else null
            )
            s.allowCrossDepartmentCheckout -> Decision.Proceed(
                openDept, Action.CHECK_OUT, offline,
                "Checked in to ${openDept.name}. This will check it OUT of ${openDept.name}."
            )
            else -> Decision.Blocked(
                "Wrong department",
                "This scanner is checked in to ${openDept.name}.\n\nScan the ${openDept.name} QR to check out first."
            )
        }
    }

    fun submit(
        ctx: Context,
        department: Department,
        action: Action,
        login: String,
        offline: Boolean,
        cb: (SubmitOutcome) -> Unit
    ) {
        val app = ctx.applicationContext
        Bg.io.execute {
            val out = try {
                submitBlocking(app, department, action, login, offline)
            } catch (e: Exception) {
                SubmitOutcome.Rejected("Not saved", "Unexpected error. Try again.\n(${e.javaClass.simpleName})")
            }
            main.post { cb(out) }
        }
    }

    private fun submitBlocking(
        ctx: Context, department: Department, action: Action, login: String, offline: Boolean
    ): SubmitOutcome {
        val s = AppSettings(ctx)
        val id = when (val r = Identity.check(ctx, s)) {
            is IdentityResult.Problem -> return SubmitOutcome.Rejected(r.title, r.message)
            is IdentityResult.Ok -> r
        }
        val now = WarehouseClock.now(s)
        val tx = Transaction(
            id = UUID.randomUUID().toString(),
            serial = id.serial,
            assetTag = id.assetTag,
            department = department,
            action = action,
            login = login,
            deviceTimestamp = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            date = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)),
            time = now.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US)),
            offline = offline,
            appVersion = BuildConfig.VERSION_NAME
        )

        // Keep order: if older items are still waiting, this one waits behind them.
        if (QueueStore.pendingCount(ctx) > 0) SyncManager.flushNow(ctx)
        if (QueueStore.pendingCount(ctx) > 0) {
            QueueStore.add(ctx, tx.copy(offline = true))
            applyLocalState(s, tx)
            return SubmitOutcome.Queued
        }

        return try {
            val r = BackendFactory.create(ctx).submit(tx)
            if (r.success) {
                applyLocalState(s, tx)
                SubmitOutcome.Saved(r.message)
            } else {
                // Central state disagrees (e.g. someone changed Excel). Refresh cache next scan.
                s.lastState = null
                SubmitOutcome.Rejected("Not saved", r.message ?: friendly(r.code))
            }
        } catch (e: IOException) {
            QueueStore.add(ctx, tx.copy(offline = true))
            applyLocalState(s, tx)
            SubmitOutcome.Queued
        }
    }

    private fun applyLocalState(s: AppSettings, tx: Transaction) {
        s.lastState = when (tx.action) {
            Action.CHECK_IN -> LocalState(true, tx.department.name, tx.login, "${tx.date} ${tx.time}", "LOCAL")
            Action.CHECK_OUT -> LocalState(false, null, null, null, "LOCAL")
        }
    }

    private fun friendly(code: String?): String = when (code) {
        "ALREADY_CHECKED_IN" -> "This scanner is already checked in."
        "NOT_CHECKED_IN" -> "This scanner is not checked in."
        "WRONG_DEPARTMENT" -> "This scanner is checked in to another department."
        "ROW_NOT_FOUND" -> "The check-in record was not found.\nContact your supervisor."
        "DUPLICATE" -> "This was already recorded."
        else -> "The system refused the request.\nContact your supervisor."
    }
}

object WarehouseClock {
    /** Time in the warehouse time zone (default America/Toronto) regardless of the device setting. */
    fun now(s: AppSettings): ZonedDateTime {
        val zone = try { ZoneId.of(s.timeZoneId) } catch (e: Exception) { ZoneId.of(AppSettings.DEF_TZ) }
        return ZonedDateTime.now(zone)
    }

    fun today(s: AppSettings): String =
        now(s).format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US))
}
