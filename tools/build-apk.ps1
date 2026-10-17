#requires -Version 5.1
<#
.SYNOPSIS
    Builds the debug APK on a machine that already has JDK 17 + Android SDK 35.

.EXAMPLE
    .\tools\build-apk.ps1
#>
[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
        $jbr = "C:\Program Files\Android\Android Studio\jbr\bin"
        if (Test-Path (Join-Path $jbr "java.exe")) {
            $env:PATH = "$jbr;$env:PATH"
        }
    }
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
        throw "java not found. Install JDK 17 or Android Studio."
    }

    $sdk = $env:ANDROID_HOME
    if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
    if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk" }
    if (-not (Test-Path $sdk)) {
        throw "Android SDK not found. Set ANDROID_HOME or install it via Android Studio."
    }
    $env:ANDROID_HOME = $sdk
    $env:ANDROID_SDK_ROOT = $sdk

    if (-not (Test-Path (Join-Path $sdk "platforms\android-35"))) {
        throw "Android 35 platform missing. Install platforms;android-35 and build-tools;35.0.0 with sdkmanager."
    }

    $gradleExe = $null
    $gradle = Get-Command gradle -ErrorAction SilentlyContinue
    if ($gradle) {
        $gradleExe = $gradle.Source
    } elseif (Test-Path (Join-Path $root "gradlew.bat")) {
        $gradleExe = Join-Path $root "gradlew.bat"
    } else {
        throw "gradle not found. Install Gradle 8.9, or open the project once in Android Studio to generate the wrapper."
    }

    & $gradleExe ":app:assembleDebug" --no-daemon
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle failed with exit code $LASTEXITCODE"
    }

    $apk = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"
    if (-not (Test-Path $apk)) {
        throw "APK not found at $apk"
    }

    $dist = Join-Path $root "dist"
    New-Item -ItemType Directory -Force -Path $dist | Out-Null
    $out = Join-Path $dist "hyperos-desktop-smooth-debug.apk"
    Copy-Item $apk $out -Force
    Write-Host "APK: $out" -ForegroundColor Green
}
finally {
    Pop-Location
}
