<#
.SYNOPSIS
  Debloats a Google TV / Android TV, installs Lume, makes it the default launcher.

.DESCRIPTION
  1. Finds adb, connects, and checks the device is a suitable Android TV.
  2. Asks the TV what it depends on (HDMI/Live TV viewer, input services, keyboard, Settings, Google core)
     and protects all of it, plus a built-in list of required system packages.
  3. Keeps only: Jellyfin, Netflix, Hotstar, Prime Video, Airtel Xstream (plus protected system packages).
     Every other launchable or user-installed app is removed for the user (reversible).
  4. Erases leftover app data of removed apps and the public media folders (Download, DCIM, ...).
  5. Installs Lume, makes it Home, disables the other launchers, verifies, and rolls the launchers back if
     Lume is not the Home app afterwards.
  6. Writes an undo file so everything can be restored.

  Shows a full plan first and asks for YES once. Use -DryRun to only see the plan.

.EXAMPLE
  .\install.ps1 -Tv 192.168.1.19:5555 -DryRun
  .\install.ps1 -Tv 192.168.1.40:5555
  .\install.ps1 -Tv 192.168.1.40:5555 -Yes -Layout .\lume-layout.json
  .\install.ps1 -Tv 192.168.1.40:5555 -Restore .\lume-undo-192.168.1.40_5555-20261025-181500.txt
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$Tv,   # ip:port (wireless debugging) or a USB serial
  [string]$Apk = "",                           # auto-detected when empty
  [string]$Layout = "",                        # optional lume-layout.json to copy to the TV
  [string[]]$AlsoKeep = @(),                   # extra package regexes to keep, e.g. 'com\.google\.android\.youtube\.tv'
  [switch]$DryRun,                             # show the plan, change nothing
  [switch]$Yes,                                # skip the YES prompt
  [switch]$KeepMedia,                          # do not erase Download/DCIM/Pictures/Movies/Music/Documents
  [switch]$ClearKeptApps,                      # also wipe data (logins) of the kept apps
  [switch]$NoSpeedTweaks,                      # skip animation-speed and cache tweaks
  [string]$Restore = ""                        # undo file written by an earlier run
)

$ErrorActionPreference = "Continue"
$LumePkg = "com.lume.launcher"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path

# ---------------------------------------------------------------------------------------------
# Rules
# ---------------------------------------------------------------------------------------------

# The apps to isolate. Matched against package names (case-insensitive regex).
$KeepPatterns = @(
  '^org\.jellyfin\.',
  '^com\.netflix\.',
  'hotstar', '^in\.startv\.',
  '^com\.amazon\.',
  'airtel', 'xstream', 'wynk',
  '^com\.google\.android\.youtube',   # YouTube, YouTube Music, YouTube Kids ...
  '^com\.android\.vending$'            # Play Store
) + $AlsoKeep

# System packages that must never be removed or disabled, on any brand.
$ProtectPatterns = @(
  '^android$', '^com\.android\.',
  '^com\.google\.android\.(gms|gsf|vending|webview|inputmethod|tts|packageinstaller|permissioncontroller|ext|tv\.|katniss|apps\.mediashell|marvin|overlay|modulemetadata|partnersetup|syncadapters|sss|feedback|onetimeinitializer)',
  'setup|wraith|provision|oobe',
  'updateservice|systemupdate|systemupdater|swupdate|\.ota\b',
  'bluetooth|companion|pairing|remote\.service|tvlock|milinkservice|bspfactory',
  '^com\.droidlogic', '^com\.amlogic', '^com\.mediatek', '^com\.mstar', '^com\.realtek', '^com\.hisilicon', '^com\.broadcom',
  '^mitv\.service$', '^com\.xiaomi\.mitv\.(updateservice|res)$', '^com\.mi\.bluetooth$'
)

# Non-launchable telemetry / ad packages that are safe to remove when not protected.
$TelemetryPattern = 'analytics|statistic|telemetry|adservice|advert'

# Never disable these Home-category packages (device setup flows).
$HomeSkipPattern = 'setup|wraith|provision|oobe'

$MediaDirs = @("Download", "DCIM", "Pictures", "Movies", "Music", "Documents")

# ---------------------------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------------------------

