# SYN-008: Android envia por Compartilhar
Stage: to-implement
Status: ready-for-agent
Blocked by: SYN-007, SYN-003
Review: agent

- Primary files:
  - android/** (intent filters SEND e SEND_MULTIPLE, serviço em primeiro plano, envio de arquivo em stream com progresso, fila de Pendentes em disco, bolhas de arquivo)

#### What to build

O caminho principal de entrada. "Compartilhar" em qualquer app do Android
lista o SynchroNice. Texto ou link compartilhado vira Item de texto e chega
no PC. Imagem ou arquivo compartilhado vira Item de arquivo e chega na Pasta
do PC. Vários arquivos compartilhados juntos viram Itens separados, nunca
agrupados. O envio roda num serviço em primeiro plano com notificação de
progresso, para continuar mesmo com o app fora de tela. Cada Item aparece no
chat do celular como bolha à direita com progresso animado, Pendente se o PC
não está na rede, e a fila Pendente é gravada em disco e drenada quando o PC
aparece. Compartilhar não abre o chat: mostra uma confirmação curta e pronto.

#### Acceptance criteria

1. Compartilhar uma foto da galeria para o SynchroNice a faz chegar na Pasta do PC e aparecer no chat do PC, sem abrir o chat do celular.
2. Compartilhar um link do navegador faz o texto chegar no PC.
3. Compartilhar 3 fotos de uma vez gera 3 bolhas separadas nos dois lados e 3 arquivos na Pasta.
4. Compartilhar um vídeo de mais de 500 MB mostra progresso na notificação e na bolha, e o arquivo chega íntegro mesmo com o app fora de tela durante o envio.
5. Compartilhar com o PC fora da rede deixa os Itens Pendentes; ao voltar para a rede do PC eles são entregues sem ação do usuário.
6. Fechar o app e reabrir mantém os Pendentes na fila (o chat visível está vazio; a fila não).

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

Critérios 1 a 5: manual, com PC real.

## Tests stage 2 writes (own commit, red)

- `android/.../test/ProtocolClientTest.kt`: envio de arquivo em stream emite progresso crescente e termina em 100; falha no meio deixa o Item Pendente; fila gravada em disco é relida ao recriar o cliente e drenada em ordem. Vermelho porque envio de arquivo e fila não existem.

## Comments

- 2026-09-20 (revisão de SYN-007, para quem for implementar a fila em disco):
  hoje uma recusa do PC trava a fila. `ProtocolClient.retryPending()` faz
  `peek()` e, se a resposta não for 201, lança `ProtocolRejectedException`
  com o Item ainda na cabeça da fila — daí em diante todo `sendText` lança
  antes de chegar na rede, nada mais é entregue, e o texto daquela chamada
  se perde (não entra na fila). Cenário real: o Par rotaciona o token
  (SYN-005) enquanto há Pendente; o PC volta e responde 401. O critério 5
  ("ao voltar para a rede do PC eles são entregues sem ação do usuário")
  cobre o caso de indisponibilidade, não o de recusa — decidir aqui o que
  acontece com um Item recusado (descartar, marcar como falho na bolha,
  pedir re-pareamento) e escrever o teste junto com a fila em disco.
- 2026-09-20 (revisão de SYN-007): `ProtocolClient` não é thread-safe. O
  `ConcurrentLinkedQueue` protege a fila, mas `sendText` drena e então
  envia sem exclusão nenhuma, então duas threads intercalando furam a ordem
  que o próprio SYN-007 consertou. Sem chamador concorrente hoje; o serviço
  em primeiro plano deste ticket é o primeiro. Vale um lock em volta do par
  drenar/enviar quando o serviço entrar.

- 2026-09-20 Attempt 1 failed: exit 0. Log tail: **SYN-008 is `Stage: blocked` on branch `syn-008`**, off the session branch. /  / Delivered (gate green, red-before-green proven): `ProtocolClient.sendFile()` — streams a file to `/items/file` with 0→100 progress, no full-file buffering, and the same never-fork-the-queue policy as `sendText`. The in-memory pending queue became a `PendingQueueStore` seam with a real disk-backed implementation, so Pendentes (text and file) survive recreating the client — the exact seam the ticket named in "Tests stage 2 writes." /  / Blocked on the rest: intent filters, the foreground service, and chat-bubble UI need a real Android app module (`com.android.application`), which needs the Android SDK — not installed on this machine. This is the identical wall SYN-007 hit, where you already said not to install it. I left the question on the ticket rather than guess: install the SDK now and I'll build the app shell, or keep that decision and split SYN-008's remaining criteria into a follow-up ticket once a build machine has the SDK. /
