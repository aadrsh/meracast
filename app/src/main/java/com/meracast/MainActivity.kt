package com.meracast

import android.Manifest
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.meracast.common.AppConstants
import com.meracast.miracast.sink.MiracastSinkEngine
import com.meracast.miracast.source.MiracastSourceEngine
import com.meracast.miracast.source.SourceForegroundService
import com.meracast.miracast.wfd.WfdDevice
import com.meracast.ui.DebugLogStore
import com.meracast.ui.DebugOverlayView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Main Activity — mode selection, device discovery, streaming,
 * runtime permissions, and debug overlay.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "${AppConstants.TAG}.Main"
        private const val REQUEST_SCREEN_CAPTURE = AppConstants.REQUEST_CODE_SCREEN_CAPTURE
    }

    // ── Engines ────────────────────────────────────────────────────────
    private lateinit var sourceEngine: MiracastSourceEngine
    private lateinit var sinkEngine: MiracastSinkEngine

    // ── UI ─────────────────────────────────────────────────────────────
    private lateinit var modeToggle: androidx.appcompat.widget.SwitchCompat
    private lateinit var deviceListLayout: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var actionButton: Button
    private lateinit var audioToggle: androidx.appcompat.widget.SwitchCompat
    private lateinit var discoveryStatus: TextView
    private lateinit var surfaceView: SurfaceView
    private lateinit var deviceListContainer: ScrollView
    private lateinit var noDevicesText: TextView
    private lateinit var debugOverlay: DebugOverlayView
    private lateinit var debugToggle: androidx.appcompat.widget.SwitchCompat
    private lateinit var logToggle: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var receiveModeGroup: com.google.android.material.chip.ChipGroup
    private lateinit var chipNetwork: com.google.android.material.chip.Chip
    private lateinit var chipDirect: com.google.android.material.chip.Chip
    private lateinit var debugLogOverlay: com.meracast.ui.DebugLogOverlayView

    // ── State ──────────────────────────────────────────────────────────
    private var currentMode = "SINK"   // "SOURCE" | "SINK"
    private var isActive = false
    private var audioEnabled = true
    private var pendingScreenCapture = false
    private var useNetworkMode = true  // default to network mode (no Wi‑Fi Direct)

    // Permission launcher (Android 13+ NEARBY_WIFI_DEVICES)
    private val nearbyPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.all { it.value }
        if (allGranted) {
            Log.d(TAG, "Nearby Wi-Fi devices permission granted")
            if (pendingScreenCapture) requestScreenCapture()
        } else {
            Log.e(TAG, "NEARBY_WIFI_DEVICES permission denied")
            Toast.makeText(this,
                "NEARBY_WIFI_DEVICES permission is required for device discovery",
                Toast.LENGTH_LONG).show()
        }
    }

    // Screen capture launcher (modern Activity Result API)
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(result.resultCode, data)
            if (projection != null) {
                sourceEngine.setMediaProjection(projection)
                Log.d(TAG, "Screen capture permission granted")
            } else {
                Log.e(TAG, "MediaProjection creation returned null")
                Toast.makeText(this, "Failed to create MediaProjection", Toast.LENGTH_LONG).show()
                stopCurrentOperation()
            }
        } else {
            Log.e(TAG, "Screen capture permission denied by user")
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_LONG).show()
            stopCurrentOperation()
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initUi()
        sourceEngine = MiracastSourceEngine(application)
        sinkEngine = MiracastSinkEngine(application)
        setMode("SINK")
        Log.i(TAG, "MiracastHub started")
    }

    // ── UI initialisation ──────────────────────────────────────────────

    private fun initUi() {
        modeToggle = findViewById(R.id.mode_toggle)
        deviceListLayout = findViewById(R.id.device_list)
        statusText = findViewById(R.id.status_text)
        actionButton = findViewById(R.id.action_button)
        audioToggle = findViewById(R.id.audio_toggle)
        discoveryStatus = findViewById(R.id.discovery_status)
        surfaceView = findViewById(R.id.preview_surface)
        deviceListContainer = findViewById(R.id.device_list_scroll)
        noDevicesText = findViewById(R.id.no_devices_text)
        debugOverlay = findViewById(R.id.debug_overlay)
        debugToggle = findViewById(R.id.debug_toggle)
        logToggle = findViewById(R.id.log_toggle)
        receiveModeGroup = findViewById(R.id.receive_mode_group)
        chipNetwork = findViewById(R.id.chip_network)
        chipDirect = findViewById(R.id.chip_direct)
        debugLogOverlay = findViewById(R.id.debug_log_overlay)

        receiveModeGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            useNetworkMode = checkedIds.contains(R.id.chip_network)
        }

        modeToggle.setOnCheckedChangeListener { _, isChecked ->
            if (!isActive) {
                setMode(if (isChecked) "SOURCE" else "SINK")
            } else {
                modeToggle.isChecked = currentMode == "SOURCE"
                Log.w(TAG, "Blocked mode change while active")
                Toast.makeText(this, "Stop first to change mode", Toast.LENGTH_SHORT).show()
            }
        }

        actionButton.setOnClickListener {
            if (isActive) stopCurrentOperation()
            else startCurrentOperation()
        }

        audioToggle.setOnCheckedChangeListener { _, isChecked -> audioEnabled = isChecked }
        debugToggle.setOnCheckedChangeListener { _, isChecked ->
            debugOverlay.isVisible = isChecked
            debugOverlay.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        logToggle.setOnCheckedChangeListener { _, isChecked ->
            debugLogOverlay.isVisible = isChecked
            debugLogOverlay.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (!isChecked) {
                com.meracast.ui.DebugLogStore.clear()
            }
        }

        surfaceView.visibility = View.GONE
    }

    private fun setMode(mode: String) {
        currentMode = mode
        stopCurrentOperation()
        when (mode) {
            "SOURCE" -> {
                actionButton.setText(R.string.start_source)
                surfaceView.visibility = View.GONE
                deviceListContainer.visibility = View.VISIBLE
                debugOverlay.visibility = View.GONE
            }
            "SINK" -> {
                actionButton.setText(R.string.start_sink)
                surfaceView.visibility = View.VISIBLE
                deviceListContainer.visibility = View.GONE
            }
        }
        statusText.setText(R.string.status_idle)
        clearDeviceList()
    }

    // ── Permissions & Wi‑Fi checks ─────────────────────────────────────

    /**
     * Show a rationale dialog explaining WHY a permission is needed,
     * then request it.  Users are significantly more likely to grant
     * when they understand the reason.
     */
    private fun showPermissionRationale(
        title: String,
        message: String,
        permissions: Array<String>,
        onDenied: () -> Unit = {}
    ) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setIconAttribute(android.R.attr.alertDialogIcon)
            .setPositiveButton("Allow") { _: DialogInterface, _: Int ->
                nearbyPermissionLauncher.launch(permissions)
            }
            .setNegativeButton("Deny") { _: DialogInterface, _: Int ->
                Toast.makeText(this, "$title permission denied", Toast.LENGTH_LONG).show()
                onDenied()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Check and request all permissions needed for Wi‑Fi Direct (P2P).
     * Returns true if all are already granted.
     *
     * Required permissions by Android version:
     *   Android 13+  → NEARBY_WIFI_DEVICES  (replaces fine-location for P2P)
     *   Android 12–   → ACCESS_FINE_LOCATION (legacy requirement)
     *
     * Always returns immediately when already granted; shows rationale
     * dialog + system prompt otherwise.
     */
    private fun checkP2pPermissions(onDenied: () -> Unit = {}): Boolean {
        val needed = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+: NEARBY_WIFI_DEVICES replaces fine-location for P2P
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.NEARBY_WIFI_DEVICES
                ) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
        // All Android versions still need location for Wi‑Fi Direct discovery
        // (even on 13+, the P2P framework may fall through to location APIs)
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (needed.isEmpty()) return true

        // Build a single rationale message explaining all needed permissions
        val rationaleMessages = mutableListOf<String>()
        if (needed.contains(Manifest.permission.NEARBY_WIFI_DEVICES)) {
            rationaleMessages.add(
                "• Nearby Wi‑Fi Devices — to discover and connect to Miracast " +
                "devices over Wi‑Fi Direct without accessing your precise location."
            )
        }
        if (needed.contains(Manifest.permission.ACCESS_FINE_LOCATION)) {
            rationaleMessages.add(
                "• Location — Android requires location access to scan for " +
                "nearby Wi‑Fi Direct devices.  Your location is never stored " +
                "or transmitted."
            )
        }

        showPermissionRationale(
            title = "Wi‑Fi Direct Permissions",
            message =
                "MiracastHub uses Wi‑Fi Direct to stream your screen to " +
                "Miracast-compatible displays.\n\n" +
                rationaleMessages.joinToString("\n\n") + "\n\n" +
                "Without these permissions, device discovery and connection " +
                "will not work.",
            permissions = needed.toTypedArray(),
            onDenied = onDenied
        )
        return false
    }

    /**
     * Check if Wi‑Fi is enabled and prompt the user to turn it on if not.
     * Returns true when Wi‑Fi is on.
     */
    private fun checkWifiEnabled(prompt: Boolean = true): Boolean {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (wifi.isWifiEnabled) return true

        if (prompt) {
            AlertDialog.Builder(this)
                .setTitle("Wi‑Fi Required")
                .setMessage(
                    "Miracast requires Wi‑Fi to create a direct connection. " +
                    "Please turn on Wi‑Fi in Settings."
                )
                .setPositiveButton("Open Settings") { _: DialogInterface, _: Int ->
                    startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                }
                .setNegativeButton("Cancel") { _: DialogInterface, _: Int ->
                    Toast.makeText(this, "Wi‑Fi must be on to use Miracast",
                        Toast.LENGTH_LONG).show()
                }
                .show()
        }
        return false
    }

    private fun requestScreenCapture() {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mgr.createScreenCaptureIntent())
    }

    // ── Operations ─────────────────────────────────────────────────────

    private fun startCurrentOperation() {
        when (currentMode) {
            "SOURCE" -> startSourceMode()
            "SINK" -> startSinkMode()
        }
    }

    private fun startSourceMode() {
        if (!checkP2pPermissions()) {
            pendingScreenCapture = true
            return
        }
        pendingScreenCapture = false

        if (!checkWifiEnabled()) return

        sourceEngine.initialize()

        sourceEngine.onDeviceFound = { devices -> runOnUiThread { updateDeviceList(devices) } }
        sourceEngine.onStateChanged = { state ->
            runOnUiThread {
                statusText.text = state
                when (state) {
                    "DISCOVERING" -> discoveryStatus.setText(R.string.status_discovering)
                    "STREAMING" -> {
                        isActive = true
                        actionButton.setText(R.string.stop)
                        discoveryStatus.setText(R.string.status_streaming)
                    }
                    "IDLE" -> { isActive = false; actionButton.setText(R.string.start_source)
                        discoveryStatus.setText(R.string.status_idle) }
                }
            }
        }
        sourceEngine.onError = { error -> runOnUiThread {
            Log.e(TAG, "Source engine error: $error")
            Toast.makeText(this, "Error: $error", Toast.LENGTH_LONG).show()
        }}

        // Request screen capture (also captures internal audio)
        requestScreenCapture()

        // Foreground service
        val si = Intent(this, SourceForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(si)
        else startService(si)

        sourceEngine.startDiscovery()
        discoveryStatus.setText(R.string.status_discovering)

        // Wire debug overlay
        scope.launch {
            sourceEngine.stats.collect { s -> runOnUiThread { debugOverlay.updateStats(s) } }
        }
    }

    private fun startSinkMode() {
        DebugLogStore.clear()
        useNetworkMode = chipNetwork.isChecked

        if (!useNetworkMode) {
            // P2P mode needs permissions + Wi‑Fi Direct
            if (!checkP2pPermissions()) return
            if (!checkWifiEnabled()) return
        } else {
            // Network mode only needs a Wi‑Fi connection
            if (!checkWifiEnabled()) return
        }

        sinkEngine.initialize()
        DebugLogStore.log(if (useNetworkMode) "Mode: NETWORK (on same Wi‑Fi)" else "Mode: Wi‑Fi DIRECT (P2P)")

        sinkEngine.onStateChanged = { state ->
            runOnUiThread {
                statusText.text = state
                when (state) {
                    "STARTING", "WAITING_FOR_SOURCE" -> {
                        actionButton.setText(R.string.stop); isActive = true
                    }
                    "STREAMING" -> discoveryStatus.setText(R.string.status_streaming)
                    "IDLE" -> { isActive = false; actionButton.setText(R.string.start_sink)
                        discoveryStatus.setText(R.string.status_idle) }
                }
            }
        }
        sinkEngine.onError = { error -> runOnUiThread {
            Log.e(TAG, "Sink engine error: $error")
            Toast.makeText(this, "Error: $error", Toast.LENGTH_LONG).show()
        }}

        val surface = surfaceView.holder.surface
        if (surface.isValid) {
            if (useNetworkMode) sinkEngine.startNetworkReceiving(surface)
            else sinkEngine.startReceiving(surface)
        } else {
            surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(h: SurfaceHolder) {
                    if (useNetworkMode) sinkEngine.startNetworkReceiving(h.surface)
                    else sinkEngine.startReceiving(h.surface)
                }
                override fun surfaceChanged(holder: SurfaceHolder, fmt: Int, w: Int, h: Int) {}
                override fun surfaceDestroyed(h: SurfaceHolder) {
                    if (useNetworkMode) sinkEngine.stopNetworkReceiving()
                    else sinkEngine.stopReceiving()
                }
            })
        }

        // Wire debug overlay
        scope.launch {
            sinkEngine.stats.collect { s -> runOnUiThread { debugOverlay.updateStats(s) } }
        }
    }

    private fun stopCurrentOperation() {
        when (currentMode) {
            "SOURCE" -> { sourceEngine.stopCasting()
                stopService(Intent(this, SourceForegroundService::class.java)) }
            "SINK" -> {
                if (useNetworkMode) sinkEngine.stopNetworkReceiving()
                else sinkEngine.stopReceiving()
            }
        }
        isActive = false
        actionButton.setText(if (currentMode == "SOURCE") R.string.start_source else R.string.start_sink)
        statusText.setText(R.string.status_idle)
    }

    // ── Device list ────────────────────────────────────────────────────

    private fun updateDeviceList(devices: List<WfdDevice>) {
        deviceListLayout.removeAllViews()
        if (devices.isEmpty()) {
            noDevicesText.visibility = View.VISIBLE; deviceListLayout.visibility = View.GONE
            return
        }
        noDevicesText.visibility = View.GONE; deviceListLayout.visibility = View.VISIBLE
        for (device in devices) {
            val v = layoutInflater.inflate(R.layout.item_device, deviceListLayout, false)
            v.findViewById<TextView>(R.id.device_name).text =
                device.deviceName.ifEmpty { device.deviceAddress }
            v.findViewById<TextView>(R.id.device_type).text =
                when (device.primaryDeviceType) {
                    0 -> "Source"; 1 -> "Sink"; 2 -> "Secondary Sink"; 3 -> "Dual Role"
                    else -> "Unknown"
                } + " • ${device.deviceAddress.takeLast(8)}"
            v.findViewById<Button>(R.id.connect_button).setOnClickListener {
                Toast.makeText(this, "Connecting…", Toast.LENGTH_SHORT).show()
                sourceEngine.selectDevice(device)
            }
            deviceListLayout.addView(v)
        }
    }

    private fun clearDeviceList() { deviceListLayout.removeAllViews() }

    override fun onDestroy() {
        stopCurrentOperation()
        sourceEngine.release()
        sinkEngine.release()
        try { unregisterReceiver(null) } catch (_: Exception) {}
        super.onDestroy()
    }

    // Keep a scope for stats collection (Activity Result API used for permissions)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
}
