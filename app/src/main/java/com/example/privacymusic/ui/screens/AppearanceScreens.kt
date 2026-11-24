package com.example.privacymusic.ui.screens

import com.example.privacymusic.i18n.T

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.privacymusic.Links
import com.example.privacymusic.data.Diag
import com.example.privacymusic.data.Settings
import com.example.privacymusic.ui.LocalApp
import com.example.privacymusic.ui.theme.Dim
import com.example.privacymusic.ui.theme.Pal
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val bgPresets get() = listOf(
    "AMOLED" to 0xFF000000, T("Графіт") to 0xFF121212, T("Північ") to 0xFF0B1220, T("Ліс") to 0xFF0B1A12,
    T("Вино") to 0xFF1C0B12, T("Фіолет") to 0xFF140D22, T("Океан") to 0xFF061A22, T("Кава") to 0xFF1A130D,
)
private val APPLE_RED = 0xFFFA2D48.toInt()
private val accentPresets = listOf(
    0xFFFFFFFF, 0xFFFA2D48, 0xFFFF0033, 0xFFFF5C8A, 0xFFFF9500, 0xFFFFD60A, 0xFF1ED760, 0xFF30D5C8,
    0xFF0A84FF, 0xFF5E5CE6, 0xFFBF5AF2, 0xFFA2845E,
)

@Composable
private fun Swatch(color: Color, selected: Boolean, label: String? = null, onClick: () -> Unit) {
    val ring by animateColorAsState(if (selected) Pal.accent else Color.Transparent, label = "ring")
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
        Box(Modifier.size(52.dp).clip(CircleShape).border(3.dp, ring, CircleShape).padding(5.dp).clip(CircleShape)
            .background(color).border(1.dp, Color.White.copy(alpha = .25f), CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Rounded.Check, null, tint = if (color.red + color.green + color.blue > 1.8f) Color.Black else Color.White)
        }
        if (label != null) Text(label, fontSize = 11.sp, color = Dim, maxLines = 1)
    }
}

/** HSV picker. For backgrounds the brightness is capped so white text always stays readable. */
@Composable
private fun ColorPickerDialog(title: String, initial: Int, maxValue: Float, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) } }
    var h by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1]) }
    var v by remember { mutableFloatStateOf(hsv[2].coerceAtMost(maxValue)) }
    val color = Color(android.graphics.Color.HSVToColor(floatArrayOf(h, sat, v)))
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column {
            Box(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(12.dp)).background(color))
            Text("#%06X".format(color.toArgb() and 0xFFFFFF), Modifier.padding(top = 6.dp), color = Dim, fontSize = 13.sp)
            Text(T("Відтінок"), Modifier.padding(top = 10.dp), fontSize = 13.sp)
            Slider(h, { h = it }, valueRange = 0f..360f)
            Text(T("Насиченість"), fontSize = 13.sp)
            Slider(sat, { sat = it })
            Text(T("Яскравість"), fontSize = 13.sp)
            Slider(v, { v = it }, valueRange = 0f..maxValue)
        }
    }, confirmButton = { TextButton(onClick = { onPick(color.toArgb()); onDismiss() }) { Text(T("Готово")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(T("Скасувати")) } })
}

@Composable
private fun Label(text: String) = Text(text, Modifier.padding(start = 16.dp, top = 22.dp, bottom = 8.dp), fontWeight = FontWeight.Bold, fontSize = 16.sp)

