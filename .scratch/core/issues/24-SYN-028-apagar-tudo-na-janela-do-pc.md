# SYN-028: Apagar tudo na janela do PC
Stage: to-review
Status: ready-for-agent
Blocked by: SYN-019
Review: agent
Difficulty: normal

- Primary files:
  - New: pc/ui/apagados.ts (ids excluídos, puro, sem DOM, no molde de `bandeja.ts`)
  - New: pc/ui/apagados.test.ts
  - pc/src-tauri/src/lib.rs (comando de Apagar tudo; evento para a janela ligado ao ouvinte de exclusão do SYN-019; `list_items` sem os Itens em exclusão diferida, por um helper com teste no `mod tests` que já existe). Sem mexer em `items.rs` nem na operação do SYN-019.
  - pc/ui/main.ts, pc/ui/styles.css, pc/index.html (🗑, aviso com Desfazer, bolhas sumindo)
  - gate.ps1 (roda `apagados.test.ts` junto com os outros testes de `pc/ui`)

#### What to build

O lado da janela do Apagar tudo do PC. O protocolo, a operação de Apagar tudo,
a Lixeira e o ouvinte de exclusão já existem: são do SYN-019. Leia as decisões
de lá antes de começar.

Decisões do stage 1 (2026-10-01; protótipo em
`prototype/syn-019-apagar-tudo`, veredito: o botão da variante B com o
desfazer da variante A):

- **Botão.** Um 🗑 no cabeçalho, à esquerda da ⚙, só visível com o chat não
  vazio.
- **Desfazer.** O clique esvazia o chat na hora e mostra um aviso embaixo,
  acima do composer: "Conversa apagada · N Itens e M arquivos", com
  "Desfazer" e uma barra que esvazia em 5 s. Durante a janela nada é apagado
  nem sai pela rede, então o desfazer só devolve as bolhas. Um segundo clique
  durante a janela fecha a anterior na hora e abre outra. Itens que chegam
  durante a janela ficam e não entram na exclusão. Sair pela bandeja durante a
  janela não apaga nada.
- **Fim da janela.** Um comando Tauri chama o Apagar tudo do SYN-019 com os
  Itens que a janela esvaziou: os recebidos pelo id de `Items`, os enviados
  pela chave da lista de enviados do BUG-003.
- **Do outro lado, sem aviso.** Uma exclusão vinda do celular tira as bolhas
  da janela sem rastro: o `lib.rs` liga o ouvinte de exclusão a um evento, como
  faz com `item` e `enviado`.
- O aviso divide o espaço acima do composer com a bandeja do SYN-020; quem
  entrar depois acomoda os dois.

#### Acceptance criteria

1. Na janela: o 🗑 só aparece com o chat não vazio; o clique esvazia o chat e
   mostra o aviso com as contagens; "Desfazer" em até 5 s devolve as bolhas e
   nenhum arquivo sai da Pasta; sem desfazer, os arquivos saem da Pasta e
   estão na Lixeira.
2. Um Item que chega durante a janela aparece no chat e continua lá depois que
   ela fecha.
3. Recarregar a janela depois do Apagar tudo não traz nada de volta.
4. Uma exclusão vinda do celular tira as bolhas daqueles Itens da janela, sem
   aviso.
5. Os testes existentes seguem verdes.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

QA no stage 3: kit no emulador, critérios 1 a 4 pelo CDP, com a Pasta e a
Lixeira conferidas pelo kit. Nos critérios 2 e 4, aja como o celular por HTTP
(token do `config.json` da execução): mande um texto durante a janela, e
mande `POST /items/delete` com o `X-Item-Id` de um Item recebido.

## Tests stage 2 writes (own commit, red)

- Primeira passagem: nenhum (a operação, a exclusão recebida e o ouvinte são
  testados no SYN-019; a cola e a interface ficaram para o QA).
