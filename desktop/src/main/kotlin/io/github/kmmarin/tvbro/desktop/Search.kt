// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import io.github.kmmarin.tvbro.data.*
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/** SqlDb over JDBC SQLite (sqlite-jdbc bundles the native library for Windows x64 and Linux arm64). */
class JdbcDb(val c: Connection) : SqlDb {
    private fun bind(st: java.sql.PreparedStatement, a: Array<out Any?>) = a.forEachIndexed { i, v -> st.setObject(i + 1, v) }
    override fun exec(sql: String, args: Array<Any?>) { c.prepareStatement(sql).use { bind(it, args); it.execute() } }
    override fun query(sql: String, args: Array<String>, row: (SqlRow) -> Unit) {
        c.prepareStatement(sql).use { st ->
            bind(st, args)
            st.executeQuery().use { rs -> val r = object : SqlRow { override fun str(i: Int) = rs.getString(i + 1); override fun long(i: Int) = rs.getLong(i + 1) }; while (rs.next()) row(r) }
        }
    }
    override fun prepare(sql: String): SqlStmt { val st = c.prepareStatement(sql); return object : SqlStmt { override fun insert(vararg a: Any?): Long { bind(st, a); st.executeUpdate(); return 0 } } }
    override fun begin() { c.autoCommit = false }
    override fun commit() { c.commit(); c.autoCommit = true }
}

/**
 * Full-text search index (shared SearchIndex: FTS4, prefix per word, diacritics folded, ranked) in search.db, rebuilt
 * after each load into a new file and swapped in atomically. Queries and builds run on one background thread.
 */
object SearchStore {
    private val file = File(Paths.cache, "search.db")
    private var db: JdbcDb? = null
    private val lock = Any()

    fun rebuild(channels: List<Channel>, vod: List<VodItem>, guide: Map<String, Array<Programme>>) = synchronized(lock) {
        val t = System.currentTimeMillis()
        val tmp = File(Paths.cache, "search.db.new"); tmp.delete()
        try {
            DriverManager.getConnection("jdbc:sqlite:" + tmp.path).use { c ->
                val d = JdbcDb(c)
                SearchIndex.create(d)
                val w = SearchIndex.Writer(d)
                for (ch in channels) w.channel(ch.key, ch.name, ch.sourceId)
                for (v in vod) w.vod(v)
                for ((k, a) in guide) for (p in a) w.programme(p.title, k, p.start, p.stop, p.sub, p.ep)
                w.finish(t)
            }
            db?.c?.close(); db = null
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            Perms.file(file) // SQLite creates it with the umask
            Diag.log("search index: ${channels.size} channels, ${vod.size} titles, built in ${System.currentTimeMillis() - t} ms")
        } catch (e: Exception) { Diag.log("search index build failed (${e.javaClass.simpleName}: ${e.message})"); tmp.delete() }
    }

    fun search(q: String, pastFrom: Long): SearchResult = synchronized(lock) {
        try {
            val d = db ?: (if (file.exists()) JdbcDb(DriverManager.getConnection("jdbc:sqlite:" + file.path)).also { db = it } else return SearchResult.EMPTY)
            SearchIndex.search(d, q, pastFrom)
        } catch (e: Exception) { Diag.log("search failed (${e.javaClass.simpleName})"); SearchResult.EMPTY }
    }
}

/**
 * Search in direction 5: a large pill field under the top bar, recent searches as chips, then rows of results
 * (films and shows as posters, channels as tiles, programmes as guide cells). Enter keeps the query in the history.
 */
