# SYN-019: Apagar tudo nos dois aparelhos (PC e protocolo)
Stage: to-implement
Status: ready-for-agent
Blocked by: BUG-003
Review: human
Difficulty: hard

- Primary files:
  - pc/src-tauri/src/server.rs (rota `POST /items/delete`, linha `delete` no `/listen`, contrato no cabeçalho)
  - pc/src-tauri/src/outbox.rs (corpo `apagar` na fila; tirar Pendentes da fila)
  - pc/src-tauri/src/items.rs (id do remetente no Item recebido; esquecer Itens)
  - pc/src-tauri/src/lib.rs (comando de Apagar tudo, Lixeira)
  - pc/src-tauri/Cargo.toml (crate `trash`)
  - pc/ui/main.ts, pc/ui/styles.css, pc/index.html (🗑 e o aviso com Desfazer)
  - pc/src-tauri/tests/server.rs
  - docs/adr/0002-par-persistente-sessao-descartavel.md, CONTEXT.md (o app passa a apagar o que ele mesmo gravou)

#### What to build

Pedido no walkthrough de QA (2026-09-30): tirar uma foto de teste e apagá-la
dos dois aparelhos pelo próprio app, sem ir à Pasta. Como a Sessão é
descartável, a resposta é um **Apagar tudo**: limpa a conversa inteira nos
dois aparelhos, com um desfazer de alguns segundos. Não há exclusão de um Item
só. Este ticket faz o lado do PC e define o protocolo; o lado do celular é o
SYN-027.

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
- **Fim da janela, no PC:**
  - os enviados ainda Pendentes da lista saem da fila;
  - os arquivos que o PC gravou ao receber aqueles Itens vão para a Lixeira,
    e as cópias de print do BUG-003 são apagadas. O original de quem enviou
    nunca é tocado;
  - os Itens saem da Sessão, recebidos e enviados, para o recarregar não os
    trazer de volta;
  - uma exclusão com os ids de todos os Itens apagados entra na fila para o
    celular.
- **Do outro lado, sem aviso.** As bolhas somem sem rastro.
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
    desconhecido). Só acontece se alguém clicar no 🗑.
- **Exclusão recebida pelo PC.** Faz o mesmo que o fim da janela, menos a
  exclusão de volta. Um Item ainda recebendo é apagado quando o upload termina
  ou falha. Uma exclusão que chega depois que a Sessão acabou é ignorada, e o
  arquivo fica: não há estado persistente novo (id → arquivo).
- **Segurança antes de apagar.** O PC só apaga o caminho que ele gravou para
  aquele Item, se o tamanho ainda é o do Item e nenhum Item mais novo da Sessão
  usa o mesmo caminho. Na dúvida, não apaga. Arquivo que não existe mais é
  ignorado; o Item sai da Sessão mesmo assim.
- **Lixeira** pelo crate `trash`.
- **Docs.** A ADR-0002 e a definição de Pasta no `CONTEXT.md` ganham a exceção:
  o app só apaga arquivos que ele mesmo gravou, e só pelo Apagar tudo. A spec
  core já tirou "apagar" do Out of Scope.

#### Acceptance criteria

1. `POST /items/delete` com o `X-Item-Id` de um texto recebido em `do_celular`
   responde 204 e tira o Item da lista de Itens.
2. Com o id de um arquivo recebido, o arquivo sai da Pasta e o Item sai da
   lista.
3. Ids desconhecidos, dos dois lados, respondem 204 e não mudam nada; repetir a
   mesma exclusão também.
4. `do_pc` com o id de um enviado ainda Pendente tira-o da fila:
   `GET /outbox/{id}/file` responde 404, e ele não sai mais no `/listen`.
5. Um arquivo da Pasta com tamanho diferente do Item não é apagado; o Item sai
   da lista mesmo assim.
6. Uma exclusão que chega com o arquivo ainda recebendo é aplicada quando o
   upload termina: o arquivo não fica na Pasta.
7. O Apagar tudo do PC (a operação que o comando chama no fim da janela)
   enfileira uma exclusão que sai no `/listen` como linha `kind:"delete"`, com
   `do_pc` e `do_celular` de todos os Itens apagados, e sai da fila com o ack.
8. O mesmo Apagar tudo tira da fila os enviados Pendentes e tira da Pasta os
   arquivos recebidos.
9. A exclusão enfileirada nunca dispara o evento `enviado`.
10. Na janela: o 🗑 só aparece com o chat não vazio; o clique esvazia o chat e
    mostra o aviso com as contagens; "Desfazer" em até 5 s devolve as bolhas e
    nenhum arquivo sai da Pasta; sem desfazer, os arquivos saem da Pasta e
    estão na Lixeira.
11. Recarregar a janela depois do Apagar tudo não traz nada de volta.
12. Os testes existentes seguem verdes.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

QA no stage 3: kit no emulador, critérios 10 e 11 pelo CDP, com a Pasta e a
Lixeira conferidas pelo kit.

## Tests stage 2 writes (own commit, red)

- `pc/src-tauri/tests/server.rs`, na costura HTTP de sempre (o teste age como
  o celular), critérios 1 a 9. Vermelho porque a rota, o corpo `apagar` e a
  operação de Apagar tudo não existem.

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
