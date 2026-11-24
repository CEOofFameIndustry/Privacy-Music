package com.example.privacymusic.net

import com.example.privacymusic.i18n.T

import com.example.privacymusic.data.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns YouTube Music (WEB_REMIX) renderer JSON into app models. It is deliberately generic: any browse page
 * (home, explore, album, artist, playlist, moods, "more" pages, search) is just a header + shelves of items,
 * which is exactly how the official client renders them too.
 */
object Parser {
    // ---------- JSON helpers ----------
    fun JSONObject.o(vararg path: String): JSONObject? {
        var cur: JSONObject? = this
        for (p in path) cur = cur?.optJSONObject(p) ?: return null
        return cur
    }
    fun JSONObject.a(vararg path: String): JSONArray? {
        if (path.isEmpty()) return null
        val parent = if (path.size == 1) this else o(*path.copyOfRange(0, path.size - 1))
        return parent?.optJSONArray(path.last())
    }
    fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    private fun JSONObject.str(key: String): String? = optString(key).takeIf { it.isNotEmpty() && has(key) }

    fun text(o: JSONObject?): String {
        if (o == null) return ""
        o.str("simpleText")?.let { return it }
        return o.optJSONArray("runs").objs().joinToString("") { it.optString("text") }
    }

    /** Largest thumbnail inside any thumbnail renderer shape. */
    fun thumbnail(o: JSONObject?): String? {
        if (o == null) return null
        fun find(x: Any?): JSONArray? = when (x) {
            is JSONObject -> x.optJSONArray("thumbnails") ?: x.keys().asSequence().firstNotNullOfOrNull { k ->
                if (k == "thumbnailOverlay" || k == "overlay") null else find(x.opt(k))
            }
            else -> null
        }
        return find(o).objs().maxByOrNull { it.optInt("width", 0) }?.optString("url")?.takeIf { it.startsWith("http") }
    }

    private fun pageType(browse: JSONObject): String? =
        browse.o("browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig")?.str("pageType")

    fun endpoint(o: JSONObject?): Endpoint? {
        if (o == null) return null
        o.optJSONObject("browseEndpoint")?.let { b ->
            val id = b.str("browseId") ?: return null
            return BrowseEndpoint(id, b.str("params"), pageType(b))
        }
        o.optJSONObject("watchEndpoint")?.let { w ->
            return WatchEndpoint(w.str("videoId"), w.str("playlistId"), w.str("params"), if (w.has("index")) w.optInt("index") else null)
        }
        o.optJSONObject("watchPlaylistEndpoint")?.let { w -> return WatchEndpoint(null, w.str("playlistId"), w.str("params")) }
        o.optJSONObject("searchEndpoint")?.let { s -> return SearchEndpoint(s.optString("query"), s.str("params")) }
        return null
    }

    private fun isArtist(e: BrowseEndpoint) = e.pageType == "MUSIC_PAGE_TYPE_ARTIST" ||
        e.pageType == "MUSIC_PAGE_TYPE_USER_CHANNEL" || (e.pageType == null && e.browseId.startsWith("UC"))
    private fun isAlbum(e: BrowseEndpoint) = e.pageType == "MUSIC_PAGE_TYPE_ALBUM" || (e.pageType == null && e.browseId.startsWith("MPRE"))
    private fun isPlaylist(e: BrowseEndpoint) = e.pageType == "MUSIC_PAGE_TYPE_PLAYLIST" ||
        (e.pageType == null && (e.browseId.startsWith("VL") || e.browseId.startsWith("PL")))

    private val durationRx = Regex("^(\\d+:)?\\d{1,2}:\\d{2}$")
    private val yearRx = Regex("^\\d{4}$")
    fun parseDuration(s: String?): Int? {
        if (s == null || !durationRx.matches(s.trim())) return null
        return s.trim().split(":").fold(0) { acc, p -> acc * 60 + (p.toIntOrNull() ?: 0) }
    }

    private data class Meta(val artists: List<Ref>, val album: Ref?, val duration: Int?, val year: String?, val rest: String)