- Reabertura (2026-10-02), provas de regressão dos três ❌ da revisão:
  - `pc/ui/apagados.test.ts` (novo), com `node --test`: um id marcado como
    excluído (fim da janela de Desfazer ou evento `apagado`) continua fora
    para sempre; um snapshot antigo com ele é filtrado; recebidos e enviados
    não se confundem (ids de espaços diferentes); o Desfazer não marca nada.
    Vermelho porque `apagados.ts` não existe. O `gate.ps1` passa a rodá-lo no
    mesmo commit.
  - Um teste no `mod tests` de `pc/src-tauri/src/lib.rs`, critério 3: um
    upload em `Receiving` passado ao `Items::forget` sai do que a janela
    recebe ao abrir, e um Item não selecionado continua. Chama o helper
    direto, sem `State`. Vermelho porque o helper não existe.
- Segunda reabertura (2026-10-02), provas dos dois ❌ da re-revisão, nas
  mesmas costuras (sem arquivo novo, sem `items.rs`):
  - `pc/ui/apagados.test.ts`: `ligarOuvintes(escutar, ouvintes)` com um
    `listen` falso que só vale depois de um turno e, a cada registro, deixa o
    backend mandar um Item (recebido e enviado) e excluí-lo logo em seguida:
    nenhuma bolha pode sobrar, qualquer que seja o ouvinte registrado primeiro;
    e cada evento chega ao seu tratador. Vermelho porque `ligarOuvintes` não
    existe em `apagados.ts`.
  - `mod tests` de `pc/src-tauri/src/lib.rs`: um upload selecionado que termina
    entre a listagem e a consulta da marca não volta no snapshot da janela
    (`visiveis(listar, selecionado)`, as duas operações injetadas para fixar
    o entrelaçamento). Vermelho porque `visiveis` não existe.

## Comments

- 2026-10-01 Proxy decided: nasceu do fatiamento do SYN-019 a pedido do dono. Veio para cá a janela do PC (critérios 10 e 11 do original, aqui 1 e 3), mais o comando Tauri e o evento de exclusão recebida no `lib.rs`; os critérios 2 e 4 deixam explícitas decisões que no original eram só prosa, para cada metade ter o seu lado verificável. Ficaram no SYN-019 o protocolo, o backend, a Lixeira e as docs. Blocked by: SYN-019, que dá a operação e o ouvinte que esta janela chama. Difficulty: normal: interface e cola fina, sem concorrência nem lógica de arquivo. Review: agent, porque as docs ficaram no SYN-019.
- 2026-10-02 stage 2: sem testes novos, como o ticket manda (cola Tauri e interface; operação, exclusão recebida e ouvinte são do SYN-019). Gate verde. Interface não exercida no app: fica para o QA do stage 3. Notas: durante a janela de Desfazer o `apagar_tudo` ainda não rodou, então recarregar a janela nesse intervalo devolve as bolhas (nada é apagado); o Desfazer refaz um snapshot (`list_items`/`list_sent_items`) para pôr em dia progresso e entregas ocorridos na janela.
- 2026-10-02 Proxy decided (após a revisão abaixo): aprovado o plano da reabertura: lápides permanentes de ids excluídos num módulo puro novo, `pc/ui/apagados.ts` (marcadas quando o timer fecha a janela, antes do `invoke`, e no `apagado`; o Desfazer nunca marca; render e snapshots, inicial e do Desfazer, passam por ele), mais um helper em `lib.rs` que tira de `list_items` os ids com `delete_requested`; testes vermelhos em `apagados.test.ts` e no `mod tests` de `lib.rs`, e o `gate.ps1` ganha o caminho — dobrado em Primary files e em Tests stage 2 writes. — Mesmo molde aprovado no SYN-020 (módulo puro + `node --test` + uma linha no gate), não toca a operação nem o `items.rs` do SYN-019 e a revisão exige as provas de regressão.

#### Review stage 3 (2026-10-02)

Verdict: Reopen — critérios 1, 3 e 4: eventos e snapshots restauram bolhas já selecionadas ou excluídas.

Diff completo `git diff 7e866dd...09228ef`, comparado à sessão
`sweatshop/2026-10-01-2019`, com os eixos Standards e Spec em subagentes
independentes. Rebase na sessão foi no-op. Todos os achados desta passagem
estão abaixo; nenhum código de produção foi corrigido pelo stage 3.

