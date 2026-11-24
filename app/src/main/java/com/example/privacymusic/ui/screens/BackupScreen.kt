package com.example.privacymusic.ui.screens

import com.example.privacymusic.i18n.T

import com.example.privacymusic.ui.theme.Danger

import com.example.privacymusic.ui.theme.Pal

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.privacymusic.data.Backup
import com.example.privacymusic.ui.LocalApp
import com.example.privacymusic.ui.theme.Dim
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val DRIVE = "com.google.android.apps.docs"
private const val PROTON = "me.proton.android.drive"

private fun installed(context: Context, pkg: String) = runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

private fun shareTo(context: Context, file: File, pkg: String?) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_TITLE, file.name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    if (pkg != null) send.setPackage(pkg)
    context.startActivity(if (pkg != null) send else Intent.createChooser(send, T("Зберегти резервну копію")))
}

@Composable
fun BackupScreen() {
    val app = LocalApp.current
    val context = LocalContext.current
    var hasKey by remember { mutableStateOf(Backup.hasKey(context)) }
    var showKey by remember { mutableStateOf<String?>(null) }
    var keyIsNew by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var confirmNewKey by remember { mutableStateOf(false) }
    var last by remember { mutableLongStateOf(Backup.lastBackup(context)) }

    fun make(then: (File) -> Unit) {
        if (!hasKey) { app.toast(T("Спочатку створіть ключ відновлення")); return }
        busy = true
        app.scope.launch {
            try { val f = withContext(Dispatchers.IO) { Backup.create(context) }; last = Backup.lastBackup(context); then(f) }
            catch (e: CancellationException) { throw e } catch (e: Exception) { app.toast(e.message ?: T("Не вдалося створити копію")) }
            finally { busy = false }
        }
    }
    val unlock = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == android.app.Activity.RESULT_OK) { showKey = Backup.currentKey(context); keyIsNew = false }
    }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        if (uri != null) make { f -> context.contentResolver.openOutputStream(uri)?.use { it.write(f.readBytes()) }; app.toast(T("Зашифровану копію збережено")) }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(T("Резервні копії"), onBack = { app.nav.popBackStack() })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp)) {
            item {
                Text(T("Медіатека, плейлисти, лайки, історія, статистика, закріплене й налаштування шифруються на телефоні (AES-256-GCM) ") +
                    T("ще до того, як файл потрапить у хмару. Google чи Proton бачать лише зашифрований файл. Розшифрувати його можна тільки вашим ключем відновлення."),
                    color = Dim, fontSize = 14.sp, lineHeight = 20.sp)
                Spacer(Modifier.height(6.dp))
                Text(T("Не входять: cookie акаунта YouTube (з міркувань безпеки) та самі завантажені аудіофайли."), color = Dim, fontSize = 13.sp)
                Spacer(Modifier.height(16.dp))
                Card(colors = CardDefaults.cardColors(containerColor = Pal.card)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Key, null, tint = if (hasKey) Color.White else Danger)
                            Text(if (hasKey) T("Ключ відновлення створено") else T("Ключа відновлення ще немає"), Modifier.padding(start = 12.dp), fontWeight = FontWeight.Bold)
                        }
                        Text(T("Збережіть ключ окремо від телефону: у менеджері паролів, на папері. Без нього копію не відкрити — ми теж не зможемо."),
                            color = Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!hasKey) Button(onClick = { showKey = Backup.newKey(context); keyIsNew = true; hasKey = true },
                                colors = ButtonDefaults.buttonColors(containerColor = Pal.accent, contentColor = Pal.onAccent)) { Text(T("Створити ключ")) }
                            else {
                                OutlinedButton(onClick = {
                                    // Showing the key again requires the phone's screen lock, so someone holding an unlocked phone can't read it.
                                    val km = context.getSystemService(android.app.KeyguardManager::class.java)
                                    @Suppress("DEPRECATION")
                                    val intent = if (km?.isDeviceSecure == true) km.createConfirmDeviceCredentialIntent(T("Ключ відновлення"), T("Підтвердіть, що це ви")) else null
                                    if (intent != null) unlock.launch(intent) else { showKey = Backup.currentKey(context); keyIsNew = false }
                                }) { Text(T("Показати ключ")) }
                                OutlinedButton(onClick = { confirmNewKey = true }) { Text(T("Новий ключ")) }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text(T("Створити копію"), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                if (last > 0) Text(T("Остання: ") + java.text.SimpleDateFormat("d MMMM yyyy, HH:mm", com.example.privacymusic.i18n.I18n.locale()).format(java.util.Date(last)), color = Dim, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 10.dp), color = Pal.accent)
                CloudButton("Google Drive", if (installed(context, DRIVE)) T("Відкриється застосунок Google Drive — виберіть папку") else T("Застосунок Google Drive не знайдено — відкриється вибір місця"), Icons.Rounded.AddToDrive, enabled = !busy) {
                    if (installed(context, DRIVE)) make { shareTo(context, it, DRIVE) } else saveAs.launch("privacymusic.pmbackup")
                }
                CloudButton("Proton Drive", if (installed(context, PROTON)) T("Відкриється Proton Drive — файл додатково зашифрує і Proton") else T("Встановіть Proton Drive або збережіть файл і завантажте його туди вручну"), Icons.Rounded.Shield, enabled = !busy) {
                    if (installed(context, PROTON)) make { shareTo(context, it, PROTON) } else saveAs.launch("privacymusic.pmbackup")
                }
                CloudButton(T("Інше місце"), T("Будь-яка папка, флешка чи інший хмарний застосунок"), Icons.Rounded.FolderOpen, enabled = !busy) { saveAs.launch("privacymusic.pmbackup") }
                Spacer(Modifier.height(20.dp))
                Text(T("Відновити"), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(T("Виберіть файл .pmbackup (з Google Drive, Proton Drive чи завантажень) і введіть ключ. Поточна медіатека буде замінена."), color = Dim, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                RestoreBackupButton { hasKey = true }
                Spacer(Modifier.height(20.dp))
                Text(T("Чому без входу в Google Drive / Proton Drive?"), fontWeight = FontWeight.Bold)
                Text(T("Застосунок не отримує доступу до ваших хмарних акаунтів: немає OAuth, немає Google SDK. Файл передається системним механізмом Android ") +
                    T("у вже встановлений застосунок хмари, і завантажує його саме він."), color = Dim, fontSize = 13.sp, lineHeight = 19.sp)
            }
        }
    }

    showKey?.let { key ->
        val groups = remember(key) { key.split("-") }
        // A new key must be proven saved: the user types back one random group of it (4 characters).
        val ask = remember(key) { (1..groups.size).random() }
        var verifying by remember(key) { mutableStateOf(false) }
        var typed by remember(key) { mutableStateOf("") }
        var wrong by remember(key) { mutableStateOf(false) }
        if (!verifying) AlertDialog(onDismissRequest = { if (!keyIsNew) showKey = null }, title = { Text(T("Ключ відновлення")) }, text = {
            Column {
                Text(T("Запишіть або збережіть у менеджері паролів. Без цього ключа резервну копію неможливо розшифрувати."), fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                SelectionContainer {
                    Text(key, Modifier.clip(RoundedCornerShape(8.dp)).background(Pal.bar).padding(12.dp), fontFamily = FontFamily.Monospace, fontSize = 15.sp, lineHeight = 22.sp)
                }
                TextButton(onClick = {
                    val cm = context.getSystemService(ClipboardManager::class.java)
                    val clip = ClipData.newPlainText(T("Ключ відновлення"), key)
                    // Mark as sensitive so the keyboard / clipboard preview doesn't show it (Android 13+).
                    if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
                    cm.setPrimaryClip(clip); app.toast(T("Скопійовано. Не забудьте очистити буфер обміну."))
                }) { Icon(Icons.Rounded.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text(T("Копіювати")) }
            }
        }, confirmButton = {
            if (keyIsNew) TextButton(onClick = { verifying = true; typed = ""; wrong = false }) { Text(T("Я зберіг — перевірити")) }
            else TextButton(onClick = { showKey = null }) { Text(T("Готово")) }
        })
        else AlertDialog(onDismissRequest = {}, title = { Text(T("Перевірка ключа")) }, text = {
            Column {
                Text(T("Щоб переконатися, що ключ справді збережено, введіть з нього групу №{0} (4 символи).", ask), fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(typed, { typed = it.uppercase().take(4); wrong = false }, singleLine = true, isError = wrong,
                    label = { Text(T("Група №{0}", ask)) }, textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace))
                if (wrong) Text(T("Не збігається. Подивіться на збережений ключ ще раз."), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
        }, confirmButton = {
            TextButton(enabled = typed.length == 4, onClick = {
                if (typed == groups[ask - 1]) { Backup.confirmSaved(context); showKey = null } else wrong = true
            }) { Text(T("Перевірити")) }
        }, dismissButton = { TextButton(onClick = { verifying = false }) { Text(T("Показати ще раз")) } })
    }

    if (confirmNewKey) AlertDialog(onDismissRequest = { confirmNewKey = false }, title = { Text(T("Створити новий ключ?")) },
        text = { Text(T("Нові копії будуть зашифровані новим ключем. Старі копії відкриваються лише старим ключем — збережіть його, якщо вони вам потрібні.")) },
        confirmButton = { TextButton(onClick = { confirmNewKey = false; showKey = Backup.newKey(context); keyIsNew = true }) { Text(T("Створити")) } },
        dismissButton = { TextButton(onClick = { confirmNewKey = false }) { Text(T("Скасувати")) } })

}

@Composable
private fun CloudButton(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(12.dp), color = Pal.card) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null)
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(subtitle, color = Dim, fontSize = 12.sp)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = Dim)
        }
    }
}

