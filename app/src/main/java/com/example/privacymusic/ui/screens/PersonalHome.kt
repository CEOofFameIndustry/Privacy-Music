package com.example.privacymusic.ui.screens

import com.example.privacymusic.i18n.T

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.privacymusic.data.*
import com.example.privacymusic.net.YouTubeMusic
import kotlinx.coroutines.*
import java.util.Calendar

/**
 * The personal part of the home page. Everything is decided ON THE DEVICE from the local library, history and
 * statistics; YouTube only receives ordinary guest requests (a song's radio, an artist page) — the same requests
 * as when you play that song or open that artist yourself. Nothing about your taste is uploaded.
 */
class PersonalHomeModel : ViewModel() {
    var shelves by mutableStateOf<List<Shelf>>(emptyList()); private set
    var refreshing by mutableStateOf(false); private set
    var empty by mutableStateOf(false); private set
    private var job: Job? = null

    init { refresh() }

    fun refresh() {
        job?.cancel()
        job = viewModelScope.launch {
            refreshing = true
            try { build() } catch (e: CancellationException) { throw e } catch (e: Exception) { Diag.log("Головна: ${e.javaClass.simpleName}") }
            finally { refreshing = false }
        }
    }

    private suspend fun build() = coroutineScope {
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val recent = withContext(Dispatchers.IO) { Library.recentSongs(24) }
        val top = withContext(Dispatchers.IO) { Library.topSongs(now - 60 * day, 12) }
        val liked = withContext(Dispatchers.IO) { Library.likedSongs().take(10) }
        val forgotten = withContext(Dispatchers.IO) { Library.forgottenLikes(12) }
        val recentIds = withContext(Dispatchers.IO) { Library.recentlyPlayedIds(now - 2 * day) }
        val favourites = (top + liked + recent).filterNot { it.isLocal }.distinctBy { it.id }
        empty = favourites.isEmpty() && recent.isEmpty()

        // Local sections appear instantly.
        val local = mutableListOf<Shelf>()
        if (recent.isNotEmpty()) local += Shelf(T("Слухати знову"), null, recent.take(16), style = ShelfStyle.CAROUSEL, id = "again")
        shelves = local.toList()
        if (empty) return@coroutineScope

        // Top artists by how much you listen to them.
        val artists = (top + recent).flatMap { s -> s.artists.filter { it.id?.startsWith("UC") == true }.map { it to s } }
            .groupBy { it.first.id }.entries.sortedByDescending { it.value.size }.take(4)

        // Quick picks: the radios of your favourite songs, mixed, minus what you heard in the last two days.
        val seeds = favourites.take(3)
        val radios = seeds.map { seed -> async { runCatching { YouTubeMusic.next(WatchEndpoint(seed.id, "RDAMVM${seed.id}")).songs }.getOrDefault(emptyList()) } }
        val artistPages = artists.take(3).map { (id, _) -> async { runCatching { YouTubeMusic.browse(id!!, auth = false) }.getOrNull() } }

        val dislikedIds = withContext(Dispatchers.IO) { favourites.filter { Library.isDisliked(it.id) }.map { it.id }.toSet() }
        val lists = radios.awaitAll()
        val interleaved = buildList { for (i in 0 until (lists.maxOfOrNull { it.size } ?: 0)) lists.forEach { l -> l.getOrNull(i)?.let(::add) } }
        val quick = interleaved.filter { it.id !in recentIds && it.id !in dislikedIds && !it.isVideo && seeds.none { s -> s.id == it.id } }
            .distinctBy { it.id }.take(20)
        if (quick.isNotEmpty()) local.add(0.coerceAtMost(local.size), Shelf(T("Швидкий вибір"), T("НА ОСНОВІ ВАШИХ ПРОСЛУХОВУВАНЬ"), quick, style = ShelfStyle.QUICK_PICKS, id = "quick"))

        // Mixes: one per favourite artist, starting from your most-played song of theirs.
        val photos = withContext(Dispatchers.IO) { Library.artistImages() }
        val mixes = artists.distinctBy { it.value.first().second.id }.map { (id, plays) ->
            val song = plays.first().second; val name = plays.first().first.name
            PlaylistItem("mix:${song.id}", T("Мікс: {0}", name), T("{0} та схожі виконавці", name), photos[id] ?: song.thumbnail, null, WatchEndpoint(song.id, "RDAMVM${song.id}"))
        }
        if (mixes.isNotEmpty()) local += Shelf(T("Мікси для вас"), null, mixes, style = ShelfStyle.CAROUSEL, id = "mixes")
        shelves = local.toList()

        val pages = artistPages.awaitAll().filterNotNull()
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val fresh = pages.flatMap { p -> p.shelves.flatMap { it.items }.filterIsInstance<AlbumItem>()
            .filter { (it.year ?: Regex("\\b(19|20)\\d{2}\\b").find(it.subtitle)?.value)?.toIntOrNull()?.let { y -> y >= year - 1 } == true }
            .map { a -> if (a.artists.isEmpty() && p.header != null) a.copy(subtitle = listOf(p.header.title, a.subtitle).filter { s -> s.isNotBlank() }.joinToString(" • ")) else a } }
            .distinctBy { it.browseId }.take(12)
        if (fresh.isNotEmpty()) local += Shelf(T("Нове від ваших виконавців"), null, fresh, style = ShelfStyle.CAROUSEL, id = "fresh")
        pages.firstOrNull()?.let { p ->
            val similar = p.shelves.firstOrNull { sh -> sh.items.isNotEmpty() && sh.items.all { it is ArtistItem } }?.items.orEmpty()
            if (similar.isNotEmpty()) local += Shelf(T("Схожі на {0}", p.header?.title.orEmpty()), T("ВИКОНАВЦІ"), similar, style = ShelfStyle.CAROUSEL, id = "similar")
        }
        if (forgotten.size >= 3) local += Shelf(T("Давно не слухали"), T("З ВАШИХ ЛАЙКІВ"), forgotten, style = ShelfStyle.QUICK_PICKS, id = "forgotten")
        shelves = local.toList()
    }
}

fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> T("Доброго ранку")
    in 12..17 -> T("Доброго дня")
    in 18..22 -> T("Доброго вечора")
    else -> T("Доброї ночі")
}
