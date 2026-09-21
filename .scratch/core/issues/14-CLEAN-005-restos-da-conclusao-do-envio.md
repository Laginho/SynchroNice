# CLEAN-005: Restos do caminho de conclusão do envio de arquivo
Stage: to-review
Status: ready-for-agent
Blocked by: none
Review: agent

- Primary files:
  - pc/src-tauri/src/items.rs (`ItemBody::File`: `done: bool` vira
    `status: FileStatus`; `file_done` passa a pôr `Done` e ganha um irmão
    `file_failed`)
  - pc/src-tauri/src/server.rs (só `post_file` — o laço que grava o corpo e a
    tarefa de conclusão que faz o rename)
  - pc/src-tauri/tests/server.rs (os testes novos dos três casos abaixo, mais
    o helper `arquivos()` e o `if let ItemBody::File { done: true, .. }` do
    teste concorrente, que desestruturam o campo antigo)
  - pc/ui/main.ts (`FileItem.done` vira `status`; `atualizarProgresso` desenha
    o `failed`; o guard de drag no `mousedown` de `renderArquivo`)
  - pc/ui/styles.css (regra da bolha falha; `cursor: grab` só na terminada)
  - android/: nada. `ItemBody` não cruza a rede — vai do Rust para a janela do
    PC por evento Tauri (`lib.rs`, `ITEM_EVENT`) e por `list_items`. O que o
    celular lê em `/listen` é `OutBody` (`outbox.rs`), espelhado em
    `android/.../Item.kt`, e esse não muda.

#### What to build

CLEAN-001 fechou o buraco grande na conclusão de `POST /items/file`: o aviso
de fim não se perde mais quando a conexão cai logo depois do último byte.
Sobraram três restos no mesmo trecho, todos anteriores a CLEAN-001 e nenhum
coberto pelos critérios de lá. Nenhum derruba o gate hoje; os três aparecem
com o celular numa rede ruim, que é o cenário normal do app.

1. **Corpo chunked ainda pode perder a conclusão.** A correção de CLEAN-001
   põe rename + `file_done` numa `tokio::spawn`, e nada suspende entre o fim
   do corpo e esse `spawn` — mas só porque, com `Content-Length`, o
   `stream.next()` final devolve `None` sem ir à rede. Num corpo chunked (o
   caminho do cabeçalho `X-File-Size`, documentado no topo de `server.rs`), o
   `stream.next()` final precisa ler o chunk terminador: aí ele suspende, a
   future pode ser cancelada antes do `spawn`, e nem o rename nem o
   `file_done` acontecem.

2. **O `.part` fica no disco para sempre.** Se o rename falha, ou se a
   conexão cai no meio do corpo (o `return StatusCode::BAD_REQUEST` do laço),
   o temporário nunca é apagado e o Item nunca sai de "em andamento" — a
   bolha fica com a barra de progresso parada, sem jeito de saber que morreu.

3. **`drop(file)` sem `flush` antes do rename.** `tokio::fs::File` é
   bufferizado e a escrita vai para o pool de bloqueio; `write_all().await`
   devolve antes de os bytes chegarem ao disco. O `drop` não é ponto de
   sincronização — o rename é disparado sem garantia de que o último pedaço
   já foi gravado.

#### Acceptance criteria

1. Um envio com corpo chunked (sem `Content-Length`, com `X-File-Size`) cuja
   conexão cai logo depois do último byte termina com o arquivo no nome
   final e o `file_done` disparado — igual ao caso com `Content-Length`.
