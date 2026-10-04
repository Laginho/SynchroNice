param(
    [string]$RunDir = "$PSScriptRoot/../.scratch/qa-runs/emulator",
    [int]$Port = 9222,
    [string]$Sdk = "$env:LOCALAPPDATA/Android/Sdk"
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
$RunDir = [IO.Path]::GetFullPath($RunDir)
$adb = Join-Path $Sdk 'platform-tools/adb.exe'
$emulator = Join-Path $Sdk 'emulator/emulator.exe'
$statePath = Join-Path $RunDir 'emulator.json'
$debugExe = [IO.Path]::GetFullPath((Join-Path $root 'pc/src-tauri/target/debug/pc.exe'))
$releaseExe = [IO.Path]::GetFullPath((Join-Path $root 'pc/src-tauri/target/release/pc.exe'))
function Save-State { $script:state | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding UTF8 }
. "$PSScriptRoot/process.ps1"
function Adb-Run([string[]]$Arguments) {
    $ErrorActionPreference = 'Continue'
    $result = & $adb @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "infraestrutura adb: $result" }
    return ($result -join "`n")
}
try {
    if ($Port -lt 1 -or $Port -gt 65535) { throw 'Porta CDP invalida' }
    New-Item -ItemType Directory -Force -Path "$RunDir/data/Pasta" | Out-Null
    $state = if (Test-Path -LiteralPath $statePath) { Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json } else { $null }
    if ($state -and $state.active) {
        # A rebuild cannot overwrite the running debug executable.
        Get-Process pc -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $debugExe } | Stop-AndWait
    } else {
        $state = [pscustomobject]@{ active=$true; serial=$null; endpoint=$null; pcPid=$null; realWasRunning=$false }
    }
    Save-State
    $config = Join-Path $RunDir 'data/config.json'
    if (-not (Test-Path -LiteralPath $config)) {
        @{ pasta="$RunDir/data/Pasta"; token=[guid]::NewGuid().ToString('N'); nome_instancia=('synchronice-' + [guid]::NewGuid().ToString('N').Substring(0,8)) } |
            ConvertTo-Json | ForEach-Object { [IO.File]::WriteAllText($config, $_, [Text.UTF8Encoding]::new($false)) }
    }
    $socket = [Net.Sockets.UdpClient]::new()
    try { $socket.Connect('8.8.8.8',80); $ip = $socket.Client.LocalEndPoint.Address.ToString() }
    finally { $socket.Dispose() }
    $settings = Get-Content -LiteralPath $config -Raw | ConvertFrom-Json
    $qrJson = @{ instance_name=$settings.nome_instancia; port=47820; token=$settings.token; ip=$ip } | ConvertTo-Json -Compress
    [IO.File]::WriteAllText("$RunDir/qr.json", $qrJson, [Text.UTF8Encoding]::new($false))
    # Reuse the Android project's ZXing; no QR generator dependency in the kit.
    $jar = Get-ChildItem "$env:USERPROFILE/.gradle/caches/modules-2/files-2.1/com.google.zxing/core" -Filter '*.jar' -Recurse |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $jar) { throw 'ZXing ausente: prepare o projeto Android com o gate antes do primeiro up' }
    $source = @'
