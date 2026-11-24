package com.example.privacymusic.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.privacymusic.data.Settings
import com.example.privacymusic.i18n.T
import com.example.privacymusic.net.Link
import com.example.privacymusic.playback.Playback
import com.example.privacymusic.ui.LocalApp
import com.example.privacymusic.ui.components.Thumb
import com.example.privacymusic.ui.theme.Dim
import com.example.privacymusic.ui.theme.Pal
import kotlinx.coroutines.delay

/** Settings → Devices nearby: turn it on, pair the computer, choose what is shared. */
@Composable
fun DevicesScreen() {
    val app = LocalApp.current
    val enabled by Settings.link.state.collectAsState()
    val devices by Link.devices.collectAsState()
    val pairing by Link.pairing.collectAsState()
    val found by Link.pairable.collectAsState()
    val sync by Settings.linkSync.state.collectAsState()
    var denied by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { Settings.link.set(true); Link.apply() } else denied = true
    }
    fun enable(on: Boolean) {
        if (on && Link.needsPermission() && !Link.hasPermission()) { permission.launch(Link.PERMISSION); return }
        Settings.link.set(on); Link.apply()
    }
    DisposableEffect(Unit) { onDispose { if (Link.pairing.value !is Link.Pairing.Confirm) Link.cancelPairing() } }
    Column(Modifier.fillMaxSize()) {
        TopBar(T("Пристрої поруч"), onBack = { app.nav.popBackStack() })
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Text(T("Телефон і комп'ютер знаходять одне одного лише у вашій мережі Wi‑Fi і спілкуються напряму, з наскрізним шифруванням. Жодних серверів чи акаунтів; якщо вимкнено, застосунок нічого не надсилає й не слухає."),
                    Modifier.padding(16.dp), color = Dim, fontSize = 13.sp)
            }
            item { SectionTitle(T("Зв'язок")) }
            item {
                SettingRow(Icons.Rounded.Devices, T("Пристрої поруч"), T("Перемикати, де грає музика, керувати з телефона, синхронізувати медіатеку"), { enable(!enabled) }) {
                    Switch(enabled, { enable(it) })
                }
            }
            if (denied) item { SettingRow(Icons.Rounded.Info, T("Немає доступу до локальної мережі"), T("Дозвольте «Пристрої поблизу» для Privacy Music у налаштуваннях Android")) }
            item { GroupEnd() }
            if (enabled) {
                item { SectionTitle(T("Під'єднані пристрої")) }
                items(devices, key = { it.id }) { d ->
                    SettingRow(Icons.Rounded.Computer, d.name, if (d.online) T("У мережі") else T("Не в мережі")) {
                        TextButton(onClick = { Link.forget(d.id); app.toast(T("Пристрій забуто")) }) { Text(T("Забути")) }
                    }
                }
                item { PairingBlock(pairing, found) }
                item { GroupEnd() }
                item { SectionTitle(T("Що спільне")) }
                item { Toggle(Icons.Rounded.Sync, T("Синхронізувати медіатеку"), T("Вподобані пісні, плейлисти, збережені альбоми й виконавці. Історія та статистика лишаються на кожному пристрої окремо"), Settings.linkSync) { if (it) Link.syncNow() } }
                if (sync) item { SettingRow(Icons.Rounded.SyncAlt, T("Синхронізувати зараз"), null, { Link.syncNow(); app.toast(T("Медіатеку надіслано")) }) }
                item { Toggle(Icons.Rounded.Tune, T("Керування з інших пристроїв"), T("Комп'ютер може ставити на паузу, перемикати пісні, змінювати гучність і забирати музику на себе"), Settings.linkControl) }
                item { Toggle(Icons.Rounded.Devices, T("Грати лише на одному пристрої"), T("Коли музика вмикається тут, вона ставиться на паузу на іншому пристрої (як у Spotify)"), Settings.linkSingle) }
                item { GroupEnd() }
            }
        }
    }
}

