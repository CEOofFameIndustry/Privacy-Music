package com.example.privacymusic.ui.screens

import com.example.privacymusic.i18n.T

import com.example.privacymusic.ui.theme.Pal

import androidx.compose.foundation.clickable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.privacymusic.data.*
import com.example.privacymusic.net.YouTubeMusic
import com.example.privacymusic.ui.LocalApp
import com.example.privacymusic.ui.components.*
import com.example.privacymusic.ui.theme.Dim
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SearchScreen() {
    val app = LocalApp.current
    var query by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val history by remember(query) { Library.observe { Library.searches(query) } }.collectAsState(emptyList())
    var suggestions by remember { mutableStateOf<Pair<List<String>, List<YTItem>>>(emptyList<String>() to emptyList()) }
    val searchHistory by Settings.searchHistory.state.collectAsState()
    val explore = viewModel(key = "explore") { PageModel(null, { YouTubeMusic.browse("FEmusic_explore") }) }
    val actions = remember { app.actions() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val layout by Settings.layoutSearch.state.collectAsState()
    var editing by remember { mutableStateOf(false) }
    val exploreIds = explore.page?.shelves?.mapNotNull { it.title }?.map { "x:" + Layouts.clean(it) to it }.orEmpty()
    if (editing) {
        LayoutEditor(T("Пошук"), Settings.layoutSearch, listOf("history" to T("Історія пошуку")) + exploreIds, onDone = { editing = false })
        return
    }
    val order = Layouts.visible(layout, listOf("history") + exploreIds.map { it.first })
    LaunchedEffect(app.focusSearch) {
        if (!app.focusSearch) return@LaunchedEffect
        // One tap on the magnifier = ready to type: focus the field and open the keyboard.
        delay(120)
        runCatching { focus.requestFocus(); keyboard?.show() }
        app.focusSearch = false
    }
    LaunchedEffect(query) {
        if (query.isBlank()) { suggestions = emptyList<String>() to emptyList(); return@LaunchedEffect }
        // Off = nothing is sent until you actually press Search.
        if (!Settings.searchSuggestions.value || Settings.offline.value) { suggestions = emptyList<String>() to emptyList(); return@LaunchedEffect }
        delay(250)
        try { suggestions = YouTubeMusic.suggestions(query) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }
    fun submit(q: String) {
        val text = q.trim(); if (text.isEmpty()) return
        keyboard?.hide()
        YouTubeMusic.parseLink(text)?.takeIf { text.contains("youtu") }?.let { query = ""; app.endpoint(it); return }
        scope.launch(Dispatchers.IO) { Library.addSearch(text) }
        app.search(text)
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        if (Pal.minimal) {
            // Minimal: large "Search" title, a compact grey field, and a Cancel link while typing.
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(T("Пошук"), Modifier.weight(1f), fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.6).sp)
                IconButton(onClick = { editing = true }) { Icon(Icons.Rounded.Edit, T("Налаштувати сторінку")) }
                IconButton(onClick = { app.nav.navigate("settings") { launchSingleTop = true } }) { Icon(Icons.Rounded.Settings, T("Налаштування")) }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.text.BasicTextField(query, { query = it }, Modifier.weight(1f).focusRequester(focus), singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 17.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Pal.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit(query) })) { inner ->
                    Row(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(10.dp)).background(Pal.chip).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Search, null, Modifier.size(20.dp), tint = Dim)
                        Box(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                            if (query.isEmpty()) Text(T("Шукати пісні, виконавців, альбоми"), color = Dim, fontSize = 17.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            inner()
                        }
                        if (query.isNotEmpty()) Icon(Icons.Rounded.Cancel, T("Очистити"), Modifier.size(18.dp).clickable { query = "" }, tint = Dim)
                    }
                }
                if (query.isNotEmpty()) Text(T("Скасувати"), Modifier.padding(start = 12.dp).clickable { query = ""; keyboard?.hide() }, color = Pal.accent, fontSize = 17.sp)
            }
        } else
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(query, { query = it }, Modifier.weight(1f).focusRequester(focus), singleLine = true,
                placeholder = { Text(T("Шукати пісні, виконавців, альбоми")) },
                leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Dim) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, T("Очистити")) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit(query) }),
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(focusedContainerColor = Pal.chip, unfocusedContainerColor = Pal.chip,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = Pal.accent))
            IconButton(onClick = { editing = true }) { Icon(Icons.Rounded.Edit, T("Налаштувати сторінку")) }
            IconButton(onClick = { app.nav.navigate("settings") { launchSingleTop = true } }) { Icon(Icons.Rounded.Settings, T("Налаштування")) }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (query.isBlank() && !searchHistory) item {
                Text(T("Історію пошуку вимкнено в налаштуваннях приватності — запити не зберігаються."), Modifier.padding(16.dp), color = Dim, fontSize = 13.sp)
            }
            if ("history" in order) items(history, key = { "h:${it}" }) { h ->
                SuggestionRow(Icons.Rounded.History, h, { submit(h) }, onFill = { query = "${h} " }, onRemove = { scope.launch(Dispatchers.IO) { Library.removeSearch(h) } })
            }
            items(suggestions.first.filter { it !in history }, key = { "s:${it}" }) { s -> SuggestionRow(Icons.Rounded.Search, s, { submit(s) }, onFill = { query = "${s} " }) }
            if (suggestions.second.isNotEmpty()) item { HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Pal.chip) }
            itemsIndexed(suggestions.second, key = { i, it -> "i:${i}:${it.key}" }) { _, item -> ItemRow(item, { app.open(item) }, { app.menu = com.example.privacymusic.ui.AppController.MenuRequest(item) }) }
            // Nothing typed yet: show what used to be the "Огляд" tab (new releases, charts, moods & genres) right here.
            if (query.isBlank()) {
                when {
                    explore.loading && explore.page == null -> item { Loading() }
                    explore.error != null && explore.page == null -> item { ErrorBox(explore.error!!, { explore.refresh() }) }
                    else -> shelves(explore, actions, arrange = { list ->
                        // Explore shelves in the user's order; untitled shelves keep their place at the end.
                        order.mapNotNull { id -> list.firstOrNull { it.title != null && "x:" + Layouts.clean(it.title) == id } } + list.filter { it.title == null }
                    })
                }
            }
        }
    }
}

