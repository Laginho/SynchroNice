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

- Stage 3 de SYN-020 (2026-10-02): confirmado no AVD API 37. A query do
  wrapper com `android:query-arg-match-trashed:i:1` retorna
  `[ERROR] Binding not well formed`, embora o adb saia com código 0.
  `clean` registrou os cinco destinos como `rm (MediaStore indisponivel)`;
  quatro arquivos estavam fisicamente em `.trashed-*`. O PDF ainda estava
  no caminho original, com o mesmo SHA-256 da fixture e `is_trashed=0`,
  depois da retentativa da limpeza. Só esse PDF foi enviado à Lixeira por
  sua URI Downloads observada; uma query com a chave escapada confirmou
  `is_trashed=1` e o caminho `.trashed-*`. Os cinco arquivos ficaram na
  Lixeira. Evidência local em
  `.scratch/qa-runs/2026-10-02-syn-020-review/cleanup-confirmed.txt` e
  relatório `.scratch/qa-runs/2026-10-02-syn-020-review.md`.
  Nenhuma alteração do wrapper ou dos critérios deste CLEAN; nota para
  triagem, sem achado adicional contra SYN-020.
