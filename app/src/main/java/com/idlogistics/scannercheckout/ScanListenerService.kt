package com.idlogistics.scannercheckout

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * Always-on foreground service (permanent "Scanner Check-In ready" notification).
 * - Holds the dynamically registered ScanReceiver so scans are received with the app closed.
 * - Retries the offline queue every few minutes and whenever the network comes back.
 * Started at boot (BootReceiver), after app updates, and whenever MainActivity opens.
 */
class ScanListenerService : Service() {

    private val receiver = ScanReceiver()
    private var registered = false
    private val handler = Handler(Looper.getMainLooper())
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    private val syncTick = object : Runnable {
        override fun run() {
            SyncManager.flushAsync(applicationContext)
            handler.postDelayed(this, SYNC_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        startForeground(Notifications.ID_SERVICE, Notifications.serviceNotification(this))

        val filter = IntentFilter(ScannerContract.ACTION).apply {
            addCategory(ScannerContract.CATEGORY)
        }
        registerReceiver(receiver, filter)
        registered = true
        Log.i(TAG, "Listening for ${ScannerContract.ACTION}")

        handler.postDelayed(syncTick, 15_000L)

        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                SyncManager.flushAsync(applicationContext)
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "Network callback not registered", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (registered) {
            try { unregisterReceiver(receiver) } catch (e: Exception) { }
            registered = false
        }
        netCallback?.let {
            try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } catch (e: Exception) { }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ScanListenerService"
        private const val SYNC_INTERVAL_MS = 3 * 60_000L

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, ScanListenerService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "Could not start service", e)
            }
        }
    }
}
