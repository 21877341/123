#requires -Version 5.1
<#
.SYNOPSIS
    Samples the HyperOS desktop process: page faults, swap, oom/frozen state,
    gfxinfo and the relevant framework settings.

.EXAMPLE
    .\diagnose.ps1 -Package com.miui.home
#>
[CmdletBinding()]
param(
    [string]$Package = ""
)

$ErrorActionPreference = "Continue"

function Fail([string]$Message) {
    Write-Host ""
    Write-Host "ERROR: $Message" -ForegroundColor Red
    exit 1
}

function Section([string]$Title) {
    Write-Host ""
    Write-Host "=== $Title ===" -ForegroundColor Cyan
}

function Sh([string]$Command) {
    $out = (& adb shell $Command 2>&1 | Out-String)
    return $out.TrimEnd()
}

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    Fail "adb not found in PATH. Install Android platform-tools first."
}

$devices = (& adb devices 2>&1 | Out-String)
if ($devices -notmatch "\tdevice") {
    Write-Host $devices
    Fail "No adb device in 'device' state."
}

Section "device"
Sh "getprop ro.product.model"
Sh "getprop ro.build.version.release"
Sh "getprop ro.build.version.sdk"
Sh "getprop ro.mi.os.version.name"

Section "home activity"
$home = Sh "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
Write-Host $home

if (-not $Package) {
    $line = ($home -split "`n" | Where-Object { $_ -match "/" } | Select-Object -Last 1)
    if ($line) {
        $Package = ($line -split "/")[0].Trim()
    }
}
if (-not $Package) {
    Fail "Cannot determine the launcher package. Re-run with -Package <name>."
}
Write-Host "Target package: $Package" -ForegroundColor Yellow

Section "processes"
Sh "ps -A -o PID,PPID,NAME | grep -i $Package"

$pids = @()
$pidText = Sh "pidof $Package"
if ($pidText) {
    $pids = $pidText -split "\s+" | Where-Object { $_ -match '^\d+$' }
}
if ($pids.Count -eq 0) {
    Fail "No running process for $Package. Open the desktop first."
}
Write-Host ("PIDs: " + ($pids -join ", "))

Section "can LSPosed inject? (libart.so must be mapped)"
foreach ($p in $pids) {
    # /proc/<pid>/maps of another app is not readable as the shell UID on newer
    # Android, so go through su; fall back if that is unavailable.
    $art = Sh "su -c 'grep -c libart.so /proc/$p/maps'"
    if ($art -notmatch '^\d+$') {
        $art = Sh "grep -c libart.so /proc/$p/maps"
    }
    $art = (($art -split "`n") | Select-Object -First 1).Trim()
    Write-Host ("pid {0}: libart.so mappings = {1}" -f $p, $art)
    if ($art -eq "0") {
        Write-Host "  !! No ART in this process. LSPosed cannot load into it." -ForegroundColor Red
    }
}

Section "oom adj / frozen state"
$dump = Sh "dumpsys activity processes"
$dumpLines = $dump -split "`n"
$dumpLines |
    Select-String -Pattern ([regex]::Escape($Package)) -Context 2, 14 |
    ForEach-Object {
        $_.Context.PreContext | ForEach-Object { Write-Host $_ }
        Write-Host $_.Line -ForegroundColor Yellow
        $_.Context.PostContext | ForEach-Object { Write-Host $_ }
        Write-Host "---"
    }

function Get-Faults([string]$Stat) {
    $close = $Stat.LastIndexOf(')')
    if ($close -lt 0) {
        return @(0, 0)
    }
    $rest = ($Stat.Substring($close + 1).Trim() -split "\s+")
    # After comm: state ppid pgrp session tty tpgid flags minflt cminflt majflt ...
    # index:        0    1    2     3      4    5     6     7      8      9
    if ($rest.Length -lt 10) {
        return @(0, 0)
    }
    return @([int64]$rest[7], [int64]$rest[9])
}

Section "page fault sampling (10 rounds x 3s)"
$prev = @{}
foreach ($p in $pids) {
    $prev[$p] = Get-Faults (Sh "cat /proc/$p/stat")
}

for ($round = 1; $round -le 10; $round++) {
    Start-Sleep -Seconds 3
    foreach ($p in $pids) {
        $f = Get-Faults (Sh "cat /proc/$p/stat")
        $dMinor = $f[0] - $prev[$p][0]
        $dMajor = $f[1] - $prev[$p][1]
        $prev[$p] = $f
        $status = Sh "grep -E 'VmRSS|VmSwap' /proc/$p/status"
        $status = (($status -split "`n") | ForEach-Object { $_.Trim() }) -join " | "
        Write-Host ("round {0,2}  pid {1}  dMinor={2,9}  dMajor={3,6}  {4}" -f `
                $round, $p, $dMinor, $dMajor, $status)
    }
}

Section "meminfo (head)"
(Sh "dumpsys meminfo $Package") -split "`n" | Select-Object -First 30

Section "gfxinfo (janky frames)"
(Sh "dumpsys gfxinfo $Package") -split "`n" |
    Select-String -Pattern "Total frames|Janky frames|50th|90th|95th|99th" |
    ForEach-Object { $_.Line }

Section "kernel vmstat"
Sh "grep -E 'pgmajfault|pswpin|pswpout|nr_free_pages' /proc/vmstat"

Section "freezer / compaction settings"
Sh "settings get global cached_apps_freezer"
Sh "device_config list activity_manager_native_boot"

Section "done"
Write-Host "Keep a baseline copy, enable enforcement, then run again and diff." -ForegroundColor Green
