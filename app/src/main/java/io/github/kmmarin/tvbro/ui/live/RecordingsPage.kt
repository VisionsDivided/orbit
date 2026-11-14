// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kmmarin.tvbro.AppModel
import io.github.kmmarin.tvbro.data.DlRepo
import io.github.kmmarin.tvbro.data.RecRepo
import io.github.kmmarin.tvbro.data.SeriesRules
import io.github.kmmarin.tvbro.ui.MenuState
import io.github.kmmarin.tvbro.ui.orbit5.CardInfo
import io.github.kmmarin.tvbro.ui.orbit5.CardSize
import io.github.kmmarin.tvbro.ui.orbit5.O5
import io.github.kmmarin.tvbro.ui.orbit5.O5Button
import io.github.kmmarin.tvbro.ui.orbit5.O5Row
import io.github.kmmarin.tvbro.ui.orbit5.O5Shape
import io.github.kmmarin.tvbro.ui.orbit5.O5Space
import io.github.kmmarin.tvbro.ui.orbit5.O5Type
import io.github.kmmarin.tvbro.ui.orbit5.PosterCard
import io.github.kmmarin.tvbro.ui.orbit5.o5Focus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** One recording as its card shows it. [status]: "scheduled", "recording", "done" or "failed". */
@Immutable
data class RecCardData(
    val id: String, val title: String, val channel: String, val logo: String?, val time: String, val size: String,
    val status: String, val note: String = "", val inList: Boolean = false, val progress: Float? = null,
)

/** A series recording rule ("every <title> on <channel>"). */
@Immutable
data class RuleData(val id: String, val title: String, val channel: String, val logo: String?)

/** A download (film or episode) as its poster card shows it. */
@Immutable
data class DlCardData(val id: String, val key: String, val title: String, val poster: String?, val meta: String, val progress: Float?)

/** What the Recordings page does; ids are the recording, rule or download id. */
class RecordingsActions(
    val onRecordNow: () -> Unit = {},
    val onRec: (String) -> Unit = {}, val onRecLong: (String) -> Unit = {}, val onRecFocus: (String) -> Unit = {},
    val onRule: (String) -> Unit = {},
    val onDl: (String) -> Unit = {}, val onDlLong: (String) -> Unit = {},
)

/**
 * Live TV ▸ Recordings (direction 5), bound to the app: Record now for the channel playing, recordings in progress,
 * finished ones as cards, the scheduled list with series rules, and downloads. Everything the old page did: OK plays a
 * finished recording, stops a running one, cancels a scheduled one, deletes a failed one (after a yes); long press
 * (Menu) gives My list and Delete; yellow toggles My list; series rules cancel or check the guide; downloads play,
 * pause, resume or cancel.
 */