##### Standards

- ❌ **P1, eventos recriam a seleção durante a exclusão local.**
  `main.ts:662` elimina `janelaDeDesfazer` antes do comando assíncrono
  terminar. Progresso/ACK passa pelos guards em `519`/`625` e recria a
  bolha. O backend local chama `Items::forget` com `notify=false`
  (`server.rs:390,676-679`; `items.rs:263`); o ouvinte ligado em
  `lib.rs:415` não emite `apagado` para essa operação local. Contraria
  Engineering Workflow, quality gate 1 (comportamento solicitado e
  preservação de regressões), e o critério 1. **Reproduzido:** depois dos
  5 s, um chunk real de upload recriou a bolha enquanto o IPC de
  `apagar_tudo` estava retido; após liberar o comando e concluir o upload,
  HTTP 201, backend vazio e uma bolha ainda visível.
- ❌ **P1, snapshot antigo restaura exclusão recebida.**
  `main.ts:702` zera o estado global antes de aguardar os snapshots em
  `712-717`. O evento remove os mapas vivos, mas não a seleção local
  capturada nem os dados do snapshot. A carga inicial em `991-1004`
  também não conserva exclusões ocorridas depois da captura. Mesma regra
  de correção do quality gate 1; critério 4. **Reproduzido duas vezes no
  Desfazer:** reter a resposta real de `list_sent_items`, excluir por HTTP,
  observar zero bolhas e backend vazio, liberar a resposta: uma bolha volta.
- ✅ Sem smells acionáveis ou violação de escopo/test-first. `013433b`
  registra a dispensa expressa de testes novos; `09228ef` muda somente
  os quatro Primary files de produção e o ticket.

##### Spec

- ❌ **P2, critério 1:** “o clique esvazia o chat”. A seleção deixa de
  bloquear eventos quando o timer expira, embora o comando ainda não tenha
  sido aplicado. Falta conservar a seleção durante a aplicação e reconciliar
  o resultado. O caso de progresso acima confirmou a bolha persistente após
  a exclusão bem-sucedida; ACK tem o mesmo guard desarmado.
- ❌ **P2, critério 3:** “Recarregar a janela depois do Apagar tudo não
  traz nada de volta”. O SYN-019 conserva um upload selecionado em `Items`
  até terminar/falhar (`items.rs:248-279`). O snapshot da janela em
  `lib.rs:87-95` o devolve sem filtrar a exclusão, e `main.ts:1003` desenha
  a bolha no reload. **Reproduzido:** expirar os 5 s, confirmar a operação
  com um comando Tauri real e idempotente, recarregar com o upload ainda
  recebendo e concluí-lo. HTTP 201 e backend vazio; a bolha continua
  mostrando 64 de 192 KiB. Falta adaptar a cola/snapshot ao descarte
  diferido já existente, sem mudar a operação do SYN-019.
- ❌ **P2, critério 4:** “Uma exclusão vinda do celular tira as bolhas
  daqueles Itens da janela, sem aviso”. Falta conservar ids excluídos
  durante a reconciliação de snapshots, tanto no Desfazer quanto ao abrir.
  A corrida do Desfazer foi reproduzida duas vezes pelo IPC real; a variante
  da carga inicial foi conferida estaticamente.
- ✅ Critério 2 e fluxo comum de 1/3/4 passaram no executável. Desfazer
  restaura três bolhas e preserva o arquivo; expiração recicla; chegada
  durante a janela permanece; segundo clique finaliza só a seleção anterior;
  exclusão recebida comum remove a bolha sem aviso. Critério 5 verde.

**Validação:** gate completo em primeiro plano, exit 0, `gate: passou`.
Rust: 63 HTTP + 5 unitários + 7 config + 5 outbox = 80; UI 55/55;
kit QA 14/14; fmt, Clippy e TypeScript verdes. Android `check` verde,
testes `UP-TO-DATE`; XMLs com 71 do app e 72 do cliente, sem falhas,
erros ou pulos. Essa suíte não cobre os três casos de UI reproduzidos.

