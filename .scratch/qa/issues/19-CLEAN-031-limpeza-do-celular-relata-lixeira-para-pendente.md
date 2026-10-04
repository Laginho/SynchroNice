# CLEAN-031: Limpeza do celular relata Lixeira para arquivo que virou pendente
Status: needs-triage
Origin: QA da revisão CLEAN-021, 2026-10-03; observação registrada no CLEAN-019 (done)

O `phone.ps1 clean` relata `Lixeira do MediaStore` quando o caminho original
some depois do update (critério 3 do CLEAN-019). No emulador, arquivos que
saíram do caminho original para `.pending-*`, fora da Lixeira, foram
relatados como Lixeira. Requer triagem antes de entrar no loop.

## Comments

- Stage 3 do CLEAN-021 (2026-10-03), AVD `SynchroNice_API37`: três payloads
  ASCII sob nomes `.jpg`, criados por adb em `Download/SynchroNice`, foram
  relatados como `Lixeira do MediaStore`. Uma query com inclusão de pendentes
  achou os derivados `.pending-<n>-<nome>`, com `is_trashed=0/is_pending=1`.
  O revisor conferiu GUID e SHA-256, registrou os derivados e os removeu pelo
  wrapper (`rm (MediaStore indisponivel)`); a listagem final voltou aos 12
  caminhos anteriores.
- Não é regressão do CLEAN-021: a inferência pela ausência já estava na base,
  vinda do CLEAN-019. Não se sabe se acontece com arquivos reais recebidos
  pelo app, nem no Redmi: as fixtures não eram JPEG válidos.
- Para triagem: confirmar a Lixeira antes de relatar (por exemplo, a query
  de confirmação incluindo pendentes, ou um `.trashed-*` com o mesmo SHA-256)
  e dar destino próprio, ou `rm`, ao derivado pendente. A solução, os testes
  e o efeito sobre o critério 3 do CLEAN-019 passam pelo stage 1.
- Evidências: [relatório do CLEAN-021](../../qa-runs/2026-10-03-emulator-CLEAN-021-review.md)
  e o JSON local `2026-10-03-emulator-CLEAN-021-review/phone-criteria-evidence.json`,
  campos `mediaStoreConfirmation` e `cleanupFollowup`.
