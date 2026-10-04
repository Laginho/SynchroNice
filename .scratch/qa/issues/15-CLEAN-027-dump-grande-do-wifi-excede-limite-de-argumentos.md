# CLEAN-027: Dump grande do Wi-Fi excede o limite de argumentos
Status: needs-triage
Origin: QA da revisão CLEAN-023, 2026-10-03

O `wait_wifi` do kit pode falhar ao confirmar o estado do Wi-Fi no Android
quando o dump completo é grande. Requer triagem antes de entrar no loop.

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
