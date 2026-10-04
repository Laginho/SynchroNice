param(
    [Parameter(Mandatory, Position=0)]
    [ValidateSet('start','click','type','key','text','screenshot','invoke','presence','list-files','track','clean','paste-image','paste-files')][string]$Action,
    [int]$Port = 9222,
    [string]$Selector,
    [string]$Text,
    [string]$Key,
    [string]$Command,
    [string]$Json = '{}',
    [string]$Path,
    [string[]]$Paths,
    [string]$RunDir,
    [string]$Exe,
    [switch]$Isolated,
    [string]$Config = "$env:APPDATA/dev.synchronice.pc/config.json"
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding
function Stop-AndWait {
    param([Parameter(ValueFromPipeline)][Diagnostics.Process]$Process)
    process {
        try {
            if (-not $Process.HasExited) {
                # Keep the handle so waiting cannot attach to a reused PID.
                $null = $Process.Handle
                $Process | Stop-Process -Force -ErrorAction Stop
                if (-not $Process.WaitForExit(10000)) { throw 'nao encerrou em 10 segundos' }
            }
        } catch {
            if (-not $Process.HasExited) { throw "infraestrutura: PID $($Process.Id): $($_.Exception.Message)" }
        }
    }
}
$launch = $null
$launcher = $null
if ($Action -in 'list-files','track','clean' -and -not $RunDir) {
    [Console]::Error.WriteLine('Informe -RunDir compartilhado por toda a execucao de QA'); exit 1
}
if (-not $RunDir) { $RunDir = "$PSScriptRoot/../.scratch/qa-runs/$((Get-Date).ToString('yyyy-MM-dd-HHmmss'))-redmi" }
$RunDir = [IO.Path]::GetFullPath($RunDir)
if ($Isolated) { $Config = Join-Path $RunDir 'data/config.json' }
if (-not $Exe) {
    $build = if ($Isolated) { 'debug' } else { 'release' }
    $Exe = "$PSScriptRoot/../pc/src-tauri/target/$build/pc.exe"
}
try {
    if ($Port -lt 1 -or $Port -gt 65535) { throw 'Porta CDP invalida' }
    if ($Action -in 'list-files','track','clean') {
        if (-not (Test-Path -LiteralPath $RunDir)) { New-Item -ItemType Directory -Path $RunDir | Out-Null }
        $settings = Get-Content -LiteralPath $Config -Raw | ConvertFrom-Json
        $folder = $settings.pasta
        if (-not $folder) {
            $downloads = (Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer\User Shell Folders').'{374DE290-123F-4565-9164-39C4925E467B}'
            $folder = Join-Path ([Environment]::ExpandEnvironmentVariables($downloads)) 'SynchroNice'
        }
        $folder = [IO.Path]::GetFullPath($folder)
        if ($folder -ne [IO.Path]::GetPathRoot($folder)) { $folder = $folder.TrimEnd('\','/') }
        $manifest = Join-Path $RunDir 'pc-files.json'
        $state = $null
        if (Test-Path -LiteralPath $manifest) {
            $state = Get-Content -LiteralPath $manifest -Raw | ConvertFrom-Json
            if ($state.folder -ne $folder) { throw 'Pasta mudou: use outra execucao de QA' }
            foreach ($previousFile in @($state.baseline)) {
                if ($previousFile -isnot [string] -or [IO.Path]::GetDirectoryName($previousFile) -ne $folder) {
                    throw 'Manifesto inseguro: baseline invalido; preserve os arquivos e inicie outra execucao'
                }
            }
        }
        if ($Action -eq 'list-files') {
            $listing = @{ action=$Action; folder=$folder } | ConvertTo-Json -Compress | & node "$PSScriptRoot/pc.mjs"
            if ($LASTEXITCODE -ne 0) { throw 'Listagem da Pasta falhou' }
            if (-not $state) {
                $initialFiles = $listing | ConvertFrom-Json
                @{ folder=$folder; baseline=@($initialFiles); tracked=@(); cleanup=@() } |
                    ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $manifest -Encoding UTF8
            }
            $listing
        } elseif ($Action -eq 'track') {
            if (-not $state) { throw 'Rode list-files antes do envio, com o mesmo -RunDir' }
            $file = Get-Item -LiteralPath $Path
            if ($file.PSIsContainer -or ($file.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
                $file.DirectoryName -ne $folder -or @($state.baseline) -contains $file.FullName) {
                throw 'So registre arquivos novos desta execucao, diretamente na Pasta'
            }
            if (@($state.tracked.path) -notcontains $file.FullName) {
                $state.tracked = @($state.tracked) + @{ path=$file.FullName; hash=(Get-FileHash -LiteralPath $file.FullName).Hash }
                $state | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $manifest -Encoding UTF8
            }
            $state.tracked | ConvertTo-Json -Depth 10
        } elseif ($state) {
            Add-Type -AssemblyName Microsoft.VisualBasic
            foreach ($entry in @($state.tracked)) {
                $target = [IO.Path]::GetFullPath($entry.path)
                if ([IO.Path]::GetDirectoryName($target) -ne $folder -or @($state.baseline) -contains $target) {
                    throw 'Manifesto inseguro: arquivo fora da Pasta ou anterior ao QA'
                }
                $destination = 'ja ausente'
                if (Test-Path -LiteralPath $target) {
                    $file = Get-Item -LiteralPath $target
                    if ($file.PSIsContainer -or ($file.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
                        (Get-FileHash -LiteralPath $target).Hash -ne $entry.hash) { throw "Arquivo alterado; preservado: $target" }
                    $shell = New-Object -ComObject Shell.Application
                    $previousRecycled = @($shell.Namespace(10).Items() | ForEach-Object { $_.Path })
                    [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteFile($target,
                        [Microsoft.VisualBasic.FileIO.UIOption]::OnlyErrorDialogs,
                        [Microsoft.VisualBasic.FileIO.RecycleOption]::SendToRecycleBin,
                        [Microsoft.VisualBasic.FileIO.UICancelOption]::ThrowException)
                    if (Test-Path -LiteralPath $target) { throw "Limpeza nao removeu arquivo; mantido no manifesto: $target" }
                    $destination = 'remocao permanente'
                    # SendToRecycleBin can permanently delete on drives without a Recycle Bin.
                    # Only a new entry with the original path and bytes proves recycling.
                    foreach ($recycled in $shell.Namespace(10).Items()) {
                        if ($previousRecycled -contains $recycled.Path) { continue }
                        $originalFolder = $recycled.ExtendedProperty('System.Recycle.DeletedFrom')
                        # Display names omit extensions such as .lnk even when extensions are visible.
                        $originalName = $recycled.ExtendedProperty('System.FileName')
                        if ($originalFolder -and $originalName -and
                            (Join-Path $originalFolder $originalName) -eq $target -and
                            (Get-FileHash -LiteralPath $recycled.Path).Hash -eq $entry.hash) {
                            $destination = 'Lixeira do Windows'
                            break
                        }
                    }
                }
                $state.cleanup = @($state.cleanup) + @{ path=$target; destination=$destination }
                $state.tracked = @($state.tracked | Where-Object { $_.path -ne $entry.path })
                $state | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $manifest -Encoding UTF8
            }
            ConvertTo-Json -InputObject @($state.cleanup) -Depth 10
        } else { '[]' }
        exit 0
    }
    if ($Action -in 'paste-image','paste-files') {
        Add-Type -AssemblyName System.Windows.Forms
        if ($Action -eq 'paste-image') {
            Add-Type -AssemblyName System.Drawing
            $image = [Drawing.Image]::FromFile((Resolve-Path -LiteralPath $Path).Path)
            try { [Windows.Forms.Clipboard]::SetImage($image) } finally { $image.Dispose() }
        } else {
            if (-not $Paths) { throw 'Informe -Paths com um ou mais arquivos' }
            $files = [Collections.Specialized.StringCollection]::new()
            foreach ($candidate in $Paths) {
                $file = Get-Item -LiteralPath $candidate
                if ($file.PSIsContainer) { throw 'Clipboard aceita arquivos, nao diretorios' }
                [void]$files.Add($file.FullName)
            }
            [Windows.Forms.Clipboard]::SetFileDropList($files)
        }
    }
    if ($Action -eq 'start') {
        $resolvedExe = (Resolve-Path -LiteralPath $Exe).Path
        New-Item -ItemType Directory -Force -Path $RunDir | Out-Null
        if ($Isolated) {
            Get-Process pc -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $resolvedExe } | Stop-AndWait
            if (Get-Process pc -ErrorAction SilentlyContinue) { throw 'Feche a instancia real antes de abrir o PC isolado (qa/up.ps1)' }
            $dataDir = Join-Path $RunDir 'data'
            New-Item -ItemType Directory -Force -Path "$dataDir/Pasta" | Out-Null
            if (-not (Test-Path -LiteralPath $Config)) {
                [IO.File]::WriteAllText($Config, (@{ pasta="$dataDir/Pasta" } | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
            }
        } else { Get-Process pc -ErrorAction SilentlyContinue | Stop-AndWait }
        $previous = $env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS
        $previousData = $env:SYNCHRONICE_DATA_DIR
        $previousProfile = $env:WEBVIEW2_USER_DATA_FOLDER
        try {
            $env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS = "$previous --remote-debugging-port=$Port".Trim()
            if ($Isolated) {
                $env:SYNCHRONICE_DATA_DIR = $dataDir
                $env:WEBVIEW2_USER_DATA_FOLDER = Join-Path $RunDir 'webview'
            }
            # A WMI launcher avoids inheriting the caller's native pipes: start must return while the app lives.
            $pidFile = Join-Path $RunDir 'pc.pid'
            [IO.File]::WriteAllText($pidFile, '')
            $launchScript = '$p = Start-Process -WindowStyle Hidden -PassThru -FilePath ' +
                "'" + $resolvedExe.Replace("'", "''") + "' -WorkingDirectory '" + (Split-Path $resolvedExe).Replace("'", "''") +
                "' -RedirectStandardError '" + "$RunDir/pc.stderr.log".Replace("'", "''") +
                "' -RedirectStandardOutput '" + "$RunDir/pc.stdout.log".Replace("'", "''") +
                "'; " + '$p.Id | Set-Content -LiteralPath ' + "'" + $pidFile.Replace("'", "''") + "'"
            $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($launchScript))
            $startup = New-CimInstance -ClassName Win32_ProcessStartup -ClientOnly -Property @{
                ShowWindow=[uint16]0
                EnvironmentVariables=[string[]]@([Environment]::GetEnvironmentVariables().GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" })
            }
            $launchRequestedAt = Get-Date
            $launch = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
                CommandLine="powershell.exe -NoProfile -ExecutionPolicy Bypass -EncodedCommand $encoded"
                ProcessStartupInformation=$startup
            }
            if ($launch.ReturnValue -ne 0) { throw "Nao foi possivel abrir o PC: WMI $($launch.ReturnValue)" }
            $launcher = Get-Process -Id $launch.ProcessId -ErrorAction SilentlyContinue
            if ($launcher -and $launcher.StartTime -lt $launchRequestedAt) { $launcher = $null }
            for ($attempt = 0; $attempt -lt 30; $attempt++) {
                $startedPid = Get-Content -LiteralPath $pidFile -Raw
                if ($startedPid -match '^\d+\s*$') { break }
                Start-Sleep -Milliseconds 200
            }
            if ($startedPid -notmatch '^\d+\s*$') { throw 'Launcher do PC nao devolveu o PID' }
            if (-not $Isolated) {
                $realStatePath = "$PSScriptRoot/../.scratch/qa-runs/real-pc/state.json"
                New-Item -ItemType Directory -Force -Path (Split-Path $realStatePath) | Out-Null
                @{ pid=[int]$startedPid; exe=$resolvedExe } | ConvertTo-Json |
                    Set-Content -LiteralPath $realStatePath -Encoding UTF8
            }
            $process = Get-Process -Id ([int]$startedPid)
        } finally {
            $env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS = $previous
            $env:SYNCHRONICE_DATA_DIR = $previousData
            $env:WEBVIEW2_USER_DATA_FOLDER = $previousProfile
        }
        $ready = $false
        for ($attempt = 0; $attempt -lt 30; $attempt++) {
            $process.Refresh()
            if ($process.HasExited) { throw "infraestrutura: pc.exe terminou; veja $RunDir/pc.stderr.log" }
            try {
                Invoke-RestMethod "http://127.0.0.1:$Port/json/version" -TimeoutSec 1 | Out-Null
                $ready = $true; break
            } catch { Start-Sleep -Milliseconds 500 }
        }
        if (-not $ready) { throw "infraestrutura: CDP indisponivel; veja $RunDir/pc.stderr.log" }
        @{ pid = $process.Id; port = $Port; stderr = "$RunDir/pc.stderr.log" } | ConvertTo-Json -Compress
    } else {
        # stdin keeps JSON, quotes and Unicode out of Windows command-line escaping.
        @{ action=$Action; port=$Port; selector=$Selector; text=$Text; key=$Key; command=$Command; args=($Json | ConvertFrom-Json); path=$Path; config=$Config } |
            ConvertTo-Json -Compress -Depth 30 | & node "$PSScriptRoot/pc.mjs"
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }
} catch {
    $failure = $_.Exception.Message
    try {
        if ($Action -eq 'start' -and $launch -and $launch.ReturnValue -eq 0) {
            if ($launcher) { $launcher | Stop-AndWait }
            # Enumerate only AFTER the launcher exits: it can no longer spawn a late child.
            # Creation time and executable exclude unrelated children of a reused parent PID.
            Get-CimInstance Win32_Process -Filter "ParentProcessId = $($launch.ProcessId)" |
                Where-Object { $_.ExecutablePath -eq $resolvedExe -and $_.CreationDate -ge $launchRequestedAt } |
                ForEach-Object {
                    $child = Get-Process -Id $_.ProcessId -ErrorAction SilentlyContinue
                    if ($child -and $child.Path -eq $resolvedExe -and $child.StartTime -ge $launchRequestedAt) {
                        $child | Stop-AndWait
                    }
                }
        }
    } catch { $failure += "; limpeza falhou: $($_.Exception.Message)" }
    [Console]::Error.WriteLine($failure); exit 1
}