    /** Splits subtitle runs ("Artist & Artist • Album • 3:21") into structured fields. */
    private fun meta(runs: List<JSONObject>, dropLabel: Boolean): Meta {
        val groups = mutableListOf(mutableListOf<JSONObject>())
        for (r in runs) {
            val t = r.optString("text")
            if (t == " • " || t == " · " || t == "•") groups += mutableListOf<JSONObject>() else groups.last() += r
        }
        groups.removeAll { it.isEmpty() }
        val artists = mutableListOf<Ref>(); var album: Ref? = null; var duration: Int? = null; var year: String? = null
        val rest = mutableListOf<String>()
        groups.forEachIndexed { gi, g ->
            val withEndpoints = g.filter { it.has("navigationEndpoint") }
            val joined = g.joinToString("") { it.optString("text") }.trim()
            when {
                withEndpoints.isNotEmpty() -> for (r in g) {
                    val e = endpoint(r.optJSONObject("navigationEndpoint")) as? BrowseEndpoint
                    val t = r.optString("text")
                    when {
                        e != null && isAlbum(e) -> album = Ref(t, e.browseId)
                        e != null && isArtist(e) -> artists += Ref(t, e.browseId)
                        e == null && r.has("navigationEndpoint") -> Unit
                        e == null -> Unit // separators like ", " " & " " і "
                        else -> artists += Ref(t, e.browseId)
                    }
                }
                durationRx.matches(joined) -> duration = parseDuration(joined)
                yearRx.matches(joined) -> year = joined
                gi == 0 && dropLabel && groups.size > 1 -> Unit // "Song" / "Альбом" type label in search results
                else -> rest += joined
            }
        }
        return Meta(artists, album, duration, year, rest.joinToString(" • "))
    }

    private fun flexRuns(item: JSONObject, i: Int): List<JSONObject> =
        item.optJSONArray("flexColumns")?.optJSONObject(i)?.o("musicResponsiveListItemFlexColumnRenderer", "text")
            ?.optJSONArray("runs").objs()

    private fun explicit(item: JSONObject): Boolean = item.optJSONArray("badges").objs().any {
        it.o("musicInlineBadgeRenderer", "icon")?.optString("iconType") == "MUSIC_EXPLICIT_BADGE"
    } || item.optJSONArray("subtitleBadges").objs().any {
        it.o("musicInlineBadgeRenderer", "icon")?.optString("iconType") == "MUSIC_EXPLICIT_BADGE"
    }

    private fun overlayPlaylistId(item: JSONObject): String? =
        (endpoint(item.o("thumbnailOverlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer", "playNavigationEndpoint"))
            as? WatchEndpoint)?.playlistId

    private fun isVideoType(watch: JSONObject?): Boolean {
        val t = watch?.o("watchEndpoint", "watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig")?.optString("musicVideoType")
        return t != null && t != "MUSIC_VIDEO_TYPE_ATV" && t.isNotEmpty()
    }

    // ---------- items ----------
    fun item(wrapper: JSONObject, search: Boolean = false): YTItem? {
        wrapper.optJSONObject("musicResponsiveListItemRenderer")?.let { return responsive(it, search) }
        wrapper.optJSONObject("musicTwoRowItemRenderer")?.let { return twoRow(it) }
        wrapper.optJSONObject("playlistPanelVideoRenderer")?.let { return panel(it) }
        wrapper.o("playlistPanelVideoWrapperRenderer", "primaryRenderer")?.let { return item(it) }
        wrapper.optJSONObject("musicNavigationButtonRenderer")?.let { b ->
            val e = endpoint(b.optJSONObject("clickCommand")) ?: return null
            return NavItem(text(b.optJSONObject("buttonText")), e, b.o("solid")?.optLong("leftStripeColor"))
        }
        wrapper.optJSONObject("musicMultiRowListItemRenderer")?.let { r ->
            // Podcast episodes
            val e = endpoint(r.optJSONObject("onTap")) as? WatchEndpoint ?: return null
            val id = e.videoId ?: return null
            return Song(id, text(r.optJSONObject("title")), thumbnail = thumbnail(r.optJSONObject("thumbnail")),
                extra = text(r.optJSONObject("subtitle")), isVideo = true)
        }
        return null
    }

