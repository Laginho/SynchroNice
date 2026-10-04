param(
    [string]$RunDir = "$PSScriptRoot/../.scratch/qa-runs/emulator",
    [string]$Sdk = "$env:LOCALAPPDATA/Android/Sdk",
    [string]$Adb = "$Sdk/platform-tools/adb.exe",
    [string]$DebugExe = "$PSScriptRoot/../pc/src-tauri/target/debug/pc.exe",
    [string]$ReleaseExe = "$PSScriptRoot/../pc/src-tauri/target/release/pc.exe",
    [string]$RealPcStatePath = "$PSScriptRoot/../.scratch/qa-runs/real-pc/state.json"
)
$ErrorActionPreference = 'Stop'
$RunDir = [IO.Path]::GetFullPath($RunDir)
$statePath = Join-Path $RunDir 'emulator.json'
$debugExe = [IO.Path]::GetFullPath($DebugExe)
$releaseExe = [IO.Path]::GetFullPath($ReleaseExe)
function Stop-AndWait {
    param([Parameter(ValueFromPipeline)][Diagnostics.Process]$Process)
    process {
        $timedOut = $false
        try {
            if (-not $Process.HasExited) {
                # Keep the handle so waiting cannot attach to a reused PID.
                $null = $Process.Handle
                $Process | Stop-Process -Force -ErrorAction Stop
                if (-not $Process.WaitForExit(10000)) {
                    $timedOut = $true
                    throw 'nao encerrou em 10 segundos'
                }
            }
        } catch {
            if ($timedOut -or -not $Process.HasExited) { throw "infraestrutura: PID $($Process.Id): $($_.Exception.Message)" }
        }
    }
}
function Start-NormalPc([string]$Executable) {
    $previousData = $env:SYNCHRONICE_DATA_DIR
    $previousProfile = $env:WEBVIEW2_USER_DATA_FOLDER
    $previousCdp = $env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS
    try {
        $env:SYNCHRONICE_DATA_DIR = $null
        $env:WEBVIEW2_USER_DATA_FOLDER = $null
        $env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS = $null
        $startup = New-CimInstance -ClassName Win32_ProcessStartup -ClientOnly -Property @{
            ShowWindow=[uint16]0
            EnvironmentVariables=[string[]]@([Environment]::GetEnvironmentVariables().GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" })
        }
        $launch = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
            CommandLine=('"' + $Executable + '"')
            CurrentDirectory=(Split-Path $Executable)
            ProcessStartupInformation=$startup
        }
        if ($launch.ReturnValue -ne 0) { throw "PC real nao reabriu (WMI $($launch.ReturnValue)); repita down" }
    } finally {
        $env:SYNCHRONICE_DATA_DIR = $previousData
        $env:WEBVIEW2_USER_DATA_FOLDER = $previousProfile
        $env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS = $previousCdp
    }
}

try {
    $restoredReal = $false
    if (Test-Path -LiteralPath $RealPcStatePath) {
        $real = Get-Content -LiteralPath $RealPcStatePath -Raw | ConvertFrom-Json
        $process = Get-Process -Id $real.pid -ErrorAction SilentlyContinue
        if ($process -and $process.Path -eq $real.exe) {
            $process | Stop-AndWait
            Start-NormalPc $real.exe
            $restoredReal = $true
        }
        Remove-Item -LiteralPath $RealPcStatePath
    }
    if (-not (Test-Path -LiteralPath $statePath)) {
        @{ stopped=$true; restored=$restoredReal } | ConvertTo-Json -Compress; exit 0
    }
    $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    if (-not $state.active) {
        @{ stopped=$true; restored=$restoredReal } | ConvertTo-Json -Compress; exit 0
    }
    # Restore the PC before any emulator error can interrupt cleanup.
    # The kit owns this debug executable and the fixed HTTP port during the run.
    Get-Process pc -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $debugExe } | Stop-AndWait
    if ($state.realWasRunning -and -not (Get-Process pc -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $releaseExe })) {
        Start-NormalPc $releaseExe
    }
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
    $state.active = $false
    $state | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding UTF8
    @{ stopped=$true; restored=($restoredReal -or $state.realWasRunning) } | ConvertTo-Json -Compress
} catch { [Console]::Error.WriteLine($_.Exception.Message); exit 1 }