/** "Restore from file": pick a .pmbackup, type the recovery key, replace the library. Used by Backup and by Import. */
@Composable
fun RestoreBackupButton(onRestored: () -> Unit = {}) {
    val app = LocalApp.current
    val context = LocalContext.current
    var restoreBytes by remember { mutableStateOf<ByteArray?>(null) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) app.scope.launch { restoreBytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } } }
    }
    OutlinedButton(onClick = { open.launch(arrayOf("*/*")) }) { Icon(Icons.Rounded.Restore, null); Spacer(Modifier.width(8.dp)); Text(T("Відновити з файлу")) }
    restoreBytes?.let { bytes ->
        var key by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var working by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { restoreBytes = null }, title = { Text(T("Відновити копію")) }, text = {
            Column {
                Text(T("Введіть ключ відновлення (52 символи, дефіси й пробіли можна не вводити). Поточні дані на пристрої буде замінено."), fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(key, { key = it; error = null }, Modifier.fillMaxWidth(), label = { Text(T("Ключ")) }, isError = error != null,
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                if (working) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), color = Pal.accent)
            }
        }, confirmButton = {
            TextButton(enabled = key.isNotBlank() && !working, onClick = {
                working = true
                app.scope.launch {
                    try {
                        withContext(Dispatchers.IO) { Backup.restore(context, bytes, key) }
                        restoreBytes = null; onRestored()
                        app.toast(T("Дані відновлено. Перезапустіть застосунок, щоб застосувати налаштування й чергу."))
                    } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: T("Не вдалося відновити") }
                    finally { working = false }
                }
            }) { Text(T("Відновити")) }
        }, dismissButton = { TextButton(onClick = { restoreBytes = null }) { Text(T("Скасувати")) } })
    }
}
