# Simple Screensaver

A minimal Android TV screensaver (`DreamService`): a solid background with large text and a clock.
Plain Java + Android framework, no AndroidX, built without Gradle. Package `dev.mibox.screensaver`, minSdk 26.

![Screensaver with the default time and date](docs/screenshot.png)

*Illustrative render using the default settings (white `{time}` / `{date}` on black); replace with a capture from your device.*

## Features
- Text with `{time}` and `{date}` placeholders (refreshed every 15 s); `\n` or Enter for new lines
- Text color, background color (presets, or any `#RRGGBB` via adb), text size
- Optional slow drift of the text to prevent burn-in (on by default), optional dimming
- Settings screen usable with the D-pad, plus "Save and preview"

Defaults: `{time}\n{date}`, white on black, size 96, drift on, dim off.

## Build
Requires the Android SDK (platforms\android-34, build-tools\34.0.0) and two JDKs: 21 and 17 (javac only).
Point the script at them with `ANDROID_HOME`, `JAVA_HOME` (JDK 21) and `JDK17_HOME`, or pass `-Sdk`, `-Jdk`, `-CompileJdk`.

```powershell
.\build.ps1
```
Output: `build\simple-screensaver.apk`, signed with the debug keystore `%USERPROFILE%\.android\debug.keystore`
(created on first run). It is a debug-signed build for personal sideloading.

## Install and select
```powershell
adb -s <box-ip>:5555 install -r build\simple-screensaver.apk
adb -s <box-ip>:5555 shell settings put secure screensaver_components dev.mibox.screensaver/.SimpleDream
adb -s <box-ip>:5555 shell am start -a android.intent.action.MAIN -n com.android.systemui/.Somnambulator   # start it now to test
```
Or choose "Simple Screensaver" in Settings → Device Preferences → Screen saver.

## Configure
Open "Simple Screensaver" from the apps row on the TV, or over adb (run from `cmd`, or with the whole command in single quotes from PowerShell):

```
adb shell am start -n dev.mibox.screensaver/.SettingsActivity --es text "Good night\n{time}" --es fg "#FFB300" --es bg "#101830" --ei size 120 --ez drift true --ez dim false
```
The settings activity is exported so adb can configure it; any app on the device could do the same. It only changes
display preferences.

## Undo
```powershell
adb shell settings put secure screensaver_components com.google.android.backdrop/.Backdrop
adb shell pm uninstall dev.mibox.screensaver
```
