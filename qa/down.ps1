param(
    [string]$RunDir = "$PSScriptRoot/../.scratch/qa-runs/emulator",
    [string]$Sdk = "$env:LOCALAPPDATA/Android/Sdk"
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
$RunDir = [IO.Path]::GetFullPath($RunDir)
$statePath = Join-Path $RunDir 'emulator.json'
$debugExe = [IO.Path]::GetFullPath((Join-Path $root 'pc/src-tauri/target/debug/pc.exe'))
$releaseExe = [IO.Path]::GetFullPath((Join-Path $root 'pc/src-tauri/target/release/pc.exe'))
try {
    if (-not (Test-Path -LiteralPath $statePath)) { '{"stopped":true,"restored":false}'; exit 0 }
    $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    if (-not $state.active) { '{"stopped":true,"restored":false}'; exit 0 }
    $adb = Join-Path $Sdk 'platform-tools/adb.exe'
    if ($state.serial -match '^emulator-\d+$') {
        $devices = & $adb devices
        if ($LASTEXITCODE -ne 0) { throw 'infraestrutura: adb devices falhou; down pode ser repetido' }
        if ($devices -match ('^' + [regex]::Escape($state.serial) + '\s+')) {
            $avd = & $adb -s $state.serial emu avd name
            if ($LASTEXITCODE -ne 0 -or $avd -notcontains 'SynchroNice_API37') { throw 'Emulador mudou; preservado' }
            & $adb -s $state.serial emu kill | Out-Host
            if ($LASTEXITCODE -ne 0) { throw 'Nao foi possivel derrubar o emulador' }
            for ($attempt = 0; $attempt -lt 30; $attempt++) {
                $devices = & $adb devices
                if (-not ($devices -match ('^' + [regex]::Escape($state.serial) + '\s+'))) { break }
                Start-Sleep -Seconds 1
            }
            if ($devices -match ('^' + [regex]::Escape($state.serial) + '\s+')) { throw 'Emulador nao encerrou' }
        }
    }
    if ($state.endpoint) { & $adb disconnect $state.endpoint | Out-Null }
    # The kit owns this debug executable and the fixed HTTP port during the run.
    Get-Process pc -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $debugExe } | Stop-Process -Force
    if ($state.realWasRunning -and -not (Get-Process pc -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $releaseExe })) {
        $previousData = $env:SYNCHRONICE_DATA_DIR
        $previousProfile = $env:WEBVIEW2_USER_DATA_FOLDER
        $previousCdp = $env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS
        try {
            $env:SYNCHRONICE_DATA_DIR = $null
            $env:WEBVIEW2_USER_DATA_FOLDER = $null
            $env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS = $null
            Start-Process -FilePath $releaseExe -WorkingDirectory (Split-Path $releaseExe) -WindowStyle Hidden | Out-Null
        } finally {
            $env:SYNCHRONICE_DATA_DIR = $previousData
            $env:WEBVIEW2_USER_DATA_FOLDER = $previousProfile
            $env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS = $previousCdp
        }
    }
    $state.active = $false
    $state | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding UTF8
    @{ stopped=$true; restored=$state.realWasRunning } | ConvertTo-Json -Compress
} catch { [Console]::Error.WriteLine($_.Exception.Message); exit 1 }
