# CLEAN-032: `down` só termina depois de o processo do emulador sair
Stage: done
Status: ready-for-agent
Blocked by: none
Review: agent
Difficulty: normal
Origin: QA da revisão CLEAN-026, 2026-10-04; observação registrada no SYN-025 (done)

- Primary files:
  - qa/down.ps1 (derrubada do emulador)
  - qa/down.test.mjs

#### What to build

O `down` dá o emulador por encerrado quando o serial some do `adb devices`,
mas o `qemu-system-x86_64` pode viver mais uns segundos. O critério 5 do
SYN-025 pede `down` sem qemu rodando, e o `up` logo depois do `down` é o uso
do loop.

Depois do check do nome do AVD e antes do `emu kill`, o `down` acha o dono do
listener na porta do serial (`emulator-5554` → 5554) com
`Get-NetTCPConnection -LocalPort <n> -State Listen`, a mesma chamada de
`up.ps1`, pega o processo e guarda o `Handle`. Sem check de nome de processo:
o check do AVD pelo console já prova que é nosso. Depois que o serial some do
`adb devices`, espera até 30 s o processo sair; vivo ainda, aplica o
`Stop-AndWait` existente. Falha nessa parada é erro de infraestrutura com o
PID, e o `state.active` fica true para o `down` poder ser repetido. Sem
listener na porta, o comportamento de hoje.

#### Acceptance criteria

1. O `down` só retorna depois que o dono do listener sai: com um stub que sai
   sozinho alguns segundos depois do `emu kill`, exit 0 e o stub saiu por
   conta própria, sem ser forçado.
2. Um processo ainda vivo 30 s depois de o serial sumir é parado à força:
   exit 0 e o stub morto.
3. Sem listener na porta do serial, os cenários atuais continuam como estão.
4. Os testes nunca usam a 5554, nem porta com outro listener além do stub do
   próprio teste: cada cenário usa `emulator-<porta do stub ou porta livre>`
   (hoje todos usam `emulator-5554`). O gate pode rodar com o emulador do kit
   de pé, e o `down` novo pegaria o qemu real.
5. Stage 3, pelo kit, com `up` e depois `down` reais: quando o `down`
   retorna, não há `qemu-system-x86_64` vivo nem listener em 5554/5555.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

## Tests stage 2 writes (own commit, red)

Em `qa/down.test.mjs`, com o adb falso atual:
- um stub que escuta numa porta livre (serial `emulator-<porta>`) e sai
  sozinho alguns segundos depois do `emu kill` (critério 1, vermelho hoje: o
  `down` retorna com o stub vivo);
- um stub que nunca sai (critério 2, vermelho hoje; leva ~30 s, aceito, sem
  parâmetro de prazo);
- os cenários atuais migram para portas que não sejam a 5554 (critério 4).

O caminho de processo impossível de matar não tem teste físico: sai erro com
o PID, conferido por leitura como no CLEAN-026.

## Comments

- Stage 3 do CLEAN-026 (2026-10-04): dois `down` seguidos saíram 0 com
  `stopped=true`; uma sonda de até 10 s depois do segundo ainda contou um
  processo `qemu-system-x86_64` ou `emulator` vivo (`survivingEmulator: 1`).
  A leitura seguinte achou zero processos, zero dispositivos adb e nenhum
  listener em 5554/5555. Nada falhou no run por isso.
- `qa/down.ps1:89–96` roda `adb emu kill` e espera até 30 s o serial sumir de
  `adb devices`; o processo não entra na conta. É preexistente (SYN-025) e
  ficou fora do critério 1 do CLEAN-026, que enumerava as paradas por
  `Stop-Process`. O critério 5 do SYN-025 pede `down` sem `qemu` rodando.
- Risco a avaliar, não reproduzido: `qa/up.ps1:88` decide se o AVD está de pé
  por `adb devices` e, se não, lança outro `-avd SynchroNice_API37 -port 5554`.
  Um `up` logo depois de um `down` pode encontrar o processo anterior vivo.
- Para triagem: esperar o processo do emulador sair, com prazo e erro de
  infraestrutura com PID, como o `Stop-AndWait` faz nas demais paradas (o
  CLEAN-030 avalia esse helper compartilhado); ou wontfix, se `up` logo após
  `down` não for caso de uso.
- Evidências: [QA CLEAN-026](../../qa-runs/2026-10-04-emulator-CLEAN-026-review.md)
  e os locais `2026-10-04-clean-026-review/emulator-final/summary.json` e
  `post-shutdown.json`.