@Composable
private fun PairingBlock(pairing: Link.Pairing, found: List<Link.Pairable>) {
    when (pairing) {
        is Link.Pairing.Searching -> {
            SettingRow(null, T("Пошук комп'ютера…"), T("На комп'ютері відкрийте Налаштування → Пристрої поруч → «Під'єднати телефон»")) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Pal.accent)
            }
            found.forEach { p -> SettingRow(Icons.Rounded.Computer, p.name, T("Натисніть, щоб під'єднати"), { Link.pairWith(p) }) }
            var manual by remember { mutableStateOf<String?>(null) }
            SettingRow(Icons.Rounded.Edit, T("Ввести адресу вручну"), T("Якщо роутер не пропускає пошук пристроїв"), { manual = "" })
            SettingRow(null, T("Скасувати"), null, { Link.cancelPairing() })
            manual?.let { text ->
                AlertDialog(onDismissRequest = { manual = null }, title = { Text(T("Адреса комп'ютера")) },
                    text = {
                        Column {
                            Text(T("Її показано на комп'ютері під час під'єднання, наприклад 192.168.1.20"), color = Dim, fontSize = 13.sp)
                            OutlinedTextField(text, { manual = it.trim() }, Modifier.padding(top = 8.dp), singleLine = true)
                        }
                    },
                    confirmButton = { TextButton(onClick = { val a = text; manual = null; Link.pairWithAddress(a) }, enabled = text.isNotBlank()) { Text(T("Під'єднати")) } },
                    dismissButton = { TextButton(onClick = { manual = null }) { Text(T("Скасувати")) } })
            }
        }
        is Link.Pairing.Connecting -> SettingRow(null, T("З'єднання з «{0}»…", pairing.name), null) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Pal.accent) }
        is Link.Pairing.Confirm -> Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).background(Pal.card).padding(16.dp)) {
            Text(T("Перевірте, що на «{0}» показано той самий код:", pairing.name))
            Text(pairing.code.chunked(3).joinToString(" "), Modifier.padding(vertical = 10.dp), fontSize = 36.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 6.sp)
            Text(T("Якщо коди відрізняються, хтось у мережі намагається втрутитися — скасуйте."), color = Dim, fontSize = 13.sp)
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { Link.confirmPairing(true) }, colors = ButtonDefaults.buttonColors(containerColor = Pal.accent, contentColor = Pal.onAccent)) { Text(T("Код збігається")) }
                OutlinedButton(onClick = { Link.confirmPairing(false) }) { Text(T("Скасувати")) }
            }
        }
        else -> {
            if (pairing is Link.Pairing.Done) SettingRow(Icons.Rounded.Check, T("«{0}» під'єднано", pairing.name), null)
            if (pairing is Link.Pairing.Failed) SettingRow(Icons.Rounded.Info, pairing.message, null)
            SettingRow(Icons.Rounded.Add, T("Під'єднати комп'ютер"), T("Потрібен Privacy Music на комп'ютері в тій самій мережі Wi‑Fi"), { Link.startPairing() })
        }
    }
}

/** Where the music plays: this phone or the paired computer (switch with one tap), like Spotify's device picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesSheet(onDismiss: () -> Unit) {
    val app = LocalApp.current
    val enabled by Settings.link.state.collectAsState()
    val devices by Link.devices.collectAsState()
    val controlling by Link.controlling.collectAsState()
    val playing by Playback.isPlaying.collectAsState()
    val current by Playback.current.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Pal.card) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(T("Пристрої поруч"), Modifier.padding(horizontal = 20.dp, vertical = 8.dp), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            if (!enabled) {
                Text(T("Перемикайте, де грає музика, керуйте комп'ютером з телефона й синхронізуйте медіатеку. Лише у вашій локальній мережі, із наскрізним шифруванням."),
                    Modifier.padding(horizontal = 20.dp, vertical = 6.dp), color = Dim, fontSize = 14.sp)
                TextButton(onClick = { onDismiss(); app.nav.navigate("devices") }, Modifier.padding(horizontal = 12.dp)) { Text(T("Налаштувати")) }
                return@Column
            }
            val here = playing || controlling == null
            DeviceLine(Icons.Rounded.Smartphone, T("Цей телефон"), if (playing) T("Грає зараз") else T("Тут"), here) {}
            devices.forEach { d ->
                val r = d.remote
                val status = when { !d.online -> T("Не в мережі"); r?.playing == true && r.song != null -> T("Грає: {0}", r.song.title); else -> T("У мережі") }
                DeviceLine(Icons.Rounded.Computer, d.name, status, controlling == d.id && !playing, enabled = d.online) {
                    if (d.online && current != null && (playing || r?.playing != true)) TextButton(onClick = { Link.handOff(d.id); onDismiss() }) { Text(T("Грати там")) }
                    if (d.online && r?.playing == true) TextButton(onClick = { Link.pull(d.id); onDismiss() }) { Text(T("Слухати тут")) }
                }
            }
            if (devices.isEmpty()) Text(T("Ще немає під'єднаних пристроїв."), Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = Dim)
            TextButton(onClick = { onDismiss(); app.nav.navigate("devices") }, Modifier.padding(horizontal = 12.dp)) {
                Text(if (devices.isEmpty()) T("Під'єднати комп'ютер…") else T("Налаштування пристроїв"))
            }
        }
    }
}

@Composable
private fun DeviceLine(icon: androidx.compose.ui.graphics.vector.ImageVector, name: String, status: String, active: Boolean, enabled: Boolean = true, actions: @Composable RowScope.() -> Unit) {
    val tint = if (active) Pal.accent else Color.White
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (enabled) tint else Dim)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(name, color = if (enabled) tint else Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(status, color = Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (active) Icon(Icons.Rounded.GraphicEq, null, Modifier.size(20.dp), tint = Pal.accent)
        actions()
    }
}

/** Small "devices" button for the players (lit when a paired computer is online). */
@Composable
fun DevicesButton(tint: Color = Color.White) {
    val enabled by Settings.link.state.collectAsState()
    val devices by Link.devices.collectAsState()
    var open by remember { mutableStateOf(false) }
    val online = enabled && devices.any { it.online }
    IconButton(onClick = { open = true }) { Icon(Icons.Rounded.Devices, T("Пристрої поруч"), tint = if (online) Pal.accent else tint) }
    if (open) DevicesSheet { open = false }
}

