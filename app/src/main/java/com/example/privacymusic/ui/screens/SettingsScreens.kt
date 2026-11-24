package com.example.privacymusic.ui.screens

import com.example.privacymusic.i18n.T
import com.example.privacymusic.i18n.I18n

import com.example.privacymusic.ui.theme.Danger

import com.example.privacymusic.ui.theme.Pal

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.privacymusic.account.AccountStore
import com.example.privacymusic.account.LoginActivity
import com.example.privacymusic.data.*
import com.example.privacymusic.net.Firewall
import com.example.privacymusic.net.YouTubeMusic
import com.example.privacymusic.playback.Downloads
import com.example.privacymusic.playback.Playback
import com.example.privacymusic.ui.LocalApp
import com.example.privacymusic.ui.theme.Dim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
internal fun SectionTitle(text: String) {
    if (Pal.minimal) {
        // Minimal: small grey caption above a rounded, inset group.
        Text(text.uppercase(), Modifier.padding(start = 32.dp, top = 22.dp, bottom = 6.dp), color = Dim, fontSize = 12.sp, letterSpacing = 0.4.sp)
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(6.dp).background(Pal.card, RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)))
    } else Text(text, Modifier.padding(start = 16.dp, top = 24.dp, bottom = 6.dp), color = Pal.accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
}

/** Closes a minimal-style group (rounded bottom edge). Draws nothing in the other style. */
@Composable
internal fun GroupEnd() {
    if (Pal.minimal) Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(6.dp).background(Pal.card, RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)))
}

@Composable
internal fun SettingRow(icon: ImageVector?, title: String, subtitle: String? = null, onClick: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null) {
    if (Pal.minimal) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).background(Pal.card)) {
            Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Box(Modifier.size(30.dp).background(Pal.accent, RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(18.dp), tint = Pal.onAccent) }
                    Spacer(Modifier.width(14.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 16.sp)
                    if (subtitle != null) Text(subtitle, color = Dim, fontSize = 13.sp)
                }
                if (trailing != null) trailing() else if (onClick != null) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Dim)
            }
            HorizontalDivider(Modifier.padding(start = if (icon != null) 58.dp else 14.dp), thickness = 0.5.dp, color = Pal.line)
        }
        return
    }
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, tint = Dim); Spacer(Modifier.width(18.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            if (subtitle != null) Text(subtitle, color = Dim, fontSize = 13.sp)
        }
        trailing?.invoke()
    }
}

@Composable
internal fun Toggle(icon: ImageVector?, title: String, subtitle: String?, pref: Settings.Pref<Boolean>, onChange: (Boolean) -> Unit = {}) {
    val value by pref.state.collectAsState()
    SettingRow(icon, title, subtitle, { pref.set(!value); onChange(!value) }) {
        Switch(value, { pref.set(it); onChange(it) }, colors = if (Pal.minimal) SwitchDefaults.colors(checkedTrackColor = Color(0xff34c759), checkedThumbColor = Color.White,
            checkedBorderColor = Color.Transparent, uncheckedTrackColor = Pal.line, uncheckedThumbColor = Color.White, uncheckedBorderColor = Color.Transparent) else SwitchDefaults.colors())
    }
}

@Composable
private fun <T> ChoiceDialog(title: String, options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(options) { (v, label) ->
                Row(Modifier.fillMaxWidth().clickable { onPick(v); onDismiss() }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(v == selected, { onPick(v); onDismiss() }); Text(label)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(T("Закрити")) } })
}

private val languages get() = listOf("uk" to T("Українська"), "en" to "English", "pl" to "Polski", "de" to "Deutsch", "fr" to "Français", "es" to "Español", "it" to "Italiano", "pt" to "Português", "cs" to "Čeština", "ja" to "日本語")
private val regions get() = listOf("UA" to T("Україна"), "PL" to T("Польща"), "DE" to T("Німеччина"), "US" to T("США"), "GB" to T("Велика Британія"), "FR" to T("Франція"), "CZ" to T("Чехія"), "CA" to T("Канада"), "ES" to T("Іспанія"), "IT" to T("Італія"), "JP" to T("Японія"))