@Composable
fun SearchScreen(app: AppState) {
    var q by remember { mutableStateOf(app.searchQ) }
    LaunchedEffect(q) { app.searchQ = q }
    var res by remember { mutableStateOf(SearchResult.EMPTY) }
    LaunchedEffect(q, app.channels) {
        if (Fold.text(q).length < 2) { res = SearchResult.EMPTY; return@LaunchedEffect }
        delay(250) // debounce typing
        res = withContext(Dispatchers.IO) { SearchStore.search(q, System.currentTimeMillis() / 1000 - 8 * 86400) }
    }
    val keep = { app.update { it.copy(searchHistory = SearchHistory.add(it.searchHistory, q)) } }
    val sh = app.settings.epgShiftMin * 60L
    val byEpg = remember(app.shown) { val m = HashMap<String, Channel>(); for (c in app.shown) { m[c.nameKey] = c; c.idKey?.let { m[it] = c } }; m }
    val byKey = remember(app.shown) { app.shown.associateBy { it.key } }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { field.settle(5) }
    LaunchedEffect(Unit) { app.colorKeys = ColorKeys(hints = listOf("Enter" to "Keep in history", "↓" to "Results", "Esc" to "Top bar")) }
    Column(Modifier.fillMaxSize()) {
        var focused by remember { mutableStateOf(false) }
        Row(Modifier.widthIn(max = 900.dp).fillMaxWidth().height(54.dp).background(if (focused) Color(0x24FFFFFF) else O5.chip, CircleShape)
            .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else O5.chipRing, CircleShape).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            SearchGlyph(22.dp, true)
            Box(Modifier.weight(1f)) {
                if (q.isEmpty()) Text2("Search channels, programmes, films and shows", 18, O5.muted)
                BasicTextField(q, { q = it }, Modifier.fillMaxWidth().focusRequester(field).onFocusChanged { typing = it.isFocused; focused = it.isFocused }
                    .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) { keep(); true } else false },
                    singleLine = true, textStyle = TextStyle(color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold), cursorBrush = SolidColor(O5.accent))
            }
            if (q.isNotEmpty()) Box(Modifier.tvItem { q = "" }.padding(8.dp, 4.dp)) { Text2("✕", 16, O5.muted, FontWeight.Bold) }
        }
        Spacer(Modifier.height(14.dp))
        if (Fold.text(q).length < 2) {
            val h = app.settings.searchHistory
            if (h.isNotEmpty()) {
                SHead("Recent searches")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                    items(h) { s -> Pill(s, false, onClick = { q = s }) }
                    item { Pill("Clear", false) { app.update { it.copy(searchHistory = emptyList()) } } }
                }
            } else Text2("Type at least two letters. Results come from the playlists' channels, the guide and the film and show catalogues.", 14, O5.muted, maxLines = 2)
            return@Column
        }
        if (res.isEmpty) { Text2(if (app.busy) "The index is being built…" else "No results for \"$q\"", 16, O5.muted, modifier = Modifier.padding(top = 10.dp)); return@Column }
        val now = System.currentTimeMillis() / 1000
        val progs = remember(res) {
            ProgGroups.group(res.programmes.map { it.copy(start = it.start + sh, stop = it.stop + sh) }, { byEpg[it] }, { c, h -> ProgTime.classify(c, h.start, h.stop, now) }, { it.key })
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 60.dp)) {
            if (res.movies.isNotEmpty()) item {
                ResultHead("Films", res.movies.size)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(res.movies, key = { it.key }) { h -> val v = h.toVod(); CineCard(v, app, 104.dp) { keep(); app.detail = v } }
                }
            }
            if (res.series.isNotEmpty()) item {
                ResultHead("Shows", res.series.size)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(res.series, key = { it.key }) { h -> val v = h.toVod(); CineCard(v, app, 104.dp) { keep(); app.detail = v } }
                }
            }
            val chs = res.channels.mapNotNull { byKey[it.key] }
            if (chs.isNotEmpty()) item {
                ResultHead("Channels", chs.size)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(chs, key = { it.key }) { c ->
                        val cur = app.epg.nowNext(c, now).first
                        Column(Modifier.width(200.dp).tvItem(onMenu = { app.channelMenu(c) }) { keep(); app.playChannel(c) }
                            .background(Color(0x12FFFFFF), RoundedCornerShape(10.dp)).padding(12.dp, 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ChannelLogo(c, 30.dp); Text2(c.name, 14, Color.White, FontWeight.Bold)
                            }
                            Text2(cur?.title ?: "No guide data", 12, O5.muted)
                            if (cur != null) CellProgress(ProgTime.progress(cur.start, cur.stop, now))
                        }
                    }
                }
            }
            if (progs.isNotEmpty()) item { ResultHead("Programmes", progs.sumOf { it.second.size }) }
            items(progs, key = { "p" + it.first.key }) { (c, hits) ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { ChannelLogo(c, 22.dp); Text2(c.name, 13, O5.muted, FontWeight.SemiBold) }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                        items(hits.take(20)) { h ->
                            val p = Programme(c.idKey ?: c.nameKey, h.start, h.stop, h.title, null, h.sub, h.ep)
                            val st = ProgTime.classify(c, h.start, h.stop, now)
                            EpgCellBox(Modifier.width(250.dp).height(62.dp), st == ProgState.NOW, onMenu = { keep(); app.programmeMenu(c, p) }, onClick = {
                                keep()
                                when (st) { ProgState.NOW -> app.playChannel(c); ProgState.PAST_CATCHUP -> app.playArchive(c, p); else -> app.programmeMenu(c, p) }
                            }) { f ->
                                Text2(h.title, 14, if (f) O5.bg else Color.White, FontWeight.SemiBold)
                                val line = when (st) {
                                    ProgState.NOW -> "On now · ${ProgTime.minutesLeft(h.stop, now)} min left"
                                    ProgState.PAST_CATCHUP -> "⟲ Catch-up · ${TimeFmt.dayTime(h.start * 1000, "EEE")}"
                                    else -> TimeFmt.dayTime(h.start * 1000, "EEE d MMM") + if (app.reminderFor(c, p) != null) "  ⏰" else ""
                                } + (EpisodeNum.line(h.ep, h.sub)?.let { " · $it" } ?: "")
                                Text2(line, 11, if (f) Color(0xB305060C) else if (st == ProgState.NOW) O5.live else O5.muted)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultHead(title: String, n: Int) = Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    Text2(title, 18, Color.White, FontWeight.Bold); Text2("$n", 13, O5.muted, FontWeight.SemiBold)
}
