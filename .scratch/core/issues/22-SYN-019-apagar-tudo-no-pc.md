# SYN-019: Apagar tudo, protocolo e backend do PC
Stage: to-review
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

- Harness de concorrência da reabertura: os testes continuam em `tests/server.rs`, observando HTTP, listas e arquivos pela API pública. Pausas de agendamento opcionais em `server.rs` (`debug_assertions`, omitidas no release) tornam os intervalos reproduzíveis; não substituem rede, fila ou filesystem, e soltam a operação mesmo se o teste falhar. Commits dessas pausas são suporte de teste, separados das correções de produção.

- Stage 2, reabertura (2026-10-02): costura mantida em `tests/server.rs`, HTTP e API pública do servidor. Chamadores reconferidos: conclusão/falha/progresso de uploads e observadores de `Items`; `Running::caminho_do_arquivo` para leitura/abertura/drag; envio de arquivo/bytes, ACK, escuta, rotação do Par e exclusão local/remota na Outbox. Casos reprovados: observador de Done que resolve o caminho, proteção de original após excluir a enviada, aliases Windows e interleavings de rename/registro e envio/exclusão. Nenhuma decisão ou critério novo; testes em commits próprios antes de cada correção.

- Stage 2 concluído (2026-10-02), branch `syn-019`: protocolo de exclusão, Apagar tudo público, descarte seguro de recebidos e cópias de prints, remoção de enviados/Pendentes e ouvinte de exclusão implementados. `CONTEXT.md` e ADR-0002 atualizados; a janela e o Android seguem nos tickets já definidos.
  - Prova red-green restante, conferida no log da tentativa anterior: `f84bf1d` falhou por `Items::on_delete` e `Running::apagar_tudo` ausentes; `5d61d52` passou os 55 HTTP. Nesta retomada, o teste de `082a39e` falhou por original ausente (55 verdes, 1 vermelho), e `940a692` passou os 56 HTTP, incluindo as quatro combinações de exclusão local/remota e envio Pendente/Entregue.
  - Gate completo, em primeiro plano: `powershell -ExecutionPolicy Bypass -File .\gate.ps1`, exit 0, `gate: passou`. Formatação, Clippy e TypeScript verdes; Rust: 56 HTTP + 4 unitários + 7 config + 5 outbox, sem falhas; UI: 47/47; kit QA: 14/14; Android: `check` verde (relatórios existentes de 71 testes do app e 62 do cliente, nenhum erro/falha; tarefas de teste `UP-TO-DATE`).
  - Diff final conferido: testes em commits próprios antes do código, sem alteração de testes em commits de implementação; sem arquivos fora do escopo além do lock gerado pela dependência aprovada. A conferência da Lixeira pelo kit continua sendo o QA previsto para o stage 3, ainda não executado aqui.

- Stage 2 retomado (2026-10-02): o driver recolocou o ticket em `to-implement` após o timeout. A branch `syn-019` foi reconstruída do commit preservado `082a39e`, depois de conferir os commits e a prova anterior; o registro da tentativa falha foi mantido. O `gate.ps1 -TestsOnly` reproduziu o vermelho do teste de originais de enviados: 55 HTTP verdes e 1 falha por arquivo ausente. Os 4 unitários Rust, 7 config, 5 outbox, 47 UI, 14 QA e o Gradle continuaram verdes. A correção conserva os caminhos dos enviados antes de removê-los da Sessão e os protege na exclusão de recebidos, inclusive com ids locais colidindo e ACK já recebido.

- Prova red-green do stage 2 (2026-10-02), fatias HTTP: `e4f7a89` falhou com 404 nos dois testes novos (41 HTTP existentes verdes); `d3cc51b` passou os 43 HTTP. `48a202e` + `ab46d53` falharam nos oito casos novos de arquivo/fila (43 verdes); `77ebaf2` passou os 51 HTTP. Todas essas rodadas usaram o `gate.ps1 -TestsOnly` em primeiro plano; Rust fora de HTTP (4 unitários, 7 config, 5 outbox), UI (47), QA (14) e Gradle seguiram verdes. `Cargo.lock` é a atualização gerada necessária para a dependência `trash` aprovada em `Cargo.toml`; sem atualizações de dependências existentes.

