# CLEAN-029: Preservar o PC real de debug na limpeza de um emulador ativo
Status: ready-for-agent
Stage: done
Origin: revisão CLEAN-024, 2026-10-04

O registro do PC real pode apontar para o mesmo exe usado pelo PC isolado.
Com estado de emulador ativo, a limpeza pode encerrar o PC recém-restaurado.
A limpeza precisa preservar a identidade da instância normal restaurada,
mesmo quando ela compartilha o exe com o PC isolado.

## Acceptance criteria

1. Com emulador ativo, `down` restaura o exe registrado pelo start não
   isolado, inclusive debug, e mantém essa instância viva após limpar os
   demais processos debug pertencentes à execução.
2. A identidade normal restaurada (PID, exe e horário de início) permanece
   registrada enquanto a limpeza do emulador puder falhar. Repetir após
   erro preserva o mesmo processo, sem novo lançamento.
3. O registro explícito de restauração prevalece sobre `realWasRunning`:
   não se abre também o release, inclusive após falha e retentativa.
4. Se o usuário encerrar o PC já restaurado antes da retentativa, `down`
   termina a limpeza sem reabri-lo. Depois da limpeza bem-sucedida,
   chamadas adicionais continuam idempotentes.

## Implementation

- `qa/down.ps1` conserva o registro de restauração concluída até a limpeza
  do emulador terminar, exclui somente seu PID e horário de início da
  limpeza debug e consome o fallback release antes de tentar limpar o AVD.
- `qa/down.test.mjs` cobre debug real com emulador ativo, flags antigos
  `realWasRunning` verdadeiros e falsos, falha de WMI, mudança do AVD,
  repetição da falha, recuperação e encerramento voluntário do PC normal.
  Os stubs ficam fora do repo; os processos reais do usuário não são alvo.

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
- Correção direta do PR #10 em 2026-10-04: contrato ampliado ao emulador
  ativo e às retentativas; aguardando gate e revisão integrada.

#### Resolution (2026-10-04)
Verdict: Approve

Implementação `5c28b21`; regressões `a71fc99` e `6089387`. Revisão independente
sem bloqueios; suíte focada PC 21/21 e gate completo saída 0. O debug real
permanece aberto durante a limpeza e retries, sem fallback indevido para
release; fechar o restaurado pelo usuário não provoca outro lançamento.
Evidência: [QA do PR #10](../../qa-runs/2026-10-04-pr10-fixes.md).