/** The paired computer that plays while nothing plays here (shown instead of the mini player, with its controls). */
@Composable
fun rememberRemoteDevice(): Link.Device? {
    val playing by Playback.isPlaying.collectAsState()
    val devices by Link.devices.collectAsState()
    val controlling by Link.controlling.collectAsState()
    if (playing) return null
    return devices.firstOrNull { d -> d.online && d.remote?.song != null && (d.remote.playing || controlling == d.id) }
}

@Composable
fun RemoteMiniPlayer(device: Link.Device) {
    val r = device.remote ?: return
    val song = r.song ?: return
    var sheet by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(r.playing) { while (r.playing) { delay(500); tick++ } }
    LaunchedEffect(device.id) { if (Link.controlling.value != device.id) Link.controlling.value = device.id }
    val pos = remember(tick, r) { r.position() }
    Column(Modifier.fillMaxWidth().background(if (Pal.minimal) Pal.card else Pal.chip).clickable { sheet = true }) {
        if (!Pal.minimal) LinearProgressIndicator({ if (r.dur > 0) pos.toFloat() / r.dur else 0f }, Modifier.fillMaxWidth().height(2.dp), color = Pal.accent, trackColor = Pal.line, drawStopIndicator = {})
        Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumb(song.thumbnail, Modifier.size(44.dp), 160)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Computer, null, Modifier.size(13.dp), tint = Pal.accent)
                    Text(" " + T("Відтворюється на «{0}»", device.name), maxLines = 1, overflow = TextOverflow.Ellipsis, color = Pal.accent, fontSize = 12.sp)
                }
            }
            IconButton(onClick = { Link.remote(device.id, "toggle") }) { Icon(if (r.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, T("Відтворити/пауза"), Modifier.size(30.dp)) }
            IconButton(onClick = { Link.remote(device.id, "next") }) { Icon(Icons.Rounded.SkipNext, T("Наступна"), Modifier.size(28.dp)) }
        }
    }
    if (sheet) RemoteSheet(device) { sheet = false }
}

/** Full remote control for the computer: seek, previous / next, volume, continue on the phone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteSheet(device: Link.Device, onDismiss: () -> Unit) {
    val live by Link.devices.collectAsState()
    val d = live.firstOrNull { it.id == device.id } ?: device
    val r = d.remote
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(r?.playing) { while (r?.playing == true) { delay(500); tick++ } }
    var seeking by remember { mutableStateOf<Float?>(null) }
    var vol by remember(d.id) { mutableStateOf<Float?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Pal.card) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Computer, null, Modifier.size(16.dp), tint = Pal.accent)
                Text(" " + T("Відтворюється на «{0}»", d.name), color = Pal.accent, fontSize = 13.sp)
            }
            val song = r?.song
            if (song != null) {
                Thumb(song.thumbnail, Modifier.padding(top = 16.dp).size(220.dp), 544, RoundedCornerShape(12.dp))
                Text(song.title, Modifier.padding(top = 16.dp), fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artistText, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (r != null) {
                val pos = remember(tick, r) { r.position() }
                Slider(seeking ?: (if (r.dur > 0) pos.toFloat() / r.dur else 0f), { seeking = it },
                    onValueChangeFinished = { seeking?.let { Link.remote(d.id, "seek", (it * r.dur).toLong()) }; seeking = null },
                    modifier = Modifier.padding(top = 12.dp), colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Pal.accent))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    IconButton(onClick = { Link.remote(d.id, "prev") }, Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipPrevious, T("Попередня"), Modifier.size(38.dp)) }
                    FilledIconButton(onClick = { Link.remote(d.id, "toggle") }, Modifier.size(68.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Pal.accent, contentColor = Pal.onAccent)) {
                        Icon(if (r.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, T("Відтворити/пауза"), Modifier.size(40.dp))
                    }
                    IconButton(onClick = { Link.remote(d.id, "next") }, Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipNext, T("Наступна"), Modifier.size(38.dp)) }
                }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.VolumeDown, null, tint = Dim)
                    Slider(vol ?: r.vol, { vol = it }, Modifier.weight(1f), onValueChangeFinished = { vol?.let { Link.remote(d.id, "vol", it.toDouble()) } },
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White))
                    Icon(Icons.Rounded.VolumeUp, null, tint = Dim)
                }
                Button(onClick = { Link.pull(d.id); onDismiss() }, Modifier.padding(top = 10.dp), colors = ButtonDefaults.buttonColors(containerColor = Pal.accent, contentColor = Pal.onAccent)) {
                    Icon(Icons.Rounded.Smartphone, null); Text("  " + T("Слухати на телефоні"))
                }
            }
        }
    }
}
