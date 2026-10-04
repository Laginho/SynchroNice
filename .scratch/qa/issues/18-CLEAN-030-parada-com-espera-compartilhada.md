# CLEAN-030: Avaliar helper compartilhado de parada com espera no kit
Status: needs-triage
Origin: eixo Standards do stage 3 de CLEAN-026, 2026-10-04

## Contexto

`qa/up.ps1`, `qa/down.ps1` e `qa/pc.ps1` definem cópias idênticas de
`Stop-AndWait`: retenção do handle, parada forçada, espera de 10 segundos e
erro de infraestrutura com PID. Uma correção futura dessa política precisa
ser aplicada nas três cópias.

O revisor registrou possível Duplicated Code como preocupação pequena e
não bloqueante. CLEAN-026 cumpre seu contrato; extrair um helper acrescenta
um arquivo e uma dependência entre wrappers que não pertencem à fronteira
aprovada daquele ticket.

## Proposta para triagem

Decidir se a manutenção em um único lugar compensa essa dependência.
Se aprovado, definir a fronteira e a verificação antes de implementar;
preservar os prazos, os erros com PID e a execução direta dos wrappers.
Este registro não autoriza implementação nem altera os critérios de CLEAN-026.

## Comments

- Registrado como acompanhamento de manutenção, sem defeito de comportamento
  identificado e sem impedir a aprovação de CLEAN-026.
