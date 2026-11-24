package com.example.privacymusic.ui.components

import com.example.privacymusic.i18n.T
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.privacymusic.data.Settings
import com.example.privacymusic.ui.theme.Dim
import com.example.privacymusic.ui.theme.Pal

/**
 * User-arranged page layouts: the order of a page's blocks and which are hidden, stored as a plain local setting
 * ("id1,-id2,id3", a leading "-" = hidden). Blocks that appear later (a new shelf) slot in at their default place.
 */
object Layouts {
    fun parse(s: String): List<Pair<String, Boolean>> =
        s.split(',').filter { it.isNotBlank() }.map { if (it.startsWith("-")) it.drop(1) to false else it to true }

    fun encode(l: List<Pair<String, Boolean>>): String = l.joinToString(",") { (id, on) -> (if (on) "" else "-") + clean(id) }

    /** Ids must survive the comma-separated format. */
    fun clean(id: String) = id.replace(",", " ")

    /** The saved arrangement applied to the blocks available right now. */
    fun arrange(saved: String, available: List<String>): List<Pair<String, Boolean>> {
        val ids = available.map(::clean)
        val result = parse(saved).filter { it.first in ids }.distinctBy { it.first }.toMutableList()
        val known = result.map { it.first }.toSet()
        ids.forEachIndexed { i, id -> if (id !in known) result.add(i.coerceAtMost(result.size), id to true) }
        return result
    }

    fun visible(saved: String, available: List<String>): List<String> = arrange(saved, available).filter { it.second }.map { it.first }
}

/** Default order of the bottom tabs per interface style. */
fun defaultTabs(): List<String> = if (Pal.minimal) listOf("home", "library", "search") else listOf("home", "search", "library")

/**
 * Full-page editor: drag ≡ to move a block, the eye shows / hides it. The bottom tabs can be reordered here too.
 * Everything is saved on the device only.
 */
@Composable
fun LayoutEditor(title: String, pref: Settings.Pref<String>, entries: List<Pair<String, String>>, onDone: () -> Unit) {
    val saved by pref.state.collectAsState()
    val labels = remember(entries) { entries.associate { Layouts.clean(it.first) to it.second } }
    val blocks = remember(entries) { Layouts.arrange(saved, entries.map { it.first }).toMutableStateList() }
    val tabLabels = mapOf("home" to T("Головна"), "search" to T("Пошук"), "library" to T("Медіатека"))
    val tabs = remember { Layouts.arrange(Settings.layoutTabs.value, defaultTabs()).toMutableStateList() }
    fun save() { pref.set(Layouts.encode(blocks)); Settings.layoutTabs.set(Layouts.encode(tabs)) }
    BackHandler { save(); onDone() }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = {
                blocks.clear(); blocks.addAll(Layouts.arrange("", entries.map { it.first }))
                tabs.clear(); tabs.addAll(Layouts.arrange("", defaultTabs()))
            }) { Text(T("Скинути"), color = Dim) }
            Button(onClick = { save(); onDone() }, colors = ButtonDefaults.buttonColors(containerColor = Pal.accent, contentColor = Pal.onAccent)) { Text(T("Готово")) }
        }
        Text(T("Перетягніть ≡, щоб змінити порядок. Око — показати або сховати блок. Зберігається лише на цьому пристрої."),
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = Dim, fontSize = 13.sp)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item { DragList(blocks, { labels[it] ?: it }, canHide = true) }
            item {
                Text(T("Вкладки внизу"), Modifier.padding(start = 16.dp, top = 22.dp, bottom = 6.dp), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                DragList(tabs, { tabLabels[it] ?: it }, canHide = false)
            }
        }
    }
}

@Composable
private fun DragList(list: SnapshotStateList<Pair<String, Boolean>>, label: (String) -> String, canHide: Boolean) {
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val rowPx = with(LocalDensity.current) { 60.dp.toPx() }
    Column(Modifier.padding(horizontal = 12.dp)) {
        list.forEach { (id, on) ->
            val isDragged = dragging == id
            key(id) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(vertical = 2.dp).zIndex(if (isDragged) 1f else 0f)
                    .graphicsLayer { translationY = if (isDragged) offset else 0f; shadowElevation = if (isDragged) 12f else 0f }
                    .background(if (isDragged) Pal.chip else Pal.card, RoundedCornerShape(12.dp)),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.DragHandle, T("Перетягнути"), Modifier.padding(horizontal = 12.dp).pointerInput(id) {
                        detectDragGestures(
                            onDragStart = { dragging = id; offset = 0f },
                            onDragEnd = { dragging = null; offset = 0f },
                            onDragCancel = { dragging = null; offset = 0f },
                        ) { change, drag ->
                            change.consume()
                            offset += drag.y
                            val i = list.indexOfFirst { it.first == id }
                            if (offset > rowPx / 2 && i < list.lastIndex) { list.add(i + 1, list.removeAt(i)); offset -= rowPx }
                            else if (offset < -rowPx / 2 && i > 0) { list.add(i - 1, list.removeAt(i)); offset += rowPx }
                        }
                    }, tint = Dim)
                    Text(label(id), Modifier.weight(1f), fontSize = 16.sp, color = if (on) Color.White else Dim)
                    if (canHide) IconButton(onClick = { val i = list.indexOfFirst { it.first == id }; list[i] = id to !on }) {
                        Icon(if (on) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, if (on) T("Сховати") else T("Показати"), tint = if (on) Pal.accent else Dim)
                    }
                }
            }
        }
    }
}
