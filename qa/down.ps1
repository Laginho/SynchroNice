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
function Save-State([string]$Path, $State) {
    $temporary = $Path + '.' + [guid]::NewGuid().ToString('N') + '.tmp'
    try {
        [IO.File]::WriteAllText($temporary, ($State | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
        # A failed write must leave the last complete restoration record intact.
        if (Test-Path -LiteralPath $Path) { [IO.File]::Replace($temporary, $Path, [NullString]::Value) }
        else { [IO.File]::Move($temporary, $Path) }
    } finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary }
    }
}
function Get-RecordedPc($Record) {
    $candidate = Get-Process -Id $Record.pid -ErrorAction SilentlyContinue
    if (-not $candidate) { return }
    try {
        if ($candidate.HasExited -or $candidate.Path -ne $Record.exe -or
            ($Record.startTime -and $candidate.StartTime.Ticks.ToString() -ne [string]$Record.startTime)) { return }
        $candidate
    } catch {
        if (-not $candidate.HasExited) { throw }
    }
}
. "$PSScriptRoot/process.ps1"
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
        $started = Get-RecordedPc ([pscustomobject]@{ pid=$launch.ProcessId; exe=$Executable })
        if (-not $started) { throw 'PC real nao permaneceu aberto; repita down' }
        $started
    } finally {
        $env:SYNCHRONICE_DATA_DIR = $previousData
        $env:WEBVIEW2_USER_DATA_FOLDER = $previousProfile
        $env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS = $previousCdp
    }
}

try {
    $restoredReal = $false
    $realRestoration = $false
    $restoredPc = $null
    if (Test-Path -LiteralPath $RealPcStatePath) {
        $real = Get-Content -LiteralPath $RealPcStatePath -Raw | ConvertFrom-Json
        $process = Get-RecordedPc $real
        if ($real.restoreComplete) {
            # Emulator cleanup may have failed after this normal PC was restored.
            # A user-closed restored PC is not a new launch request.
            $restoredPc = $process
            $realRestoration = $true
        } elseif ($real.restorePending -or $process) {
            if (-not $real.restorePending) {
                $real | Add-Member -NotePropertyName restorePending -NotePropertyValue $true -Force
                $real | Add-Member -NotePropertyName startTime -NotePropertyValue $process.StartTime.Ticks.ToString() -Force
                Save-State $RealPcStatePath $real
            }
            # A failed stop can leave the original alive even with pending intent.
            if ($process) { $process | Stop-AndWait }
            $restoredPc = Start-NormalPc $real.exe
            $real.pid = $restoredPc.Id
            $real | Add-Member -NotePropertyName startTime -NotePropertyValue $restoredPc.StartTime.Ticks.ToString() -Force
            $real | Add-Member -NotePropertyName restorePending -NotePropertyValue $false -Force
            $real | Add-Member -NotePropertyName restoreComplete -NotePropertyValue $true -Force
            Save-State $RealPcStatePath $real
            $restoredReal = $true
            $realRestoration = $true
        }
    }
    $state = if (Test-Path -LiteralPath $statePath) { Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json }
    if (-not $state -or -not $state.active) {
        if (Test-Path -LiteralPath $RealPcStatePath) { Remove-Item -LiteralPath $RealPcStatePath }
        @{ stopped=$true; restored=$restoredReal } | ConvertTo-Json -Compress; exit 0
    }
    # Restore the PC before any emulator error can interrupt cleanup.
    if ($realRestoration) {
        # The explicit real-PC record takes precedence over the older release fallback.
        $state.realWasRunning = $false
        Save-State $statePath $state
    }
    # The kit owns this debug executable and the fixed HTTP port during the run.
    Get-Process pc -ErrorAction SilentlyContinue | Where-Object {
        $_.Path -eq $debugExe -and -not ($restoredPc -and $_.Id -eq $real.pid -and
            $_.StartTime.Ticks.ToString() -eq [string]$real.startTime)
    } | Stop-AndWait
    if ($state.realWasRunning -and -not (Get-Process pc -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $releaseExe })) {
        $null = Start-NormalPc $releaseExe
    }
    if ($state.serial -match '^emulator-\d+$') {
        $devices = & $adb devices
        if ($LASTEXITCODE -ne 0) { throw 'infraestrutura: adb devices falhou; down pode ser repetido' }
        if ($devices -match ('^' + [regex]::Escape($state.serial) + '\s+')) {
            $avd = & $adb -s $state.serial emu avd name
            if ($LASTEXITCODE -ne 0 -or $avd -notcontains 'SynchroNice_API37') { throw 'Emulador mudou; preservado' }
            $listener = Get-NetTCPConnection -LocalPort ([int]$state.serial.Split('-')[1]) -State Listen -ErrorAction SilentlyContinue |
                Select-Object -First 1
            $emulatorProcess = if ($listener) { Get-Process -Id $listener.OwningProcess -ErrorAction SilentlyContinue }
            if ($emulatorProcess) {
                try {
                    # The AVD check identifies our listener; keep its handle across emu kill.
                    $null = $emulatorProcess.Handle
                } catch {
                    if (-not $emulatorProcess.HasExited) { throw }
                }
            }
            & $adb -s $state.serial emu kill | Out-Host
            if ($LASTEXITCODE -ne 0) { throw 'Nao foi possivel derrubar o emulador' }
            for ($attempt = 0; $attempt -lt 30; $attempt++) {
                $devices = & $adb devices
                if (-not ($devices -match ('^' + [regex]::Escape($state.serial) + '\s+'))) { break }
                Start-Sleep -Seconds 1
            }
            if ($devices -match ('^' + [regex]::Escape($state.serial) + '\s+')) { throw 'Emulador nao encerrou' }
            if ($emulatorProcess -and -not $emulatorProcess.HasExited -and -not $emulatorProcess.WaitForExit(30000)) {
                $emulatorProcess | Stop-AndWait
            }
        }
    }
    if ($state.endpoint) { & $adb disconnect $state.endpoint | Out-Null }
    $state.active = $false
    Save-State $statePath $state
    if (Test-Path -LiteralPath $RealPcStatePath) { Remove-Item -LiteralPath $RealPcStatePath }
    @{ stopped=$true; restored=($restoredReal -or $state.realWasRunning) } | ConvertTo-Json -Compress
} catch { [Console]::Error.WriteLine($_.Exception.Message); exit 1 }