    private fun responsive(r: JSONObject, search: Boolean): YTItem? {
        val titleRuns = flexRuns(r, 0)
        val title = titleRuns.joinToString("") { it.optString("text") }.trim()
        if (title.isEmpty()) return null
        val thumb = thumbnail(r.optJSONObject("thumbnail"))
        val titleEndpoint = titleRuns.firstOrNull()?.optJSONObject("navigationEndpoint")
        val videoId = r.o("playlistItemData")?.str("videoId")
            ?: (endpoint(titleEndpoint) as? WatchEndpoint)?.videoId
            ?: (endpoint(r.optJSONObject("overlay")?.o("musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer", "playNavigationEndpoint")) as? WatchEndpoint)?.videoId
        val nav = endpoint(r.optJSONObject("navigationEndpoint"))
        val second = flexRuns(r, 1)
        if (videoId != null && (nav == null || nav is WatchEndpoint)) {
            val columns = (1 until (r.optJSONArray("flexColumns")?.length() ?: 0)).flatMap { i ->
                val runs = flexRuns(r, i); if (i > 1) listOf(JSONObject().put("text", " • ")) + runs else runs
            }
            val m = meta(columns, search)
            val fixed = r.optJSONArray("fixedColumns").objs().map { text(it.o("musicResponsiveListItemFixedColumnRenderer", "text")) }
            val duration = m.duration ?: fixed.firstNotNullOfOrNull { parseDuration(it) }
            return Song(
                id = videoId, title = title, artists = m.artists, album = m.album, duration = duration,
                thumbnail = thumb, explicit = explicit(r), isVideo = isVideoType(titleEndpoint),
                setVideoId = r.o("playlistItemData")?.str("playlistSetVideoId"),
                extra = m.rest.ifBlank { null },
            )
        }
        val browse = nav as? BrowseEndpoint ?: (endpoint(titleEndpoint) as? BrowseEndpoint)
        if (browse != null) {
            val m = meta(second, search)
            val subtitle = buildList {
                if (m.artists.isNotEmpty()) add(m.artists.joinToString(", ") { it.name })
                m.year?.let(::add); if (m.rest.isNotBlank()) add(m.rest)
            }.joinToString(" • ")
            return when {
                isArtist(browse) -> ArtistItem(browse.browseId, title, m.rest.ifBlank { subtitle }, thumb)
                isAlbum(browse) -> AlbumItem(browse.browseId, overlayPlaylistId(r), title, m.artists, m.year, subtitle, thumb, explicit(r))
                else -> PlaylistItem(browse.browseId, title, subtitle, thumb, overlayPlaylistId(r) ?: browse.browseId.removePrefix("VL"))
            }
        }
        return null
    }

    private fun twoRow(r: JSONObject): YTItem? {
        val title = text(r.optJSONObject("title")).trim()
        if (title.isEmpty()) return null
        val thumb = thumbnail(r.optJSONObject("thumbnailRenderer"))
        val subtitleRuns = r.o("subtitle")?.optJSONArray("runs").objs()
        val m = meta(subtitleRuns, false)
        val subtitle = text(r.optJSONObject("subtitle"))
        val navObj = r.optJSONObject("navigationEndpoint")
        return when (val e = endpoint(navObj)) {
            is WatchEndpoint -> if (e.videoId != null) Song(e.videoId, title, m.artists, m.album, m.duration, thumb,
                explicit(r), isVideoType(navObj), extra = m.rest.ifBlank { null })
                else PlaylistItem(e.playlistId ?: return null, title, subtitle, thumb, e.playlistId, e)
            is BrowseEndpoint -> when {
                isArtist(e) -> ArtistItem(e.browseId, title, subtitle, thumb)
                isAlbum(e) -> AlbumItem(e.browseId, overlayPlaylistId(r), title, m.artists, m.year, subtitle, thumb, explicit(r))
                isPlaylist(e) -> PlaylistItem(e.browseId, title, subtitle, thumb, overlayPlaylistId(r) ?: e.browseId.removePrefix("VL"))
                else -> NavItem(title, e, thumbnail = thumb)
            }
            else -> null
        }
    }

