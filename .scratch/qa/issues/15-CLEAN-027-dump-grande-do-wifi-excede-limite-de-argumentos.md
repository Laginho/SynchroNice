# CLEAN-027: Dump grande do Wi-Fi excede o limite de argumentos
Stage: implementing
Status: ready-for-agent
Blocked by: none
Review: agent
Difficulty: normal
Origin: QA da revisão CLEAN-023, 2026-10-03

- Primary files:
  - qa/phone.ps1 (script gerado do `wifi-off`)
  - qa/phone.test.mjs

#### What to build

O `wait_wifi` do script do `wifi-off` guarda o `dumpsys wifi` inteiro numa
variável e o passa como argumento de `printf`. No mksh do aparelho o `printf`
não é embutido, e o Linux limita cada argumento a 131072 bytes; o dump real
do `SynchroNice_API37` tem ~343 KB, e o `wifi-off` falha com `Argument list
too long` antes de confirmar o estado.

O `wait_wifi` passa a nunca levar o dump a um argumento de comando. Um
`dumpsys` que sai diferente de 0 continua falhando o passo na hora, sem
retentativa. A forma é livre; se usar arquivo temporário, o `restore` o apaga
junto com o script. Evitar `dumpsys | grep -q` sob `pipefail`: o `grep -q`
sai cedo, o `dumpsys` toma SIGPIPE e o pipeline dá falso negativo.

#### Acceptance criteria

1. Com um dump de 400 KB ou mais contendo a linha `Wi-Fi is <estado>`, o
   `wifi-off` confirma que desligou e que restaurou (cenário `large-dump`).
2. Um `dumpsys` que sai diferente de 0 falha o passo sem retentativa: no
   cenário `status-denied`, o `clock` falso continua 0.
3. Os cenários atuais do teste 4 continuam passando.
4. Stage 3, pelo kit no `SynchroNice_API37`: `qa/phone.ps1 wifi-off` sai 0 e
   o log tem `QA_WIFI_DISABLED` e `QA_WIFI_RESTORED`. Falha de
   infraestrutura não reprova o ticket.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

## Tests stage 2 writes (own commit, red)

Em `qa/phone.test.mjs`, teste 4, o harness passa a emular o shell do
aparelho:
- o script roda como `sh -c 'enable -n printf; . ./captured.sh'` em vez de
  `sh captured.sh`, e um `printf` falso no diretório do fake sai com
  `Argument list too long` quando um argumento passa de 131072 bytes; nos
  outros casos faz `exec /usr/bin/printf "$@"`. O proxy conferiu no git-bash
  que, assim, o código atual falha e uma variante com arquivo passa;
- cenário `large-dump`: o `dumpsys` falso emite 400 KB ou mais com a linha
  de estado (critério 1, vermelho hoje);
- no `status-denied`, a asserção de `clock` igual a 0 (critério 2).

## Comments

- Stage 3 do CLEAN-023 (2026-10-03): no AVD `SynchroNice_API37`, shell
  `/system/bin/sh` mksh R59, a CLI real chegou ao `svc wifi disable`, mas
  `printf '%s\n' "$state"` falhou com `Argument list too long`. O wrapper
  terminou com `Wi-Fi nao confirmou desligar e restaurar`; o script não chegou
  ao sleep pedido. Uma medição posterior de `dumpsys wifi | wc -c` retornou
  343513 bytes. A restauração também executou o mesmo caminho de falha.
- O `wait_wifi` é idêntico ao da base `168ffb9`, anterior ao CLEAN-023.
  A mudança revisada acrescenta traps/retries; não altera a captura do dump
  nem o `printf`. Achado anterior, sem reabertura do CLEAN-023.
- Filtrar a saída real do `dumpsys` para a linha `Wi-Fi is ...`, somente no
  ambiente controlado da verificação, permitiu confirmar os sinais e retries.
  Evitar passar o dump inteiro como um argumento é uma possibilidade para
  triagem; a solução e os testes ainda precisam ser especificados.
- Evidências: [relatório do CLEAN-023](../../qa-runs/2026-10-03-emulator-CLEAN-023-review.md),
  arquivos locais `2026-10-03-emulator-CLEAN-023-review/runtime/signal-TERM/cli.stderr.log`
  e `environment.log`. O Redmi não foi acionado.
- Proxy decided: aceito (normal, Review: agent); wait_wifi nunca passa o dump como argumento, dumpsys com erro falha sem retentativa, vermelho comportamental no harness (printf embutido desligado e printf falso com limite de 128 KiB por argumento) e wifi-off real no emulador como verificação — o bash do harness tem printf embutido, então só emulando o mksh do aparelho o teste reproduz o defeito; checagem estática do script testaria a forma, não o comportamento.
