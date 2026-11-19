package com.idlogistics.scannercheckout

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import java.util.UUID

/**
 * Transaction screen. Opened by ScanRouter (scan with app closed or in background) or manually
 * from the launcher (shows READY – never closes on its own when opened manually).
 */
class MainActivity : Activity() {

    private enum class Kind { SUCCESS, WARNING, ERROR }

    private lateinit var tvBrand: TextView
    private lateinit var tvTestBanner: TextView
    private lateinit var panelReady: View
    private lateinit var panelLoading: View
    private lateinit var panelLogin: View
    private lateinit var panelResult: View
    private lateinit var tvReadyInfo: TextView
    private lateinit var tvLoading: TextView
    private lateinit var tvDept: TextView
    private lateinit var tvAction: TextView
    private lateinit var tvNote: TextView
    private lateinit var etLogin: EditText
    private lateinit var tvLoginError: TextView
    private lateinit var btnDone: Button
    private lateinit var btnCancel: Button
    private lateinit var tvResultTitle: TextView
    private lateinit var tvResultMsg: TextView
    private lateinit var btnResult: Button

    private val handler = Handler(Looper.getMainLooper())
    private var pending: Decision.Proceed? = null
    /** True when this screen was opened by a scan: after the transaction we close and return to the previous app. */
    private var openedByScan = false
    private var resumed = false

    private val loginTimeout = Runnable { cancelTransaction() }
    private val autoClose = Runnable { finishTransaction() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Show over the lock screen and wake the display when a scan arrives.
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        setContentView(R.layout.activity_main)
        bindViews()
        ScanListenerService.start(this)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        Notifications.cancelFallback(this)
        tvTestBanner.visibility = if (AppSettings(this).isTestMode) View.VISIBLE else View.GONE
        if (AppState.screen == Screen.CLOSED) showReady()
        if (AppState.screen == Screen.READY) refreshReadyInfo()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations) return
        when (AppState.screen) {
            // Associate left the login screen (Home, screen off): cancel, nothing is recorded.
            Screen.LOGIN, Screen.RESULT -> cancelTransaction()
            Screen.READY -> AppState.screen = Screen.CLOSED
            // LOADING/SAVING keep running on the background thread; result is applied on return.
            else -> Unit
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (AppState.screen != Screen.SAVING) AppState.screen = Screen.CLOSED
        super.onDestroy()
    }

    @Deprecated("Activity back handling")
    override fun onBackPressed() {
        when (AppState.screen) {
            Screen.LOGIN -> cancelTransaction()
            Screen.RESULT -> finishTransaction()
            Screen.LOADING, Screen.SAVING -> Unit   // wait
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    // ------------------------------------------------------------------------------------------

    private fun bindViews() {
        tvBrand = findViewById(R.id.tvBrand)
        tvTestBanner = findViewById(R.id.tvTestBanner)
        panelReady = findViewById(R.id.panelReady)
        panelLoading = findViewById(R.id.panelLoading)
        panelLogin = findViewById(R.id.panelLogin)
        panelResult = findViewById(R.id.panelResult)
        tvReadyInfo = findViewById(R.id.tvReadyInfo)
        tvLoading = findViewById(R.id.tvLoading)
        tvDept = findViewById(R.id.tvDept)
        tvAction = findViewById(R.id.tvAction)
        tvNote = findViewById(R.id.tvNote)
        etLogin = findViewById(R.id.etLogin)
        tvLoginError = findViewById(R.id.tvLoginError)
        btnDone = findViewById(R.id.btnDone)
        btnCancel = findViewById(R.id.btnCancel)
        tvResultTitle = findViewById(R.id.tvResultTitle)
        tvResultMsg = findViewById(R.id.tvResultMsg)
        btnResult = findViewById(R.id.btnResult)

        btnDone.setOnClickListener { onDone() }
        btnCancel.setOnClickListener { cancelTransaction() }
        btnResult.setOnClickListener { finishTransaction() }
        etLogin.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_DONE || enter) {
                onDone(); true
            } else false
        }
        // Hidden admin entry: long-press the header (PIN required).
        tvBrand.setOnLongClickListener { promptAdmin(); true }
    }

