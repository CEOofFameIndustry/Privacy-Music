Privacy Music for Windows
=========================

Install or update with one command (PowerShell):
  irm https://raw.githubusercontent.com/CEOofFameIndustry/Privacy-Music/main/install.ps1 | iex
The same command also updates yt-dlp if music stops playing.

Or by hand: unpack the whole folder anywhere (for example into Documents) and start privacy-music.exe.
Nothing else needs to be installed. Keep all the files together in one folder.

What is in the folder:
  privacy-music.exe     the app
  WebView2Loader.dll    needed for the app window (WebView2 itself is part of Windows 10 and 11)
  yt-dlp\               fetches the audio from YouTube (official yt-dlp_win build, github.com/yt-dlp/yt-dlp, Unlicense)
  deno.exe              needed by yt-dlp for YouTube's checks (official build, github.com/denoland/deno, MIT)

Your data (library, settings) is stored only on this computer:
  %APPDATA%\privacy-music

If Windows SmartScreen warns about an unknown publisher: "More info" -> "Run anyway".
The app is not signed with a developer certificate, but it never sends your data anywhere.

Devices nearby (phone): the first time you turn it on, Windows may ask for network access -
allow it for "Private networks" (your home Wi-Fi).
