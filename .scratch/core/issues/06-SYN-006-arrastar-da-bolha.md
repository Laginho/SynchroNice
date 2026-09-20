# SYN-006: Arrastar arquivo da bolha para outro programa
Stage: to-review
Status: ready-for-agent
Blocked by: SYN-003
Review: agent

- Primary files:
  - pc/src-tauri/src/** (comando de drag nativo com caminho na Pasta)
  - pc/src-tauri/Cargo.toml (plugin de drag)
  - pc/ui/** (iniciar drag a partir da bolha de arquivo)

#### What to build

Toda bolha de arquivo no PC, imagem ou não, pode ser arrastada para fora da
janela e solta em outro programa (Explorer, navegador, editor) como o arquivo
real que está na Pasta. Não precisa achar o arquivo na Pasta. Bolha Pendente
ou em progresso não arrasta.

#### Acceptance criteria

1. Arrastar uma bolha de imagem recebida e soltar numa pasta do Explorer copia o arquivo para lá.
2. Arrastar uma bolha de PDF e soltar no navegador abre o PDF.
3. Arrastar uma bolha de imagem e soltar num campo de anexo de site funciona como se viesse do Explorer.
4. Uma bolha com envio em progresso não inicia drag.
5. Se o arquivo foi removido da Pasta, o drag não acontece e a bolha indica que o arquivo não está mais lá.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

Todos os critérios são manuais; descrever cada um no Resolution.

## Tests stage 2 writes (own commit, red)

- Nenhum teste automático viável: o comportamento é integração com o shell do Windows. Se o comando Rust tiver lógica própria (por exemplo, recusar caminho fora da Pasta), um teste unitário para ela em `pc/src-tauri/tests/`.

## Comments

- 2026-09-20 (stage 2, notas para o revisor): **escopo restrito às bolhas
  recebidas** (`.item.file` sem `.mine`) — a bolha enviada (mine) não entra.
  Dois sinais no próprio ticket apontam pra isso: "Blocked by: SYN-003" (PC
  recebe) e não SYN-004 (PC envia), e os critérios 1 e 5 falam do arquivo
  "que está na Pasta" — só o recebido mora lá. O enviado vive em
  `outbox/{id}.bin` enquanto Pendente e é apagado no `ack` (`outbox.rs`,
  `confirm`); depois de Entregue não sobra nada no PC pra arrastar. "Bolha
  Pendente ou em progresso não arrasta" (What to build) cobre os dois casos
  de bolha inacabada — Pendente (enviada, `outbox`) e em progresso (recebida,
  `done: false`) — sem implicar que a enviada arrasta depois de acabar.

  Se a leitura certa fosse "toda bolha, enviada inclusa", este ticket
  precisaria também depender de SYN-004 e reter os bytes do enviado além da
  confirmação — mudança de contrato do Outbox que não cabe como achado
  pequeno da revisão; voltaria pra stage 1.

  Dependência nova: `tauri-plugin-drag` (crate `drag` por baixo, MIT/Apache,
  usa a API OLE de drag-and-drop do Windows — `DoDragDrop`/`IDataObject`/
  `IDropSource`, a mesma família que o Explorer usa). Não usei o pacote npm
  do plugin: a janela já só fala com o Rust por `invoke()` (nenhum outro
  plugin daqui tem contraparte JS instalada), então `main.ts` chama
  `plugin:drag|start_drag` direto pelo `invoke` de `@tauri-apps/api/core`
  que já é dependência, com um `Channel` só pra satisfazer a assinatura do
  comando (o resultado do drop não tem uso na UI ainda).

  `caminho_para_arrastar` (novo comando) resolve o nome pro caminho absoluto
  e recusa se o arquivo não existir mais — `drag::resolve` (`src/drag.rs`) é
  a única lógica própria, e a única testável automaticamente, como o
  ticket já antecipava. O resto (`start_drag` de fato) é chamada direta pro
  plugin, sem lógica própria pra testar.

  Um único "ícone" de arrastar para os dois casos: mando o próprio caminho
  do arquivo como `image`. Pra imagem funciona como preview real; pra PDF
  (ou qualquer outro tipo que o WIC não decodifica) a decodificação falha
  silenciosamente dentro do plugin (`get_drag_image` engole o erro) e o
  drag segue sem bitmap customizado — sem crash, sem erro visível, só sem
  preview bonito. Não fiz nada especial pra ícone de arquivo genérico.

  `capabilities/default.json` ganhou `drag:default` — sem isso o `invoke`
  do plugin cai com erro de permissão; não é um Primary file listado, mas é
  metade obrigatória de "plugin de drag" (a outra metade é o Cargo.toml).

  Os 5 critérios de aceite são só verificação manual (o próprio bloco de
  Verification já diz isso) — nenhum tenho como provar sozinho: preciso do
  app aberto, um celular mandando arquivo de verdade, e soltar em
  Explorer/navegador/campo de upload de um site real. Gate completo verde
  (fmt, clippy, cargo test com os 2 testes novos de `drag.rs`, tsc) é a
  garantia que dá pra dar de dentro daqui.

- 2026-09-20 (humano simulado, stage 3): nenhum dos 5 critérios foi
  exercitado à mão — sem app aberto, sem celular, sem Explorer/navegador de
  verdade. O que dá pra afirmar por inspeção do diff, do fonte dos crates
  (`drag 2.1.1` e `tauri-plugin-drag 2.1.1` no registro local) e do gate
  (`gate.ps1 -TestsOnly` rodado de novo aqui: 2 testes de `drag.rs` + 18 de
  `server.rs` verdes): (a) a cadeia de `invoke` está coerente — plugin
  registrado como `"drag"`, `drag:default` = `allow-start-drag`, os args
  `item: [caminho]`/`image: caminho`/`options: {mode: "copy"}`/`onEvent`
  casam com os `#[serde(untagged)]`/`camelCase` de `commands.rs`; (b) no
  Windows o plugin monta o `IDataObject` do próprio Shell
  (`SHCreateShellItemArrayFromIDLists` + `BindToHandler(BHID_DataObject)`)
  e chama `DoDragDrop` com `DROPEFFECT_COPY`, então Explorer, navegador e
  campo de upload recebem exatamente o que receberiam de um drag saído do
  Explorer — é o argumento pelos critérios 1-3, não uma prova; (c) critério
  4: o guard de `mousedown` checa `.enviando`, que só sai quando `done`,
  e `file_done` só dispara depois do `rename` para o nome final
  (`server.rs`); antes disso `resolve` também devolveria `None`, porque só
  existe o `.part`; (d) critério 5: `caminho_para_arrastar` falha se
  `is_file()` é falso, o `.catch` marca `.sumiu` + legenda e `start_drag`
  nunca é chamado — a metade Rust tem teste, a metade JS é só leitura; (e)
  o nome do Item é o `final_name` já com ` (1)`, então `resolve` acha o
  arquivo certo mesmo com nome repetido; (f) PDF como `image`: WIC falha em
  `read_path_to_hbitmap`, `.ok()` engole, drag segue sem preview, como o
  stage 2 descreveu.

  Dois achados para o revisor pesar, não verificados em execução: (1) o
  drag começa no `mousedown`, sem esperar movimento, e `DropSource::
  QueryContinueDrag` devolve `DRAGDROP_S_DROP` ao soltar o botão — um
  clique simples numa bolha recebida vira um drop em cima do próprio
  WebView (`dragDropEnabled` default true, `ligarDrop` escuta `drop`), o
  que tende a chamar `send_files` e mandar o arquivo de volta pro celular;
  (2) o `.catch` de `iniciarDrag` também pega falha do `start_drag` em si
  (OLE, canonicalize), e rotula a bolha como "não está mais na Pasta" sem
  ser o caso. Cosmético: `.item.file:not(.enviando) { cursor: grab }` pega
  a bolha enviada (`.mine`), que não arrasta.

  Débito humano: critério 1 — arrastar bolha de imagem recebida e soltar numa pasta do Explorer, conferir que copiou.
  Débito humano: critério 2 — arrastar bolha de PDF e soltar no navegador, conferir que abre.
  Débito humano: critério 3 — arrastar bolha de imagem para um campo de anexo de site real, conferir que anexa como do Explorer.
  Débito humano: critério 4 — com um arquivo grande chegando, tentar arrastar a bolha em progresso e conferir que nada começa (e que arrasta depois de concluir).
  Débito humano: critério 5 — apagar o arquivo da Pasta, tentar arrastar, conferir que não há drag e a legenda vira "o arquivo não está mais na Pasta".
  Débito humano: clicar (sem arrastar) numa bolha recebida e conferir se o arquivo NÃO é reenviado ao celular (achado 1 acima).
