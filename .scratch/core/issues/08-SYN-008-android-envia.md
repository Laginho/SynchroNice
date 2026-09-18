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
