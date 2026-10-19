package com.kine.clipper

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings as SysSettings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.security.SecureRandom
import java.text.DateFormat
import java.util.Date

/**
 * Jediná obrazovka appky: stav + Spustit / Zastavit, nastavení (délka klipu,
 * kvalita, kamera, zvuk, bublina, Kine) a seznam klipů s nahráním na Kine.
 * Rozhraní je postavené v kódu (bez knihoven), ať appka nic nepotřebuje.
 */
class MainActivity : Activity() {
    private lateinit var settings: Settings
    private lateinit var api: KineApi
    private lateinit var library: Library
    private val main = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView
    private lateinit var startButton: Button
    private lateinit var clipButton: Button
    private lateinit var accountView: TextView
    private lateinit var loginButton: Button
    private lateinit var clipsBox: LinearLayout
    private lateinit var noteView: TextView
    private var pendingStart = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshStatus()
            renderClips()
        }
    }
    private val libraryListener: () -> Unit = { main.post { renderClips() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        api = KineApi(this, settings)
        library = Library.get(this)
        setContentView(buildUi())
        handleLink(intent)
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
        refreshStatus()
        renderClips()
        refreshAccount()
    }

    override fun onStop() {
        unregisterReceiver(receiver)
        library.removeListener(libraryListener)
        super.onStop()
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
                    refreshAccount()
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

    private fun refreshAccount() {
        if (settings.loggedIn) {
            accountView.text = getString(R.string.signed_in_as, settings.username ?: "kine")
            loginButton.text = getString(R.string.sign_out)
        } else {
            accountView.text = getString(R.string.not_signed_in)
            loginButton.text = getString(R.string.sign_in)
        }
    }

    // ---- start / stop ----

    private fun onStartClick() {
        if (CaptureService.running) {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
            return
        }
        val needed = ArrayList<String>()
        if (settings.microphone || settings.gameAudio) needed.add(Manifest.permission.RECORD_AUDIO)
        if (settings.camera != "off") needed.add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            pendingStart = true
            requestPermissions(missing.toTypedArray(), REQ_PERMISSIONS)
            return
        }
        if (settings.bubble && !SysSettings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.bubble_permission, Toast.LENGTH_LONG).show()
            pendingStart = true
            startActivityForResult(Intent(SysSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), REQ_OVERLAY)
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSIONS) return
        val denied = permissions.indices.filter { grantResults[it] != PackageManager.PERMISSION_GRANTED }.map { permissions[it] }
        val blocking = denied.filter { it != Manifest.permission.POST_NOTIFICATIONS }
        if (blocking.isNotEmpty()) {
            Toast.makeText(this, R.string.need_permissions, Toast.LENGTH_LONG).show()
            pendingStart = false
            return
        }
        if (pendingStart) {
            pendingStart = false
            onStartClick()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_PROJECTION -> {
                if (resultCode == RESULT_OK && data != null) {
                    val intent = Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_START)
                        .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                        .putExtra(CaptureService.EXTRA_RESULT_DATA, data)
                    startForegroundService(intent)
                    main.postDelayed({ refreshStatus() }, 800)
                } else Toast.makeText(this, R.string.status_no_projection, Toast.LENGTH_LONG).show()
            }
            REQ_OVERLAY -> {
                if (pendingStart) {
                    pendingStart = false
                    if (!SysSettings.canDrawOverlays(this)) settings.bubble = false
                    onStartClick()
                }
            }
        }
    }

    private fun refreshStatus() {
        val running = CaptureService.running
        startButton.text = getString(if (running) R.string.stop else R.string.start)
        clipButton.isEnabled = running
        val cam = CaptureService.cameraState
        val camText = when {
            !running || cam.isEmpty() -> ""
            cam == "on" -> " · camera on"
            cam == "starting" -> " · camera starting"
            cam == "missing" -> " · no camera"
            else -> " · camera: $cam"
        }
        statusView.text = (if (running) CaptureService.statusText.ifEmpty { getString(R.string.status_running, settings.clipSeconds) } else CaptureService.statusText.ifEmpty { getString(R.string.status_stopped) }) + camText
        noteView.visibility = if (running) View.VISIBLE else View.GONE
    }

    // ---- UI ----

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(32))
        }
        val brand = Color.parseColor("#2EE6C4")
        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(brand)
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.about_text)
            textSize = 13f
            alpha = 0.7f
            setPadding(0, dp(4), 0, dp(12))
        })

        // Účet
        accountView = TextView(this).apply { textSize = 14f }
        loginButton = Button(this).apply {
            setOnClickListener {
                if (settings.loggedIn) {
                    api.logout()
                    refreshAccount()
                } else startLogin()
            }
        }
        root.addView(accountView)
        root.addView(TextView(this).apply { text = getString(R.string.login_hint); textSize = 12f; alpha = 0.6f })
        root.addView(loginButton)

        // Stav a Start / Stop
        statusView = TextView(this).apply { textSize = 15f; setPadding(0, dp(16), 0, dp(6)) }
        root.addView(statusView)
        noteView = TextView(this).apply { text = getString(R.string.while_recording_hint); textSize = 12f; alpha = 0.6f; visibility = View.GONE }
        root.addView(noteView)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startButton = Button(this).apply {
            text = getString(R.string.start)
            setOnClickListener { onStartClick() }
        }
        clipButton = Button(this).apply {
            text = getString(R.string.clip_now)
            isEnabled = false
            setOnClickListener { startService(Intent(this@MainActivity, CaptureService::class.java).setAction(CaptureService.ACTION_CLIP)) }
        }
        row.addView(startButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(clipButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)

        // Nahrávání
        root.addView(header(R.string.section_recording))
        root.addView(spinnerRow(R.string.clip_length, listOf(15, 30, 45, 60, 90, 120).map { getString(R.string.clip_seconds_value, it) }, listOf(15, 30, 45, 60, 90, 120).indexOf(settings.clipSeconds).coerceAtLeast(0)) { i -> settings.clipSeconds = listOf(15, 30, 45, 60, 90, 120)[i] })
        root.addView(spinnerRow(R.string.quality, listOf("720p", "1080p"), if (settings.maxHeight >= 1080) 1 else 0) { i -> settings.maxHeight = if (i == 1) 1080 else 720 })
        root.addView(spinnerRow(R.string.frame_rate, listOf("30", "60"), if (settings.fps >= 60) 1 else 0) { i -> settings.fps = if (i == 1) 60 else 30 })
        root.addView(switchRow(R.string.bubble, settings.bubble) { on ->
            settings.bubble = on
            if (on && !SysSettings.canDrawOverlays(this)) startActivity(Intent(SysSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })

        // Kamera
        root.addView(header(R.string.section_camera))
        val cameras = listOf("off", "front", "back")
        root.addView(spinnerRow(R.string.camera, listOf(getString(R.string.camera_off), getString(R.string.camera_front), getString(R.string.camera_back)), cameras.indexOf(settings.camera).coerceAtLeast(0)) { i -> settings.camera = cameras[i] })
        root.addView(TextView(this).apply { text = getString(R.string.camera_size); textSize = 13f; alpha = 0.8f })
        root.addView(SeekBar(this).apply {
            max = 38
            progress = ((settings.cameraScale - 0.12f) * 100).toInt().coerceIn(0, 38)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) settings.cameraScale = 0.12f + p / 100f }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        })
        val corners = listOf("top-left", "top-right", "bottom-left", "bottom-right")
        root.addView(spinnerRow(R.string.camera_corner, listOf(getString(R.string.corner_tl), getString(R.string.corner_tr), getString(R.string.corner_bl), getString(R.string.corner_br)), corners.indexOf(settings.cameraCorner).coerceAtLeast(0)) { i -> settings.cameraCorner = corners[i] })
        root.addView(spinnerRow(R.string.camera_shape, listOf(getString(R.string.shape_circle), getString(R.string.shape_rect)), if (settings.cameraShape == "rect") 1 else 0) { i -> settings.cameraShape = if (i == 1) "rect" else "circle" })
        root.addView(switchRow(R.string.camera_mirror, settings.cameraMirror) { settings.cameraMirror = it })
        val rotations = listOf(-1, 0, 90, 180, 270)
        root.addView(spinnerRow(R.string.camera_rotation, listOf(getString(R.string.rotation_auto), "0°", "90°", "180°", "270°"), rotations.indexOf(settings.cameraRotation).coerceAtLeast(0)) { i -> settings.cameraRotation = rotations[i] })
        root.addView(TextView(this).apply { text = getString(R.string.rotate_hint); textSize = 12f; alpha = 0.6f })

        // Zvuk
        root.addView(header(R.string.section_audio))
        root.addView(switchRow(R.string.game_audio, settings.gameAudio) { settings.gameAudio = it })
        root.addView(switchRow(R.string.microphone, settings.microphone) { settings.microphone = it })

        // Kine
        root.addView(header(R.string.section_kine))
        root.addView(switchRow(R.string.auto_upload, settings.autoUpload) { settings.autoUpload = it })
        val vis = listOf("public", "subscribers", "private")
        root.addView(spinnerRow(R.string.visibility, listOf(getString(R.string.vis_public), getString(R.string.vis_subscribers), getString(R.string.vis_private)), vis.indexOf(settings.visibility).coerceAtLeast(0)) { i -> settings.visibility = vis[i] })
        root.addView(EditText(this).apply {
            setText(settings.siteUrl)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            textSize = 13f
            setOnFocusChangeListener { _, focused -> if (!focused) settings.siteUrl = text.toString().ifBlank { Settings.DEFAULT_SITE } }
        })

        // Klipy
        root.addView(header(R.string.section_clips))
        clipsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(clipsBox)

        // O appce
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (_: Exception) { "" }
        root.addView(TextView(this).apply { text = getString(R.string.version, version); textSize = 12f; alpha = 0.5f; setPadding(0, dp(24), 0, 0) })
        root.addView(Button(this).apply {
            text = getString(R.string.send_log)
            setOnClickListener {
                val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, Log.text().takeLast(60_000))
                startActivity(Intent.createChooser(share, getString(R.string.send_log)))
            }
        })

        return ScrollView(this).apply { addView(root) }
    }

    private fun header(res: Int): TextView = TextView(this).apply {
        text = getString(res)
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(6))
    }

    private fun spinnerRow(labelRes: Int, items: List<String>, selected: Int, onPick: (Int) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
        row.addView(TextView(this).apply { text = getString(labelRes); textSize = 14f }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        spinner.setSelection(selected.coerceIn(0, items.size - 1))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { onPick(position) }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        row.addView(spinner)
        return row
    }

    private fun switchRow(labelRes: Int, checked: Boolean, onChange: (Boolean) -> Unit): View = Switch(this).apply {
        text = getString(labelRes)
        textSize = 14f
        isChecked = checked
        setPadding(0, dp(6), 0, dp(6))
        setOnCheckedChangeListener { _, on -> onChange(on) }
    }

    private fun renderClips() {
        if (!::clipsBox.isInitialized) return
        clipsBox.removeAllViews()
        val clips = library.list()
        if (clips.isEmpty()) {
            clipsBox.addView(TextView(this).apply { text = getString(R.string.no_clips); alpha = 0.6f; textSize = 13f })
            return
        }
        val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        for (c in clips.take(50)) {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, dp(10)) }
            box.addView(TextView(this).apply { text = c.title; textSize = 15f; setTypeface(typeface, Typeface.BOLD) })
            val state = when (c.uploadState) {
                "queued" -> getString(R.string.upload_state_queued)
                "uploading" -> getString(R.string.upload_state_uploading, c.uploadPercent)
                "done" -> getString(R.string.upload_state_done)
                "error" -> getString(R.string.upload_state_error, c.error ?: "")
                else -> ""
            }
            box.addView(TextView(this).apply {
                text = "${df.format(Date(c.createdAt))} · ${c.durationMs / 1000} s · ${c.sizeBytes / 1024 / 1024} MB · ${c.width}×${c.height}" + if (state.isNotEmpty()) "\n$state" else ""
                textSize = 12f
                alpha = 0.7f
            })
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(Button(this).apply {
                text = getString(R.string.play)
                setOnClickListener {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(c.uri), "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, e.message ?: "", Toast.LENGTH_SHORT).show()
                    }
                }
            })
            if (c.uploadState == "done" && c.url != null) {
                actions.addView(Button(this).apply {
                    text = getString(R.string.open_on_kine)
                    setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(c.url))) }
                })
            } else if (c.uploadState != "uploading" && c.uploadState != "queued") {
                actions.addView(Button(this).apply {
                    text = getString(R.string.upload)
                    setOnClickListener {
                        if (!settings.loggedIn) {
                            startLogin()
                            return@setOnClickListener
                        }
                        library.update(c.id) { it.uploadState = "queued"; it.error = null }
                        UploadService.kick(this@MainActivity)
                    }
                })
            }
            actions.addView(Button(this).apply {
                text = getString(R.string.share)
                setOnClickListener {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, Uri.parse(c.uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), c.title))
                }
            })
            actions.addView(Button(this).apply {
                text = getString(R.string.delete)
                setOnClickListener { library.remove(this@MainActivity, c.id) }
            })
            box.addView(actions)
            clipsBox.addView(box)
        }
    }

    companion object {
        private const val REQ_PERMISSIONS = 10
        private const val REQ_PROJECTION = 11
        private const val REQ_OVERLAY = 12
    }
}