    private fun panel(r: JSONObject): Song? {
        val id = r.str("videoId") ?: return null
        val m = meta(r.o("longBylineText")?.optJSONArray("runs").objs(), false)
        val nav = r.optJSONObject("navigationEndpoint")
        return Song(id, text(r.optJSONObject("title")), m.artists, m.album,
            parseDuration(text(r.optJSONObject("lengthText"))), thumbnail(r.optJSONObject("thumbnail")),
            explicit(r), isVideoType(nav), r.str("playlistSetVideoId"))
    }

    // ---------- shelves ----------
    private fun moreEndpoint(header: JSONObject?): Endpoint? {
        val h = header?.optJSONObject("musicCarouselShelfBasicHeaderRenderer") ?: return null
        return endpoint(h.o("moreContentButton", "buttonRenderer", "navigationEndpoint"))?.takeIf { it !is WatchEndpoint }
            ?: endpoint(h.o("title")?.optJSONArray("runs")?.optJSONObject(0)?.optJSONObject("navigationEndpoint"))
    }

    fun continuationToken(o: JSONObject?): String? {
        if (o == null) return null
        o.optJSONArray("continuations").objs().firstOrNull()?.let { c ->
            return (c.optJSONObject("nextContinuationData") ?: c.optJSONObject("nextRadioContinuationData")
                ?: c.optJSONObject("reloadContinuationData"))?.str("continuation")
        }
        return null
    }

    private fun itemsWithContinuation(arr: JSONArray?, search: Boolean = false): Pair<List<YTItem>, String?> {
        var token: String? = null
        val items = arr.objs().mapNotNull { w ->
            w.o("continuationItemRenderer", "continuationEndpoint", "continuationCommand")?.str("token")?.let { token = it; return@mapNotNull null }
            item(w, search)
        }
        return items to token
    }

