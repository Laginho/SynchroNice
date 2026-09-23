# CLEAN-005: Restos do caminho de conclusão do envio de arquivo
Stage: done
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

- 2026-09-20 Débito humano: a bolha `.falhou` nunca foi vista rodando no app de
  verdade. O que dá para provar sem olhar está provado na costura HTTP (o Item
  vira `failed`, o `.part` some); o que ficou sem verificação manual é o
  desenho: se a legenda "o envio não chegou ao fim" cabe na bolha, se o
  `opacity: 0.5` herdado de `.sumiu` lê como "morreu" e não como "carregando",
  e se a barra sumindo no meio do progresso não pisca feio. `pc/ui` não tem
  runner de teste — o gate só roda `tsc --noEmit` nele.

- 2026-09-20 Verbete do glossário em aberto: `CONTEXT.md` descreve **Pendente**
  e **Entregue** como estados de Item, e agora existe um terceiro no PC
  (`FileStatus::Failed`, a bolha que não vai chegar) sem nome no glossário.
  Fora dos Primary files deste ticket e sem critério que o peça, então não foi
  tocado aqui: é edição de stage 1 em `CONTEXT.md`, uma linha.

#### Resolution (2026-09-20)

Verdict: Needs your call: a correção que apliquei muda um caminho observável
(um `File::create` que falha agora cria um Item marcado `failed`, antes não
criava Item nenhum), e a bolha `.falhou` do critério 4 nunca foi vista
rodando de verdade — ver o Débito humano acima.

**Critérios.** ✅ 1 a 5.

1. ✅ O laço sai em `received >= size` (com `size > 0`), antes de qualquer
   `await` que possa suspender, e flush/rename/`file_done` vão para uma
   `tokio::spawn` sem `await` no meio. Teste
   `envio_chunked_cortado_apos_ultimo_byte_ainda_termina`, que fala HTTP
   chunked cru num `TcpStream` e corta antes do chunk terminador.
2. ✅ `FailGuard` (RAII) apaga o `.part` em todo caminho de morte, inclusive
   a future cancelada. Teste `envio_cortado_no_meio_nao_deixa_part_na_pasta`.
3. ✅ Por construção: o guarda é movido para dentro da tarefa de conclusão,
   então o `Err` do rename também passa por ele. Sem teste próprio — o plano
   aprovado não pediu um, e forçar um rename a falhar exigiria costura nova.
4. ✅ `FileStatus { Receiving, Done, Failed }` em `snake_case`; `file_done` e
   `file_failed` só agem sobre `Receiving`, então a limpeza e a conclusão não
   disputam em nenhuma ordem. UI: `.falhou` perde `.enviando` e a barra,
   ganha a legenda, não carrega miniatura, não arrasta (`mousedown` e
   `cursor: grab`). Teste `envio_cortado_no_meio_marca_item_como_failed`.
5. ✅ `file.flush().await` antes do rename, dentro da tarefa.

**Correção aplicada na revisão** (`server.rs`, dentro dos Primary files, sem
teste novo — a diferença só aparece num sistema de arquivos que recusa apagar
arquivo aberto, que esta máquina não tem):

`FailGuard` era declarado *depois* do `tokio::fs::File`, e ordem de destruição
é a inversa da declaração — o `remove_file` do guarda corria com o handle do
`.part` ainda aberto, em todo caminho de morte do laço e no flush que falha
dentro da tarefa. Nesta máquina passa; numa Pasta em exFAT ou num share de
rede o critério 2 estaria valendo por acidente. Agora `fail_guard` é declarado
antes de `file` (e `file` é rebindado depois do guarda dentro da tarefa), então
o handle fecha primeiro. Efeito colateral desejado, e o motivo do "Needs your
call": um `File::create` que falha agora cria o Item e o marca `failed`, em vez
de não criar Item nenhum — é mais um caminho de morte de `post_file` coberto
pelo critério 4, mas é comportamento novo que ninguém pediu.

**Achados não corrigidos.**

- **`X-File-Size` mentiroso → CLEAN-006.** O `break` em `received >= size`
  confia num número que só o celular controla (no caminho chunked não há
  `Content-Length` para conferir): subestimado, o resto do corpo é descartado
  e um arquivo truncado é renomeado e marcado `Done`. Superestimado, o corpo
  acaba antes e cai no mesmo rename. Não é correção pequena — precisa de teste
  novo e de uma decisão de stage 1 sobre o que fazer com um `size` que mente.
- **Verbete do glossário** e **débito humano da bolha `.falhou`**: nos
  Comments acima.

**Arquivos.** `pc/src-tauri/src/items.rs`, `pc/src-tauri/src/server.rs`,
`pc/src-tauri/tests/server.rs`, `pc/ui/main.ts`, `pc/ui/styles.css` — todos
dentro dos Primary files. `android/` intocado, como o ticket previu.

**Prova red-green.** Separação por commit, que é o check que sustenta a regra:
`8e8d21f` (`test:`) mexe só em `pc/src-tauri/tests/server.rs` (+ a linha
`Stage:`), `9e4f54f` (`fix:`) mexe só em `src/` e `ui/`, em nenhum arquivo de
teste. O vermelho dos três testes novos é falha de compilação — `FileStatus`
não existia em `items.rs` —, e os testes afirmam `FileStatus::Done` /
`FileStatus::Failed`, não apenas tipam: um `status` que nascesse sempre
`Receiving` compilaria e ainda assim falharia. Mesmo precedente de SYN-003.

**Gate.** `powershell -ExecutionPolicy Bypass -File .\gate.ps1` na branch
mesclada, em primeiro plano: `cargo fmt --check`, `cargo clippy -D warnings`,
`cargo test` **22 passed; 0 failed** (as três novas incluídas), `tsc --noEmit`,
`gradle check` BUILD SUCCESSFUL — `gate: passou`. A instabilidade do teste
concorrente que CLEAN-001 deixou em observação foi medida fora desta sessão na
`clean-005`: 12 rodadas de `-TestsOnly`, 12 verdes, 0 vermelhas.

- 2026-09-23 (revisão de CLEAN-006): **o critério 1 daqui foi revertido de
  propósito por CLEAN-006.** Ele pedia que um corpo chunked cuja conexão cai
  logo depois do último byte terminasse em `Done` com o arquivo no nome final;
  CLEAN-006 mostrou que aceitar sem o chunk terminador contradiz a recusa de um
  corpo que não bate com o `X-File-Size`, porque o servidor não sabe distinguir
  um corpo completo de um prefixo. Hoje, chunked sem terminador é `Failed`, sem
  `.part` e sem nome final. A proteção contra cancelamento depois do último
  byte — que era o miolo do critério — continua viva no caminho
  `Content-Length`. Este ticket segue `done`; a linha fica aqui para o critério
  1 não ser lido como comportamento atual.