- Stage 2 (2026-10-02): costura aprovada `tests/server.rs`, HTTP e API pública do servidor. Chamadores revistos: `post_text`/`post_file` criam e terminam recebidos; `FailGuard` termina uploads cancelados; a janela consulta os snapshots e caminhos, e escuta `on_new`/`on_change`; `/listen`, download, ACK e rotação do Par usam a fila. A exclusão precisa preservar ids locais monotônicos, retries já aceitos, uploads em curso (sucesso e falha), arquivos de tamanho desconhecido/zero, a Pasta original após troca, caminhos reutilizados e originais de enviados. Testes e código serão entregues em commits separados por fatia.

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
#### Review (2026-10-02)

Verdict: Reopen — regressões de preservação de originais e de callback, mais janelas concorrentes de exclusão sem proteção.

Revisão completa de `git diff d953e55...4c7dab3`, com os eixos Standards e
Spec em subagentes independentes. Todos os achados desta primeira passagem
estão abaixo. A retomada do stage 2 deve resolver os ❌ em commits de teste
próprios, vermelhos antes do código; nenhum teste ou código foi alterado
pelo stage 3. Os casos de segurança são S2-implícito (regressões de envio e
arquivo sob os critérios 7/8), não requisitos novos acrescentados ao contrato.

##### Standards

- ❌ **P2, regressão de API pública / S2-implícito:**
  `server.rs:862-876` chama `Items::file_done` segurando `received_files`;
  o `on_new` é síncrono (`items.rs:204`). Um observador de `Done` que resolve
  o arquivo por `Running::caminho_do_arquivo` (`server.rs:329-334`) tenta
  adquirir esse mesmo mutex e trava a conclusão do upload. Antes do diff,
  o lock era solto antes do callback. Viola preservar comportamento
  existente (Engineering Workflow, quality gate 1). O ouvinte atual de
  `lib.rs` só emite eventos; o achado é a regressão na costura pública,
  verificada pelo fluxo de locks, sem probe de execução nesta revisão.
  **Falta:** teste HTTP com observador que consulta o caminho em `Done`, e
  conclusão/notificação que preserve essa consulta sem deadlock.
- ❌ **P1, proteção concorrente de original / S2-implícito:**
  `server.rs:609-625` e `866-872` capturam `session_items` e soltam o lock
  da Outbox antes de reciclar o arquivo. Um `enqueue_file_from_path`
  concorrente entre a captura e a exclusão pode registrar um envio novo
  cujo original não está em `sent_paths` e será reciclado. Viola preservar
  o comportamento de arquivos/envios e considerar impacto operacional
  (Engineering Workflow, quality gates 1/3; política de original no
  `CONTEXT.md`). Achado estático, sem reprodução por estes probes.
  **Falta:** teste de interleaving pela costura aprovada, e proteção válida
  até a ação no arquivo, incluindo o caminho da conclusão de upload.
- **Heurística, sem veto próprio — possível Duplicated Code:** a captura
  `session_items().into_iter().filter_map(|sent| sent.path).collect()` aparece
  nos dois lugares acima. Uma operação coesa de proteção da Outbox pode
  eliminar a duplicação ao corrigir a sincronização; não há pedido de
  refatoração fora de Primary files.

##### Spec

- ❌ **P1, arquivo mais novo fora da seleção / S2-implícito**
  (critério 7, “Um Item fora da lista continua”; decisão de segurança,
  “nenhum Item mais novo da Sessão usa o mesmo caminho”):
  `server.rs:854` faz rename antes de registrar o caminho em `862-863`.
  Se o arquivo de um recebido antigo foi removido e um upload novo reutiliza
  esse caminho com tamanho igual, excluir o antigo entre rename e registro
  recicla o arquivo do upload novo: `recycle_received_file` só vê o mapa
  de caminhos já registrados (`652-654`). O novo upload pode então terminar
  com 201/Done e caminho sem arquivo, quebrando o recebimento existente.
  Achado estático; não foi reproduzido no kit. **Falta:** teste controlando
  a conclusão nesse intervalo e proteção que inclua rename/registro. O
  `after_file_rename` de testes já existente mostra essa janela.