    fun shelf(section: JSONObject, search: Boolean = false): Shelf? {
        section.optJSONObject("musicCarouselShelfRenderer")?.let { c ->
            val h = c.o("header", "musicCarouselShelfBasicHeaderRenderer")
            val items = c.optJSONArray("contents").objs().mapNotNull { item(it) }
            if (items.isEmpty()) return null
            val listy = c.optJSONArray("contents").objs().firstOrNull()?.has("musicResponsiveListItemRenderer") == true
            val play = endpoint(h?.o("moreContentButton", "buttonRenderer", "navigationEndpoint")) as? WatchEndpoint
            return Shelf(text(h?.optJSONObject("title")).ifBlank { null }, text(h?.optJSONObject("strapline")).ifBlank { null },
                items, moreEndpoint(c.optJSONObject("header")),
                when { listy -> ShelfStyle.QUICK_PICKS; items.all { it is NavItem && it.thumbnail == null } -> ShelfStyle.NAV_GRID; else -> ShelfStyle.CAROUSEL }, play = play)
        }
        section.optJSONObject("musicShelfRenderer")?.let { s ->
            val (items, token) = itemsWithContinuation(s.optJSONArray("contents"), search)
            val more = endpoint(s.optJSONObject("bottomEndpoint"))
                ?: endpoint(s.o("title")?.optJSONArray("runs")?.optJSONObject(0)?.optJSONObject("navigationEndpoint"))
                ?: endpoint(s.o("bottomButton", "buttonRenderer", "navigationEndpoint"))
            if (items.isEmpty()) return null
            return Shelf(text(s.optJSONObject("title")).ifBlank { null }, null, items, more, ShelfStyle.LIST,
                token ?: continuationToken(s))
        }
        section.optJSONObject("musicPlaylistShelfRenderer")?.let { s ->
            val (items, token) = itemsWithContinuation(s.optJSONArray("contents"))
            return Shelf(null, null, items, null, ShelfStyle.LIST, token ?: continuationToken(s))
        }
        section.optJSONObject("gridRenderer")?.let { g ->
            val (items, token) = itemsWithContinuation(g.optJSONArray("items"))
            if (items.isEmpty()) return null
            val style = if (items.all { it is NavItem }) ShelfStyle.NAV_GRID else ShelfStyle.GRID
            return Shelf(text(g.o("header", "gridHeaderRenderer", "title")).ifBlank { null }, null, items, null, style,
                token ?: continuationToken(g))
        }
        section.optJSONObject("musicCardShelfRenderer")?.let { c ->
            val titleRun = c.o("title")?.optJSONArray("runs")?.optJSONObject(0)
            val e = endpoint(titleRun?.optJSONObject("navigationEndpoint")) ?: endpoint(c.optJSONObject("onTap"))
            val thumb = thumbnail(c.optJSONObject("thumbnail"))
            val title = text(c.optJSONObject("title"))
            val m = meta(c.o("subtitle")?.optJSONArray("runs").objs(), true)
            val subtitle = text(c.optJSONObject("subtitle"))
            val main: YTItem? = when (e) {
                is WatchEndpoint -> e.videoId?.let { Song(it, title, m.artists, m.album, m.duration, thumb, explicit(c), extra = m.rest.ifBlank { null }) }
                is BrowseEndpoint -> when {
                    isArtist(e) -> ArtistItem(e.browseId, title, subtitle, thumb)
                    isAlbum(e) -> AlbumItem(e.browseId, null, title, m.artists, m.year, subtitle, thumb)
                    else -> PlaylistItem(e.browseId, title, subtitle, thumb)
                }
                else -> null
            }
            val rest = c.optJSONArray("contents").objs().mapNotNull { item(it, true) }
            val header = text(c.o("header", "musicCardShelfHeaderBasicRenderer", "title")).ifBlank { null }
            return Shelf(header, null, listOfNotNull(main) + rest, null, ShelfStyle.TOP_RESULT)
        }
        section.optJSONObject("musicDescriptionShelfRenderer")?.let { d ->
            return Shelf(text(d.optJSONObject("header")).ifBlank { null }, null, emptyList(), null, ShelfStyle.DESCRIPTION,
                text = text(d.optJSONObject("description")))
        }
        section.optJSONObject("itemSectionRenderer")?.let { s ->
            val items = s.optJSONArray("contents").objs().mapNotNull { item(it, search) }
            if (items.isEmpty()) return null
            return Shelf(null, null, items, null, ShelfStyle.LIST)
        }
        return null
    }

    /** Consecutive untitled list sections (new-style search results) are merged into one shelf. */
    private fun merge(shelves: List<Shelf>): List<Shelf> {
        val out = mutableListOf<Shelf>()
        for (s in shelves) {
            val last = out.lastOrNull()
            if (last != null && s.title == null && last.title == null && s.style == ShelfStyle.LIST && last.style == ShelfStyle.LIST && last.continuation == null)
                out[out.lastIndex] = last.copy(items = last.items + s.items, continuation = s.continuation)
            else out += s
        }
        return out
    }

    fun shelves(sectionList: JSONObject?, search: Boolean = false): List<Shelf> =
        merge(sectionList?.optJSONArray("contents").objs().mapNotNull { shelf(it, search) })

    fun chips(sectionList: JSONObject?): List<Chip> =
        sectionList?.o("header", "chipCloudRenderer")?.optJSONArray("chips").objs().mapNotNull { c ->
            val r = c.optJSONObject("chipCloudChipRenderer") ?: return@mapNotNull null
            val selected = r.optBoolean("isSelected")
            val e = if (selected) endpoint(r.optJSONObject("onDeselectedCommand")) else endpoint(r.optJSONObject("navigationEndpoint"))
            Chip(text(r.optJSONObject("text")), e, selected)
        }.filter { it.title.isNotBlank() }

