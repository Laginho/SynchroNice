# Gate: typecheck, lint e testes de tudo que existir em pc/ e android/.
# Sai com codigo diferente de zero se qualquer passo falhar.

$ErrorActionPreference = "Stop"
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

if (Test-Path "pc/src-tauri") {
    Run-Step "cargo fmt --check (pc)" "pc/src-tauri" { cargo fmt --check }
    Run-Step "cargo clippy (pc)" "pc/src-tauri" { cargo clippy --all-targets -- -D warnings }
    Run-Step "cargo test (pc)" "pc/src-tauri" { cargo test }
}

if (Test-Path "pc/ui") {
    Run-Step "tsc --noEmit (pc/ui)" "pc" { npx tsc --noEmit }
}

if (Test-Path "android") {
    Run-Step "gradle check (android)" "android" { ./gradlew check }
}

if ($failed) {
    Write-Host "gate: FALHOU" -ForegroundColor Red
    exit 1
}

Write-Host "gate: passou" -ForegroundColor Green
exit 0