- ❌ **P1, original perde proteção na mesma Sessão / S2-implícito**
  (segurança dos critérios 7/8; decisão, “O original de quem enviou nunca é
  tocado”; `CONTEXT.md`, “O original de um Item enviado nunca é apagado”):
  receber R, reenviar seu arquivo como S, excluir só S e depois excluir R
  apaga o original. O primeiro pedido tira S da lista (`outbox.rs:426-428`);
  o segundo conserva só os caminhos de enviados ainda presentes
  (`server.rs:609-614`). Tirar S não encerra a Sessão. **Reproduzido no kit**:
  ambos os pedidos 204, R e arquivo presentes após excluir S, arquivo
  ausente e na Lixeira após excluir R. O teste de `082a39e` cobre exclusão
  simultânea, não essa sequência. **Falta:** teste das exclusões separadas
  e preservação do original pelo ciclo de vida da Sessão, sem persistência
  nova de id → arquivo.
- ❌ **P1, alias Windows apaga original de outro enviado / S2-implícito**
  (critério 7, Item fora da lista; critério 8, descarte da cópia interna;
  mesma política de original): `outbox.rs:416-420` compara `PathBuf`
  literalmente. Criar cópia interna P, reenviá-la como S por outra
  capitalização do mesmo caminho e excluir só P apaga o original de S.
  **Reproduzido no kit**: P=2, S=3; pedido só com `do_pc:[2]` respondeu
  204; S continuou em `list_sent_items`, mas original e alias sumiram.
  A leitura/drag desse Item passa a apontar a arquivo ausente. **Falta:**
  teste de reuso por alias e checagem de identidade do caminho, mantendo
  os Itens não selecionados funcionais.
- A regressão de callback do eixo Standards também impede a confirmação
  HTTP do upload nesse uso existente. Não há requisito básico ausente nem
  escopo adicional: a rota, controle persistido/ACK, remoção selecionada,
  Lixeira e ouvinte estão implementados, com lacunas de segurança acima.

**Validação:** gate completo em primeiro plano, exit 0, `gate: passou`.
Rust: 56 HTTP + 4 unitários + 7 config + 5 outbox; UI: 47/47; QA: 14/14.
Formatação, Clippy e TypeScript verdes. Android `check` verde; testes
`UP-TO-DATE`, XML com 71 do app e 62 do cliente, sem falhas/erros/pulos.
Essa suíte verde não cobre os interleavings e reusos identificados.
Provas red-green da implementação conferidas nos commits e no registro
acima: `e4f7a89` → `d3cc51b` (41 → 43 HTTP),
`48a202e`/`ab46d53` → `77ebaf2` (43 → 51),
`f84bf1d` → `5d61d52` (APIs ausentes → 55),
`082a39e` → `940a692` (55 verdes/1 vermelho → 56 verdes).
Não houve violação de Primary files ou da separação teste/código; o lock
gerado pela dependência `trash` aprovada é a atualização necessária.

**QA:** [relatório do emulador](../../qa-runs/2026-10-02-syn-019-review.md).
Critério 2 ✅: destino na Lixeira do Windows confirmado com SHA-256 idêntico;
dois casos de segurança ❌ reproduzidos. `clean` e `down` concluídos, sem
arquivos prévios afetados. A branch continua `syn-019`, sem merge; não havia
linha SYN-019 no ledger a retirar.

**Decisão do proxy revisada:** a linha `2026-10-01 Proxy decided` deste
ticket fixou o fatiamento: protocolo/backend/Lixeira/docs aqui, janela no
SYN-028; critério 7 seleciona só a lista, 8 inclui enviados/print e 10 é o
ouvinte; BUG-003 continua como dependência e `Review: human` se mantém.
O diff respeita esse recorte; esta revisão não mudou decisões ou critérios.

