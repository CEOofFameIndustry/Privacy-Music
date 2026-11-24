# Privacy Music for Windows 10 / 11 — install or update with one command (PowerShell):
#   irm https://raw.githubusercontent.com/CEOofFameIndustry/Privacy-Music/main/install.ps1 | iex
# Updates an existing copy where it is (the running one, or the one the Start menu shortcut points to); otherwise
# installs into %LOCALAPPDATA%\Programs\Privacy Music. No administrator rights; adds a Start menu shortcut.
# Your library and settings (%APPDATA%\privacy-music) are never touched.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'   # Invoke-WebRequest is many times faster without the progress bar
$repo = 'CEOofFameIndustry/Privacy-Music'
$url  = "https://github.com/$repo/releases/latest/download/PrivacyMusic-windows-x64.zip"
$dest = Join-Path $env:LOCALAPPDATA 'Programs\Privacy Music'
$menu = Join-Path ([Environment]::GetFolderPath('Programs')) 'Privacy Music.lnk'
$shell = New-Object -ComObject WScript.Shell

# Already installed? Update that copy in place (e.g. a zip unpacked into Documents earlier).
$found = $null
$running = Get-Process -Name 'privacy-music' -ErrorAction SilentlyContinue
foreach ($p in $running) { if ($p.Path) { $found = Split-Path $p.Path -Parent; break } }
if (-not $found -and (Test-Path $menu)) {
    $target = $shell.CreateShortcut($menu).TargetPath
    if ($target -and (Test-Path $target)) { $found = Split-Path $target -Parent }
}
if ($found -and (Test-Path (Join-Path $found 'privacy-music.exe'))) { $dest = $found }
$tmp  = Join-Path ([IO.Path]::GetTempPath()) ("pm-" + [guid]::NewGuid())
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
try {
    Write-Host '==> Downloading Privacy Music' -ForegroundColor Magenta
    Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile "$tmp\pm.zip"
    Expand-Archive -Path "$tmp\pm.zip" -DestinationPath $tmp -Force
    $src = Join-Path $tmp 'Privacy Music'
    if (-not (Test-Path "$src\privacy-music.exe")) { throw 'Unexpected archive layout.' }

    # A running copy keeps its files locked: close it first (music stops for a moment).
    if ($running) {
        Write-Host '==> Closing the running Privacy Music to replace it' -ForegroundColor Magenta
        $running | Stop-Process -Force
        Start-Sleep -Seconds 1
    }
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    # Old helper files (a previous yt-dlp) go away; everything else is overwritten.
    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue "$dest\yt-dlp", "$dest\yt-dlp.exe"
    Copy-Item -Path "$src\*" -Destination $dest -Recurse -Force

    $exe = Join-Path $dest 'privacy-music.exe'
    $lnk = $shell.CreateShortcut($menu); $lnk.TargetPath = $exe; $lnk.WorkingDirectory = $dest; $lnk.IconLocation = "$exe,0"; $lnk.Save()

    Write-Host "==> Privacy Music is up to date in $dest" -ForegroundColor Green
    Write-Host '    Start menu: Privacy Music. Starting it now...'
    Start-Process -FilePath $exe -WorkingDirectory $dest
} finally {
    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $tmp
}