@Composable
fun SettingsScreen() {
    val app = LocalApp.current
    val context = LocalContext.current
    val signedIn by AccountStore.signedIn.collectAsState()
    val quality by Settings.quality.state.collectAsState()
    val hl by Settings.language.state.collectAsState()
    val gl by Settings.region.state.collectAsState()
    val cacheMb by Settings.cacheMb.state.collectAsState()
    var dialog by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        TopBar(T("Налаштування"), onBack = { app.nav.popBackStack() })
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Card(Modifier.fillMaxWidth().padding(16.dp), colors = CardDefaults.cardColors(containerColor = Pal.card)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (signedIn) Icons.Rounded.AccountCircle else Icons.Rounded.Shield, null, Modifier.size(36.dp), tint = if (signedIn) Color.White else Pal.accent)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(if (signedIn) T("Акаунт YouTube Music підключено") else T("Гостьовий режим"), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                Text(if (signedIn) T("Пошук, потоки й рекомендації за замовчуванням ідуть без акаунта") else T("Без акаунта, без cookie, без ідентифікаторів пристрою"),
                                    color = Dim, fontSize = 13.sp)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        if (!signedIn) {
                            OutlinedButton(onClick = { dialog = "login" }) { Text(T("Увійти — лише якщо є YouTube Music Premium")) }
                            Text(T("Необов'язково. Після входу YouTube бачитиме більше про вас. Якість звуку від входу не змінюється."),
                                Modifier.padding(top = 8.dp), color = Dim, fontSize = 12.sp)
                        } else OutlinedButton(onClick = { dialog = "logout" }) { Text(T("Вийти з акаунта")) }
                    }
                }
            }
            if (signedIn) {
                item { SectionTitle(T("Що можна брати з акаунта")) }
                item { Toggle(Icons.Rounded.LibraryMusic, T("Медіатека акаунта"), T("Показувати ваші плейлисти, лайки й альбоми з YouTube Music у Медіатеці"), Settings.accountLibrary) }
                item { Toggle(Icons.Rounded.Home, T("Персональна головна"), T("Головна й огляд з вашими рекомендаціями (Google бачитиме, що ви відкриваєте застосунок)"), Settings.accountHome) }
                item { Toggle(Icons.Rounded.Sync, T("Надсилати дії в акаунт"), T("Лайки, підписки й збережені альбоми також записуються у ваш акаунт"), Settings.accountSync) }
                item { GroupEnd() }
            }
            item { SectionTitle(T("Мова")) }
            item {
                val ui = Settings.uiLanguage.value
                val name = I18n.languages.firstOrNull { it.first == ui }?.second ?: T("Як у системі")
                SettingRow(Icons.Rounded.Translate, T("Мова інтерфейсу"), name, { dialog = "ui" })
            }
            item { GroupEnd() }
            item { SectionTitle(T("Оформлення")) }
            item { SettingRow(Icons.Rounded.Palette, T("Кольори, фон і обкладинки"), T("Стиль інтерфейсу, кольори, фон, розмір тексту"), { app.nav.navigate("appearance") }) }
            item { GroupEnd() }
            item { SectionTitle(T("Відтворення")) }
            item { SettingRow(Icons.Rounded.HighQuality, T("Якість звуку"), Settings.audioQuality.label, { dialog = "quality" }) }
            item { Toggle(Icons.Rounded.AllInclusive, T("Автовідтворення"), T("Коли черга закінчується, продовжувати схожими піснями (як у YouTube Music)"), Settings.autoplay) }
            item { Toggle(Icons.Rounded.PlayCircle, T("Грати після закриття застосунку"), T("Вимкнено: коли ви змахуєте застосунок зі списку недавніх, музика зупиняється"), Settings.playAfterClose) }
            item { Toggle(Icons.Rounded.GraphicEq, T("Пропускати тишу"), null, Settings.skipSilence) { Playback.setSkipSilence(it) } }
            item { Toggle(Icons.Rounded.Restore, T("Пам'ятати чергу"), T("Зберігати поточну чергу на пристрої між запусками"), Settings.persistQueue) { if (!it) Playback.forgetQueueFile() } }
            item { GroupEnd() }
            item { SectionTitle(T("Пристрої поруч")) }
            item { SettingRow(Icons.Rounded.Devices, T("Пристрої поруч"), T("Комп'ютер у вашій мережі: перемикати, де грає музика, керувати й синхронізувати медіатеку"), { app.nav.navigate("devices") }) }
            item { GroupEnd() }
            item { SectionTitle(T("Вміст")) }
            item { SettingRow(Icons.Rounded.Language, T("Мова вмісту YouTube"), languages.firstOrNull { it.first == hl }?.second ?: hl, { dialog = "hl" }) }
            item { SettingRow(Icons.Rounded.Public, T("Регіон вмісту"), regions.firstOrNull { it.first == gl }?.second ?: gl, { dialog = "gl" }) }
            item { GroupEnd() }
            item { SectionTitle(T("Приватність")) }
            item { SettingRow(Icons.Rounded.Security, T("Що бачить YouTube"), T("Що він отримує, а що ні • журнал мережі"), { app.nav.navigate("privacy") }) }
            item { SettingRow(Icons.Rounded.Shield, T("Максимальна приватність"), T("Вимкнути все необов'язкове одним натиском"), { dialog = "max_privacy" }) }
            item { Toggle(Icons.Rounded.CloudOff, T("Офлайн-режим"), T("Застосунок не виходить у мережу взагалі: грають лише завантажені пісні й файли з пристрою"), Settings.offline) }
            item { Toggle(Icons.Rounded.Search, T("Підказки під час пошуку"), T("Вимкнено: YouTube отримує запит лише після натискання «Шукати», а не кожну набрану літеру"), Settings.searchSuggestions) }
            item { Toggle(Icons.Rounded.History, T("Історія прослуховування"), T("Записується тільки на цьому пристрої"), Settings.history) }
            item { Toggle(Icons.Rounded.ManageSearch, T("Історія пошуку"), T("Записується тільки на цьому пристрої"), Settings.searchHistory) }
            item { Toggle(Icons.Rounded.Lyrics, T("Синхронізовані тексти (LRCLIB)"), T("Вимкнено: тексти лише з YouTube Music. Увімкнено: назва й виконавець надсилаються на lrclib.net"), Settings.lrclib) }
            item { Toggle(Icons.Rounded.Lyrics, T("Поступове заповнення тексту"), T("Увімкнено: рядок заповнюється так, як його співають. Вимкнено: рядок підсвічується цілком"), Settings.lyricsFlow) }
            item { SettingRow(Icons.Rounded.DeleteSweep, T("Очистити історію прослуховування"), null, { dialog = "clear_history" }) }
            item { SettingRow(Icons.Rounded.SearchOff, T("Очистити історію пошуку"), null, { app.scope.launch(Dispatchers.IO) { Library.clearSearches() }; app.toast(T("Історію пошуку очищено")) }) }
            item { GroupEnd() }
            item { SectionTitle(T("Статистика")) }
            item { Toggle(Icons.Rounded.BarChart, T("Збирати статистику прослуховування"), T("Хвилини, топ пісень і виконавців, години — лише на цьому пристрої"), Settings.stats) }
            item { SettingRow(Icons.Rounded.Insights, T("Відкрити статистику"), null, { app.nav.navigate("stats") }) }
            item { GroupEnd() }
            item { SectionTitle(T("Резервні копії")) }
            item { SettingRow(Icons.Rounded.CloudUpload, T("Зашифрована резервна копія"), T("Google Drive, Proton Drive або файл • ключ зберігаєте ви"), { app.nav.navigate("backup") }) }
            item { GroupEnd() }
            item { SectionTitle(T("Сховище")) }
            item { SettingRow(Icons.Rounded.Storage, T("Кеш аудіо"), T("{0} МБ • зміни застосуються після перезапуску", cacheMb), { dialog = "cache" }) }
            item { SettingRow(Icons.Rounded.CleaningServices, T("Очистити кеш"), T("Аудіо й обкладинки, які зберігалися для швидкого відтворення"), {
                app.scope.launch(Dispatchers.IO) {
                    Playback.cache.keys.toList().forEach { runCatching { Playback.cache.removeResource(it) } }
                    context.cacheDir.resolve("artwork").deleteRecursively()
                }
                app.toast(T("Кеш очищено"))
            }) }
            item { SettingRow(Icons.Rounded.DeleteForever, T("Стерти всі дані"), T("Медіатека, історія, плейлисти, завантаження, кеш і акаунт"), { dialog = "wipe" }) }
            item { GroupEnd() }
            item { SectionTitle(T("Підтримка")) }
            item { SettingRow(Icons.Rounded.Favorite, T("Підтримати автора"), null, { app.nav.navigate("support") }) }
            item { SettingRow(Icons.Rounded.BugReport, T("Повідомити про помилку"), T("Або запропонувати ідею"), { app.nav.navigate("report?kind=bug") }) }
            item { GroupEnd() }
            item { SectionTitle(T("Про застосунок")) }
            item { SettingRow(Icons.Rounded.Info, "Privacy Music " + remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }, T("Без Google SDK, без Play Services, без OAuth, без аналітики. Каталог — YouTube Music, потоки — NewPipe Extractor.")) }
            item { UpdateRow() }
            item { Toggle(Icons.Rounded.NewReleases, T("Повідомляти про оновлення"), T("Раз на день запитувати в GitHub номер нової версії. GitHub бачить лише IP-адресу"), Settings.updateCheck) }
            item { GroupEnd() }
        }
    }
    when (dialog) {
        "ui" -> ChoiceDialog(T("Мова інтерфейсу"), listOf("system" to T("Як у системі")) + I18n.languages, Settings.uiLanguage.value,
            { Settings.uiLanguage.set(it) }, { dialog = null })

        "quality" -> ChoiceDialog(T("Якість звуку"), AudioQuality.entries.map { it.name to it.label }, quality, { Settings.quality.set(it) }, { dialog = null })
        "hl" -> ChoiceDialog(T("Мова вмісту YouTube"), languages, hl, { Settings.language.set(it); YouTubeMusic.resetSession() }, { dialog = null })
        "gl" -> ChoiceDialog(T("Регіон вмісту"), regions, gl, { Settings.region.set(it); YouTubeMusic.resetSession() }, { dialog = null })
        "cache" -> ChoiceDialog(T("Кеш аудіо"), listOf(128, 256, 512, 1024, 2048, 4096).map { it to T("{0} МБ", it) }, cacheMb, { Settings.cacheMb.set(it) }, { dialog = null })
        "clear_history" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text(T("Очистити історію?")) },
            text = { Text(T("Історію прослуховування буде видалено з пристрою. Лайки й плейлисти залишаться.")) },
            confirmButton = { TextButton(onClick = { app.scope.launch(Dispatchers.IO) { Library.clearHistory() }; dialog = null }) { Text(T("Очистити")) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(T("Скасувати")) } })
        "wipe" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text(T("Стерти всі дані?")) },
            text = { Text(T("Буде видалено всю медіатеку, історію, пошуки, плейлисти, завантаження, кеш, чергу та вихід з акаунта. Це не можна скасувати.")) },
            confirmButton = { TextButton(onClick = {
                dialog = null
                Playback.clearQueue(); Playback.forgetQueueFile(); AccountStore.signOut(); YouTubeMusic.resetSession()
                app.scope.launch(Dispatchers.IO) {
                    Downloads.deleteAll(); Library.wipeAll()
                    Playback.cache.keys.toList().forEach { runCatching { Playback.cache.removeResource(it) } }
                    context.cacheDir.resolve("artwork").deleteRecursively()
                }
                app.toast(T("Усі дані стерто"))
            }) { Text(T("Стерти"), color = Danger) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(T("Скасувати")) } })
        "max_privacy" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text(T("Максимальна приватність")) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(T("Буде вимкнено:\n• тексти в такт (LRCLIB)\n• підказки під час пошуку\n• історію прослуховування й пошуку\n• статистику\n• збереження черги між запусками\n• персональну головну й надсилання дій в акаунт (якщо ви увійшли)\n\n") +
                    T("Після цього в мережу йдуть лише запити, без яких музика не грає: пошук, сторінки й аудіопотік. Ви завжди можете ввімкнути будь-що назад.\n\n") +
                    T("Ще більше: офлайн-режим (нуль запитів, грають лише завантаження), не входити в акаунт і VPN, щоб YouTube не бачив вашу IP-адресу."), fontSize = 14.sp)
            }
        }, confirmButton = { TextButton(onClick = {
            dialog = null
            Settings.lrclib.set(false); Settings.searchSuggestions.set(false); Settings.history.set(false); Settings.searchHistory.set(false)
            Settings.stats.set(false); Settings.persistQueue.set(false); Playback.forgetQueueFile()
            Settings.accountHome.set(false); Settings.accountSync.set(false); YouTubeMusic.resetSession()
            app.toast(T("Максимальну приватність увімкнено"))
        }) { Text(T("Увімкнути")) } }, dismissButton = { TextButton(onClick = { dialog = null }) { Text(T("Скасувати")) } })
        "login" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text(T("Вхід лише для YouTube Music Premium")) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) { Text(
            T("Вхід потрібен, тільки якщо у вас є підписка YouTube Music Premium і ви хочете бачити свою медіатеку, лайки, плейлисти й рекомендації з акаунта. Без підписки він нічого не додає: фонове відтворення, завантаження й відсутність реклами працюють і так.\n\n") +
                T("Що зміниться для приватності: YouTube знатиме, хто ви, і бачитиме, коли ви відкриваєте свою медіатеку чи персональну головну, а також лайки й підписки, якщо ввімкнете їх надсилання. Без входу він не знає, хто ви.\n\n") +
                T("Якість звуку від входу не змінюється: застосунок завжди бере найкращий доступний потік (Opus до ~160 кбіт/с). Режим 256 кбіт/с Premium працює лише в офіційному клієнті Google.\n\n") +
                T("Як це зроблено:\n• Ви входите на сторінці Google в одноразовому WebView — без OAuth, без Google SDK і Play Services.\n") +
                T("• Ми зберігаємо лише cookie сесії, зашифровані ключем Android Keystore; усе інше з WebView стирається.\n") +
                T("• Сторінку music.youtube.com не завантажуємо, трекери в WebView заблоковано.\n") +
                T("• Пошук і потоки й далі йдуть без акаунта; відтворення не повідомляється Google, тож не потрапляє в історію YouTube.\n\n") +
                T("Google побачить сам факт входу і ті розділи акаунта, які ви відкриєте."), fontSize = 14.sp) }
        }, confirmButton = { TextButton(onClick = { dialog = null; context.startActivity(Intent(context, LoginActivity::class.java)) }) { Text(T("Продовжити")) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(T("Скасувати")) } })
        "logout" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text(T("Вийти з акаунта?")) },
            text = { Text(T("Cookie сесії буде видалено з пристрою разом із ключем шифрування. Локальна медіатека залишиться.")) },
            confirmButton = { TextButton(onClick = { AccountStore.signOut(); dialog = null }) { Text(T("Вийти")) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(T("Скасувати")) } })
    }
}

