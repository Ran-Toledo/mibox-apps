<#
.SYNOPSIS
    Builds the Task Manager APK without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.

.DESCRIPTION
    Output: build\task-manager.apk, signed with the standard Android debug keystore
    (%USERPROFILE%\.android\debug.keystore, created on first run).
    Install separately: adb -s <serial> install -r build\task-manager.apk

.PARAMETER Sdk
    Android SDK root (needs platforms\android-34 and build-tools\34.0.0).

.PARAMETER Jdk
    JDK home (javac, jar, keytool).
#>
param(
    [string]$Sdk = $env:ANDROID_HOME,
    [string]$Jdk = $env:JAVA_HOME,
    # javac from JDK 21 with -target 8 produces anonymous classes that d8 34.0.0 crashes on,
    # so compile with JDK 17 (d8/apksigner still run on $Jdk).
    [string]$CompileJdk = $env:JDK17_HOME,
    [int]$VersionCode = 1,
    [string]$VersionName = '0.1'
)

$ErrorActionPreference = 'Stop'
if (-not $Sdk -or -not $Jdk -or -not $CompileJdk) {
    throw 'Set ANDROID_HOME, JAVA_HOME (JDK 21) and JDK17_HOME, or pass -Sdk, -Jdk and -CompileJdk.'
}
$here = $PSScriptRoot
$bt = Join-Path $Sdk 'build-tools\34.0.0'
$androidJar = Join-Path $Sdk 'platforms\android-34\android.jar'
$env:JAVA_HOME = $Jdk
$env:Path = "$Jdk\bin;$env:Path"

function Exec([string]$what, [scriptblock]$cmd) {
    Write-Host "== $what"
    # Tools like keytool and d8 print progress on stderr; judge success by exit code only.
    $ErrorActionPreference = 'Continue'
    & $cmd
    if ($LASTEXITCODE -ne 0) { throw "$what failed (exit $LASTEXITCODE)" }
}

$out = Join-Path $here 'build'
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force "$out\gen", "$out\classes", "$out\dex" | Out-Null

Exec 'aapt2 compile' { & "$bt\aapt2.exe" compile --dir "$here\res" -o "$out\res.zip" }
Exec 'aapt2 link' {
    & "$bt\aapt2.exe" link -I $androidJar --manifest "$here\AndroidManifest.xml" `
        --min-sdk-version 26 --target-sdk-version 34 `
        --version-code $VersionCode --version-name $VersionName `
        --java "$out\gen" -o "$out\unsigned.apk" "$out\res.zip"
}

$sources = @(Get-ChildItem -Recurse -Filter *.java "$here\src", "$out\gen" | ForEach-Object FullName)
if (-not (Test-Path "$CompileJdk\bin\javac.exe")) { throw "JDK 17 not found at $CompileJdk; pass -CompileJdk" }
Exec 'javac' { & "$CompileJdk\bin\javac.exe" -source 8 -target 8 -Xlint:-options -encoding UTF-8 -bootclasspath "$androidJar;$bt\core-lambda-stubs.jar" -d "$out\classes" @sources }
Exec 'jar classes' { & jar cf "$out\classes.jar" -C "$out\classes" . }
Exec 'd8' { & "$bt\d8.bat" --release --min-api 26 --lib $androidJar --output "$out\dex" "$out\classes.jar" }

# Add classes.dex to the APK produced by aapt2.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$out\unsigned.apk", 'Update')
try {
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$out\dex\classes.dex", 'classes.dex') | Out-Null
} finally { $zip.Dispose() }

Exec 'zipalign' { & "$bt\zipalign.exe" -p -f 4 "$out\unsigned.apk" "$out\aligned.apk" }

$ks = Join-Path $env:USERPROFILE '.android\debug.keystore'
if (-not (Test-Path $ks)) {
    New-Item -ItemType Directory -Force (Split-Path $ks) | Out-Null
    Exec 'keytool (debug keystore)' {
        & keytool -genkeypair -keystore $ks -storepass android -keypass android -alias androiddebugkey `
            -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Android Debug,O=Android,C=US'
    }
}
$apk = Join-Path $out 'task-manager.apk'
Exec 'apksigner' { & "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android --out $apk "$out\aligned.apk" }
Exec 'apksigner verify' { & "$bt\apksigner.bat" verify $apk }
Write-Host "Built $apk ($([math]::Round((Get-Item $apk).Length / 1KB)) KB)"
