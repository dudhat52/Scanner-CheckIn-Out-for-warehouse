<#
  Scanner Check-In - IT Asset Tag registration (PC tool, Option B)

  Connect a PM86 (USB or wireless ADB) -> serial is read automatically (ro.serialno)
  -> scan/type the 6-digit Asset Tag -> saved to the scanner AND to the master CSV.

  Master CSV columns: Serial Number,Asset Tag,Scanner Type,Status,Registered Date,Last Updated
  History CSV keeps every change.

  Usage:  powershell -ExecutionPolicy Bypass -File register_scanner.ps1 [-MasterCsv path] [-EndpointUrl url] [-AdminPin 123456]
  Tip: put the master CSV in a OneDrive/SharePoint-synced folder so it is shared and backed up.
#>
param(
    [string]$MasterCsv = (Join-Path $PSScriptRoot "scanner_master.csv"),
    [string]$EndpointUrl = "",
    [string]$AdminPin = ""
)

$ErrorActionPreference = "Stop"
$Pkg = "com.idlogistics.scannercheckout"
$HistoryCsv = [System.IO.Path]::ChangeExtension($MasterCsv, $null).TrimEnd('.') + "_history.csv"
$LocalAdb = Join-Path $PSScriptRoot "adb.exe"
$Adb = if (Test-Path $LocalAdb) { $LocalAdb } else { "adb" }

function Invoke-Adb([string[]]$AdbArgs) {
    $out = & $Adb @AdbArgs 2>&1
    return ($out | Out-String).Trim()
}

function Get-Devices {
    $lines = (Invoke-Adb @("devices")) -split "`r?`n"
    return @($lines | Where-Object { $_ -match "^\S+\s+device$" } | ForEach-Object { ($_ -split "\s+")[0] })
}

function Load-Master {
    if (Test-Path $MasterCsv) { return @(Import-Csv $MasterCsv) }
    return @()
}

function Save-Master($rows) {
    $rows | Select-Object "Serial Number","Asset Tag","Scanner Type","Status","Registered Date","Last Updated" |
        Export-Csv -Path $MasterCsv -NoTypeInformation -Encoding UTF8
}

function Add-History($serial, $old, $new, $note) {
    $row = [pscustomobject]@{
        "Timestamp"     = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss")
        "Serial Number" = $serial
        "Old Asset Tag" = $old
        "New Asset Tag" = $new
        "IT User"       = $env:USERNAME
        "PC"            = $env:COMPUTERNAME
        "Note"          = $note
    }
    $row | Export-Csv -Path $HistoryCsv -NoTypeInformation -Append -Encoding UTF8
}

function Send-Provision($dev, [string[]]$extra) {
    $a = @("-s", $dev, "shell", "am", "broadcast", "-a", "$Pkg.PROVISION", "-n", "$Pkg/.ProvisionReceiver") + $extra
    $out = Invoke-Adb $a
    $code = if ($out -match "result=(-?\d+)") { [int]$Matches[1] } else { -999 }
    $data = if ($out -match 'data="([^"]*)"') { $Matches[1] } else { "" }
    return @{ Code = $code; Data = $data; Raw = $out }
}

Write-Host ""
Write-Host "=== Scanner Check-In : Asset Tag registration ===" -ForegroundColor Cyan
Write-Host "Master list: $MasterCsv"
Write-Host ""