function Find-Adb {
  $cmd = Get-Command adb -ErrorAction SilentlyContinue
  if ($cmd) { return $cmd.Source }
  $cands = @(
    "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    "$env:ANDROID_HOME\platform-tools\adb.exe",
    "$env:ANDROID_SDK_ROOT\platform-tools\adb.exe",
    "C:\platform-tools\adb.exe"
  )
  $lp = Join-Path $Root "local.properties"
  if (Test-Path $lp) {
    foreach ($line in (Get-Content $lp)) {
      if ($line -match '^sdk\.dir=(.+)$') {
        $cands += ((($Matches[1] -replace '\\:', ':') -replace '\\\\', '\') + "\platform-tools\adb.exe")
      }
    }
  }
  foreach ($c in $cands) { if ($c -and (Test-Path $c)) { return $c } }
  throw "adb.exe not found. Install Android Studio or platform-tools."
}

# Waits until the TV answers again (some changes make it drop the adb connection for a few seconds).
function Wait-Tv([int]$sec) {
  $deadline = (Get-Date).AddSeconds($sec)
  while ((Get-Date) -lt $deadline) {
    if ($script:Serial -match ':\d+$') { & $script:Adb connect $script:Serial 2>&1 | Out-Null }
    $st = ((& $script:Adb -s $script:Serial get-state 2>&1) | Out-String).Trim()
    if ($st -match '^device') {
      $t = ((& $script:Adb -s $script:Serial shell echo lume-ok 2>&1) | Out-String).Trim()
      if ($t -match 'lume-ok') { return $true }
    }
    Start-Sleep -Seconds 2
  }
  return $false
}

function AdbSh([string]$cmd) {
  $lines = @()
  for ($i = 0; $i -lt 4; $i++) {
    $out = & $script:Adb -s $script:Serial shell $cmd 2>&1
    $lines = @()
    foreach ($o in $out) { $lines += ("$o").TrimEnd("`r", "`n") }
    if (($lines -join " ") -notmatch 'device offline|no devices/emulators|not found|error: closed|connection reset|cannot connect') { return $lines }
    [void](Wait-Tv 45)
  }
  return $lines
}

function Test-Match([string]$s, [string[]]$patterns) {
  foreach ($p in $patterns) { if ($p -and ($s -match $p)) { return $true } }
  return $false
}

function Get-Pkgs([string]$flags) {
  $res = @()
  foreach ($l in @(AdbSh "pm list packages $flags")) {
    if ($l -match '^package:(\S+)') { $res += $Matches[1] }
  }
  return $res
}

function Get-QueryPkgs([string]$verb, [string]$intentArgs) {
  $res = @()
  foreach ($l in @(AdbSh "cmd package $verb --brief $intentArgs")) {
    if ($l -match '^\s*([A-Za-z0-9_.]+)/\S+\s*$') { $res += $Matches[1] }
  }
  return @($res | Sort-Object -Unique)
}

function Section([string]$t) { Write-Host ""; Write-Host "== $t" -ForegroundColor Cyan }

# ---------------------------------------------------------------------------------------------
# Connect
# ---------------------------------------------------------------------------------------------

$script:Adb = Find-Adb
$script:Serial = $Tv
Write-Host "Using adb: $script:Adb"
if ($Tv -match ':\d+$') { & $script:Adb connect $Tv | Out-Null }
$state = ((& $script:Adb -s $Tv get-state 2>&1) | Out-String).Trim()
if ($state -notmatch '^device') {
  throw "TV not ready (state: '$state'). Check the IP, that network debugging is on, and accept the prompt on the TV."
}

# ---------------------------------------------------------------------------------------------
# Restore mode
# ---------------------------------------------------------------------------------------------

if ($Restore) {
  if (-not (Test-Path $Restore)) { throw "Undo file not found: $Restore" }
  Section "Restoring from $Restore"
  foreach ($l in (Get-Content $Restore)) {
    if ($l -match '^RESTORE (\S+)$') { Write-Host "restore $($Matches[1])"; AdbSh "cmd package install-existing $($Matches[1])" | Out-Null }
    elseif ($l -match '^ENABLE (\S+)$') { Write-Host "enable  $($Matches[1])"; AdbSh "pm enable $($Matches[1])" | Out-Null }
    elseif ($l -match '^ENABLECOMP (\S+)$') { Write-Host "enable component $($Matches[1])"; AdbSh "pm enable $($Matches[1])" | Out-Null }
    elseif ($l -match '^HOMEROLE (\S+)$') { Write-Host "home app  $($Matches[1])"; AdbSh "cmd role add-role-holder --user 0 android.app.role.HOME $($Matches[1]) 0" | Out-Null }
    elseif ($l -match '^USERAPP (\S+)$') { Write-Host "note: $($Matches[1]) was a user-installed app; reinstall it from the Play Store." }
  }
  Write-Host "Done. Reboot the TV: adb -s $Tv reboot"
  exit 0
}

# ---------------------------------------------------------------------------------------------
# Pre-flight
# ---------------------------------------------------------------------------------------------

Section "Pre-flight"
$sdk = 0
[void][int]::TryParse(((@(AdbSh "getprop ro.build.version.sdk") | Select-Object -First 1)), [ref]$sdk)
$make = (@(AdbSh "getprop ro.product.manufacturer") | Select-Object -First 1)
$model = (@(AdbSh "getprop ro.product.model") | Select-Object -First 1)
Write-Host "TV: $make $model, Android API $sdk"
if ($sdk -lt 26) { throw "Lume needs Android 8.0 (API 26) or newer." }
$features = (@(AdbSh "pm list features") -join "`n")
if ($features -notmatch 'android\.software\.leanback') { throw "This device does not report the TV (leanback) feature. Refusing to continue." }

if (-not $Apk) {
  $found = @()
  foreach ($rel in @("app\release\app-release.apk", "app\build\outputs\apk\release\app-release.apk", "app\build\outputs\apk\debug\app-debug.apk", "lume.apk")) {
    $p = Join-Path $Root $rel
    if (Test-Path $p) { $found += Get-Item $p }
  }
  if ($found.Count -eq 0) { throw "No Lume APK found. Build it first, or pass -Apk <path>." }
  $Apk = ($found | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
}
if (-not (Test-Path $Apk)) { throw "APK not found: $Apk" }
Write-Host "APK: $Apk"

# ---------------------------------------------------------------------------------------------
# Analyse the TV
# ---------------------------------------------------------------------------------------------

Section "Analysing installed packages"
$all = @(Get-Pkgs "")
$user = @(Get-Pkgs "-3")
$disabledAlready = @(Get-Pkgs "-d")
$launchable = @(
  (Get-QueryPkgs "query-activities" "-a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER") +
  (Get-QueryPkgs "query-activities" "-a android.intent.action.MAIN -c android.intent.category.LAUNCHER")
) | Sort-Object -Unique
$homePkgs = @(Get-QueryPkgs "query-activities" "-a android.intent.action.MAIN -c android.intent.category.HOME")

# What the TV itself says it needs: never touch these (system packages only).
$dynWhy = @{}
function Add-Dyn([string]$label, [string]$verb, [string]$intentArgs) {
  foreach ($q in (Get-QueryPkgs $verb $intentArgs)) {
    if (-not $dynWhy.ContainsKey($q)) { $dynWhy[$q] = $label }
  }
}
Add-Dyn "HDMI / Live TV viewer" "query-activities" "-a android.intent.action.VIEW -d content://android.media.tv/passthrough/x"
Add-Dyn "TV input service" "query-services" "-a android.media.tv.TvInputService"
Add-Dyn "keyboard" "query-services" "-a android.view.InputMethod"
Add-Dyn "accessibility service" "query-services" "-a android.accessibilityservice.AccessibilityService"
Add-Dyn "Settings" "query-activities" "-a android.settings.SETTINGS"
Add-Dyn "Wi-Fi settings" "query-activities" "-a android.settings.WIFI_SETTINGS"
Add-Dyn "Bluetooth settings" "query-activities" "-a android.settings.BLUETOOTH_SETTINGS"
Add-Dyn "TV settings entry" "query-activities" "-a android.intent.action.MAIN -c android.intent.category.LEANBACK_SETTINGS"

function Get-ProtectReason([string]$p, [bool]$isUser) {
  foreach ($pat in $ProtectPatterns) { if ($p -match $pat) { return "required system package" } }
  if ((-not $isUser) -and $dynWhy.ContainsKey($p)) { return ("TV needs it: " + $dynWhy[$p]) }
  return $null
}

if ($launchable.Count -eq 0) {
  Write-Warning "This TV does not list launchable apps through adb. Only user-installed apps and telemetry packages will be considered."
}

$candidates = @()
$candidates += $launchable
$candidates += $user
foreach ($p in $all) { if ($p -match $TelemetryPattern) { $candidates += $p } }
$candidates = @($candidates | Sort-Object -Unique)

$keep = @(); $protected = @(); $remove = @(); $protReason = @{}
foreach ($p in $candidates) {
  if ($p -eq $LumePkg) { continue }
  if ($homePkgs -contains $p -and -not ($user -contains $p)) { continue }   # system launchers are handled below
  if (Test-Match $p $KeepPatterns) { $keep += $p; continue }
  $why = Get-ProtectReason $p ($user -contains $p)
  if ($why) { $protected += $p; $protReason[$p] = $why; continue }
  $remove += $p
}
$keepInstalled = @($all | Where-Object { Test-Match $_ $KeepPatterns })

# Home-category packages: disable real launchers only. Settings, key services, setup flows etc. also
# register as Home and must stay enabled.
$disableLaunchers = @(); $homeKept = @()
foreach ($p in $homePkgs) {
  if ($p -eq $LumePkg) { continue }
  if ($user -contains $p) { if ($remove -notcontains $p) { $remove += $p }; continue }
  $why = $null
  foreach ($pat in $ProtectPatterns) { if ($p -match $pat) { $why = "required system package"; break } }
  if ((-not $why) -and $dynWhy.ContainsKey($p) -and -not (Test-Match $p @('launcher', 'tvhome', 'patchwall'))) {
    $why = "TV needs it: " + $dynWhy[$p]
  }
  if ($why) { $homeKept += ("{0}  ({1})" -f $p, $why); continue }
  if ($disabledAlready -contains $p) { continue }
  $disableLaunchers += $p
}

$mediaPlan = @()
if (-not $KeepMedia) {
  foreach ($d in $MediaDirs) {
    $n = 0
    [void][int]::TryParse(((@(AdbSh "ls -A /sdcard/$d 2>/dev/null | wc -l") | Select-Object -First 1)).Trim(), [ref]$n)
    if ($n -gt 0) { $mediaPlan += [pscustomobject]@{ Dir = $d; Items = $n } }
  }
}

# ---------------------------------------------------------------------------------------------
# Plan
# ---------------------------------------------------------------------------------------------

Section "PLAN for $make $model ($Tv)"
Write-Host "KEEP (isolated apps + YouTube + Play Store, $($keepInstalled.Count) packages):" -ForegroundColor Green
foreach ($p in $keepInstalled) { Write-Host "   $p" }
Write-Host ""
Write-Host "PROTECTED required system packages seen among candidates ($($protected.Count)), never touched:" -ForegroundColor Green
foreach ($p in $protected) { Write-Host ("   {0}   <- {1}" -f $p, $protReason[$p]) }
Write-Host ""
Write-Host "REMOVE for this user, data cleared first ($($remove.Count)):" -ForegroundColor Yellow
foreach ($p in $remove) {
  $kind = "system"; if ($user -contains $p) { $kind = "user-installed" }
  Write-Host ("   {0}   [{1}]" -f $p, $kind)
}
Write-Host ""
Write-Host "DISABLE other launchers ($($disableLaunchers.Count)):" -ForegroundColor Yellow
foreach ($p in $disableLaunchers) { Write-Host "   $p" }
if ($homeKept.Count -gt 0) {
  Write-Host "LEFT ENABLED although Home-category (needed by the TV):" -ForegroundColor Green
  foreach ($h in $homeKept) { Write-Host "   $h" }
}
Write-Host ""
if ($mediaPlan.Count -gt 0) {
  Write-Host "ERASE contents of public media folders:" -ForegroundColor Red
  foreach ($m in $mediaPlan) { Write-Host ("   /sdcard/{0}  ({1} items)" -f $m.Dir, $m.Items) }
} elseif ($KeepMedia) {
  Write-Host "Media folders: left alone (-KeepMedia)."
} else {
  Write-Host "Media folders: already empty."
}
if ($ClearKeptApps) { Write-Host "Kept apps will have their data cleared (logins lost)." -ForegroundColor Red }
Write-Host ""
Write-Host "Then: install Lume, set it as Home, verify, and write an undo file."

if ($DryRun) { Write-Host ""; Write-Host "Dry run: nothing was changed."; exit 0 }
if (-not $Yes) {
  Write-Host ""
  $answer = Read-Host "Type YES to apply this to $Tv"
  if ($answer -cne "YES") { Write-Host "Cancelled. Nothing was changed."; exit 1 }
}

# ---------------------------------------------------------------------------------------------
# Apply
# ---------------------------------------------------------------------------------------------

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$undo = Join-Path $Root ("lume-undo-{0}-{1}.txt" -f ($Tv -replace '[:\\/]', '_'), $stamp)
"# Lume undo file for $Tv ($make $model), created $stamp" | Set-Content $undo
function Log-Undo([string]$line) { Add-Content -Path $undo -Value $line }

$prevHome = ""
foreach ($l in @(AdbSh "cmd role get-role-holders --user 0 android.app.role.HOME")) {
  if ($l -match '^\s*([A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+)\s*$') { $prevHome = $Matches[1]; break }
}
if ($prevHome -and ($prevHome -ne $LumePkg)) { Log-Undo "HOMEROLE $prevHome" }

Section "Installing Lume"
$inst = (& $script:Adb -s $Tv install -r $Apk 2>&1 | Out-String)
if ($inst -notmatch 'Success') { throw "Install failed: $inst" }
if (-not (@(Get-Pkgs $LumePkg) -contains $LumePkg)) { throw "Lume does not appear to be installed. Stopping before changing anything else." }
AdbSh "appops set $LumePkg GET_USAGE_STATS allow" | Out-Null
AdbSh "am start -n $LumePkg/.MainActivity" | Out-Null
Start-Sleep -Seconds 3
if ($Layout -ne "") {
  if (Test-Path $Layout) {
    & $script:Adb -s $Tv push $Layout "/sdcard/Android/data/$LumePkg/files/lume-layout.json" | Out-Null
    Write-Host "Layout copied. Import it from Settings > Personalize > Import layout."
  } else {
    Write-Warning "Layout file not found: $Layout"
  }
}
AdbSh "cmd package set-home-activity $LumePkg/.MainActivity" | Out-Null

Section "Removing apps"
$removed = 0; $failed = @()
foreach ($p in $remove) {
  AdbSh "pm clear $p" | Out-Null
  $r = (@(AdbSh "pm uninstall --user 0 $p") -join " ")
  if ($r -match 'Success') {
    $removed++
    if ($user -contains $p) { Log-Undo "USERAPP $p" } else { Log-Undo "RESTORE $p" }
    Write-Host "  removed $p"
  } else {
    $failed += $p
    Write-Host "  could not remove $p ($r)" -ForegroundColor DarkYellow
  }
}

if ($ClearKeptApps) {
  Section "Clearing data of kept apps"
  foreach ($p in $keepInstalled) {
    if ($p -match '^com\.google\.' -or $p -match '^com\.android\.') { Write-Host "  skipped $p (Google/system app, keeps your account sign-in)"; continue }
    AdbSh "pm clear $p" | Out-Null; Write-Host "  cleared $p"
  }
}

if (-not $KeepMedia -and $mediaPlan.Count -gt 0) {
  Section "Erasing media folders"
  foreach ($m in $mediaPlan) {
    AdbSh "find /sdcard/$($m.Dir) -mindepth 1 -delete 2>/dev/null; rm -rf /sdcard/$($m.Dir)/* 2>/dev/null" | Out-Null
    Write-Host "  cleared /sdcard/$($m.Dir)"
  }
}

if (-not $NoSpeedTweaks) {
  Section "Speed tweaks"
  AdbSh "pm trim-caches 999G" | Out-Null
  AdbSh "settings put global window_animation_scale 0.5" | Out-Null
  AdbSh "settings put global transition_animation_scale 0.5" | Out-Null
  AdbSh "settings put global animator_duration_scale 0.5" | Out-Null
}

Section "Disabling other launchers"
$disabledNow = @()
foreach ($p in $disableLaunchers) {
  $r = (@(AdbSh "pm disable-user --user 0 $p") -join " ")
  if ($r -match 'new state: disabled') {
    $disabledNow += $p
    Log-Undo "ENABLE $p"
    Write-Host "  disabled $p"
  } else {
    Write-Host "  could not disable $p ($r)" -ForegroundColor DarkYellow
  }
}

Section "Making Lume the Home app"
# Real test: open something else, press Home exactly like the remote does, and see what comes to the front.
function Test-Home {
  [void](Wait-Tv 60)
  AdbSh "am start -a android.settings.SETTINGS" | Out-Null
  Start-Sleep -Seconds 3
  AdbSh "input keyevent 3" | Out-Null
  Start-Sleep -Seconds 3
  $script:focus = (@(AdbSh "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'") -join " ")
  return ($script:focus -match [regex]::Escape($LumePkg))
}
$script:focus = ""
$isHome = $false
$methods = @(
  @{ Name = "Android role manager"; Cmd = "cmd role add-role-holder --user 0 android.app.role.HOME $LumePkg 0" },
  @{ Name = "preferred activity"; Cmd = "cmd package set-home-activity $LumePkg/.MainActivity" }
)
foreach ($m in $methods) {
  Write-Host "Trying: $($m.Name)"
  $o = (@(AdbSh $m.Cmd) -join " ").Trim()
  if ($o) { Write-Host "  $o" }
  if (Test-Home) { $isHome = $true; break }
  Write-Host "  not yet (front: $script:focus)"
}
# Android always opens the Home activity with the highest declared priority and only uses the default-launcher
# choice when priorities tie. Only the setup wizard may declare a priority above 0, so while its Home screen is
# enabled nothing else can win. On a TV whose setup is already complete the wizard is not needed, so disable it
# for this user (a factory reset brings it back).
$g = { param($c) ((@(AdbSh $c) | Select-Object -First 1) + "").Trim() }
$rounds = 0
while ((-not $isHome) -and ($rounds -lt 2) -and ($script:focus -match '([A-Za-z0-9_.]+)/[A-Za-z0-9_.$]+')) {
  $rounds++
  $cpkg = $Matches[1]
  if (($cpkg -eq $LumePkg) -or (-not (Test-Match $cpkg @('setup', 'wraith', 'provision', 'oobe')))) { break }
  $devProv = & $g "settings get global device_provisioned"
  $usc = & $g "settings get secure user_setup_complete"
  $tvusc = & $g "settings get secure tv_user_setup_complete"
  if (-not (($devProv -eq "1") -and ($usc -ne "0") -and ($tvusc -ne "0"))) {
    Write-Warning "TV setup does not look complete (provisioned=$devProv, user_setup=$usc, tv_user_setup=$tvusc). Not touching the setup wizard."
    break
  }
  Write-Host "The setup wizard ($cpkg) has a higher-priority Home screen than any launcher can have. Setup is complete, so disabling it for this user."
  $o = (@(AdbSh "pm disable-user --user 0 $cpkg") -join " ")
  if ($o -match 'new state: disabled') {
    $disabledNow += $cpkg
    Log-Undo "ENABLE $cpkg"
    Write-Host "  disabled $cpkg"
  } else {
    Write-Warning "  could not disable $cpkg ($o)"
    break
  }
  Write-Host "  waiting for the TV to settle..."
  [void](Wait-Tv 90)
  Start-Sleep -Seconds 3
  AdbSh "cmd role add-role-holder --user 0 android.app.role.HOME $LumePkg 0" | Out-Null
  AdbSh "cmd package set-home-activity $LumePkg/.MainActivity" | Out-Null
  if (Test-Home) { $isHome = $true } else { Write-Host "  not yet (front: $script:focus)" }
}
$inconclusive = (-not $isHome) -and ($script:focus -match 'offline|error:|no devices|closed|cannot connect|^\s*$')
if ($isHome) {
  Write-Host "Pressing Home opens Lume." -ForegroundColor Green
} elseif ($inconclusive) {
  Write-Warning "Could not read the TV's screen to verify (front: '$script:focus'). Leaving the changes in place."
  Write-Host "Press Home on the remote now. If it does not open Lume, run: .\install.ps1 -Tv $Tv -Restore `"$undo`""
} else {
  Write-Warning "Pressing Home did NOT open Lume (front: $script:focus). Rolling back the launchers that were disabled."
  [void](Wait-Tv 60)
  foreach ($p in $disabledNow) {
    $r = (@(AdbSh "pm enable $p") -join " ")
    if ($r -match 'new state: enabled') { Write-Host "  re-enabled $p" } else { Write-Warning "  could NOT re-enable $p ($r)" }
  }
  if ($prevHome -and ($prevHome -ne $LumePkg)) {
    AdbSh "cmd role add-role-holder --user 0 android.app.role.HOME $prevHome 0" | Out-Null
    Write-Host "  Home app set back to $prevHome"
  }
}

Section "Done"
Write-Host "Removed: $removed   Could not remove: $($failed.Count)   Launchers disabled: $($disabledNow.Count)"
if ($failed.Count -gt 0) { Write-Host "Not removable (usually protected by the TV): $($failed -join ', ')" }
Write-Host "Undo file: $undo"
Write-Host "To undo: .\install.ps1 -Tv $Tv -Restore `"$undo`"   (a factory reset also restores everything)"
Write-Host "Tip: set Settings > Personalize > When TV powers on for the TV's users."
