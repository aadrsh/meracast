package com.meracast

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import android.view.LayoutInflater
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.meracast.common.*
import com.meracast.media.*
import com.meracast.miracast.rtp.RtpReceiver
import com.meracast.miracast.sink.AudioPlayer
import com.meracast.miracast.sink.VideoRenderer
import com.meracast.miracast.source.ScreenStreamer
import com.meracast.miracast.wfd.WfdDevice
import com.meracast.ui.DebugLogStore
import com.meracast.ui.DebugLogOverlayView
import com.meracast.ui.NsdDiscoveryHelper
import kotlinx.coroutines.*
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Meracast App-to-App Media Streaming screen.
 *
 * Flow:
 *   SOURCE: Broadcast → Pair → Pick file → Serve
 *   SINK:   Discover → Pair → Browse files → Play
 */
class MediaActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "${AppConstants.TAG}.MediaAct"
    }

    private var currentMode = "SOURCE"  // "SOURCE" | "SINK"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var backgroundScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Components
    private lateinit var fileProvider: MediaFileProvider
    private lateinit var mediaPlayer: MediaPlayerEngine
    private val mediaHttpServer by lazy { MediaHttpServer(contentResolver) }
    private lateinit var nsdHelper: NsdDiscoveryHelper
    private var controlServer: ControlSocket.ControlServer? = null
    private var controlClient: ControlSocket.ControlClient? = null
    private var isPlaying = false
    private var wifiIp: String? = null

    // State
    private var isBroadcasting = false      // SOURCE mode: broadcasting (server running)
    private var isPaired = false            // Whether we have an active pairing
    private var pairedDeviceName = ""       // Name of the paired peer
    private var isBrowsingRemoteFiles = false // SINK mode: showing remote file list

    // Files
    private var localFiles = listOf<ControlMessage.FileEntry>()
    private var remoteFiles = listOf<ControlMessage.FileEntry>()
    private var currentServingFile: ControlMessage.FileEntry? = null
    private var currentServingUrl: String? = null
    private var currentMediaId: String? = null

    // Discovered peers (seen on network)
    private var discoveredDevices = listOf<WfdDevice>()
    private var connectedDevice: WfdDevice? = null

    // UI
    private lateinit var btnSourceMode: Button
    private lateinit var btnSinkMode: Button
    private lateinit var sourcePanel: View
    private lateinit var sinkPanel: View
    private lateinit var pairingPanel: View  // shared pairing area
    private lateinit var playerContainer: View
    private lateinit var fileRecycler: RecyclerView
    private lateinit var deviceRecycler: RecyclerView
    private lateinit var noFilesText: TextView
    private lateinit var noDevicesText: TextView
    private lateinit var statusText: TextView
    private lateinit var pairedInfoText: TextView  // shows paired device info
    private lateinit var surfaceView: SurfaceView
    private lateinit var seekBar: SeekBar
    private lateinit var positionText: TextView
    private lateinit var durationText: TextView
    private lateinit var bufferText: TextView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnStop: ImageButton
    private lateinit var logOverlay: DebugLogOverlayView
    private var isSeekbarTracking = false

    // ── Screen casting ──────────────────────────────────
    private val screenStreamer by lazy { ScreenStreamer(application) }
    private var rtpReceiver = RtpReceiver()
    private var videoRenderer = VideoRenderer()
    private var audioPlayer = AudioPlayer()
    private var castInProgress = false
    private var castSinkIp: String? = null
    private var pendingScreenCapture = false
    private var pendingServeFile: ControlMessage.FileEntry? = null

    // ── Permission launchers ─────────────────────────────

    private val mediaPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            pendingServeFile?.let { serveFileToPair(it) }
        } else {
            statusText.text = "Media read permission required to serve files"
            DebugLogStore.log("MEDIA: Media permission denied by user")
        }
        pendingServeFile = null
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mgr.getMediaProjection(result.resultCode, data)
            if (projection != null) {
                startScreenCast(projection)
            }
        } else {
            DebugLogStore.log("CAST: Screen capture permission denied")
            statusText.text = "Screen capture permission required"
        }
    }

    // ── Lifecycle ────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_media)

        fileProvider = MediaFileProvider(application)
        mediaPlayer = MediaPlayerEngine(application)
        nsdHelper = NsdDiscoveryHelper(application)

        initUi()
        setMode("SOURCE")
        DebugLogStore.log("MEDIA: Activity started")
    }

    private fun initUi() {
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        btnSourceMode = findViewById(R.id.btn_source_mode)
        btnSinkMode = findViewById(R.id.btn_sink_mode)
        sourcePanel = findViewById(R.id.media_source_panel)
        sinkPanel = findViewById(R.id.media_sink_panel)
        pairingPanel = findViewById(R.id.media_pairing_panel)
        playerContainer = findViewById(R.id.media_player_container)
        fileRecycler = findViewById(R.id.media_file_list)
        deviceRecycler = findViewById(R.id.media_device_list)
        noFilesText = findViewById(R.id.media_no_files_text)
        noDevicesText = findViewById(R.id.media_no_devices_text)
        statusText = findViewById(R.id.media_status)
        pairedInfoText = findViewById(R.id.media_paired_info)
        surfaceView = findViewById(R.id.media_surface)
        seekBar = findViewById(R.id.media_seek_bar)
        positionText = findViewById(R.id.media_position)
        durationText = findViewById(R.id.media_duration)
        bufferText = findViewById(R.id.media_buffer_status)
        btnPlayPause = findViewById(R.id.btn_play_pause)
        btnStop = findViewById(R.id.btn_stop)
        logOverlay = findViewById(R.id.media_log_overlay)

        btnSourceMode.setOnClickListener {
            stopCurrentOperation()
            setMode("SOURCE")
        }
        btnSinkMode.setOnClickListener {
            stopCurrentOperation()
            setMode("SINK")
        }

        fileRecycler.layoutManager = LinearLayoutManager(this)
        deviceRecycler.layoutManager = LinearLayoutManager(this)

        btnPlayPause.setOnClickListener {
            if (isPlaying) mediaPlayer.pause() else mediaPlayer.resume()
        }
        btnStop.setOnClickListener { stopPlayback() }

        // "Start Broadcasting" / "Stop" button (source mode)
        findViewById<Button>(R.id.btn_broadcast).setOnClickListener {
            if (isBroadcasting) stopBroadcasting()
            else startBroadcasting()
        }

        // "Receive Cast" button (sink mode, after pairing)
        findViewById<Button>(R.id.btn_receive_cast).setOnClickListener {
            if (castInProgress) stopReceiveCast()
            else requestReceiveCast()
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar?) { isSeekbarTracking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                isSeekbarTracking = false
                val duration = mediaPlayer.getDuration()
                if (duration > 0) {
                    val pos = (sb?.progress ?: 0) / 1000.0 * duration
                    mediaPlayer.seekTo(pos)
                    controlClient?.send(ControlMessage.Seek(pos))
                }
            }
        })

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {
                if (currentServingUrl != null && isPaired) {
                    startExoPlayer(h.surface)
                }
            }
            override fun surfaceChanged(holder: SurfaceHolder, fmt: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) { mediaPlayer.stop() }
        })

        // Wire player callbacks
        mediaPlayer.onPositionUpdate = { update ->
            runOnUiThread {
                if (!isSeekbarTracking) {
                    val posMs = (update.position * 1000).toInt()
                    val durMs = (update.duration * 1000).toInt()
                    if (durMs > 0) {
                        seekBar.progress = (posMs * 1000 / durMs).coerceIn(0, 1000)
                    }
                    positionText.text = formatTime(update.position)
                    durationText.text = formatTime(update.duration)
                }
                bufferText.text = "${update.bufferedPercent}% buffered"
                when (update.state) {
                    "playing" -> { isPlaying = true; btnPlayPause.setImageResource(android.R.drawable.ic_media_pause) }
                    "paused" -> { isPlaying = false; btnPlayPause.setImageResource(android.R.drawable.ic_media_play) }
                    "buffering" -> bufferText.text = "Buffering… ${update.bufferedPercent}%"
                    "idle" -> stopPlayback()
                }
                // Notify peer
                controlClient?.send(update)
            }
        }

        mediaPlayer.onError = { msg ->
            runOnUiThread {
                DebugLogStore.log("MEDIA: Error — $msg")
                statusText.text = "Error: $msg"
            }
        }
    }

    // ── Mode switching ──────────────────────────────────

    private fun setMode(mode: String) {
        currentMode = mode
        val isSource = mode == "SOURCE"
        btnSourceMode.isEnabled = !isSource
        btnSinkMode.isEnabled = isSource

        sourcePanel.visibility = if (isSource) View.VISIBLE else View.GONE
        sinkPanel.visibility = if (isSource) View.GONE else View.VISIBLE

        hideAllContent()
        updateUi()
    }

    /** Hide content panels (player, pairing, file lists) between mode switches. */
    private fun hideAllContent() {
        pairingPanel.visibility = View.GONE
        playerContainer.visibility = View.GONE
        fileRecycler.visibility = View.GONE
        noFilesText.visibility = View.GONE
        deviceRecycler.visibility = View.GONE
        noDevicesText.visibility = View.GONE
        isBroadcasting = false
        isPaired = false
        isBrowsingRemoteFiles = false
        pairedDeviceName = ""
    }

    /** Show the pairing panel with paired device info. */
    private fun showPaired(peerName: String) {
        isPaired = true
        pairedDeviceName = peerName
        pairingPanel.visibility = View.VISIBLE
        pairedInfoText.text = "Paired with $peerName"
        statusText.text = "Paired with $peerName"
    }

    /** Show the player view. */
    private fun showPlayer() {
        pairingPanel.visibility = View.GONE
        fileRecycler.visibility = View.GONE
        noFilesText.visibility = View.GONE
        playerContainer.visibility = View.VISIBLE
    }

    private fun hidePlayer() {
        playerContainer.visibility = View.GONE
        updateUi()
    }

    /** Refresh UI visibility based on current state. */
    private fun updateUi() {
        if (currentMode == "SOURCE") {
            updateSourceUi()
        } else {
            updateSinkUi()
        }
    }

    // ═══════════════════════════════════════════════════
    //  SOURCE MODE — broadcast → pair → serve file
    // ═══════════════════════════════════════════════════

    private fun updateSourceUi() {
        val btn = findViewById<Button>(R.id.btn_broadcast)
        if (isBroadcasting) {
            btn.text = "Stop Broadcasting"
            if (!isPaired) {
                statusText.text = "Broadcasting… waiting for a receiver"
                noFilesText.visibility = View.GONE
                fileRecycler.visibility = View.GONE
            }
            // When paired, file list is shown by showPaired → showLocalFiles
        } else {
            btn.text = "Start Broadcasting"
            pairingPanel.visibility = View.GONE
            noFilesText.visibility = View.GONE
            fileRecycler.visibility = View.GONE
            statusText.text = getString(R.string.status_idle)
        }
    }

    private fun startBroadcasting() {
        wifiIp = getWifiIp()
        if (wifiIp == null) {
            statusText.text = "Not connected to Wi‑Fi"
            return
        }

        // Stop any previous server
        controlServer?.stop()
        controlServer = null

        isBroadcasting = true
        updateSourceUi()
        DebugLogStore.log("MEDIA: Starting broadcast as source")
        statusText.text = "Broadcasting… waiting for a receiver"

        // Start control server
        controlServer = ControlSocket.ControlServer(
            onMessage = { msg -> handleSourceMessage(msg) },
            onClientConnected = { peerName ->
                castSinkIp = controlServer?.remoteAddress ?: wifiIp
                runOnUiThread {
                    DebugLogStore.log("MEDIA: Paired with receiver \"$peerName\"")
                    showPaired(peerName)
                    // Now show local files for the user to pick and serve
                    loadLocalFiles()
                }
            },
            onClientDisconnected = {
                runOnUiThread {
                    DebugLogStore.log("MEDIA: Receiver disconnected")
                    isPaired = false
                    pairingPanel.visibility = View.GONE
                    fileRecycler.visibility = View.GONE
                    statusText.text = "Receiver disconnected"
                }
            }
        )
        controlServer?.start()
        nsdHelper.registerSource()
    }

    private fun stopBroadcasting() {
        isBroadcasting = false
        isPaired = false
        controlServer?.stop()
        controlServer = null
        nsdHelper.unregister()
        mediaHttpServer.stop()
        hidePlayer()
        updateSourceUi()
        DebugLogStore.log("MEDIA: Broadcasting stopped")
    }

    /** Load local media files and show them for the user to pick. */
    private fun loadLocalFiles() {
        if (!hasMediaReadPermission() && Build.VERSION.SDK_INT < 30) {
            statusText.text = "Requesting media permission…"
            return
        }
        backgroundScope.launch {
            val files = fileProvider.listMediaFiles(100)
            localFiles = files
            runOnUiThread {
                if (files.isEmpty()) {
                    noFilesText.visibility = View.VISIBLE
                    noFilesText.text = "No media files found on this device"
                    fileRecycler.visibility = View.GONE
                    statusText.text = "Paired with $pairedDeviceName — no files found"
                } else {
                    noFilesText.visibility = View.GONE
                    fileRecycler.visibility = View.VISIBLE
                    fileRecycler.adapter = FileListAdapter(files) { entry ->
                        serveFileToPair(entry)
                    }
                    statusText.text = "Paired with $pairedDeviceName — pick a file to stream"
                }
            }
        }
    }

    /**
     * After pairing, user picks a local file → serve it + send MediaInfo to paired receiver.
     */
    private fun serveFileToPair(file: ControlMessage.FileEntry) {
        statusText.text = "Preparing to serve \"${file.name}\"…"
        DebugLogStore.log("MEDIA: Serving \"${file.name}\" to paired receiver \"$pairedDeviceName\"")

        if (!hasMediaReadPermission()) {
            DebugLogStore.log("MEDIA: Media permission not granted — requesting")
            statusText.text = "Requesting media read permission…"
            pendingServeFile = file
            requestMediaReadPermission()
            return
        }

        wifiIp = getWifiIp()
        if (wifiIp == null) {
            statusText.text = "Not connected to Wi‑Fi"
            return
        }

        currentServingFile = file
        mediaHttpServer.start()

        val contentUri = buildContentUri(file)
        val url = try {
            mediaHttpServer.serveFile(contentUri, file.mimeType, wifiIp!!)
        } catch (e: SecurityException) {
            pendingServeFile = file
            requestMediaReadPermission()
            return
        }

        if (url == null) {
            statusText.text = "Cannot access file"
            DebugLogStore.log("MEDIA: Failed to open file")
            mediaHttpServer.stop()
            return
        }
        currentServingUrl = url
        currentMediaId = file.id

        // Send MediaInfo over the already-established control channel
        controlServer?.send(ControlMessage.MediaInfo(
            mediaId = file.id,
            httpUrl = url,
            duration = file.duration,
            mimeType = file.mimeType,
            fileSize = file.size
        ))

        DebugLogStore.log("MEDIA: Streaming \"${file.name}\" at $url to \"$pairedDeviceName\"")
        statusText.text = "Streaming \"${file.name}\" to $pairedDeviceName — play on receiver"
    }

    private fun handleSourceMessage(msg: ControlMessage) {
        when (msg) {
            is ControlMessage.Play -> {
                val pos = msg.position
                DebugLogStore.log("MEDIA: Receiver requested PLAY (position=$pos)")
                runOnUiThread {
                    showPlayer()
                    if (surfaceView.holder.surface.isValid) {
                        startExoPlayer(surfaceView.holder.surface, pos)
                    }
                    controlServer?.send(ControlMessage.Playing(msg.mediaId, pos))
                }
            }
            is ControlMessage.Pause -> runOnUiThread { mediaPlayer.pause() }
            is ControlMessage.Resume -> runOnUiThread { mediaPlayer.resume() }
            is ControlMessage.Seek -> runOnUiThread { mediaPlayer.seekTo(msg.position) }
            is ControlMessage.Stop -> runOnUiThread { stopPlayback() }
            is ControlMessage.SetVolume -> runOnUiThread { mediaPlayer.setVolume(msg.volume) }
            is ControlMessage.StartScreenCast -> runOnUiThread { requestScreenCapture() }
            else -> {}
        }
    }

    // ═══════════════════════════════════════════════════
    //  SINK MODE — discover → pair → browse files → play
    // ═══════════════════════════════════════════════════

    private fun updateSinkUi() {
        if (!isPaired) {
            // Show discovery
            if (discoveredDevices.isEmpty()) {
                noDevicesText.visibility = View.VISIBLE
                deviceRecycler.visibility = View.GONE
            } else {
                noDevicesText.visibility = View.GONE
                deviceRecycler.visibility = View.VISIBLE
            }
            pairingPanel.visibility = View.GONE
            noFilesText.visibility = View.GONE
            fileRecycler.visibility = View.GONE
        }
        // When paired, the file list or player is shown by other methods
    }

    private fun startSinkDiscovery() {
        discoveredDevices = emptyList()
        updateSinkUi()
        statusText.text = "Discovering devices…"

        nsdHelper.startDiscovery(
            onServiceFound = { serviceInfo ->
                nsdHelper.resolveService(serviceInfo) { resolved ->
                    val host = resolved.host?.hostAddress ?: return@resolveService

                    val attrs = resolved.attributes
                    val model = attrs?.get("model")?.let { String(it) } ?: ""
                    val mode = attrs?.get("mode")?.let { String(it) } ?: "sink"
                    val displayName = buildString {
                        append(resolved.serviceName.removePrefix("Meracast-"))
                        if (model.isNotEmpty() && !resolved.serviceName.contains(model)) {
                            append(" ($model)")
                        }
                    }

                    val device = WfdDevice(
                        deviceAddress = host,
                        deviceName = displayName,
                        primaryDeviceType = if (mode == "source") 0 else 1,
                        isAvailable = true,
                        groupOwner = true,
                        groupOwnerAddress = host,
                        wifiP2pDevice = null
                    )
                    runOnUiThread {
                        val exists = discoveredDevices.any { it.deviceAddress == host }
                        if (!exists) {
                            discoveredDevices = discoveredDevices + device
                            updateSinkUi()
                            updateDeviceList()
                        }
                    }
                }
            }
        )
    }

    private fun updateDeviceList() {
        if (discoveredDevices.isEmpty()) {
            noDevicesText.visibility = View.VISIBLE
            deviceRecycler.visibility = View.GONE
        } else {
            noDevicesText.visibility = View.GONE
            deviceRecycler.visibility = View.VISIBLE
            deviceRecycler.adapter = SinkListAdapter(discoveredDevices) { device ->
                connectToDevice(device)
            }
        }
    }

    private fun connectToDevice(device: WfdDevice) {
        connectedDevice = device
        val host = device.groupOwnerAddress ?: device.deviceAddress
        statusText.text = "Pairing with ${device.deviceName}…"
        DebugLogStore.log("MEDIA: Pairing with source at $host")

        controlClient = ControlSocket.ControlClient(
            host = host,
            onMessage = { msg -> handleSinkMessage(msg) },
            onConnected = { peerName ->
                showPaired(peerName)
                // Hide device discovery, show file browsing
                deviceRecycler.visibility = View.GONE
                noDevicesText.visibility = View.GONE
                statusText.text = "Paired with $peerName — browsing files…"
                DebugLogStore.log("MEDIA: Paired with source \"$peerName\"")

                // Request file list
                controlClient?.send(ControlMessage.ListFiles("/"))
            },
            onDisconnected = {
                runOnUiThread {
                    isPaired = false
                    pairingPanel.visibility = View.GONE
                    fileRecycler.visibility = View.GONE
                    statusText.text = "Disconnected"
                    // Re-show device discovery
                    startSinkDiscovery()
                }
            }
        )
        controlClient?.connect()
        deviceRecycler.visibility = View.GONE
        noDevicesText.visibility = View.GONE
    }

    private fun handleSinkMessage(msg: ControlMessage) {
        when (msg) {
            is ControlMessage.FileList -> {
                runOnUiThread {
                    remoteFiles = msg.files
                    if (msg.files.isEmpty()) {
                        noFilesText.visibility = View.VISIBLE
                        noFilesText.text = "No media files on source"
                        fileRecycler.visibility = View.GONE
                        statusText.text = "Paired with $pairedDeviceName — no files found"
                    } else {
                        noFilesText.visibility = View.GONE
                        fileRecycler.visibility = View.VISIBLE
                        fileRecycler.adapter = FileListAdapter(msg.files) { entry ->
                            // User tapped a file → send Play
                            DebugLogStore.log("MEDIA: Requesting play of \"${entry.name}\"")
                            controlClient?.send(ControlMessage.Play(entry.id, 0.0))
                            statusText.text = "Requesting play of \"${entry.name}\"…"
                        }
                        statusText.text = "Paired with $pairedDeviceName — ${msg.files.size} file(s)"
                        isBrowsingRemoteFiles = true
                    }
                }
            }
            is ControlMessage.MediaInfo -> {
                currentServingUrl = msg.httpUrl
                currentMediaId = msg.mediaId
                DebugLogStore.log("MEDIA: Received media info — ${msg.httpUrl}")
            }
            is ControlMessage.Playing -> {
                runOnUiThread {
                    showPlayer()
                    statusText.text = getString(R.string.media_now_playing)
                    if (surfaceView.holder.surface.isValid && currentServingUrl != null) {
                        startExoPlayer(surfaceView.holder.surface, msg.position)
                    }
                }
            }
            is ControlMessage.PositionUpdate -> {
                runOnUiThread {
                    val posMs = (msg.position * 1000).toInt()
                    val durMs = (msg.duration * 1000).toInt()
                    if (durMs > 0 && !isSeekbarTracking) {
                        seekBar.progress = (posMs * 1000 / durMs).coerceIn(0, 1000)
                    }
                    positionText.text = formatTime(msg.position)
                    durationText.text = formatTime(msg.duration)
                    bufferText.text = "${msg.bufferedPercent}% buffered"
                }
            }
            is ControlMessage.ScreenCastStarted -> {
                val sourceIp = connectedDevice?.let { it.groupOwnerAddress ?: it.deviceAddress } ?: ""
                DebugLogStore.log("CAST: Source confirmed cast on port ${msg.port}")
                runOnUiThread { startReceiveCast(sourceIp, msg.port) }
            }
            else -> {}
        }
    }

    // ═══════════════════════════════════════════════════
    //  Shared: media file helpers
    // ═══════════════════════════════════════════════════

    private fun buildContentUri(file: ControlMessage.FileEntry): Uri {
        return when {
            file.mimeType.startsWith("video/") ->
                Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, file.id)
            file.mimeType.startsWith("audio/") -> {
                val realId = file.id.removePrefix("a_")
                Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, realId)
            }
            else -> Uri.fromFile(java.io.File(file.path))
        }
    }

    private fun startExoPlayer(surface: android.view.Surface, startPosition: Double = 0.0) {
        val url = currentServingUrl ?: return
        val id = currentMediaId ?: return
        mediaPlayer.play(id, url, surface, startPosition)
        isPlaying = true
        btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
        statusText.text = getString(R.string.media_now_playing)
    }

    private fun stopPlayback() {
        mediaPlayer.stop()
        isPlaying = false
        hidePlayer()
        statusText.text = "Stopped"
    }

    private fun hasMediaReadPermission(): Boolean {
        return when {
            Build.VERSION.SDK_INT >= 33 -> {
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) ==
                        PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
            }
            Build.VERSION.SDK_INT >= 30 -> {
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                        PackageManager.PERMISSION_GRANTED
            }
            else -> true
        }
    }

    private fun requestMediaReadPermission() {
        val permissions = when {
            Build.VERSION.SDK_INT >= 33 -> arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
            )
            Build.VERSION.SDK_INT >= 30 -> arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
            )
            else -> return
        }
        mediaPermissionLauncher.launch(permissions)
    }

    // ═══════════════════════════════════════════════════
    //  Screen casting
    // ═══════════════════════════════════════════════════

    private fun requestScreenCapture() {
        pendingScreenCapture = true
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mgr.createScreenCaptureIntent())
    }

    private fun requestReceiveCast() {
        val host = connectedDevice?.let { it.groupOwnerAddress ?: it.deviceAddress } ?: return
        DebugLogStore.log("CAST: Requesting screen cast from $host")
        controlClient?.send(ControlMessage.StartScreenCast(19000))
        statusText.text = "Waiting for source to start casting…"
    }

    private fun startScreenCast(projection: MediaProjection) {
        val sinkIp = castSinkIp ?: connectedDevice?.let {
            it.groupOwnerAddress ?: it.deviceAddress
        } ?: run {
            DebugLogStore.log("CAST: No sink IP to cast to")
            statusText.text = "Select a sink device first"
            return
        }

        castInProgress = true
        statusText.text = getString(R.string.media_cast_active)
        DebugLogStore.log("CAST: Starting screen cast to $sinkIp")

        screenStreamer.initialize()
        screenStreamer.setMediaProjection(projection)
        screenStreamer.startCasting(sinkIp)

        showPlayer()
        videoRenderer.initialize(surfaceView.holder.surface)
        audioPlayer.initialize()

        rtpReceiver.start(19000)
        rtpReceiver.onVideoNal = { nal, keyFrame, pts ->
            videoRenderer.feedNalUnit(nal, keyFrame, pts)
        }
        rtpReceiver.onAudioFrame = { frame, pts ->
            audioPlayer.feedAacFrame(frame, pts)
        }

        scope.launch {
            while (castInProgress) {
                rtpReceiver.poll()
                delay(10)
            }
        }

        controlClient?.send(ControlMessage.ScreenCastStarted(19000))
        findViewById<Button>(R.id.btn_receive_cast).text = getString(R.string.media_cast_stop)
        findViewById<Button>(R.id.btn_receive_cast).setOnClickListener { stopReceiveCast() }
    }

    private fun stopScreenCast() {
        castInProgress = false
        screenStreamer.stopCasting()
        rtpReceiver.stop()
        videoRenderer.stopDecoding()
        audioPlayer.stopPlayback()
        hidePlayer()
        statusText.text = getString(R.string.status_idle)
        findViewById<Button>(R.id.btn_receive_cast).text = getString(R.string.media_cast_receive)
        findViewById<Button>(R.id.btn_receive_cast).setOnClickListener { requestReceiveCast() }
    }

    private fun startReceiveCast(sourceIp: String, port: Int = 19000) {
        castInProgress = true
        statusText.text = "Receiving cast…"
        showPlayer()
        videoRenderer.initialize(surfaceView.holder.surface)
        audioPlayer.initialize()
        rtpReceiver.start(port)
        rtpReceiver.onVideoNal = { nal, keyFrame, pts ->
            videoRenderer.feedNalUnit(nal, keyFrame, pts)
        }
        rtpReceiver.onAudioFrame = { frame, pts ->
            audioPlayer.feedAacFrame(frame, pts)
        }
        scope.launch {
            while (castInProgress) {
                rtpReceiver.poll()
                delay(10)
            }
        }
        findViewById<Button>(R.id.btn_receive_cast).text = getString(R.string.media_cast_stop)
        findViewById<Button>(R.id.btn_receive_cast).setOnClickListener { stopReceiveCast() }
    }

    private fun stopReceiveCast() {
        castInProgress = false
        rtpReceiver.stop()
        videoRenderer.stopDecoding()
        audioPlayer.stopPlayback()
        hidePlayer()
        statusText.text = getString(R.string.status_idle)
        findViewById<Button>(R.id.btn_receive_cast).text = getString(R.string.media_cast_receive)
        findViewById<Button>(R.id.btn_receive_cast).setOnClickListener { requestReceiveCast() }
    }

    // ── Helpers ──────────────────────────────────

    private fun getWifiIp(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val netif = interfaces.nextElement()
                if (!netif.isUp || netif.isLoopback) continue
                val addrs = netif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        if (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")) {
                            return host
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun formatTime(seconds: Double): String {
        val total = seconds.toInt().coerceAtLeast(0)
        val mins = total / 60
        val secs = total % 60
        return "%d:%02d".format(mins, secs)
    }

    /** Stop all ongoing operations (broadcasting, client, playback). */
    private fun stopCurrentOperation() {
        if (isBroadcasting) stopBroadcasting()
        controlClient?.disconnect()
        controlClient = null
        stopPlayback()
        stopScreenCast()
        stopReceiveCast()
        nsdHelper.stopDiscovery()
        nsdHelper.unregister()
        mediaHttpServer.stop()
        connectedDevice = null
        hideAllContent()
    }

    override fun onDestroy() {
        stopCurrentOperation()
        screenStreamer.release()
        videoRenderer.release()
        audioPlayer.stopPlayback()
        mediaPlayer.release()
        scope.cancel()
        backgroundScope.cancel()
        DebugLogStore.log("MEDIA: Activity destroyed")
        super.onDestroy()
    }
}

// ── File list adapter (shared for local + remote files) ──

class FileListAdapter(
    private val files: List<ControlMessage.FileEntry>,
    private val onClick: (ControlMessage.FileEntry) -> Unit
) : RecyclerView.Adapter<FileListAdapter.ViewHolder>() {

    class ViewHolder(val view: View) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(h: ViewHolder, i: Int) {
        val f = files[i]
        h.view.findViewById<TextView>(android.R.id.text1).text = f.name
        val detail = buildString {
            append(if (f.mimeType.startsWith("video/")) "🎬 " else "🎵 ")
            append(formatFileSize(f.size))
            if (f.duration > 0) append(" • ${formatDuration(f.duration)}")
        }
        h.view.findViewById<TextView>(android.R.id.text2).text = detail
        h.view.setOnClickListener { onClick(f) }
    }

    override fun getItemCount() = files.size

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
            else -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    private fun formatDuration(seconds: Double): String {
        val total = seconds.toInt()
        val m = total / 60
        val s = total % 60
        return "$m:${"%02d".format(s)}"
    }
}

// ── Sink list adapter ──

class SinkListAdapter(
    private val devices: List<WfdDevice>,
    private val onClick: (WfdDevice) -> Unit
) : RecyclerView.Adapter<SinkListAdapter.ViewHolder>() {

    class ViewHolder(val view: View) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(h: ViewHolder, i: Int) {
        val d = devices[i]
        h.view.findViewById<TextView>(R.id.device_name).text = d.deviceName
        val isSource = d.primaryDeviceType == 0
        h.view.findViewById<TextView>(R.id.device_type).text =
            if (isSource) "📤 Source" else "📺 Sink"
        h.view.findViewById<com.google.android.material.button.MaterialButton>(R.id.connect_button)
            .setOnClickListener { onClick(d) }
    }

    override fun getItemCount() = devices.size
}