Resumo por eixo: Standards, 2 regressões/riscos que exigem teste + 1
heurística sem veto (pior: original de envio concorrente pode ser reciclado);
Spec, 3 falhas de preservação de arquivo, duas reproduzidas (pior: arquivo
fora da seleção/original é apagado). O callback foi citado nos dois eixos
como o mesmo achado, não como outro defeito.

- 2026-10-02 Proxy decided: retomar da branch `syn-019` em `f2b2530`, sem recomeçar nem fatiar, no próximo run com o hard do lineup (sonnet-5.5 xhigh) e `-ImplementMinutes 75` — dos cinco ❌ da revisão, quatro já estão vermelho→verde na branch e o último tem o teste vermelho committed; fatiar para destravar SYN-028/027 tiraria daqui a guarda de "o original de quem enviou nunca é tocado" e o teste da branch para ganhar menos de um ciclo (a rodada 2 fechou um a cada ~8 min).
  - **Para o stage 2 (retomada):**
    - Trabalhe na branch `syn-019` como está; não recrie a branch nem refaça os testes já committed. Prova da rodada 2, conferida pelo proxy nos logs locais de `.scratch/qa-runs/2026-10-02-syn-019-reopen/` (`gate.ps1 -TestsOnly`): `6078c66` → `817dfe8` (56 HTTP + 1 vermelho → 57), `7d8ae2e` → `475bcdf` (57 + 1 → 58), `27b3cfb` → `3adbe4a` (58 + 1 → 59), `db2bb99` → `16da8d5` (59 + 1 → 60); 4 unitários, 7 config e 5 outbox verdes em todas.
    - Comece pelo teste de `f2b2530`, `envio_concorrente_com_exclusao_preserva_original_ou_recusa_origem_ausente` (o ❌ "proteção concorrente de original" da revisão): rode `gate.ps1 -TestsOnly` em primeiro plano e registre o vermelho. Essa prova ainda não existe: o timeout matou a rodada na compilação (`send-race-red.log`). Se falhar só pelo harness, conserte em commit de teste.
    - Depois, a correção em commit só de código. A revisão aponta o formato: uma operação da Outbox que confere os originais e faz a ação no arquivo sob a mesma trava em que `enqueue_file_from_path` confere a origem e registra o caminho, usada pelos dois chamadores (`apply_delete` e `finish_received_file`); isso também tira a captura duplicada.
    - É o último ❌. Não abra ciclos novos para outros intervalos: um risco novo vai como nota aqui, para o stage 3. Verde → gate completo em primeiro plano → `to-review`.

- Stage 2, retomada final (2026-10-02): último ❌ ("proteção concorrente de original") fechado. Vermelho: `gate.ps1 -TestsOnly` em primeiro plano sobre `f2b2530` falhou só em `envio_concorrente_com_exclusao_preserva_original_ou_recusa_origem_ausente` (60 HTTP verdes, 1 vermelho: o envio aceito durante a exclusão perdeu o original, `NotFound` em `tests/server.rs:2202`); 4 unitários, 7 config e 5 outbox verdes. Verde: `2d6869a` (só código, `outbox.rs` e `server.rs`) passou os 61 HTTP. A correção é `Outbox::unless_original`, que confere o original e age no arquivo sob a trava em que `enqueue_file_from_path` registra a origem; `apply_delete` e `finish_received_file` a usam (a captura duplicada de `original_paths` saiu), e um envio cujo arquivo já foi reciclado devolve `NotFound`. A Lixeira roda com a trava da Outbox segurada; a ordem de locks é a de antes (`received_files` → Outbox) e nenhum ouvinte é chamado sob ela.
  - Gate completo, em primeiro plano: `gate: passou`. Formatação e Clippy verdes; Rust: 61 HTTP + 4 unitários + 7 config + 5 outbox; UI 47/47; kit QA 14/14; Gradle `BUILD SUCCESSFUL`.
  - Diff desde o último commit de teste (`f2b2530`): só `src/outbox.rs` e `src/server.rs`, nenhum arquivo de teste, tudo dentro dos Primary files.