    // ---------- headers ----------
    private fun menuWatch(menu: JSONArray?, icon: String): WatchEndpoint? = menu.objs().firstNotNullOfOrNull { i ->
        val n = i.optJSONObject("menuNavigationItemRenderer") ?: return@firstNotNullOfOrNull null
        if (n.o("icon")?.optString("iconType") == icon) endpoint(n.optJSONObject("navigationEndpoint")) as? WatchEndpoint else null
    }

    fun header(o: JSONObject?, browseId: String): Header? {
        if (o == null) return null
        val type = when {
            browseId.startsWith("MPRE") -> PageType.ALBUM
            browseId.startsWith("VL") || browseId.startsWith("PL") || browseId.startsWith("RD") || browseId.startsWith("OLAK") -> PageType.PLAYLIST
            browseId.startsWith("UC") -> PageType.ARTIST
            browseId == "FEmusic_home" -> PageType.HOME
            else -> PageType.OTHER
        }
        o.optJSONObject("musicResponsiveHeaderRenderer")?.let { h ->
            val strap = h.o("straplineTextOne")?.optJSONArray("runs").objs()
            val artists = meta(strap, false).artists.ifEmpty { listOfNotNull(text(h.optJSONObject("straplineTextOne")).ifBlank { null }?.let { Ref(it) }) }
            val buttons = h.optJSONArray("buttons").objs()
            val play = buttons.firstNotNullOfOrNull { endpoint(it.o("musicPlayButtonRenderer", "playNavigationEndpoint")) as? WatchEndpoint }
            val menu = buttons.firstNotNullOfOrNull { it.o("menuRenderer")?.optJSONArray("items") }
            return Header(text(h.optJSONObject("title")), text(h.optJSONObject("subtitle")), text(h.optJSONObject("secondSubtitle")),
                text(h.o("description", "musicDescriptionShelfRenderer", "description")).ifBlank { null },
                thumbnail(h.optJSONObject("thumbnail")), artists, play,
                menuWatch(menu, "MUSIC_SHUFFLE"), menuWatch(menu, "MIX"), play?.playlistId, type)
        }
        (o.optJSONObject("musicImmersiveHeaderRenderer") ?: o.optJSONObject("musicVisualHeaderRenderer"))?.let { h ->
            return Header(text(h.optJSONObject("title")), text(h.o("subscriptionButton", "subscribeButtonRenderer", "longSubscriberCountText")),
                text(h.optJSONObject("monthlyListenerCount")),
                text(h.optJSONObject("description")).ifBlank { null },
                thumbnail(h.optJSONObject("thumbnail")) ?: thumbnail(h.optJSONObject("foregroundThumbnail")), emptyList(),
                endpoint(h.o("playButton", "buttonRenderer", "navigationEndpoint")) as? WatchEndpoint,
                null,
                endpoint(h.o("startRadioButton", "buttonRenderer", "navigationEndpoint")) as? WatchEndpoint,
                null, PageType.ARTIST)
        }
        (o.optJSONObject("musicDetailHeaderRenderer") ?: o.o("musicEditablePlaylistDetailHeaderRenderer", "header", "musicDetailHeaderRenderer"))?.let { h ->
            val m = meta(h.o("subtitle")?.optJSONArray("runs").objs(), true)
            val menu = h.o("menu", "menuRenderer")?.optJSONArray("items")
            return Header(text(h.optJSONObject("title")), text(h.optJSONObject("subtitle")), text(h.optJSONObject("secondSubtitle")),
                text(h.optJSONObject("description")).ifBlank { null }, thumbnail(h.optJSONObject("thumbnail")), m.artists,
                null, menuWatch(menu, "MUSIC_SHUFFLE"), menuWatch(menu, "MIX"), null, type)
        }
        o.o("musicEditablePlaylistDetailHeaderRenderer", "header")?.let { return header(it, browseId) }
        o.optJSONObject("musicHeaderRenderer")?.let { h -> return Header(text(h.optJSONObject("title")), type = type) }
        return null
    }

