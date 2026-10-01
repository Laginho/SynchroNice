param(
    [Parameter(Mandatory, Position=0)]
    [ValidateSet('start','click','type','key','text','screenshot','invoke','presence')][string]$Action,
    [int]$Port = 9222,
    [string]$Selector,
    [string]$Text,
    [string]$Key,
    [string]$Command,
    [string]$Json = '{}',
    [string]$Path,
    [string]$RunDir,
    [string]$Exe,
    [string]$Config = "$env:APPDATA/dev.synchronice.pc/config.json"
)
$ErrorActionPreference = 'Stop'
if (-not $RunDir) { $RunDir = "$PSScriptRoot/../.scratch/qa-runs/$((Get-Date).ToString('yyyy-MM-dd-HHmmss'))-redmi" }
if (-not $Exe) { $Exe = "$PSScriptRoot/../pc/src-tauri/target/release/pc.exe" }
try {
    if ($Port -lt 1 -or $Port -gt 65535) { throw 'Porta CDP invalida' }
    if ($Action -eq 'start') {
        $resolvedExe = (Resolve-Path -LiteralPath $Exe).Path
        New-Item -ItemType Directory -Force -Path $RunDir | Out-Null
        Get-Process pc -ErrorAction SilentlyContinue | Stop-Process -Force
        $previous = $env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS
        try {
            $env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS = "$previous --remote-debugging-port=$Port".Trim()
            $process = Start-Process -FilePath $resolvedExe -WorkingDirectory (Split-Path $resolvedExe) -WindowStyle Hidden -RedirectStandardError "$RunDir/pc.stderr.log" -PassThru
        } finally { $env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS = $previous }
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
        $OutputEncoding = [Text.UTF8Encoding]::new($false)
        @{ action=$Action; port=$Port; selector=$Selector; text=$Text; key=$Key; command=$Command; args=($Json | ConvertFrom-Json); path=$Path; config=$Config } |
            ConvertTo-Json -Compress -Depth 30 | & node "$PSScriptRoot/pc.mjs"
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }
} catch { [Console]::Error.WriteLine($_.Exception.Message); exit 1 }
