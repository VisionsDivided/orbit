// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Canvas
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import io.github.kmmarin.tvbro.data.*
import java.awt.Canvas
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

/** Native child surface mpv draws into; mpv is attached once it has a window id and detached before it goes. */
private class VideoCanvas(val onReady: (Long) -> Unit, val onGone: () -> Unit, val onMouse: (MouseEvent) -> Unit, val onSize: (Int, Int) -> Unit) : Canvas() {
    init {
        background = java.awt.Color.BLACK; isFocusable = false
        addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) = onMouse(e) })
        // the overlay is laid out in the surface's aspect (and clicks on it are mapped back): it needs the size
        addComponentListener(object : java.awt.event.ComponentAdapter() { override fun componentResized(e: java.awt.event.ComponentEvent) = onSize(width, height) })
    }
    override fun addNotify() { super.addNotify(); onReady(MpvVideo.windowId(this)) }
    override fun removeNotify() { onGone(); super.removeNotify() }
}

/**
 * The video, at one stable place in the window tree so the same mpv keeps playing when it moves between full screen
 * and the TV screen's preview spot (no re-buffering). Off-screen while a preview spot is not on screen.
 */
@Composable
fun VideoHost(app: AppState) = BoxWithConstraints(Modifier.fillMaxSize()) {
    val req = app.playing ?: return@BoxWithConstraints
    val d = LocalDensity.current
    val r = app.previewRect
    val whole = with(d) { Rect(0f, 0f, maxWidth.toPx(), maxHeight.toPx()) }
    val off = Rect(-64f, -64f, -48f, -48f)
    val onTv = app.tab == Tab.TV && app.detail == null
    // where the video belongs; null while the TV screen is still laying out its preview spot (keep the last place)
    val target: Rect? = when {
        app.full -> whole
        onTv && r != null -> r
        onTv -> null
        else -> off
    }
    val box = remember { Animatable(target ?: off, Rect.VectorConverter) }
    LaunchedEffect(target) {
        val t = target ?: return@LaunchedEffect
        // full screen <-> preview: a short, smooth move of the same surface (the player is never reloaded);
        // window resizes and off-screen moves are immediate
        val between = (box.targetValue == whole && t == r) || (box.targetValue == r && t == whole)
        if (between && box.value != off) box.animateTo(t, tween(320, easing = FastOutSlowInEasing)) else box.snapTo(t)
    }
    val b = box.value
    val mod = Modifier.offset { IntOffset(b.left.roundToInt(), b.top.roundToInt()) }
        .size(with(d) { b.width.coerceAtLeast(1f).toDp() }, with(d) { b.height.coerceAtLeast(1f).toDp() })
    val lib = MpvLoader.lib
    if (lib == null) {
        if (app.full) Box(Modifier.fillMaxSize().background(Color.Black)) {
            Text("Video library missing (libmpv is not included in Orbit).\n${MpvLoader.error ?: ""}\nSettings > Playback > Video library shows how to add it (see also INSTALL.md).\nPress Esc to go back.\n\n${req.title}",
                color = Color.White, modifier = Modifier.align(Alignment.Center).padding(32.dp))
        }
        return@BoxWithConstraints
    }
    if (MpvVideo.mode(Platform.os) == VideoMode.SW) {
        // macOS: mpv renders frames into memory, drawn here as an ordinary Compose image (menus can draw above it).
        // Same lifecycle as the Canvas below: mpv starts when the video appears and stops when it goes.
        DisposableEffect(lib) { app.player.attach(lib, MpvVideo.SW); onDispose { app.player.detach() } }
        SwVideoSurface(app.player.sw, mod, onSize = { w, h -> app.player.surface(w, h) },
            onClick = { btn, x, y, n -> app.runOnUi { app.player.click(btn, x, y, n) } })
        return@BoxWithConstraints
    }
    SwingPanel(background = Color.Black, modifier = mod, factory = {
        VideoCanvas(onReady = { wid -> app.player.attach(lib, wid) }, onGone = { app.player.detach() }, onMouse = { e -> app.runOnUi { app.player.mouse(e) } },
            onSize = { w, h -> app.runOnUi { app.player.surface(w, h) } })
    })
}