@Composable
private fun SuggestionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit, onFill: () -> Unit, onRemove: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 20.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Dim)
        Text(text, Modifier.weight(1f).padding(start = 20.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp)
        if (onRemove != null) IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, T("Видалити з історії"), tint = Dim) }
        IconButton(onClick = onFill) { Icon(Icons.Rounded.NorthWest, T("Вставити"), tint = Dim) }
    }
}

@Composable
fun SearchResultsScreen(query: String, params: String?) {
    val app = LocalApp.current
    val model = viewModel(key = "search:${query}") {
        PageModel(params, { p -> YouTubeMusic.search(query, p) }, { YouTubeMusic.searchContinuation(it) })
    }
    val actions = remember { app.actions() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.statusBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { app.nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, T("Назад")) }
            Surface(onClick = { app.focusSearch = true; app.nav.navigate("search") }, Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(24.dp), color = Pal.chip) {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) { Text(query, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        val page = model.page
        if (page != null && page.chips.isNotEmpty()) ChipsRow(page.chips.map { it.title to it.selected }, { i ->
            val chip = page.chips[i]
            model.refresh(if (chip.selected) null else (chip.endpoint as? SearchEndpoint)?.params)
        }, Modifier.padding(vertical = 6.dp))
        when {
            model.loading -> Loading(Modifier.fillMaxSize())
            model.error != null -> ErrorBox(model.error!!, { model.refresh() })
            page != null && page.shelves.all { it.items.isEmpty() } -> EmptyState(Icons.Rounded.SearchOff, T("Нічого не знайдено"), T("Спробуйте інший запит або фільтр."))
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) { shelves(model, actions) }
        }
    }
}
