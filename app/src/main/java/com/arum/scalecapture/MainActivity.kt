package com.arum.scalecapture

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ScaleCapture"
        private const val TARGET_NAME = "tzc"
        private const val MFG_COMPANY_LOW_BYTE = 0xC0
        private const val MFG_DATA_LEN = 13
        private const val MATCH_BY_NAME = true
        private const val MATCH_BY_MODEL_SIGNATURE = true
        private const val LOG_ALL_DEVICES = false
        private const val STABLE_FRAMES = 6
        private const val MIN_BODY_KG = 2.0

        private val COLORS = mapOf(
            ScaleDecoder.StatusKind.NORMAL to 0xFF2E7D32.toInt(),
            ScaleDecoder.StatusKind.LOW to 0xFFEF6C00.toInt(),
            ScaleDecoder.StatusKind.SLIGHTLY_HIGH to 0xFFF9A825.toInt(),
            ScaleDecoder.StatusKind.HIGH to 0xFFC62828.toInt(),
            ScaleDecoder.StatusKind.NONE to 0xFF888888.toInt(),
        )
    }

    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private var scanStartElapsedMs = 0L
    private var matchedCount = 0
    private var totalCount = 0

    private lateinit var logFile: File
    private val fileExecutor = Executors.newSingleThreadExecutor()
    private val iso = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val prefs: SharedPreferences by lazy { getSharedPreferences("scale_profile", MODE_PRIVATE) }

    // reading state
    private val recentWeights = ArrayDeque<Double>()
    private var liveWeightKg: Double? = null   // weight while someone is on the scale
    private var lockedWeightKg: Double? = null // last stabilized weight
    private var lastShownWeight = -1.0
    private var lastShownStable = false
    private var listenersReady = false
    private var beepedForCurrentLock = false   // ensures one beep per weighing, not per packet
    private var toneGen: ToneGenerator? = null

    private lateinit var statusView: TextView
    private lateinit var pathView: TextView
    private lateinit var counterView: TextView
    private lateinit var weightHeader: TextView
    private lateinit var modeView: TextView
    private lateinit var readingsContainer: LinearLayout
    private lateinit var lastPacketView: TextView
    private lateinit var heightEt: EditText
    private lateinit var ageEt: EditText
    private lateinit var sexGroup: RadioGroup
    private lateinit var sexMale: RadioButton
    private lateinit var sexFemale: RadioButton
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var shareBtn: Button

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) appendStatus("Permissions granted. Ready to scan.")
        else appendStatus("Permission denied - grant 'Nearby devices' in app settings.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        loadProfile()
        wireListeners()
        prepareLogFile()
        if (!hasAllPermissions()) permLauncher.launch(requiredPermissions())
        appendStatus("Set Height/Age/Sex, tap Start, then step on the scale.")
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        root.addView(TextView(this).apply {
            text = "Scale Readings"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, Typeface.BOLD)
        })
        statusView = TextView(this).apply {
            setPadding(0, dp(4), 0, dp(2)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        root.addView(statusView)

        // --- profile row: height / age / sex ---
        heightEt = numField("e.g. 173", decimal = false)
        ageEt = numField("e.g. 32", decimal = false)
        val profRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(2)); gravity = Gravity.BOTTOM
        }
        profRow.addView(labeledField("Height (cm)", heightEt))
        profRow.addView(labeledField("Age", ageEt))
        sexGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        sexMale = RadioButton(this).apply { text = "M" }
        sexFemale = RadioButton(this).apply { text = "F" }
        sexGroup.addView(sexMale); sexGroup.addView(sexFemale); sexMale.isChecked = true
        profRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f)
            addView(TextView(this@MainActivity).apply { text = "Sex"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f); setTextColor(Color.GRAY) })
            addView(sexGroup)
        })
        root.addView(profRow)

        // --- big weight + mode ---
        weightHeader = TextView(this).apply {
            text = "-- kg"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f); setTypeface(typeface, Typeface.BOLD)
        }
        modeView = TextView(this).apply {
            text = "waiting…"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setTextColor(Color.GRAY)
        }
        root.addView(weightHeader); root.addView(modeView)

        // --- scan buttons ---
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(2)) }
        startBtn = Button(this).apply { text = "Start"; setOnClickListener { startScan() } }
        stopBtn = Button(this).apply { text = "Stop"; isEnabled = false; setOnClickListener { stopScan() } }
        shareBtn = Button(this).apply { text = "Share log"; setOnClickListener { shareLog() } }
        val bw = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        btnRow.addView(startBtn, bw); btnRow.addView(stopBtn, bw); btnRow.addView(shareBtn, bw)
        root.addView(btnRow)

        counterView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f); setTextColor(Color.GRAY); text = "matched: 0   seen: 0"
        }
        root.addView(counterView)

        readingsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        scroll.addView(readingsContainer)
        root.addView(scroll)

        lastPacketView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f); setTextColor(Color.LTGRAY); setTypeface(Typeface.MONOSPACE)
        }
        root.addView(lastPacketView)
        pathView = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f); setTextColor(Color.LTGRAY) }
        root.addView(pathView)
        setContentView(root)
    }

    private fun numField(hint: String, decimal: Boolean) = EditText(this).apply {
        this.hint = hint
        inputType = if (decimal) (InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
                    else InputType.TYPE_CLASS_NUMBER
        setSingleLine(true)
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        isEnabled = true
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun labeledField(label: String, field: EditText): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f)
            setPadding(0, 0, dp(8), 0)
            addView(TextView(this@MainActivity).apply { text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f); setTextColor(Color.GRAY) })
            addView(field)
        }

    private fun wireListeners() {
        heightEt.doAfterTextChanged { if (listenersReady) recompute() }
        ageEt.doAfterTextChanged { if (listenersReady) recompute() }
        sexGroup.setOnCheckedChangeListener { _, _ -> if (listenersReady) recompute() }
        listenersReady = true
    }

    private fun loadProfile() {
        heightEt.setText(prefs.getString("height", "170"))
        ageEt.setText(prefs.getString("age", "30"))
        if (prefs.getString("sex", "MALE") == "FEMALE") sexFemale.isChecked = true else sexMale.isChecked = true
    }

    private fun currentProfile(): ScaleDecoder.Profile? {
        val h = heightEt.text.toString().trim().toDoubleOrNull() ?: return null
        val a = ageEt.text.toString().trim().toIntOrNull() ?: return null
        if (h < 80 || h > 250 || a < 5 || a > 120) return null
        val sex = if (sexFemale.isChecked) ScaleDecoder.Sex.FEMALE else ScaleDecoder.Sex.MALE
        prefs.edit().putString("height", h.toString()).putString("age", a.toString()).putString("sex", sex.name).apply()
        return ScaleDecoder.Profile(h, a, sex)
    }

    private fun appendStatus(msg: String) = runOnUiThread { statusView.text = msg; Log.i(TAG, msg) }
    private fun updateCounters() = runOnUiThread { counterView.text = "matched: $matchedCount   seen: $totalCount" }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_SCAN)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasAllPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun prepareLogFile() {
        logFile = createSegmentFile()
        pathView.text = logFile.absolutePath
    }

    /**
     * Create a fresh, timestamped log segment and write its header. Each "Share log" press rotates
     * to a new segment, so a shared file only ever contains the packets captured since the previous
     * share (or since app start) - even without restarting the app.
     */
    private fun createSegmentFile(): File {
        val dir = getExternalFilesDir(null) ?: filesDir
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val file = File(dir, "scale_capture_$stamp.txt")
        val header = buildString {
            appendLine("# Scale Capture log")
            appendLine("# device: ${Build.MANUFACTURER} ${Build.MODEL}  Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("# match: name=$TARGET_NAME OR model-signature (companyLowByte=0x${"%02X".format(MFG_COMPANY_LOW_BYTE)}, dataLen=$MFG_DATA_LEN, last6=self-MAC-echo) - NO hardcoded per-unit MAC")
            appendLine("# columns: elapsed_ms<TAB>iso<TAB>rssi<TAB>link_addr<TAB>name<TAB>MATCH=<name|sig|both><TAB>MFG=<cid>:<hex><TAB>RAW=<adv_hex>")
            appendLine("# segment started ${iso.format(System.currentTimeMillis())}")
            appendLine("#")
        }
        fileExecutor.execute {
            try { FileWriter(file, true).use { it.append(header) } }
            catch (e: Exception) { Log.e(TAG, "segment header write failed", e) }
        }
        return file
    }

    private fun writeRaw(text: String) {
        val target = logFile // bind each write to the segment active when the packet arrived
        fileExecutor.execute {
            try { FileWriter(target, true).use { it.append(text) } }
            catch (e: Exception) { Log.e(TAG, "file write failed", e) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (scanning) return
        if (!hasAllPermissions()) { permLauncher.launch(requiredPermissions()); appendStatus("Grant 'Nearby devices', then Start again."); return }
        val mgr = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter? = mgr?.adapter
        if (adapter == null || !adapter.isEnabled) { appendStatus("Turn Bluetooth ON, then Start again."); return }
        scanner = adapter.bluetoothLeScanner
        if (scanner == null) { appendStatus("BLE scanner unavailable."); return }
        currentProfile()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(0L).build()
        try {
            scanner!!.startScan(null, settings, scanCallback)
            scanning = true; scanStartElapsedMs = SystemClock.elapsedRealtime()
            matchedCount = 0; totalCount = 0; recentWeights.clear(); updateCounters()
            startBtn.isEnabled = false; stopBtn.isEnabled = true
            appendStatus("Scanning… step on the scale now.")
            writeRaw("# --- scan started ${iso.format(System.currentTimeMillis())} ---\n")
        } catch (e: SecurityException) { appendStatus("Scan blocked: ${e.message}") }
        catch (e: Exception) { appendStatus("Scan failed: ${e.message}") }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        try { scanner?.stopScan(scanCallback) } catch (e: Exception) { Log.e(TAG, "stopScan", e) }
        scanning = false; startBtn.isEnabled = true; stopBtn.isEnabled = false
        appendStatus("Stopped. Matched $matchedCount packets.")
        writeRaw("# --- scan stopped ${iso.format(System.currentTimeMillis())}  matched=$matchedCount ---\n")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            totalCount++
            val record = result.scanRecord ?: return
            val name = record.deviceName
            val raw = record.bytes ?: ByteArray(0)
            val nameMatch = MATCH_BY_NAME && name != null && name.equals(TARGET_NAME, true)
            val sigMatch = MATCH_BY_MODEL_SIGNATURE && matchesScaleModel(result, record)
            val matched = LOG_ALL_DEVICES || nameMatch || sigMatch
            if (!matched) { if (totalCount % 50 == 0) updateCounters(); return }
            matchedCount++
            val matchTag = buildString {
                if (nameMatch) append("name")
                if (sigMatch) { if (isNotEmpty()) append("+"); append("sig") }
                if (isEmpty()) append("all")
            }
            val elapsed = SystemClock.elapsedRealtime() - scanStartElapsedMs

            val msd = record.manufacturerSpecificData
            val mfg = StringBuilder()
            var reading: ScaleDecoder.ScaleReading? = null
            for (i in 0 until msd.size()) {
                val cid = msd.keyAt(i); val data = msd.valueAt(i)
                if (mfg.isNotEmpty()) mfg.append(";")
                mfg.append("%04X".format(cid)).append(":").append(data.toHex(" "))
                if (reading == null) reading = ScaleDecoder.decode(cid, data)
            }
            if (mfg.isEmpty()) mfg.append("(none)")
            val line = buildString {
                append(elapsed); append('\t'); append(iso.format(System.currentTimeMillis())); append('\t')
                append(result.rssi); append('\t'); append(result.device.address); append('\t')
                append(name ?: "?"); append('\t'); append("MATCH="); append(matchTag); append('\t')
                append("MFG="); append(mfg); append('\t'); append("RAW="); append(raw.toHex(""))
            }
            writeRaw(line + "\n")
            updateCounters()

            val r = reading
            if (r != null) {
                runOnUiThread { lastPacketView.text = "f#${r.frameCounter} flag=0x${"%02X".format(r.flagByte)} mac=${r.linkMac}  raw=${r.weightKg}kg" }
                handleWeight(r.weightKg)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            appendStatus("onScanFailed: code $errorCode"); writeRaw("# onScanFailed code=$errorCode\n")
        }
    }

    private fun handleWeight(w: Double) {
        if (w > MIN_BODY_KG) {
            liveWeightKg = w
            recentWeights.addLast(w); while (recentWeights.size > 8) recentWeights.removeFirst()
            val stable = recentWeights.size >= STABLE_FRAMES && recentWeights.all { it == w }
            if (stable) {
                lockedWeightKg = w
                if (!beepedForCurrentLock) { beep(); beepedForCurrentLock = true } // beep once: dead-stable
            }
            renderReadings(w, stable)
        } else {
            recentWeights.clear(); liveWeightKg = null
            beepedForCurrentLock = false // re-arm so the next weighing beeps again
            lockedWeightKg?.let { renderReadings(it, true) }
        }
    }

    /** Short audible beep to signal the reading is dead-stable (stabilized). */
    private fun beep() {
        try {
            val gen = toneGen ?: ToneGenerator(AudioManager.STREAM_MUSIC, 90).also { toneGen = it }
            gen.startTone(ToneGenerator.TONE_PROP_BEEP, 180)
        } catch (e: Exception) { Log.w(TAG, "beep failed", e) }
    }

    /** Re-render the current/locked reading after a profile (height/age/sex) edit. */
    private fun recompute() {
        val live = liveWeightKg
        val locked = lockedWeightKg
        val w: Double; val stable: Boolean
        when {
            live != null -> { w = live; stable = (locked != null && locked == live) }
            locked != null -> { w = locked; stable = true }
            else -> { showIdle(); return }
        }
        lastShownWeight = -1.0 // force refresh
        renderReadings(w, stable)
    }

    private fun showIdle() = runOnUiThread {
        weightHeader.text = "-- kg"
        modeView.text = "Tap Start, then step on the scale"
        modeView.setTextColor(Color.GRAY)
        readingsContainer.removeAllViews()
        if (currentProfile() == null) readingsContainer.addView(noteView("Enter a valid Height (cm) and Age first."))
    }

    private fun renderReadings(weightKg: Double, stabilized: Boolean) {
        if (weightKg == lastShownWeight && stabilized == lastShownStable) return
        lastShownWeight = weightKg; lastShownStable = stabilized
        val profile = currentProfile()
        runOnUiThread {
            weightHeader.text = "%.2f kg".format(weightKg)
            modeView.text = if (stabilized) "● STABILIZED" else "○ live (step on & hold still)"
            modeView.setTextColor(if (stabilized) 0xFF2E7D32.toInt() else Color.GRAY)
            readingsContainer.removeAllViews()
            if (profile == null) {
                readingsContainer.addView(noteView("Enter a valid Height (cm) and Age above to see body-composition estimates."))
                return@runOnUiThread
            }
            val metrics = ScaleDecoder.computeMetrics(weightKg, profile)
            var lastGroup = ""
            for (m in metrics) {
                if (m.group != lastGroup) { readingsContainer.addView(groupHeader(m.group)); lastGroup = m.group }
                readingsContainer.addView(metricRow(m))
            }
            readingsContainer.addView(noteView("All values except Weight are ESTIMATES from BMI + age + sex (CUN-BAE) - not measured. Weight is the only measured value. ≈ = rough approximation."))
        }
    }

    private fun groupHeader(title: String) = TextView(this).apply {
        text = title; setTypeface(typeface, Typeface.BOLD); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(0, dp(10), 0, dp(2)); setTextColor(0xFF37474F.toInt())
    }

    private fun metricRow(m: ScaleDecoder.MetricRow): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(2), dp(3), dp(2), dp(3)); gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(this).apply {
            text = m.label; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 5f)
        })
        row.addView(TextView(this).apply {
            text = m.value; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.END; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 4f)
        })
        row.addView(TextView(this).apply {
            text = m.status; setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); gravity = Gravity.END
            setTextColor(COLORS[m.kind] ?: Color.GRAY)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f)
        })
        return row
    }

    private fun noteView(text: String) = TextView(this).apply {
        this.text = text; setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f); setTextColor(Color.GRAY); setPadding(0, dp(8), 0, dp(4))
    }

    /**
     * Rotate + share. Finalizes the current segment, switches capture to a fresh segment, then
     * shares ONLY the just-closed segment - so the shared file contains exactly the packets from
     * the previous share (or app start) up to this press. Earlier data is never re-shared, and no
     * app restart is needed. Called on the UI thread (button click).
     */
    private fun shareLog() {
        if (!::logFile.isInitialized) { appendStatus("Nothing to share yet."); return }
        val now = System.currentTimeMillis()
        val sharedAt = iso.format(now)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)
        val segment = logFile
        // Route subsequent packets to a new segment BEFORE finalizing/sharing this one.
        val next = createSegmentFile()
        logFile = next
        pathView.text = next.absolutePath
        // After every queued write to `segment` has flushed (single-thread FIFO), finalize + share.
        fileExecutor.execute {
            try {
                FileWriter(segment, true).use { it.append("# --- shared $sharedAt ---\n") }
            } catch (e: Exception) { Log.e(TAG, "finalize segment", e) }
            // Name the exported file by this share's timestamp; fall back to the segment if needed.
            val shareFile = File(segment.parentFile, "scale_share_$stamp.txt")
            val out = if (segment.renameTo(shareFile)) shareFile else segment
            runOnUiThread { doShare(out) }
        }
    }

    private fun doShare(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share capture log"))
            appendStatus("Shared ${file.name}. A new log was started for the next session.")
        } catch (e: Exception) { appendStatus("Share failed: ${e.message}. File: ${file.absolutePath}") }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (scanning) stopScan()
        fileExecutor.shutdown()
        toneGen?.release(); toneGen = null
    }

    private fun ByteArray.toHex(sep: String): String = joinToString(sep) { "%02X".format(it) }

    /**
     * Tier-1, device-agnostic model recognition - NO hardcoded per-unit MAC.
     */
    private fun matchesScaleModel(result: ScanResult, record: ScanRecord): Boolean {
        val msd = record.manufacturerSpecificData ?: return false
        val addrBytes: List<Byte>? = runCatching {
            result.device.address.split(":").map { it.toInt(16).toByte() }
        }.getOrNull()
        for (i in 0 until msd.size()) {
            val companyId = msd.keyAt(i)
            val data = msd.valueAt(i) ?: continue
            if ((companyId and 0xFF) != MFG_COMPANY_LOW_BYTE) continue
            if (data.size != MFG_DATA_LEN) continue
            if (addrBytes != null && addrBytes.size == 6 &&
                data.copyOfRange(7, 13).toList() == addrBytes) return true
            if (data[2] == 0x13.toByte() && data[3] == 0x88.toByte()) return true
        }
        return false
    }
}
