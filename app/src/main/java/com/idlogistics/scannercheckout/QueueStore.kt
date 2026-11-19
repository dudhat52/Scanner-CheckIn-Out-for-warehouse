package com.idlogistics.scannercheckout

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** One background thread for ALL backend work, so a sync and a new submit can never race. */
object Bg {
    val io: ExecutorService = Executors.newSingleThreadExecutor()
}

/**
 * Persistent FIFO of transactions that could not be sent. Survives reboot, power loss and app
 * restart. Each item keeps its TransactionId, so a retry after a lost reply cannot create a
 * duplicate row (the flow ignores a TransactionId it has already processed).
 */
object QueueStore {
    private const val PREFS = "scanner_checkout_queue"
    private const val K_PENDING = "pending"
    private const val K_FAILED = "failed"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun add(ctx: Context, tx: Transaction) {
        val arr = read(ctx, K_PENDING)
        for (i in 0 until arr.length()) {
            if (Transaction.fromJson(arr.getString(i))?.id == tx.id) return
        }
        arr.put(tx.toJson())
        write(ctx, K_PENDING, arr)
    }

    @Synchronized
    fun pending(ctx: Context): List<Transaction> {
        val arr = read(ctx, K_PENDING)
        return (0 until arr.length()).mapNotNull { Transaction.fromJson(arr.getString(it)) }
    }

    @Synchronized
    fun remove(ctx: Context, id: String) {
        val arr = read(ctx, K_PENDING)
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val s = arr.getString(i)
            if (Transaction.fromJson(s)?.id != id) out.put(s)
        }
        write(ctx, K_PENDING, out)
    }

    @Synchronized
    fun moveToFailed(ctx: Context, tx: Transaction, reason: String) {
        remove(ctx, tx.id)
        val failed = read(ctx, K_FAILED)
        failed.put(JSONObject().put("tx", tx.toJson()).put("reason", reason).toString())
        write(ctx, K_FAILED, failed)
    }

    @Synchronized fun pendingCount(ctx: Context) = read(ctx, K_PENDING).length()
    @Synchronized fun failedCount(ctx: Context) = read(ctx, K_FAILED).length()

    @Synchronized
    fun failedSummary(ctx: Context): List<String> {
        val arr = read(ctx, K_FAILED)
        return (0 until arr.length()).mapNotNull {
            try {
                val o = JSONObject(arr.getString(it))
                val tx = Transaction.fromJson(o.getString("tx"))
                "${tx?.date} ${tx?.time} ${tx?.department} ${tx?.action?.label} ${tx?.login}: ${o.optString("reason")}"
            } catch (e: Exception) {
                null
            }
        }
    }

    @Synchronized fun clearFailed(ctx: Context) = write(ctx, K_FAILED, JSONArray())

    private fun read(ctx: Context, key: String): JSONArray = try {
        JSONArray(prefs(ctx).getString(key, "[]"))
    } catch (e: Exception) {
        JSONArray()
    }

    private fun write(ctx: Context, key: String, arr: JSONArray) {
        prefs(ctx).edit().putString(key, arr.toString()).commit()
    }
}

object SyncManager {
    private const val TAG = "SyncManager"

    fun flushAsync(ctx: Context, done: ((Int) -> Unit)? = null) {
        val app = ctx.applicationContext
        Bg.io.execute {
            val remaining = flushNow(app)
            done?.invoke(remaining)
        }
    }

    /**
     * Sends queued transactions in order. Stops at the first "unavailable" error so ordering is
     * preserved. A business rejection moves the item to the failed list (shown in Admin).
     * MUST be called on [Bg.io]. Returns the number still pending.
     */
    fun flushNow(ctx: Context): Int {
        val items = QueueStore.pending(ctx)
        if (items.isEmpty()) return 0
        val backend = BackendFactory.create(ctx)
        for (tx in items) {
            try {
                val r = backend.submit(tx)
                if (r.success) {
                    QueueStore.remove(ctx, tx.id)
                    Log.i(TAG, "Synced ${tx.id}")
                } else {
                    QueueStore.moveToFailed(ctx, tx, r.message ?: r.code ?: "Rejected")
                    Log.w(TAG, "Rejected ${tx.id}: ${r.code} ${r.message}")
                }
            } catch (e: IOException) {
                Log.i(TAG, "Still offline: ${e.message}")
                break
            } catch (e: Exception) {
                Log.e(TAG, "Sync error", e)
                break
            }
        }
        return QueueStore.pendingCount(ctx)
    }
}
