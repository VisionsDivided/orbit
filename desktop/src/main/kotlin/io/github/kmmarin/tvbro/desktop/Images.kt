// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/** Logos and posters: small in-memory LRU plus a disk cache; 4 downloads at a time. */
object Images {
    private val mem = object : LinkedHashMap<String, ImageBitmap>(256, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, ImageBitmap>) = size > 400
    }
    private val failed = HashSet<String>()
    private val gate = Semaphore(4)
    private val dir = Perms.dir(File(Paths.cache, "img"))

    fun cached(url: String, maxW: Int = 400) = synchronized(mem) { mem[key(url, maxW)] }
    private fun key(url: String, maxW: Int) = if (maxW == 400) url else "$url@$maxW"

    /** [maxW]: wider images are scaled down to this (posters 400; cinematic backdrops larger). */
    suspend fun load(url: String, maxW: Int = 400): ImageBitmap? {
        cached(url, maxW)?.let { return it }
        if (synchronized(failed) { url in failed }) return null
        return withContext(Dispatchers.IO) {
            gate.withPermit {
                try {
                    val f = File(dir, Cache.hash(url))
                    val bytes = if (f.exists()) f.readBytes() else {
                        Net.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                            if (!r.isSuccessful) null else r.body?.bytes()?.also { if (it.size < 2_000_000) f.writeBytes(it) }
                        }
                    } ?: return@withPermit null.also { synchronized(failed) { failed += url } }
                    var img = org.jetbrains.skia.Image.makeFromEncoded(bytes)
                    if (img.width > maxW) { // downscale big posters to keep memory flat
                        val h = img.height * maxW / img.width
                        val s = org.jetbrains.skia.Surface.makeRasterN32Premul(maxW, h)
                        s.canvas.drawImageRect(img, org.jetbrains.skia.Rect.makeWH(maxW.toFloat(), h.toFloat()))
                        img = s.makeImageSnapshot()
                    }
                    img.toComposeImageBitmap().also { synchronized(mem) { mem[key(url, maxW)] = it } }
                } catch (e: Exception) { synchronized(failed) { failed += url }; null }
            }
        }
    }
}

@Composable
fun NetImage(url: String?, modifier: Modifier, scale: ContentScale = ContentScale.Fit) {
    var img by remember(url) { mutableStateOf(url?.let { Images.cached(it) }) }
    LaunchedEffect(url) { if (img == null && url != null && url.startsWith("http")) img = Images.load(url) }
    val i = img
    if (i != null) Image(i, null, modifier, contentScale = scale) else Box(modifier.background(Theme.surface2))
}
