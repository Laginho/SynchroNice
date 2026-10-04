# QA — 2026-10-03 — Emulador — CLEAN-020

Infraestrutura: Windows, AVD `SynchroNice_API37`, SDK em
`D:/AppData/Local/Android/Sdk`, APK e PC debug da branch `clean-020`
(`0262548`), PC e Par isolados, CDP 9222. Execução local:
`.scratch/qa-runs/2026-10-03-emulator-CLEAN-020-review/`.
O SDK foi passado por `-Sdk`, conforme `android/local.properties`.

O PC real estava fechado e o executável release não estava presente nesta
árvore. A restauração com `realWasRunning: true` foi verificada pela costura
prescrita no ticket: wrapper real, adb falso e executáveis Windows temporários
chamados `pc.exe`, lançados por WMI. O QA com o aplicativo real verificou o
caminho com PC inicialmente fechado. Não houve falha de infraestrutura no kit;
o build do PC levou 7m39s em um ambiente com pouca memória livre.

| Critério | Veredito | Evidência |
| --- | --- | --- |
| 1 — parâmetros, padrões e chamador | ✅ | Diff mantém caminhos anteriores e `up.ps1` inalterado. `up/down` reais usaram apenas `-Sdk` e `-RunDir`, com os executáveis padrão. Os testes passaram `-Adb`, `-DebugExe` e `-ReleaseExe`. |
| 2 — restauração nas quatro falhas | ✅ | No gate, `changed`, `devices-error`, `kill-error` e `timeout` encerraram o debug e lançaram exatamente um release por WMI. O registro do processo continha valores vazios para `SYNCHRONICE_DATA_DIR`, `WEBVIEW2_USER_DATA_FOLDER` e `WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS`. |
| 3 — erro e estado preservados | ✅ | Os quatro casos saíram 1 com a mensagem correspondente; todos mantiveram `active: true`. |
| 4 — repetição sem duplicação | ✅ | Repetição do erro de AVD manteve os mesmos PID e registro do release no teste. Após o `down` real, repetir saiu 0 com `{"stopped":true,"restored":false}` e nenhum processo PC. |
| 5 — caminho feliz | ✅ | `up` saiu 0 com `serial: emulator-5554`, `online: true`, `restoredOnDown: false`; debug PID 8888. `down` saiu 0 com `{"restored":false,"stopped":true}`. PID encerrado, emulador ausente e estado `active: false`. A consulta final ao adb não listou dispositivos. O teste também cobriu o caminho feliz com restauração positiva. |

Comandos principais, executados em primeiro plano:

```powershell
powershell -ExecutionPolicy Bypass -File .\gate.ps1
powershell -ExecutionPolicy Bypass -File .\qa\up.ps1 -Sdk 'D:/AppData/Local/Android/Sdk' -RunDir '.scratch/qa-runs/2026-10-03-emulator-CLEAN-020-review'
powershell -ExecutionPolicy Bypass -File .\qa\down.ps1 -Sdk 'D:/AppData/Local/Android/Sdk' -RunDir '.scratch/qa-runs/2026-10-03-emulator-CLEAN-020-review'
# Repetido com os mesmos argumentos.
```

Gate: saída 0, `gate: passou`; Rust 83, interface 61, QA 44, incluindo
10/10 testes novos (nove cenários e o pai). Fmt, clippy e TypeScript passaram.
Android: `BUILD SUCCESSFUL`, 41 tarefas, 1 executada e 40 up-to-date; testes
reutilizados pelo Gradle. Logs locais: `up.log`, `down.log`, `down-repeat.log`
na execução e `.scratch/run-log/CLEAN-020-stage3-gate.log`.

Red conferido no transcript do stage 2, linha 43 (`item_21`): com a costura
exposta e antes da mudança de ordem, 10 testes, 5 passaram e 5 falharam;
as quatro falhas do emulador deixavam o debug vivo. A quinta falha é o pai.

Limpeza: nenhum arquivo de usuário criado ou apagado. Stubs temporários
encerrados e removidos pelo harness. Emulador e debug reais encerrados por
`down`; estado e evidências preservados localmente. A alteração automática de
finais de linha em `pc/src-tauri/Cargo.toml` foi desfeita. Sem débito humano novo.
