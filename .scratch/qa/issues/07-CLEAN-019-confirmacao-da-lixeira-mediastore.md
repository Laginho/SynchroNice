# CLEAN-019: Confirmar e relatar o destino da limpeza MediaStore
Status: needs-triage
Blocked by: none
Origin: SYN-024, revisão stage 3 de 2026-09-30

## Comments

- `qa/phone.ps1:226` passa o extra
  `android:query-arg-match-trashed:i:1` à query de confirmação. O
  [parser AOSP Content.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/cmds/content/src/com/android/commands/content/Content.java)
  separa campos por dois-pontos não escapados e exige três campos:
  chave, tipo e valor. O argumento atual tem quatro; a query não confirma
  `is_trashed=1`. Verificação por leitura da fonte primária, não no Redmi.
- Se o update anterior tiver mandado o arquivo à Lixeira, o catch tenta rm
  no caminho antigo e pode relatar `rm (MediaStore indisponivel)` como
  destino. Esse relato não distingue uma reciclagem já concluída.
- Proposta para triagem: conservar o escape da chave através do PowerShell
  e adb shell, confirmar a query e registrar o destino que ocorreu, com
  um check mínimo no wrapper real. Sem nova dependência.
- Critério 6 de SYN-024 permite Lixeira ou rm; não exige a confirmação ou
  a distinção exata desse caso. Este achado não participa da reabertura.
  Primary files, critérios e seam deste CLEAN precisam passar pelo stage 1.
