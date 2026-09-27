# Reopen attribution

Uma linha por achado de cada reabertura, classificada pela rubrica do foreman (`foreman/SKILL.md`, seção 6). `Round` é a N-ésima linha `reopened` do ticket em `run-log.md`; `Implementer` e `Reviewer` como a coluna `Model` de lá. Quando o revisor da rodada é o mesmo modelo do foreman, quem classifica é o GPT-6 Astra (medium, read-only).

| When | ID | Round | Implementer | Reviewer | Label | Finding | Why |
|---|---|---|---|---|---|---|---|
| 2026-09-27 | CLEAN-012 | 1 | sonnet | opus | S2-explícito/código | O filtro de provider próprio compara `uri.authority` cru e é contornado pelo prefixo `userId@` (`content://0@<pacote>.camera/...`) | O critério 2 exige barrar o FileProvider próprio e o ContentResolver tira o prefixo; classificado pelo GPT-6 Astra (d9e5300) |
| 2026-09-27 | CLEAN-012 | 1 | sonnet | opus | S2-explícito/teste | O teste `SEND_MULTIPLE` leva só duas das três URIs e deixa sem prova o lote com a URI própria | A lista de testes do ticket pedia "com as três"; classificado pelo GPT-6 Astra (d9e5300) |
