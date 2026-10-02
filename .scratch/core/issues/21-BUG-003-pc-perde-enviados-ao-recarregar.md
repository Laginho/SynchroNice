# BUG-003: O PC perde os enviados ao recarregar, e a bolha enviada não abre nem arrasta
Stage: implementing
Status: ready-for-agent
Blocked by: none
Review: agent
Difficulty: normal

- Primary files:
  - pc/src-tauri/src/outbox.rs (lista dos enviados da Sessão e arquivo de cada um)
  - pc/src-tauri/src/lib.rs (`send_text`, `send_files`, `send_bytes`; comandos de listar, abrir, arrastar e ler bytes da enviada)
  - pc/ui/main.ts (carga inicial, `renderEnviado`, `renderArquivoEnviado`)
  - pc/ui/styles.css (cursor e miniatura da bolha enviada)
  - New: pc/src-tauri/tests/outbox.rs

#### What to build

Achado do walkthrough de QA (2026-09-30). Dois sintomas, uma causa: o backend
não lembra o que o PC mandou.

1. Recarregar a janela apaga as bolhas enviadas pelo PC; as recebidas voltam.
   O `main.ts` só relista os recebidos (`list_items`), e a bolha enviada só
   nasce do evento `enviado`. A Sessão é a vida do processo (ADR-0002), e
   recarregar não a encerra.
2. A bolha de arquivo enviado pelo PC não tem miniatura, não abre com clique e
   não arrasta. A recebida faz as três coisas desde a3f6618. Depois do ack,
   `Outbox::confirm` apaga a cópia, e o PC só guarda o nome do arquivo.

Decisões do stage 1 (2026-10-01):

- **Lista dos enviados.** O backend guarda, em memória, os enviados desta vida
  do processo, Pendentes e Entregues, com o arquivo de cada um. A UI relista os
  dois lados ao carregar, em ordem de `at_ms` (no empate, a ordem de inserção).
  Pendentes herdados de um processo anterior continuam fora do chat, como hoje.
  O recarregar continua ligado.
- **O arquivo da enviada é o caminho original**, o do drop ou da lista do
  Explorer. Nada é copiado a mais.
- **O print colado não tem original.** Ganha uma cópia própria, com o nome do
  Item, na pasta de dados do app. A cópia vive até a Sessão acabar, e a pasta
  é varrida quando a fila é criada, porque o processo pode morrer sem limpar.
- **A bolha enviada de arquivo se comporta como a recebida:** miniatura se for
  imagem, clique abre (com o guard de executável), arrastar leva o arquivo e
  liga `arrastandoDaBolha`. Vale desde Pendente. O arquivo aberto ou arrastado
  tem o nome do Item, nunca `<id>.bin`.
- **Original que sumiu** (movido ou apagado): a bolha fica. Cada tentativa
  consulta de novo e marca ou desmarca `sumiu`, reusando
  `marcarArquivoSumido`/`desmarcarArquivoSumido`. A legenda não fala em "Pasta"
  quando o arquivo não é da Pasta.
- Os ids de `Items` e de `OutItem` são sequências diferentes e colidem: os
  comandos da enviada têm chave própria.

#### Acceptance criteria

1. Um arquivo enfileirado com caminho de origem aparece na lista de enviados da
   Sessão, Pendente e com esse caminho; depois do ack continua listado,
   Entregue e com o mesmo caminho.
2. Um texto enfileirado aparece na lista, Pendente, e depois do ack, Entregue.
3. Bytes enfileirados sem caminho (o print colado) geram uma cópia com o nome
   do Item, que continua existindo depois do ack.
4. Uma cópia de print deixada por um processo anterior é apagada quando a fila
   é criada.
5. Pendentes carregados do manifesto de um processo anterior não entram na
   lista de enviados da Sessão.
6. Recarregar a janela mostra as enviadas e as recebidas em ordem de `at_ms`,
   cada enviada com seu estado (Pendente ou Entregue).
7. A bolha de imagem enviada mostra miniatura; clicar numa enviada de arquivo
   abre o arquivo; arrastar leva o arquivo com o nome do Item.
8. Com o original apagado depois do envio, clicar na bolha a marca como sumida,
   sem erro; com o arquivo de volta no lugar, o próximo clique abre.
9. Os testes existentes seguem verdes.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

QA no stage 3: kit no emulador, critérios 6 a 8 (o recarregar e o DOM pelo
CDP). Arrastar para fora da janela continua 👤.

## Tests stage 2 writes (own commit, red)

- `pc/src-tauri/tests/outbox.rs` (novo), na API pública de `pc_lib::outbox`,
  critérios 1 a 5. Vermelho porque a fila não guarda a origem, não lista os
  confirmados, e `confirm` apaga a única cópia.

## Comments

- Stage 2 (2026-10-01): chamadores conferidos: comandos de envio em `lib.rs`,
  download/ACK/escuta no servidor e `Par::rotate` (`clear`). Primeiro vermelho:
  `gate.ps1 -TestsOnly` sai 1, com E0599 nas APIs de origem/lista/caminho da
  Sessão. O teste cobre ACK repetido, origem apagada/restaurada e cópia de
  entrega independente da origem. UI 47/47, kit 14/14 e Android verdes.

- Stage 1 (2026-10-01), grilling com o dono e o `proxy`. O dono escolheu o
  caminho original, com cópia só para o print colado, em vez de uma cópia de
  tudo em `Pasta\Enviados` ou de manter a cópia da fila até o fim da Sessão.
  O `proxy` decidiu o resto. Paridade no Android fica fora: lá a imagem enviada
  já abre, e o PDF enviado não; não há ticket.