    // ---------- pages ----------
    fun page(json: JSONObject, browseId: String): Page {
        val contents = json.optJSONObject("contents")
        var header = header(json.optJSONObject("header"), browseId)
        var sectionList: JSONObject? = null
        val shelves = mutableListOf<Shelf>()
        contents?.o("singleColumnBrowseResultsRenderer")?.optJSONArray("tabs").objs().firstOrNull()?.o("tabRenderer", "content", "sectionListRenderer")?.let {
            sectionList = it
        }
        contents?.optJSONObject("twoColumnBrowseResultsRenderer")?.let { two ->
            val primary = two.optJSONArray("tabs").objs().firstOrNull()?.o("tabRenderer", "content", "sectionListRenderer")
            primary?.optJSONArray("contents").objs().forEach { s ->
                if (header == null) header = header(s, browseId)
                if (!s.has("musicResponsiveHeaderRenderer") && !s.has("musicEditablePlaylistDetailHeaderRenderer")) shelf(s)?.let(shelves::add)
            }
            sectionList = two.o("secondaryContents", "sectionListRenderer")
        }
        contents?.optJSONObject("sectionListRenderer")?.let { sectionList = it }
        shelves += shelves(sectionList)
        var h = header
        // Playlists/albums always have a "play all" even when the header doesn't spell it out.
        if (h != null && h.play == null && (h.type == PageType.PLAYLIST || h.type == PageType.ALBUM)) {
            val pid = h.playlistId ?: browseId.removePrefix("VL").takeIf { browseId.startsWith("VL") }
            if (pid != null) h = h.copy(play = WatchEndpoint(playlistId = pid), playlistId = pid,
                shuffle = h.shuffle ?: WatchEndpoint(playlistId = pid, params = "wAEB8gECKAE%3D"))
        }
        // Artist page: the bio lives in a description shelf.
        if (h != null && h.type == PageType.ARTIST && h.description == null)
            shelves.firstOrNull { it.style == ShelfStyle.DESCRIPTION }?.let { d -> h = h.copy(description = d.text); shelves.remove(d) }
        // Album tracks don't repeat the artwork / artists / album; fill them in like the official client shows them.
        if (h != null && h.type == PageType.ALBUM) {
            val albumRef = Ref(h.title, browseId)
            for (i in shelves.indices) if (shelves[i].style == ShelfStyle.LIST && i == 0) shelves[i] = shelves[i].copy(items = shelves[i].items.map {
                if (it is Song) it.copy(thumbnail = it.thumbnail ?: h.thumbnail, artists = it.artists.ifEmpty { h.artists }, album = it.album ?: albumRef) else it
            })
        }
        return Page(h, chips(sectionList), shelves, continuationToken(sectionList))
    }

    data class Continued(val shelves: List<Shelf>, val items: List<YTItem>, val next: String?)

    fun continuation(json: JSONObject): Continued {
        val cc = json.optJSONObject("continuationContents")
        cc?.optJSONObject("sectionListContinuation")?.let { return Continued(shelves(it), emptyList(), continuationToken(it)) }
        for (k in listOf("musicPlaylistShelfContinuation", "musicShelfContinuation", "playlistPanelContinuation")) {
            cc?.optJSONObject(k)?.let { s ->
                val (items, token) = itemsWithContinuation(s.optJSONArray("contents"), k == "musicShelfContinuation")
                return Continued(emptyList(), items, token ?: continuationToken(s))
            }
        }
        cc?.optJSONObject("gridContinuation")?.let { g ->
            val (items, token) = itemsWithContinuation(g.optJSONArray("items"))
            return Continued(emptyList(), items, token ?: continuationToken(g))
        }
        val actions = json.optJSONArray("onResponseReceivedActions").objs()
        for (a in actions) {
            val arr = a.o("appendContinuationItemsAction")?.optJSONArray("continuationItems") ?: continue
            val sections = arr.objs().mapNotNull { shelf(it) }
            if (sections.isNotEmpty()) return Continued(merge(sections), emptyList(), null)
            val (items, token) = itemsWithContinuation(arr)
            return Continued(emptyList(), items, token)
        }
        return Continued(emptyList(), emptyList(), null)
    }

