# SYN-019: Apagar tudo, protocolo e backend do PC
Stage: to-implement
Status: ready-for-agent
Blocked by: BUG-003
Review: human
Difficulty: hard

- Primary files:
  - pc/src-tauri/src/server.rs (rota `POST /items/delete`, linha `delete` no `/listen`, contrato no cabeçalho)
  - pc/src-tauri/src/outbox.rs (corpo `apagar` na fila; tirar enviados da fila e da lista de enviados da Sessão)
  - pc/src-tauri/src/items.rs (id do remetente no Item recebido; esquecer Itens; ouvinte de exclusão aplicada)
  - pc/src-tauri/src/lib.rs (só `start_server`, se a exclusão precisar de algo novo para subir; o comando e o evento da janela são do SYN-028)
  - pc/src-tauri/Cargo.toml (crate `trash`)
  - pc/src-tauri/tests/server.rs
  - docs/adr/0002-par-persistente-sessao-descartavel.md, CONTEXT.md (o app passa a apagar o que ele mesmo gravou)

#### What to build

Pedido no walkthrough de QA (2026-09-30): tirar uma foto de teste e apagá-la
dos dois aparelhos pelo próprio app, sem ir à Pasta. Como a Sessão é
descartável, a resposta é um **Apagar tudo**: limpa a conversa inteira nos
dois aparelhos, com um desfazer de alguns segundos. Não há exclusão de um Item
só.

Este ticket define o protocolo e faz o backend do PC, tudo na costura HTTP.
A janela do PC (🗑, aviso com Desfazer, bolhas sumindo) é o SYN-028. O
celular é o SYN-027 (cliente do protocolo e fila) e o SYN-029 (app).

Decisões do stage 1 (2026-10-01; protótipo em
`prototype/syn-019-apagar-tudo`):

- **A janela é do SYN-028.** Durante os 5 s do Desfazer nada é apagado nem
  sai pela rede. No fim da janela, ela chama o Apagar tudo daqui com os Itens
  que esvaziou: os recebidos pela chave de `Items`, os enviados pela chave da
  lista de enviados do BUG-003 (as duas sequências colidem). Um Item que não
  está na lista fica.
- **Apagar tudo, no PC** (uma operação da API pública de `pc_lib`, que
  `tests/server.rs` alcança e o comando do SYN-028 chama):
  - os enviados ainda Pendentes da lista saem da fila;
  - os arquivos que o PC gravou ao receber aqueles Itens vão para a Lixeira,
    e as cópias de print do BUG-003 são apagadas. O original de quem enviou
    nunca é tocado;
  - os Itens saem da Sessão, recebidos e enviados, para o recarregar não os
    trazer de volta;
  - uma exclusão com os ids de todos os Itens apagados entra na fila para o
    celular.
- **Protocolo.** A exclusão viaja pela fila de Pendentes dos dois lados e
  endereça cada Item pela origem e pelo id do remetente:
  `{"do_pc":[<id u64>...],"do_celular":["<X-Item-Id>"...]}`.
  - Celular → PC: `POST /items/delete`, com esse JSON no corpo e Bearer.
    Responde 204. É idempotente: id desconhecido é ignorado. Não tem id nem
    dedup próprios.
  - PC → celular: um `OutItem` de corpo `apagar`, que sai no `/listen` como a
    linha `{"id":N,"at_ms":...,"kind":"delete","do_pc":[...],"do_celular":[...]}`
    e sai da fila com o `POST /outbox/N/ack` de sempre. Nunca vira bolha nem
    dispara o evento `enviado`.
  - O Item recebido do celular passa a guardar o `X-Item-Id`, para ser achado
    por `do_celular`.
  - Até o SYN-027 entrar, o celular rejeita a linha `delete` (kind
    desconhecido); entre o SYN-027 e o SYN-029, o app a ignora sem ack. Só
    acontece se alguém clicar no 🗑 do SYN-028.
