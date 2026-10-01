# CLEAN-021: Baseline do celular sem diferenciar maiúsculas
Status: needs-triage
Blocked by: none
Origin: revisão do PR 8 (sessão sweatshop/2026-09-30-2146), 2026-10-01

## Comments

- `qa/phone.ps1:165` (track) e `:276` (clean) comparam com o baseline por
  `-ccontains`, que diferencia maiúsculas. O `/storage/emulated/0` não
  diferencia (FUSE). Cenário: o usuário já tem `Foto.jpg` e o agente rastreia
  `foto.jpg`. A guarda passa, o hash sai do arquivo do usuário, a busca
  `_data=` exata do MediaStore erra no `clean`, e o fallback `rm` apaga o
  arquivo real. Risco de perda de dado do usuário.
- Proposta para triagem: comparar sem diferenciar maiúsculas (`-contains`) nos
  dois pontos, com um caso no `qa/phone.test.mjs` pelo adb falso.
- Nunca rodou no Redmi: o critério 6 de SYN-024 é `Débito humano`.