- Proxy decided: aceito (normal, Review: agent, sem Blocked by); antes do emu kill o down guarda o dono do listener da porta do serial, espera até 30 s ele sair depois que o serial some do adb e senão aplica Stop-AndWait, e os testes nunca usam a 5554 — o critério 5 do SYN-025 pede down sem qemu e o up logo após o down é o uso do loop; reusa o Stop-AndWait existente, então não depende do CLEAN-030.

- Stage 2 (2026-10-04), leitura antes do vermelho: `down` é chamado pelo cleanup de falha de `qa/up.ps1` e pelos comandos de encerramento do kit. O novo caminho só alcança serial presente com AVD confirmado; serial ausente, AVD diferente, falhas do adb, estado ausente/inativo e restauração do PC continuam cobertos pelos 20 cenários existentes. Sem listener deve continuar sem espera de processo. O processo pode sair entre a captura e a espera; o handle mantém a identidade contra reutilização de PID.
- Primeiro vermelho: `node --test --test-name-pattern='listener to exit naturally' qa/down.test.mjs` — 1 teste, 0 passou, 1 falhou porque `down` retornou com o listener próprio ainda vivo. Os cenários antigos agora recebem serial de porta livre, sem usar 5554.
- Primeiro verde: o mesmo teste, fora do sandbox (a consulta CIM de listeners é negada nele), passou: 1 teste, 1 passou, 0 falhou; o stub saiu espontaneamente após o `emu kill`.
- Segundo vermelho: `node --test --test-name-pattern='forces the emulator listener' qa/down.test.mjs` — 1 teste, 0 passou, 1 falhou com timeout de infraestrutura após os 30 s, sem parada forçada. A correção aplica `Stop-AndWait` ao processo capturado; se falhar, seu erro inclui o PID e ocorre antes de limpar `state.active`.
- Verde e gate completo (2026-10-04): `powershell -ExecutionPolicy Bypass -File .\gate.ps1` terminou com exit 0 e `gate: passou`. Rust: 83 passaram, 0 falharam; UI: 61 passaram, 0 falharam; QA: 98 passaram, 0 falharam (23 em `down.test.mjs`, incluindo os dois novos testes e os 20 cenários anteriores). Saída espontânea em 10,6 s; parada forçada em 32,0 s, com asserção dos 30 s completos desde `emu kill`. Fmt, clippy e typecheck passaram; `gradle check` terminou `BUILD SUCCESSFUL`, com os testes Android já atualizados (`UP-TO-DATE`).
- Handoff: critério 5 (`up/down` reais pelo kit e ausência de qemu/listeners 5554/5555) permanece para o stage 3. O caminho de processo impossível de parar foi conferido por leitura, conforme previsto no contrato. Diff revisado e `git diff --check` sem erros; commits de código não tocam os testes.
- Stage 3 (2026-10-04): corrigida a ordem dos comentários do stage 2 para acrescentá-los ao fim do histórico, conforme `docs/agents/issue-tracker.md`; nenhuma alteração de código ou dos critérios.
- 2026-10-04 14:50 (foreman): revisão estourou os 40 min no gate, com dois runs Codex de outros repos (PhysiSyst, ClassyCall) dividindo a CPU — causa ambiental. Volta a to-review; a revisão seguinte retoma da branch clean-032 (cópia em recovery/20261004-1442/clean-032), com limite de 60 min.
- Stage 3 retomado (2026-10-04): revisoes independentes Standards e Spec sem achados acionaveis nos criterios 1-4. Examinados o cleanup de falha de `qa/up.ps1`, o helper `Stop-AndWait`, os usos em `qa/pc.ps1`, os registros de restauracao do PC, serial ausente, AVD diferente, erros do adb, listener ausente, saida entre captura e espera e falha da parada. Internos dos apps, clipboard e limpeza de arquivos nao foram reavaliados, pois nao mudam neste diff. O criterio 5 aguarda o ciclo real do kit.
- Rebase da retomada: conflitos limitados ao `Stage` e ao historico deste ticket, conciliados sem mudar o contrato. Em `957f32d`, codigo e testes sao identicos a `origin/clean-032`; test-first preservado (`a29fda7` antes de `2326107`, `4e4b516` antes de `8f2f93c`), sem testes nos commits de codigo. A decisao `Proxy decided: aceito` permanece atendida; a recuperacao entre chamadas apos falha de parada continua como triagem no CLEAN-033, sem ampliar os criterios deste ticket.
- Infraestrutura da retomada: executor do sandbox indisponivel (Windows 1909), contornado pelo executor externo. O primeiro gate foi interrompido sob pressao de memoria; a repeticao com `CARGO_BUILD_JOBS=1` revelou `pc.exe` isolado ainda aberto, impedindo a recompilacao (`Access is denied`, Rust). Esse PC foi encerrado pelo helper, mantendo o qemu do kit em 5554/5555. Os dois testes novos passaram nesta repeticao (10,8 s e 32,6 s); o gate completo e o QA real ainda precisam terminar antes do fechamento.