@Composable
fun RecordingsSection(m: AppModel) {
    val storage = remember(m.storageTick) { m.storageLabel() }
    val logos = remember(m.channels) { m.channels.associate { it.name to it.logo } + m.channels.associate { it.key to it.logo } }
    val all = RecRepo.items.sortedByDescending { it.startMs }
    val nowMs = System.currentTimeMillis()
    fun card(r: io.github.kmmarin.tvbro.data.Rec): RecCardData {
        val bytes = RecRepo.progress[r.id] ?: File(r.path).takeIf { r.path.isNotEmpty() }?.length() ?: 0L
        return RecCardData(
            r.id, r.title, r.channel, logos[r.chKey] ?: logos[r.channel], recTime(r.startMs, r.endMs), sizeLabel(bytes), r.status, r.note,
            "r|${r.id}" in m.favourites,
            if (r.status == "recording" && r.endMs > r.startMs) ((nowMs - r.startMs).toFloat() / (r.endMs - r.startMs)).coerceIn(0f, 1f) else null,
        )
    }
    val byId = all.associateBy { it.id }
    val confirmDelete = { r: io.github.kmmarin.tvbro.data.Rec ->
        m.menu = MenuState("Delete “${r.title}”?", listOf("Delete recording and file" to { m.deleteRec(r) }, "Keep" to {}))
    }
    val dls = DlRepo.items.sortedBy { it.title }
    val c = m.current
    val active = c?.let { m.activeRec(it) }
    RecordingsView(
        storage = "Saving to $storage (minimum ${m.store.recMinFreeMb} MB free) · change in Settings ▸ Recording",
        recordNow = c?.let { if (active != null) "Stop recording ${it.name}" else "Record ${it.name} now" },
        recording = all.filter { it.status == "recording" }.map(::card),
        recorded = all.filter { it.status == "done" || it.status == "failed" }.map(::card),
        scheduled = all.filter { it.status == "scheduled" }.sortedBy { it.startMs }.map(::card),
        rules = SeriesRules.items.map { RuleData(it.id, it.title, it.channel, logos[it.chKey] ?: logos[it.channel]) },
        downloads = dls.map { d ->
            val have = DlRepo.progress[d.id] ?: File(d.path).takeIf { it.exists() }?.length() ?: 0L
            val running = d.status == "downloading" || d.status == "queued"
            val pct = if (d.total > 0) (have * 100 / d.total).toInt() else null
            DlCardData(d.id, d.key, d.title, null,
                (when (d.status) { "done" -> "Downloaded"; "downloading" -> "Downloading"; "queued" -> "Waiting"; "paused" -> "Paused"; else -> d.status.replaceFirstChar { it.uppercase() } }) +
                    (if (pct != null && d.status != "done") " $pct%" else "") + " · ${sizeLabel(have)}" + if (d.note.isNotEmpty()) " · ${d.note}" else "",
                if (running && d.total > 0) have.toFloat() / d.total else null)
        },
        poster = { key -> rememberSnapPoster(m, key) },
        actions = RecordingsActions(
            onRecordNow = { val ch = m.current ?: return@RecordingsActions; val a = m.activeRec(ch); if (a != null) m.stopRec(a) else m.recordNow(ch, 0) },
            onRec = { id ->
                val r = byId[id] ?: return@RecordingsActions
                when (r.status) {
                    "done" -> m.playRec(r)
                    "recording" -> { m.stopRec(r); m.toast("Recording stopped") }
                    "scheduled" -> { m.stopRec(r); m.toast("Recording cancelled: ${r.title}") }
                    else -> confirmDelete(r)
                }
            },
            onRecLong = { id ->
                val r = byId[id] ?: return@RecordingsActions
                m.menu = MenuState(r.title, listOf(
                    (if ("r|${r.id}" in m.favourites) "Remove from My list" else "Add to My list") to { m.toggleFavRec(r) },
                    "Delete" to { confirmDelete(r) },
                ))
            },
            onRecFocus = { id -> byId[id]?.let { r -> m.favAction = { m.toggleFavRec(r) } } },
            onRule = { id ->
                val r = SeriesRules.items.firstOrNull { it.id == id } ?: return@RecordingsActions
                m.menu = MenuState(r.title, listOf("Cancel series recording" to { m.cancelSeries(r) }, "Check the guide now" to { m.scheduleSeries() }))
            },
            onDl = { id ->
                val d = dls.firstOrNull { it.id == id } ?: return@RecordingsActions
                when (d.status) { "done" -> m.playLocal(d); "downloading", "queued" -> m.pauseDl(d); else -> m.resumeDl(d) }
            },
            onDlLong = { id ->
                val d = dls.firstOrNull { it.id == id } ?: return@RecordingsActions
                val running = d.status == "downloading" || d.status == "queued"
                m.menu = MenuState(d.title, listOf(
                    (if (running) "Pause" else "Resume") to { if (running) m.pauseDl(d) else m.resumeDl(d) },
                    "Cancel and delete" to { m.cancelDl(d) },
                ))
            },
        ),
    )
}

/** A film's or episode's poster from its stored title snapshot (read off the main thread). */
@Composable
private fun rememberSnapPoster(m: AppModel, key: String): String? {
    val p by produceState<String?>(null, key) { value = withContext(Dispatchers.IO) { runCatching { m.store.snaps(listOf(key)).firstOrNull()?.poster }.getOrNull() } }
    return p
}

/** "Tue 21:30 – 23:00" (the end only when known). */
fun recTime(startMs: Long, endMs: Long): String =
    io.github.kmmarin.tvbro.data.TimeFmt.dayTime(startMs) + if (endMs > startMs) " – " + io.github.kmmarin.tvbro.data.TimeFmt.time(endMs / 1000) else ""

/** "820 MB", "1.4 GB", "" for nothing yet. */
fun sizeLabel(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    else -> "${bytes shr 20} MB"
}

private val Heading = O5Type.RowHeading

