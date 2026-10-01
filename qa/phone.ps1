param(
    [Parameter(Mandatory, Position=0)]
    [ValidateSet('tap','type','key','dump','screenshot','open','stop','pair')][string]$Action,
    [string]$Serial = $env:SYNCHRONICE_ADB_SERIAL,
    [ValidateSet('id','text','description')][string]$By = 'id',
    [string]$Value,
    [string]$Text,
    [string]$Key,
    [string]$Path,
    [string]$Config = "$env:APPDATA/dev.synchronice.pc/config.json",
    [string]$Ip,
    [string]$Adb = $env:SYNCHRONICE_ADB
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding
if (-not $Adb) {
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command) { $Adb = $command.Source }
    else { $Adb = "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" }
}
function Wireless([string]$candidate) {
    return $candidate -match '^\S+:\d+$|^adb-\S+\._adb-tls-connect\._tcp\.?$'
}
function Adb-Run([string[]]$Arguments, [switch]$AllowFailure) {
    # Windows PowerShell treats native stderr as ErrorRecords even with 2>&1.
    $ErrorActionPreference = 'Continue'
    $result = & $Adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0 -and -not $AllowFailure) { throw "infraestrutura adb: $result" }
    return ($result -join "`n")
}
function Dump {
    $remote = '/data/local/tmp/synchronice-qa-' + [guid]::NewGuid().ToString('N') + '.xml'
    try {
        for ($attempt = 0; $attempt -lt 2; $attempt++) {
            $result = Adb-Run -Arguments @('shell','uiautomator','dump',$remote) -AllowFailure
            if ($result -match 'could not get idle state' -and $attempt -eq 0) { Start-Sleep -Milliseconds 500; continue }
            if ($LASTEXITCODE -ne 0 -or $result -match 'ERROR') { throw "infraestrutura dump: $result" }
            $xml = Adb-Run @('exec-out','cat',$remote)
            if ($xml -notmatch '<hierarchy') { throw 'infraestrutura: dump sem XML' }
            return $xml
        }
    } finally { & $Adb -s $Serial shell rm -f $remote 2>&1 | Out-Null }
}
try {
    if (-not (Get-Command $Adb -ErrorAction SilentlyContinue)) { throw 'infraestrutura: adb nao encontrado' }
    if ($Serial -and -not (Wireless $Serial)) { throw 'sem aparelho: transporte USB recusado' }
    if (-not $Serial) {
        $devices = & $Adb devices 2>&1
        if ($LASTEXITCODE -ne 0) { throw 'infraestrutura: adb devices falhou' }
        $Serial = @($devices | ForEach-Object {
            if ($_ -match '^(\S+)\s+device\s*$' -and (Wireless $Matches[1])) { $Matches[1] }
        }) | Select-Object -First 1
        if (-not $Serial) {
            $services = & $Adb mdns services
            if ($LASTEXITCODE -ne 0) { throw 'infraestrutura: adb mdns falhou' }
            $Serial = @($services | ForEach-Object {
                if ($_ -match '^\S+\s+_adb-tls-connect\._tcp\.?\s+(\S+:\d+)') { $Matches[1] }
            }) | Select-Object -First 1
        }
    }
    if (-not $Serial) { throw 'sem aparelho: nenhum transporte adb sem fio' }
    $ErrorActionPreference = 'Continue'
    $connection = & $Adb connect $Serial 2>&1
    $ErrorActionPreference = 'Stop'
    if ($LASTEXITCODE -ne 0 -or $connection -match 'failed|cannot|unable') { throw "infraestrutura: adb connect: $connection" }
    if ((Adb-Run @('get-state')).Trim() -ne 'device') { throw 'infraestrutura: aparelho offline' }
    foreach ($scale in 'window_animation_scale','transition_animation_scale','animator_duration_scale') {
        Adb-Run @('shell','settings','put','global',$scale,'0') | Out-Null
    }
    Adb-Run @('shell','input','keyevent','KEYCODE_WAKEUP') | Out-Null
    Adb-Run @('shell','wm','dismiss-keyguard') | Out-Null
    $policy = Adb-Run @('shell','dumpsys','window','policy')
    if ($policy -match '(?:mShowingLockscreen|isStatusBarKeyguard|mKeyguardShowing|showing)=true') {
        throw 'infraestrutura: aparelho bloqueado; desbloqueie manualmente (o kit nao recebe PIN)'
    }
    switch ($Action) {
        'dump' { Dump }
        'tap' {
            if (-not $Value) { throw 'Informe -Value' }
            $local = [IO.Path]::GetTempFileName()
            try {
                [IO.File]::WriteAllText($local, (Dump), [Text.UTF8Encoding]::new($false))
                $point = & node "$PSScriptRoot/dump.mjs" $local $By $Value
                if ($LASTEXITCODE -ne 0) { throw 'parser do dump falhou' }
                $point = $point | ConvertFrom-Json
                if ($null -eq $point) { throw "elemento nao achado: $By=$Value" }
                Adb-Run @('shell','input','tap',"$($point.x)","$($point.y)")
            } finally { Remove-Item -LiteralPath $local }
        }
        'type' {
            # adb input text has no Unicode support and interprets %s as a space.
            if (-not $Text -or $Text -match '[^\x20-\x7e]|%') { throw 'Use texto ASCII imprimivel sem % (limite do adb input text)' }
            $quoted = "'" + $Text.Replace("'", "'\''").Replace(' ', '%s') + "'"
            Adb-Run @('shell',"input text $quoted")
        }
        'key' {
            if ($Key -notmatch '^(KEYCODE_)?[A-Z0-9_]+$') { throw 'Informe -Key como KEYCODE_ENTER ou 66' }
            Adb-Run @('shell','input','keyevent',$Key)
        }
        'screenshot' {
            if (-not $Path) { throw 'Informe -Path para o PNG' }
            $remote = '/data/local/tmp/synchronice-qa-' + [guid]::NewGuid().ToString('N') + '.png'
            try {
                Adb-Run @('shell','screencap','-p',$remote) | Out-Null
                Adb-Run @('pull',$remote,$Path)
            } finally { & $Adb -s $Serial shell rm -f $remote 2>&1 | Out-Null }
        }
        'open' { Adb-Run @('shell','am','start','-n','synchronice.android/.MainActivity') }
        'stop' { Adb-Run @('shell','am','force-stop','synchronice.android') }
        'pair' {
            $pair = Get-Content -LiteralPath $Config -Raw | ConvertFrom-Json
            if ($pair.instance_name -cnotmatch '^synchronice-[0-9a-fA-F]{8}$' -or
                $pair.token -cnotmatch '^[0-9a-fA-F]{32}$') { throw 'config.json sem Par valido' }
            if (-not $Ip) {
                # Same route lookup as the PC QR: UDP connect selects an address without sending data.
                $socket = [Net.Sockets.UdpClient]::new()
                try {
                    $socket.Connect('8.8.8.8', 80)
                    $Ip = $socket.Client.LocalEndPoint.Address.ToString()
                } finally { $socket.Dispose() }
            }
            $address = $null
            if ($Ip -notmatch '^\d{1,3}(\.\d{1,3}){3}$' -or
                -not [Net.IPAddress]::TryParse($Ip, [ref]$address) -or $address.ToString() -ne $Ip -or
                $Ip -eq '0.0.0.0') { throw 'Informe -Ip como IPv4 valido do PC' }
            $json = @{ instance_name=$pair.instance_name; port=47820; token=$pair.token; ip=$Ip } | ConvertTo-Json -Compress
            # Preserve JSON quotes through Windows PowerShell native argv and the adb shell.
            $quoted = "'" + $json.Replace('"', '\"') + "'"
            $result = Adb-Run @('shell','am','start','-W','-n','synchronice.android/.DebugPairActivity','--es','qr',$quoted)
            if ($result -match 'Error:|Exception') { throw 'pair falhou: instale o APK de debug e confira a activity' }
            $result
        }
    }
} catch { [Console]::Error.WriteLine($_.Exception.Message); exit 1 }
