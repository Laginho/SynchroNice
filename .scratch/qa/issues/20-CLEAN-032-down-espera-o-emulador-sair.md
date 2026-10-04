# CLEAN-032: `down` só termina depois de o processo do emulador sair
Status: needs-triage
Origin: QA da revisão CLEAN-026, 2026-10-04; observação registrada no SYN-025 (done)

O `down` dá o emulador por encerrado quando o adb deixa de listá-lo, sem
esperar o processo `qemu-system-x86_64`/`emulator` sair. Requer triagem antes
de entrar no loop.

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
