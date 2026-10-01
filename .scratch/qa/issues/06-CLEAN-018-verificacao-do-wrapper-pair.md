# CLEAN-018: Preservar uma verificação executável do wrapper pair
Status: needs-triage
Blocked by: none
Origin: SYN-023, revisão stage 3 de 2026-09-30

## Comments

- Standards identificou uma limpeza não bloqueante em `qa/phone.ps1`,
  ação `pair`: validação do config e IPv4, descoberta da rota e escape do
  JSON pelo PowerShell/adb não têm uma verificação executável preservada.
  Ponytail ativo exige um check para lógica não trivial. O stage 2 registrou
  que seu harness sintético passou e foi removido; os testes da activity
  constroem o JSON diretamente e não exercitam o wrapper.
- Proposta para triagem: conservar um check mínimo com config e adb
  sintéticos que execute o wrapper real, confira JSON/porta/IP explícito e
  padrão, e confirme que entradas inválidas não disparam a activity. Sem
  aparelho real, novo framework ou dependência.
- Nenhum defeito ou regressão demonstrado. O contrato de SYN-023 não exige
  esse teste; pelo ticket-flow, o achado não reabre SYN-023. Primary files,
  critérios e seam deste CLEAN ainda precisam ser aprovados no stage 1.
