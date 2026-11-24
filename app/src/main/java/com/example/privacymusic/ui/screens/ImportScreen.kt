package com.example.privacymusic.ui.screens

import com.example.privacymusic.i18n.T

import com.example.privacymusic.ui.theme.Pal

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.privacymusic.data.Library
import com.example.privacymusic.net.Importer
import com.example.privacymusic.ui.LocalApp
import com.example.privacymusic.ui.components.ItemRow
import com.example.privacymusic.ui.theme.Dim
import kotlinx.coroutines.*

@Composable
fun ImportScreen() {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<Importer.Progress?>(null) }
    var result by remember { mutableStateOf<Importer.Result?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var source by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun run(src: String?, block: suspend ((Importer.Progress) -> Unit) -> Importer.Result) {
        error = null; result = null; source = src
        job = scope.launch {
            try {
                val r = block { p -> scope.launch(Dispatchers.Main) { if (job?.isActive == true && result == null) progress = p } }
                result = r; name = r.name
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                error = e.message ?: T("Не вдалося імпортувати")
            } finally { progress = null }
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) run(null) { p -> Importer.fromTable(context, uri, p) }
    }
    val pickTelegram = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) run(null) { p -> Importer.fromTelegramFolder(context, uri, p) }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(T("Імпорт плейлиста"), onBack = { job?.cancel(); app.nav.popBackStack() })
        val r = result
        when {
            progress != null || job?.isActive == true && r == null && error == null -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val p = progress
                if (p != null && p.total > 0) LinearProgressIndicator({ p.done.toFloat() / p.total }, Modifier.fillMaxWidth(), color = Pal.accent)
                else LinearProgressIndicator(Modifier.fillMaxWidth(), color = Pal.accent)
                Spacer(Modifier.height(12.dp))
                Text(p?.stage ?: T("Підготовка…"), color = Dim)
                TextButton(onClick = { job?.cancel(); progress = null }) { Text(T("Скасувати")) }
            }
            r != null -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Column(Modifier.padding(16.dp)) {
                        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(T("Назва плейлиста")) }, singleLine = true)
                        Spacer(Modifier.height(8.dp))
                        Text(T("Знайдено {0} треків", r.songs.size) + if (r.missed.isNotEmpty()) T(" • не знайдено {0}", r.missed.size) else "", fontWeight = FontWeight.Bold)
                        r.note?.let { Text(it, color = Color(0xffffc46b), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
                        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                val songs = r.songs; val title = name.ifBlank { r.name }
                                app.scope.launch {
                                    val id = withContext(Dispatchers.IO) { Library.createPlaylist(title, songs, source) }
                                    app.toast(T("Плейлист «{0}» збережено на пристрої", title))
                                    app.nav.popBackStack(); app.nav.navigate("local/${id}")
                                }
                            }, enabled = r.songs.isNotEmpty(), colors = ButtonDefaults.buttonColors(containerColor = Pal.accent, contentColor = Pal.onAccent)) { Text(T("Зберегти плейлист")) }
                            OutlinedButton(onClick = { result = null }) { Text(T("Скасувати")) }
                        }
                    }
                }
                if (r.missed.isNotEmpty()) {
                    item { Text(T("Не знайдено на YouTube Music:"), Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = Dim, fontSize = 13.sp) }
                    items(r.missed) { Text("• ${it}", Modifier.padding(horizontal = 16.dp, vertical = 2.dp), color = Dim, fontSize = 13.sp) }
                    item { HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Pal.chip) }
                }
                items(r.songs, key = { it.id }) { s -> ItemRow(s, { app.open(s) }, null) }
            }
            else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                item {
                    Column {
                        Text(T("З резервної копії"), fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(top = 8.dp))
                        Text(T("Виберіть файл .pmbackup (з Google Drive, Proton Drive чи завантажень) і введіть ключ. Поточна медіатека буде замінена."), color = Dim, fontSize = 13.sp)
                        Spacer(Modifier.height(10.dp))
                        RestoreBackupButton()
                        Spacer(Modifier.height(20.dp))
                    }
                }
                item {
                    Text(T("За посиланням"), fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(top = 8.dp))
                    Text(T("Плейлист або альбом YouTube Music (music.youtube.com, youtube.com) чи Spotify (open.spotify.com)."), color = Dim, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(link, { link = it }, Modifier.fillMaxWidth(), placeholder = { Text("https://…") }, singleLine = true, shape = RoundedCornerShape(12.dp))
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { val l = link.trim(); run(l) { p -> Importer.fromLink(l, p) } }, enabled = link.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = Pal.accent, contentColor = Pal.onAccent)) {
                        Icon(Icons.Rounded.Link, null); Spacer(Modifier.width(8.dp)); Text(T("Імпортувати"))
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                    HorizontalDivider(Modifier.padding(vertical = 20.dp), color = Pal.chip)
                    Text(T("З Telegram"), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(T("У Telegram Desktop: Налаштування → Додатково → Експорт даних (або ⋮ у чаті → Експорт історії чату), формат JSON, з аудіофайлами. ") +
                        T("Перенесіть папку експорту на телефон і виберіть її: пісні з файлами гратимуть без інтернету, решту знайдемо на YouTube Music. ") +
                        T("Можна вибрати й лише result.json через «Вибрати файл» нижче. Застосунок не входить у ваш Telegram — він лише читає експорт."),
                        color = Dim, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = { pickTelegram.launch(null) }) {
                        Icon(Icons.Rounded.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text(T("Вибрати папку експорту"))
                    }
                    HorizontalDivider(Modifier.padding(vertical = 20.dp), color = Pal.chip)
                    Text(T("З таблиці"), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(T("CSV, TSV або Excel (.xlsx). Підходять експорти Exportify (Spotify), Google Takeout (YouTube Music), TuneMyMusic, ") +
                        T("Soundiiz або будь-яка таблиця зі стовпцями «назва» і «виконавець» (або «Video ID»)."), color = Dim, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = { pick.launch(arrayOf("text/*", "application/json", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel", "application/octet-stream")) }) {
                        Icon(Icons.Rounded.TableView, null); Spacer(Modifier.width(8.dp)); Text(T("Вибрати файл"))
                    }
                    HorizontalDivider(Modifier.padding(vertical = 20.dp), color = Pal.chip)
                    Text(T("Як це працює"), fontWeight = FontWeight.Bold)
                    Text(T("• YouTube Music: треки беруться напряму, повністю.\n") +
                        T("• Spotify: список читається з публічної сторінки плейлиста без входу (до 100 треків), потім кожен трек шукається в YouTube Music за назвою, виконавцем і тривалістю.\n") +
                        T("• Таблиця читається лише на пристрої; у YouTube надсилаються тільки пошукові запити «назва + виконавець».\n") +
                        T("• Результат зберігається як локальний плейлист — нікуди не синхронізується."),
                        color = Dim, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(bottom = 24.dp))
                }
            }
        }
    }
}
