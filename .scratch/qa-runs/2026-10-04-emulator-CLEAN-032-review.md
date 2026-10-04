# QA — 2026-10-04 — Emulador — CLEAN-032

Infraestrutura: Windows, AVD `SynchroNice_API37`, SDK em `D:/AppData/Local/Android/Sdk`, PC debug isolado, CDP 9222. RunDir: `2026-10-04-emulator-CLEAN-032-review`. Código e testes revisados idênticos a `origin/clean-032`; rebase sobre `sweatshop/2026-10-04-1210` sem alteração funcional.

| Critério | Veredito | Evidência |
| --- | --- | --- |
| CLEAN-032.1 | ✅ | Teste chama `qa/down.ps1` com listener próprio e adb falso; saída espontânea comprovada pelo marcador do stub. Passou em 10,512 s no gate final. |
| CLEAN-032.2 | ✅ | Stub próprio que permanece vivo é encerrado após os 30 s completos; teste passou em 32,665 s, exit 0 e processo morto. |
| CLEAN-032.3 | ✅ | Os 20 cenários anteriores de `down.test.mjs` passaram, incluindo ausência de listener, serial ausente, AVD diferente, falhas do adb e restauração do PC. |
| CLEAN-032.4 | ✅ | Testes usam portas livres dinâmicas; gate executado com qemu real PID 3448 escutando em 5554/5555. `after-gate.json` confirmou o mesmo processo e listeners preservados após o gate. |
| CLEAN-032.5 | ✅ | Sonda chamou o `down` real com qemu PID 10516 vivo, capturado pelo dono do listener. No retorno: exit 0, `HasExited=true`, zero qemu vivo, zero listeners em 5554/5555 e `active=false`. |

## Gate e preparação

`powershell -ExecutionPolicy Bypass -File .\gate.ps1` terminou com exit 0 e `gate: passou` (`gate-final.log`). Rust: 83 testes; UI: 61; QA: 98, todos passaram. Fmt, clippy e typecheck passaram. Android: `BUILD SUCCESSFUL`; testes Gradle `UP-TO-DATE`, sem alterações Kotlin. `CARGO_BUILD_JOBS=1` foi aplicado somente ao ambiente da chamada.

A retomada enfrentou pressão de memória e um PC isolado aberto que impedia sobrescrever `pc.exe`. Esse PC foi encerrado pelo helper do kit antes do gate final, preservando o AVD. A regravação de finais de linha de `Cargo.toml` pelo Tauri foi revertida após confirmar diff funcional vazio.

`up-retry.log`, da mesma revisão e RunDir, registra `qa/up.ps1` real bem-sucedido com `online=true`, serial `emulator-5554` e endpoint `127.0.0.1:5555`. O qemu desse ciclo permaneceu vivo durante o gate final.

Na retomada, `qa/up.ps1` recompilou e instalou os artefatos desta branch (`up-final.log`), mas abortou por ausência de presença em 30 s; seu cleanup também atingiu o prazo do adb. A retentativa de `down` deixou o ambiente limpo, porém o qemu já havia saído antes dela: `post-down-retry.json` não é usado como prova do critério 5.

Para medir a parada de um qemu vivo, a preparação foi retomada com esses mesmos APK/PC, os argumentos de lançamento do AVD usados por `up.ps1`, `pc.ps1 start -Isolated` e `phone.ps1 pair`. Boot confirmado (`sys.boot_completed=1`), PC isolado PID 9572. O pareamento foi recusado pela tela bloqueada: `recovered-up.json` registra honestamente `pairExit=1` e `online=false`. Esta falha de infraestrutura limita a repetição do QA de pareamento/presença; a parada física foi medida e o `up` real anterior bem-sucedido está documentado acima.

## Parada física

`shutdown-probe.ps1` guardou o handle do dono do listener e do launcher antes de chamar `qa/down.ps1` com o mesmo RunDir e SDK. `post-down.json` registra:

- retorno em `2026-10-04T18:42:53.5463297Z`, duração 6.935 ms;
- `downExit=0`, `ownerPid=10516`, `ownerName=qemu-system-x86_64-headless`, `ownerHasExitedAtReturn=true`;
- qemu 10516 e launcher 21840 capturados antes da chamada: ambos `HasExited=true` no retorno;
- nenhum qemu remanescente, nenhum listener 5554/5555, `active=false`;
- `down-1.log`: `{"restored":false,"stopped":true}`.

A sonda adicional saiu 2 porque também exigia ausência de qualquer processo chamado `emulator`: observou o PID 18868, que já não existia na inspeção imediata seguinte, sem intervenção. Essa exigência adicional não pertence ao critério 5; o qemu e os listeners exigidos já estavam ausentes no retorno do `down`.

## Limites e limpeza

O caminho de processo impossível de matar foi conferido por leitura, como permite o ticket: erro com PID ocorre antes de limpar `active`. A recuperação entre chamadas depois de uma falha de parada continua registrada para triagem em CLEAN-033.

PC isolado encerrado, AVD encerrado, nenhum qemu/listener 5554/5555 na conferência final. PC real não estava em execução (`realWasRunning=false`), portanto não havia restauração a realizar. Nenhum arquivo de usuário foi criado ou apagado; artefatos e logs locais permanecem no subdiretório homônimo, ignorado pelo git. Não houve QA adicional de clipboard, arquivos ou telas dos apps, pois o diff trata apenas do encerramento do emulador.
