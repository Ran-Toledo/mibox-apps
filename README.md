# mibox-apps

Small sideloadable Android TV apps (plain Java, no Gradle), built for a Xiaomi Mi Box but usable on any Android TV 8+ device.

- [Simple Screensaver](screensaver/) — a clock/text screensaver with burn-in drift
- [Task Manager](taskmanager/) — memory, CPU and storage per app, with a Close button

## Builds and releases

Each app's `build.ps1` writes its signed APK to `<app>\build\`, which is overwritten by every build.
To keep a finished version, run `.\release.ps1 <app>` (`screensaver` or `taskmanager`): it builds the app and copies
the APK to `releases\<app>\<name>-<version>.apk` with a `.sha256` file. The version is the `-VersionName` default in
that app's `build.ps1`; the script refuses to overwrite an existing version unless you pass `-Force`.
`releases\` is git-ignored and stays on this machine.
