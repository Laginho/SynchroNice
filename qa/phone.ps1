param(
    [Parameter(Mandatory, Position=0)]
    [ValidateSet('tap','type','key','dump','screenshot','open','stop','pair','push','share','list-files','track','wifi-off','clean')][string]$Action,
    [string]$Serial = $env:SYNCHRONICE_ADB_SERIAL,
    [ValidateSet('id','text','description')][string]$By = 'id',
    [string]$Value,
    [string]$Text,
    [string]$Key,
    [Parameter(Position=1)][string]$Path,
    [string]$RunDir,
    [int]$Seconds = 15,
    [string]$Mime,
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
function Shell-Quote([string]$value) {
    if ($value -match '[\r\n\x00]') { throw 'Caminho com caractere de controle recusado' }
    return "'" + $value.Replace("'", "'\''").Replace('"', '\"') + "'"
}
function Phone-Files {
    $listing = Adb-Run @('shell',"if [ -d /storage/emulated/0/Download/SynchroNice ]; then find /storage/emulated/0/Download/SynchroNice -maxdepth 1 -type f; fi")
    return @($listing -split '\r?\n' | Where-Object { $_ })
}
function Save-Files {
    $script:fileState | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $script:manifest -Encoding UTF8
}
function Phone-Hash([string]$remote) {
    $hash = Adb-Run @('shell', "sha256sum $(Shell-Quote $remote)")
    if ($hash -notmatch '^([a-fA-F0-9]{64})\s') { throw "Nao foi possivel identificar arquivo: $remote" }
    return $Matches[1]
}
function Upload-PrefixHash($entry) {
    if (-not $entry.source -or -not $entry.sourceHash) {
        throw 'Origem do upload ausente ou alterada; preserve o arquivo parcial'
    }
    $size = (Adb-Run @('shell', "wc -c < $(Shell-Quote $entry.path)")).Trim()
    if ($size -notmatch '^\d+$') { throw 'Tamanho do upload parcial invalido' }
    $remaining = [long]$size
    $stream = [IO.File]::OpenRead($entry.source)
    $hash = [Security.Cryptography.SHA256]::Create()
    try {
        if ((Get-FileHash -InputStream $stream).Hash -ine $entry.sourceHash) {
            throw 'Origem do upload alterada; preserve o arquivo parcial'
        }
        $stream.Position = 0
        if ($remaining -gt $stream.Length) { throw 'Upload parcial maior que a origem; preservado' }
        $buffer = [byte[]]::new(65536)
        while ($remaining -gt 0) {
            $count = $stream.Read($buffer, 0, [int][Math]::Min($remaining, $buffer.Length))
            if ($count -eq 0) { throw 'Origem do upload truncada; preserve o arquivo parcial' }
            [void]$hash.TransformBlock($buffer, 0, $count, $buffer, 0)
            $remaining -= $count
        }
        [void]$hash.TransformFinalBlock([byte[]]@(), 0, 0)
        return [BitConverter]::ToString($hash.Hash).Replace('-', '').ToLowerInvariant()
    } finally { $stream.Dispose(); $hash.Dispose() }
}
function Media-Uri([string]$remote) {
    $local = [IO.Path]::GetTempFileName()
    try {
        $where = Shell-Quote ("_data='" + $remote.Replace("'", "''") + "'")
        $query = Adb-Run @('shell',"content query --uri content://media/external/file --projection _id:_data --where $where")
        [IO.File]::WriteAllText($local, $query, [Text.UTF8Encoding]::new($false))
        $uri = & node "$PSScriptRoot/mediastore.mjs" $local $remote
        if ($LASTEXITCODE -ne 0) { throw "nao achado no MediaStore: $remote" }
        return $uri
    } finally { Remove-Item -LiteralPath $local }
}
function Prepare-Optional([string[]]$Arguments) {
    try { Adb-Run $Arguments | Out-Null }
    catch {
        if ($_.Exception.Message -notmatch 'SecurityException') { throw }
        [Console]::Error.WriteLine("aviso: preparacao opcional negada: $($_.Exception.Message)")
    }
}
try {
    if ($Action -in 'push','share','list-files','track','clean' -and -not $RunDir) {
        throw 'Informe -RunDir compartilhado por toda a execucao de QA'
    }
    if ($Action -eq 'wifi-off') {
        if ($Path) { $Seconds = [int]$Path }
        if ($Seconds -lt 1 -or $Seconds -gt 600) { throw 'Wi-Fi fora: use de 1 a 600 segundos' }
    }
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
        Prepare-Optional @('shell','settings','put','global',$scale,'0')
    }
    Prepare-Optional @('shell','input','keyevent','KEYCODE_WAKEUP')
    Prepare-Optional @('shell','wm','dismiss-keyguard')
    $policy = Adb-Run @('shell','dumpsys','window','policy')
    if ($policy -match '(?:mShowingLockscreen|isStatusBarKeyguard|mKeyguardShowing|showing)=true') {
        throw 'infraestrutura: aparelho bloqueado; desbloqueie manualmente (o kit nao recebe PIN)'
    }
    if ($Action -in 'push','share','list-files','track','clean') {
        New-Item -ItemType Directory -Force -Path $RunDir | Out-Null
        $manifest = Join-Path $RunDir 'phone-files.json'
        $device = (Adb-Run @('shell','getprop','ro.serialno')).Trim()
        if (-not $device) { throw 'infraestrutura: aparelho sem identificacao' }
        if (Test-Path -LiteralPath $manifest) {
            $fileState = Get-Content -LiteralPath $manifest -Raw | ConvertFrom-Json
            if ($fileState.device -cne $device) { throw 'Manifesto pertence a outro aparelho' }
        } else {
            $fileState = [pscustomobject]@{ device=$device; baseline=@(Phone-Files); tracked=@(); cleanup=@() }
            Save-Files
        }
    }
    switch ($Action) {
        'list-files' { ConvertTo-Json -InputObject @(Phone-Files) }
        'track' {
            $prefix = '/storage/emulated/0/Download/SynchroNice/'
            if (-not $Path.StartsWith($prefix) -or $Path.Substring($prefix.Length) -match '[/\\\r\n]' -or
                $Path.Substring($prefix.Length) -in '', '.', '..' -or @($fileState.baseline) -ccontains $Path) {
                throw 'So registre arquivos novos desta execucao, diretamente em Download/SynchroNice'
            }
            if (@($fileState.tracked.path) -cnotcontains $Path) {
                $fileState.tracked = @($fileState.tracked) + @{ path=$Path; hash=(Phone-Hash $Path) }
                Save-Files
            }
            ConvertTo-Json -InputObject @($fileState.tracked) -Depth 10
        }
        { $_ -in 'push','share' } {
            $file = Get-Item -LiteralPath $Path
            if ($file.PSIsContainer) { throw 'Informe um arquivo, nao um diretorio' }
            $remote = '/storage/emulated/0/Pictures/SynchroNiceQA/' + $file.Name
            $quoted = Shell-Quote $remote
            $sourceHash = (Get-FileHash -LiteralPath $file.FullName).Hash
            Adb-Run @('shell','mkdir','-p','/storage/emulated/0/Pictures/SynchroNiceQA') | Out-Null
            # Reserve without overwriting an existing photo; track before push so partial writes can be cleaned.
            Adb-Run @('shell',"set -C; : > $quoted") | Out-Null
            $fileState.tracked = @($fileState.tracked) + @{
                path=$remote; hash=$null; source=$file.FullName; sourceHash=$sourceHash
            }
            Save-Files
            Adb-Run @('push',$file.FullName,$remote) | Out-Null
            $entry = $fileState.tracked | Select-Object -Last 1
            $entry.hash = Phone-Hash $remote
            Save-Files
            if ($Action -eq 'push') { @{ path=$remote } | ConvertTo-Json -Compress; break }
            Adb-Run @('shell',"content call --uri content://media --method scan_file --arg $quoted") | Out-Null
            $uri = $null
            for ($attempt = 0; $attempt -lt 10; $attempt++) {
                try { $uri = Media-Uri $remote; break } catch { if ($attempt -eq 9) { throw }; Start-Sleep -Milliseconds 500 }
            }
            if (-not $Mime) {
                $Mime = @{ '.jpg'='image/jpeg'; '.jpeg'='image/jpeg'; '.png'='image/png'; '.webp'='image/webp'; '.gif'='image/gif'; '.pdf'='application/pdf'; '.mp4'='video/mp4'; '.txt'='text/plain' }[$file.Extension.ToLowerInvariant()]
                if (-not $Mime) { $Mime = 'application/octet-stream' }
            }
            if ($Mime -notmatch '^[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+$') { throw 'MIME invalido' }
            $result = Adb-Run @('shell','am','start','-W','-n','synchronice.android/.ShareActivity',
                '-a','android.intent.action.SEND','-t',$Mime,'-d',$uri,
                '--eu','android.intent.extra.STREAM',$uri,'--grant-read-uri-permission')
            if ($result -match 'Error:|Exception|Permission Denial') { throw "Compartilhar recusado pelo am: $result" }
            @{ path=$remote; uri=$uri; am=$result } | ConvertTo-Json -Compress
        }
        'wifi-off' {
            $remote = '/data/local/tmp/synchronice-qa-wifi-' + [guid]::NewGuid().ToString('N') + '.sh'
            $logPath = "$remote.log"
            $local = [IO.Path]::GetTempFileName()
            try {
                # The trap restores Wi-Fi on exit; nohup keeps it alive after wireless adb drops.
                $script = @'
wait_wifi() {
    for attempt in 1 2 3 4 5 6 7 8 9 10; do
        state=$(dumpsys wifi) || return 1
        if printf '%s\n' "$state" | grep -qx "Wi-Fi is $1"; then return 0; fi
        sleep 1
    done
    echo "Error: Wi-Fi did not become $1"
    return 1
}
restore() {
    if svc wifi enable && wait_wifi enabled; then echo QA_WIFI_RESTORED; fi
    rm -f __REMOTE__
}
trap restore EXIT
svc wifi disable && wait_wifi disabled || exit 1
echo QA_WIFI_DISABLED
sleep __SECONDS__
'@
                $script = $script.Replace('__REMOTE__', $remote).Replace('__SECONDS__', "$Seconds").Replace("`r`n", "`n") + "`n"
                [IO.File]::WriteAllText($local, $script, [Text.UTF8Encoding]::new($false))
                Adb-Run @('push',$local,$remote) | Out-Null
                $launch = Adb-Run -Arguments @('shell',"nohup sh $remote </dev/null >$logPath 2>&1 &") -AllowFailure
                if ($launch -match 'Error|Exception|Permission Denial') { throw "Wi-Fi recusado: $launch" }
            } finally { Remove-Item -LiteralPath $local }
            Start-Sleep -Seconds ($Seconds + 2)
            $ready = $false
            for ($attempt = 0; $attempt -lt 30; $attempt++) {
                $ErrorActionPreference = 'Continue'
                & $Adb connect $Serial 2>&1 | Out-Null
                $status = & $Adb -s $Serial get-state 2>&1
                $ErrorActionPreference = 'Stop'
                if ($LASTEXITCODE -eq 0 -and $status -contains 'device') { $ready = $true; break }
                $services = & $Adb mdns services
                foreach ($service in $services) {
                    if ($service -match '^\S+\s+_adb-tls-connect\._tcp\.?\s+(\S+:\d+)') {
                        $endpoint = $Matches[1]
                        if ($Serial -match '^([^:]+):' -and $endpoint.StartsWith($Serial.Split(':')[0] + ':')) {
                            $Serial = $endpoint; break
                        }
                    }
                }
                Start-Sleep -Seconds 1
            }
            if (-not $ready) { throw 'infraestrutura: Wi-Fi nao reconectou o adb; confira o aparelho' }
            for ($attempt = 0; $attempt -lt 21; $attempt++) {
                $log = Adb-Run @('shell','cat',$logPath)
                if ($log -match 'Error|Exception|Permission Denial' -or
                    ($log -match '(?m)^QA_WIFI_DISABLED\r?$' -and $log -match '(?m)^QA_WIFI_RESTORED\r?$')) { break }
                if ($attempt -lt 20) { Start-Sleep -Seconds 1 }
            }
            if ($log -match 'Error|Exception|Permission Denial' -or
                $log -notmatch '(?m)^QA_WIFI_DISABLED\r?$' -or $log -notmatch '(?m)^QA_WIFI_RESTORED\r?$') {
                throw "Wi-Fi nao confirmou desligar e restaurar: $log"
            }
            Adb-Run @('shell','rm','-f',$logPath) | Out-Null
            @{ seconds=$Seconds; reconnected=$true; serial=$Serial; log=$log } | ConvertTo-Json -Compress
        }
        'clean' {
            foreach ($entry in @($fileState.tracked)) {
                $remote = $entry.path
                if ($remote -notmatch '^/storage/emulated/0/(Pictures/SynchroNiceQA|Download/SynchroNice)/[^/\\\r\n]+$' -or
                    [IO.Path]::GetFileName($remote) -in '.', '..' -or @($fileState.baseline) -ccontains $remote) {
                    throw 'Manifesto inseguro: arquivo fora da lista de QA'
                }
                $quoted = Shell-Quote $remote
                $exists = Adb-Run @('shell',"if [ -f $quoted ]; then echo exists; fi")
                $destination = 'ja ausente'
                if ($exists.Trim() -eq 'exists') {
                    $expectedHash = $entry.hash
                    if (-not $expectedHash) { $expectedHash = Upload-PrefixHash $entry }
                    if ((Phone-Hash $remote) -ine $expectedHash) { throw "Arquivo alterado; preservado: $remote" }
                    $destination = 'rm (MediaStore indisponivel)'
                    try {
                        $uri = Media-Uri $remote
                        $result = Adb-Run @('shell','content','update','--uri',$uri,'--bind','is_trashed:i:1')
                        $trashed = Adb-Run @('shell','content','query','--uri',$uri,'--projection','is_trashed','--extra','android:query-arg-match-trashed:i:1')
                        if ($result -match 'Exception|Error' -or $trashed -notmatch 'is_trashed=1\b') { throw 'MediaStore nao confirmou lixeira' }
                        $destination = 'Lixeira do MediaStore'
                    } catch { Adb-Run @('shell',"rm -f -- $quoted") | Out-Null }
                }
                $fileState.cleanup = @($fileState.cleanup) + @{ path=$remote; destination=$destination }
                $fileState.tracked = @($fileState.tracked | Where-Object { $_.path -cne $remote })
                Save-Files
            }
            ConvertTo-Json -InputObject @($fileState.cleanup) -Depth 10
        }
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
            if ($pair.nome_instancia -isnot [string] -or $pair.token -isnot [string] -or
                $pair.nome_instancia -cnotmatch '\Asynchronice-[0-9a-fA-F]{8}\z' -or
                $pair.token -cnotmatch '\A[0-9a-fA-F]{32}\z') { throw 'config.json sem Par valido' }
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
            $json = @{ instance_name=$pair.nome_instancia; port=47820; token=$pair.token; ip=$Ip } | ConvertTo-Json -Compress
            # Preserve JSON quotes through Windows PowerShell native argv and the adb shell.
            $quoted = "'" + $json.Replace('"', '\"') + "'"
            $result = Adb-Run @('shell','am','start','-W','-n','synchronice.android/.DebugPairActivity','--es','qr',$quoted)
            if ($result -match 'Error:|Exception') { throw 'pair falhou: instale o APK de debug e confira a activity' }
            $result
        }
    }
} catch { [Console]::Error.WriteLine($_.Exception.Message); exit 1 }
