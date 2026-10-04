# CLEAN-032: `down` só termina depois de o processo do emulador sair
Stage: blocked
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

- 2026-10-04 Review ended at reviewing (timeout); branch clean-032 holds the review; left for a human
