# Gate: typecheck, lint e testes de tudo que existir em pc/ e android/.
# Sai com codigo diferente de zero se qualquer passo falhar.
# Ancorado em $PSScriptRoot: rodar de qualquer diretorio da o mesmo resultado.

# -TestsOnly: so os testes, sem fmt/lint/typecheck. E o que o stage 2 precisa
# para a prova red-before-green -- uma rodada que falha pelo teste, nao pelo lint.
param([switch]$TestsOnly)

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
    if (-not $TestsOnly) {
        Run-Step "cargo fmt --check (pc)" "$root/pc/src-tauri" { cargo fmt --check }
        Run-Step "cargo clippy (pc)" "$root/pc/src-tauri" { cargo clippy --all-targets -- -D warnings }
    }
    Run-Step "cargo test (pc)" "$root/pc/src-tauri" { cargo test }
}

if ((Test-Path "$root/pc/ui") -and (-not $TestsOnly)) {
    Run-Step "tsc --noEmit (pc/ui)" "$root/pc" { npx tsc --noEmit }
}

if (Test-Path "$root/pc/ui/copiaveis.test.ts") {
    Run-Step "node --test (pc/ui)" "$root" { node --test pc/ui/copiaveis.test.ts }
}

if (Test-Path "$root/qa/dump.test.mjs") {
    Run-Step "node --test (qa)" "$root" { node --test qa/dump.test.mjs }
}

# Ancorado no settings, nao na pasta: o wrapper do Gradle entra antes do modulo,
# e uma pasta android/ so com wrapper nao tem o que testar -- gradle falharia e
# levaria o gate junto.
if (Test-Path "$root/android/settings.gradle.kts") {
    if ($TestsOnly) {
        Run-Step "gradle test (android)" "$root/android" { .\gradlew.bat test }
    } else {
        Run-Step "gradle check (android)" "$root/android" { .\gradlew.bat check }
    }
}

if ($failed) {
    Write-Host "gate: FALHOU" -ForegroundColor Red
    exit 1
}

Write-Host "gate: passou" -ForegroundColor Green
exit 0
