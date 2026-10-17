#requires -Version 5.1
<#
.SYNOPSIS
    Enables or disables the system_server half of HyperOS Desktop Smooth.

.DESCRIPTION
    Creating /data/system/hyperos_desktop_fix.sys flips the module from
    dry-run (log only) to enforce (block compaction/freezing/trim) for the
    packages listed in /data/system/hyperos_desktop_targets.txt.

.EXAMPLE
    .\apply-system.ps1 -Package com.miui.home

.EXAMPLE
    .\apply-system.ps1 -Disable
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, ParameterSetName = "Enable")]
    [string]$Package,

    [Parameter(ParameterSetName = "Disable")]
    [switch]$Disable,

    [switch]$NoReboot
)

$ErrorActionPreference = "Stop"

function Fail([string]$Message) {
    Write-Host "ERROR: $Message" -ForegroundColor Red
    exit 1
}

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    Fail "adb not found in PATH."
}

$id = (& adb shell "su -c id" 2>&1 | Out-String)
if ($id -notmatch "uid=0") {
    Write-Host $id
    Fail "root (su) is required for the system-side switch."
}

if ($Disable) {
    & adb shell "su -c 'rm -f /data/system/hyperos_desktop_fix.sys'"
    Write-Host "System-side enforcement disabled." -ForegroundColor Yellow
}
else {
    $tmp = New-TemporaryFile
    try {
        Set-Content -Path $tmp.FullName -Value $Package -Encoding Ascii
        & adb push $tmp.FullName /data/local/tmp/hyperos_desktop_targets.txt | Out-Null
        & adb shell "su -c 'cp /data/local/tmp/hyperos_desktop_targets.txt /data/system/hyperos_desktop_targets.txt && chmod 644 /data/system/hyperos_desktop_targets.txt && touch /data/system/hyperos_desktop_fix.sys'"
        & adb shell "su -c 'ls -l /data/system/hyperos_desktop_targets.txt /data/system/hyperos_desktop_fix.sys; cat /data/system/hyperos_desktop_targets.txt'"
        Write-Host "Enforcement enabled for $Package." -ForegroundColor Green
    }
    finally {
        Remove-Item $tmp.FullName -ErrorAction SilentlyContinue
    }
}

if (-not $NoReboot) {
    Write-Host "Rebooting..." -ForegroundColor Yellow
    & adb reboot
}
else {
    Write-Host "Reboot skipped; system_server only re-reads the switch at boot." -ForegroundColor Yellow
}