    private fun handleIntent(intent: Intent?) {
        Notifications.cancelFallback(this)
        val payload = intent?.getStringExtra(Launcher.EXTRA_PAYLOAD)
        when (intent?.action) {
            Launcher.ACTION_SCAN -> {
                val dept = Department.fromName(payload) ?: return
                openedByScan = true
                startTransaction(dept)
            }
            Launcher.ACTION_ADMIN -> {
                if (AppState.screen == Screen.CLOSED || AppState.screen == Screen.READY || AppState.screen == Screen.RESULT) {
                    showReady()
                    promptAdmin()
                }
            }
            Launcher.ACTION_FILL_LOGIN -> {
                if (AppState.screen == Screen.LOGIN && !payload.isNullOrBlank()) {
                    etLogin.setText(payload.trim().uppercase())
                    etLogin.setSelection(etLogin.text.length)
                    restartLoginTimer()
                }
            }
            else -> {
                // Manual launch from the home screen.
                if (AppState.screen == Screen.CLOSED || AppState.screen == Screen.RESULT) {
                    openedByScan = false
                    showReady()
                }
            }
        }
        // Consume the action so a configuration change does not replay it.
        intent?.action = Intent.ACTION_MAIN
    }

    // ---------------------------------------- flow ----------------------------------------------

    private fun startTransaction(dept: Department) {
        if (AppState.screen == Screen.LOADING || AppState.screen == Screen.SAVING) return
        handler.removeCallbacks(autoClose)
        showLoading("Checking scanner…", Screen.LOADING)
        TransactionProcessor.decide(this, dept) { d ->
            if (isFinishing || isDestroyed) return@decide
            when (d) {
                is Decision.Proceed -> showLogin(d)
                is Decision.Blocked -> showResult(Kind.ERROR, d.title, d.message, 0L)
            }
        }
    }

    private fun onDone() {
        val p = pending ?: return
        if (AppState.screen != Screen.LOGIN) return
        val login = etLogin.text.toString().trim().uppercase()
        val s = AppSettings(this)
        if (login.isEmpty()) {
            loginError("Enter your Login ID")
            return
        }
        if (!Regex(s.loginPattern).matches(login)) {
            loginError("Login ID is not valid")
            return
        }
        hideKeyboard()
        handler.removeCallbacks(loginTimeout)
        showLoading("Saving…", Screen.SAVING)
        TransactionProcessor.submit(this, p.department, p.action, login, p.offline) { out ->
            if (out !is SubmitOutcome.Rejected) {
                AppState.lastSavedDept = p.department
                AppState.lastSavedAt = android.os.SystemClock.elapsedRealtime()
            }
            if (isFinishing || isDestroyed) {
                // Screen already gone: the transaction itself is saved or queued; just reset state.
                AppState.screen = Screen.CLOSED
                return@submit
            }
            when (out) {
                is SubmitOutcome.Saved -> {
                    Feedback.success(this)
                    showResult(Kind.SUCCESS, "Saved", "${p.department.name}\n${p.action.label}\n$login", 2_000L)
                }
                SubmitOutcome.Queued -> {
                    Feedback.success(this)
                    showResult(
                        Kind.WARNING, "Saved – will sync",
                        "${p.department.name} ${p.action.label}\n\nNo connection right now.\nIt will be sent automatically.",
                        4_000L
                    )
                }
                is SubmitOutcome.Rejected -> showResult(Kind.ERROR, out.title, out.message, 0L)
            }
        }
    }

    private fun cancelTransaction() {
        handler.removeCallbacks(loginTimeout)
        pending = null
        hideKeyboard()
        finishTransaction()
    }

    /** Return to idle: back to the previous app if opened by a scan, otherwise the READY screen. */
    private fun finishTransaction() {
        handler.removeCallbacks(autoClose)
        handler.removeCallbacks(loginTimeout)
        pending = null
        if (openedByScan) {
            openedByScan = false
            AppState.screen = Screen.CLOSED
            finish()
        } else {
            showReady()
        }
    }

    // ---------------------------------------- views ---------------------------------------------

    private fun show(panel: View) {
        listOf(panelReady, panelLoading, panelLogin, panelResult).forEach {
            it.visibility = if (it === panel) View.VISIBLE else View.GONE
        }
    }

    private fun showReady() {
        AppState.screen = Screen.READY
        show(panelReady)
        refreshReadyInfo()
    }