/**
 * The software-rendered video ([SwRenderer]): black, with the newest frame drawn over the whole box (the frame has
 * the box's aspect; mpv letterboxed the video in it). Redraws on every new frame; the box size in pixels goes to
 * the renderer (render size) and to [onSize] (OSD layout); clicks go to [onClick] with AWT button numbers.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SwVideoSurface(r: SwRenderer?, modifier: Modifier, onSize: (Int, Int) -> Unit = { _, _ -> }, onClick: (Int, Int, Int, Int) -> Unit = { _, _, _, _ -> }) {
    var box by remember { mutableStateOf(IntSize.Zero) }
    // also when the renderer appears after the first layout (mpv starts once the surface is composed)
    LaunchedEffect(r, box) { if (box != IntSize.Zero) r?.box(box.width, box.height) }
    Canvas(modifier.background(Color.Black)
        .onSizeChanged { box = it; onSize(it.width, it.height) }
        .onPointerEvent(PointerEventType.Release) { e ->
            val awt = e.awtEventOrNull ?: return@onPointerEvent
            val p = e.changes.firstOrNull()?.position ?: return@onPointerEvent
            onClick(awt.button, p.x.roundToInt(), p.y.roundToInt(), awt.clickCount)
        }) {
        r ?: return@Canvas
        r.frame.value // read: a new frame redraws
        val f = r.claim() ?: return@Canvas
        val img = f.image() ?: return@Canvas
        try {
            drawIntoCanvas { c ->
                c.nativeCanvas.drawImageRect(img, org.jetbrains.skia.Rect.makeWH(f.w.toFloat(), f.h.toFloat()),
                    org.jetbrains.skia.Rect.makeWH(size.width, size.height), org.jetbrains.skia.SamplingMode.LINEAR, null, true)
            }
        } finally { img.close() } // the drawing keeps its own reference until it is flushed
    }
}

class PlayerCtl(private val app: AppState, private val scope: CoroutineScope) {
    private var mpv: Mpv? = null
    /** The software frames of the playing mpv ([VideoMode.SW], macOS); null on the `wid` path. */
    var sw by mutableStateOf<SwRenderer?>(null)
        private set
    private var loadedUrl: String? = null
    private var req: PlayReq? = null
    var info by mutableStateOf(false)
    var menu by mutableStateOf(false)
    var sel by mutableStateOf(0)
    /** Index in [aspects]; "Fit, crop black bars" ([CROP]) is remembered in the settings, the others last for the run. */
    var aspect by mutableStateOf(if (app.settings.cropBars) CROP else 0)
    /** "Fit, crop black bars" of the playing mpv (MpvCropBars). */
    private var crop: MpvCropBars? = null
    var sleepMin by mutableStateOf(0)
    private var sleepJob: Job? = null
    private var ticker: Job? = null
    /** Bottom bar / quick settings hide 5 s after the last input. */
    private var lastInput = 0L
    private var barShown = false
    // Up next (episodes)
    private var upNextAt = 0L
    private var upNextCancelled = false
    private var advancing = false
    private var lastSave = 0L
    private var digits = ""
    private var digitJob: Job? = null
    val aspects = listOf("Auto" to "-1", "Fit, crop black bars" to "-1", "16:9" to "16:9", "4:3" to "4:3", "2.35:1" to "2.35:1", "Fill" to "-1")
    /** The video surface's size in pixels (the overlay's aspect; mouse clicks on it). */
    private var vw = 0
    private var vh = 0
    /** "This channel keeps buffering. Try HLS?" is shown (StallHint; once per channel while it plays). */
    private var hlsHint = false
    private var stalls = 0
    private var liveSince = 0L
    private val hinted = HashSet<String>()

    fun surface(w: Int, h: Int) { vw = w; vh = h; mpv?.let { redraw(it) } }

    fun attach(lib: MpvLib, wid: Long) {
        try {
            val m = Mpv(lib, wid, app.settings.hwdec, onLoaded = { app.runOnUi { onLoaded() } }) { why -> app.runOnUi { onEnd(why) } }
            m.set("audio-delay", "%.3f".format(java.util.Locale.US, app.settings.audioDelayMs / 1000.0))
            applyCaptionStyle(m)
            mpv = m; loadedUrl = null; sw = m.sw
            crop = MpvCropBars(m); applyAspect(m)
            app.playing?.let { load(it) }
            if (ticker?.isActive != true) ticker = scope.launch { while (true) { delay(500); tick() } }
        } catch (e: Throwable) { Diag.log("mpv start failed: ${e.message}"); app.toast("Video engine failed: ${e.message}") }
    }

    fun detach() { saveResume(); ticker?.cancel(); sw = null; crop = null; mpv?.destroy(); mpv = null; loadedUrl = null }

    private fun applyCaptionStyle(m: Mpv) {
        m.set("sub-font-size", listOf("40", "55", "72")[app.settings.captionSize.coerceIn(0, 2)])
        when (app.settings.captionBg) {
            1 -> { m.set("sub-border-style", "outline-and-shadow"); m.set("sub-back-color", "#99000000") }
            2 -> { m.set("sub-border-style", "opaque-box"); m.set("sub-back-color", "#E0000000") }
            else -> { m.set("sub-border-style", "outline-and-shadow"); m.set("sub-back-color", "#00000000") }
        }
    }

    /** Plays [r]; the same address already playing (preview to full screen) is kept without reloading. */
    fun load(r: PlayReq) {
        val m = mpv
        if (r.url != loadedUrl) {
            saveResume()
            // a Plex / Jellyfin title that stops playing here: "stopped" to the media server (not when the same one loads again)
            if (mediaReq?.answer?.session != r.media?.answer?.session) mediaEnd()
            if (r.media != null && mediaReq == null) { mediaReq = r.media; mediaClock = io.github.kmmarin.tvbro.orbit.MediaProgressClock(); fellBack = r.media.hlsOnly }
            req = r
            if (m != null) { if (start(m, r)) loadedUrl = r.url }
            upNextAt = 0; upNextCancelled = false; advancing = false
            startRecovery(r)
        } else req = r
        muted = null
        if (app.full) { showBar(); osd(r.title) }
    }

    /** Plays [r] in [m]; an address that may not be played ([PlayUrl]) is refused with a notice instead. */
    private fun start(m: Mpv, r: PlayReq): Boolean = try {
        // the home server's relay wants this app's session cookie (Plex / Jellyfin titles); nothing else gets it
        m.set("http-header-fields", if (r.media != null) DeskMediaPlay.headerFields(app.orbit.link, r.url) else "")
        m.play(playUrl(r), r.startSec, trustedLocal = r.recording != null); true
    }
        catch (e: IllegalArgumentException) { Diag.log("${r.title}: address refused"); app.toast(e.message ?: "Not played"); false }

    /** What mpv opens for [r]: live Xtream channels in their own format (TS / HLS, synced per profile); the rest as is. */
    private fun playUrl(r: PlayReq, alt: Channel? = null): String {
        val ch = r.live
        if (ch == null || r.archive != null || r.recording != null) return r.url
        return if (alt != null) app.liveUrl(alt) else app.liveUrl(ch, r.url)
    }

    /** The channel's stream format changed: the same channel again in the new format (from its source, not an alternate). */
    fun reloadLive() {
        val r = req ?: return
        val m = mpv ?: return
        if (r.live == null || r.archive != null) return
        hlsHint = false
        startRecovery(r)
        try { m.play(playUrl(r)) } catch (e: IllegalArgumentException) { app.toast(e.message ?: "Not played") }
        if (app.full) showBar()
    }

    fun close() { saveResume(); mediaEnd(); stopRecovery(); sleepMin = 0; sleepJob?.cancel(); menu = false; info = false; upNextAt = 0; req = null; loadedUrl = null; mpv?.cmd("stop") }

    // ---- live TV that errors or stalls: reconnect, then the channel's alternate sources, then rest (shared LiveRecovery) ----
    private var recovery: LiveRecovery? = null
    private val stall = StallWatch()
    private var recoverJob: Job? = null
    private var noticeJob: Job? = null
    /** Small corner notice ("Reconnecting… (2/3)", "Trying another source: …"); drawn by mpv in full screen and the preview. */
    var notice by mutableStateOf("")
        private set
    /** The alternate source playing instead of the channel (the channel shown stays the one asked for). */
    var altPlaying by mutableStateOf<Channel?>(null)
        private set

    private fun startRecovery(r: PlayReq) {
        stopRecovery()
        val ch = r.live
        if (ch != null && r.archive == null && r.recording == null) recovery = LiveRecovery(ch.key)
        stall.reset(System.currentTimeMillis())
        stalls = 0; liveSince = System.currentTimeMillis(); hlsHint = false
    }

    private fun stopRecovery() { recoverJob?.cancel(); noticeJob?.cancel(); recovery = null; altPlaying = null; setNotice("") }

    private fun setNotice(s: String, clearAfterMs: Long = 0) {
        noticeJob?.cancel()
        notice = s; mpv?.let { redraw(it) }
        if (clearAfterMs > 0) noticeJob = scope.launch { delay(clearAfterMs); notice = ""; mpv?.let { redraw(it) } }
    }

    /** The live source playing now failed (error, end of stream or a stall). */
    private fun liveFailed(why: String) {
        val rec = recovery ?: return
        val r = req ?: return
        val ch = r.live ?: return
        if (recoverJob?.isActive == true) return
        var step = rec.failed()
        if (step === LiveRecovery.Step.NeedAlts) { rec.setAlts(app.alternates(ch)); step = rec.failed() }
        Diag.log("${ch.name}: live $why, ${step.javaClass.simpleName}")
        setNotice(LiveRecovery.notice(step, ch.name))
        val (url, wait) = when (step) {
            is LiveRecovery.Step.Retry -> playUrl(r, rec.alt) to step.delayMs
            is LiveRecovery.Step.Alt -> playUrl(r, step.alt) to step.delayMs
            is LiveRecovery.Step.Rest -> playUrl(r) to step.delayMs
            LiveRecovery.Step.NeedAlts -> return
        }
        recoverJob = scope.launch {
            delay(wait)
            if (recovery !== rec || req !== r) return@launch
            if (step is LiveRecovery.Step.Rest) setNotice("Reconnecting… (${ch.name})")
            altPlaying = rec.alt
            mpv?.let { m -> try { m.play(url) } catch (e: IllegalArgumentException) { setNotice(e.message ?: "Not played") } }
            stall.reset(System.currentTimeMillis())
        }
    }

    private fun liveHealthy() {
        val rec = recovery ?: return
        val was = notice.isNotEmpty()
        rec.healthy()
        val alt = rec.alt
        if (alt != null) setNotice("Playing from another source: ${alt.name}", 6000) else if (was) setNotice("", 0)
    }

    // ---- Plex / Jellyfin titles: progress to the media server (every 10 s, also paused; "stopped" at the end) ----
    private var mediaReq: DeskMedia? = null
    private var mediaClock: io.github.kmmarin.tvbro.orbit.MediaProgressClock? = null
    /** The original file did not play and HLS was asked for (once per title). */
    private var fellBack = false

    private fun mediaEnd() {
        val mr = mediaReq ?: return
        mediaClock?.stop()?.let { app.media.report(mr, (pos() ?: 0) / 1000, it) }
        mediaReq = null; mediaClock = null
    }

    /** The original file of [md] did not play: once, the same title as HLS from where it stopped. */
    private fun mediaFallback(md: DeskMedia, r: PlayReq): Boolean {
        if (md.answer.isHls || md.hlsOnly || fellBack) return false
        fellBack = true
        osd("Trying another format…")
        app.media.play(md.conn, md.id, md.source, md.episode, md.item, hlsOnly = true, atSecs = pos()?.div(1000)?.takeIf { it > 0 } ?: r.startSec)
        return true
    }

    /** [r] has a next episode (the playlist's, or the media server's). */
    private fun hasNext(r: PlayReq): Boolean = r.nextEpisode() != null || r.media?.answer?.next != null

    private fun pos(): Long? = mpv?.num("time-pos")?.let { (it * 1000).toLong() }
    private fun dur(): Long = mpv?.num("duration")?.let { (it * 1000).toLong() } ?: 0

    fun saveResume(ended: Boolean = false) {
        val r = req ?: return
        val k = r.resumeKey ?: return
        val p = pos() ?: if (ended) 0 else return
        val done = app.watch.setPos(k, p, dur(), r.series?.key, ended)
        if (r.isEpisode) {
            if (done) app.watch.updateSeries(r.series!!, r.episodes, lastId = r.episode!!.id, touch = true)
        }
        lastSave = System.currentTimeMillis()
    }

    private fun onLoaded() {
        crop?.newFile() // a new title: the full frame until its own bars are found
        val r = req ?: return
        val m = mpv ?: return
        // Plex / Jellyfin: the server's WebVTT subtitles next to the file's own tracks (S / quick action Subtitles)
        r.media?.let { md -> for (s in md.answer.subtitles) m.cmd("sub-add", s.url, "auto", s.label, s.lang ?: "") }
        if (!r.isEpisode) return
        // captions and audio remembered for this series
        val row = app.watch.series(r.series!!.key) ?: return
        val tr = m.tracks()
        val subs = tr.filter { it.type == "sub" }; val auds = tr.filter { it.type == "audio" }
        when (val c = Watch.pickText(row.subPref, subs.map { TrackInfo(it.lang, it.title) })) {
            TrackChoice.Off -> m.set("sid", "no")
            is TrackChoice.Pick -> subs.getOrNull(c.index)?.let { m.set("sid", it.id) }
            TrackChoice.Default -> {}
        }
        Watch.pickAudio(row.audioLang, auds.map { TrackInfo(it.lang, it.title) })?.let { i -> auds.getOrNull(i)?.let { m.set("aid", it.id) } }
    }

    private fun onEnd(why: String) {
        val r = req ?: return
        // live: never a dialog; reconnect, then another source (the corner notice says what happens)
        if (recovery != null) return liveFailed(if (why.startsWith("error")) "error" else "ended")
        if (why.startsWith("error")) {
            val md = r.media
            if (md != null && mediaFallback(md, r)) return
            osd("Playback failed (${why.removePrefix("error: ")})")
            app.toast(if (md != null) "Could not play ${r.title} from ${DeskMediaPlay.name(md.source)}: the home server stopped answering or this format cannot be played" else "Playback failed: ${r.title}")
            return
        }
        if (r.live != null && r.archive == null) return
        saveResume(ended = true)
        r.media?.let { md ->
            if (md.answer.next != null && app.settings.autoplayNext && !upNextCancelled) return playNext()
            return app.stopPlayback()
        }
        val next = r.nextEpisode()
        if (r.isEpisode && next != null && app.settings.autoplayNext && !upNextCancelled) return playNext()
        app.stopPlayback()
    }

    fun playNext() {
        val r = req ?: return
        if (advancing) return
        r.media?.let { md ->
            val nx = md.answer.next ?: return
            advancing = true
            app.media.play(md.conn, nx.id, md.source, true, md.item)
            return
        }
        val n = r.nextEpisode() ?: return
        advancing = true
        saveResume(ended = true)
        app.playEpisode(r.series!!, r.episodes, n)
    }

    fun playPrev() { val r = req ?: return; val p = r.prevEpisode() ?: return osd("First episode"); saveResume(); app.playEpisode(r.series!!, r.episodes, p) }

    private var muted: Boolean? = null

    private fun tick() {
        val r = req ?: return
        val m = mpv ?: return
        val t = System.currentTimeMillis()
        val mute = !app.full && app.settings.previewMuted && !app.docked // the video that left full screen keeps its sound
        if (mute != muted) { muted = mute; m.set("mute", if (mute) "yes" else "no") }
        if (r.resumeKey != null && t - lastSave > 10_000 && app.full) saveResume()
        mediaReq?.let { mr -> mediaClock?.tick(t, m.get("pause") != "yes" && pos() != null)?.let { app.media.report(mr, (pos() ?: 0) / 1000, it) } }
        if (recovery != null && recoverJob?.isActive != true) when (stall.tick(m.num("time-pos"), m.get("pause") == "yes", t)) {
            StallWatch.Event.STALL -> { noteStall(r); liveFailed("stalled") }
            StallWatch.Event.HEALTHY -> liveHealthy()
            StallWatch.Event.NONE -> {}
        }
        // Up next: during the credits, a card and a countdown; then the next episode plays
        if ((r.isEpisode || r.media?.episode == true) && app.full && app.settings.autoplayNext && !upNextCancelled && hasNext(r)) {
            val p = pos() ?: 0; val d = dur()
            if (Watch.upNextDue(p, d)) { if (upNextAt == 0L) { upNextAt = t; lastInput = t } }
            else upNextAt = 0
            if (upNextAt > 0 && t - upNextAt >= Watch.UP_NEXT_COUNTDOWN_S * 1000L) { upNextAt = 0; playNext(); return }
        }
        if ((barShown || menu) && t - lastInput > 5000) { barShown = false; menu = false }
        crop?.tick(t, vw, vh)
        redraw(m)
    }

    /**
     * A live TS channel that stalls again and again in its first two minutes may play better as HLS (StallHint): the
     * player offers it once per channel; F3 (yellow) says yes. Never a switch on its own.
     */
    private fun noteStall(r: PlayReq) {
        val ch = r.live ?: return
        stalls++
        val t = System.currentTimeMillis()
        if (!hlsHint && ch.key !in hinted && stalls >= StallHint.MIN_STALLS && t - liveSince <= StallHint.WINDOW_MS &&
            LiveFormat.canHls(ch.url) && app.channelFormat(ch.key) != ChannelFormats.HLS) {
            hlsHint = true; hinted += ch.key
            if (app.full) showBar()
        }
    }

    fun showBar() { barShown = true; lastInput = System.currentTimeMillis(); mpv?.let { redraw(it) } }

    fun osd(s: String) { mpv?.cmd("show-text", s.replace("\n", " "), "2500") }

    private fun recordingNow(): Rec? = req?.live?.let { app.recorder.active(it) }

    private fun toggleRec() {
        val r = req ?: return
        val ch = r.live ?: return osd("Only live channels can be recorded")
        if (r.archive != null) return osd("Catch-up is not recorded")
        val a = app.recorder.active(ch)
        if (a != null) { app.recorder.stop(a); osd("Recording saved: ${a.title}") } else app.recordMenu(ch)
    }

    private fun cycleTrack(type: String, off: Boolean) {
        val m = mpv ?: return
        val t = m.tracks().filter { it.type == type }
        val opts = (if (off) listOf("no") else emptyList()) + t.map { it.id }
        if (opts.isEmpty()) return osd("No ${if (type == "sub") "subtitles" else "audio tracks"}")
        val cur = t.firstOrNull { it.selected }?.id ?: "no"
        val nx = opts[(opts.indexOf(cur) + 1) % opts.size]
        m.set(if (type == "sub") "sid" else "aid", nx)
        osd((if (type == "sub") "Subtitles: " else "Audio: ") + value(if (type == "sub") "subs" else "audio"))
        // remembered for the series (applied to its later episodes)
        val sk = req?.takeIf { it.isEpisode }?.series?.key ?: return
        val lang = t.firstOrNull { it.id == nx }?.lang
        if (type == "sub") app.watch.rememberSubs(sk, nx != "no", lang) else app.watch.rememberAudio(sk, lang)
    }

    // ---- quick actions (DESIGN-SPEC-5 §10): Guide, Favourite, Record, Audio, Subtitles, Start over, Format, Channels,
    //      Sleep timer, Info; then Video quality, Audio delay and Aspect (desktop extras) ----

    /** The quick actions for what plays now. */
    fun actions(): List<PlayerOsd.Qa> {
        val r = req ?: return emptyList()
        val ch = r.live
        val live = ch != null && r.archive == null && r.recording == null
        val l = ArrayList<PlayerOsd.Qa>()
        if (ch != null && r.recording == null) l += PlayerOsd.Qa("guide", "Guide", "≡")
        if (live) {
            l += PlayerOsd.Qa("fav", "Favourite", "♥", if (app.inList(ch!!.key)) PlayerOsd.RED else null, on = app.inList(ch.key))
            l += PlayerOsd.Qa("rec", if (recordingNow() != null) "Stop rec" else "Record", "●", PlayerOsd.REC, on = recordingNow() != null)
        } else if (r.vod != null || r.series != null) {
            val k = (r.series ?: r.vod)!!.key
            l += PlayerOsd.Qa("fav", "My list", "♥", if (app.inList(k)) PlayerOsd.RED else null, on = app.inList(k))
        }
        l += PlayerOsd.Qa("audio", "Audio", "♪")
        l += PlayerOsd.Qa("subs", "Subtitles", "CC")
        when {
            live && ch!!.catchup != null && ch.catchupDays > 0 -> l += PlayerOsd.Qa("startover", "Start over", "↺")
            r.archive != null -> l += PlayerOsd.Qa("tolive", "Back to live", "»")
            !live && ch == null -> l += PlayerOsd.Qa("startover", "From start", "↺")
        }
        if (hasNext(r)) l += PlayerOsd.Qa("next", "Next episode", "»")
        if (live) {
            val f = app.channelFormat(ch!!.key)
            val eff = if (f == io.github.kmmarin.tvbro.data.ChannelFormats.HLS) "HLS" else "TS"
            l += PlayerOsd.Qa("format", "Format · $eff", eff, if (f != null) PlayerOsd.YELLOW else null, on = f != null)
            l += PlayerOsd.Qa("channels", "Channels", "#")
        }
        l += PlayerOsd.Qa("sleep", if (sleepMin > 0) "Sleep · $sleepMin" else "Sleep timer", "z", on = sleepMin > 0)
        l += PlayerOsd.Qa("info", "Info", "i", on = info)
        l += PlayerOsd.Qa("quality", "Quality", "HD")
        l += PlayerOsd.Qa("delay", "Audio delay", "±", on = app.settings.audioDelayMs != 0)
        l += PlayerOsd.Qa("aspect", "Aspect · ${aspects[aspect].first}", "AR", on = aspect != 0)
        return l
    }

    /** Runs quick action [id]; [d] +1 / −1 steps a value (Enter / Down: +1, Up: −1). */
    private fun act(id: String, d: Int) {
        val m = mpv ?: return
        val r = req ?: return
        val ch = r.live
        when (id) {
            "guide" -> { menu = false; saveResume(); app.leavePlayer() }
            "fav" -> ch?.takeIf { r.archive == null && r.recording == null }?.let { app.toggleList(it.key) } ?: (r.series ?: r.vod)?.let { app.toggleList(it.key) }
            "rec" -> toggleRec()
            "audio" -> cycleTrack("audio", false)
            "subs" -> cycleTrack("sub", true)
            "startover" -> if (ch != null && r.archive == null) {
                val cur = app.epg.nowNext(ch, System.currentTimeMillis() / 1000).first
                if (cur != null) { menu = false; app.playArchive(ch, cur) } else osd("No guide data: nothing to start over")
            } else m.cmd("seek", "0", "absolute")
            "tolive" -> ch?.let { menu = false; app.playChannel(it) }
            "next" -> playNext()
            "format" -> ch?.let { app.cycleFormat(it) }
            "channels" -> { menu = false; channelPicker() }
            "sleep" -> {
                val steps = listOf(0, 15, 30, 60, 90, 120)
                sleepMin = steps[(steps.indexOf(sleepMin) + d + steps.size) % steps.size]
                sleepJob?.cancel()
                if (sleepMin > 0) sleepJob = scope.launch { delay(sleepMin * 60_000L); app.stopPlayback() }
                osd(if (sleepMin == 0) "Sleep timer off" else "Sleep in $sleepMin min")
            }
            "info" -> info = !info
            "quality" -> {
                // HLS / DASH variants are separate video tracks in mpv; "auto" lets mpv pick the best one
                val v = m.tracks().filter { it.type == "video" }
                if (v.size < 2) return osd("Only one video quality")
                val opts = listOf("auto") + v.sortedByDescending { it.h * 10_000_000L + it.bitrate }.map { it.id }
                val cur = m.get("vid")?.takeIf { it in opts } ?: "auto"
                m.set("vid", opts[(opts.indexOf(cur) + d + opts.size) % opts.size])
                osd("Quality: " + value("quality"))
            }
            "delay" -> {
                val ms = (app.settings.audioDelayMs + d * 50).coerceIn(-5000, 5000)
                app.update { it.copy(audioDelayMs = ms) }
                m.set("audio-delay", "%.3f".format(java.util.Locale.US, ms / 1000.0))
                osd("Audio delay $ms ms")
            }
            "aspect" -> {
                aspect = (aspect + d + aspects.size) % aspects.size
                applyAspect(m)
                if (app.settings.cropBars != (aspect == CROP)) app.update { it.copy(cropBars = aspect == CROP) }
            }
        }
        redraw(m)
    }

    private fun applyAspect(m: Mpv) {
        m.set("video-aspect-override", aspects[aspect].second)
        m.set("panscan", if (aspects[aspect].first == "Fill") "1.0" else "0.0")
        crop?.enable(aspect == CROP)
    }

    /** The quick action Channels: the current group's channels in a pick list (its own window, above the video). */
    private fun channelPicker() {
        val cur = req?.live
        val l = app.filtered.ifEmpty { app.shown }
        val now = System.currentTimeMillis() / 1000
        app.menu = MenuState("Channels · ${app.groupLabel(app.group)}", l.take(200).map { c ->
            val p = app.epg.nowNext(c, now).first
            ((if (c.key == cur?.key) "▶ " else "") + (if (c.number > 0) "${c.number}  " else "") + c.name + (p?.let { "  ·  ${it.title}" } ?: "")) to {
                if (app.isLocked(c)) app.askPin { app.focusCh = c; app.update { it.copy(lastChannel = c.key) }; app.setPlaying(PlayReq(c.url, c.name, live = c)) }
                else { app.focusCh = c; app.update { it.copy(lastChannel = c.key) }; app.setPlaying(PlayReq(c.url, c.name, live = c)) }
            }
        })
    }

    private fun value(id: String): String {
        val m = mpv
        fun label(type: String, none: String) = m?.tracks()?.firstOrNull { it.type == type && it.selected }
            ?.let { listOfNotNull(it.lang?.uppercase(), it.title.takeIf { t -> t.isNotBlank() }).joinToString(" ") }?.ifBlank { "#" } ?: none
        return when (id) {
            "audio" -> label("audio", "—")
            "subs" -> label("sub", "Off")
            "quality" -> m?.tracks()?.filter { it.type == "video" }?.let { v ->
                val s = v.firstOrNull { it.selected }
                if (v.size < 2) "Single" else (if (m.get("vid") == "auto") "Auto · " else "") + (s?.let { if (it.h > 0) "${it.h}p" else "#${it.id}" } ?: "Auto") + (s?.bitrate?.takeIf { it > 0 }?.let { " ${it / 1000} kb/s" } ?: "")
            } ?: "Auto"
            else -> ""
        }
    }

    private fun techText(m: Mpv): List<String> {
        fun g(k: String) = m.get(k)
        val w = g("video-params/w"); val h = g("video-params/h")
        val fps = (g("estimated-vf-fps") ?: g("container-fps"))?.toDoubleOrNull()
        val vbr = g("video-bitrate")?.toDoubleOrNull(); val abr = g("audio-bitrate")?.toDoubleOrNull()
        return listOf(
            "Video: ${if (w != null) "${w}×$h" else "—"}${fps?.let { " @ %.2f fps".format(it) } ?: ""}  ${g("video-codec") ?: ""}",
            "Audio: ${g("audio-codec-name") ?: "—"}  ${g("audio-params/channel-count") ?: ""} ch  ${g("audio-params/samplerate") ?: ""} Hz",
            "Bitrate: video ${vbr?.let { "%.1f Mb/s".format(it / 1e6) } ?: "—"}, audio ${abr?.let { "${(it / 1000).toInt()} kb/s" } ?: "—"}",
            "Decoder: ${g("hwdec-current")?.takeIf { it.isNotBlank() && it != "no" } ?: "software"}   Cache: ${g("demuxer-cache-duration")?.toDoubleOrNull()?.toInt() ?: 0} s" +
                (req?.live?.takeIf { req?.archive == null }?.let { "   Stream: ${app.formatLabel(it)}" } ?: ""),
        )
    }

    /** ASS width of the OSD (720 × the video surface's aspect). */
    private val aw: Double get() = if (vw > 0 && vh > 0) PlayerOsd.H * vw / vh else 1280.0

    /** The colour keys in the player (§9): Favourite, Record, Format: TS / HLS (live), Options. */
    private fun legendKeys(): List<PlayerOsd.Key> {
        val r = req ?: return emptyList()
        val live = r.live != null && r.archive == null && r.recording == null
        return listOfNotNull(PlayerOsd.Key(PlayerOsd.RED, if (live) "Favourite" else "My list"),
            if (live) PlayerOsd.Key(PlayerOsd.GREEN, if (recordingNow() != null) "Stop recording" else "Record") else null,
            if (live) PlayerOsd.Key(PlayerOsd.YELLOW, if (hlsHint) "Try HLS" else "Format: TS / HLS") else null,
            PlayerOsd.Key(PlayerOsd.BLUE, "Options"))
    }

    /** The overlay: scrims, channel / title top left, clock top right, the info block, the quick actions, the legend. */
    private fun barEvents(m: Mpv): List<String> {
        val r = req ?: return emptyList()
        val w = aw
        val side = PlayerOsd.SIDE
        val nowS = System.currentTimeMillis() / 1000
        val out = ArrayList<String>(PlayerOsd.scrims(w))
        val ch = r.live
        // top left: the channel (logo tile, name, number and group) or the title's kind; top right: the clock
        if (ch != null) {
            out += PlayerOsd.box(side, 40.0, 48.0, 48.0, 11.0, "&H8A3F6B&")
            out += PlayerOsd.text(side + 24, 64.0, ch.name.split(' ', '-').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase(), 17.0, bold = true, an = 5)
            out += PlayerOsd.text(side + 62, 47.0, ch.name, 19.0, bold = true)
            out += PlayerOsd.text(side + 62, 71.0, listOfNotNull(if (ch.number > 0) "Channel ${ch.number}" else null, app.groupLabel(ch.group).takeIf { it.isNotBlank() }).joinToString(" · "), 15.0, PlayerOsd.MUTED, .64)
        } else out += PlayerOsd.text(side, 47.0, if (r.isEpisode || r.media?.episode == true) "Episode" else if (r.recording != null) "Recording" else "Film", 15.0, PlayerOsd.MUTED, .64, bold = true)
        out += PlayerOsd.text(w - side, 41.0, io.github.kmmarin.tvbro.data.TimeFmt.time(nowS), 27.0, an = 9, light = true)
        if (recordingNow() != null) out += PlayerOsd.text(w - side, 80.0, "● REC", 15.0, PlayerOsd.REC, bold = true, an = 9)
        // the info block (§10) at y ≈ 408
        val y0 = 408.0
        if (ch != null && r.archive == null && r.recording == null) {
            val (cur, next) = app.epg.nowNext(ch, nowS)
            val (pill, pw) = PlayerOsd.livePill(side, y0)
            out += pill
            out += PlayerOsd.text(side + pw + 11, y0 + 10, cur?.let { io.github.kmmarin.tvbro.data.TimeFmt.time(it.start) + " – " + io.github.kmmarin.tvbro.data.TimeFmt.time(it.stop) +
                (io.github.kmmarin.tvbro.data.EpisodeNum.line(it.ep, it.sub)?.let { e -> " · $e" } ?: "") } ?: "No guide data", 16.0, PlayerOsd.MUTED, .64, an = 4)
            out += PlayerOsd.text(side, y0 + 30, cur?.title ?: ch.name, 48.0, bold = true)
            if (cur != null) out += PlayerOsd.scrubber(y0 + 104, w, io.github.kmmarin.tvbro.data.ProgTime.progress(cur.start, cur.stop, nowS).toDouble(),
                io.github.kmmarin.tvbro.data.TimeFmt.time(cur.start), io.github.kmmarin.tvbro.data.TimeFmt.time(cur.stop))
            val nextLine = listOfNotNull(next?.let { "Next · ${io.github.kmmarin.tvbro.data.TimeFmt.time(it.start)} ${it.title}" }, altPlaying?.let { "Playing from another source: ${it.name}" }).joinToString("   ·   ")
            if (nextLine.isNotEmpty()) out += PlayerOsd.text(side, y0 + 122, nextLine, 15.0, PlayerOsd.MUTED, .64)
            if (hlsHint) out += PlayerOsd.hintChip(w, y0 + 2, "This channel keeps buffering. Try HLS?")
        } else {
            val p = pos() ?: 0; val d = dur()
            val sub = when {
                r.archive != null -> "Catch-up · ${io.github.kmmarin.tvbro.data.TimeFmt.dayTime(r.archive.start * 1000, "EEE")}"
                r.isEpisode -> r.series!!.name + " · " + Watch.code(r.episode!!)
                r.media?.answer?.subtitle != null -> r.media.answer.title.ifBlank { null }
                else -> null
            }
            if (sub != null) out += PlayerOsd.text(side, y0 + 10, sub, 16.0, PlayerOsd.MUTED, .64, an = 4)
            val title = if (r.isEpisode) VodFmt.cleanTitle(r.series!!.name, r.episode!!.title) else r.media?.answer?.subtitle ?: r.archive?.title ?: r.title
            out += PlayerOsd.text(side, y0 + 30, title, 48.0, bold = true)
            out += PlayerOsd.scrubber(y0 + 104, w, if (d > 0) p.toDouble() / d else 0.0, Watch.clock(p), if (d > 0) "-" + Watch.clock((d - p).coerceAtLeast(0)) else "--:--")
            out += PlayerOsd.text(side, y0 + 122, if (m.get("pause") == "yes") "❚❚  Paused" else listOfNotNull(if (d > 0) "Ends ${io.github.kmmarin.tvbro.data.TimeFmt.time(nowS + (d - p) / 1000)}" else null,
                r.nextEpisode()?.let { "Next · ${Watch.code(it)}" } ?: r.media?.answer?.next?.let { "Next · ${it.title}" }).joinToString("   ·   "), 15.0, PlayerOsd.MUTED, .64)
        }
        if (menu) out += PlayerOsd.quickActions(actions(), sel)
        else out += PlayerOsd.text(side, PlayerOsd.QA_Y + 20, "Enter quick actions · ↑↓ " + (if (ch != null && r.archive == null) "channel" else "show the bar") + " · ←→ " + (if (ch != null && r.archive == null) "seek" else "10 s") + " · right click for the actions", 14.0, PlayerOsd.MUTED, .5)
        out += PlayerOsd.legend(legendKeys(), if (menu) listOf("Enter" to "Select", "Esc" to "Close") else listOf("Enter" to "Actions", "Esc" to "Back"))
        return out
    }

    private fun infoEvents(m: Mpv): List<String> {
        val r = req ?: return emptyList()
        val nowS = System.currentTimeMillis() / 1000
        val lines = ArrayList<String>()
        val ch = r.live
        val prog = if (ch != null && r.archive == null) app.epg.nowNext(ch, nowS).first else r.archive
        val desc = prog?.desc ?: r.vod?.let { app.detailCache[it.key]?.plot } ?: r.episode?.plot
        val side = PlayerOsd.SIDE
        val w = 560.0
        val descLines = desc?.let { wrap(it, 70).take(4) }.orEmpty()
        val tech = techText(m)
        val h = 50.0 + descLines.size * 20 + 12 + tech.size * 19 + 14
        lines += PlayerOsd.panel(side, 104.0, w, h)
        lines += PlayerOsd.text(side + 18, 118.0, prog?.title ?: r.title, 19.0, bold = true)
        var y = 146.0
        for (l in descLines) { lines += PlayerOsd.text(side + 18, y, l, 15.0, PlayerOsd.MUTED, .8); y += 20 }
        y += 12
        for (l in tech) { lines += PlayerOsd.text(side + 18, y, l, 14.0, PlayerOsd.MUTED, .64); y += 19 }
        return lines
    }

    private fun wrap(s: String, n: Int): List<String> {
        val out = ArrayList<String>(); val b = StringBuilder()
        for (word in s.split(Regex("\\s+"))) { if (b.length + word.length + 1 > n && b.isNotEmpty()) { out += b.toString(); b.clear() }; if (b.isNotEmpty()) b.append(' '); b.append(word) }
        if (b.isNotEmpty()) out += b.toString()
        return out
    }

    private fun upNextEvents(): List<String>? {
        val r = req ?: return null
        if (upNextAt == 0L) return null
        val label = r.nextEpisode()?.let { n -> "${Watch.code(n)} · ${VodFmt.cleanTitle(r.series!!.name, n.title)}" }
            ?: r.media?.answer?.next?.let { it.title.ifBlank { "Next episode" } } ?: return null
        val left = (Watch.UP_NEXT_COUNTDOWN_S - (System.currentTimeMillis() - upNextAt) / 1000).coerceAtLeast(0)
        val w = aw; val pw = 380.0; val x = w - PlayerOsd.SIDE - pw; val y = 300.0
        return listOf(PlayerOsd.panel(x, y, pw, 112.0),
            PlayerOsd.text(x + 18, y + 14, "UP NEXT · PLAYING IN $left S", 12.0, PlayerOsd.ACCENT, bold = true),
            PlayerOsd.text(x + 18, y + 36, label, 20.0, bold = true),
            PlayerOsd.text(x + 18, y + 72, "Enter play now · Esc cancel", 14.0, PlayerOsd.MUTED, .64))
    }

    fun redraw(m0: Mpv? = mpv) {
        val m = m0 ?: return
        // the reconnect notice: a small label in the top right corner, also in the TV screen's preview
        m.overlay(5, notice.takeIf { it.isNotEmpty() }?.let { "{\\an9\\fs${if (app.full) 18 else 40}\\bord2\\shad0\\1c&HFFFFFF&\\3c&H000000&\\pos(${(aw - 16).toInt()},${if (app.full) 96 else 16})}" + PlayerOsd.esc(it) })
        if (!app.full) { m.overlay(1, null); m.overlay(2, null); m.overlay(3, null); m.overlay(4, null); return }
        val bar = barShown || menu
        m.overlay(1, if (info) infoEvents(m).joinToString("\n") else null)
        m.overlay(2, null)
        m.overlay(3, if (bar) barEvents(m).joinToString("\n") else null)
        m.overlay(4, upNextEvents()?.joinToString("\n"))
    }

    fun mouse(e: MouseEvent) = click(e.button, e.x, e.y, e.clickCount)

    /** A click on the video at [x], [y] (in the units of [surface]) with AWT mouse [button]. */
    fun click(button: Int, x: Int, y: Int, count: Int) {
        if (!app.full) { if (button == MouseEvent.BUTTON1) app.playing?.live?.let { app.playChannel(it) }; return }
        lastInput = System.currentTimeMillis()
        if (button == MouseEvent.BUTTON1 && menu && vw > 0 && vh > 0) {
            // a click on a quick action runs it (the OSD is 720 high, as wide as the surface's aspect)
            val i = PlayerOsd.qaAt(x * aw / vw, y * PlayerOsd.H / vh, actions().size)
            if (i >= 0) { sel = i; act(actions()[i].id, 1); showBar(); return }
        }
        when {
            button == MouseEvent.BUTTON3 -> { menu = !menu; sel = 0; showBar() }
            count >= 2 -> app.fullscreen = !app.fullscreen
            else -> showBar()
        }
    }

    /** The colour keys F1 to F4 (§9 player row): Favourite, Record, Format (or yes to "Try HLS?"), Options. */
    private fun colorKey(k: Int) {
        val r = req ?: return
        val ch = r.live?.takeIf { r.archive == null && r.recording == null }
        when (k) {
            1 -> act("fav", 1)
            2 -> if (ch != null) toggleRec()
            3 -> if (ch != null) { if (hlsHint) { hlsHint = false; app.setChannelFormat(ch, io.github.kmmarin.tvbro.data.ChannelFormats.HLS) } else app.cycleFormat(ch) }
            4 -> if (ch != null) app.channelMenu(ch) else (r.series ?: r.vod)?.let { app.vodMenu(it) } ?: run { menu = !menu; sel = 0 }
        }
    }

    /** TV-remote style keys while the player fills the window. Returns true when handled. */
    fun key(e: KeyEvent): Boolean {
        val m = mpv
        val r = req
        val live = r?.live != null && r.archive == null
        lastInput = System.currentTimeMillis()
        if (upNextAt > 0) when (e.keyCode) {
            KeyEvent.VK_ENTER -> { upNextAt = 0; playNext(); return true }
            KeyEvent.VK_ESCAPE, KeyEvent.VK_BACK_SPACE -> { upNextAt = 0; upNextCancelled = true; m?.let { redraw(it) }; return true }
        }
        if (live && e.keyCode in KeyEvent.VK_0..KeyEvent.VK_9 || live && e.keyCode in KeyEvent.VK_NUMPAD0..KeyEvent.VK_NUMPAD9) {
            val dgt = if (e.keyCode >= KeyEvent.VK_NUMPAD0) e.keyCode - KeyEvent.VK_NUMPAD0 else e.keyCode - KeyEvent.VK_0
            digits = (digits + dgt).takeLast(4); osd("Channel $digits")
            digitJob?.cancel(); digitJob = scope.launch { delay(1500); val n = digits.toIntOrNull(); digits = ""; if (n != null && !app.zapNumber(n)) osd("No channel $n") }
            return true
        }
        val acts = if (menu) actions() else emptyList()
        if (menu) sel = sel.coerceIn(0, (acts.size - 1).coerceAtLeast(0))
        when (e.keyCode) {
            KeyEvent.VK_ESCAPE, KeyEvent.VK_BACK_SPACE -> if (menu) menu = false else if (info) info = false else { saveResume(); app.leavePlayer() }
            KeyEvent.VK_ENTER -> if (menu) acts.getOrNull(sel)?.let { act(it.id, 1) } else { menu = true; sel = 0 }
            KeyEvent.VK_UP -> if (menu) acts.getOrNull(sel)?.let { if (it.id in STEPPED) act(it.id, -1) } else if (live) app.zap(-1) else showBar()
            KeyEvent.VK_DOWN -> if (menu) acts.getOrNull(sel)?.let { if (it.id in STEPPED) act(it.id, 1) } else if (live) app.zap(1) else { menu = true; sel = 0 }
            KeyEvent.VK_PAGE_UP -> if (live) app.zap(-1) else m?.cmd("seek", "60")
            KeyEvent.VK_PAGE_DOWN -> if (live) app.zap(1) else m?.cmd("seek", "-60")
            KeyEvent.VK_LEFT -> if (menu) sel = (sel - 1).coerceAtLeast(0) else m?.cmd("seek", "-10")
            KeyEvent.VK_RIGHT -> if (menu) sel = (sel + 1).coerceAtMost(acts.size - 1) else m?.cmd("seek", "10")
            KeyEvent.VK_SPACE, KeyEvent.VK_P -> m?.cmd("cycle", "pause")
            KeyEvent.VK_I -> info = !info
            KeyEvent.VK_M, KeyEvent.VK_CONTEXT_MENU -> { menu = !menu; sel = 0 }
            KeyEvent.VK_R -> toggleRec()
            KeyEvent.VK_S -> cycleTrack("sub", true)
            KeyEvent.VK_A -> cycleTrack("audio", false)
            KeyEvent.VK_N -> if (r != null && (r.isEpisode || r.media?.episode == true)) { if (hasNext(r)) playNext() else osd("Last episode") }
            KeyEvent.VK_B -> if (r?.isEpisode == true) playPrev()
            KeyEvent.VK_W -> app.dueReminder?.let { app.reminderDone(it, true) }
            KeyEvent.VK_Y -> act("fav", 1)
            KeyEvent.VK_F1 -> colorKey(1)
            KeyEvent.VK_F2 -> colorKey(2)
            KeyEvent.VK_F3 -> colorKey(3)
            KeyEvent.VK_F4 -> colorKey(4)
            KeyEvent.VK_F, KeyEvent.VK_F11 -> app.fullscreen = !app.fullscreen
            KeyEvent.VK_OPEN_BRACKET -> act("delay", -1)
            KeyEvent.VK_CLOSE_BRACKET -> act("delay", 1)
            else -> return false
        }
        if (app.full) showBar()
        return true
    }

    private companion object {
        /** Quick actions whose value Up / Down step (the others only run on Enter). */
        val STEPPED = setOf("sleep", "quality", "delay", "aspect")
        /** [aspects] index of "Fit, crop black bars". */
        const val CROP = 1
    }
}