- **Exclusão recebida pelo PC.** Faz o mesmo que o Apagar tudo, menos a
  exclusão de volta. Um Item ainda recebendo é apagado quando o upload termina
  ou falha. Uma exclusão que chega depois que a Sessão acabou é ignorada, e o
  arquivo fica: não há estado persistente novo (id → arquivo).
- **Aviso para a janela.** Depois de aplicar uma exclusão recebida, o PC chama
  um ouvinte com os Itens que saíram (recebidos e enviados), no padrão de
  `Items::on_new` e `Outbox::on_change`. O SYN-028 liga esse ouvinte a um
  evento para as bolhas sumirem sem aviso.
- **Segurança antes de apagar.** O PC só apaga o caminho que ele gravou para
  aquele Item, se o tamanho ainda é o do Item e nenhum Item mais novo da Sessão
  usa o mesmo caminho. Na dúvida, não apaga. Arquivo que não existe mais é
  ignorado; o Item sai da Sessão mesmo assim.
- **Lixeira** pelo crate `trash`, aqui, porque a exclusão recebida também
  apaga sem passar pela janela.
- **Docs.** A ADR-0002 e a definição de Pasta no `CONTEXT.md` ganham a exceção:
  o app só apaga arquivos que ele mesmo gravou, e só pelo Apagar tudo. A spec
  core já tirou "apagar" do Out of Scope.

#### Acceptance criteria

1. `POST /items/delete` com o `X-Item-Id` de um texto recebido em `do_celular`
   responde 204 e tira o Item da lista de Itens.
2. Com o id de um arquivo recebido, o arquivo sai da Pasta, vai para a Lixeira,
   e o Item sai da lista.
3. Ids desconhecidos, dos dois lados, respondem 204 e não mudam nada; repetir a
   mesma exclusão também.
4. `do_pc` com o id de um enviado tira-o da lista de enviados da Sessão. Se ele
   ainda é Pendente, sai também da fila: `GET /outbox/{id}/file` responde 404,
   e ele não sai mais no `/listen`.
5. Um arquivo da Pasta com tamanho diferente do Item não é apagado; o Item sai
   da lista mesmo assim.
6. Uma exclusão que chega com o arquivo ainda recebendo é aplicada quando o
   upload termina: o arquivo não fica na Pasta.
7. O Apagar tudo do PC, chamado com uma lista de Itens, enfileira uma exclusão
   que sai no `/listen` como linha `kind:"delete"`, com `do_pc` e `do_celular`
   de todos os Itens da lista, e sai da fila com o ack. Um Item fora da lista
   continua na lista de Itens e fora da exclusão.
8. O mesmo Apagar tudo tira da fila os enviados Pendentes, tira da Pasta os
   arquivos recebidos, apaga a cópia do print colado, e tira das listas da
   Sessão os Itens da lista, recebidos e enviados, Pendentes e Entregues.
9. A exclusão enfileirada nunca dispara o evento `enviado`.
10. Uma exclusão recebida, depois de aplicada, chama o ouvinte de exclusão uma
    vez, com os Itens que saíram; uma exclusão só de ids desconhecidos não o
    chama.
11. Os testes existentes seguem verdes.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

QA no stage 3: kit no emulador, a Lixeira do critério 2. Agindo como o celular
por HTTP (token do `config.json` da execução), mande um arquivo ao PC debug,
apague-o com `POST /items/delete` e confira que ele saiu de `data/Pasta` e
está na Lixeira do Windows.

## Tests stage 2 writes (own commit, red)

- `pc/src-tauri/tests/server.rs`, na costura HTTP de sempre (o teste age como
  o celular), critérios 1 a 10; a Lixeira do critério 2 fica para o QA.
  Vermelho porque a rota, o corpo `apagar`, a operação de Apagar tudo e o
  ouvinte de exclusão não existem.

## Comments

- Stage 1 (2026-10-01), grilling com o dono e o `proxy`.
  - **Decisões do dono:**
    - Apagar tudo no lugar da exclusão de um Item;
    - só "para os dois";
    - apagar os arquivos que o app gravou, com Lixeira no PC;
    - sem rastro do outro lado;
    - o botão da B com o desfazer da A.
  - **Decisões do proxy:** o resto.
  - **Fatiamento:** o pedido virou dois tickets, um por costura de teste: este
    (PC, HTTP) e o SYN-027 (celular, cliente do protocolo).
