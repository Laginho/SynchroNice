# CLEAN-029: Preservar o PC real de debug na limpeza de um emulador ativo
Status: needs-triage
Origin: revisão CLEAN-024, 2026-10-04

O registro do PC real pode apontar para o mesmo exe usado pelo PC isolado.
Com estado de emulador ativo, a limpeza pode encerrar o PC recém-restaurado.
Requer triagem antes de entrar no loop.

## Comments

- Stage 3 do CLEAN-024: `pc.ps1 start -Exe <debug>` sem `-Isolated` é
  permitido; a triagem retirou a recusa por exe. Se `emulator.json` ainda
  está ativo e o registro aponta para `DebugExe`, o `down` relança esse
  exe em `qa/down.ps1:47`, depois encerra todos os processos desse caminho
  em `:61`. Com `realWasRunning: true`, a rotina seguinte pode abrir o
  release, que é outro exe.
- Repro contra o script real com cmdlets simulados: registro de um PID
  vivo no debug, emulador ativo e `realWasRunning: false`; sequência
  `stop:102`, lançamento do debug, `stop:201`. Saída 0 com
  `restored: true`, mas nenhum PC permanece vivo. Nenhum processo real
  do usuário foi aberto ou encerrado.
- A prosa do CLEAN-024 pede restauração com ou sem emulador ativo, mas
  seu critério 2 restringe-se a emulador ausente/inativo. O teste
  `real-active` usa release, sem essa colisão. A limpeza anterior já
  encerrava `DebugExe` no mesmo estado; o achado requer ampliar o
  contrato e distinguir a instância restaurada da instância isolada.
- Evidências: [relatório CLEAN-024](../../qa-runs/2026-10-04-emulator-CLEAN-024-review.md),
  harness local ignorado `clean-024-stage3/repro.ps1` e `repro.log`.
