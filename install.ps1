<#
  Installs Lume on a TV over adb and makes it the default launcher.

  Usage (PowerShell, from the repo root, after building the release APK):
    .\install.ps1 -Tv 192.168.1.19:5555
    .\install.ps1 -Tv 192.168.1.40:5555 -Layout .\lume-layout.json   # also copies your saved layout
#>
param(
  [Parameter(Mandatory = $true)][string]$Tv,
  [string]$Apk = "app\build\outputs\apk\release\app-release.apk",
  [string]$Layout = ""
)

$pkg = "com.lume.launcher"
adb connect $Tv | Out-Null
$a = @("-s", $Tv)

Write-Host "Installing $Apk ..."
adb @a install -r $Apk
if ($LASTEXITCODE -ne 0) { Write-Error "Install failed"; exit 1 }

# Lets the Recent row see apps opened with the remote's Netflix/Prime buttons.
adb @a shell appops set $pkg GET_USAGE_STATS allow

# Start once so the app's files folder exists, then optionally push a saved layout.
adb @a shell am start -n "$pkg/.MainActivity" | Out-Null
Start-Sleep -Seconds 2
if ($Layout -ne "") {
  adb @a push $Layout "/sdcard/Android/data/$pkg/files/lume-layout.json"
  Write-Host "Layout copied. On the TV: Settings pill > Personalize > Import layout."
}

# Make Lume the Home app.
adb @a shell cmd package set-home-activity "$pkg/.MainActivity"
Write-Host "Done. Press Home on the remote."
