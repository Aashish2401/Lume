# Lume

A fast, minimal, dark launcher for Google TV / Android TV. Brand-agnostic (standard Android APIs only),
looks identical on every TV, and designed around a basic remote.

- Android 8.0+ (API 26+). Developed against a Mi TV on API 30.
- No wallpaper files, no network access, no third-party libraries besides AndroidX Compose.

## Build (Windows, Android Studio installed)

1. Open this folder in Android Studio and let Gradle sync (first sync downloads dependencies).
2. Build the **release** APK (much faster than debug for Compose):

   ```powershell
   .\gradlew.bat assembleRelease
   ```

   If `gradlew.bat` is missing, Android Studio: Build > Generate App Bundles or APKs > Generate APKs
   (choose the release variant), or run `gradle wrapper` once from the Studio terminal.

   Output: `app\build\outputs\apk\release\app-release.apk`

## Install on a TV (debloat + set as Home)

On the TV: Settings > Device Preferences > About > tap Build 7 times, then Developer options > turn on
USB/Network debugging, and note the IP address. Then on the PC (build the release APK first):

```powershell
.\install.cmd -Tv 192.168.1.40:5555 -DryRun     # shows the plan, changes nothing
.\install.cmd -Tv 192.168.1.40:5555             # applies it after you type YES
```

What it does: keeps Jellyfin, Netflix, Hotstar, Prime Video and Airtel Xstream; protects required system
packages (found by asking the TV what it depends on); removes every other launchable or user-installed app
for the user (reversible); erases leftover data and the public media folders; installs Lume, makes it Home,
disables the other launchers, verifies, and writes an undo file.

Options: `-Yes` (no prompt), `-KeepMedia`, `-ClearKeptApps`, `-AlsoKeep 'regex'` (e.g. YouTube:
`'com\.google\.android\.youtube\.tv'`), `-Layout .\lume-layout.json`, `-NoSpeedTweaks`,
`-Restore .\lume-undo-<tv>-<time>.txt`.

## Remote controls

| Input | Action |
|---|---|
| D-pad | Move between tiles and pills |
| OK | Open |
| Hold OK on a tile | App menu: pin / unpin, move, hide, app info, uninstall |
| Hold Back (anywhere on home) | All apps |
| Home | Back to the first tile / close overlays |
| Menu / Settings key (if the remote has one) | Quick settings |
| Settings pill | Quick settings: Inputs, Network, Bluetooth, Sound, Display, Manage apps, Personalize |
| Search pill | Type to filter apps; Enter opens the top result |

Remote hardware buttons (Netflix, Prime, Assistant) are handled by the TV itself and keep working.

## Same look on every TV

Layout (pinned apps and order, hidden apps, accent colour) is a small JSON file.

1. On the first TV: Settings pill > Personalize > **Export layout**.
2. Copy it to the PC:

   ```powershell
   adb -s 192.168.1.19:5555 pull /sdcard/Android/data/com.lume.launcher/files/lume-layout.json .
   ```
3. On another TV: `.\install.ps1 -Tv <ip>:5555 -Layout .\lume-layout.json`, then Personalize > **Import layout**.

Apps are matched by package name; ones a TV doesn't have are skipped and appear automatically once installed.

## Notes

- The release build is signed with the local debug key so it installs directly. Keep using the same
  machine/key for updates, or uninstall first if you switch machines.
- Revert to the stock launcher: `adb shell pm enable <stock launcher package>` and uninstall Lume.