**QA:** [relatório do emulador](../../qa-runs/2026-10-02-syn-028-review.md).
PC debug da branch, emulador pareado, HTTP e CDP reais; o CDP apenas
retém/libera pedidos ou respostas reais para controlar os interleavings.
Dois arquivos da execução confirmados na Lixeira, com origem e SHA-256
idênticos; Pasta vazia, arquivos anteriores do emulador preservados,
`clean` e `down` concluídos. Sem débito humano neste recorte.

**Decisão do proxy conferida:** a única linha `2026-10-01 Proxy decided`
fixa o fatiamento: backend/protocolo/Lixeira no SYN-019 e cola/janela aqui,
com os critérios 2 e 4 explícitos, `Review: agent` e dependência SYN-019.
A revisão respeita esse recorte e não mudou critérios ou decisões.

**Para o stage 2:** fechar os três ❌ e produzir as provas de regressão
de evento durante aplicação, reload de upload selecionado e snapshot
concomitante à exclusão. Os testes do SYN-019 não exercitam esses casos da
janela; a correção precisa de verificação nova e volta ao stage 2 conforme
o ticket-flow. Se a solução exigir ampliar a costura de testes ou o escopo
explicitado de `lib.rs`, consultar stage 1/proxy antes de implementar,
sem reinterpretar os critérios. Preservar os fluxos comuns aprovados e
rodar novamente o gate completo e o QA.

`Stage: to-implement` e esta revisão são committed na branch `syn-028`,
sem merge/push/PR individual. Não havia linha SYN-028 no ledger a retirar.

Resumo por eixo: Standards, 2 falhas de correção (pior: seleção deixa de
proteger o chat antes da aplicação); Spec, 3 falhas (pior: reload mantém
bolha de upload excluído mesmo depois de terminar).

#### Stage 2, reabertura (2026-10-02)

Provas vermelhas em `d4d3d47` (test-only: `apagados.test.ts` sem o módulo,
dois testes de `recebidos_visiveis` sem a função; `gate.ps1` ganhou o
caminho do teste). Correção sem tocar em teste:

- `pc/ui/apagados.ts`: lápide definitiva de ids excluídos (recebidos e
  enviados separados). `main.ts` marca antes do `invoke("apagar_tudo")` (também
  no segundo clique), em `removerExcluidos` (exclusão do celular), e filtra
  `renderRecebido`/`renderEnviado`, o snapshot do Desfazer e a carga inicial
  por ela. Fecha os ❌ de progresso/ACK pós-5 s e de snapshot atrasado.
- `lib.rs`: `list_items` passa por `recebidos_visiveis`, que esconde o upload
  já selecionado (`Items::delete_requested`) e ainda não descartado. Fecha o
  ❌ do reload (critério 3).
- Não toquei `items.rs` nem a operação do SYN-019. Gate completo verde
  (Rust 7 unitários novos incluídos, 63 HTTP; UI e QA verdes).
- Limite conhecido, o mesmo da primeira passagem: recarregar a janela *dentro*
  dos 5 s devolve as bolhas, pois nada foi apagado ainda. QA no stage 3.

#### Review stage 3, reabertura (2026-10-02)

Verdict: Reopen — critérios 3 e 4: snapshot de upload concluído entre leituras e exclusão perdida antes do registro de `apagado`.

Re-revisão dos ❌ anteriores e do diff `git diff 09228ef...386f452`,
com Standards e Spec em subagentes independentes. Base do loop:
`sweatshop/2026-10-01-2019` (`7e866dd`); rebase no-op. Conferidos
também o diff completo desde a base, os commits e as duas decisões do
proxy. Nenhuma correção de produção ou teste foi feita pelo stage 3.

##### Standards