- 2026-10-01 Proxy decided: fatiado a pedido do dono, na costura de teste. Ficam aqui o protocolo e o backend do PC (critérios 1 a 9 do original, testados em `tests/server.rs`), a Lixeira com o crate `trash` e as mudanças da ADR-0002 e do `CONTEXT.md`; a janela (🗑, aviso com Desfazer, recarregar, critérios 10 e 11 do original) foi para o SYN-028 (`24-SYN-028-apagar-tudo-na-janela-do-pc.md`). Para o corte fechar, o critério 7 ganhou "só os Itens da lista", o 8 ganhou a lista de enviados e o print, o 10 é novo (ouvinte de exclusão), e a Lixeira tem QA aqui. Blocked by: BUG-003, sem mudança (lista de enviados e cópia do print). Difficulty: hard, sem mudança: a exclusão no meio do upload e as checagens de segurança interagem. Review: human, porque a mudança de docs fica aqui — o backend é onde o app passa a apagar arquivos, inclusive pela exclusão recebida, que não passa pela janela.

- 2026-10-02 Attempt 1 failed: timeout. Log tail: ...st: protect received files reused as send originals (SYN-019)' -m 'Exercise both remote deletion and local clearing when a received file is the original of a pending or delivered send, including colliding local id sequences.'\"","aggregated_output":"","exit_code":null,"status":"in_progress"}} / {"type":"item.completed","item":{"id":"item_101","type":"command_execution","command":"\"C:\\\\Windows\\\\System32\\\\WindowsPowerShell\\\\v1.0\\\\powershell.exe\" -Command \"git commit -m 'test: protect received files reused as send originals (SYN-019)' -m 'Exercise both remote deletion and local clearing when a received file is the original of a pending or delivered send, including colliding local id sequences.'\"","aggregated_output":"[syn-019 082a39e] test: protect received files reused as send originals (SYN-019)\n 1 file changed, 39 insertions(+)\n","exit_code":0,"status":"completed"}} / {"type":"item.started","item":{"id":"item_102","type":"command_execution","command":"\"C:\\\\Windows\\\\System32\\\\WindowsPowerShell\\\\v1.0\\\\powershell.exe\" -Command \"powershell -ExecutionPolicy Bypass -File .\\\\gate.ps1 -TestsOnly\"","aggregated_output":"","exit_code":null,"status":"in_progress"}} /

