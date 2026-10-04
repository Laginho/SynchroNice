# CLEAN-028: Recuperar a restauração do PC real quando o relançamento falha
Status: ready-for-agent
Stage: done
Origin: revisão CLEAN-024, 2026-10-04

O `down` pode deixar o PC real fechado após falha de WMI ao relançá-lo.
A restauração iniciada pelo kit deve persistir até o relançamento normal
terminar; um registro comum de processo já morto continua sem relançamento.

## Acceptance criteria

1. Antes de encerrar o PC real identificado, `down` salva a intenção de
   restauração e a identidade do processo. Falha ao encerrar conserva o
   registro e não abre outra instância.
2. Se o relançamento falhar, `down` sai com erro. Repetir o comando retoma
   a restauração mesmo sem emulador ou com estado inativo, encerrando o
   original ainda vivo antes de abrir uma única instância normal.
3. A instância normal usa o exe registrado, sem CDP, isolamento ou pipes
   de saída herdados. A gravação do estado não trunca o último registro
   completo se falhar.
4. Sem intenção pendente, PID morto ou exe diferente apenas consome o
   registro; não relança um PC que o usuário já encerrou. Depois do
   sucesso, repetir `down` não substitui nem duplica o PC restaurado.

## Implementation

- `qa/down.ps1` grava `restorePending` e o horário de início antes do stop;
  a retentativa ainda encerra o original se sua identidade corresponder.
  O helper de lançamento retorna o processo normal, registrado com PID,
  horário de início e `restoreComplete` até a limpeza terminar.
- As gravações usam um arquivo temporário e substituição do registro
  completo. Os flags de restauração distinguem o PID morto pelo kit de
  um registro antigo sem intenção.
- `qa/down.test.mjs` usa executáveis inofensivos temporários e injeta
  falhas de WMI e de stop. Verifica processos vivos, ambiente herdado,
  retentativa sem duplicação e os casos anteriores de PID morto/diferente.

## Comments

- Stage 3 do CLEAN-024: `qa/down.ps1:45–50` encerra o PID registrado antes
  de chamar `Start-NormalPc`. Se o WMI falha, o registro permanece com o
  PID que o próprio `down` acabou de encerrar. Na chamada seguinte, o
  processo já morreu: o registro é apagado e a saída indica sucesso sem
  reabrir o PC. A mensagem do helper pede `repita down`, mas essa repetição
  não retoma a restauração sem emulador ativo.
- Repro contra o script real, com cmdlets simulados e sem tocar em processos
  do usuário: primeira saída 1, WMI 2 e registro preservado; restaurada a
  disponibilidade do WMI, segunda saída 0, `restored: false`, registro
  apagado, nenhuma segunda tentativa de lançamento e nenhum PC vivo.
- O critério 3 do CLEAN-024 manda apagar o registro de um processo já morto
  sem lançar nada. A recuperação precisa distinguir esse caso de uma
  restauração iniciada pelo kit e ainda pendente. Essa decisão não foi
  acrescentada ao contrato durante a revisão.
- Evidências: [relatório CLEAN-024](../../qa-runs/2026-10-04-emulator-CLEAN-024-review.md),
  harness local ignorado `clean-024-stage3/repro.ps1` e `repro.log`.
- Correção direta do PR #10 em 2026-10-04: critérios acima definem a
  recuperação solicitada pela revisão; aguardando gate e revisão integrada.

#### Resolution (2026-10-04)
Verdict: Approve

Implementação `5c28b21`; regressões `a71fc99` e `6089387`. Revisão independente
sem bloqueios; suíte focada PC 21/21 e gate completo saída 0 (83 Rust,
61 UI, 95 QA e Android aprovado). Recuperação, falha de parada e identidade
do processo verificadas com executáveis temporários reais, sem fechar o PC
do usuário. Evidência: [QA do PR #10](../../qa-runs/2026-10-04-pr10-fixes.md).
