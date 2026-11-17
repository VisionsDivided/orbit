// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.orbit

import android.media.MediaCodecList
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import io.github.kmmarin.tvbro.AppModel
import io.github.kmmarin.tvbro.Screen
import io.github.kmmarin.tvbro.VodPlay
import io.github.kmmarin.tvbro.data.Diag
import io.github.kmmarin.tvbro.data.Net
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient

/**
 * A Plex / Jellyfin film or episode playing on the TV through the home server (VodPlay.media). [startSecs]: where it
 * starts (the resume point, 0 from the start); [hlsOnly]: the HLS fallback after the original file did not play.
 */
data class MediaPlay(
    val conn: String, val id: String, val source: String, val episode: Boolean, val p: MediaPlayAnswer,
    val startSecs: Long, val hlsOnly: Boolean = false,
)

/**
 * Plays Plex / Jellyfin titles with the TV's own VOD player (`POST /v1/media/play`, docs/orbit/API-v1.md "Playback"):
 * the original file when the server can (direct play), else HLS; the relay needs the session cookie and nothing else,
 * so no Plex or Jellyfin token reaches the TV. Media-server playback takes no IPTV stream slot.
 */
object MediaPlayback {
    /** Bumped when a media title stops playing: title pages load their resume points and marks again. */
    var rev by mutableIntStateOf(0)
    /** A play request is on its way (the title page shows it on the button). */
    var starting by mutableStateOf(false)
        private set

    /** The VOD player's key for [conn] / [id] ("m|…" a film, "e|…" an episode). */
    fun key(conn: String, id: String, episode: Boolean) = (if (episode) "e|" else "m|") + conn + "|" + id

    /**
     * Asks the home server for film / episode [id] of [conn] and opens the player. [fromStart] ignores the resume point;
     * [hlsOnly] asks for HLS (fallback); [atSecs] starts there instead (the fallback goes on where it stopped).
     */
    fun play(m: AppModel, conn: String, id: String, source: String, episode: Boolean, fromStart: Boolean = false,
             hlsOnly: Boolean = false, atSecs: Long? = null, onFail: ((String) -> Unit)? = null) {
        val link = m.orbit.link ?: run { m.toast("Sign in to Orbit to play titles from ${name(source)}"); return }
        if (starting) return
        starting = true
        m.viewModelScope.launch {
            try {
                val p = withContext(Dispatchers.IO) {
                    val api = MediaApi(OrbitApi())
                    api.play(link, conn, id, "tv", if (hlsOnly) MediaApi.HLS_ONLY else tvCaps(), api.speedKbps(link))
                }
                val start = atSecs ?: MediaApi.startAt(p.resumeSecs, p.durationSecs, fromStart)
                val title = listOfNotNull(p.title.ifBlank { null }, p.subtitle).joinToString("  ·  ").ifEmpty { name(source) }
                m.detailMsg = ""
                m.vodPlay = VodPlay(key(conn, id, episode), p.url, title, MediaPlay(conn, id, source, episode, p, start, hlsOnly))
                m.screen = Screen.VODPLAY
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                val pr = MediaApi.problem(e, name(source))
                Diag.log("media play: ${pr.kind} (${(e as? OrbitError)?.code ?: e.javaClass.simpleName})")
                m.detailMsg = pr.message
                m.toast(pr.message)
                onFail?.invoke(pr.message)
            } finally { starting = false }
        }
    }

    /** Reports progress of [mp] (fire and forget; a failed report is only logged). */
    fun report(m: AppModel, mp: MediaPlay, positionMs: Long, state: String) {
        val link = m.orbit.link ?: return
        m.viewModelScope.launch(Dispatchers.IO) {
            try { MediaApi(OrbitApi()).progress(link, mp.p.session, positionMs / 1000, state) }
            catch (e: CancellationException) { throw e } catch (e: Exception) { Diag.log("media progress: ${(e as? OrbitError)?.code ?: e.javaClass.simpleName}") }
        }
    }

    /**
     * The HTTP client the player loads [link]'s relay with: the session cookie only on the home server's own addresses,
     * and no redirects (the cookie would follow them).
     */
    fun client(link: OrbitLink?): OkHttpClient = Net.client.newBuilder().followRedirects(false).followSslRedirects(false)
        .addInterceptor { chain ->
            val r = chain.request()
            val h = MediaApi.streamHeaders(link, r.url.toString())
            chain.proceed(if (h == null) r else r.newBuilder().apply { h.forEach { (k, v) -> header(k, v) } }.build())
        }.build()

    /** What a playback error of the player means for the viewer. */
    fun playerError(codeName: String, source: String): String = when {
        codeName.contains("NETWORK") || codeName.contains("TIMEOUT") -> "The Orbit home server stopped answering"
        codeName.contains("BAD_HTTP_STATUS") -> "The home server ended this playback (${name(source)} may have stopped it). Try again."
        codeName.contains("DECOD") || codeName.contains("FORMAT") || codeName.contains("PARSING") -> "This title's format cannot be played on this TV"
        else -> "Playback error ($codeName)"
    }

    fun name(source: String) = when (source) { "plex" -> "Plex"; "jellyfin" -> "Jellyfin"; else -> "the media server" }

    /**
     * What this TV decodes, for the server's direct-play choice (`caps`): its video and audio decoders. ExoPlayer reads
     * MP4, Matroska / WebM and MPEG-TS itself.
     */
    private var caps: JsonObject? = null
    private fun tvCaps(): JsonObject = caps ?: run {
        val video = LinkedHashSet<String>(); val audio = LinkedHashSet<String>()
        try {
            for (c in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (c.isEncoder) continue
                for (t in c.supportedTypes) CODECS[t.lowercase()]?.let { if (t.startsWith("video/")) video += it else audio += it }
            }
        } catch (e: Exception) { Diag.log("media caps: ${e.javaClass.simpleName}") }
        fun arr(s: Collection<String>) = JsonArray(s.map { JsonPrimitive(it) })
        JsonObject(mapOf(
            "directPlay" to JsonPrimitive(true),
            "containers" to arr(listOf("mp4", "m4v", "mov", "mkv", "webm", "ts", "mpegts")),
            "videoCodecs" to arr(video), "audioCodecs" to arr(audio),
        )).also { caps = it }
    }

    private val CODECS = mapOf(
        "video/avc" to "h264", "video/hevc" to "hevc", "video/x-vnd.on2.vp9" to "vp9", "video/x-vnd.on2.vp8" to "vp8",
        "video/av01" to "av1", "video/mpeg2" to "mpeg2video", "video/mp4v-es" to "mpeg4", "video/dolby-vision" to "dovi",
        "audio/mp4a-latm" to "aac", "audio/ac3" to "ac3", "audio/eac3" to "eac3", "audio/eac3-joc" to "eac3", "audio/vnd.dts" to "dts",
        "audio/vnd.dts.hd" to "dts", "audio/true-hd" to "truehd", "audio/opus" to "opus", "audio/flac" to "flac",
        "audio/mpeg" to "mp3", "audio/vorbis" to "vorbis", "audio/raw" to "pcm",
    )
}