    private fun refreshReadyInfo() {
        val s = AppSettings(this)
        val asset = s.assetTag ?: "NOT REGISTERED"
        val pendingCount = QueueStore.pendingCount(this)
        val sb = StringBuilder("Asset Tag: $asset")
        if (pendingCount > 0) sb.append("\nWaiting to sync: $pendingCount")
        tvReadyInfo.text = sb.toString()
    }

    private fun showLoading(text: String, state: Screen) {
        AppState.screen = state
        tvLoading.text = text
        show(panelLoading)
    }

    private fun showLogin(d: Decision.Proceed) {
        pending = d
        AppState.screen = Screen.LOGIN
        tvDept.text = d.department.name
        tvAction.text = d.action.label
        tvAction.setBackgroundColor(getColor(if (d.action == Action.CHECK_IN) R.color.checkin else R.color.checkout))
        val notes = listOfNotNull(d.note, if (d.offline) "Offline – will sync later" else null)
        tvNote.text = notes.joinToString("\n")
        tvNote.visibility = if (notes.isEmpty()) View.GONE else View.VISIBLE
        tvLoginError.visibility = View.GONE
        etLogin.setText("")
        show(panelLogin)
        etLogin.requestFocus()
        etLogin.postDelayed({
            getSystemService(InputMethodManager::class.java)?.showSoftInput(etLogin, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
        restartLoginTimer()
    }

    private fun restartLoginTimer() {
        handler.removeCallbacks(loginTimeout)
        handler.postDelayed(loginTimeout, AppSettings(this).loginTimeoutSec * 1000L)
    }

    private fun loginError(msg: String) {
        tvLoginError.text = msg
        tvLoginError.visibility = View.VISIBLE
        Feedback.error(this)
        restartLoginTimer()
    }

    private fun showResult(kind: Kind, title: String, msg: String, autoCloseMs: Long) {
        AppState.screen = Screen.RESULT
        tvResultTitle.text = title
        tvResultTitle.setTextColor(
            getColor(
                when (kind) {
                    Kind.SUCCESS -> R.color.ok
                    Kind.WARNING -> R.color.warn
                    Kind.ERROR -> R.color.err
                }
            )
        )
        tvResultMsg.text = msg
        btnResult.text = if (kind == Kind.ERROR) "CLOSE" else "OK"
        if (kind == Kind.ERROR) Feedback.error(this)
        show(panelResult)
        handler.removeCallbacks(autoClose)
        if (autoCloseMs > 0) handler.postDelayed(autoClose, autoCloseMs)
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(etLogin.windowToken, 0)
    }

    // ---------------------------------------- admin ---------------------------------------------

    private fun promptAdmin() {
        val s = AppSettings(this)
        val locked = PinManager.lockedRemainingMs(s)
        if (locked > 0) {
            showResult(Kind.ERROR, "Admin locked", "Too many wrong PINs.\nTry again in ${locked / 60_000 + 1} min.", 0L)
            return
        }
        val creating = !PinManager.isSet(s)
        val input = pinField(if (creating) "New PIN (6+ digits)" else "Admin PIN")
        AlertDialog.Builder(this)
            .setTitle(if (creating) "Create admin PIN" else "IT / Admin")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val pin = input.text.toString()
                if (creating) confirmNewPin(pin)
                else if (PinManager.verify(s, pin)) openAdmin()
                else Feedback.toast(this, "Wrong PIN")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmNewPin(first: String) {
        if (!PinManager.isValidNewPin(first)) {
            Feedback.toast(this, "PIN must be at least ${PinManager.MIN_LENGTH} digits")
            return
        }
        val input = pinField("Repeat PIN")
        AlertDialog.Builder(this)
            .setTitle("Confirm admin PIN")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                if (input.text.toString() == first) {
                    PinManager.set(AppSettings(this), first)
                    openAdmin()
                } else Feedback.toast(this, "PINs do not match")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pinField(hint: String) = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        this.hint = hint
        setPadding(48, 32, 48, 32)
    }

    private fun openAdmin() {
        val token = UUID.randomUUID().toString()
        AppState.adminToken = token
        AppState.adminTokenExpiry = System.currentTimeMillis() + 30_000L
        openedByScan = false
        startActivity(Intent(this, AdminActivity::class.java).putExtra(AdminActivity.EXTRA_TOKEN, token))
    }
}