- ❌ **P2 — snapshot e visibilidade não são consistentes.**
  `lib.rs:87-92` chama `Items::snapshot()` e depois
  `Items::delete_requested()` sob locks separados. O upload selecionado
  pode estar na cópia; antes do filtro, `file_done` ou `file_failed`
  chama `Inner::finish_delete` (`items.rs:88-95,201,224`), removendo
  tanto o Item quanto a marca. O filtro vê `false` e devolve a cópia
  antiga. Viola Engineering Workflow, quality gate 1, e deixa parcial
  o ❌ do critério 3. Achado no helper novo, não omissão da passagem
  anterior. Os dois testes novos só verificam o upload que continua em
  `Receiving` durante a consulta. **Evidência estática**; não atribuir
  a esse caso o resultado do cenário dinâmico estável de reload.
- ✅ A lápide marcada antes do IPC (`main.ts:671`) continua bloqueando
  progresso/ACK após os 5 s (`520/626`). QA confirmou que o chunk real
  durante o IPC retido e o término posterior não recriam a bolha.
- ✅ A exclusão observada marca ids em `731`; filtros do Desfazer e
  carga inicial, mais os guards, bloqueiam snapshots atrasados. Ambos
  os caminhos passaram no CDP com respostas reais retidas.
- ✅ Primary files e test-first respeitados: `d4d3d47` só testes e sua
  inclusão no gate; `386f452` só produção/ticket, sem tocar testes.
  `items.rs` e a operação do SYN-019 não foram alterados. Nenhum smell
  acionável adicional.

##### Spec

- ❌ **P2 — critério 3:** “Recarregar a janela depois do Apagar tudo
  não traz nada de volta”. O interleaving do helper descrito acima
  ainda pode devolver o upload excluído depois da aplicação. Ao
  recarregar, `Apagados` começa vazio, e a exclusão local usa
  `notify=false` (`server.rs:390,676-679`); nenhum `apagado` posterior
  corrige a bolha antiga. O caso estável original foi corrigido e
  passou no executável; falta fechar a conclusão concomitante à
  consulta. Encontrado no código novo.
- ❌ **P2 — critério 4, omissão da primeira passagem:** “Uma exclusão
  vinda do celular tira as bolhas daqueles Itens da janela, sem
  aviso”. `main.ts:974-978` registra `item` e `enviado` antes de
  `apagado`, com `await` entre eles. Um Item recebido nessa janela
  cria bolha; sua exclusão antes do registro de `apagado` se perde.
  O snapshot posterior vem vazio, mas `1012-1013` apenas adicionam
  ou atualizam, sem retirar a bolha antiga. **Reproduzido duas vezes
  no app:** reter só o pedido real de registro de `apagado`, enviar
  e excluir por HTTP, liberar o registro e conferir o snapshot.
  Backend vazio e uma bolha residual após duas leituras em cada
  execução. Este código já estava em `09228ef`; a primeira revisão
  deixou escapar essa variante, agora explicitamente registrada.
- ✅ Critérios 1 e 2 e os cenários anteriores passam: Desfazer mantém
  arquivo e restaura três bolhas; expiração recicla; segundo clique
  finaliza só a seleção anterior; chegada posterior permanece;
  progresso pós-5 s não recria; reload estável de upload selecionado
  fica vazio; exclusão comum e snapshots atrasados observados são
  respeitados. Critério 5 verde. Nenhum escopo indevido identificado.

**Prova vermelho→verde:** registro real do stage 2 conferido:
`d4d3d47` falha com `E0425` para o helper e
`ERR_MODULE_NOT_FOUND` para `apagados.ts`; `386f452` passa no gate
atual sem editar testes. Extrato `stage2-red.log` no RunDir do QA.
Essa prova cobre as costuras declaradas; não cobre os dois interleavings
que ainda falham na revisão.

**Validação:** gate completo em primeiro plano, exit 0, `gate: passou`.
Rust 82/82 (7 unitários + 7 config + 5 outbox + 63 HTTP); UI 58/58;
kit QA 14/14; fmt, Clippy e TypeScript verdes. Android `check` verde;
XMLs `UP-TO-DATE` com 71 testes do app e 72 do cliente, sem
falhas/erros/pulos. O build de QA compilou APK e PC debug da branch.

