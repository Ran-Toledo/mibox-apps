<#
.SYNOPSIS
    Builds an app and files the signed APK under releases\<app>\ with its version in the name.

.DESCRIPTION
    build\ is scratch output that every build overwrites. releases\ keeps one APK per version, e.g.
    releases\screensaver\simple-screensaver-1.6.apk, plus a .sha256 file next to it.
    The version is the -VersionName default in the app's build.ps1, so bump it there first.

.PARAMETER App
    App folder name: screensaver or taskmanager.

.PARAMETER Force
    Replace an existing release of the same version.

.EXAMPLE
    .\release.ps1 screensaver
#>
param(
    [Parameter(Mandatory)][ValidateSet('screensaver', 'taskmanager')][string]$App,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$appDir = Join-Path $root $App
$buildScript = Join-Path $appDir 'build.ps1'

$m = Select-String -Path $buildScript -Pattern "\`$VersionName\s*=\s*'([^']+)'" | Select-Object -First 1
if (-not $m) { throw "No VersionName default found in $buildScript" }
$version = $m.Matches[0].Groups[1].Value

$dirty = git -C $root status --porcelain -- $App
if ($dirty) { Write-Warning "$App has uncommitted changes; this release won't match a commit." }

$destDir = Join-Path $root "releases\$App"
$existing = Get-ChildItem $destDir -Filter "*-$version.apk" -ErrorAction SilentlyContinue
if ($existing -and -not $Force) {
    throw "$($existing.Name) already exists. Bump VersionName in $App\build.ps1, or pass -Force to replace it."
}

& $buildScript
$apk = Get-ChildItem (Join-Path $appDir 'build') -Filter *.apk | Select-Object -First 1
if (-not $apk) { throw "Build produced no APK in $App\build" }

New-Item -ItemType Directory -Force $destDir | Out-Null
$dest = Join-Path $destDir "$($apk.BaseName)-$version.apk"
Copy-Item $apk.FullName $dest -Force
$hash = (Get-FileHash $dest -Algorithm SHA256).Hash.ToLower()
Set-Content -Path "$dest.sha256" -Value "$hash  $(Split-Path $dest -Leaf)" -Encoding ascii

Write-Host "Released $dest"
Write-Host "sha256 $hash"
