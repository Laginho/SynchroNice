# Gate: typecheck, lint e testes de tudo que existir em pc/ e android/.
# Sai com codigo diferente de zero se qualquer passo falhar.
# Ancorado em $PSScriptRoot: rodar de qualquer diretorio da o mesmo resultado.

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$failed = $false

function Run-Step {
    param([string]$Name, [string]$WorkingDir, [scriptblock]$Command)

    Write-Host "==> $Name" -ForegroundColor Cyan
    Push-Location $WorkingDir
    try {
        & $Command
        if ($LASTEXITCODE -ne 0) {
            Write-Host "FALHOU: $Name" -ForegroundColor Red
            $script:failed = $true
        }
    } finally {
        Pop-Location
    }
}

if (Test-Path "$root/pc/src-tauri") {
    Run-Step "cargo fmt --check (pc)" "$root/pc/src-tauri" { cargo fmt --check }
    Run-Step "cargo clippy (pc)" "$root/pc/src-tauri" { cargo clippy --all-targets -- -D warnings }
    Run-Step "cargo test (pc)" "$root/pc/src-tauri" { cargo test }
}

if (Test-Path "$root/pc/ui") {
    Run-Step "tsc --noEmit (pc/ui)" "$root/pc" { npx tsc --noEmit }
}

if (Test-Path "$root/android") {
    Run-Step "gradle check (android)" "$root/android" { .\gradlew.bat check }
}

if ($failed) {
    Write-Host "gate: FALHOU" -ForegroundColor Red
    exit 1
}

Write-Host "gate: passou" -ForegroundColor Green
exit 0