- 2026-10-02 Attempt 2 failed: timeout; blocked after two attempts. Log tail: ...i/tests/server.rs', LF will be replaced by CRLF the next time Git touches it\n[syn-019 f2b2530] test: control send origins racing with file recycling (SYN-019)\n 2 files changed, 114 insertions(+), 4 deletions(-)\n","exit_code":0,"status":"completed"}} / {"type":"item.completed","item":{"id":"item_79","type":"agent_message","text":"A proteção do rename passou: 60/60 testes HTTP verdes. O último caso reprovado testa um envio que tenta usar o arquivo enquanto ele vai para a Lixeira, tanto na exclusão normal quanto na conclusão de um upload já marcado para apagar."}} / {"type":"item.started","item":{"id":"item_80","type":"command_execution","command":"\"C:\\\\Windows\\\\System32\\\\WindowsPowerShell\\\\v1.0\\\\powershell.exe\" -Command \"powershell -ExecutionPolicy Bypass -File .\\\\gate.ps1 -TestsOnly *> '.scratch/qa-runs/2026-10-02-syn-019-reopen/send-race-red.log'; \"'$gateResult = $LASTEXITCODE; Get-Content '\"'.scratch/qa-runs/2026-10-02-syn-019-reopen/send-race-red.log' | Select-String -Pattern 'running \\\\d+ tests|test result:|FAILED|panicked|error\\\\[|gate:|==>|BUILD SUCCESSFUL' -Context 0,2; exit \"'$gateResult'","aggregated_output":"","exit_code":null,"status":"in_progress"}} /

- 2026-10-02 Proxy decided: retomar da branch `syn-019` em `f2b2530`, sem recomeçar nem fatiar, no próximo run com o hard do lineup (sonnet-5.5 xhigh) e `-ImplementMinutes 75` — dos cinco ❌ da revisão, quatro já estão vermelho→verde na branch e o último tem o teste vermelho committed; fatiar para destravar SYN-028/027 tiraria daqui a guarda de "o original de quem enviou nunca é tocado" e o teste da branch para ganhar menos de um ciclo (a rodada 2 fechou um a cada ~8 min).
  - **Para o stage 2 (retomada):**
    - Trabalhe na branch `syn-019` como está; não recrie a branch nem refaça os testes já committed. Prova da rodada 2, conferida pelo proxy nos logs locais de `.scratch/qa-runs/2026-10-02-syn-019-reopen/` (`gate.ps1 -TestsOnly`): `6078c66` → `817dfe8` (56 HTTP + 1 vermelho → 57), `7d8ae2e` → `475bcdf` (57 + 1 → 58), `27b3cfb` → `3adbe4a` (58 + 1 → 59), `db2bb99` → `16da8d5` (59 + 1 → 60); 4 unitários, 7 config e 5 outbox verdes em todas.
    - Comece pelo teste de `f2b2530`, `envio_concorrente_com_exclusao_preserva_original_ou_recusa_origem_ausente` (o ❌ "proteção concorrente de original" da revisão): rode `gate.ps1 -TestsOnly` em primeiro plano e registre o vermelho. Essa prova ainda não existe: o timeout matou a rodada na compilação (`send-race-red.log`). Se falhar só pelo harness, conserte em commit de teste.
    - Depois, a correção em commit só de código. A revisão aponta o formato: uma operação da Outbox que confere os originais e faz a ação no arquivo sob a mesma trava em que `enqueue_file_from_path` confere a origem e registra o caminho, usada pelos dois chamadores (`apply_delete` e `finish_received_file`); isso também tira a captura duplicada.
    - É o último ❌. Não abra ciclos novos para outros intervalos: um risco novo vai como nota aqui, para o stage 3. Verde → gate completo em primeiro plano → `to-review`.

- 2026-10-02 Attempt 3 failed: exit 0; blocked after two attempts. Log tail: ...stsOnly` was refused for approval, and I haven't changed any code or committed anything. /  / The attempts were the nested `powershell`, the bare `.\gate.ps1`, `powershell.exe` under Bash, and redirects or pipes around them. The earlier `powershell` call under Bash returned exit 127 (command not found). The loop's rule is red before green, so I won't write the fix without this run. /  / Either approve one of these, or run it yourself and tell me the result: /  / ``` / powershell -ExecutionPolicy Bypass -File .\gate.ps1 -TestsOnly / ``` /  / I need that run to show `envio_concorrente_com_exclusao_preserva_original_ou_recusa_origem_ausente` failing, with the other tests green. /  / Once it's red, the code-only commit is: / - **New Outbox operation:** it checks the sent originals and recycles the file while holding the outbox lock. / - **Origin registration:** `enqueue_file_with_origin` checks that the path exists and registers it under that same lock. It returns `NotFound` if the delete already won. / - **Callers:** `apply_delete` and `finish_received_file` both use the new operation, which also removes the duplicated `original_paths()` capture. /  / After that comes the full gate in the foreground, then `to-review`.

- 2026-10-02 Proxy decided: reabrir para o stage 2 com o mesmo plano (retomar da branch `syn-019` em `f2b2530`, começando pelo vermelho de `envio_concorrente_com_exclusao_preserva_original_ou_recusa_origem_ausente`) — a terceira tentativa não chegou ao ticket: o driver só liberava `Bash(powershell:*)` e cada `gate.ps1 -TestsOnly` pela ferramenta PowerShell foi negado; defeito de infraestrutura, já corrigido (Agent-Skills `7972d73`, regras `PowerShell(...)` espelhadas), e a decisão anterior continua de pé no mérito.