do {
    # ---- pick device -----------------------------------------------------------------
    $devs = Get-Devices
    if ($devs.Count -eq 0) {
        Write-Host "No scanner connected. Connect one and press Enter (or type Q to quit)." -ForegroundColor Yellow
        if ((Read-Host) -match "^[Qq]") { break }
        continue
    }
    $dev = $devs[0]
    if ($devs.Count -gt 1) {
        for ($i = 0; $i -lt $devs.Count; $i++) { Write-Host "  [$($i+1)] $($devs[$i])" }
        $sel = Read-Host "Several scanners connected - choose number"
        $dev = $devs[[int]$sel - 1]
    }

    $serial = (Invoke-Adb @("-s", $dev, "shell", "getprop", "ro.serialno")).ToUpper()
    $model  = Invoke-Adb @("-s", $dev, "shell", "getprop", "ro.product.model")
    Write-Host ""
    Write-Host "Scanner detected:  $model   Serial Number: $serial" -ForegroundColor Green
    if ($model -ne "PM86") { Write-Host "WARNING: not a PM86" -ForegroundColor Yellow }
    if ($serial -notmatch "^[A-Z0-9]{6,20}$") { Write-Host "Could not read serial number." -ForegroundColor Red; continue }

    $installed = Invoke-Adb @("-s", $dev, "shell", "pm", "list", "packages", $Pkg)
    if ($installed -notmatch [regex]::Escape($Pkg)) {
        Write-Host "The Scanner Check-In app is not installed on this scanner. Run setup_device.bat first." -ForegroundColor Red
        if ((Read-Host "Press Enter to retry, Q to quit") -match "^[Qq]") { break }
        continue
    }

    # ---- optional one-time config -------------------------------------------------------
    $cfg = @("--es", "cmd", "config")
    if ($EndpointUrl) { $cfg += @("--es", "endpoint_url", "'$EndpointUrl'") }
    if ($AdminPin)    { $cfg += @("--es", "admin_pin", $AdminPin) }
    if ($cfg.Count -gt 3) {
        $r = Send-Provision $dev $cfg
        Write-Host "Config: $($r.Data)"
    }

    # ---- asset tag ----------------------------------------------------------------------
    $master = Load-Master
    $bySerial = $master | Where-Object { $_."Serial Number" -eq $serial } | Select-Object -First 1

    if ($bySerial) {
        Write-Host "Scanner already registered: Asset Tag $($bySerial.'Asset Tag') (status $($bySerial.Status))" -ForegroundColor Yellow
    }

    $tag = ""
    while ($true) {
        $tag = (Read-Host "Scan or type the 6-digit Asset Tag (blank = skip)").Trim()
        if ($tag -eq "") { break }
        if ($tag -notmatch "^\d{6}$") { Write-Host "Asset Tag must be exactly 6 digits." -ForegroundColor Red; continue }
        $byTag = $master | Where-Object { $_."Asset Tag" -eq $tag -and $_."Serial Number" -ne $serial -and $_.Status -ne "RETIRED" } | Select-Object -First 1
        if ($byTag) {
            Write-Host "Asset Tag $tag is already assigned to scanner $($byTag.'Serial Number'). Not allowed." -ForegroundColor Red
            continue
        }
        break
    }

    if ($tag -ne "") {
        $force = $false
        $old = ""
        if ($bySerial -and $bySerial."Asset Tag" -ne $tag) {
            $old = $bySerial."Asset Tag"
            $confirm = Read-Host "Change Asset Tag $old -> $tag ? Type CHANGE to confirm"
            if ($confirm -ne "CHANGE") { Write-Host "Cancelled." -ForegroundColor Yellow; $tag = "" }
            else { $force = $true }
        }
        elseif ($bySerial -and $bySerial."Asset Tag" -eq $tag) {
            $force = $true   # same tag: re-push to device (e.g. after app reinstall)
            $old = $tag
        }
    }

    if ($tag -ne "") {
        $extra = @("--es", "cmd", "register", "--es", "serial", $serial, "--es", "asset_tag", $tag)
        if ($force) { $extra += @("--ez", "force", "true") }
        $r = Send-Provision $dev $extra

        switch ($r.Code) {
            1 {
                # Central master list (Excel tblScanners) through the flow, when the URL is given
                if ($EndpointUrl) {
                    try {
                        $body = @{ Operation = "Register"; SerialNumber = $serial; AssetTag = $tag; Force = $force } | ConvertTo-Json
                        $resp = Invoke-RestMethod -Method Post -Uri $EndpointUrl -ContentType "application/json" -Body $body
                        Write-Host "Excel master list: $($resp.Code) - $($resp.Message)" -ForegroundColor Green
                    } catch {
                        $msg = $_.ErrorDetails.Message
                        Write-Host "Excel master list NOT updated: $msg" -ForegroundColor Red
                        Write-Host "Fix the problem and run again (the scanner itself is already set)." -ForegroundColor Yellow
                    }
                } else {
                    Write-Host "No -EndpointUrl given: Excel master list not updated (CSV only)." -ForegroundColor Yellow
                }
                $today = (Get-Date).ToString("yyyy-MM-dd")
                if ($bySerial) {
                    $bySerial."Asset Tag" = $tag; $bySerial.Status = "ACTIVE"; $bySerial."Last Updated" = $today
                } else {
                    $master += [pscustomobject]@{
                        "Serial Number" = $serial; "Asset Tag" = $tag; "Scanner Type" = "PM86"
                        "Status" = "ACTIVE"; "Registered Date" = $today; "Last Updated" = $today
                    }
                }
                Save-Master $master
                Add-History $serial $old $tag ($(if ($old -and $old -ne $tag) { "CHANGED" } elseif ($old) { "RE-PUSHED" } else { "NEW" }))
                Write-Host "SAVED:  $serial  ->  $tag" -ForegroundColor Green
            }
            2 { Write-Host "Scanner says it is already registered: $($r.Data). Use CHANGE to replace." -ForegroundColor Red }
            3 { Write-Host "Rejected as invalid: $($r.Data)" -ForegroundColor Red }
            4 { Write-Host "Serial mismatch on device: $($r.Data). Not saved - check the device." -ForegroundColor Red }
            default { Write-Host "No answer from the app. Raw output:`n$($r.Raw)" -ForegroundColor Red }
        }
    }

    $st = Send-Provision $dev @("--es", "cmd", "status")
    Write-Host "Device status: $($st.Data)"
    Write-Host ""
    $again = Read-Host "Connect the next scanner and press Enter (Q to quit)"
} while ($again -notmatch "^[Qq]")

Write-Host "Done. Master list: $MasterCsv"