@Composable
private fun PrivacyBlock(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, points: List<String>) {
    Card(colors = CardDefaults.cardColors(containerColor = Pal.card)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = Pal.accent); Text(title, Modifier.padding(start = 10.dp), fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
            points.forEach { p ->
                Row(Modifier.padding(top = 8.dp)) { Text("•", color = Dim, modifier = Modifier.width(14.dp)); Text(p, color = Dim, fontSize = 14.sp, lineHeight = 20.sp) }
            }
        }
    }
}

@Composable
fun PrivacyScreen() {
    val app = LocalApp.current
    val blocked by Firewall.blocked.collectAsState()
    val allowed by Firewall.allowedCount.collectAsState()
    val signedIn by AccountStore.signedIn.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TopBar(T("Приватність"), onBack = { app.nav.popBackStack() })
        LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp)) {
            item {
                val lrclib by Settings.lrclib.state.collectAsState()
                PrivacyBlock(T("Що бачить YouTube"), Icons.Rounded.Visibility, buildList {
                    add(T("IP-адресу пристрою — як і будь-який сайт. Щоб приховати її, використовуйте VPN або Tor (Orbot) на рівні системи."))
                    add(T("Те, що ви шукаєте й відкриваєте: пошуковий запит, сторінку альбому, виконавця чи плейлиста та ID пісні, яку вмикаєте. Без цього музику не отримати."))
                    add(T("Тимчасовий гостьовий токен сесії. Він живе лише в пам'яті й зникає, щойно ви закриваєте застосунок."))
                    if (Settings.searchSuggestions.value) add(T("Підказки пошуку: текст, який ви друкуєте, ще до натискання «Шукати». Це можна вимкнути в налаштуваннях."))
                    if (Settings.offline.value) add(T("Зараз нічого: увімкнено офлайн-режим, і фаєрвол блокує всі запити."))
                    if (signedIn) add(T("Cookie вашого акаунта, але тільки для розділів, які ви дозволили в налаштуваннях акаунта."))
                })
                Spacer(Modifier.height(14.dp))
                PrivacyBlock(T("Чого YouTube не отримує"), Icons.Rounded.VisibilityOff, buildList {
                    if (signedIn) add(T("Акаунта під час пошуку й відтворення: ці запити йдуть як гостьові."))
                    else add(T("Жодних cookie, входу в акаунт, ID пристрою чи рекламного ID."))
                    add(T("Звітів про відтворення (playback, watchtime, qoe, ptracking). Тому прослуховування не потрапляють в історію Google."))
                    add(T("Вашої медіатеки, лайків, плейлистів, історії й статистики: вони лише в базі на цьому пристрої."))
                    add(T("Аналітики та звітів про збої: у застосунку немає SDK Google, Firebase чи Play Services (збірка падає, якщо такий з'являється)."))
                    add(T("Резервних копій: вони зашифровані вашим ключем і потрапляють лише туди, куди ви самі їх відправите."))
                })
                Spacer(Modifier.height(14.dp))
                PrivacyBlock(T("Інші сервіси"), Icons.Rounded.Public, buildList {
                    add(if (lrclib) T("LRCLIB (тексти в такт): отримує назву, виконавця й тривалість пісні, текст якої ви відкриваєте.") else T("LRCLIB (тексти в такт) вимкнено, тож запитів туди немає."))
                    add(T("Spotify: лише під час імпорту за посиланням — відкривається публічна сторінка плейлиста, без входу."))
                    add(T("Сервери обкладинок Google (i.ytimg.com, googleusercontent.com): бачать IP-адресу, коли завантажується картинка."))
                })
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(T("Мережевий фаєрвол"), Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    TextButton(onClick = Firewall::clearLog) { Text(T("Очистити")) }
                }
                Text(T("Дозволено запитів: {0} • заблоковано: {1}", allowed, blocked.size), color = Dim, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                if (blocked.isEmpty()) Text(T("За цю сесію нічого не довелося блокувати."), color = Dim, fontSize = 13.sp)
            }
            items(blocked) { b ->
                Column(Modifier.padding(vertical = 6.dp)) {
                    Text("${b.method} ${b.host}", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(b.path + " • " + java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(b.time)), color = Dim, fontSize = 12.sp)
                }
            }
        }
    }
}

