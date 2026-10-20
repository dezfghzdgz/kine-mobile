package com.kine.clipper

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings as SysSettings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.security.SecureRandom
import java.text.DateFormat
import java.util.Date

/**
 * Appka: tři záložky dole - Nahrávání (stav, Start / Klip / Stop, rychlé přepínače,
 * co je potřeba povolit), Klipy (seznam s náhledy, přehrát / nahrát na Kine / sdílet /
 * smazat) a Nastavení (skupiny v kartách, vysvětlivky pod „?“). Tmavé jako Kine, všechny
 * barvy vlastní - čitelné na každém telefonu. Rozhraní je v kódu (bez knihoven).
 */
class MainActivity : Activity() {
    private lateinit var settings: Settings
    private lateinit var api: KineApi
    private lateinit var library: Library
    private lateinit var ui: Ui
    private lateinit var thumbs: Thumbs
    private val main = Handler(Looper.getMainLooper())

    private var tab = 0
    private lateinit var pages: Array<View>
    private val navIcons = ArrayList<ImageView>()
    private val navLabels = ArrayList<TextView>()

    // Nahrávání
    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusTimer: TextView
    private lateinit var statusSub: TextView
    private lateinit var chipsRow: LinearLayout
    private lateinit var mainBtn: Btn
    private lateinit var runRow: LinearLayout
    private lateinit var infoLine: TextView
    private lateinit var alertsBox: LinearLayout
    private lateinit var howCard: View
    private lateinit var howStep3: TextView
    private lateinit var latestCard: LinearLayout

    // Klipy
    private lateinit var clipsHeader: TextView
    private lateinit var clipsList: ListView
    private lateinit var clipsEmpty: View
    private val adapter = ClipsAdapter()

    // Účet
    private lateinit var accountChip: TextView
    private lateinit var accountName: TextView
    private lateinit var accountBtn: Btn
    private lateinit var settingsNote: TextView