    fun search(json: JSONObject): Page {
        val tab = json.o("contents", "tabbedSearchResultsRenderer")?.optJSONArray("tabs").objs().firstOrNull()
            ?.o("tabRenderer", "content", "sectionListRenderer") ?: json.o("contents", "sectionListRenderer")
        return Page(null, chips(tab), shelves(tab, search = true), continuationToken(tab))
    }

    fun suggestions(json: JSONObject): Pair<List<String>, List<YTItem>> {
        val sections = json.optJSONArray("contents").objs().mapNotNull { it.optJSONObject("searchSuggestionsSectionRenderer") }
        val queries = mutableListOf<String>(); val items = mutableListOf<YTItem>()
        for (s in sections) for (c in s.optJSONArray("contents").objs()) {
            c.optJSONObject("searchSuggestionRenderer")?.let { r ->
                (endpoint(r.optJSONObject("navigationEndpoint")) as? SearchEndpoint)?.query?.let(queries::add)
            } ?: item(c, true)?.let(items::add)
        }
        return queries to items
    }

    fun watch(json: JSONObject): WatchQueue {
        val tabs = json.o("contents", "singleColumnMusicWatchNextResultsRenderer", "tabbedRenderer", "watchNextTabbedResultsRenderer")
            ?.optJSONArray("tabs").objs().mapNotNull { it.optJSONObject("tabRenderer") }
        val panel = tabs.firstOrNull()?.o("content", "musicQueueRenderer", "content", "playlistPanelRenderer")
        val contents = panel?.optJSONArray("contents").objs()
        val songs = contents.mapNotNull { item(it) as? Song }
        val automix = contents.firstNotNullOfOrNull {
            endpoint(it.o("automixPreviewVideoRenderer", "content", "automixPlaylistVideoRenderer", "navigationEndpoint")) as? WatchEndpoint
        }
        val selected = contents.indexOfFirst { (it.optJSONObject("playlistPanelVideoRenderer") ?: it.o("playlistPanelVideoWrapperRenderer", "primaryRenderer", "playlistPanelVideoRenderer"))?.optBoolean("selected") == true }
        fun tabBrowse(type: String) = tabs.firstNotNullOfOrNull { t ->
            (endpoint(t.optJSONObject("endpoint")) as? BrowseEndpoint)?.takeIf { it.pageType == type }?.browseId
        }
        return WatchQueue(songs, continuationToken(panel), panel?.str("playlistId"), tabBrowse("MUSIC_PAGE_TYPE_TRACK_LYRICS"),
            tabBrowse("MUSIC_PAGE_TYPE_TRACK_RELATED"), automix, selected)
    }

    fun lyrics(json: JSONObject): Lyrics? {
        val shelf = json.o("contents", "sectionListRenderer")?.optJSONArray("contents").objs().firstNotNullOfOrNull { it.optJSONObject("musicDescriptionShelfRenderer") }
            ?: return null
        val textValue = text(shelf.optJSONObject("description")).ifBlank { return null }
        return Lyrics(textValue, text(shelf.optJSONObject("footer")).ifBlank { "YouTube Music" })
    }

    /** Request a bigger image from Google's image servers (same host, just a size suffix). */
    fun resize(url: String?, size: Int): String? {
        if (url == null) return null
        val sized = Regex("=w\\d+-h\\d+.*$")
        return when {
            url.contains("googleusercontent.com") || url.contains("ggpht.com") ->
                if (sized.containsMatchIn(url)) url.replace(sized, "=w${size}-h${size}-l90-rj") else if (!url.contains('=')) "${url}=w${size}-h${size}-l90-rj" else url
            else -> url
        }
    }
}
