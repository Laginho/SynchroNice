# SYN-004: PC envia texto e arquivos
Stage: to-implement
Status: ready-for-agent
Blocked by: SYN-002
Review: agent

- Primary files:
  - pc/src-tauri/src/** (fila de saída persistida, rota de escuta para o cliente, rota de download, presença, leitura de clipboard)
  - pc/src-tauri/tests/server.rs
  - pc/src-tauri/Cargo.toml (clipboard de arquivos do Windows)
  - pc/ui/** (composer, drop, Ctrl+V, bolha à direita com estado Pendente/Entregue, indicador de presença)

#### What to build

O PC manda coisas ao celular. Enter envia o texto do composer. Arrastar um
arquivo do Explorer para a janela envia o arquivo. Ctrl+V com imagem no
clipboard envia um PNG; Ctrl+V com arquivos copiados no Explorer envia cada
um. Sem botão de anexar. Cada envio vira bolha à direita, Pendente (opacidade
reduzida, indicador girando) até um cliente pareado buscar o Item, quando
passa a Entregue com hora.

Direção PC para celular: o cliente mantém uma conexão de escuta (long-poll ou
similar) enquanto está aberto e recebe os Itens anunciados; arquivo ele baixa
por uma rota própria. A conexão de escuta ativa define presença: o chat mostra
se o outro lado está na rede. A fila de saída é gravada em disco e sobrevive a
sair e reabrir o app; texto em linha, arquivo por caminho.

#### Acceptance criteria

1. Enter com texto no composer cria bolha à direita, Pendente; sem cliente conectado ela fica Pendente indefinidamente.
2. Um cliente que abre a conexão de escuta recebe todos os Pendentes em ordem, cada um passa a Entregue no chat.
3. Arrastar um arquivo do Explorer para a janela cria bolha de arquivo à direita, Pendente.
4. Ctrl+V com um print no clipboard cria bolha de imagem à direita.
5. Ctrl+V com um arquivo copiado no Explorer cria bolha de arquivo à direita; dois arquivos copiados criam duas bolhas.
6. Não existe botão de anexar na interface.
7. Sair pela bandeja e reabrir: os Pendentes continuam na fila e são entregues quando um cliente conectar (o chat visível está vazio, a fila não).
8. O indicador de presença mostra "na rede" enquanto há conexão de escuta ativa e "fora" caso contrário.

#### Verification

    curl -N http://localhost:<porta>/listen -H "Authorization: Bearer <token>"
    powershell -ExecutionPolicy Bypass -File .\gate.ps1

Critérios 3, 4, 5, 6 e 8: manual.

## Tests stage 2 writes (own commit, red)

- `pc/src-tauri/tests/server.rs`: enfileirar texto sem cliente deixa o Item Pendente; abrir a escuta entrega os Pendentes em ordem e eles ficam Entregues; enfileirar arquivo e baixar pela rota devolve bytes idênticos; fila gravada em disco sobrevive a recriar o servidor a partir do mesmo diretório. Vermelho porque fila, escuta e download não existem.

## Comments

- 2026-09-20 (revisão de SYN-002): **o id de Item ainda é do destinatário, e
  este ticket é quem precisa consertar isso.** A spec diz, em "Protocolo de
  Itens": "Cada Item leva um id gerado pelo remetente; o destinatário responde
  confirmando o id e ignora id já recebido. Só a confirmação remove o Item da
  fila de Pendentes, então reenvio nunca duplica bolha nem arquivo." Hoje
  `Items::push_text` (`pc/src-tauri/src/items.rs`) cunha `inner.items.len() + 1`
  e `POST /items/text` responde 201 sem corpo, de propósito — o cabeçalho de
  `pc/src-tauri/src/server.rs` explica que nenhum cliente deve nascer
  dependendo de um id do PC. Consequência para este ticket: o `Item.id` de
  hoje é chave de render local, não id de protocolo; a confirmação e a
  deduplicação por id não existem em lugar nenhum e são pré-requisito da fila
  de Pendentes do critério 1. Ao implementar, o id do remetente entra na
  requisição (e no anúncio da rota de escuta) e o campo local ganha outro
  nome, para os dois não se confundirem.
