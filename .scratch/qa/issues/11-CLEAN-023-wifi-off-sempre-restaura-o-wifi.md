# CLEAN-023: `wifi-off` sempre restaura o Wi-Fi
Status: needs-triage
Blocked by: none
Origin: revisão do PR 8 (sessão sweatshop/2026-09-30-2146), 2026-10-01

## Comments

- O script no aparelho em `qa/phone.ps1:225-229` só faz `trap restore EXIT`,
  e o `svc wifi enable` é tentado uma vez. Um TERM/INT/HUP, ou um enable que
  falha de forma transitória, deixa o Redmi sem Wi-Fi. Como USB foi recusado
  para o kit, não sobra canal de volta, e o pós-run do foreman é desassistido.
- Proposta para triagem: `trap restore EXIT HUP INT TERM` e algumas tentativas
  de `svc wifi enable` antes de desistir, com um caso pelo adb falso que já
  executa o script gerado.
- Nunca rodou no Redmi: o critério 4 de SYN-024 é `Débito humano`.