/** The Recordings page without the app model (the shot tests draw it). Under the sub-menu row. */
@Composable
fun RecordingsView(
    storage: String, recordNow: String?, recording: List<RecCardData>, recorded: List<RecCardData>, scheduled: List<RecCardData>,
    rules: List<RuleData>, downloads: List<DlCardData>, actions: RecordingsActions, poster: @Composable (String) -> String? = { null },
) {
    LazyColumn(Modifier.fillMaxSize().testTag("recordings"), contentPadding = PaddingValues(top = O5Space.ContentUnderSubMenu - 10.dp, bottom = 140.dp)) {
        item("head") {
            Row(Modifier.padding(start = O5Space.Side, end = O5Space.Side, top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                if (recordNow != null) O5Button((if (recordNow.startsWith("Stop")) "■  " else "●  ") + recordNow, compact = true, primary = false, onClick = actions.onRecordNow)
                Text(storage, style = O5Type.Body.copy(fontSize = 20.sp), color = O5.Muted, maxLines = 2, modifier = Modifier.widthIn(max = 900.dp))
            }
        }
        if (recording.isNotEmpty()) item("rec-now") { RecRow("Recording now", recording, actions) }
        item("recorded") {
            if (recorded.isNotEmpty()) RecRow("Recordings", recorded, actions)
            else Empty("Recordings", "No recordings yet. Press Green on a programme in the guide, or Record in the player.")
        }
        item("sched-head") {
            Text("Scheduled", style = Heading, modifier = Modifier.padding(start = O5Space.Side, top = 18.dp, bottom = 10.dp))
            if (scheduled.isEmpty() && rules.isEmpty()) Text("Nothing scheduled.", style = O5Type.Body.copy(fontSize = 20.sp), color = O5.Muted,
                modifier = Modifier.padding(start = O5Space.Side, bottom = 12.dp))
        }
        items(scheduled, key = { "s" + it.id }) { r ->
            ListRow(r.logo, r.channel, r.title, r.time + " · " + r.channel, "Scheduled", onFocus = { actions.onRecFocus(r.id) },
                onLong = { actions.onRecLong(r.id) }, mark = if (r.inList) "★" else null) { actions.onRec(r.id) }
        }
        items(rules, key = { "rule" + it.id }) { r ->
            ListRow(r.logo, r.channel, "Every “${r.title}”", "Series recording on ${r.channel} · new airings are scheduled 48 h ahead", "Series") { actions.onRule(r.id) }
        }
        item("downloads") {
            if (downloads.isEmpty()) Empty("Downloads", "No downloads. Use Download on a movie page or Download season on a show (Wi-Fi or Ethernet only).")
            else O5Row("Downloads", Modifier.padding(top = 30.dp), gap = O5Space.RowGapPoster) {
                items(downloads, key = { it.id }) { d ->
                    PosterCard(d.poster ?: poster(d.key), CardInfo(d.title, d.meta, progress = d.progress), width = CardSize.RowPosterW, height = CardSize.RowPosterH,
                        onLongClick = { actions.onDlLong(d.id) }) { actions.onDl(d.id) }
                }
            }
        }
    }
}

@Composable
private fun RecRow(title: String, list: List<RecCardData>, a: RecordingsActions) {
    O5Row(title, Modifier.padding(top = 18.dp)) {
        items(list, key = { it.id }) { r ->
            ChannelArtCard(
                r.logo, r.channel, r.title, listOf(r.time, r.size).filter { it.isNotEmpty() }.joinToString(" · "),
                width = 400.dp, height = 225.dp, label = r.channel, progress = r.progress, mark = if (r.inList) "★" else null,
                badge = {
                    when (r.status) {
                        "recording" -> CardPill("● REC", O5.Live)
                        "failed" -> CardPill("Failed" + if (r.note.isNotEmpty()) " · ${r.note.take(24)}" else "", Color(0xE6B4561A))
                        "scheduled" -> CardPill("Scheduled", Color(0x99000000))
                        else -> {}
                    }
                },
                onFocus = { a.onRecFocus(r.id) }, onLongClick = { a.onRecLong(r.id) },
            ) { a.onRec(r.id) }
        }
    }
}

@Composable
private fun Empty(title: String, text: String) {
    Column(Modifier.padding(start = O5Space.Side, top = 26.dp, bottom = 8.dp)) {
        Text(title, style = Heading)
        Text(text, style = O5Type.Body.copy(fontSize = 20.sp), color = O5.Muted, modifier = Modifier.padding(top = 10.dp))
    }
}

/** A scheduled recording or series rule: logo, title, when and where, a tag; OK acts, long press opens its menu. */
@Composable
private fun ListRow(logo: String?, channel: String, title: String, meta: String, tag: String, onFocus: (() -> Unit)? = null, onLong: (() -> Unit)? = null,
                    mark: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.padding(start = O5Space.Side, top = 6.dp, bottom = 6.dp).width(1180.dp)
            .o5Focus(O5Shape.Button, 12.dp, scaleTo = 1.02f, onFocus = onFocus, onLongClick = onLong, onClick = onClick)
            .background(Color(0x0FFFFFFF), O5Shape.Button).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        ChannelTile(logo, channel, 52.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = O5Type.CardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(meta, style = O5Type.CardMeta, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        mark?.let { Text(it, color = O5.Rating, fontSize = 22.sp) }
        Box(Modifier.background(Color(0x1FFFFFFF), O5Shape.Pill).padding(horizontal = 14.dp, vertical = 5.dp)) {
            Text(tag, color = O5.Text, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1)
        }
    }
}