@Composable
private fun StyleOption(modifier: Modifier, selected: Boolean, title: String, text: String, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(14.dp), color = Pal.card,
        border = androidx.compose.foundation.BorderStroke(if (selected) 2.dp else 1.dp, if (selected) Pal.accent else Pal.line)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(text, color = Dim, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
fun AppearanceScreen() {
    val app = LocalApp.current
    val context = LocalContext.current
    val bg = Settings.themeBg.value; val accent = Settings.themeAccent.value
    val dim by Settings.themeDim.state.collectAsState()
    val blur by Settings.themeBlur.state.collectAsState()
    val corner by Settings.themeCorner.state.collectAsState()
    val font by Settings.themeFont.state.collectAsState()
    val hasImage = Pal.image != null
    var picker by remember { mutableStateOf<String?>(null) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) app.scope.launch { if (!Pal.setImage(context, uri)) app.toast(T("Не вдалося відкрити зображення")) }
    }
    LaunchedEffect(blur) { delay(250); Pal.reload(context) }

    Column(Modifier.fillMaxSize()) {
        TopBar(T("Оформлення"), onBack = { app.nav.popBackStack() })
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            item { Label(T("Стиль інтерфейсу")) }
            item {
                val style by Settings.uiStyle.state.collectAsState()
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StyleOption(Modifier.weight(1f), style == "ytm", T("Як YouTube Music"), T("Щільні полиці, чипи дій, звичні вкладки")) {
                        Settings.uiStyle.set("ytm")
                        if (Settings.themeAccent.value == APPLE_RED) Settings.themeAccent.set(0xFFFFFFFF.toInt())
                    }
                    StyleOption(Modifier.weight(1f), style == "minimal", T("Мінімалістичний"), T("У дусі Apple Music: великі заголовки, плаваюча панель, просторий плеєр")) {
                        Settings.uiStyle.set("minimal")
                        // The minimal look's signature accent, unless the user already picked their own colour.
                        if (Settings.themeAccent.value == 0xFFFFFFFF.toInt()) Settings.themeAccent.set(APPLE_RED)
                    }
                }
            }
            item {
                // Live preview
                Card(Modifier.fillMaxWidth().padding(16.dp), colors = CardDefaults.cardColors(containerColor = Pal.card)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(56.dp).clip(RoundedCornerShape(corner.dp)).background(Pal.chip), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.MusicNote, null, tint = Dim) }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(T("Назва пісні"), fontWeight = FontWeight.Medium); Text(T("Виконавець • 3:21"), color = Dim, fontSize = 13.sp)
                        }
                        FilledIconButton(onClick = {}, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Pal.accent, contentColor = Pal.onAccent)) { Icon(Icons.Rounded.PlayArrow, null) }
                    }
                    Slider(0.4f, {}, Modifier.padding(horizontal = 14.dp), colors = SliderDefaults.colors(thumbColor = Pal.accent, activeTrackColor = Pal.accent))
                }
            }
            item { Label(T("Колір фону")) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(bgPresets) { (name, c) -> Swatch(Color(c), bg == c.toInt(), name) { Settings.themeBg.set(c.toInt()) } }
                    item { Swatch(Color(bg), bgPresets.none { it.second.toInt() == bg }, T("Свій")) { picker = "bg" } }
                }
            }
            item { Label(T("Акцентний колір")) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(accentPresets) { c -> Swatch(Color(c), accent == c.toInt()) { Settings.themeAccent.set(c.toInt()) } }
                    item { Swatch(Color(accent), accentPresets.none { it.toInt() == accent }, T("Свій")) { picker = "accent" } }
                }
            }
            item { Label(T("Фонове зображення")) }
            item {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Icon(Icons.Rounded.Image, null); Spacer(Modifier.width(8.dp)); Text(if (hasImage) T("Змінити") else T("Вибрати зображення"))
                    }
                    if (hasImage) OutlinedButton(onClick = { Pal.clearImage(context) }) { Text(T("Прибрати")) }
                }
                Text(T("Зображення копіюється в приватну пам'ять застосунку. Системний вибір фото не дає застосунку доступу до решти галереї."),
                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = Dim, fontSize = 12.sp)
                if (hasImage) {
                    Text(T("Затемнення: {0}%", dim), Modifier.padding(start = 16.dp, top = 8.dp), fontSize = 14.sp)
                    Slider(dim.toFloat(), { Settings.themeDim.set(it.toInt()) }, Modifier.padding(horizontal = 16.dp), valueRange = 20f..95f)
                    Text(T("Розмиття: {0}", blur), Modifier.padding(start = 16.dp), fontSize = 14.sp)
                    Slider(blur.toFloat(), { Settings.themeBlur.set(it.toInt()) }, Modifier.padding(horizontal = 16.dp), valueRange = 0f..20f, steps = 19)
                }
            }
            item { Label(T("Фонове відео")) }
            item {
                val video = Pal.video
                var copying by remember { mutableStateOf(false) }
                val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
                    if (uri != null) { copying = true; app.scope.launch { Pal.setVideo(context, uri)?.let(app::toast); copying = false } }
                }
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }, enabled = !copying) {
                        Icon(Icons.Rounded.Movie, null); Spacer(Modifier.width(8.dp)); Text(if (video != null) T("Змінити відео") else T("Вибрати відео"))
                    }
                    if (video != null) OutlinedButton(onClick = { Pal.clearVideo(context) }) { Text(T("Прибрати")) }
                }
                if (copying) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp), color = Pal.accent)
                if (video != null && Pal.videoFailed) Text(T("Цей телефон не може відтворити вибране відео, тому показано фонову картинку. Спробуйте інше відео (MP4, H.264)."),
                    Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                Text(T("Відео грає без звуку по колу й ставиться на паузу, коли застосунок згорнуто. До 300 МБ. Відео витрачає більше батареї, ніж картинка; ") +
                    T("воно має пріоритет над фоновим зображенням і не входить у резервну копію."),
                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = Dim, fontSize = 12.sp)
                if (video != null && !hasImage) {
                    Text(T("Затемнення: {0}%", dim), Modifier.padding(start = 16.dp, top = 8.dp), fontSize = 14.sp)
                    Slider(dim.toFloat(), { Settings.themeDim.set(it.toInt()) }, Modifier.padding(horizontal = 16.dp), valueRange = 20f..95f)
                }
            }
            item { Label(T("Обкладинки")) }
            item {
                Text(T("Заокруглення кутів: {0} dp", corner), Modifier.padding(start = 16.dp), fontSize = 14.sp)
                Slider(corner.toFloat(), { Settings.themeCorner.set(it.toInt()) }, Modifier.padding(horizontal = 16.dp), valueRange = 0f..28f)
            }
            item { Label(T("Розмір тексту")) }
            item {
                Text("${font}%", Modifier.padding(start = 16.dp), fontSize = 14.sp)
                Slider(font.toFloat(), { Settings.themeFont.set((it / 5).toInt() * 5) }, Modifier.padding(horizontal = 16.dp), valueRange = 85f..130f)
            }
            item {
                TextButton(onClick = {
                    Settings.themeBg.set(0xFF000000.toInt()); Settings.themeAccent.set(0xFFFFFFFF.toInt()); Settings.themeDim.set(60)
                    Settings.themeBlur.set(0); Settings.themeCorner.set(4); Settings.themeFont.set(100); Pal.clearImage(context); Pal.clearVideo(context)
                }, Modifier.padding(16.dp)) { Icon(Icons.Rounded.Restore, null); Spacer(Modifier.width(8.dp)); Text(T("Скинути оформлення")) }
            }
        }
    }
    when (picker) {
        "bg" -> ColorPickerDialog(T("Колір фону"), bg, 0.35f, { Settings.themeBg.set(it) }, { picker = null })
        "accent" -> ColorPickerDialog(T("Акцентний колір"), accent, 1f, { Settings.themeAccent.set(it) }, { picker = null })
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
fun SupportScreen() {
    val app = LocalApp.current
    val context = LocalContext.current
    fun open(url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure { app.toast(T("Немає застосунку, щоб відкрити посилання")) }
    Column(Modifier.fillMaxSize()) {
        TopBar(T("Підтримати автора"), onBack = { app.nav.popBackStack() })
        LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp)) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(72.dp).clip(CircleShape).background(Pal.accent), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Favorite, null, Modifier.size(36.dp), tint = Pal.onAccent)
                    }
                    Text(T("Дякуємо, що слухаєте з Privacy Music"), Modifier.padding(top = 14.dp), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(T("Застосунок безкоштовний, без реклами й стеження. Ось як можна допомогти йому розвиватися:"), Modifier.padding(top = 6.dp),
                        color = Dim, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            item { Text(T("Криптовалюта"), Modifier.padding(top = 8.dp, bottom = 6.dp), fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            item {
                Text(T("Підтримка приймається лише в криптовалюті. Monero рекомендовано: перекази в ній приватні за замовчуванням. Для USDT використовуйте лише мережу Arbitrum One — кошти з інших мереж буде втрачено."),
                    Modifier.padding(bottom = 10.dp), color = Dim, fontSize = 13.sp)
            }
            items(Links.donations) { d ->
                Column(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(12.dp)).background(Pal.card)
                    .then(if (d.address.isNotEmpty()) Modifier.clickable {
                        (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                            .setPrimaryClip(android.content.ClipData.newPlainText(d.coin, d.address))
                        app.toast(T("Адресу скопійовано"))
                    } else Modifier).padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(d.coin, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text("  · " + T("мережа {0}", d.network), color = Dim, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        if (d.recommended) Text(T("Рекомендовано"), Modifier.clip(RoundedCornerShape(10.dp)).background(Pal.accent).padding(horizontal = 8.dp, vertical = 2.dp),
                            color = Pal.onAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    if (d.address.isEmpty()) Text(T("Адресу буде додано незабаром"), Modifier.padding(top = 6.dp), color = Dim, fontSize = 13.sp)
                    else {
                        Text(d.address, Modifier.padding(top = 6.dp), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 12.sp, color = Dim)
                        Text(T("Натисніть, щоб скопіювати адресу"), Modifier.padding(top = 4.dp), color = Pal.accent, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SupportRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(12.dp), color = Pal.card) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Pal.accent)
            Column(Modifier.padding(start = 14.dp).weight(1f)) { Text(title, fontWeight = FontWeight.Medium); Text(subtitle, color = Dim, fontSize = 12.sp) }
            Icon(Icons.Rounded.ChevronRight, null, tint = Dim)
        }
    }
}

@Composable
fun ReportScreen(initialKind: String) {
    val app = LocalApp.current
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var includeTech by remember { mutableStateOf(true) }
    var kind by remember { mutableStateOf(initialKind) }
    var preview by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        TopBar(if (kind == "bug") T("Повідомити про помилку") else T("Запропонувати ідею"), onBack = { app.nav.popBackStack() })
        LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp)) {
            item {
                Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(kind == "bug", { kind = "bug" }, { Text(T("Помилка")) })
                    FilterChip(kind == "idea", { kind = "idea" }, { Text(T("Ідея")) })
                }
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 5,
                    label = { Text(if (kind == "bug") T("Що сталося і як це повторити?") else T("Опишіть ідею")) })
                if (kind == "bug") Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(includeTech, { includeTech = it })
                    Text(T("Додати технічні дані (версія, модель телефону, останні помилки)"), fontSize = 13.sp)
                }
                Text(T("Лист піде автору з вашого поштового застосунку. Звіт не містить вашої медіатеки, історії, пошуків чи акаунта, і перед надсиланням ви побачите весь текст."),
                    color = Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(12.dp))
                Button(onClick = { preview = Diag.report(context, text, includeTech && kind == "bug") }, enabled = text.isNotBlank()) {
                    Icon(Icons.Rounded.Send, null); Spacer(Modifier.width(8.dp)); Text(T("Переглянути й надіслати"))
                }
                if (Links.ISSUES.isNotEmpty()) TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Links.ISSUES))) } }) { Text(T("Відкрити трекер помилок")) }
            }
        }
    }
    preview?.let { body ->
        AlertDialog(onDismissRequest = { preview = null }, title = { Text(T("Ось що буде надіслано")) }, text = {
            LazyColumn(Modifier.heightIn(max = 380.dp)) { item { Text(body, fontSize = 12.sp, color = Dim) } }
        }, confirmButton = {
            TextButton(onClick = {
                preview = null
                val subject = if (kind == "bug") T("Privacy Music: помилка") else T("Privacy Music: ідея")
                val mail = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Links.BUG_EMAIL}")).putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, body)
                runCatching { context.startActivity(mail) }.onFailure {
                    // No e-mail app: let the user pick any app and send it to the address themselves.
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_EMAIL, arrayOf(Links.BUG_EMAIL)).putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, body), null))
                }
            }) { Text(T("Надіслати")) }
        }, dismissButton = { TextButton(onClick = { preview = null }) { Text(T("Скасувати")) } })
    }
}
