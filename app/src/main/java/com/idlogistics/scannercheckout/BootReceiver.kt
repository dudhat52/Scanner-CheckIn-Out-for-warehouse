package com.idlogistics.scannercheckout

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the scan listener after reboot / power loss and after the APK is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                ScanListenerService.start(context.applicationContext)
        }
    }
}