#### Resolution (2026-10-04)

Verdict: Approve

**Standards:** revisão independente sem achados acionáveis ou smells. Mudança de código restrita a `qa/down.ps1` e `qa/down.test.mjs`, com captura de identidade pelo handle, espera limitada e reutilização de `Stop-AndWait`. Documentação e triagem mantêm o histórico em ordem; nenhum artefato binário, segredo ou mudança funcional alheia no diff final.

**Spec:** revisão independente sem critérios descumpridos. Examinados os chamadores de encerramento do kit e cleanup de falha de `qa/up.ps1`, o helper `Stop-AndWait`, os usos e registros de restauração em `qa/pc.ps1`, estado ausente/inativo, serial ausente, AVD diferente, erros do adb, listener ausente, saída entre captura e espera e erro da parada. Internos dos apps, clipboard e limpeza de arquivos não foram reavaliados: não mudam neste diff. A parada impossível de executar foi conferida por leitura conforme o contrato; o erro inclui PID antes de limpar `state.active`.

| Critério | Veredito | Evidência |
| --- | --- | --- |
| 1 | ✅ | Teste de produção com listener próprio comprova saída espontânea, exit 0 e marcador de encerramento natural; passou em 10,512 s no gate final. |
| 2 | ✅ | Teste de produção comprova os 30 s completos antes da parada forçada, exit 0 e stub morto; passou em 32,665 s. |
| 3 | ✅ | Os 20 cenários anteriores passaram; ausência de listener conserva o fluxo existente. |
| 4 | ✅ | Portas dinâmicas próprias; gate final com qemu PID 3448 e listeners 5554/5555 preservados, comprovados em `after-gate.json`. |
| 5 | ✅ | `up` real anterior da mesma revisão ficou online. Sonda posterior com qemu real PID 10516 vivo: `down` retornou exit 0 em 6.935 ms, handle com `HasExited=true`, zero qemu vivo, zero listeners 5554/5555 e `active=false`. [Relatório de QA](../../qa-runs/2026-10-04-emulator-CLEAN-032-review.md). |

Test-first preservado no rebase: `a29fda7` (teste natural vermelho, retorno com stub vivo) precede `2326107` (verde); `4e4b516` (teste de parada forçada vermelho, timeout após 30 s) precede `8f2f93c` (verde). Os commits de código não alteram testes; ambos chamam o `down` de produção. Evidência dos vermelhos no handoff do stage 2 e no run-log correspondente.

Gate final: `powershell -ExecutionPolicy Bypass -File .\gate.ps1`, exit 0, `gate: passou`. Rust 83, UI 61 e QA 98 testes passaram, incluindo 23 em `down.test.mjs`; fmt, clippy e typecheck passaram. Android `BUILD SUCCESSFUL`, testes Gradle `UP-TO-DATE`, sem alterações Kotlin. Código/testes iguais aos validados antes do merge; `git diff --check` passou.

A decisão existente `Proxy decided: aceito` foi atendida: captura antes do `emu kill`, espera de 30 s, fallback no helper e testes fora da 5554. A recuperação entre chamadas após falha da parada continua para triagem no CLEAN-033, sem requisito novo nesta revisão.

Limitação de infraestrutura: o `up` completo da retomada falhou em presença; a preparação recuperada usou os APK/PC já produzidos e primitivas do kit, mas `phone.ps1 pair` recusou a tela bloqueada (`pairExit=1`, `online=false`). Isso limita a repetição de pareamento/presença; o critério de parada física foi medido. A sonda adicional observou um launcher `emulator` transitório após o retorno, já ausente na inspeção seguinte; qemu e listeners exigidos estavam ausentes no retorno. Logs e distinção entre exit do `down` e da sonda estão no relatório.

Merge local `4baf08e` em `sweatshop/2026-10-04-1210`. PC isolado e AVD encerrados; nenhum arquivo de usuário criado ou apagado e PC real não estava aberto para restauração. Resolution, ledger e `Stage: done` registrados juntos na branch de sessão.
