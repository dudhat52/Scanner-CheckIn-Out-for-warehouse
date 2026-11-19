package com.idlogistics.scannercheckout

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import java.io.IOException

/**
 * IT/Admin screen. Not exported; opens only with a one-time token issued after a correct PIN.
 * Closes itself (re-locks) as soon as it leaves the screen.
 */
class AdminActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var s: AppSettings

    private lateinit var tvIdentity: TextView
    private lateinit var etAssetTag: EditText
    private lateinit var tvBackend: TextView
    private lateinit var etEndpoint: EditText
    private lateinit var etInbound: EditText
    private lateinit var etOutbound: EditText
    private lateinit var etShipdock: EditText
    private lateinit var etAdminCode: EditText
    private lateinit var etDebounce: EditText
    private lateinit var etCooldown: EditText
    private lateinit var etLoginTimeout: EditText
    private lateinit var cbCrossDept: CheckBox
    private lateinit var tvQueue: TextView
    private lateinit var tvPermissions: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = intent.getStringExtra(EXTRA_TOKEN)
        val valid = token != null && token == AppState.adminToken &&
            System.currentTimeMillis() < AppState.adminTokenExpiry
        AppState.adminToken = null
        if (!valid) {
            finish()
            return
        }
        s = AppSettings(this)
        setContentView(R.layout.activity_admin)

        tvIdentity = findViewById(R.id.tvIdentity)
        etAssetTag = findViewById(R.id.etAssetTag)
        tvBackend = findViewById(R.id.tvBackend)
        etEndpoint = findViewById(R.id.etEndpoint)
        etInbound = findViewById(R.id.etInbound)
        etOutbound = findViewById(R.id.etOutbound)
        etShipdock = findViewById(R.id.etShipdock)
        etAdminCode = findViewById(R.id.etAdminCode)
        etDebounce = findViewById(R.id.etDebounce)
        etCooldown = findViewById(R.id.etCooldown)
        etLoginTimeout = findViewById(R.id.etLoginTimeout)
        cbCrossDept = findViewById(R.id.cbCrossDept)
        tvQueue = findViewById(R.id.tvQueue)
        tvPermissions = findViewById(R.id.tvPermissions)

        findViewById<Button>(R.id.btnSaveAsset).setOnClickListener { saveAssetTag() }
        findViewById<Button>(R.id.btnSaveEndpoint).setOnClickListener { saveEndpoint() }
        findViewById<Button>(R.id.btnClearEndpoint).setOnClickListener { confirmTestMode() }
        findViewById<Button>(R.id.btnTestConnection).setOnClickListener { testConnection() }
        findViewById<Button>(R.id.btnSaveCodes).setOnClickListener { saveCodes() }
        findViewById<Button>(R.id.btnSaveBehaviour).setOnClickListener { saveBehaviour() }
        findViewById<Button>(R.id.btnSyncNow).setOnClickListener { syncNow() }
        findViewById<Button>(R.id.btnClearFailed).setOnClickListener { clearFailed() }
        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.btnChangePin).setOnClickListener { changePin() }
        findViewById<Button>(R.id.btnExit).setOnClickListener { finish() }

        etInbound.setText(s.inboundCode)
        etOutbound.setText(s.outboundCode)
        etShipdock.setText(s.shipdockCode)
        etAdminCode.setText(s.adminCode)
        etDebounce.setText((s.debounceMs / 1000).toString())
        etCooldown.setText((s.cooldownMs / 1000).toString())
        etLoginTimeout.setText(s.loginTimeoutSec.toString())
        cbCrossDept.isChecked = s.allowCrossDepartmentCheckout
    }

    /** While admin is open, a scanned 6-digit barcode fills the Asset Tag field. */
    private val adminScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val v = ScanParser.extract(intent) ?: return
            if (v.matches(Regex("^\\d{6}$"))) {
                etAssetTag.setText(v)
                Feedback.success(this@AdminActivity)
            } else {
                toast("Scanned value is not a 6-digit Asset Tag")
            }
        }
    }
    private var adminReceiverRegistered = false

    override fun onResume() {
        super.onResume()
        if (!::s.isInitialized) return
        AppState.adminOpen = true
        registerReceiver(adminScanReceiver, IntentFilter(ScannerContract.ACTION).apply { addCategory(ScannerContract.CATEGORY) })
        adminReceiverRegistered = true
        refresh()
    }

    override fun onPause() {
        AppState.adminOpen = false
        if (adminReceiverRegistered) {
            try { unregisterReceiver(adminScanReceiver) } catch (e: Exception) { }
            adminReceiverRegistered = false
        }
        super.onPause()
    }

    private var leavingForSettings = false

    override fun onStop() {
        super.onStop()
        // Re-lock when leaving admin (except while the overlay settings page is open).
        if (!isChangingConfigurations && !leavingForSettings) finish()
        leavingForSettings = false
    }

    override fun startActivity(intent: Intent?) {
        if (intent?.action == Settings.ACTION_MANAGE_OVERLAY_PERMISSION) leavingForSettings = true
        super.startActivity(intent)
    }

    private fun refresh() {
        val cands = DeviceIdentity.candidates(this)
        val id = Identity.check(this, s)
        val sb = StringBuilder()
        sb.append("Detected serial: ")
        sb.append(if (cands.isEmpty()) "NONE" else cands.joinToString { "${it.value} (${it.source})" })
        sb.append("\nADB-registered serial: ${s.provisionedSerial ?: "—"}")
        sb.append("\nRegistered serial: ${s.registeredSerial ?: "—"}")
        sb.append("\nAsset Tag: ${s.assetTag ?: "NOT REGISTERED"}")
        sb.append("\nRegistered: ${s.registeredAt ?: "—"}")
        sb.append(
            "\nStatus: " + when (id) {
                is IdentityResult.Ok -> "OK"
                is IdentityResult.Problem -> "${id.title} – ${id.message.replace('\n', ' ')}"
            }
        )
        val last = s.lastState
        sb.append("\nLast known: ${if (last == null) "—" else if (last.open) "CHECKED IN ${last.department} ${last.associate ?: ""} (${last.source})" else "NOT CHECKED IN (${last.source})"}")
        sb.append("\nApp version: ${BuildConfig.VERSION_NAME}")
        tvIdentity.text = sb.toString()

        tvBackend.text = if (s.isTestMode) {
            "TEST MODE – no URL set. Transactions stay on this device."
        } else {
            "Power Automate URL set: " + mask(s.endpointUrl)
        }

        val failed = QueueStore.failedSummary(this)
        tvQueue.text = buildString {
            append("Waiting to sync: ${QueueStore.pendingCount(this@AdminActivity)}")
            append("\nRejected by system: ${failed.size}")
            failed.take(5).forEach { append("\n • $it") }
        }

        val pm = getSystemService(PowerManager::class.java)
        val overlay = Settings.canDrawOverlays(this)
        val battery = pm?.isIgnoringBatteryOptimizations(packageName) == true
        tvPermissions.text = buildString {
            append("Display over other apps: ${if (overlay) "GRANTED" else "MISSING – app cannot pop up by itself"}")
            append("\nBattery optimisation exempt: ${if (battery) "YES" else "NO – listener may be stopped"}")
            append("\nTime zone used: ${s.timeZoneId}")
        }
    }

    private fun mask(url: String): String {
        val host = try { Uri.parse(url).host ?: "?" } catch (e: Exception) { "?" }
        return "$host/…(hidden)"
    }

    // ---------------------------------------------------------------------------- asset tag

    private fun saveAssetTag() {
        val tag = etAssetTag.text.toString().trim()
        if (!tag.matches(Regex("^\\d{6}$"))) {
            toast("Asset Tag must be exactly 6 digits")
            return
        }
        val serial = s.provisionedSerial ?: DeviceIdentity.detect(this)?.value
        if (serial == null) {
            toast("Serial number not detected – register with the PC tool")
            return
        }
        val existing = s.assetTag
        if (existing == tag) {
            toast("Already registered as $tag")
            return
        }
        if (existing != null) {
            AlertDialog.Builder(this)
                .setTitle("Scanner already registered")
                .setMessage("This scanner is registered as Asset Tag $existing.\n\nReplace with $tag?")
                .setPositiveButton("Replace") { _, _ -> registerAndSave(serial, tag, force = true) }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            registerAndSave(serial, tag, force = false)
        }
    }

    private fun registerAndSave(serial: String, tag: String, force: Boolean) {
        toast("Saving…")
        Bg.io.execute {
            val msg: String = try {
                val r = BackendFactory.create(this).register(serial, tag, force)
                if (r.success) {
                    s.saveRegistration(serial, tag, WarehouseClock.today(s), fromAdb = false)
                    s.lastState = null
                    "Saved: $serial → $tag"
                } else {
                    "Not saved: ${r.message ?: r.code ?: "rejected by master list"}"
                }
            } catch (e: IOException) {
                "Not saved: cannot reach the master list. Try again when online."
            }
            main.post {
                toast(msg)
                etAssetTag.setText("")
                refresh()
            }
        }
    }

    // ---------------------------------------------------------------------------- backend

    private fun saveEndpoint() {
        val url = etEndpoint.text.toString().trim()
        if (!url.startsWith("https://")) {
            toast("URL must start with https://")
            return
        }
        s.endpointUrl = url
        s.lastState = null
        etEndpoint.setText("")
        toast("URL saved")
        refresh()
    }

    private fun confirmTestMode() {
        AlertDialog.Builder(this)
            .setTitle("Switch to TEST MODE?")
            .setMessage("The Power Automate URL will be removed. Transactions will NOT reach Excel.")
            .setPositiveButton("Switch") { _, _ ->
                s.endpointUrl = ""
                s.lastState = null
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun testConnection() {
        val id = Identity.check(this, s)
        if (id !is IdentityResult.Ok) {
            toast("Register the scanner first")
            return
        }
        toast("Testing…")
        Bg.io.execute {
            val msg = try {
                val st = BackendFactory.create(this).getStatus(id.serial, id.assetTag)
                "OK – ${if (st.open) "CHECKED IN ${st.department} ${st.associate ?: ""}" else "not checked in"}" +
                    (st.centralAssetTag?.let { " (master Asset Tag $it)" } ?: "")
            } catch (e: BackendRejected) {
                "Flow answered with an error: ${e.message}"
            } catch (e: IOException) {
                "Cannot reach the flow: ${e.message}"
            } catch (e: Exception) {
                "Error: ${e.javaClass.simpleName}"
            }
            main.post {
                AlertDialog.Builder(this).setTitle("Connection test").setMessage(msg)
                    .setPositiveButton("OK", null).show()
            }
        }
    }

    // ---------------------------------------------------------------------------- settings

    private fun saveCodes() {
        val codes = listOf(etInbound, etOutbound, etShipdock, etAdminCode).map { it.text.toString().trim() }
        if (codes.any { it.length < 3 }) {
            toast("Each QR value needs at least 3 characters")
            return
        }
        if (codes.map { it.uppercase() }.toSet().size != codes.size) {
            toast("QR values must all be different")
            return
        }
        s.inboundCode = codes[0]
        s.outboundCode = codes[1]
        s.shipdockCode = codes[2]
        s.adminCode = codes[3]
        toast("QR values saved")
    }

    private fun saveBehaviour() {
        val deb = etDebounce.text.toString().toLongOrNull()
        val cool = etCooldown.text.toString().toLongOrNull()
        val tmo = etLoginTimeout.text.toString().toIntOrNull()
        if (deb == null || cool == null || tmo == null) {
            toast("Enter numbers only")
            return
        }
        s.debounceMs = deb * 1000
        s.cooldownMs = cool * 1000
        s.loginTimeoutSec = tmo
        s.allowCrossDepartmentCheckout = cbCrossDept.isChecked
        toast("Behaviour saved")
    }

    // ---------------------------------------------------------------------------- queue

    private fun syncNow() {
        toast("Syncing…")
        SyncManager.flushAsync(this) { remaining ->
            main.post {
                toast(if (remaining == 0) "All sent" else "$remaining still waiting (offline?)")
                refresh()
            }
        }
    }

    private fun clearFailed() {
        if (QueueStore.failedCount(this) == 0) return
        AlertDialog.Builder(this)
            .setTitle("Clear rejected items?")
            .setMessage("Write them down first – they were NOT recorded in Excel.")
            .setPositiveButton("Clear") { _, _ ->
                QueueStore.clearFailed(this)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------------------- PIN

    private fun changePin() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "New PIN (6+ digits)"
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("New admin PIN")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val pin = input.text.toString()
                if (PinManager.isValidNewPin(pin)) {
                    PinManager.set(s, pin)
                    toast("PIN changed")
                } else toast("PIN must be at least ${PinManager.MIN_LENGTH} digits")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(msg: String) = Feedback.toast(this, msg)

    companion object {
        const val EXTRA_TOKEN = "admin_token"
    }
}
