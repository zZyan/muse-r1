package com.zzyan.r1.muse.core

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.TextView
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import com.zzyan.r1.muse.hardware.SideButtonService
import com.zzyan.r1.muse.pairing.PairingServer
import com.zzyan.r1.muse.transport.MuseConnection
import kotlinx.coroutines.*

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val store get() = (application as MuseApp).store
    private var pairing: PairingServer? = null
    private var connection: MuseConnection? = null
    private var recorder: VoiceRecorder? = null
    private var sideButtonRecording = false
    private var cappedVoiceNote: ByteArray? = null
    private var connected = false
    private var recording = false
    private var sending = false
    private var pairingRequested = false
    private var pairingWindow: Job? = null
    private var turnTimeout: Job? = null
    private var transcriptFetch: Job? = null
    private var controls: AlertDialog? = null
    private var attachMenu: AlertDialog? = null
    private var clearDialog: AlertDialog? = null
    private var transcriptPending = false
    private val history = mutableListOf<ConversationTurn>()
    private var activeTurn: ConversationTurn? = null
    private var historyLoaded = false
    private var historyLoad: Job? = null
    private val hasConversation get() = history.isNotEmpty()
    private val historyStore get() = (application as MuseApp).displayHistory
    private var serverHistoryFetched = false
    private val chatThreadImported = mutableSetOf<String>()
    private val sendQueue = ArrayDeque<Triple<ByteArray, String?, List<JSONObject>>>()
    private val textQueue = ArrayDeque<String>()
    private val sideChats by lazy { SideChats(this) }
    private var currentSession: String? = null
    private var pendingItems = mutableListOf<JSONObject>()
    private var pendingItemsLabel = ""
    private val shake = ShakeDetector()
    private val sensors by lazy { getSystemService(SensorManager::class.java) }
    private val shakeListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!historyLoaded || !hasConversation || recording || sending ||
                clearDialog?.isShowing == true || controls?.isShowing == true) {
                shake.reset()
                return
            }
            if (shake.sample(event.timestamp / 1_000_000, event.values[0], event.values[1], event.values[2]))
                confirmClearHistory()
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
    private var turn = 0L
    private lateinit var speech: SpeechOutput
    private lateinit var screen: MuseScreen
    private lateinit var status: TextView
    private lateinit var message: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        screen = MuseScreen(this, ::beginRecording, ::finishRecording, ::showControls, ::confirmClearHistory,
            ::showSideChatList, ::showAttachMenu, ::sendTextMessage)
        status = screen.status
        message = screen.message
        setContentView(screen)
        enterFullscreen()
        speech = SpeechOutput(this) { state ->
            if (recording || sending) return@SpeechOutput
            updateStatus(state)
            if (state != "MUSE IS SPEAKING" && !recording && !sending)
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun loadHistory() {
        if (historyLoaded || historyLoad?.isActive == true) return
        historyLoad = scope.launch {
            try {
                history.addAll(loadVisibleHistory())
                historyLoaded = true
                if (hasConversation) { renderConversation(); screen.jumpToLatest() }
                screen.setHistoryState(hasConversation, !recording && !sending)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                updateStatus("HISTORY UNAVAILABLE")
                screen.showNotice("Couldn't open display history. Reopen Muse to try again.")
            }
        }
    }

    private suspend fun loadVisibleHistory(): List<ConversationTurn> {
        val session = currentSession ?: return historyStore.load()
        return sideChats.load(session)
    }

    private fun saveVisibleHistory() {
        val session = currentSession
        if (session == null) historyStore.save(history) else sideChats.save(session, history)
    }

    private fun showSideChatList() {
        screen.showSideChats(currentSession, sideChats.list(), sideChats.unreadMain(),
            ::switchSession, { switchSession(sideChats.create()) }, ::openAndroidSettings)
    }

    private fun openAndroidSettings() {
        startActivity(Intent(Settings.ACTION_SETTINGS))
    }

    /** Text composer send: non-blocking like voice; rides the same queue. */
    private fun sendTextMessage(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        if (!connected || connection == null) {
            screen.showNotice(if (store.credentials() == null) "Pair this r1 with Muse on your phone first."
                else "Not connected.\nHold the empty background to reconnect.")
            return
        }
        if (sending || recording) {
            synchronized(textQueue) { textQueue.add(text) }
            screen.showNotice("Queued — sends when Muse finishes this reply.")
            return
        }
        dispatchText(text)
    }

    private fun dispatchText(text: String) {
        startTurn(user = text)
        val current = connection
        val thisTurn = turn
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val items = synchronized(pendingItems) { pendingItems.toList() }
                    checkNotNull(current).sendText(text, currentSession, items)
                }
                synchronized(pendingItems) { pendingItems.clear(); pendingItemsLabel = "" }
            } catch (_: Exception) {
                if (connection === current) { sending = false; window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); updateStatus("SEND FAILED"); screen.showNotice("Couldn't send.") }
            }
        }
        turnTimeout?.cancel()
        turnTimeout = scope.launch { delay(300_000); if (sending) { sending = false; disconnect(); updateStatus("REPLY TIMED OUT") } }
    }

    private fun switchSession(session: String?) {
        if (session == currentSession) return
        transcriptFetch?.cancel(); transcriptPending = false
        if (recording) finishRecording(false)
        sending = false; turnTimeout?.cancel(); activeTurn = null
        if (session == null) screen.setUnread(sideChats.totalUnread())
        currentSession?.let { sideChats.save(it, history.toList()) }
        currentSession = session
        if (session == null) sideChats.markMainRead()
        if (session != null) {
            sideChats.markRead(session)
            connection?.let { current ->
                scope.launch { try { withContext(Dispatchers.IO) { current.subscribeSession(session) } } catch (_: Exception) { } }
            }
        }
        screen.setUnread(sideChats.totalUnread())
        history.clear()
        historyLoaded = false
        scope.launch {
            try {
                val turns = loadVisibleHistory()
                history.addAll(turns)
                historyLoaded = true
                if (hasConversation) { renderConversation(); screen.jumpToLatest() } else screen.showIdle()
            } catch (_: Exception) {
                historyLoaded = true
                screen.showIdle()
            }
        }
    }

    private fun showAttachMenu() {
        if (recording || sending || controls?.isShowing == true || clearDialog?.isShowing == true) return
        attachMenu = AlertDialog.Builder(this)
            .setTitle("Attachment")
            .setItems(arrayOf("Take photo", "Choose file")) { _, item ->
                when (item) {
                    0 -> {
                        val intent = Intent(this, com.zzyan.r1.muse.camera.CameraActivity::class.java)
                            .putExtra("for_muse", true)
                        startActivityForResult(intent, 7101)
                    }
                    else -> startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                        }, 7102)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            7101, 7102 -> {
                val photo = requestCode == 7101
                val bytes: ByteArray
                val mime: String
                val name: String
                if (photo) {
                    val path = data?.getStringExtra("photo_path")?.takeIf { it.isNotBlank() }
                        ?: data?.getStringExtra("photo_uri")?.takeIf { it.isNotBlank() } ?: return
                    val source = java.io.File(path)
                    if (!source.exists()) return
                    var loaded = source.readBytes()
                    if (loaded.size > 2_000_000) {
                        val bitmap = BitmapFactory.decodeByteArray(loaded, 0, loaded.size)
                        if (bitmap != null) {
                            var scale = 0.7f
                            while (scale >= 0.25f) {
                                val scaled = Bitmap.createScaledBitmap(bitmap,
                                    (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
                                val buffer = java.io.ByteArrayOutputStream()
                                scaled.compress(Bitmap.CompressFormat.JPEG, 85, buffer)
                                if (buffer.size() <= 2_000_000) { loaded = buffer.toByteArray(); break }
                                buffer.reset(); scale -= 0.15f
                            }
                            bitmap.recycle()
                        }
                    }
                    if (loaded.size > 2_000_000) {
                        screen.showNotice("Attachment stays over the 2 MB Muse limit. Pick a smaller file.")
                        return
                    }
                    source.delete()
                    bytes = loaded; mime = "image/jpeg"; name = System.currentTimeMillis().toString() + ".jpg"
                } else {
                    val uri = data?.data ?: return
                    bytes = try { contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    } catch (e: Exception) { screen.showNotice("Couldn't read that file."); return } ?: return
                    if (bytes.size > 2_000_000) {
                        screen.showNotice("Attachment is over the 2 MB Muse limit. Pick a smaller file.")
                        return
                    }
                    mime = contentResolver.getType(uri) ?: "application/octet-stream"; name = "attachment"
                }
                addToPendingItems(bytes, mime, name)
            }
        }
    }

    private fun addToPendingItems(bytes: ByteArray, mime: String, suggestedName: String? = null) {
        val name = suggestedName ?: "attachment"
        val item = JSONObject().put("type", "file").put("mime_type", mime).put("filename", name)
            .put("data_base64", java.util.Base64.getEncoder().encodeToString(bytes))
        synchronized(pendingItems) { pendingItems.add(item); pendingItemsLabel = "photo" }
        screen.showAttachHint()
        updateStatus("ATTACHMENT READY")
    }

    private fun enterFullscreen() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.apply {
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterFullscreen()
    }

    /** Assistant/launcher re-deliveries (long-hold power while Muse is already foreground) must be a no-op. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Absorb silently: no recreate, no re-entry animation handling, no state change.
    }

    private fun updateStatus(value: String, force: Boolean = false) {
        screen.setState(if (force) value else when {
            currentSession != null && value in setOf("READY", "REPLY RECEIVED") -> "SIDE CHAT ACTIVE"
            else -> value
        })
        screen.setHistoryState(hasConversation, historyLoaded && !recording && !sending)
    }

    private fun confirmClearHistory() {
        if (!historyLoaded || !hasConversation || recording || sending ||
            controls?.isShowing == true || clearDialog?.isShowing == true) return
        clearDialog = AlertDialog.Builder(this)
            .setTitle("Clear display history?")
            .setMessage("This clears only this r1's display history. Your conversation remains in Meta Muse, and Muse still remembers earlier turns.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                if (recording || sending) return@setPositiveButton
                speech.stop()
                transcriptFetch?.cancel()
                transcriptPending = false
                turn++
                activeTurn = null
                history.clear()
                saveVisibleHistory()
                screen.message.text = ""
                screen.showIdle()
                screen.setHistoryState(false, false)
            }
            .show()
    }

    private fun showControls() {
        if (recording || controls?.isShowing == true || clearDialog?.isShowing == true) return
        val paired = store.credentials() != null
        controls = AlertDialog.Builder(this)
            .setTitle("Device controls")
            .setItems(arrayOf(if (paired) "Reconnect to Muse" else "Pair with Muse", "Android settings",
                "Return to idle", "Show conversation", "Side button settings")) { _, item ->
                when (item) {
                    0 -> if (paired) { disconnect(); connect() } else startPairing()
                    1 -> startActivity(Intent(Settings.ACTION_SETTINGS))
                    2 -> screen.showIdle()
                    3 -> if (hasConversation) renderConversation()
                    4 -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
            .setNegativeButton("Back to Muse", null)
            .show()
    }

    private fun renderConversation() {
        screen.showConversation(history, transcriptPending)
        screen.setHistoryState(hasConversation, historyLoaded && !recording && !sending)
    }

    private fun receiveReply(id: String, text: String, done: Boolean) {
        val current = activeTurn ?: return
        current.replies[id] = if (done && text.isNotEmpty()) text else current.replies.getOrDefault(id, "") + text
        saveVisibleHistory()
        renderConversation()
        if (done) {
            sending = false; turnTimeout?.cancel(); updateStatus("REPLY RECEIVED")
            speech.speak(current.replies.getValue(id))
            drainSendQueue()
        }
    }

    private fun receiveTranscript(text: String) {
        val current = activeTurn ?: return
        current.user = text
        transcriptPending = false
        transcriptFetch?.cancel()
        saveVisibleHistory()
        renderConversation()
    }

    override fun onResume() {
        super.onResume()
        foreground = this
        loadHistory()
        shake.reset()
        sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensors.registerListener(shakeListener, it, SensorManager.SENSOR_DELAY_UI)
        }
        try {
            store.importPendingToken()
            if (store.credentials() == null) {
                updateStatus("NOT PAIRED")
                if (hasConversation) renderConversation()
                else screen.showNotice(if (store.sdkToken() == null) "SDK token needed.\nThen pair with Muse on your phone."
                    else "Hold the empty background, choose Pair with Muse, then add a gadget on your phone.")
                if (pairingRequested && getSystemService(android.bluetooth.BluetoothManager::class.java).adapter.isEnabled) startPairing()
            } else if (connection == null) connect()
        } catch (_: Exception) { updateStatus("SECURE STORAGE ERROR"); screen.showNotice("Couldn't open the device credentials.") }
    }

    private fun startPairing() {
        if (store.sdkToken() == null) { screen.showNotice("Install your SDK token securely over USB first."); return }
        if (store.credentials() != null) { connect(); return }
        pairingRequested = true
        val needed = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) { requestPermissions(needed.toTypedArray(), 2); return }
        if (!getSystemService(android.bluetooth.BluetoothManager::class.java).adapter.isEnabled) {
            updateStatus("TURN ON BLUETOOTH"); startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); return
        }
        pairingWindow?.cancel()
        pairing?.close()
        pairing = PairingServer(this, store.identity, store::sdkToken, { credentials ->
            try { store.save(credentials); runOnUiThread { connect() }; true } catch (_: Exception) { false }
        }, { value -> runOnUiThread { updateStatus(value.replace('_', ' ').uppercase()) } })
        if (pairing!!.start()) {
            pairingRequested = false
            screen.showNotice("In Muse on your phone, add a gadget.\n\nChoose ${store.identity.bleName}.\n\nThis r1 uses its current Wi-Fi connection.")
            pairingWindow = scope.launch { delay(120_000); if (store.credentials() == null) { pairing?.close(); pairing = null; updateStatus("PAIRING CLOSED") } }
        }
    }

    private fun connect() {
        if (connection != null) return
        val credentials = store.credentials() ?: return
        lateinit var current: MuseConnection
        current = MuseConnection(credentials, store.sdkToken(), store::save,
            { value -> runOnUiThread {
                if (connection !== current) return@runOnUiThread
                updateStatus(value.uppercase())
                if (value == "Disconnected" || value == "Connection lost") {
                    connected = false
                    transcriptFetch?.cancel()
                    transcriptPending = false
                    if (hasConversation && !recording) renderConversation()
                }
            } },
            { id, text, done -> runOnUiThread {
                if (connection !== current || recording) return@runOnUiThread
                receiveReply(id, text, done)
            } },
            { text -> runOnUiThread {
                if (connection !== current || recording) return@runOnUiThread
                receiveTranscript(text)
            } },
            { value -> runOnUiThread {
                if (connection !== current || recording) return@runOnUiThread
                if (value.isNotBlank()) updateStatus(value.uppercase()) else updateStatus("READY")
            } },
            { sessionId, isMain -> runOnUiThread {
                if (isMain) { if (currentSession != null) sideChats.observeMain() }
                else sideChats.observe(sessionId, currentSession)
                screen.setUnread(sideChats.totalUnread())
            } })
        connection = current
        scope.launch {
            try {
                withContext(Dispatchers.IO) { current.connect() }
                if (connection !== current) return@launch
                connected = true
                updateStatus("READY")
                current.mainSession()?.let { sideChats.promoteMain(it) }
                if (hasConversation) renderConversation() else screen.showIdle()
                fetchServerHistoryOnce(current)
            } catch (_: Exception) {
                if (connection === current) { connection = null; connected = false; updateStatus("CONNECTION FAILED"); screen.showNotice("Couldn't connect to Muse.\n\nHold the empty background to check Wi-Fi or reconnect.") }
            }
        }
    }

    /** Main-chat history from Meta Muse replaces the local display list when it shows more turns. */
    private fun fetchServerHistoryOnce(current: MuseConnection) {
        if (serverHistoryFetched || currentSession != null) return
        serverHistoryFetched = true
        scope.launch {
            try { withContext(Dispatchers.IO) { current.probeThreadsEndpoints() } } catch (_: Exception) { }
            try {
                val turns = withContext(Dispatchers.IO) { current.fetchRemoteHistory() }
                if (connection !== current || currentSession != null || recording) return@launch
                current.mainSession()?.let { sideChats.promoteMain(it); runOnUiThread { screen.setUnread(sideChats.totalUnread()) } }
                val mapped = turns.map { (user, reply) ->
                    ConversationTurn(user.takeIf { it.isNotBlank() }, linkedMapOf("assistant" to reply))
                }.ifEmpty { return@launch }
                if (mapped.size > history.size) {
                    history.clear(); history.addAll(mapped); saveVisibleHistory()
                    renderConversation(); screen.jumpToLatest()
                }
                // Pull the official app's thread inventory (root) and backfill every one of them.
                val inventory = withContext(Dispatchers.IO) { AuraBridge.syncThreadIds(this@MainActivity) }
                val known = sideChats.list().map { it.id }.toMutableSet()
                inventory.forEach { thread ->
                    val fresh = chatThreadImported.add(thread.sessionId) && thread.sessionId !in known
                    val backlog = try {
                        withContext(Dispatchers.IO) { current.fetchChatHistory(thread.sessionId, 10) }
                    } catch (_: Exception) { emptyList() }
                    if (backlog.isNotEmpty() && sideChats.load(thread.sessionId).isEmpty()) {
                        val title = backlog.firstNotNullOfOrNull { (user, _) ->
                            user.takeIf { it.isNotBlank() }?.take(26)?.trim()
                        } ?: "Side chat"
                        sideChats.rename(thread.sessionId, title)
                        sideChats.save(thread.sessionId, backlog.mapNotNull { (user, reply) ->
                            if (user.isBlank() && reply.isBlank()) null
                            else ConversationTurn(user.takeIf { it.isNotBlank() }, linkedMapOf("assistant" to reply))
                        })
                        if (currentSession != null && thread.sessionId != currentSession) sideChats.observe(thread.sessionId, currentSession)
                    } else if (fresh && backlog.isEmpty()) {
                        sideChats.rename(thread.sessionId, "Side chat")
                    }
                    screen.setUnread(sideChats.totalUnread())
                }
            } catch (_: Exception) { /* History 403s or timeouts keep the local display. */ }
        }
    }

    private fun beginRecording() {
        if (!historyLoaded || recording || controls?.isShowing == true || clearDialog?.isShowing == true) return
        if (!connected) {
            screen.showNotice(if (store.credentials() == null) "Pair this r1 with Muse on your phone first."
                else "Not connected.\nHold the empty background to reconnect.")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return
        }
        speech.stop()
        lateinit var capture: VoiceRecorder
        capture = VoiceRecorder({ runOnUiThread {
            if (recorder === capture) recordingLimitReached()
        } }, { runOnUiThread {
            if (recorder === capture) { finishRecording(false); updateStatus("MICROPHONE ERROR") }
        } })
        recorder = capture
        try {
            capture.start(); recording = true
            transcriptFetch?.cancel()
            transcriptPending = false
            turn++
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            updateStatus("LISTENING")
            screen.showRecording(history)
        } catch (_: Exception) { recorder = null; updateStatus("MICROPHONE UNAVAILABLE") }
    }

    internal fun sideButtonNotice(text: String) { screen.showNotice(text) }

    internal val hasPlayback get() = speech.hasPlayback

    internal fun beginSideButtonRecording(): Boolean {
        when {
            !hasWindowFocus() || controls?.isShowing == true || clearDialog?.isShowing == true ->
                sideButtonNotice("Close the dialog, then hold the side button to talk.")
            !historyLoaded -> sideButtonNotice("Display history is still loading. Try holding again in a moment.")
            recording -> sideButtonNotice("A recording is already in progress.")
            else -> { beginRecording(); sideButtonRecording = recording; return recording }
        }
        return false
    }

    internal fun finishSideButtonRecording(send: Boolean) { finishRecording(send) }

    internal fun adjustMediaVolume(steps: Int) {
        val audio = getSystemService(AudioManager::class.java)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val min = audio.getStreamMinVolume(AudioManager.STREAM_MUSIC)
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (current.toLong() + steps).coerceIn(min.toLong(), max.toLong()).toInt(), 0)
        screen.showVolume(audio.getStreamVolume(AudioManager.STREAM_MUSIC), max)
    }

    internal fun prepareForLock() {
        finishRecording(false)
        speech.stop()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun recordingLimitReached() {
        if (!sideButtonRecording) { finishRecording(true); return }
        // Stop the microphone, but keep the capped audio cancellable until button release.
        cappedVoiceNote = recorder?.finish(); recorder = null
        updateStatus("RELEASE TO SEND")
        screen.showNotice("20-second limit reached.\n\nRelease to send.\nTurn the wheel to discard and adjust volume.")
    }

    private fun finishRecording(send: Boolean) {
        if (!recording) return
        recording = false
        sideButtonRecording = false
        val wav = cappedVoiceNote ?: recorder?.finish()
        cappedVoiceNote = null; recorder = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!send || wav == null) {
            if (hasConversation) renderConversation() else screen.showIdle()
            updateStatus(if (send) "HOLD A LITTLE LONGER" else "READY")
            return
        }
        val queued = sending
        screen.markVoiceUsed()
        if (queued) {
            synchronized(pendingItems) {
                sendQueue.add(Triple(wav, currentSession, pendingItems.toList()))
                pendingItems.clear(); pendingItemsLabel = ""
            }
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            updateStatus("QUEUED")
            screen.showNotice("Queued — sends when Muse finishes this reply.")
            return
        }
        dispatchVoice(wav)
    }

    private fun dispatchVoice(wav: ByteArray) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        startVoiceTurn()
        val current = connection
        val thisTurn = turn
        scope.launch {
            try {
                val userId = withContext(Dispatchers.IO) {
                    val items = synchronized(pendingItems) { pendingItems.toList() }
                    checkNotNull(current).sendVoice(wav, currentSession, items)
                }
                synchronized(pendingItems) { pendingItems.clear(); pendingItemsLabel = "" }
                if (connection === current && turn == thisTurn && transcriptPending) fetchTranscript(current!!, userId, thisTurn)
            }
            catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                if (connection === current && turn == thisTurn) { sending = false; transcriptPending = false; window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); updateStatus("SEND FAILED"); screen.showNotice("Couldn't send. Hold the empty background to reconnect.") }
            }
        }
        turnTimeout?.cancel()
        turnTimeout = scope.launch { delay(300_000); if (sending) { sending = false; disconnect(); updateStatus("REPLY TIMED OUT") } }
    }

    private fun drainSendQueue() {
        val next = synchronized(sendQueue) { sendQueue.removeFirstOrNull() } ?: return
        scope.launch { delay(1_200) }
        scope.launch {
            next.third.let { synchronized(pendingItems) { pendingItems.clear(); pendingItems.addAll(it) } }
            dispatchVoice(next.first)
        }
    }

    private fun startVoiceTurn() = startTurn(user = null)

    private fun startTurn(user: String?) {
        val turn = ConversationTurn(user = user).also { history.add(it) }
        activeTurn = turn
        // Persist image attachments so the conversation renders thumbnails.
        val items = synchronized(pendingItems) { pendingItems.toList() }
        items.forEachIndexed { index, item ->
            if (item.optString("mime_type").startsWith("image/")) {
                try {
                    val bytes = java.util.Base64.getDecoder().decode(item.optString("data_base64"))
                    val file = java.io.File(noBackupFilesDir(), "att-${turnIndex()}-$index.jpg")
                    file.writeBytes(bytes)
                    turn.attachments.add(file.absolutePath)
                } catch (_: Exception) { }
            }
        }
        sending = true; transcriptPending = true
        saveVisibleHistory()
        screen.jumpToLatest()
        renderConversation(); updateStatus("SENDING VOICE NOTE")
    }

    private fun noBackupFilesDir() = applicationContext.noBackupFilesDir

    private var turnCounter = 0
    private fun turnIndex(): Int { turnCounter += 1; return turnCounter }

    private fun fetchTranscript(current: MuseConnection, userId: String?, thisTurn: Long) {
        transcriptFetch = scope.launch {
            // History may be forbidden while live STT still arrives. Bound both paths together.
            withTimeoutOrNull(30_000) {
                var pollHistory = userId != null
                repeat(15) {
                    val text = if (pollHistory) {
                        try { withContext(Dispatchers.IO) { current.userTranscript(userId!!) } }
                        catch (_: Exception) {
                            currentCoroutineContext().ensureActive()
                            pollHistory = false
                            null
                        }
                    } else null
                    if (connection !== current || recording || turn != thisTurn) return@withTimeoutOrNull
                    if (!text.isNullOrBlank()) {
                        receiveTranscript(text); return@withTimeoutOrNull
                    }
                    delay(2_000)
                }
            }
            if (connection === current && !recording && turn == thisTurn && transcriptPending) {
                transcriptPending = false
                renderConversation()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The accessibility service owns this gesture globally. Never record via a second path.
        if (event.keyCode == KeyEvent.KEYCODE_PAIRING) {
            if (event.action == KeyEvent.ACTION_UP && SideButtonService.instance == null)
                sideButtonNotice("Enable Side button controls in Android accessibility settings.\n\nOpen device controls, then Side button settings. You can still hold the character to talk.")
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_UP) showControls()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun disconnect() {
        connected = false; sending = false; turnTimeout?.cancel(); transcriptFetch?.cancel()
        transcriptPending = false
        if (hasConversation && !recording) renderConversation()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); connection?.close(); connection = null
    }
    override fun onPause() {
        SideButtonService.instance?.activityPaused(this)
        screen.hideVolume()
        if (foreground === this) foreground = null
        super.onPause()
    }
    override fun onStop() {
        sensors.unregisterListener(shakeListener)
        shake.reset()
        clearDialog?.dismiss()
        controls?.dismiss(); finishRecording(false); speech.stop(); pairing?.close(); pairing = null; disconnect()
        scope.coroutineContext.cancelChildren()
        super.onStop()
    }
    override fun onDestroy() { speech.close(); scope.cancel(); super.onDestroy() }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 2 && results.all { it == PackageManager.PERMISSION_GRANTED }) startPairing()
    }

    companion object {
        internal var foreground: MainActivity? = null
            private set
    }
}