/** "Check for updates": asks GitHub for the latest release; if it is newer, downloads the APK and opens Android's installer. */
@Composable
private fun UpdateRow() {
    val app = LocalApp.current
    val context = LocalContext.current
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<com.example.privacymusic.net.Updater.Release?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    val installed = remember { com.example.privacymusic.net.Updater.installedVersion(context) }
    SettingRow(Icons.Rounded.SystemUpdate, T("Перевірити оновлення"), status ?: T("Версія {0}", installed), {
        if (busy) return@SettingRow
        if (Settings.offline.value) { app.toast(T("Офлайн-режим: мережу вимкнено в налаштуваннях приватності")); return@SettingRow }
        busy = true; status = T("Перевіряю…")
        app.scope.launch {
            val r = runCatching { com.example.privacymusic.net.Updater.latest() }
            busy = false
            val rel = r.getOrNull()
            status = when {
                r.isFailure -> T("Не вдалося перевірити. Спробуйте пізніше.")
                rel == null || !com.example.privacymusic.net.Updater.newer(rel.version, installed) -> T("У вас найновіша версія")
                else -> { found = rel; T("Доступна версія {0}", rel.version) }
            }
        }
    }) { if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
    val rel = found ?: return
    AlertDialog(onDismissRequest = { if (progress == null) found = null },
        title = { Text(T("Оновлення до версії {0}", rel.version)) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                if (rel.notes.isNotBlank()) Text(rel.notes, fontSize = 14.sp, color = Dim)
                progress?.let { Spacer(Modifier.height(12.dp)); LinearProgressIndicator(progress = { it }, Modifier.fillMaxWidth()) }
                if (rel.apkUrl == null) Text(T("У цьому випуску немає файлу для Android."), fontSize = 14.sp)
            }
        },
        confirmButton = {
            TextButton(enabled = progress == null && rel.apkUrl != null, onClick = {
                progress = 0f
                app.scope.launch {
                    val f = runCatching { com.example.privacymusic.net.Updater.download(context, rel) { p -> progress = p } }
                    progress = null
                    f.onSuccess { file ->
                        if (!com.example.privacymusic.net.Updater.install(context, file)) app.toast(T("Дозвольте встановлення з Privacy Music і натисніть «Оновити» ще раз"))
                        else found = null
                    }.onFailure { app.toast(T("Не вдалося завантажити оновлення")) }
                }
            }) { Text(T("Оновити")) }
        },
        dismissButton = { TextButton(enabled = progress == null, onClick = { found = null }) { Text(T("Пізніше")) } })
}