**QA:** [relatório da re-revisão](../../qa-runs/2026-10-02-syn-028-rereview.md).
Critérios 1 a 4 por HTTP/CDP reais, com a exceção explicitada da corrida
interna do Rust, analisada estaticamente. Pasta vazia; arquivo na Lixeira
com origem e SHA-256 conferidos; baseline do emulador preservada;
`clean` e `down` concluídos. Sem débito humano neste recorte.

**Decisões do proxy conferidas:** (1) `2026-10-01 Proxy decided` mantém
o fatiamento: backend/protocolo/Lixeira no SYN-019, cola/janela aqui,
critérios 2 e 4 explícitos e `Review: agent`; (2) `2026-10-02 Proxy
decided (após a revisão abaixo)` autoriza as lápides em módulo puro,
os testes próprios, o caminho no gate e o helper de `list_items`,
sem tocar em `items.rs`. Ambas foram respeitadas. Nenhum critério ou
decisão foi reescrito por esta revisão.

**Para o stage 2:** fechar os dois ❌ numa passagem, preservando todos
os casos aprovados. Acrescentar provas de regressão da finalização
concomitante ao snapshot e da exclusão antes do registro do ouvinte,
nas costuras permitidas, com commit próprio vermelho antes do código.
Se a solução exigir outra costura ou alterar `items.rs`, consultar
stage 1/proxy antes de ampliar Primary files. Rodar o gate e repetir
o QA da abertura e os cenários comuns.

`Stage: to-implement` e esta revisão são committed em `syn-028`,
sem merge, push ou PR individual. Não havia linha SYN-028 no ledger
a retirar. Resumo por eixo: Standards, **1 bloqueador** (snapshot
inconsistente); Spec, **2 achados** (snapshot e exclusão perdida na
abertura). São dois problemas distintos; o de snapshot consta dos
dois eixos.

#### Stage 2, segunda reabertura (2026-10-02)

Provas vermelhas em `2905b50` (test-only; `E0425` para `visiveis` e
`SyntaxError` por `ligarOuvintes` ausente de `apagados.ts`). Correção sem tocar
em teste, nos Primary files, sem `items.rs`:

- `lib.rs`: `recebidos_visiveis` passa por `visiveis(listar, selecionado)`, que
  consulta a marca de exclusão dos ids de uma primeira listagem e só então lista
  de novo, devolvendo a segunda sem os marcados. Quem foi marcado e ainda existe
  na segunda listagem continua escondido; quem terminou entre as duas já não
  está nela (ids não se repetem, e `finish_delete` tira o Item e a marca juntos).
  Fecha o ❌ de critério 3. Item selecionado depois da consulta, por exclusão
  concorrente do celular, aparece e sai pelo `apagado` quando o upload termina,
  como já era.
- `apagados.ts` + `main.ts`: `ligarOuvintes` registra `apagado` antes de `item`
  e `enviado` (cada `listen` é assíncrono), e `main()` passa a usá-lo. Um Item
  que chega e é excluído entre dois registros não cria bolha sem ouvinte para
  removê-la; o que chegou antes do registro de `item` vem no snapshot, já
  filtrado pelas lápides. Fecha o ❌ de critério 4. É o código de ligação dos
  eventos de exclusão, por isso mora no módulo das lápides.
- Mutantes: com `visiveis` voltando a uma listagem só, o teste do Rust cai; com
  a ordem antiga de registro (`item`, `enviado`, `apagado`), o primeiro teste de
  `ligarOuvintes` cai. Restaurados. Gate completo verde: Rust 8 unitários + 7
  config + 5 outbox + 63 HTTP = 83; UI 60/60; QA 14/14; fmt, Clippy e `tsc`.
- Visto de passagem, sem ação: `server.rs:649-651` tem o mesmo par
  listar-e-consultar, mas roda sob o lock `file_ops` que serializa a conclusão
  do upload (comentário em `636-638`), então não corre.
- Interface não exercida no app por este stage: o QA dos critérios 3 e 4 com
  respostas retidas fica para o stage 3. Limite conhecido, o mesmo de antes:
  recarregar a janela *dentro* dos 5 s devolve as bolhas, pois nada foi apagado.
