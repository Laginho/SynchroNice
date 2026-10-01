# CLEAN-020: `down` restaura o PC real mesmo quando o emulador falha
Status: needs-triage
Blocked by: none
Origin: revisão do PR 8 (sessão sweatshop/2026-09-30-2146), 2026-10-01

## Comments

- `qa/down.ps1:21-29` lança erro (`Emulador mudou`, `Nao foi possivel
  derrubar`, `Emulador nao encerrou` após 30 s) antes de matar o PC de
  debug (`:34`) e de reabrir o PC real (`:35-58`). Como `qa/up.ps1:133`
  chama `down` em toda falha do `up`, um emulador lento deixa o `pc.exe`
  real fechado e o PC isolado segurando a porta 47820, e nem o autostart
  consegue subir o real. Isso contraria a história 6 da spec.
- Proposta para triagem: matar o PC de debug e restaurar o real primeiro, ou
  num `finally`, e só depois derrubar o emulador. O check mínimo é o
  wrapper real com um adb falso que nunca encerra o emulador, e o PC real
  tem que reaparecer mesmo assim.