2. Um envio cortado no meio do corpo não deixa `.part` na Pasta.
3. Um rename que falha não deixa `.part` na Pasta.
4. Um Item cujo envio morreu (corte no meio do corpo, ou rename que falhou)
   sai de "em andamento": `ItemBody::File` troca `done: bool` por
   `status: FileStatus`, enum de três valores `Receiving | Done | Failed`,
   serializado em `snake_case` como o resto do shape (`"receiving"`,
   `"done"`, `"failed"`). `Items::start_file` nasce `Receiving`;
   `Items::file_done` põe `Done` depois do rename, como hoje; um
   `Items::file_failed(id)` novo põe `Failed` e avisa pelo mesmo `on_new`.
   Os dois só têm efeito sobre um Item ainda `Receiving` — estado terminal
   nunca é sobrescrito, para a limpeza de um envio cancelado e a tarefa de
   conclusão não disputarem. `Failed` sai em todo caminho de morte de
   `post_file`: `Err` do stream, `write_all` que falha, rename que falha, e
   a future cancelada sem chegar a nenhum deles (o mesmo caso do critério 1).
   `received` fica como estava, com quanto chegou. Na UI (`pc/ui/main.ts`),
   `FileItem.done` vira `status` com os mesmos três literais; uma bolha
   `failed` perde `.enviando` (a listra parada some) e a barra, ganha a
   classe `.falhou` com legenda no molde de `.sumiu` (ex. "o envio não
   chegou ao fim"), não carrega miniatura e não arrasta — o guard do
   `mousedown` e a regra `cursor: grab` hoje só olham `.enviando`.
5. Os bytes do corpo estão garantidamente no disco antes do rename.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

## Tests stage 2 writes (own commit, red)

- `pc/src-tauri/tests/server.rs`, na costura HTTP (o teste age como o
  celular, igual aos vizinhos): um envio chunked com a conexão cortada logo
  após o último byte — vermelho hoje porque o `file_done` não chega.
- `pc/src-tauri/tests/server.rs`: um envio cortado no meio — vermelho hoje
  porque sobra um `.part` na Pasta.
- `pc/src-tauri/tests/server.rs`: o mesmo envio cortado — vermelho hoje
  porque o Item fica `receiving` para sempre, sem nunca virar `failed`
  (antes disso, nem compila: `status` ainda não existe — como em SYN-003, o
  teste tem que afirmar o `failed`, não só compilar).

## Comments

- 2026-09-20 (revisão de CLEAN-001): aberto pela revisão. Os três achados
  são do trecho que CLEAN-001 tinha nos Primary files, mas nenhum cabia nos
  critérios de lá (cada um precisa de teste novo), então viraram ticket em
  vez de correção pequena. O `envio_interrompido_nao_deixa_arquivo_com_nome_final`
  que já existe cobre só o nome *final*, não o `.part`.
- 2026-09-20: `Status: needs-triage` de propósito — o critério 4 mexe no
  formato de `ItemBody::File`, que é contrato com a UI, e isso é decisão de
  stage 1 (manual neste repo), não de stage 2.

- 2026-09-20 Attempt 1 failed: exit 0. Log tail: CLEAN-005 is `Stage: to-implement` but `Status: needs-triage` — and its own Comments explain why: criterion 4 requires a stage-1 (human) decision on how `ItemBody::File` represents a dead send, since that struct is the contract with the Android UI. Today it's just `done: bool`. /  / Per this repo's rules, stage 1 is always manual (never automated, even under the sweatshop/foreman override), so stage 2 (`tdd`) can't pick this up until that's decided. I need your call before implementation starts: /  / **Question:** how should a dead/failed file transfer (chunked-cut-mid-body, or rename failure) be represented in `ItemBody::File`? Options on the table: / 1. Add a tri-state, e.g. `status: Pending | Done | Failed` (replacing `done: bool`). / 2. Keep `done: bool` and add a separate `failed: bool`. / 3. Something else you have in mind for the Android side. /  / Once you pick, I'll fold it into the ticket (Primary files + criterion 4 wording), flip `Status` to `ready-for-agent`, and dispatch stage 2. /

- 2026-09-20 (resposta do humano — stage 1): **opção 1, tri-state.**
  `done: bool` vira `status: FileStatus { Receiving, Done, Failed }` (JSON
  `"receiving" | "done" | "failed"`), dobrado no critério 4 e nos Primary
  files. Por quê: lendo `items.rs`, `lib.rs`, `outbox.rs` e
  `android/.../Item.kt`, `ItemBody::File` não é contrato com o Android — só
  vai do Rust para a janela do PC (evento Tauri e `list_items`); o que o
  celular lê em `/listen` é `OutBody`, outro tipo. Então trocar o shape custa
  três arquivos do PC mais os testes, e os três estados são uma dimensão só
  ("chegando" / "chegou" / "não vai chegar") — dois booleanos admitiriam
  `done && failed` e custariam o mesmo, porque o helper `arquivos()` dos
  testes desestrutura todos os campos de qualquer jeito. Nomes em inglês como
  os vizinhos (`name`, `size`, `received`); a questão "o shape fala
  português?" que a review de SYN-003 parqueou continua parqueada. Não é
  `Pending` porque **Pendente** no glossário é o Item parado na origem com o
  destino fora da rede — um arquivo cujos bytes já estão chegando não é isso.

- 2026-09-20 Review ended at to-review (exit 0); branch clean-005 holds the review; left for a human

- 2026-09-20 Foreman: o review anterior terminou sem veredito (exit 0 logo apos "Baseline round 1 green. Waiting for the remaining 11") -- ele gastou o orcamento produzindo medicao de instabilidade, que a Verification deste ticket nem pede. A medicao ja esta feita, fora da sessao de review, na propria branch `clean-005`: **12 rodadas de `gate.ps1 -TestsOnly`, 12 verdes, 0 vermelhas**, mais uma rodada do gate completo, verde. Nao rode as 12 de novo -- julgue o codigo e use esta evidencia.

- 2026-09-20 Review ended at to-review (exit 0); branch clean-005 holds the review; left for a human

- 2026-09-20 Foreman: terceiro review morreu esperando o gate. A ultima frase dele
  foi "The gate re-run is still in its cargo test link step. I'll pick this up when
  it completes" -- mas `claude -p` acaba junto com o turno, entao nao ha retomada.
  O AGENTS.md agora proibe rodar o gate em segundo plano. Para o proximo review,
  duas coisas ja estao feitas e **nao precisam ser refeitas**:
  - Gate na `clean-005`: 12 rodadas de `-TestsOnly`, 12 verdes, 0 vermelhas, mais
    o gate completo verde. Medido fora da sessao de review.
  - O review anterior chegou a um veredito e aplicou uma correcao que **se perdeu
    no rollback**, e que vale reaplicar: em `server.rs`, `FailGuard::drop` apaga o
    `.part` e notifica **antes** do handle do arquivo fechar, nos tres caminhos de
    morte. Passa hoje so porque o NTFS desta maquina tem delete com semantica
    POSIX; a Pasta e escolhida pelo usuario e pode estar em exFAT ou em share de
    rede, onde o nome sobrevive ate o ultimo handle fechar. Os dois eixos de
    review acharam isso de forma independente.
  Achados que o review anterior decidiu **nao** corrigir, para o bloco Resolution:
  o `break` em `received >= size` faz um `X-File-Size` subestimado virar arquivo
  truncado marcado `done` (consequencia do criterio 1 como aprovado, merece ticket
  proprio); criterio 3 vale por construcao mas nao tem teste, e o plano aprovado
  nao pediu; `CONTEXT.md` nao ganhou verbete para o terceiro estado do Item.