import java.nio.file.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.qrcode.QRCodeWriter;
class QrImage {
    public static void main(String[] args) throws Exception {
        // Leave a wide white margin around the QR.
        var matrix = new QRCodeWriter().encode(Files.readString(Path.of(args[0])), BarcodeFormat.QR_CODE, 240, 240);
        var image = new BufferedImage(720, 720, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 720; y++) for (int x = 0; x < 720; x++)
            image.setRGB(x, y, x >= 240 && x < 480 && y >= 240 && y < 480 && matrix.get(x - 240, y - 240) ? 0 : 0xffffff);
        ImageIO.write(image, "png", Path.of(args[1]).toFile());
    }
}
'@
    [IO.File]::WriteAllText("$RunDir/QrImage.java", $source, [Text.UTF8Encoding]::new($false))
    $java = if ($env:JAVA_HOME) { "$env:JAVA_HOME/bin/java.exe" } else { 'java' }
    & $java --class-path $jar.FullName "$RunDir/QrImage.java" "$RunDir/qr.json" "$RunDir/qr.png"
    if ($LASTEXITCODE -ne 0) { throw 'Geracao do QR falhou' }
    $serial = $null
    foreach ($line in ((Adb-Run @('devices')) -split '\r?\n')) {
        if ($line -match '^(emulator-\d+)\s+device') {
            $candidate = $Matches[1]
            if ((Adb-Run @('-s',$candidate,'emu','avd','name')) -match '(?m)^SynchroNice_API37\r?$') { $serial = $candidate; break }
        }
    }
    if (-not $serial) {
        if (Get-NetTCPConnection -LocalPort 5554,5555 -State Listen -ErrorAction SilentlyContinue) { throw 'infraestrutura: portas do emulador 5554/5555 ocupadas' }
        $serial = 'emulator-5554'
        $state.serial = $serial; Save-State
        $startup = New-CimInstance -ClassName Win32_ProcessStartup -ClientOnly -Property @{ ShowWindow=[uint16]0 }
        $launch = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
            CommandLine=('"' + $emulator + '" -avd SynchroNice_API37 -port 5554 -no-window -no-audio -no-snapshot-save -gpu swiftshader_indirect -camera-back "imagefile:' + "$RunDir/qr.png" + '"')
            ProcessStartupInformation=$startup
        }
        if ($launch.ReturnValue -ne 0) { throw "infraestrutura: emulador nao abriu (WMI $($launch.ReturnValue))" }
    }
    $state.serial = $serial
    $state.endpoint = '127.0.0.1:' + ([int]$serial.Split('-')[1] + 1)
    Save-State
    $booted = $false
    for ($attempt = 0; $attempt -lt 180; $attempt++) {
        $ErrorActionPreference = 'Continue'
        $boot = & $adb -s $serial shell getprop sys.boot_completed 2>$null
        $ErrorActionPreference = 'Stop'
        if ($LASTEXITCODE -eq 0 -and "$boot".Trim() -eq '1') { $booted = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $booted) { throw 'infraestrutura: emulador nao terminou o boot em 180 segundos' }
    Push-Location "$root/android"
    try { & .\gradlew.bat :app:assembleDebug | Out-Host; if ($LASTEXITCODE -ne 0) { throw 'Build Android falhou' } }
    finally { Pop-Location }
    $apk = "$root/android/app/build/outputs/apk/debug/app-debug.apk"
    $ErrorActionPreference = 'Continue'
    $install = & $adb -s $serial install -r $apk 2>&1
    $installCode = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($installCode -ne 0 -and "$install" -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') {
        Adb-Run @('-s',$serial,'uninstall','synchronice.android') | Out-Null
        Adb-Run @('-s',$serial,'install',$apk) | Out-Null
    } elseif ($installCode -ne 0) { throw "Instalacao APK falhou: $install" }
    Adb-Run @('-s',$serial,'shell','pm','grant','synchronice.android','android.permission.POST_NOTIFICATIONS') | Out-Null
    Push-Location "$root/pc"
    try { & npx.cmd tauri build --debug --no-bundle | Out-Host; if ($LASTEXITCODE -ne 0) { throw 'Build PC falhou' } }
    finally { Pop-Location }
    $real = @(Get-Process pc -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $releaseExe })
    if ($real.Count) { $state.realWasRunning = $true; Save-State; $real | Stop-AndWait }
    $started = & "$PSScriptRoot/pc.ps1" start -Isolated -RunDir $RunDir -Port $Port | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0 -or -not $started.pid) { throw 'PC isolado nao abriu' }
    $state.pcPid = $started.pid; Save-State
    # The emulator exposes adb over loopback too; the phone wrapper keeps refusing USB.
    Adb-Run @('connect',$state.endpoint) | Out-Null
    & "$PSScriptRoot/phone.ps1" pair -Serial $state.endpoint -Adb $adb -Config $config -Ip $ip | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'Pareamento do emulador falhou' }
    $online = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        $presence = & "$PSScriptRoot/pc.ps1" presence -Isolated -RunDir $RunDir -Port $Port | ConvertFrom-Json
        if ($LASTEXITCODE -eq 0 -and $presence.online) { $online = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $online) { throw 'Emulador pareado, mas nao ficou Na rede em 30 segundos' }
    @{ serial=$serial; endpoint=$state.endpoint; runDir=$RunDir; port=$Port; online=$online; restoredOnDown=$state.realWasRunning } | ConvertTo-Json -Compress
} catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    if (Test-Path -LiteralPath $statePath) { & "$PSScriptRoot/down.ps1" -RunDir $RunDir -Sdk $Sdk | Out-Host }
    exit 1
}
