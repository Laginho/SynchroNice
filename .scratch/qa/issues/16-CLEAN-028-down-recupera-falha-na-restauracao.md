# CLEAN-028: Recuperar a restauração do PC real quando o relançamento falha
Status: needs-triage
Origin: revisão CLEAN-024, 2026-10-04

O `down` pode deixar o PC real fechado após falha de WMI ao relançá-lo.
Requer triagem e definição da recuperação antes de entrar no loop.

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