    // Start: oprávnění -> bublina -> povolení nahrávání obrazovky
    private var askedPermissions = false
    private var overlayAsked = false
    private var renderPosted = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = scheduleRender()
    }
    private val libraryListener: () -> Unit = { main.post { scheduleRender() } }
    private val ticker = object : Runnable {
        override fun run() {
            updateTimer()
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        api = KineApi(this, settings)
        library = Library.get(this)
        ui = Ui(this)
        thumbs = Thumbs(this)
        setContentView(buildUi())
        selectTab(savedInstanceState?.getInt("tab") ?: 0)
        handleLink(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(CaptureService.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED) else registerReceiver(receiver, filter)
        library.addListener(libraryListener)
        renderAll()
        main.removeCallbacks(ticker)
        main.post(ticker)
        // Klipy, které zůstaly ve frontě (nahrávání z pozadí Android nedovolil), poslat teď.
        if (settings.loggedIn && !UploadService.active && library.queued().isNotEmpty()) UploadService.kick(this)
    }

    override fun onResume() {
        super.onResume()
        // Oprávnění pro bublinu mohlo přibýt (Nastavení systému) - během nahrávání ji hned ukázat.
        if (CaptureService.active && settings.bubble && !CaptureService.bubbleShown && SysSettings.canDrawOverlays(this)) {
            sendToService(CaptureService.ACTION_BUBBLE)
        }
        renderStatus()
    }

    override fun onStop() {
        main.removeCallbacks(ticker)
        try { unregisterReceiver(receiver) } catch (_: Exception) { }
        library.removeListener(libraryListener)
        super.onStop()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        thumbs.close()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (tab != 0) {
            selectTab(0)
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun sendToService(action: String) {
        try {
            startService(Intent(this, CaptureService::class.java).setAction(action))
        } catch (e: Exception) {
            Log.w("služba: $action nejde (${e.message})")
        }
    }

    // ---- přihlášení přes Kine (kine://link?th=…&state=…) ----

    private fun handleLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "kine" || data.host != "link") return
        val th = data.getQueryParameter("th") ?: return
        val state = data.getQueryParameter("state")
        if (state == null || state != settings.linkState) {
            Toast.makeText(this, getString(R.string.login_failed, "state"), Toast.LENGTH_LONG).show()
            return
        }
        settings.linkState = null
        Thread {
            try {
                api.finishLogin(th)
                main.post {
                    Toast.makeText(this, R.string.login_ok, Toast.LENGTH_SHORT).show()
                    renderAccount()
                }
            } catch (e: Exception) {
                Log.e("přihlášení", e)
                main.post { Toast.makeText(this, getString(R.string.login_failed, e.message ?: ""), Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun startLogin() {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        val state = bytes.joinToString("") { "%02x".format(it) }
        settings.linkState = state
        val url = "${settings.siteUrl}/connect?app=mobile&state=$state"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.login_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    // ---- start / stop ----

    private fun onMainButton() {
        when (CaptureService.state) {
            CaptureService.PREPARING, CaptureService.STARTING, CaptureService.RUNNING -> sendToService(CaptureService.ACTION_STOP)
            CaptureService.STOPPING -> { }
            else -> {
                askedPermissions = false
                overlayAsked = false
                continueStart()
            }
        }
    }

    /** Postupně: oprávnění (mikrofon, kamera, oznámení) -> bublina -> okno systému „nahrávat obrazovku“. */
    private fun continueStart() {
        val needed = ArrayList<String>()
        if (settings.microphone || settings.gameAudio) needed.add(Manifest.permission.RECORD_AUDIO)
        if (settings.camera != "off") needed.add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty() && !askedPermissions) {
            askedPermissions = true
            requestPermissions(missing.toTypedArray(), REQ_PERMISSIONS)
            return
        }
        if (settings.bubble && !overlayAsked && !SysSettings.canDrawOverlays(this) && !Device.isLowRam(this)) {
            overlayAsked = true
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(R.string.overlay_title)
                .setMessage(R.string.overlay_text)
                .setPositiveButton(R.string.allow) { _, _ -> openOverlaySettings(true) }
                .setNegativeButton(R.string.overlay_skip) { _, _ -> askProjection() }
                .setOnCancelListener { askProjection() }
                .show()
            return
        }
        askProjection()
    }

    private fun openOverlaySettings(forStart: Boolean) {
        try {
            val i = Intent(SysSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            @Suppress("DEPRECATION")
            if (forStart) startActivityForResult(i, REQ_OVERLAY) else startActivity(i)
        } catch (e: Exception) {
            Log.w("nastavení překrytí nejde otevřít: ${e.message}")
            if (forStart) askProjection()
        }
    }

    private fun askProjection() {
        // Služba s mikrofonem / kamerou hned teď, dokud je appka vepředu (Android 14+ vrací povolení,
        // když je appka už v pozadí - hlavně při „Jedna aplikace“ - a z pozadí by mikrofon nešel).
        // Obyčejný start (appka je vepředu, služba si sama hned zavolá startForeground).
        if (CaptureService.state == CaptureService.IDLE && CaptureService.sensorTypes(this, settings) != 0) {
            try {
                startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_PREPARE))
            } catch (e: Exception) {
                Log.w("příprava služby nejde: ${e.message}")
            }
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        // Android 14+: v okně systému jde vybrat „jedna aplikace“ - nahrává se jen hra, bublina ani
        // oznámení v klipu nejsou (kameru skládá appka sama, ta zůstane). Starší Android: celá obrazovka.
        val intent = if (Build.VERSION.SDK_INT >= 34) mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice()) else mpm.createScreenCaptureIntent()
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_PROJECTION)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.err_generic, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATIONS) {
            renderStatus()
            return
        }
        if (requestCode != REQ_PERMISSIONS) return
        // Odepřené se nevyžaduje - nahraje se bez toho (appka ukáže, co chybí).
        continueStart()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_PROJECTION -> {
                if (resultCode == RESULT_OK && data != null) {
                    val intent = Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_START)
                        .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                        .putExtra(CaptureService.EXTRA_RESULT_DATA, data)
                    launchStart(intent)
                    main.postDelayed({ renderStatus() }, 400)
                } else {
                    // Zrušeno - připravená služba (mikrofon / kamera) zase pryč.
                    if (CaptureService.state == CaptureService.PREPARING) sendToService(CaptureService.ACTION_STOP)
                    Toast.makeText(this, R.string.status_no_projection, Toast.LENGTH_LONG).show()
                }
            }
            REQ_OVERLAY -> continueStart()
        }
    }

    /** Připravená služba už běží na popředí - stačí obyčejný start (jde i z pozadí); jinak na popředí. */
    private fun launchStart(intent: Intent) {
        try {
            if (CaptureService.state == CaptureService.PREPARING) startService(intent) else startForegroundService(intent)
        } catch (e: Exception) {
            try {
                startForegroundService(intent)
            } catch (e2: Exception) {
                Log.e("start služby", e2)
                Toast.makeText(this, getString(R.string.err_generic, e2.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---- vykreslení ----

    private fun scheduleRender() {
        if (renderPosted) return
        renderPosted = true
        // Víc změn najednou (průběh nahrávání) = jedno překreslení za ~¼ s.
        main.postDelayed({
            renderPosted = false
            renderAll()
        }, 250)
    }

    private fun renderAll() {
        renderStatus()
        renderClips()
        renderAccount()
    }

    private fun renderAccount() {
        if (!::accountChip.isInitialized) return
        if (settings.loggedIn) {
            val name = getString(R.string.signed_in_as, settings.username ?: "kine")
            accountChip.text = name
            accountChip.setTextColor(Palette.TEXT)
            accountChip.background = ui.round(Palette.CARD_HI, 16)
            accountName.text = name
            ui.style(accountBtn, getString(R.string.sign_out), null, Ui.Kind.GHOST)
        } else {
            accountChip.text = getString(R.string.sign_in)
            accountChip.setTextColor(Palette.BRAND)
            accountChip.background = ui.round(0, 16, Palette.LINE, 1.5f)
            accountName.text = getString(R.string.not_signed_in)
            ui.style(accountBtn, getString(R.string.sign_in), R.drawable.ic_person, Ui.Kind.PRIMARY)
        }
    }

    private fun renderStatus() {
        if (!::statusTitle.isInitialized) return
        val st = CaptureService.state
        val err = CaptureService.error
        val idle = st == CaptureService.IDLE
        val (dot, title) = when (st) {
            CaptureService.RUNNING -> Palette.RED to getString(R.string.state_recording)
            CaptureService.PREPARING, CaptureService.STARTING -> Palette.AMBER to getString(R.string.state_starting)
            CaptureService.STOPPING -> Palette.AMBER to getString(R.string.state_stopping)
            else -> if (err != null) Palette.RED to getString(R.string.state_error) else Palette.GREEN to getString(R.string.state_ready)
        }
        statusDot.background = ui.oval(dot)
        statusTitle.text = title
        statusSub.text = when (st) {
            CaptureService.RUNNING -> getString(R.string.sub_recording, settings.clipSeconds)
            CaptureService.PREPARING, CaptureService.STARTING -> getString(R.string.sub_starting)
            CaptureService.STOPPING -> ""
            else -> if (err != null) "" else getString(R.string.sub_ready, settings.clipSeconds)
        }
        statusSub.visibility = if (statusSub.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        when (st) {
            CaptureService.RUNNING -> {
                mainBtn.root.visibility = View.GONE
                runRow.visibility = View.VISIBLE
            }
            CaptureService.PREPARING, CaptureService.STARTING -> {
                runRow.visibility = View.GONE
                mainBtn.root.visibility = View.VISIBLE
                ui.style(mainBtn, getString(R.string.stop), R.drawable.ic_stop, Ui.Kind.GHOST)
            }
            CaptureService.STOPPING -> {
                runRow.visibility = View.GONE
                mainBtn.root.visibility = View.VISIBLE
                ui.style(mainBtn, getString(R.string.state_stopping), null, Ui.Kind.GHOST)
            }
            else -> {
                runRow.visibility = View.GONE
                mainBtn.root.visibility = View.VISIBLE
                ui.style(mainBtn, getString(R.string.start), R.drawable.ic_play, Ui.Kind.PRIMARY)
            }
        }
        val running = st == CaptureService.RUNNING
        infoLine.visibility = if (running && CaptureService.info.isNotEmpty()) View.VISIBLE else View.GONE
        infoLine.text = CaptureService.info + cameraText()
        updateTimer()
        renderChips(idle)
        renderAlerts(idle, err)
        howCard.visibility = if (idle) View.VISIBLE else View.GONE
        howStep3.text = getString(R.string.how_3, settings.clipSeconds)
        if (::settingsNote.isInitialized) settingsNote.visibility = if (CaptureService.active) View.VISIBLE else View.GONE
        renderLatest()
    }

    private fun cameraText(): String = when (val c = CaptureService.cameraState) {
        "" -> ""
        "on" -> " · " + getString(R.string.camera_on)
        "starting" -> " · " + getString(R.string.camera_starting)
        "missing" -> " · " + getString(R.string.camera_missing)
        "paused" -> " · " + getString(R.string.camera_paused)
        else -> " · " + getString(R.string.camera_error) + " (" + c.removePrefix("error:") + ")"
    }

    private fun updateTimer() {
        if (!::statusTimer.isInitialized) return
        val since = CaptureService.startedAt
        if (CaptureService.state != CaptureService.RUNNING || since <= 0L) {
            statusTimer.visibility = View.GONE
            return
        }
        val s = (SystemClock.elapsedRealtime() - since) / 1000
        statusTimer.text = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
        statusTimer.visibility = View.VISIBLE
    }

    /** Rychlé přepínače: mikrofon, zvuk hry, kamera, bublina (během nahrávání jen ukazují stav). */
    private fun renderChips(idle: Boolean) {
        chipsRow.removeAllViews()
        fun add(icon: Int, label: Int, on: Boolean, toggle: () -> Unit) {
            val chip = ui.chip(icon, getString(label), on, idle) {
                if (!idle) {
                    Toast.makeText(this, R.string.applies_after_restart, Toast.LENGTH_SHORT).show()
                } else {
                    toggle()
                    renderStatus()
                }
            }
            chipsRow.addView(chip, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = ui.dp(8) })
        }
        add(R.drawable.ic_mic, R.string.chip_mic, settings.microphone) { settings.microphone = !settings.microphone }
        add(R.drawable.ic_volume, R.string.chip_game, settings.gameAudio) { settings.gameAudio = !settings.gameAudio }
        add(R.drawable.ic_camera, R.string.chip_camera, settings.camera != "off") { settings.camera = if (settings.camera == "off") "front" else "off" }
        add(R.drawable.ic_bubble, R.string.chip_bubble, settings.bubble) { settings.bubble = !settings.bubble }
    }

    /** Co je potřeba povolit / co nejde - každé s tlačítkem, které to spraví. */
    private fun renderAlerts(idle: Boolean, err: String?) {
        alertsBox.removeAllViews()
        fun alert(text: String, color: Int, soft: Int, action: String?, onAction: (() -> Unit)?) {
            val row = ui.hbox().apply {
                background = ui.round(soft, 14)
                setPadding(ui.dp(12), ui.dp(10), ui.dp(10), ui.dp(10))
            }
            row.addView(ui.icon(R.drawable.ic_warning, color, 20), ui.lp(ui.dp(20), ui.dp(20)).apply { marginEnd = ui.dp(10) })
            row.addView(ui.text(text, 14f, Palette.TEXT), ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (action != null && onAction != null) {
                val b = ui.text(action, 14f, color, true).apply {
                    setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
                    background = ui.pressable(ui.round(0, 10, color, 1.2f))
                    setOnClickListener { onAction() }
                }
                row.addView(b, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ui.dp(8) })
            }
            alertsBox.addView(row, ui.matchWrap().apply { topMargin = ui.dp(10) })
        }
        if (settings.bubble && !SysSettings.canDrawOverlays(this)) {
            if (Device.isLowRam(this)) alert(getString(R.string.warn_overlay_lowram), Palette.AMBER, Palette.AMBER_SOFT, null, null)
            else alert(getString(R.string.warn_overlay), Palette.AMBER, Palette.AMBER_SOFT, getString(R.string.allow)) { openOverlaySettings(false) }
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            alert(getString(R.string.warn_notifications), Palette.AMBER, Palette.AMBER_SOFT, getString(R.string.allow)) { askNotifications() }
        }
        val w = CaptureService.warning
        if (!idle && w != null) alert(w, Palette.AMBER, Palette.AMBER_SOFT, null, null)
        if (idle && err != null) alert(err, Palette.RED, Palette.RED_SOFT, getString(R.string.send_log)) { sendLog() }
        alertsBox.visibility = if (alertsBox.childCount > 0) View.VISIBLE else View.GONE
    }

    /** Oznámení: poprvé okno Androidu, po odmítnutí (Android už okno neukáže) nastavení appky. */
    private fun askNotifications() {
        if (Build.VERSION.SDK_INT < 33) return
        val p = getSharedPreferences("kine", MODE_PRIVATE)
        val askedBefore = p.getBoolean("askedNotifications", false)
        if (!askedBefore || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            p.edit().putBoolean("askedNotifications", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        } else {
            try {
                startActivity(Intent(SysSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(SysSettings.EXTRA_APP_PACKAGE, packageName))
            } catch (_: Exception) { }
        }
    }

    private fun renderLatest() {
        latestCard.removeAllViews()
        val c = library.list().firstOrNull()
        if (c == null) {
            latestCard.visibility = View.GONE
            return
        }
        latestCard.visibility = View.VISIBLE
        val head = ui.hbox()
        head.addView(ui.text(getString(R.string.latest_clip), 13f, Palette.DIM, true), ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(ui.text(getString(R.string.all_clips), 13f, Palette.BRAND, true).apply {
            setPadding(ui.dp(8), ui.dp(4), 0, ui.dp(4))
            setOnClickListener { selectTab(1) }
        })
        latestCard.addView(head, ui.matchWrap().apply { bottomMargin = ui.dp(8) })
        val row = ui.hbox().apply {
            isClickable = true
            setOnClickListener { play(c) }
        }
        val thumb = thumbView(96, 54)
        thumbs.load(c.uri, thumb)
        row.addView(thumb)
        val texts = ui.vbox()
        texts.addView(ui.text(c.title, 14f, Palette.TEXT, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        texts.addView(ui.text(duration(c.durationMs) + " · " + meta(c), 12f, Palette.DIM).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        row.addView(texts, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ui.dp(12) })
        row.addView(ui.icon(R.drawable.ic_play, Palette.BRAND, 26), ui.lp(ui.dp(26), ui.dp(26)))
        latestCard.addView(row, ui.matchWrap())
    }

    private fun renderClips() {
        if (!::clipsList.isInitialized) return
        val clips = library.list()
        adapter.items = clips
        adapter.notifyDataSetChanged()
        clipsEmpty.visibility = if (clips.isEmpty()) View.VISIBLE else View.GONE
        clipsList.visibility = if (clips.isEmpty()) View.GONE else View.VISIBLE
        clipsHeader.visibility = clipsList.visibility
        val total = clips.sumOf { it.sizeBytes }
        clipsHeader.text = getString(R.string.clips_header, clips.size, size(total))
        navLabels.getOrNull(1)?.text = if (clips.isEmpty()) getString(R.string.tab_clips) else getString(R.string.tab_clips) + " · " + clips.size
    }

    // ---- stavba rozhraní ----

    private fun buildUi(): View {
        val root = ui.vbox().apply { setBackgroundColor(Palette.BG) }
        root.addView(topBar(), ui.matchWrap())
        val content = FrameLayout(this)
        val record = recordPage()
        val clips = clipsPage()
        val settingsPage = settingsPage()
        pages = arrayOf(record, clips, settingsPage)
        for (p in pages) content.addView(p, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(content, ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(bottomNav(), ui.matchWrap())
        return root
    }

    private fun topBar(): View {
        val bar = ui.hbox().apply { setPadding(ui.dp(10), ui.dp(10), ui.dp(12), ui.dp(6)) }
        bar.addView(ImageView(this).apply { setImageResource(R.drawable.ic_launcher_foreground) }, ui.lp(ui.dp(40), ui.dp(40)))
        bar.addView(ui.text(getString(R.string.app_name), 20f, Palette.TEXT, true), ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ui.dp(2) })
        accountChip = ui.text("", 13f, Palette.BRAND, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = ui.dp(170)
            setPadding(ui.dp(12), ui.dp(7), ui.dp(12), ui.dp(7))
            setOnClickListener { if (settings.loggedIn) selectTab(2) else startLogin() }
        }
        bar.addView(accountChip)
        return bar
    }

    private fun bottomNav(): View {
        val nav = ui.hbox().apply {
            setBackgroundColor(Palette.CARD)
            setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6))
        }
        val items = listOf(R.drawable.ic_record to R.string.tab_record, R.drawable.ic_clips to R.string.tab_clips, R.drawable.ic_settings to R.string.tab_settings)
        items.forEachIndexed { i, (icon, label) ->
            val item = ui.vbox().apply {
                gravity = Gravity.CENTER
                setPadding(0, ui.dp(4), 0, ui.dp(4))
                background = ui.pressable(ui.round(0, 12))
                isClickable = true
                setOnClickListener { selectTab(i) }
            }
            val iv = ui.icon(icon, Palette.FAINT, 24)
            val tv = ui.text(getString(label), 12f, Palette.FAINT, true).apply { gravity = Gravity.CENTER; maxLines = 1 }
            item.addView(iv, ui.lp(ui.dp(24), ui.dp(24)))
            item.addView(tv, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(2) })
            navIcons.add(iv)
            navLabels.add(tv)
            nav.addView(item, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return nav
    }

    private fun selectTab(i: Int) {
        tab = i.coerceIn(0, 2)
        pages.forEachIndexed { index, v -> v.visibility = if (index == tab) View.VISIBLE else View.GONE }
        navIcons.forEachIndexed { index, v -> v.imageTintList = ColorStateList.valueOf(if (index == tab) Palette.BRAND else Palette.FAINT) }
        navLabels.forEachIndexed { index, v -> v.setTextColor(if (index == tab) Palette.BRAND else Palette.FAINT) }
    }

    private fun page(): Pair<ScrollView, LinearLayout> {
        val box = ui.vbox().apply { setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(24)) }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(box, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return scroll to box
    }

    private fun recordPage(): View {
        val (scroll, box) = page()

        // Stav + hlavní tlačítka
        val status = ui.card()
        val head = ui.hbox()
        statusDot = View(this)
        head.addView(statusDot, ui.lp(ui.dp(12), ui.dp(12)).apply { marginEnd = ui.dp(10) })
        statusTitle = ui.text("", 20f, Palette.TEXT, true)
        head.addView(statusTitle, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        statusTimer = ui.text("", 15f, Palette.RED, true).apply { visibility = View.GONE }
        head.addView(statusTimer)
        status.addView(head, ui.matchWrap())
        statusSub = ui.text("", 14f, Palette.DIM)
        status.addView(statusSub, ui.matchWrap().apply { topMargin = ui.dp(4) })
        chipsRow = ui.hbox()
        status.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipsRow)
        }, ui.matchWrap().apply { topMargin = ui.dp(12) })
        mainBtn = ui.button(getString(R.string.start), R.drawable.ic_play, Ui.Kind.PRIMARY) { onMainButton() }
        status.addView(mainBtn.root, ui.matchWrap().apply { topMargin = ui.dp(14) })
        runRow = ui.hbox().apply { visibility = View.GONE }
        val clipBtn = ui.button(getString(R.string.clip_now), R.drawable.ic_clip, Ui.Kind.PRIMARY) { sendToService(CaptureService.ACTION_CLIP) }
        val stopBtn = ui.button(getString(R.string.stop), R.drawable.ic_stop, Ui.Kind.DANGER) { onMainButton() }
        runRow.addView(clipBtn.root, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = ui.dp(10) })
        runRow.addView(stopBtn.root, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        status.addView(runRow, ui.matchWrap().apply { topMargin = ui.dp(14) })
        infoLine = ui.text("", 12f, Palette.FAINT).apply { visibility = View.GONE }
        status.addView(infoLine, ui.matchWrap().apply { topMargin = ui.dp(10) })
        alertsBox = ui.vbox()
        status.addView(alertsBox, ui.matchWrap())
        box.addView(status)

        // Jak na to (jen když se nenahrává)
        val how = ui.card(getString(R.string.how_title), R.drawable.ic_info)
        fun step(n: Int, icon: Int, text: String): TextView {
            val r = ui.hbox().apply { setPadding(0, ui.dp(5), 0, ui.dp(5)) }
            r.addView(ui.text(n.toString(), 12f, Palette.ON_BRAND, true).apply {
                gravity = Gravity.CENTER
                background = ui.oval(Palette.BRAND)
            }, ui.lp(ui.dp(22), ui.dp(22)).apply { marginEnd = ui.dp(12) })
            r.addView(ui.icon(icon, Palette.DIM, 20), ui.lp(ui.dp(20), ui.dp(20)).apply { marginEnd = ui.dp(10) })
            val tv = ui.text(text, 14f, Palette.TEXT)
            r.addView(tv, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            how.addView(r, ui.matchWrap())
            return tv
        }
        step(1, R.drawable.ic_play, getString(if (Build.VERSION.SDK_INT >= 34) R.string.how_1_14 else R.string.how_1))
        step(2, R.drawable.ic_game, getString(R.string.how_2))
        howStep3 = step(3, R.drawable.ic_clip, getString(R.string.how_3, settings.clipSeconds))
        howCard = how
        box.addView(how)

        // Poslední klip
        latestCard = ui.card()
        box.addView(latestCard)
        return scroll
    }

    private fun clipsPage(): View {
        val frame = FrameLayout(this)
        val col = ui.vbox()
        clipsHeader = ui.text("", 13f, Palette.DIM, true).apply { setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(8)) }
        col.addView(clipsHeader, ui.matchWrap())
        clipsList = ListView(this).apply {
            divider = null
            dividerHeight = 0
            selector = android.graphics.drawable.ColorDrawable(0)
            clipToPadding = false
            setPadding(ui.dp(12), 0, ui.dp(12), ui.dp(16))
            adapter = this@MainActivity.adapter
            cacheColorHint = 0
            isVerticalScrollBarEnabled = false
        }
        col.addView(clipsList, ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        frame.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val empty = ui.vbox().apply {
            gravity = Gravity.CENTER
            setPadding(ui.dp(32), ui.dp(32), ui.dp(32), ui.dp(32))
        }
        empty.addView(ui.icon(R.drawable.ic_clips, Palette.FAINT, 56), ui.lp(ui.dp(56), ui.dp(56)))
        empty.addView(ui.text(getString(R.string.no_clips), 17f, Palette.TEXT, true).apply { gravity = Gravity.CENTER }, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(12) })
        empty.addView(ui.text(getString(R.string.no_clips_hint), 14f, Palette.DIM).apply { gravity = Gravity.CENTER }, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(4) })
        clipsEmpty = empty
        frame.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return frame
    }

    private fun settingsPage(): View {
        val (scroll, box) = page()
        settingsNote = ui.text(getString(R.string.applies_after_restart), 13f, Palette.AMBER).apply {
            visibility = View.GONE
            background = ui.round(Palette.AMBER_SOFT, 12)
            setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
        }
        box.addView(settingsNote, ui.matchWrap().apply { bottomMargin = ui.dp(12) })
        val tier = Device.tier(this)

        // Klip
        val clip = ui.card(getString(R.string.section_clip), R.drawable.ic_clip)
        val lengths = listOf(15, 30, 60, 90, 120)
        val curLen = lengths.minByOrNull { kotlin.math.abs(it - settings.clipSeconds) } ?: 30
        clip.addView(ui.field(getString(R.string.clip_length), ui.segmented(lengths.map { getString(R.string.clip_seconds_value, it) }, lengths.indexOf(curLen)) { i ->
            settings.clipSeconds = lengths[i]
            renderStatus()
        }))
        val qualities = listOf("auto", "480", "720", "1080")
        val autoP = if (tier == Device.Tier.LOW) "480p" else "720p"
        clip.addView(ui.field(getString(R.string.quality), ui.segmented(listOf(getString(R.string.quality_auto), "480p", "720p", "1080p"), qualities.indexOf(settings.quality).coerceAtLeast(0)) { i ->
            settings.quality = qualities[i]
        }, getString(R.string.quality_auto_hint, autoP)))
        clip.addView(ui.field(getString(R.string.frame_rate), ui.segmented(listOf("30", "60"), if (settings.fps >= 60) 1 else 0) { i -> settings.fps = if (i == 1) 60 else 30 }))
        box.addView(clip)

        // Bublina
        val bubble = ui.card(getString(R.string.section_bubble), R.drawable.ic_bubble)
        bubble.addView(ui.switchRow(getString(R.string.bubble_show), null, settings.bubble, getString(R.string.bubble_hint)) { on ->
            settings.bubble = on
            if (on && !SysSettings.canDrawOverlays(this) && !Device.isLowRam(this)) openOverlaySettings(false)
            if (CaptureService.active) sendToService(CaptureService.ACTION_BUBBLE)
            renderStatus()
        })
        val looks = listOf("button", "edge")
        bubble.addView(ui.field(getString(R.string.bubble_look), ui.segmented(listOf(getString(R.string.bubble_button), getString(R.string.bubble_edge)), looks.indexOf(settings.bubbleLook).coerceAtLeast(0)) { i ->
            settings.bubbleLook = looks[i]
            if (CaptureService.active) sendToService(CaptureService.ACTION_BUBBLE)
        }, getString(R.string.bubble_look_hint)))
        bubble.addView(ui.switchRow(getString(R.string.shake_to_clip), null, settings.shakeToClip, getString(R.string.shake_hint)) { settings.shakeToClip = it })
        if (Build.VERSION.SDK_INT >= 34) bubble.addView(ui.text(getString(R.string.single_app_tip), 13f, Palette.FAINT), ui.matchWrap().apply { topMargin = ui.dp(4) })
        box.addView(bubble)

        // Kamera
        val camera = ui.card(getString(R.string.section_camera), R.drawable.ic_camera)
        val cams = listOf("off", "front", "back")
        camera.addView(ui.segmented(listOf(getString(R.string.camera_off), getString(R.string.camera_front), getString(R.string.camera_back)), cams.indexOf(settings.camera).coerceAtLeast(0)) { i ->
            settings.camera = cams[i]
            renderStatus()
        }, ui.matchWrap().apply { bottomMargin = ui.dp(6) })
        camera.addView(ui.field(getString(R.string.camera_size), ui.seek(38, ((settings.cameraScale - 0.12f) * 100).toInt()) { p -> settings.cameraScale = 0.12f + p / 100f }))
        val corners = listOf("top-left", "top-right", "bottom-left", "bottom-right")
        camera.addView(ui.field(getString(R.string.camera_corner), ui.segmented(listOf("↖", "↗", "↙", "↘"), corners.indexOf(settings.cameraCorner).coerceAtLeast(0)) { i -> settings.cameraCorner = corners[i] }))
        camera.addView(ui.field(getString(R.string.camera_shape), ui.segmented(listOf(getString(R.string.shape_circle), getString(R.string.shape_rect)), if (settings.cameraShape == "rect") 1 else 0) { i ->
            settings.cameraShape = if (i == 1) "rect" else "circle"
        }))
        camera.addView(ui.switchRow(getString(R.string.camera_mirror), null, settings.cameraMirror) { settings.cameraMirror = it })
        val rotations = listOf(-1, 0, 90, 180, 270)
        camera.addView(ui.field(getString(R.string.camera_rotation), ui.segmented(listOf(getString(R.string.rotation_auto), "0°", "90°", "180°", "270°"), rotations.indexOf(settings.cameraRotation).coerceAtLeast(0)) { i ->
            settings.cameraRotation = rotations[i]
        }, getString(R.string.rotate_hint)))
        box.addView(camera)

        // Zvuk
        val sound = ui.card(getString(R.string.section_audio), R.drawable.ic_volume)
        sound.addView(ui.switchRow(getString(R.string.game_audio), R.drawable.ic_volume, settings.gameAudio, getString(R.string.game_audio_hint)) {
            settings.gameAudio = it
            renderStatus()
        })
        sound.addView(ui.switchRow(getString(R.string.microphone), R.drawable.ic_mic, settings.microphone) {
            settings.microphone = it
            renderStatus()
        })
        box.addView(sound)

        // Kine
        val kine = ui.card(getString(R.string.section_kine), R.drawable.ic_upload)
        val acc = ui.hbox()
        acc.addView(ui.icon(R.drawable.ic_person, Palette.DIM, 22), ui.lp(ui.dp(22), ui.dp(22)).apply { marginEnd = ui.dp(14) })
        accountName = ui.text("", 15f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        acc.addView(accountName, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        accountBtn = ui.button(getString(R.string.sign_in), null, Ui.Kind.PRIMARY) {
            if (settings.loggedIn) {
                api.logout()
                renderAccount()
            } else startLogin()
        }
        accountBtn.root.minimumHeight = ui.dp(40)
        acc.addView(accountBtn.root, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        kine.addView(acc, ui.matchWrap())
        kine.addView(ui.text(getString(R.string.login_hint), 12f, Palette.FAINT), ui.matchWrap().apply { topMargin = ui.dp(6); bottomMargin = ui.dp(4) })
        kine.addView(ui.divider())
        kine.addView(ui.switchRow(getString(R.string.auto_upload), null, settings.autoUpload, getString(R.string.auto_upload_hint)) { settings.autoUpload = it })
        val vis = listOf("public", "subscribers", "private")
        kine.addView(ui.field(getString(R.string.visibility), ui.segmented(listOf(getString(R.string.vis_public), getString(R.string.vis_subscribers), getString(R.string.vis_private)), vis.indexOf(settings.visibility).coerceAtLeast(0)) { i ->
            settings.visibility = vis[i]
        }))
        if (settings.debuggable) {
            // Jen testovací sestavení: jiná adresa Kine (ostré mluví jen s playkine.com).
            kine.addView(ui.field(getString(R.string.site_url), EditText(this).apply {
                setText(settings.siteUrl)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                textSize = 14f
                setTextColor(Palette.TEXT)
                setHintTextColor(Palette.FAINT)
                backgroundTintList = ColorStateList.valueOf(Palette.LINE)
                setOnFocusChangeListener { _, focused -> if (!focused) settings.siteUrl = text.toString().ifBlank { Settings.DEFAULT_SITE } }
            }))
        }
        box.addView(kine)

        // O appce
        val app = ui.card(getString(R.string.section_app), R.drawable.ic_info)
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (_: Exception) { "" }
        val tierName = getString(when (tier) { Device.Tier.LOW -> R.string.tier_low; Device.Tier.MID -> R.string.tier_mid; Device.Tier.HIGH -> R.string.tier_high })
        app.addView(ui.text(getString(R.string.version, version) + " · " + getString(R.string.phone_class, tierName), 13f, Palette.DIM), ui.matchWrap().apply { bottomMargin = ui.dp(10) })
        app.addView(ui.button(getString(R.string.send_log), R.drawable.ic_share, Ui.Kind.GHOST) { sendLog() }.root, ui.matchWrap())
        box.addView(app)
        return scroll
    }

    private fun sendLog() {
        try {
            val share = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Kine Clipper log")
                .putExtra(Intent.EXTRA_TEXT, Device.describe(this) + "\n\n" + Log.text().takeLast(60_000))
            startActivity(Intent.createChooser(share, getString(R.string.send_log)))
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- klipy ----

    private fun thumbView(wDp: Int, hDp: Int): ImageView = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = ui.round(Palette.CARD_HI, 10)
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(ui.dp(wDp), ui.dp(hDp))
    }

    private fun meta(c: Clip): String {
        val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val res = if (c.width > 0 && c.height > 0) " · ${minOf(c.width, c.height)}p" else ""
        return "${df.format(Date(c.createdAt))} · ${size(c.sizeBytes)}$res"
    }

    private fun size(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / 1073741824.0)
        bytes >= 1024L * 1024 -> "${bytes / 1048576} MB"
        else -> "${bytes / 1024} kB"
    }

    private fun duration(ms: Long): String {
        val s = (ms / 1000).toInt()
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun play(c: Clip) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(c.uri), "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "", Toast.LENGTH_SHORT).show()
        }
    }

    private fun upload(c: Clip) {
        if (!settings.loggedIn) {
            Toast.makeText(this, R.string.need_login, Toast.LENGTH_SHORT).show()
            startLogin()
            return
        }
        library.update(c.id) { it.uploadState = "queued"; it.error = null }
        UploadService.kick(this)
    }

    private fun share(c: Clip) {
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, Uri.parse(c.uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), c.title))
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "", Toast.LENGTH_SHORT).show()
        }
    }

    private fun delete(c: Clip) {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setMessage(R.string.delete_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                thumbs.forget(c.uri)
                // Mazání z Galerie je práce s diskem - mimo hlavní vlákno.
                val app = applicationContext
                Thread({ library.remove(app, c.id) }, "kine-delete").start()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private class Holder(
        val thumb: ImageView,
        val duration: TextView,
        val title: TextView,
        val meta: TextView,
        val state: TextView,
        val upload: ImageView,
    ) {
        var clip: Clip? = null
    }

    /** Seznam klipů s náhledy (řádky se recyklují - plynulé i se stovkami klipů na starším telefonu). */
    private inner class ClipsAdapter : BaseAdapter() {
        var items: List<Clip> = emptyList()

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = items[position].id.hashCode().toLong()
        override fun hasStableIds(): Boolean = true

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: createRow()
            val h = view.tag as Holder
            val c = items[position]
            h.clip = c
            thumbs.load(c.uri, h.thumb)
            h.duration.text = duration(c.durationMs)
            h.title.text = c.title
            h.meta.text = meta(c)
            val (stateText, stateColor) = when (c.uploadState) {
                "queued" -> getString(R.string.upload_state_queued) to Palette.AMBER
                "uploading" -> getString(R.string.upload_state_uploading, c.uploadPercent) to Palette.AMBER
                "done" -> getString(R.string.upload_state_done) to Palette.GREEN
                "error" -> getString(R.string.upload_state_error, c.error ?: "") to Palette.RED
                else -> "" to Palette.DIM
            }
            h.state.text = stateText
            h.state.setTextColor(stateColor)
            h.state.visibility = if (stateText.isEmpty()) View.GONE else View.VISIBLE
            val busy = c.uploadState == "uploading" || c.uploadState == "queued"
            val done = c.uploadState == "done" && c.url != null
            h.upload.setImageResource(if (done) R.drawable.ic_open else R.drawable.ic_upload)
            h.upload.contentDescription = getString(if (done) R.string.open_on_kine else R.string.upload)
            h.upload.alpha = if (busy) 0.35f else 1f
            h.upload.isEnabled = !busy
            return view
        }

        private fun createRow(): View {
            val outer = FrameLayout(this@MainActivity).apply { setPadding(0, 0, 0, ui.dp(10)) }
            val row = ui.hbox().apply {
                background = ui.round(Palette.CARD, 16)
                setPadding(ui.dp(10), ui.dp(10), ui.dp(8), ui.dp(10))
                gravity = Gravity.TOP
            }
            val thumbBox = FrameLayout(this@MainActivity)
            val thumb = thumbView(120, 68)
            thumbBox.addView(thumb)
            val dur = ui.text("", 11f, Palette.TEXT, true).apply {
                background = ui.round(0xB3000000.toInt(), 6)
                setPadding(ui.dp(5), ui.dp(1), ui.dp(5), ui.dp(1))
            }
            thumbBox.addView(dur, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = ui.dp(4)
                bottomMargin = ui.dp(4)
            })
            row.addView(thumbBox)
            val info = ui.vbox()
            val title = ui.text("", 14f, Palette.TEXT, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            val meta = ui.text("", 12f, Palette.DIM).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            val state = ui.text("", 12f, Palette.DIM, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
            info.addView(title)
            info.addView(meta)
            info.addView(state)
            val actions = ui.hbox().apply { setPadding(0, ui.dp(2), 0, 0) }
            var holder: Holder? = null
            val playBtn = ui.iconButton(R.drawable.ic_play, Palette.TEXT, getString(R.string.play)) { holder?.clip?.let { play(it) } }
            val uploadBtn = ui.iconButton(R.drawable.ic_upload, Palette.BRAND, getString(R.string.upload)) {
                val c = holder?.clip ?: return@iconButton
                if (c.uploadState == "done" && c.url != null) {
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(c.url))) } catch (_: Exception) { }
                } else if (c.uploadState != "uploading" && c.uploadState != "queued") upload(c)
            }
            val shareBtn = ui.iconButton(R.drawable.ic_share, Palette.TEXT, getString(R.string.share)) { holder?.clip?.let { share(it) } }
            val deleteBtn = ui.iconButton(R.drawable.ic_delete, Palette.DIM, getString(R.string.delete)) { holder?.clip?.let { delete(it) } }
            actions.addView(playBtn)
            actions.addView(uploadBtn)
            actions.addView(shareBtn)
            actions.addView(deleteBtn)
            info.addView(actions)
            row.addView(info, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ui.dp(12) })
            thumbBox.setOnClickListener { holder?.clip?.let { play(it) } }
            outer.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val h = Holder(thumb, dur, title, meta, state, uploadBtn)
            holder = h
            outer.tag = h
            return outer
        }
    }

    companion object {
        private const val REQ_PERMISSIONS = 10
        private const val REQ_PROJECTION = 11
        private const val REQ_OVERLAY = 12
        private const val REQ_NOTIFICATIONS = 13
    }
}
