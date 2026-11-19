package com.idlogistics.scannercheckout

import android.content.Context
import android.os.SystemClock
import android.util.Log

enum class Screen { CLOSED, READY, LOADING, LOGIN, SAVING, RESULT }

/** Process-wide UI state shared between the receiver and MainActivity. */
object AppState {
    @Volatile var screen: Screen = Screen.CLOSED
    @Volatile var lastSavedDept: Department? = null
    @Volatile var lastSavedAt: Long = 0L
    /** Admin screen is in front and handles scans itself (Asset Tag entry). */
    @Volatile var adminOpen: Boolean = false

    // One-time token issued after a correct PIN; AdminActivity refuses to open without it.
    @Volatile var adminToken: String? = null
    @Volatile var adminTokenExpiry: Long = 0L
}

/**
 * Decides what a scan means. Duplicate protection happens here:
 *  - The same value again within debounceMs is ignored, and the window keeps extending while the
 *    trigger is held (the PM86 repeats a held QR roughly every 0.7 s – seen in the device log).
 *  - While the app is loading/saving, scans are ignored.
 *  - After a successful save, the same department is ignored for cooldownMs, so a held trigger or
 *    double scan cannot turn CHECK IN straight into CHECK OUT.
 */
object ScanRouter {
    private const val TAG = "ScanRouter"
    private var lastValue: String? = null
    private var lastAt = 0L

    @Synchronized
    fun route(ctx: Context, value: String) {
        if (AppState.adminOpen) return
        val s = AppSettings(ctx)
        val now = SystemClock.elapsedRealtime()

        if (value == lastValue && now - lastAt < s.debounceMs) {
            lastAt = now
            Log.d(TAG, "Duplicate scan ignored")
            return
        }
        lastValue = value
        lastAt = now

        val dept = Department.fromScan(value, s)
        val isAdmin = value.equals(s.adminCode, ignoreCase = true)

        when (AppState.screen) {
            Screen.LOADING, Screen.SAVING -> {
                Log.d(TAG, "Busy – scan ignored")
                return
            }
            Screen.LOGIN -> {
                // A badge/ID barcode scanned on the login screen fills the Login ID field.
                if (dept == null && !isAdmin) Launcher.open(ctx, Launcher.ACTION_FILL_LOGIN, value)
                return
            }
            else -> Unit
        }

        if (isAdmin) {
            Launcher.open(ctx, Launcher.ACTION_ADMIN, null)
            return
        }
        if (dept == null) {
            Log.i(TAG, "Unknown QR rejected")
            Feedback.toast(ctx, "Not a department QR code")
            Feedback.error(ctx)
            return
        }
        if (dept == AppState.lastSavedDept && now - AppState.lastSavedAt < s.cooldownMs) {
            Feedback.toast(ctx, "${dept.name} was just recorded")
            return
        }
        Launcher.open(ctx, Launcher.ACTION_SCAN, dept.name)
    }
}
