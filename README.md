# Privacy Music

Приватний музичний плеєр з каталогом YouTube Music — для **Android**, **Windows** і **Linux**.
Без акаунта, без реклами, без стеження: медіатека, лайки, плейлисти й історія зберігаються лише на вашому пристрої.

*A private YouTube Music player for Android, Windows and Linux. No account, no ads, no tracking — your library lives only on your device.*

---

## Встановлення / Install

### 📱 Android

**[⬇ Завантажити PrivacyMusic.apk](https://github.com/CEOofFameIndustry/Privacy-Music/releases/latest/download/PrivacyMusic.apk)** — відкрийте посилання на телефоні, натисніть на завантажений файл і дозвольте встановлення.

Android 8.0+. Оновлення — у застосунку: **Налаштування → Про застосунок → Перевірити оновлення**.

### 🪟 Windows 10 / 11

Відкрийте **PowerShell** (Win → «PowerShell») і вставте одну команду:

```powershell
irm https://raw.githubusercontent.com/CEOofFameIndustry/Privacy-Music/main/install.ps1 | iex
```

Встановлюється в `%LOCALAPPDATA%\Programs\Privacy Music` без прав адміністратора й додає ярлик у меню «Пуск».
Ця ж команда оновлює застосунок. Або вручну: [PrivacyMusic-windows-x64.zip](https://github.com/CEOofFameIndustry/Privacy-Music/releases/latest/download/PrivacyMusic-windows-x64.zip) — розпакуйте й запустіть `privacy-music.exe`.

### 🐧 Linux (x86_64)

```bash
curl -fsSL https://raw.githubusercontent.com/CEOofFameIndustry/Privacy-Music/main/install.sh | bash
```

* **Arch, Manjaro, EndeavourOS** — ставиться пакет pacman (yt-dlp і deno з репозиторіїв).
* **Ubuntu 22.04+, Debian 12+, Mint 21+, Fedora 36+, openSUSE** — бібліотеки з вашого менеджера пакетів, а сам застосунок, yt-dlp і deno — у `~/.local/lib/privacy-music` (офіційні збірки, контрольні суми перевіряються).

Ця ж команда оновлює застосунок. Видалити: `rm -rf ~/.local/lib/privacy-music ~/.local/bin/privacy-music ~/.local/share/applications/privacy-music.desktop` (на Arch — `sudo pacman -R privacy-music`).

Ваші дані: `~/.local/share/privacy-music` (Linux), `%APPDATA%\privacy-music` (Windows) — встановлення й оновлення їх не чіпають.

---

## Що вміє

* Пошук, альбоми, виконавці, плейлисти, радіо, чарти — каталог YouTube Music.
* Медіатека на пристрої: лайки, плейлисти (сортування, пошук у плейлисті, власні обкладинки), завантаження для офлайну, статистика.
* Тексти пісень, у тому числі синхронізовані (LRCLIB, вмикається окремо).
* Плавний перехід між піснями (кросфейд), альбоми без пауз, еквалайзер.
* Імпорт: Telegram (з обкладинками), Spotify-посилання, CSV/Excel, YouTube Music.
* Пристрої поруч: телефон ↔ комп'ютер у локальній мережі з наскрізним шифруванням — керування, передавання музики, синхронізація медіатеки.

## Приватність

* Жодних Google SDK, Play Services, аналітики, cookie чи рекламних ID.
* Увесь мережевий трафік іде через вбудований фаєрвол з білим списком адрес; звіти про відтворення YouTube заблоковано.
* YouTube бачить лише IP-адресу та те, що ви шукаєте й вмикаєте. Щоб приховати IP — VPN або Tor.
* Перевірка оновлень (GitHub) вимикається в налаштуваннях.

## Збирання з коду

* Android: `./gradlew :app:assembleRelease` (JDK 21; Kotlin 2.4).
* Desktop (Rust + Dioxus): `cd desktop && cargo build --release` — потрібні webkit2gtk-4.1, gtk3, opus, alsa, libayatana-appindicator; під час роботи — yt-dlp і deno.
  Пакети: `desktop/packaging/` (`PKGBUILD`, `build-linux.sh`, `build-windows.sh`).

## Підтримати автора

Лише криптовалюта (адреси з QR-кодами є в застосунку: Налаштування → Підтримка):

| | Мережа | Адреса |
|---|---|---|
| **Monero (XMR)** — рекомендовано | Monero | `89DRpjfQeyjSxpsKYsd2dwJwjCjqLRjsrEf3kWcjKYyM5BRykkY2qiqEoJmPZrCpLR3J7jqLFUrgdEbYzF7ELLk28oYcQvS` |
| Bitcoin (BTC) | Bitcoin | `bc1qknsaqeupv2z9v24m7mvh0zxutewq32lqr7l2cv` |
| Tether (USDT) | **лише Arbitrum One** | `0x2E6568ec773f220e7ED672Bd54AaF8Bd20f24c81` |

Помилки й ідеї — у [Issues](https://github.com/CEOofFameIndustry/Privacy-Music/issues).

Ліцензія: GPL-3.0-or-later (див. LICENSE). Privacy Music не пов'язаний з YouTube чи Google.
