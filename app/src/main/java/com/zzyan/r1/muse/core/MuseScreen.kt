package com.zzyan.r1.muse.core

import android.animation.ValueAnimator
import com.zzyan.r1.muse.R
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.graphics.*
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.ReplacementSpan
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.min
import kotlin.math.sin

/** The gadget's idle companion and conversation, independent of pairing and transport. */
internal class MuseScreen(
    context: Context,
    onPress: () -> Unit,
    onRelease: (Boolean) -> Unit,
    onControls: () -> Unit,
    onClearHistory: () -> Unit = {},
    onSideChats: () -> Unit = {},
    onAttach: () -> Unit = {},
    onSendText: (String) -> Unit = {},
) : FrameLayout(context) {
    private val personaName: String
        get() = preferences.getString("persona_name", null) ?: "Muse"
    private val background = Color.rgb(10, 11, 10)
    private val cream = Color.rgb(242, 233, 221)
    private val muted = Color.rgb(162, 154, 142)
    private val orange = Color.rgb(255, 139, 66)
    private val preferences = context.getSharedPreferences("gadget_ui", Context.MODE_PRIVATE)
    private val transcriptFade = Paint()
    private val latestFade = Paint()
    private var compact = 0f
    private var conversation = false
    private var followingLatest = true
    private var updatingTranscript = false
    private var applyingScroll = false
    private var transition: ValueAnimator? = null
    private val avatar = Character(context)
    private var voiceIndicator: VoiceIndicator? = null
    private val controlsGesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(event: MotionEvent) {
            if (listOf(avatar, clearHistory, latest).none {
                it.isShown && event.x in it.left.toFloat()..it.right.toFloat() &&
                    event.y in it.top.toFloat()..it.bottom.toFloat()
            }) onControls()
        }
    })
    // The state remains available to accessibility and hardware tests, but ordinary states are not labels.
    val status = text("NOT PAIRED", 11f, muted)
    val message = object : TextView(context) {
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (voiceIndicator != null && isShown) postInvalidateOnAnimation()
        }
    }.apply {
        textSize = 16f
        setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = false
        setLineSpacing(dp(3).toFloat(), 1f)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    private val bubbleColumn = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(4), dp(8), dp(4))
    }
    private val scroll: ScrollView = ScrollView(context).apply {
        isFillViewport = false
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        clipToPadding = false
        setPadding(dp(18), 0, dp(18), dp(24))
        addView(bubbleColumn, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        visibility = View.GONE
        setOnScrollChangeListener { _, _, _, _, _ ->
            if (!updatingTranscript && !applyingScroll) followingLatest = atBottom()
            updateLatestButton()
            this@MuseScreen.invalidate()
        }
        setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE)
                followingLatest = false
            if (event.actionMasked == MotionEvent.ACTION_UP && atBottom()) followingLatest = true
            false
        }
    }
    private val hint = text("Hold the side button to talk", 12f, muted).apply {
        gravity = Gravity.CENTER
        visibility = if (preferences.getBoolean("used_voice", false)) View.GONE else View.VISIBLE
    }
    private val clearHistory = IconButton(context, "Clear display history", false).apply {
        visibility = View.GONE
        setOnClickListener { onClearHistory() }
    }
    private val latest = IconButton(context, "Scroll to latest message", true).apply {
        visibility = View.GONE
        setOnClickListener { jumpToLatest() }
    }
    private val volume = VolumeOverlay(context)
    private val recordOverlay = RecordOverlay(context)
    private val bubble = BubbleButton(context, "Open side panel").apply {
        setOnClickListener { onSideChats() }
    }
    private val composer = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(-0xe8e8e9)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        gravity = Gravity.CENTER_VERTICAL
    }
    val composerInput = EditText(context).apply {
        hint = "Message"
        setHintTextColor(muted)
        setTextColor(cream)
        textSize = 15f
        background = null
        setPadding(dp(8), dp(10), dp(8), dp(10))
        inputType = android.text.InputType.TYPE_CLASS_TEXT
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
        setSingleLine(true)
        isFocusableInTouchMode = true
        setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                val value = text?.toString()?.trim().orEmpty()
                (context as? Activity)?.window?.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                (context as? Activity)?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
                if (value.isNotEmpty()) { onSendText(value); setText("") }
                true
            } else false
        }
        // Immersive fullscreen blocks the IME; drop the flag while typing.
        setOnClickListener {
            (context as? Activity)?.window?.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }
    private val composerAttach = IconButton(context, "Add attachment", downArrow = false, plus = true).apply {
        setOnClickListener { onAttach() }
    }
    private val composerVoice = ComposerVoice(context).apply {
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { onPress(); true }
                MotionEvent.ACTION_UP -> {
                    val held = event.eventTime - event.downTime >= 300
                    onRelease(held)
                    if (!held) view.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> { onRelease(false); true }
                else -> true
            }
        }
        setOnClickListener { }
    }
    private val dismissVolume = Runnable {
        volume.animate().alpha(0f).setDuration(180).withEndAction { hideVolume() }.start()
    }

    init {
        setBackgroundColor(background)
        contentDescription = "Muse. Hold the empty background for device controls."
        setOnLongClickListener { onControls(); true }
        avatar.contentDescription = "Muse. Hold to talk, then release to send. Tap for device controls."
        avatar.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { onPress(); true }
                MotionEvent.ACTION_UP -> {
                    val held = event.eventTime - event.downTime >= 300
                    onRelease(held)
                    if (!held) view.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> { onRelease(false); true }
                else -> true
            }
        }
        avatar.setOnClickListener { onControls() }
        addView(avatar)
        addView(scroll)
        addView(status)
        addView(hint)
        addView(clearHistory)
        addView(latest)
        addView(bubble)
        composer.addView(composerAttach, LinearLayout.LayoutParams(dp(60), dp(60)))
        composer.addView(composerInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(-8), 0, dp(-8), 0) })
        composer.addView(composerVoice, LinearLayout.LayoutParams(dp(60), dp(60)))
        addView(composer, LayoutParams(LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        addView(volume)
        showIdle()
    }

    fun showVolume(current: Int, max: Int) {
        volume.animate().cancel()
        volume.alpha = 1f
        volume.setLevel(current, max)
        volume.visibility = View.VISIBLE
        removeCallbacks(dismissVolume)
        postDelayed(dismissVolume, 1500)
    }

    fun hideVolume() {
        removeCallbacks(dismissVolume)
        volume.animate().cancel()
        volume.visibility = View.GONE
    }

    fun showIdle() {
        conversation = false
        voiceIndicator = null
        scroll.visibility = View.GONE
        latest.visibility = View.GONE
        hint.visibility = if (preferences.getBoolean("used_voice", false)) View.GONE else View.VISIBLE
        resizeCharacter(false)
        refreshAvatarImage()
    }

    private fun layoutButtons() {
        val pad = dp(8)
        bubble.layout(pad, pad, pad + dp(72), pad + dp(72))
        composer.layout(0, height - dp(66), width, height)
        composerAttach.layout(dp(23), dp(3), dp(83), dp(63))
        composerVoice.layout(width - dp(80), dp(3), width - dp(20), dp(63))
        composerInput.layout(dp(75), dp(1), width - dp(83), dp(65))
    }

    fun setUnread(total: Int) { bubble.unread = total }

    fun showAttachHint() = showNotice("Photo attached.\nSay something to send it with your voice note, or hold the background to discard.")

    fun showSideChats(
        current: String?, chats: List<com.zzyan.r1.muse.core.SideChats.Chat>, mainUnread: Int,
        onPick: (String?) -> Unit, onNew: () -> Unit, onSettings: () -> Unit,
        onSyncTitles: () -> Unit = {},
    ) {
        hideSideChats()
        val overlay = FrameLayout(context).apply {
            setBackgroundColor(Color.argb(130, 0, 0, 0))
            setOnClickListener { hideSideChats() }
        }
        val sheet = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(17, 17, 17))
            setOnClickListener { }
            setPadding(dp(16), dp(20), dp(16), dp(16))
        }
        // Header: persona name, like the official drawer.
        sheet.addView(text(personaName, 19f, cream).apply {
            setPadding(0, 0, 0, dp(14))
        })
        // Toggle: TABS <-> SIDE CHATS.
        val toggle = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(8))
        }
        val tabsBtn = TextView(context).apply {
            text = "TABS"
            textSize = 15f
            setTextColor(muted)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = ColorDrawable(Color.rgb(30, 30, 30))
        }
        val chatsBtn = TextView(context).apply {
            text = "SIDE CHATS"
            textSize = 15f
            setTextColor(cream)
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        toggle.addView(tabsBtn)
        toggle.addView(chatsBtn)
        sheet.addView(toggle)
        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val rowsScroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = true
        }

        fun chatRow(label: String, unread: Int, isCurrent: Boolean, action: () -> Unit) {
            rows.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(6), dp(16), dp(6), dp(16))
                gravity = Gravity.CENTER_VERTICAL
                if (isCurrent) setBackgroundColor(Color.rgb(40, 40, 40))
                setOnClickListener { hideSideChats(); action() }
                if (unread > 0) addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(12) }
                    setBackgroundColor(Color.rgb(52, 120, 246))
                })
                addView(text(label, 16f, cream).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                })
                if (unread > 0) addView(text("$unread", 13f, muted))
            })
        }

        var showingChats = true
        fun fillChats() {
            rows.removeAllViews()
            showingChats = true
            tabsBtn.setTextColor(muted); chatsBtn.setTextColor(cream)
            chatRow("Main chat", mainUnread, current == null) { onPick(null) }
            chats.forEach { chat ->
                chatRow(chat.name, chat.unread, chat.id == current) { onPick(chat.id) }
            }
            rows.addView(text("+ New side chat", 16f, muted).apply {
                setPadding(dp(6), dp(16), dp(6), dp(16))
                setOnClickListener { hideSideChats(); onNew() }
            })
        }
        fun fillTabs() {
            rows.removeAllViews()
            showingChats = false
            tabsBtn.setTextColor(cream); chatsBtn.setTextColor(muted)
            chatRow("Main chat", 0, current == null) { onPick(null) }
            listOf("Feed", "Ideas", "Goals", "Library").forEach { page ->
                chatRow(page, 0, false) { hideSideChats() }
            }
        }
        fillChats()
        tabsBtn.setOnClickListener { fillTabs() }
        chatsBtn.setOnClickListener { fillChats() }
        rowsScroll.addView(rows, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        sheet.addView(rowsScroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        // Footer: settings + title sync + new chat.
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(4))
        }
        footer.addView(text("Settings", 14f, muted).apply {
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener { hideSideChats(); onSettings() }
        })
        footer.addView(text("Sync titles", 14f, muted).apply {
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener { hideSideChats(); onSyncTitles() }
        })
        footer.addView(text("New side chat", 14f, muted).apply {
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener { hideSideChats(); onNew() }
        })
        sheet.addView(footer)
        overlay.addView(sheet, LayoutParams(width * 7 / 8, LayoutParams.MATCH_PARENT, Gravity.END))
        overlay.tag = "side_panel"
        addView(overlay)
        val wSpec = android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY)
        val hSpec = android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY)
        overlay.measure(wSpec, hSpec)
        overlay.layout(0, 0, width, height)
    }

    fun hideSideChats() {
        (0 until childCount).map { getChildAt(it) }.filter { it.tag == "side_panel" }.forEach { removeView(it) }
    }

    fun sideChatsOpen() = (0 until childCount).any { getChildAt(it).tag == "side_panel" }

    fun showNotice(value: String) {
        voiceIndicator = null
        updatingTranscript = true
        message.contentDescription = null
        message.text = value
        showConversationArea()
        followingLatest = false
        scroll.post { scroll.scrollTo(0, 0); updatingTranscript = false; updateLatestButton() }
    }

    fun showRecording(history: List<ConversationTurn> = emptyList()) {
        followingLatest = true
        showTranscript(history + ConversationTurn(), Phase.LISTENING)
    }

    fun showConversation(user: String?, answer: String, transcriptPending: Boolean = false) =
        showConversation(listOf(ConversationTurn(user, linkedMapOf("preview" to answer))), transcriptPending)

    fun showConversation(history: List<ConversationTurn>, transcriptPending: Boolean = false) =
        showTranscript(history, if (history.lastOrNull()?.user.isNullOrBlank() && transcriptPending) Phase.THINKING else null)

    fun setHistoryState(hasHistory: Boolean, canClear: Boolean) {
        clearHistory.visibility = if (hasHistory) View.VISIBLE else View.GONE
        clearHistory.isEnabled = canClear
        clearHistory.alpha = if (canClear) 1f else .35f
    }

    private fun atBottom() = scroll.scrollY >= (message.height + scroll.paddingBottom - scroll.height).coerceAtLeast(0) - dp(2)

    private fun updateLatestButton() {
        latest.visibility = if (conversation && scroll.isShown && !atBottom()) View.VISIBLE else View.GONE
    }

    fun jumpToLatest() {
        followingLatest = true
        scroll.post {
            if (followingLatest) {
                applyingScroll = true
                scroll.scrollTo(0, (message.height + scroll.paddingBottom - scroll.height).coerceAtLeast(0))
                applyingScroll = false
                updateLatestButton()
            }
        }
    }

    private fun showTranscript(history: List<ConversationTurn>, indicator: Phase?) {
        voiceIndicator = indicator?.let { VoiceIndicator(it) }
        if (indicator == Phase.LISTENING) recordOverlay.show() else recordOverlay.hide()
        bubbleColumn.removeAllViews()
        val activeIndex = if (indicator != null) history.lastIndex else -1
        history.forEachIndexed { index, turn ->
            val active = index == activeIndex
            if (!active && turn.user == null && turn.attachments.isEmpty() && turn.replies.isEmpty()) {
                return@forEachIndexed // orphan empty turn: nothing to show (prevents stray "…")
            }
            // User bubble (light slate, dark text) + photo thumbnails + label.
            if (turn.user != null || turn.attachments.isNotEmpty() || active) {
                bubbleColumn.addView(label("You", muted, alignRight = true))
                bubbleColumn.addView(bubble(Color.rgb(232, 228, 220), Color.BLACK, alignRight = true).apply {
                    gravity = Gravity.START
                    if (turn.attachments.isNotEmpty()) {
                        val thumbRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                        turn.attachments.forEach { path ->
                            val thumb = thumbnailFor(path)
                            if (thumb != null) thumbRow.addView(ImageView(context).apply {
                                setImageBitmap(thumb)
                                layoutParams = LinearLayout.LayoutParams(dp(96), dp(96)).apply { marginEnd = dp(8) }
                            })
                        }
                        addView(thumbRow)
                    }
                    if (turn.user?.isNotBlank() == true || !active) {
                        val body = turn.user?.takeIf { it.isNotBlank() } ?: (if (active) "\uFFFC" else "Transcript unavailable")
                        val paperclip = if (turn.attachments.isNotEmpty()) "  📎" else ""
                        addView(bubbleText(body + paperclip, active && voiceIndicator != null))
                    }
                    if (turn.attachments.isNotEmpty() && turn.user?.isNotBlank() != true) {
                        addView(bubbleText(if (active) "\uFFFC" else "Photo attached", false))
                    }
                })
            }
            // Muse bubble (peach tint, dark text) with the reply or the streaming indicator.
            val museText = turn.answer
            bubbleColumn.addView(label("Muse", orange))
            bubbleColumn.addView(bubble(Color.rgb(255, 224, 204), Color.rgb(66, 32, 10), alignRight = false).apply {
                if (active) {
                    addView(bubbleText(museText.ifBlank { if (indicator == Phase.LISTENING) "" else "…" },
                        indicator != null && indicator != Phase.LISTENING))
                } else {
                    addView(bubbleText(museText.ifBlank { "…" }, false))
                }
            })
        }
        if (activeIndex >= 0 && indicator != null) {
            // Active-turn indicators ride inside the last bubbles (listening sparkle in the user side).
            voiceIndicator?.let { _ -> }
        }
        message.contentDescription = null
        updatingTranscript = true
        val position = scroll.scrollY
        scroll.post {
            applyingScroll = true
            if (followingLatest) scroll.scrollTo(0, (bubbleColumn.height - scroll.height).coerceAtLeast(0))
            else scroll.scrollTo(0, position)
            applyingScroll = false
            updatingTranscript = false
            updateLatestButton()
        }
    }

    private fun label(name: String, color: Int, alignRight: Boolean = false) = TextView(context).apply {
        text = name
        textSize = 12f
        setTextColor(color)
        setPadding(dp(4), dp(6), dp(4), dp(4))
        gravity = if (alignRight) Gravity.END else Gravity.START
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { gravity = if (alignRight) Gravity.END else Gravity.START }
    }

    private fun bubbleText(value: String, animated: Boolean): TextView = TextView(context).apply {
        text = value
        textSize = 16f
        maxWidth = dp(330)
        setTextColor(Color.BLACK)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(dp(2).toFloat(), 1f)
        includeFontPadding = false
        setPadding(dp(12), dp(8), dp(12), dp(10))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private fun bubble(bg: Int, textColor: Int, alignRight: Boolean): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(bg)
                cornerRadius = dp(16).toFloat()
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.gravity = if (alignRight) Gravity.END else Gravity.START
            lp.setMargins(0, dp(2), 0, dp(6))
            layoutParams = lp
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }



    fun setState(value: String) {
        status.text = value
        status.contentDescription = value.lowercase().replaceFirstChar { it.uppercase() }
        status.visibility = if (value in setOf("READY", "CONNECTED", "LISTENING", "SENDING VOICE NOTE",
                "REPLY RECEIVED", "MUSE IS SPEAKING")) View.GONE else View.VISIBLE
        val phase = when (value) {
            "LISTENING" -> Phase.LISTENING
            "SENDING VOICE NOTE" -> Phase.THINKING
            "MUSE IS SPEAKING" -> Phase.SPEAKING
            else -> Phase.QUIET
        }
        avatar.phase = phase
        avatar.contentDescription = when (phase) {
            Phase.LISTENING -> "Muse is listening. Release to send."
            Phase.THINKING -> "Muse is thinking."
            Phase.SPEAKING -> "Muse is speaking. Hold to interrupt and talk."
            Phase.QUIET -> "Muse. Hold to talk, then release to send. Tap for device controls."
        }
        if (phase != Phase.QUIET) {
            if (!conversation && message.text.isBlank()) message.text = "Speak, then release the side button."
            showConversationArea()
        }
    }

    fun markVoiceUsed() {
        preferences.edit().putBoolean("used_voice", true).apply()
        hint.visibility = View.GONE
    }

    private fun showConversationArea() {
        conversation = true
        scroll.visibility = View.VISIBLE
        hint.visibility = View.GONE
        resizeCharacter(true)
    }

    private fun resizeCharacter(small: Boolean) {
        val target = if (small) 1f else 0f
        transition?.cancel()
        if (compact == target) return
        if (!isLaidOut) { compact = target; requestLayout(); return }
        transition = ValueAnimator.ofFloat(compact, target).apply {
            duration = 280
            interpolator = DecelerateInterpolator()
            addUpdateListener { compact = it.animatedValue as Float; requestLayout() }
            start()
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) hideVolume()
        controlsGesture.onTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val large = min(w * .88f, h * .68f)
        val small = min(w * .32f, h * .25f)
        val size = (large + (small - large) * compact).toInt()
        fun exact(value: Int) = MeasureSpec.makeMeasureSpec(value, MeasureSpec.EXACTLY)
        avatar.measure(exact(size), exact(size))
        val contentTop = conversationTop(w.toFloat(), h.toFloat())
        scroll.measure(exact(w), exact(h - contentTop - dp(66)))
        recordOverlay.measure(exact(w), exact(h))
        status.measure(exact(w - dp(52)), exact(dp(24)))
        hint.measure(exact(w - dp(20)), exact(dp(36)))
        clearHistory.measure(exact(dp(48)), exact(dp(48)))
        latest.measure(exact(dp(48)), exact(dp(48)))
        volume.measure(exact(w), exact(h))
        bubble.measure(exact(dp(72)), exact(dp(72)))
        composer.measure(exact(w), exact(dp(66)))
        composerAttach.measure(exact(dp(60)), exact(dp(60)))
        composerInput.measure(exact(w - dp(163)), exact(dp(60)))
        composerVoice.measure(exact(dp(60)), exact(dp(60)))
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.tag == "side_panel") child.measure(exact(w), exact(h))
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val w = width.toFloat()
        val h = height.toFloat()
        val large = min(w * .88f, h * .68f)
        val small = min(w * .32f, h * .25f)
        val size = (large + (small - large) * compact).toInt()
        val idleTop = (h - large) / 2 - h * .025f
        val activeTop = h * .045f
        val avatarTop = (idleTop + (activeTop - idleTop) * compact).toInt()
        avatar.compact = compact
        avatar.layout((width - size) / 2, avatarTop, (width + size) / 2, avatarTop + size)
        val contentTop = conversationTop(w, h)
        status.layout(dp(26), contentTop - dp(24), width - dp(26), contentTop)
        scroll.layout(0, contentTop, width, height - dp(66))
        hint.layout(dp(10), height - dp(53), width - dp(10), height - dp(17))
        clearHistory.layout(width - dp(62), dp(14), width - dp(14), dp(62))
        latest.layout((width - dp(48)) / 2, height - dp(68), (width + dp(48)) / 2, height - dp(20))
        volume.layout(0, 0, width, height)
        recordOverlay.layout(0, 0, width, height)
        layoutButtons()
        updateLatestButton()
    }

    private val thumbnails = mutableMapOf<String, Bitmap>()
    private fun thumbnailFor(path: String): Bitmap? {
        thumbnails[path]?.let { return it }
        val file = java.io.File(path)
        if (!file.exists()) return null
        val raw = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        val size = dp(56)
        val scaled = Bitmap.createScaledBitmap(raw, size, size, true)
        if (scaled !== raw) raw.recycle()
        thumbnails[path] = scaled
        return scaled
    }

    private fun conversationTop(w: Float, h: Float) = (h * .045f + min(w * .32f, h * .25f) + dp(27)).toInt()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val contentTop = conversationTop(w.toFloat(), h.toFloat())
        transcriptFade.shader = LinearGradient(
            0f, contentTop.toFloat(), 0f, contentTop + dp(24).toFloat(),
            intArrayOf(background, background, Color.TRANSPARENT),
            floatArrayOf(0f, .17f, 1f), Shader.TileMode.CLAMP,
        )
        latestFade.shader = LinearGradient(0f, h - dp(100).toFloat(), 0f, h - dp(72).toFloat(),
            Color.TRANSPARENT, background, Shader.TileMode.CLAMP)
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        val drawn = super.drawChild(canvas, child, drawingTime)
        if (child === scroll && latest.visibility == View.VISIBLE)
            canvas.drawRect(0f, height - dp(100).toFloat(), width.toFloat(), height.toFloat(), latestFade)
        if (child === scroll && scroll.scrollY > 0) {
            // Cover clipped glyphs at the edge before fading into the readable transcript.
            canvas.drawRect(0f, scroll.top.toFloat(), width.toFloat(), scroll.top + dp(24).toFloat(), transcriptFade)
        }
        return drawn
    }

    override fun onDetachedFromWindow() {
        transition?.cancel()
        hideVolume()
        super.onDetachedFromWindow()
    }

    private fun text(value: String, size: Float, color: Int) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color)
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        includeFontPadding = false
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private enum class Phase { QUIET, LISTENING, THINKING, SPEAKING }

    private inner class VolumeOverlay(context: Context) : View(context) {
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
        private val speaker = Path()
        private var level = 0
        private var steps = 0

        init {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            visibility = View.GONE
        }

        fun setLevel(current: Int, max: Int) {
            level = current
            steps = max
            contentDescription = if (current == 0) "Media volume muted" else "Media volume $current of $max"
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.argb(230, 10, 11, 10))
            val cx = width / 2f
            val cy = height / 2f
            ink.style = Paint.Style.FILL
            ink.color = cream
            ink.textAlign = Paint.Align.CENTER
            ink.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics)
            ink.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            canvas.drawText("V O L U M E", cx, cy - dp(100), ink)

            canvas.save()
            canvas.translate(cx - dp(6), cy - dp(10).toFloat())
            speaker.rewind()
            speaker.moveTo(-dp(42).toFloat(), -dp(14).toFloat())
            speaker.lineTo(-dp(26).toFloat(), -dp(14).toFloat())
            speaker.lineTo(0f, -dp(36).toFloat())
            speaker.lineTo(0f, dp(36).toFloat())
            speaker.lineTo(-dp(26).toFloat(), dp(14).toFloat())
            speaker.lineTo(-dp(42).toFloat(), dp(14).toFloat())
            speaker.close()
            ink.color = if (level == 0) muted else cream
            canvas.drawPath(speaker, ink)
            ink.style = Paint.Style.STROKE
            ink.strokeWidth = dp(5).toFloat()
            ink.strokeCap = Paint.Cap.ROUND
            ink.color = orange
            if (level == 0) {
                canvas.drawLine(dp(14).toFloat(), -dp(12).toFloat(), dp(38).toFloat(), dp(12).toFloat(), ink)
                canvas.drawLine(dp(14).toFloat(), dp(12).toFloat(), dp(38).toFloat(), -dp(12).toFloat(), ink)
            } else if (steps > 0) {
                // One, two, then three sound waves across the lower, middle, and upper thirds.
                repeat((level * 3 + steps - 1) / steps) { index ->
                    val radius = dp(26 + index * 14).toFloat()
                    canvas.drawArc(-radius, -radius, radius, radius, -40f, 80f, false, ink)
                }
            }
            canvas.restore()

            if (steps > 0) {
                val gap = dp(4).toFloat()
                val cell = min(dp(28).toFloat(), (width - dp(52) - gap * (steps - 1)) / steps)
                val left = (width - cell * steps - gap * (steps - 1)) / 2
                val top = cy + dp(70)
                ink.style = Paint.Style.FILL
                repeat(steps) { index ->
                    ink.color = if (index < level) orange else Color.rgb(46, 43, 39)
                    val x = left + index * (cell + gap)
                    canvas.drawRoundRect(x, top, x + cell, top + cell, cell * .2f, cell * .2f, ink)
                }
            }
        }
    }

    private inner class IconButton(context: Context, label: String, private val downArrow: Boolean,
        private val plus: Boolean = false) : View(context) {
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            contentDescription = label
            isFocusable = true
            isClickable = true
        }

        override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = "android.widget.Button"
        }

        override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val radius = width / 2f - dp(2)
            ink.style = Paint.Style.FILL
            ink.color = if (isPressed) Color.rgb(57, 43, 30)
                else if (downArrow) Color.rgb(32, 29, 25) else this@MuseScreen.background
            canvas.drawCircle(cx, cy, radius, ink)
            ink.style = Paint.Style.STROKE
            ink.strokeWidth = dp(1).toFloat()
            ink.color = if (downArrow) orange else muted
            if (downArrow) canvas.drawCircle(cx, cy, radius - dp(1), ink)
            ink.strokeWidth = dp(6).toFloat()
            ink.strokeCap = Paint.Cap.ROUND
            ink.strokeJoin = Paint.Join.ROUND
            if (downArrow) {
                canvas.drawLine(cx, cy - dp(8), cx, cy + dp(8), ink)
                canvas.drawLine(cx - dp(6), cy + dp(2), cx, cy + dp(8), ink)
                canvas.drawLine(cx, cy + dp(8), cx + dp(6), cy + dp(2), ink)
            } else if (plus) {
                ink.color = cream
                val arm = width / 5f
                canvas.drawLine(cx, cy - arm, cx, cy + arm, ink)
                canvas.drawLine(cx - arm, cy, cx + arm, cy, ink)
            } else {
                canvas.drawLine(cx - dp(8), cy - dp(7), cx + dp(8), cy - dp(7), ink)
                canvas.drawLine(cx - dp(3), cy - dp(11), cx + dp(3), cy - dp(11), ink)
                canvas.drawRoundRect(cx - dp(6).toFloat(), cy - dp(4).toFloat(), cx + dp(6).toFloat(),
                    cy + dp(10).toFloat(), dp(2).toFloat(), dp(2).toFloat(), ink)
                canvas.drawLine(cx - dp(2), cy, cx - dp(2), cy + dp(6), ink)
                canvas.drawLine(cx + dp(2), cy, cx + dp(2), cy + dp(6), ink)
            }
        }
    }

    /** Recording feedback: red pulsing dot + live waveform bars, Muse-style attention. */
    private inner class RecordOverlay(context: Context) : FrameLayout(context) {
        private val red = Color.rgb(232, 59, 44)
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
        private var phase = false

        init { visibility = View.GONE }

        fun show() {
            phase = true
            alpha = 0f
            animate().cancel()
            alpha = 1f
            visibility = View.VISIBLE
            postInvalidateOnAnimation()
        }

        fun hide() {
            phase = false
            visibility = View.GONE
        }

        override fun onDraw(canvas: Canvas) {
            if (!phase || visibility != View.VISIBLE) return
            val clock = android.os.SystemClock.uptimeMillis() / 1000.0
            val pulse = ((sin(clock * 6) + 1) / 2).toFloat()
            val cx = width / 2f
            val cy = height * .58f
            // Backdrop: dim the conversation while recording.
            ink.style = Paint.Style.FILL
            ink.color = Color.argb(110, 0, 0, 0)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), ink)
            // Pulsing red dot on the mic button position (right side of the composer).
            val dotR = (dp(9) + pulse * dp(5)).toFloat()
            ink.color = red
            canvas.drawCircle(width - dp(50).toFloat(), height - dp(36).toFloat(), dotR, ink)
            // Waveform bars in the center.
            val barW = dp(5).toFloat()
            val gap = dp(5).toFloat()
            val total = 9 * barW + 8 * gap
            var x = cx - total / 2
            repeat(9) { index ->
                val hRatio = ((sin(clock * 7 + index * 1.4) + 1) / 2).toFloat()
                val barH = dp(8) + hRatio * dp(56)
                ink.color = cream
                canvas.drawRoundRect(x, cy - barH / 2, x + barW, cy + barH / 2, dp(2).toFloat(), dp(2).toFloat(), ink)
                x += barW + gap
            }
            // REC label.
            ink.color = Color.WHITE
            ink.textSize = dp(14).toFloat()
            ink.textAlign = Paint.Align.CENTER
            canvas.drawText("● REC", cx, cy - dp(46), ink)
            if (phase) postInvalidateOnAnimation()
        }
    }

    /** Mic button in the composer; hold to talk (same gesture contract as the avatar). */
    private inner class ComposerVoice(context: Context) : View(context) {
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            contentDescription = "Voice input. Hold to talk, release to send."
            isClickable = true
            isFocusable = true
        }

        override fun performClick(): Boolean { super.performClick(); return true }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val radius = width / 2f - dp(2)
            ink.style = Paint.Style.FILL
            ink.color = if (isPressed) Color.rgb(57, 43, 30) else this@MuseScreen.background
            canvas.drawCircle(cx, cy, radius, ink)
            ink.style = Paint.Style.STROKE
            ink.strokeWidth = dp(1).toFloat()
            ink.color = muted
            canvas.drawCircle(cx, cy, radius - dp(1), ink)
            ink.style = Paint.Style.FILL
            ink.color = cream
            val body = radius * .34f
            canvas.drawRoundRect(cx - body, cy - radius * .46f, cx + body, cy + radius * .28f,
                body, body, ink)
            val feet = radius * .22f
            ink.style = Paint.Style.STROKE
            ink.strokeWidth = dp(2).toFloat()
            ink.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(cx - feet, cy + radius * .06f, cx + feet, cy + radius * .06f, ink)
            canvas.drawLine(cx, cy + radius * .06f, cx, cy + radius * .48f, ink)
        }
    }

    /** Left-corner chat bubble: opens the side-chat panel; dot shows total unread. */
    private inner class BubbleButton(context: Context, label: String) : View(context) {
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
        var unread = 0
            set(value) { field = value; invalidate() }

        init {
            contentDescription = label
            isClickable = true
            isFocusable = true
        }

        override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = "android.widget.Button"
        }

        override fun performClick(): Boolean { super.performClick(); return true }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            ink.style = Paint.Style.FILL
            ink.color = if (isPressed) Color.rgb(57, 43, 30) else this@MuseScreen.background
            canvas.drawCircle(cx, cy, dp(22).toFloat(), ink)
            ink.strokeWidth = dp(1).toFloat()
            ink.style = Paint.Style.STROKE
            ink.color = muted
            canvas.drawCircle(cx, cy, dp(21).toFloat(), ink)
            ink.style = Paint.Style.FILL
            ink.color = cream
            ink.textSize = width / 3.5f
            ink.textAlign = Paint.Align.CENTER
            val fm = ink.fontMetrics
            canvas.drawText("💬", cx, cy - (fm.ascent + fm.descent) / 2, ink)
            if (unread > 0) {
                ink.color = Color.rgb(52, 120, 246)
                canvas.drawCircle(width - dp(9).toFloat(), dp(9).toFloat(), dp(6).toFloat(), ink)
            }
        }
    }

    private inner class VoiceIndicator(private val phase: Phase) : ReplacementSpan() {
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted }

        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?) = dp(24)

        override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float,
            top: Int, y: Int, bottom: Int, paint: Paint) {
            val clock = android.os.SystemClock.uptimeMillis() / 1000.0
            val center = y + (paint.ascent() + paint.descent()) / 2
            if (phase == Phase.LISTENING) {
                ink.style = Paint.Style.FILL
                repeat(4) { index ->
                    val height = dp(4) + dp(12) * ((sin(clock * 6 + index * 1.8) + 1) / 2).toFloat()
                    val left = x + index * dp(6)
                    canvas.drawRoundRect(left, center - height / 2, left + dp(3), center + height / 2,
                        dp(2).toFloat(), dp(2).toFloat(), ink)
                }
            } else {
                ink.style = Paint.Style.STROKE
                ink.strokeWidth = dp(2).toFloat()
                ink.strokeCap = Paint.Cap.ROUND
                val radius = dp(7).toFloat()
                canvas.drawArc(x + dp(2), center - radius, x + dp(2) + radius * 2, center + radius,
                    (clock * 240 % 360).toFloat(), 250f, false, ink)
            }
        }
    }

    private fun refreshAvatarImage() {
        val f = java.io.File(context.noBackupFilesDir, "avatar.webp")
        val file = when {
            f.exists() && f.length() > 0 && f.canRead() -> f
            else -> RootAssets.syncAvatar(context) ?: return
        }
        thread {
            val decoded = BitmapFactory.decodeFile(file.absolutePath) ?: return@thread
            val trimmed = trimWhiteMargins(decoded)
            if (trimmed !== decoded) decoded.recycle()
            if (file.lastModified() != lastAvatarStamp) {
                lastAvatarStamp = file.lastModified()
                post { personaImage = trimmed; avatar.invalidate() }
            }
        }
    }

    @Volatile private var personaImage: Bitmap? = null
    private var lastAvatarStamp = 0L
    /** Persona avatars ship on a white canvas; crop to the drawn subject so the face fills the ring. */
    private fun trimWhiteMargins(source: Bitmap): Bitmap {
        val w = source.width; val h = source.height
        if (w * h > 4_000_000) return source
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        fun bright(i: Int) = ((pixels[i] shr 16 and 0xFF) + (pixels[i] shr 8 and 0xFF) + (pixels[i] and 0xFF)) / 3 > 245
        var top = 0; while (top < h && (0 until w).all { bright(it + top * w) }) top++
        var bottom = h; while (bottom > top + 1 && (0 until w).all { bright(it + (bottom - 1) * w) }) bottom--
        var left = 0; while (left < w && (0 until h).all { bright(left + it * w) }) left++
        var right = w; while (right > left + 1 && (0 until h).all { bright((right - 1) + it * w) }) right--
        if (top == 0 && left == 0 && bottom == h && right == w) return source
        val pad = 4
        val x0 = (left - pad).coerceAtLeast(0); val y0 = (top - pad).coerceAtLeast(0)
        val x1 = (right + pad).coerceAtMost(w); val y1 = (bottom + pad).coerceAtMost(h)
        if (x1 - x0 < 24 || y1 - y0 < 24) return source
        return Bitmap.createBitmap(source, x0, y0, x1 - x0, y1 - y0)
    }

    private fun thread(block: () -> Unit) = Thread(block).apply { start() }

    private inner class Character(context: Context) : View(context) {
        var compact = 0f
        var phase = Phase.QUIET
            set(value) { field = value; invalidate() }
        // The user's own emoji stands in for the plush illustration, whose redistribution
        // rights are not established. Set via preferences key "avatar_emoji".
        private val glyph = context.getSharedPreferences("gadget_ui", Context.MODE_PRIVATE)
            .getString("avatar_emoji", "🙂").orEmpty().ifBlank { "🙂" }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var glow: RadialGradient? = null

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            glow = RadialGradient(w / 2f, h / 2f, w / 2f, orange, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val clock = android.os.SystemClock.uptimeMillis() / 1000.0
            val pulse = ((sin(clock * 3) + 1) / 2).toFloat()
            paint.style = Paint.Style.FILL
            paint.alpha = 255
            val center = width / 2f
            val radius = width * .42f
            val glowAlpha = if (phase == Phase.QUIET) 18 else (28 + pulse * 22).toInt()
            paint.shader = glow
            paint.alpha = glowAlpha
            canvas.drawCircle(center, center, width / 2f, paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1).toFloat()
            paint.color = orange
            paint.alpha = when (phase) {
                Phase.LISTENING -> (150 + pulse * 100).toInt()
                Phase.THINKING -> 170
                else -> 0
            }
            if (phase == Phase.THINKING) canvas.drawArc(center - radius, center - radius,
                center + radius, center + radius, (clock * 140 % 360).toFloat(), 90f, false, paint)
            else canvas.drawCircle(center, center, radius, paint)
            paint.style = Paint.Style.FILL
            paint.alpha = 255
            val image = personaImage
            val ringed = compact > 0f
            paint.style = Paint.Style.FILL
            if (image != null) {
                // Cover the ring: scale so the shorter side fills, centered.
                val s = maxOf(width.toFloat() / image.width, height.toFloat() / image.height)
                canvas.save()
                if (ringed) canvas.clipPath(Path().apply { addCircle(center, center, radius, Path.Direction.CW) })
                canvas.translate(center, center); canvas.scale(s, s)
                canvas.drawBitmap(image, -image.width / 2f, -image.height / 2f, paint)
                canvas.restore()
            } else {
                if (ringed) {
                    canvas.save()
                    canvas.clipPath(Path().apply { addCircle(center, center, radius, Path.Direction.CW) })
                }
                paint.color = Color.WHITE
                paint.textSize = width * .52f
                paint.textAlign = Paint.Align.CENTER
                val metrics = paint.fontMetrics
                canvas.drawText(glyph, center, center - (metrics.ascent + metrics.descent) / 2, paint)
                if (ringed) canvas.restore()
            }
            paint.color = orange
            if (phase == Phase.SPEAKING) {
                repeat(3) { index ->
                    val bar = dp(5) + (dp(9) * ((sin(clock * 7 + index * 1.8) + 1) / 2)).toFloat()
                    val x = width * .80f + index * dp(5)
                    canvas.drawRoundRect(x, height / 2f - bar / 2, x + dp(3), height / 2f + bar / 2,
                        dp(2).toFloat(), dp(2).toFloat(), paint)
                }
            }
            if (phase != Phase.QUIET && isShown) postInvalidateOnAnimation()
        }
    }
}
